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
6. **Back-dating posters are made explicit** (C3, ratified by the owner 2026-10-30, "Floor to first open date"): the
   ledger refuses an entry dated into a locked or closed period; every **system** back-dating poster (lending's
   accrual; the settlement report and statement; the merchant-payout return; reconciliation suspense; `fx-trade`;
   `fx-cover`) posts on the first open date and keeps its economic/value date; a **person's** adjustment (`MANUAL`, or
   a reconciliation resolution's) dated into a locked period is refused at approval and stays `PROPOSED` - re-proposed
   in the open period or made a prior-period adjustment; close readiness requires accruals complete through the
   period end. `P14-TSK-002`'s register classifies every poster's dating and names each rule's reaction. *(Read "the
   ledger refuses; lending's accrual floors its posting date at the first open date and keeps the accrual date as
   value date" until the ratification widened it to the eleven back-dating rules.)*

## Alternatives Considered

### Check in `PostingService` only (application)
Pros: simple. Cons: not serialised with the close; raw SQL bypasses it. Rejected.

### `SELECT … FOR SHARE` on the cutoff row in the trigger
Pros: row-level, visible in `pg_locks`. Cons: a multixact on one hot row for every posting; same semantics as the
advisory shared lock at higher cost. Rejected in favour of namespace 14.

### The ledger silently re-dates a back-dated posting
Pros: no producer change. Cons: an undisclosed alteration of a domain input. Rejected (C3). *(The floor built by
`P14-TSK-006` is not this: the poster asks per command, its rule registers the reaction, and the entry's audit record
names the date it stated - see As built.)*

## Consequences

Positive: closed periods are immutable by construction, so FINAL reports are reproducible. Negative: the lock briefly
queues postings behind the longest in-flight posting transaction; lending's accrual rule gains a floor. Operational
impact: lock under `lock_timeout` with retry; a refused back-dated posting is metered and alerted. Security impact: lock,
unlock, close and reopen are four-eyes and audited. Financial impact: none on balances.

## Invariants / Constraints

`INV-ACC-03`, `-08`, `-09`, `-10`; `INV-LED-03`; `INV-REV-04`; `INV-AUD-04`; `INV-IDEM-02`.

## As built

