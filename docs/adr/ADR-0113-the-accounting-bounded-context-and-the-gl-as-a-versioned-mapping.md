# ADR-0113 — The `accounting` bounded context: the General Ledger as a versioned mapping over the one journal, no second ledger, no ledger edge

Status: Accepted (2026-11-06, `P14-DOC-001` — read against the code and corrected first)
Date: 2026-10-29
Phase: 14
Context: Accounting / General Ledger · Reporting · Ledger
Supersedes: nothing. Closes `ROADMAP.md` Refinement 2's last seam ("GL account mapping", Phase 3 → 14) and the anticipated
ADR "Operational-ledger-to-GL mapping". Applies ADR-0002 (the journal is the authoritative record), ADR-0040 (a flat
ledger account with a typed classification; the GL mapping Phase 14's) and ADR-0107 (a module reaching every other
domain through ports implemented in `app`). Rests on `PHASE_14_PLAN.md` §1, §3, §4, §13.1 and `INV-ACC-06`, `-07`, `-14`.

## Context

1. The ledger is complete as an economic record: every economic event posts once, balanced per currency, in its source
   domain's transaction (`INV-LED-01`, `-04`), classified by purpose (40 values) and by the posting key's namespace in
   `idempotency_scope`. What does not exist is an accounting representation: GL accounts, statement classification,
   periods, reports.
2. `ledger_account.gl_code` was left nullable and unwritten as "the Phase 14 seam" (ADR-0040), deliberately unfrozen.
   Writing it would be an `UPDATE` of a ledger table by a reporting concern, unversioned — a mapping change would rewrite
   history silently (`INV-ACC-04`).
3. Product books create accounts at runtime (six per loan, six per BNPL obligation): a per-account mapping would be
   unbounded and never total.

## Decision

1. **One module, `accounting`**, schema `accounting`, owning contexts Accounting / General Ledger (25) and Reporting (27),
   depending on `platform` and `sharedkernel` only. **No edge to `ledger` or any business module**: the journal is read
   through `JournalReads`, the ledger's chart through `LedgerChart`, the cutoff commanded through `PostingCutoff`, a
   prior-period adjustment posted through `PriorPeriodPostings` — each a port implemented in `app`
   (`AccountingModuleIsolationTest`).
2. **The GL is a function, not a store.** A GL figure = Σ journal lines (posting date in the period, currency) whose
   account's purpose (and owner kind) maps to the GL account under the version pinned by the period. No accounting
   table holds a balance or a line amount except frozen output, never truth: the content of a report run, and a
   regulatory submission's content and lines, copied from `FINAL` runs and held equal to them (`INV-ACC-06`).
   *(Corrected by `P14-DOC-001`, the Phase 14 exit review, 2026-11-06: this read "except the frozen content of a
   report run". Accounting `V010` (`P14-TSK-018`) also holds amounts: `regulatory_submission.content` (the attested JSON,
   amounts as minor units, `V010:205`) and `regulatory_submission_line.minor_units` (`V010:355`), drawn only from `FINAL`
   runs (`V010:409`) and held equal to the content (`regulatory_submission_lines_agree`). The exception is widened, not
   the decision: both are a return's record of what it said, never truth. `V001`'s schema comment and
   `accounting/build.gradle.kts` still say "no table holds a balance or a line amount"; they are left as written.)*
3. **The mapping is versioned data keyed by ledger purpose (+ owner kind)**; a counterparty is a dimension, not an
   account. `gl_code` stays unwritten; a later ledger change may retire it. A version contains its GL accounts (code,
   name, classification, statement section, normal balance) and its rules; it is immutable from birth, activated under
   four eyes, effective from an `OPEN` period only, total over `AccountPurpose` and classification-compatible with each
   purpose's ledger type — refused at activation otherwise (`INV-ACC-07`).
4. **Accounting writes the journal only through the ledger's own doors**: the `ACCOUNTING`-origin adjustment
   (prior-period, four-eyes) and nothing else; write-offs (C6) are lending's and bnpl's postings.
