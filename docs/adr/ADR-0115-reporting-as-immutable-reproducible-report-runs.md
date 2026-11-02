# ADR-0115 — Financial reporting as immutable, reproducible report runs over a pinned journal cut

Status: Proposed
Date: 2026-10-29
Phase: 14
Context: Reporting · Accounting / General Ledger
Supersedes: nothing. Writes `DELIVERY_PLAN.md` §Phase 14's anticipated "ADR on reporting read-model architecture".
Rests on `PHASE_14_PLAN.md` §13.6 and `INV-ACC-02`, `-04`, `-05`, `-14`, `INV-BAL-05`.

## Context

The platform's reports today are live operator reads (lending, bnpl, reconciliation, merchant, risk) — correct as of the
moment, never issued, never reproducible. Financial reporting needs issued outputs that answer the same question the
same way forever, and a trail from every figure to its journal lines.

## Decision

1. A **report definition** is code: a kind (`TRIAL_BALANCE`, `GL_ACTIVITY`, `BALANCE_SHEET`, `INCOME_STATEMENT`,
   `REVENUE`, `RECEIVABLES_PAYABLES`, `SETTLEMENT_POSITIONS`, `SUSPENSE`, `FX_RESULTS`, `LOAN_PORTFOLIO`, `BNPL_EXPOSURE`,
   `RECONCILIATION_STATUS`, `CLOSE_STATUS`, regulatory forms) with a version constant.
2. A **report run** is a row born once per `(definition, definition_version, period, revision, parameters_hash)`: claimed
   (insert-or-read), generated in a `REPEATABLE READ READ ONLY` transaction, completed by a conditional edge with its
   content (`jsonb`) and `content_sha256`, frozen by trigger. It records its inputs: the period and its revision, the
   mapping version, the valuation rate set, the journal cut, the definition version.
3. **FINAL** runs exist only for `LOCKED`/`CLOSED` periods — whose posting-date range is immutable after the cutoff
   (ADR-0114) — so regeneration reproduces the hash (`INV-ACC-04`); `…/regeneration` answers `IDENTICAL | DIVERGED`, a
   divergence metered and alerted, never repaired. **PROVISIONAL** runs (open periods) are cut at the journal's settle
   horizon (the ADR-0111 keyset), labelled, never issued. A reopen marks FINAL runs `SUPERSEDED`; never deleted.
4. **Drill-down**: every figure carries its (GL account, currency, period) key; the GL activity door lists the journal
   lines composing it, paged (`INV-ACC-02`).
5. **Authoritative vs operational**: the existing live reports and gauges stay operational and say so; no cache,
   projection or gauge is the source of a FINAL figure (`INV-BAL-05`).

## Alternatives Considered

### Materialised GL balance tables refreshed by a job
Pros: fast reads. Cons: a projection that can drift, a second set of balances. Rejected; aggregates computed per run.

### Regenerate on demand without storing output
Pros: no storage. Cons: no proof of what was issued. Rejected.

## Consequences

Positive: issued outputs provable byte-for-byte. Negative: generation cost per run (bounded by the posting-date index).
Operational impact: lease-based completion by `AccountingCloseSchedule`. Security impact: content `CONFIDENTIAL`, reads
audited. Financial impact: none.

## Invariants / Constraints

`INV-ACC-02`, `-04`, `-05`, `-14`; `INV-HIST-04`; `INV-BAL-05`.

## As built

