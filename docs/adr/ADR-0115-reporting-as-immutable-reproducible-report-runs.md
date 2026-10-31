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
set's table is `P14-TSK-014`'s `V005`, landing after) and is a hashed parameter of the question. Doors:
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
version does not map in an explicit unmapped row; per currency **zero** (cumulative debits = credits and the closing
balances foot - `INV-ACC-01` reported, never corrected) and **equal to the ledger** over three windows (before, within,
through the end) read by a `TrialBalance.sweep`-shaped statement that joins no account and **takes its bounds from the
period's row**, so a wrong range bound on either side is caught (`-007`'s M-6); the verdict at `{body,verdict,passes}`.
A FINAL run reads its period's pin and refuses another version. **Two refinements of decision 3 for PROVISIONAL runs**
(`-008`'s review, M-8): the definition counts only entries **created at or before the run's cut** (`created_at <=
cut_at`) - the content is the journal as asked, whenever generated; and a completed PROVISIONAL run **keeps answering its
question** while its cut is younger than `finapp.accounting.report.provisional-freshness` (default 15 minutes) on the
database's clock and its period's revision is unchanged - at most one PROVISIONAL row per question per window (an owner
item: the window, against a cap or a retention sweep).

## Follow-up

Retention of runs with Phase 15's data-retention work; export formats beyond JSON. *(`P14-TSK-009`: a chained
opening - the previous FINAL trial balance's closing, sound only under one version and a kept `locked_at` - if the
cumulative fold's cost grows; consolidating `GlSnapshots` and `JdbcReportSnapshots`.)*