5. **Reporting never writes** (`INV-ACC-14`): every report and drill-down read runs in a `REPEATABLE READ READ ONLY`
   transaction; `ReportingNeverWritesTest` refuses any writer type reachable from report code. A separate database role is
   Phase 15's (owner decision C10).

*As built by `P14-TSK-013` (2026-11-04), decision 4 - nothing here is a different decision:* `PriorPeriodPostings` holds five methods on the caller's transaction (classify, propose, approve, reject, linesOf), and `app`'s `LedgerPriorPeriodPostings` implements them over `AdjustmentService`'s accounting-owned door, the one class that may. The ledger's refusals pass through as its own types: accounting has no edge to catch them by. Accounting still has no ledger edge (`AccountingModuleIsolationTest`, unloosened), and neither reporting nor the break machinery reaches the port (`ReportingNeverWritesTest`, `BreaksNeverPostTest`, unloosened).

*As built by `P14-TSK-001` (2026-10-29), the boundary only - nothing here is a different decision:* decision 1 holds as
written (`AccountingModuleIsolationTest`: `platform` and `sharedkernel` only, every business module, `risk` and `app`
refused; every sibling's isolation test refusing `accounting`), the nine ports declared with no methods, each shaped by the
task that implements it. Decision 5's "report code" is made precise: every class of `com.finapp.accounting.reporting..`,
whose reach inside `accounting` may touch no `ledger` type, no writer port (`PostingCutoff`, `PriorPeriodPostings`,
`RegulatoryReportingProvider`), no platform writer (audit, outbox, idempotency store) and no JDBC write - so the run store,
the audited read and the door live outside that package (`P14-TSK-008`). The trust split the decisions rest on
(`PHASE_14_PLAN.md` §12, C12) is built as a partition: `ACCOUNTING_READ` is the `ACCOUNTANT`'s alone, because the plan's
three-way grant failed the platform's pairwise-disjoint grants (`INV-AUD-03`).

*As built by `P14-TSK-003` (2026-10-30), decision 3 - nothing here is a different decision, two things are made precise:*
accounting `V002` holds the version, its GL accounts and its rules as decided (immutable from birth, four-eyes by `CHECK`,
persons only, one `ACTIVE`, the activation under namespace `14` key `mapping`). **"Effective from an `OPEN` period only"**
is held, until periods exist, as "a calendar month never earlier than any version already activated" (Java and the edge
trigger for every writer); `P14-TSK-005` re-reads it against the period row. **"Classification-compatible with each
purpose's ledger type"**: the ledger fixes no type per purpose, its accounts carry one each, so `LedgerChart` answers a
purpose's types as its accounts hold them joined by the type its owning module declares for accounts opened at runtime;
compatible means exactly one type, equal by name to the GL account's classification - a contradiction (two types) is
refused, never passed. The reference chart (test fixtures, 35 GL accounts over the forty purposes) is the "reference
values for qualified review" of the Follow-up; no migration activates it.

*As built by `P14-TSK-007` (2026-10-31), decisions 2 and 5 - nothing here is a different decision, four things are made
precise:* (1) **"A GL figure"** is a period's **movement** per GL account × currency - debits, credits, the net on the
account's normal side (`netMovement`), the line count and the components by purpose, owner kind and counterparty (the dimension) -
recomputed from the journal on every read; the cumulative opening and closing balances are the trial balance's
(`P14-TSK-009`, a FINAL run), so a live read never re-aggregates the journal from inception. (2) **"The version pinned
by the period"** is resolved by `GlPins`: a `LOCKED`/`CLOSE_PROPOSED`/`CLOSED` period's pin, else the version in force
for the month (equal by construction - the lock pins `inForceFor`, and no activation reaches a locked month); none, or
a period locked with no version, is `accounting.NoActiveMapping` (409). (3) **The fold is a partition, and proves it in
every answer**: `GeneralLedger` (in `com.finapp.accounting.reporting`, so `ReportingNeverWritesTest` now guards real
report code) folds `JournalReads`' aggregates through the version's rules - a map, so every line lands in one GL account
or in an explicit unmapped bucket - and returns, per currency, the GL's debits, credits and lines beside the ledger's own
totals over the same range, read by a statement that joins no account; a figure's drill-down selects exactly its keys,
currency and range, keyset-paged on ledger `V029`'s `journal_entry (posting_date, id)`. (4) **Decision 5's read-only
transaction** is a connection of its own per request, `REPEATABLE READ` and read-only, **verified by its first
statement** (fail closed), every statement bounded by `statement_timeout` armed by the second (a cancelled one `accounting.ReadTimedOut`), always rolled back; the audit record `accounting.GlRead` is written in a separate transaction
after the read and before the answer. `JournalReads` is `app`'s `LedgerJournalReads` (SELECT only; lines carry owner
references, never names, never the entry's `reference` - free text on a `MANUAL` adjustment, an owner item - reason or actor). `finapp.accounting.gl.unmapped` is fed from the database
(the open period's lines the active version does not map; 0 without a journal read when the version is total).

## Alternatives Considered

### A GL journal in `accounting` fed by ledger events
Pros: familiar ERP shape; GL-only entries easy. Cons: a second writable record competing with the ledger; events are not
truth (`INV-EVT-02`); drift between the two is the very risk `DELIVERY_PLAN.md` names. Rejected.

### Populate `ledger_account.gl_code`
Pros: no new tables. Cons: an unversioned `UPDATE` of ledger rows; per-account, never total; a mapping change rewrites
history. Rejected.

### `accounting` with a direct `ledger` edge
Pros: fewer ports. Cons: the reporting module could reach `PostingService`; the "no write access" rule becomes a review
question. Rejected (the `risk` precedent).

## Consequences

Positive: one financial truth; GL always reconcilable to the ledger by construction; mappings auditable and pinned.
Negative: GL queries aggregate journal lines (an index on `posting_date`, ledger `V029`); GL-only entries need the ledger's
door. Operational impact: the first mapping version is activated after deployment, under four eyes. Security impact:
reporting reads only. Financial impact: none on balances.

## Invariants / Constraints

`INV-ACC-02`, `-04`, `-06`, `-07`, `-14`; `INV-LED-04`; `INV-EVT-02`; `INV-HIST-04`.

## Follow-up

Retire `gl_code` by a ledger migration if no use appears. A separate reporting role (Phase 15). The chart's codes and
sections are reference values for qualified review before production.

**Dated note (2026-11-08, `P15-TSK-021`; `PHASE_15_PLAN.md` C13, ratified 2026-11-07): the separate reporting role is
built.** Report generation (`ReportRunGenerator`: a run's generation and a FINAL run's regeneration) reads through its
own pool as `finapp_reporting`, which holds `SELECT` on the 23 tables a report run reads - recounted from the code: 10
accounting, 5 ledger, 3 reconciliation, 5 lending; wider than the plan's "the journal and accounting tables", because
the suspense definition, the statements' notes and the safeguarding return read reconciliation and lending - and
nothing else, granted by accounting `V013`, ledger `V033`, reconciliation `V023` and lending `V034`, pinned by
`ReportingRoleIsSelectOnlyTest`. So decision 5 (`INV-ACC-14`, reporting never writes) now holds at the privilege level
too: a report path that lost its `READ ONLY` transaction still cannot write. The pool is counted in the connection
budget (the writer's pool gave it one of its eight connections), is not on the readiness path, and never falls back to
the application's role. The operational door, the close checks and the final statements still read through the
application's role in their `REPEATABLE READ READ ONLY` snapshots. The role is a cluster object, created by the local
role script and, in a deployed database, by the one-time bootstrap step (`P15-TSK-004`) before the migrations that
grant to it. No new ADR: the decision is this ADR's follow-up, taken as C13 framed it.