*(`P14-TSK-008`, 2026-10-31, accounting `V004` - the machinery; no production definition yet.)* **The run**
(`accounting.report_run`): the plan's key verbatim, `UNIQUE (definition, definition_version, period_id, revision,
parameters_hash)`, where `revision` is the **run's** own - 1, then one more each time the latest run can no longer answer
its question (`FAILED`, `SUPERSEDED`, a completed PROVISIONAL snapshot, a run of the other basis); the period's revision
is an input beside it (`period_revision`). The claim (T-claim) holds the period row `FOR SHARE`, fixes the basis (FINAL
for a period no longer `OPEN`, PROVISIONAL for an `OPEN` one), and births the next revision by `INSERT … ON CONFLICT ON
CONSTRAINT report_run_key DO NOTHING` plus the read-back - ten claimers, one row. A FINAL run records **the lock it read
under** (`period_locked_at`, the period's `locked_at`) and the version that lock pinned; a PROVISIONAL one the version in
force and its cut (`cut_at`, the claim's database clock). **Generation** (T-gen) runs on a separate pooled connection
whose first statement is `SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY`, proven by `current_setting`
before any report code reads (fail closed), always rolled back (`app`'s `JdbcReportSnapshots`); the period is re-read in
the snapshot and a FINAL run whose lock moved is `PERIOD_MOVED`. **Completion** (T-complete) re-takes the period
`FOR SHARE` and makes one conditional `UPDATE … WHERE status = 'GENERATING' AND attempt = :held` carrying status, content
and hash, with `accounting.ReportGenerated` and `accounting.ReportRunCompleted` in the same commit. **The lease**: a
generator holds the run at its attempt; past `lease_until` (database clock, default 5 minutes) any instance reclaims it by
the conditional `attempt + 1` - never a second run; the dead attempt's completion moves nothing; past the bound (default
5) the run fails `ATTEMPTS_EXHAUSTED`. The orphan leg is the callable `ReportRunGenerator.completeOrphaned(limit, actor)`
(`FOR UPDATE SKIP LOCKED` + the reclaim); `P14-TSK-011` schedules it.

**Three corrections, recorded.** (1) **The content is stored as its canonical text, not `jsonb`**: `jsonb` re-orders and
re-spaces, so it would not retain "the originally issued output" (`INV-ACC-04`) and the database could not check the hash.
The text is `CanonicalJson`'s (sorted keys in UTF-16 order, no whitespace, **no JSON number** - amounts as minor units in
strings beside currency and scale - ECMAScript escaping; `U+0000` and unpaired surrogates refused), checked a JSON object
by `CHECK (jsonb_typeof(content::jsonb) = 'object')`, and `content_sha256` is **recomputed by the database** for every
writer (`encode(sha256(convert_to(content, 'UTF8')), 'hex')` - the risk `V006`/`V007` precedent); queries read
`content::jsonb`. Fixed test vectors pin the bytes and hashes (`CanonicalJsonTest`). (2) **The unlock supersedes**:
decision 3 superseded FINAL runs only at a reopen, but `P14-TSK-005`'s four-eyes unlock already returns a `LOCKED` period
to `OPEN` without a new revision - a FINAL run left standing would stop reproducing as postings land. So
`AccountingPeriods.approveUnlock` supersedes the period's FINAL runs in its own transaction, before the edge (the port
`ReportRunSupersession`, which `P14-TSK-012`'s reopen reuses), and `V004`'s trigger
`accounting_period_reopens_with_its_runs_superseded` refuses any return to `OPEN` that leaves one - for every writer.
(3) **The freeze is its own trigger** (`report_run_content_is_frozen`, firing before the machine's by name), so its
refusal is its own. The valuation rate set is pinned as `valuation_rate_set_id uuid NULL` with **no foreign key** (the
set's table is `P14-TSK-014`'s `V005`, landing after) *(keyed by `P14-TSK-014`'s `V005` at its integration, 2026-10-31: `report_run_valuation_rate_set_fk`)* and is a hashed parameter of the question. Doors:
`POST /v1/operator/accounting/report-runs` (keyed, `ACCOUNTING_REPORT_GENERATE`; the run, never its content),
`GET …/{runId}` (`ACCOUNTING_READ`; the content, recorded `accounting.ReportRead` before it is served),
`POST …/{runId}/regeneration` (keyed; `IDENTICAL | DIVERGED`, recorded `accounting.ReportRegenerated`, a divergence
counted `finapp.accounting.report.diverged`, never repaired). `P14-TSK-012`'s close must keep `locked_at` through
`CLOSE_PROPOSED` and `CLOSED` (V003's `accounting_period_lock_coherent` already requires it): it is the lock instance a
FINAL run reproduces under.

**The independent review's fixes** (2026-10-31, before the merge). *I-1 - a time bound*: a lease cannot be renewed, so a
generation that outlived it would be overtaken by its own reclaim at every attempt; the generation is now bounded below
the lease - `finapp.accounting.report.generation-timeout` (default 4 minutes; refused unless it leaves a fifth of the
lease) is armed as the snapshot's `statement_timeout` and re-armed to what is left before every statement, and a spent
budget or a cancelled statement (`57014`) fails the run `GENERATION_TIMED_OUT`, inside its lease. *I-2 - the write paths
closed*: a definition is handed a `ReadOnlySnapshot` view, not the raw connection - single `SELECT`/`WITH` statements
only (no `;`, no `set_config`, `pg_advisory*`, `pg_notify`, `pg_sleep`, `dblink`, `lo_*`), executed by
`executeQuery()` only, every connection mutator, plain or callable statement, statement or result write and `unwrap`
refused; `ReportingNeverWritesTest` refuses `DataSource`, `DriverManager` and the `Connection` mutators in report code.
*M-1*: past the attempts' bound, a failure that moved nothing re-reads the run and returns it if it still answers - never
a second FINAL run. *M-2*: the unlock supersedes before the cutoff moves, under the period's `FOR UPDATE`, and the content
checks (bounded, a JSON object, the hash its own) run at the completion edge instead of as `CHECK`s every later `UPDATE`
would re-run; the freeze fires only for an `UPDATE` naming the content, its hash or its completion. *M-5*: the
regeneration looks its key up first (the platform's two-transaction command), so a replay opens no snapshot.

*(`P14-TSK-009`, 2026-11-01, no migration - the first definition.)* **`TRIAL_BALANCE` v1** (`TrialBalanceDefinition`,
declared by `app`'s `TrialBalanceBeans`): per GL account × currency the opening (a cumulative fold of every line before the
period, from inception, under the period's own version), debits, credits and closing, debit-positive; every line the
version does not map in an explicit unmapped row; per currency **zero in three legs** - the opening's debits = credits,
the period's, and cumulatively through the end with the closing balances footing (`INV-ACC-01` reported, never
corrected; "cumulative and for the period", `CloseCheckCode.LEDGER_TRIAL_BALANCE`) - and **equal to the ledger** over
three windows (before, within, through the end) read by a `TrialBalance.sweep`-shaped statement that joins no account,
**bounded by the period's row** read in the same snapshot and bound as values (the `ledger` statement names no
`accounting` table, ADR-0006), so a wrong bound is caught for the trial balance's own reads and `Range.of` (`-007`'s M-6;
`-007`'s GL-door statements stay `P14-TST-002`'s; the opening's bound is checked by the ledger comparison, not by
`rangeIsThePeriods`); the verdict at `{body,verdict,passes}`. The output scale per currency is the data's own - no JDK
currency table decides a FINAL run's bytes (ADR-0003). A FINAL run reads its period's pin and refuses another version.
**The reclassification bridge**: every row - the opening included - is under the run's version, so when the previous
period pinned another version the opening **restates** that period's closing per GL account (the per-currency totals
still agree; figures move between accounts); the content names the previous period's pin and `openingReclassified`, and
the statements (`-016`, `-017`) owe the bridge from the previous closing to this opening. **Two refinements of decision
3 for PROVISIONAL runs** (`-008`'s review, M-8): the definition counts only entries **created at or before the run's
cut** (`created_at <= cut_at`) - approximately the journal as asked, whenever generated, and fuzzy both ways (the posting
instance's clock, minted before its commit; entries in flight at the snapshot absent) - a deviation from decision 3's
"cut at the journal's settle horizon", whose alternatives are `cut_at` less the settle horizon (ADR-0111's shape) or
labelling the run with its snapshot's instant; no false divergence follows, only FINAL runs regenerate; and a completed
PROVISIONAL run **keeps answering its question** while its cut is younger than
`finapp.accounting.report.provisional-freshness` (default 15 minutes) on the database's clock and its period's revision
is unchanged - at most one PROVISIONAL row per question per window (an owner item: the window, against a cap or a
retention sweep). *The independent review* (2026-11-01, `MERGE`, applied before the merge): I-1 the period and opening
legs, M-3 the data's scales, M-4 the bounds bound as values, M-5 the bridge, M-8 a javadoc - fixed; M-1, M-2, M-7
recorded; M-6 routed to `P14-TSK-011` (`passes` is not the whole close: `GL_EQUALS_LEDGER` fails an unpinned or
not fully mapped period; the run checked FINAL and of its code and version).

*(`P14-TSK-017`, 2026-11-02, no migration - the operational reports.)* **A definition now says whether it can keep the promise.** Decision 3
reads as if every definition's FINAL run were reproducible; that holds for what a locked period makes immutable, and two of the ten read what
keeps moving under one lock. `ReportDefinition.operational()` (default false; `OperationalReportDefinition` true) names them, and **an operational
definition is never a run**: `ReportRuns.claim` refuses it before anything is read or locked (`DefinitionIsOperational`, `409
accounting.ReportDoorMismatch`); `OperationalReports` generates it through the same `ReportGeneration` - canonical form, sha256 - on a
verified snapshot under the door's own bound (`OperationalDoorBound`: 30 seconds, 2 permits per instance, the next refused `503`), its header's `basis` `OPERATIONAL`, its cut the database's instant, nothing stored but the read's audit record
(`accounting.OperationalReportRead`), at `GET /v1/operator/accounting/periods/{periodId}/operational-reports/{definition}`. **Why not a run of
either kind**: `V004`'s birth trigger holds a PROVISIONAL run to an `OPEN` period and `report_run_basis_coherent` a completed run's status to its basis,
so a run of a locked period can only be a row saying `FINAL`; and `ReportRuns.answers` keeps a FINAL run answering for the life of its lock, so
`CLOSE_STATUS` would say nine checks `PENDING` for good. Why no migration: `V008`-`V010` are reserved and Flyway runs in order, and a report that
stores nothing needs none. Nothing moved for any other definition: its question, key, basis and bytes are as they were. **The eight FINAL
definitions**: `PurposeReportDefinition` (six, tables of purposes over `TrialBalanceReads`, the trial balance's own statements, with
`CounterpartyPositionReads` beside them wherever a purpose is a counterparty's: nothing nets between counterparties, `INV-RAIL-04`; and a section
totals only figures of one normal side), `GlActivityDefinition`, `SuspenseDefinition`. Their bodies write `ReportDefinition.statement()`, whose five facts the method's abstractness and `ReportStatement`'s
constructor make unomittable (`TRIAL_BALANCE` states them too and writes none: version 1's bytes are fixed). **Held statically**:
`ReportingNeverWritesTest` - no definition has a path to `GeneralLedger` (`P14-TSK-015`'s review, M-6: the fold's `zero` is a JDK's table, the exception's
reach closed); only an operational definition reads what moves; a report port's adapter writes nothing and holds no product's reports. **`SUSPENSE`'s register
is FINAL by a new read**: `ReportReadings.eachSuspenseItemBornAsOf` narrows the as-of read to items whose owning break was raised before the
instant, because an adopted parking's `opened_on` is its entry's posting date (ADR-0070 section 2) and it would otherwise join a locked month; the disclosure's
read is unchanged, the close wanting that item as `suspenseUnowned`. **The reclassification bridge lands in `GL_ACTIVITY`**: `Reclassification.between`, a
pure fold of the opening's aggregates through both versions - each moved purpose's balance, per counterparty where it has them, per GL account the previous closing beside this opening, footing
to zero per currency - exact because the lines before this period's first day are the lines through the previous period's last, and read only
when the versions differ; `-016` may call it as it stands. **Costs**: the heaviest statement of a balance report is `TrialBalanceReads.BEFORE`, from inception,
`journal_entry_by_posting_date`, the trial balance's own cost; of `RECONCILIATION_STATUS` the past-the-cutoff scan, the same index from the cutoff
forward, then one probe of `audit_record_by_target` per entry it keeps; a run inside the 4-minute budget, a read at the door inside 30 seconds, past it `503`, never a
partial answer. A long-past month may no longer answer at the door, and says so: an owner item. **Version 1's bodies changed in `P14-TSK-017`'s fix round and stay version 1, because
no FINAL run has been issued anywhere.**

## Follow-up

Retention of runs with Phase 15's data-retention work; export formats beyond JSON. *(`P14-TSK-009`: a chained
opening - the previous FINAL trial balance's closing, sound only under one version and a kept `locked_at` - if the
cumulative fold's cost grows; consolidating `GlSnapshots` and `JdbcReportSnapshots`.)* *(`P14-TSK-017`: a stalled check leg after the run's birth - an
attempt nobody claims - shows only in `period.open.age` and `close.leg.failed`; a labelled operational run of a locked period, if storing them is ever wanted, is
a change to `V004`'s trigger.)*
