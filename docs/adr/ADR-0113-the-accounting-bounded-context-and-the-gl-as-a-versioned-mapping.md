# ADR-0113 — The `accounting` bounded context: the General Ledger as a versioned mapping over the one journal, no second ledger, no ledger edge

Status: Proposed
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
   table holds a balance or a line amount except the frozen content of a report run, which is output, not truth
   (`INV-ACC-06`).
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
