# ADR-0114 — Accounting periods, the posting cutoff enforced by the ledger, and the four-eyes close

Status: Proposed
Date: 2026-10-29
Phase: 14
Context: Accounting / General Ledger · Ledger · Lending (the back-dating accrual)
Supersedes: nothing. Writes the anticipated ADR "Accounting period and close model". Applies `LEDGER_MODEL.md` §2 (the
posting date decides the period), the namespace-2 trigger precedent (ledger `V009`) and the risk policy pin's
shared/exclusive advisory lock (namespace 13). Rests on `PHASE_14_PLAN.md` §6, §8, §13.5 and `INV-ACC-03`, `-08`…`-10`.

## Context

1. `INV-ACC-03` requires closed periods to reject postings at `DOMAIN + DB-CONSTRAINT`. Thirty-odd posting sites in eleven
   modules call `PostingService`; a check in each caller would be skipped by the next one.
2. Under READ COMMITTED a posting transaction can read "open", a close can commit, and the posting can commit after it —
   an entry in a period whose trial balance was already taken. The guard must serialise with the close, not just read it.
3. Most posters date "today"; lending's accrual dates the **accrual date** (`LoanServicing.java:432-433`), so a catch-up
   after a lock would post into it; BNPL's dates today with the accrual date as value date (`BnplAccruals.java:344`).

## Decision

1. **The ledger owns the cutoff**: `ledger.posting_cutoff` (one row, `closed_through date`) and the append-only
   `posting_cutoff_event`, written only by the ledger's `PostingCutoffs` API (advance by one period, move back by one
   period), called by `app`'s adapter in accounting's lock, unlock and reopen transactions.
2. **Enforced at insert**: a `BEFORE INSERT` trigger on `journal_entry` takes `pg_advisory_xact_lock_shared(14, <cutoff
   key>)` and then reads `closed_through`, refusing `posting_date <= closed_through` (→ `ledger.PeriodClosed`). The cutoff
   edge takes the same key **exclusive** before its conditional `UPDATE`. So once the cutoff commits, no entry dated in
   the period commits afterwards: every in-flight poster finished first, every later one sees the new cutoff
   (`INV-ACC-08`). Namespace `14` is reserved for accounting (keys `cutoff`, `mapping`).
3. **Periods** are calendar months of `posting_date` (C9), in `accounting.accounting_period`:
   `OPEN → LOCKED → CLOSE_PROPOSED → CLOSED`, reject back to `LOCKED`, unlock `LOCKED → OPEN` and reopen
   `CLOSED → OPEN` (latest only, `revision + 1`) each four-eyes; in order; every edge conditional and evented.
   `LOCKED` *is* the cutoff: the lock transaction advances the ledger cutoff and pins the mapping version atomically.
4. **The close is evidence, then approval** (`INV-ACC-09`): a close run of recorded checks (§13.5 of the plan), claimed
   leaderlessly; proposal only with every check `PASSED` or explicitly `DISCLOSED`; approval by a second holder,
   approver ≠ proposer by `CHECK`.
5. **After close, corrections are prior-period adjustments** in the open period (origin `ACCOUNTING`, reason
   `PRIOR_PERIOD_ADJUSTMENT`, four-eyes) or the owning domain's own correction, never re-dated into the closed period; a
   reopen supersedes the period's FINAL report runs and never deletes them (`INV-ACC-10`).
6. **Back-dating posters are made explicit** (C3): the ledger refuses; lending's accrual floors its posting date at the
   first open date and keeps the accrual date as value date; close readiness requires accruals complete through the
   period end. `P14-TSK-002`'s register classifies every poster's dating.

## Alternatives Considered

### Check in `PostingService` only (application)
Pros: simple. Cons: not serialised with the close; raw SQL bypasses it. Rejected.

### `SELECT … FOR SHARE` on the cutoff row in the trigger
Pros: row-level, visible in `pg_locks`. Cons: a multixact on one hot row for every posting; same semantics as the
advisory shared lock at higher cost. Rejected in favour of namespace 14.

### The ledger silently re-dates a back-dated posting
Pros: no producer change. Cons: an undisclosed alteration of a domain input. Rejected (C3).

## Consequences

Positive: closed periods are immutable by construction, so FINAL reports are reproducible. Negative: the lock briefly
queues postings behind the longest in-flight posting transaction; lending's accrual rule gains a floor. Operational
impact: lock under `lock_timeout` with retry; a refused back-dated posting is metered and alerted. Security impact: lock,
unlock, close and reopen are four-eyes and audited. Financial impact: none on balances.

## Invariants / Constraints

`INV-ACC-03`, `-08`, `-09`, `-10`; `INV-LED-03`; `INV-REV-04`; `INV-AUD-04`; `INV-IDEM-02`.

## Follow-up

The storm (PI-004) measures the lock's pause. Daily periods if ever needed (C9 B). The calendar grace is configuration.