`P14-TSK-004` (2026-10-30), decisions 1-2: ledger `V028` (`posting_cutoff`, unset until the first lock;
`posting_cutoff_event`, append-only; the `BEFORE INSERT` trigger on `journal_entry` under namespace `14` key `cutoff`
shared, SQLSTATE `LDCUT` → `ledger.PeriodClosed`), `PostingCutoffs` (the key exclusive, the conditional `UPDATE`, the
event; replay `UNCHANGED`), accounting's `PostingCutoff` port and `app`'s `LedgerPostingCutoff`. Beyond the decision text:
the trigger reads `FOR SHARE` only under REPEATABLE READ/SERIALIZABLE (a cut after the poster's snapshot is `40001`, not
a miss - the rejected alternative's cost paid by those posters alone); "one period back" is the value the latest advance
into the cutoff came from (unset after the first lock), judged by a `BEFORE UPDATE` trigger that binds every updater, with
a deferred trigger refusing an unevented move; the cutoff never covers today (UTC); the move's wait for the exclusive
key is bounded in the ledger (`lock_timeout` 2 s per attempt inside a savepoint, five attempts, the caller's timeout
restored), so a waiting move holds new postings for at most one attempt. Decision 6 (C3) is untouched: the
ledger refuses and nothing else changed. Status stays `Proposed` until the phase's exit review.

`P14-TSK-005` (2026-10-30), decision 3's open, lock and unlock: accounting `V003` (`accounting_period`, one per month,
contiguous, at most one `LOCKED`/`CLOSE_PROPOSED`; `period_event`, append-only), `AccountingPeriods` over
`JdbcAccountingPeriodStore`. The lock: the previous period `FOR SHARE` then the period `FOR UPDATE`, in order and after
the grace (C9: N business days after the month end, default 2), namespace `14` key `mapping` **shared**, the version in
force pinned (none when none is), the cutoff advanced through `PostingCutoff`, the conditional edge, history, audit and
`accounting.AccountingPeriodLocked` - one transaction; a second locker converges on `LOCKED`. The unlock: a proposal on
the `LOCKED` row, a second person's approval moving the cutoff back one period (approver ≠ proposer by `CHECK`). Beyond
the decision text: the mapping-key arbiter for the pin (the activation re-reads the periods under `mapping` exclusive and
refuses a month at or before a locked period - V003 replaces V002's edge function with the same rule); a busy cutoff key
crosses the port as `PostingCutoff.CutoffBusy` (`503 accounting.CutoffBusy`, nothing stored under the key). Decision 6
(C3) still untouched.

`P14-TSK-006` (2026-10-30), decision 6 as ratified: **the floor is the posting command's, on the poster's explicit
request** - `PostingCommand.Dating.FLOOR_AT_FIRST_OPEN`, a sixth record component (the five-argument constructor means
`AS_STATED`, so no other poster changed). In the posting's own effect - after the idempotency claim, one statement
before the insert - `PostingCutoffs.firstOpenOnOrAfter` takes namespace `14` key `cutoff` **shared** (held to commit,
re-entrant for the trigger), then reads `closed_through` (`FOR SHARE` under REPEATABLE READ/SERIALIZABLE, the
trigger's own rule), and the entry posts on `max(stated, closed_through + 1)`; the value date, the lines and the key
are untouched, the audit record says `flooredFrom=<stated>`, and `finapp.accounting.cutoff.floored{command}` counts
it. **Why the ledger, and why on request**: only the ledger can read the cutoff under the key its insert's refusal
takes - six producers each reading it first would be context 1's "a check in each caller" six times - and only the
ledger can keep the key's fingerprint on the **stated** date (the canonical form is unchanged and carries no dating),
so a replay converges on the first entry whatever the cutoff did since; a producer passing its own floored date would
conflict on a replay after the next lock. A poster racing a lock posts in the period before the move commits (the
move waits for its shared key) or reads the moved cutoff and floors - never refused. Seven rules ask (register version
2 from 2026-10-30): `LND-2`, `SETTLEMENT-REPORT`, `SETTLEMENT-STATEMENT`, `MERCHANT-PAYOUT-RETURN`, `RECON-SUSPENSE`,
`FX-TRADE`, `FX-COVER`. **The adjustments**: `AdjustmentService.doApprove` - both doors, the generic `MANUAL` approval
and the resolution's `approveOwned` - reads the cutoff the same way after the proposal's row lock and refuses a
covered posting date with `ledger.PeriodClosed` before anything is written (409; the proposal and a resolution stay
`PROPOSED`); an adjustment is never floored - its date is part of what was approved. `V028`'s trigger stays the
backstop for every writer. **The close's input**: accounting's `AccrualCompleteness` (lending's and bnpl's oldest
unaccrued date per currency, `blocking(periodEnd)`), implemented in `app` over lending's `AccrualLags` and bnpl's
`BnplAccrualStore`; `P14-TSK-011`'s `ACCRUALS_COMPLETE` reads it. No migration (the journal's three dates hold the
floor: posting date floored, value date the economic one; each source row keeps its stated date). The meter's tag
stays the command (a command still names no module).

*`P14-TSK-011`, 2026-11-02 - decision 4, the close run (accounting `V007`):* one run per lock instance, born by the schedule's
check leg insert-if-absent rather than in T-lock - **a recorded deviation** from the plan's T-lock: the lock is the ledger's
protection and waits for nothing, as with the pin and the rate set. Nine checks, each an attempt row with its evidence and the
versions it read, claimed by one `FOR UPDATE SKIP LOCKED` statement under a lease on the database's clock, judged with no
transaction held, concluded by one conditional edge naming the claim's `holder`. **A run of any length keeps its lease**: a short
lease, renewed by a heartbeat, bounded by `max-run` on the database's clock; a dead worker's check is reclaimed within one lease, a hung
one past the bound, and `reclaims` past its bound is `FAILED WORKERS_EXHAUSTED`. No path turns an exception, a timeout, an incomplete
proof or an empty read into a pass. **"Explicitly `DISCLOSED`"** is `close_check_disclosed_only_where_it_may`: the trial balance, GL = ledger,
the projections and the valuation set are `PASSED` or they block; a raising check is `DISCLOSED` only when acknowledged breaks hold every
finding as acknowledged (`ACKNOWLEDGED_CHANGED` otherwise, ADR-0117); the accruals when entries of either accruer lie past the cutoff and nothing is behind; the disclosure when read whole and not clean; the open breaks when
none is `OPEN` and some acknowledged. `C3`'s "close readiness requires accruals complete": `blocking` and `flooredPast` on one snapshot -
find and disclose, never `PASSED` on a period short of interest. `BNPL-F2a` has no floor and the same shortfall: it posts today valued the accrual date, so the month's last day
always posts after the month and a lagging sweep posts older dates there too; the read holds it beside `LND-2`, the last day apart from anything older, so a period with an active
pay-in-N obligation reads `DISCLOSED` and carving the last day out is the owner's (`P14-TSK-011`'s review, C-1). The grace's minimum raised to two business days (`LockGrace`), so a lock
never covers a western servicing zone's today (`P14-TSK-006`'s review, M-1). Proposal and approval are `-012`'s, reading
`CloseRuns.readiness`.

## Follow-up

The storm (PI-004) measures the lock's pause. Daily periods if ever needed (C9 B). The calendar grace is configuration.
