# ADR-0114 — Accounting periods, the posting cutoff enforced by the ledger, and the four-eyes close

Status: Accepted (2026-11-06, `P14-DOC-001` — read against the code and corrected first)
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
3. Most posters date "today"; lending's accrual dates the **accrual date** (`LoanServicing.java:436-437`), so a catch-up
   after a lock would post into it; BNPL's dates today with the accrual date as value date (`BnplAccruals.java:344`).
   *(Corrected by `P14-DOC-001`, the Phase 14 exit review, 2026-11-06: this read `LoanServicing.java:432-433`; the
   file has moved since (`P14-TSK-006`'s floor among the edits), and the posting command's stated date and value date
   are now lines 436-437.)*

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
   `OPEN → LOCKED → CLOSE_PROPOSED → CLOSED`, reject back to `LOCKED`; the close's approval, unlock `LOCKED → OPEN` and
   reopen `CLOSED → OPEN` (latest only, `revision + 1`) each four-eyes; the lock is one person's act, and a close's
   rejection may be its proposer's own (only an `APPROVED` close is bound to a second person); in order; every edge
   conditional and recorded as a `period_event` history row beside its audit record. Integration events are published
   only for the opened, locked, close-proposed, closed and reopened edges; an unlock (its proposal, approval and ends), a
   close's rejection and a reopen's proposal and ends are history and audit only.
   `LOCKED` *is* the cutoff: the lock transaction advances the ledger cutoff and pins the mapping version atomically.
   *(Corrected by `P14-DOC-001`, the Phase 14 exit review, 2026-11-06: this read "reject back to `LOCKED`, unlock
   `LOCKED → OPEN` and reopen `CLOSED → OPEN` (latest only, `revision + 1`) each four-eyes; in order; every edge
   conditional and evented". The lock has no second person (`AccountingPeriods.lock`, `POST .../{periodId}/lock` under
   `ACCOUNTING_CLOSE_PROPOSE` alone); a close's rejection may be the proposer's (`period_close_four_eyes`, `V008:170`,
   binds `APPROVED` only; `PeriodCloses.reject`). "Evented" is the `period_event` row; the outbox carries only
   `accounting.AccountingPeriodOpened` and `…Locked` (`AccountingPeriods.java:71,74`) and `…CloseProposed`, `…Closed`
   and `…Reopened` (`PeriodCloses.java:89,92,95`), as `P14-TSK-012`'s As built already said.)*
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
   value date" until the ratification widened it to the eleven back-dating rules.)* *(Corrected by `P14-DOC-001`, the
   Phase 14 exit review, 2026-11-06: the register now holds **twelve** back-dating rules - ten `STORED_ROW` and two
   `ECONOMIC_DATE` (`PostingRuleCatalogue.java`), since `P14-TSK-013` added `ACC-PRIOR-PERIOD` (catalogue version 3); the
   eleven above is the count at the ratification. Of the twelve, the seven system rules floor and the five adjustments -
   `MANUAL`, the resolution's three and the prior-period adjustment - are refused at approval.)*

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
impact: lock under `lock_timeout` with retry; a refused back-dated posting is metered and alerted. Security impact:
unlock, close and reopen are four-eyes, the lock one person's act, and every act audited. Financial impact: none on
balances. *(Corrected by `P14-DOC-001`, the Phase 14 exit review, 2026-11-06: this read "lock, unlock, close and reopen
are four-eyes and audited". The lock is one accountant's act under `ACCOUNTING_CLOSE_PROPOSE`
(`AccountingPeriods.lock`; `AccountingPeriodController` `POST .../{periodId}/lock`), as `P14-TSK-005` built it: it
protects the month and freezes nothing a second person must attest - the close that follows is the four-eyes act.)*

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
finding as acknowledged (`ACKNOWLEDGED_CHANGED` otherwise, ADR-0117 - *corrected by `P14-DOC-001`, the Phase 14 exit review, 2026-11-06: neither `ACKNOWLEDGED_CHANGED` nor `RECURRED` exists since `P14-TSK-012`; a finding a person decided on - an `ACKNOWLEDGED` break by its disclosure, a `RESOLVED` one by its correcting entry - that stands unchanged discloses, and a changed one is `FAILED FINDING_CHANGED` with its next generation raised, `CheckOutcome.java:49`, `CloseChecks.java:29-32`*); the accruals when entries of either accruer lie past the cutoff and nothing is behind; the disclosure when read whole and not clean; the open breaks when
none is `OPEN` and some acknowledged. `C3`'s "close readiness requires accruals complete": `blocking` and `flooredPast` on one snapshot -
find and disclose, never `PASSED` on a period short of interest. `BNPL-F2a` has no floor and the same shortfall: it posts today valued the accrual date, so the month's last day
always posts after the month and a lagging sweep posts older dates there too; the read holds it beside `LND-2`, the last day apart from anything older, so a period with an active
pay-in-N obligation reads `DISCLOSED` and carving the last day out is the owner's (`P14-TSK-011`'s review, C-1) *(corrected by `P14-DOC-001`, the Phase 14 exit review, 2026-11-06: ruled - the owner decided on 2026-11-02 that `BNPL-F2a`'s month-end shift stays disclosed, nothing carved out, `DECISIONS.md` "What the close accepts")*. The grace's minimum raised to two business days (`LockGrace`), so a lock
never covers a western servicing zone's today (`P14-TSK-006`'s review, M-1). Proposal and approval are `-012`'s, reading
`CloseRuns.readiness`.

*`P14-TSK-012`, 2026-11-03 - decision 5 and the reopen (accounting `V008`):* **the proposal and the reopen are rows** (`period_close`, `period_reopen`), so a rejected proposal stays what it was and the next
carries its own reasons; nine `period_close_check` rows name the attempts read, each held by trigger to its frozen attempt and to being the latest, a `DISCLOSED` one with a copy of what it disclosed and a person's reason (`INV-ACC-09`, unamended; the owner's (2)). The period's edge trigger is replaced whole, `V003`'s edges unchanged, and judges every close edge for every writer; `revision` moves only at a reopen, by one. **Readiness is read holding the period row** (`P14-TSK-011`'s review, M-4): every conclusion, re-run and unlock takes that row first. A lock with no run yet is `accounting.CloseNotReady`, the owner's (3). **The approver never closes something other than what they read**: the approval names the proposal, and with the period and proposal rows held refuses `accounting.CloseProposalStale` unless it is the present one, every latest attempt the one frozen, the month's break counts and the approved set as they were; the same question is served as `stillStands` at `GET .../close-proposal` under `ACCOUNTING_CLOSE_APPROVE`, audited. **A rejection** returns the period to `LOCKED`, the same lock and run, and may be the proposer's: it closes nothing. **The reopen**: the latest closed only (built as the next period absent or `OPEN`, judged by the domain and the trigger), in the unlock's order (A1) previous, period, next, oldest first → (A4) FINAL runs `SUPERSEDED`, the reopen row → (A2) → (A3) exactly one period back, the edge at `revision + 1`, one transaction; a pending reopen moves nothing. The ledger's cutoff stands through `CLOSE_PROPOSED` and `CLOSED`: the close's edges take no cutoff key. **Events**: `accounting.AccountingPeriodCloseProposed`, `AccountingPeriodClosed`, `AccountingPeriodReopened`, in the edge's transaction, ids and codes only; a rejection and a reopen's proposal are history and audit. **`VALUATION_SET_APPROVED` waits** rather than failing (M-6): the third way, neither a birth by the approval nor a quieter alert; `-020` holds the note. **What no door leaves** (`PHASE_14_PLAN.md` §13.5's walk): a `PROJECTION_VERIFIED` drift has no product door, and a reference gap beyond the staleness bound none: owner items, stated rather than papered over. No new ADR: this and ADR-0117's As built hold it.

`P14-TSK-013` (2026-11-04), decision 5: accounting `V009` and ledger `V030`; `PHASE_14_PLAN.md` §13.5's as-built paragraphs state the machine, the date's rule, correcting twice and the walk through the close. Decision 5 made precise: **"in the open period" is the month holding today**, stored at the proposal and named by the approver, never `firstOpen` (a reopened month); a month with no row refuses and births nothing. **A refused approval stands**: decision 6's "stays `PROPOSED`" applies, and the rejection, asking nothing of either month, is its way on. **The revision is named**, since a reopen moves it; an approval is refused once it has moved. **So is the target's** (`target_revision`, the review's I-1): a month closed and reopened while a proposal stood is `OPEN` again, and only its revision says so; so a correction never lands in a reopened month, at the proposal or at the approval. Decision 6's floor stays out of reach of this origin.

*The independent review's fix round, 2026-11-04:* **I-2, what the close re-judges.** A batch repudiated after `RECONCILIATION_DISCLOSURE` concluded would have closed a month with no counting run while the frozen evidence said it ran, so `propose` and the approval's re-read judge `MonthRuns.ran` again on the machine's connection, holding the period row; only `ran` refuses, a replay is not re-judged, and a new batch of the same source and date is the way on (settlement's live uniques exclude `REPUDIATED`; `TheWayOnAfterARepudiationTest`). **Accepted limit**: a repudiation committing after that read, or after `CLOSED`, is not caught. The month stays closed, the way on is the reopen, and `RECONCILIATION_STATUS` shows it. `PHASE_14_PLAN.md` §13.5 tabulates, per check, what can move after its attempt and what catches it. **M-8**: `V008`'s edge reads a lock's previous and a reopen's next `FOR SHARE`, rows the domain's path already holds, so no door gains an inversion.

*`P14-TSK-023`, 2026-11-05 - the owner's ruling on `P14-TSK-018`'s item (a), no migration:* **a reopen's two receipts list the returns it overtakes**, and this is a list, not the naming below. `overtakenReturns`: each return `SUBMITTED` or `ACKNOWLEDGED` for the month, whatever revision it read, its id, form and status, newest sent first, at most twenty (`ReturnsAReopenOvertakes.NEWEST`), `overtakenReturnsTotal` how many in all, because a correction chain and a form's count are not small by construction; `overtakenReturnsNote` says what it is and is not, served and never stored, since no field of this API carries a description (ADR-0015). Those are exactly what reads `overtaken` once the approval commits. **It stops nothing, and nothing names it back.** One plain statement on the act's own transaction, after the act, holding the period's row, so it takes no lock, adds no edge to the plan's order and cannot invert against a submit, `permitResend` or `V010`'s trigger, each of which takes the period `FOR SHARE` and then the row. A send committed before the act held the row is on it; one waiting on the row is not, and on an approval is then refused at the new revision; a conclusion takes no period, so a status can be stale a moment later. **It costs one bounded indexed read more with the cutoff's key held, as the adjustments' read already does.** The key's stored receipt holds the list, about 2.3 kB at most; a replay returns what was stored; a row of six or seven fields, before this, and a close's act, read no list, so serve the list, the total and the note null, never an empty list and none (the fix round's M-6). The fingerprint does not move. `PeriodCloses` does not change: the read is `PeriodCloseDesk`'s, beside the adjustments' own, and each act's audit is as it was. **Not on it:** a return not yet sent, `APPROVED` and about to send among them, and one `UNDELIVERED` or `REJECTED`, so an empty list is not nothing in flight. **The approval's receipt comes after the approval committed**, which is the ruling's own limit: `P14-TSK-023`'s owner item.

*`P14-TSK-022`, 2026-11-04 - the owner's rulings (m), (n) and (r), no migration:* **a reopen names the posted adjustments it read.** `postedAdjustments` in the body of both acts, compared with what is posted against the month <em>at that act</em>, and differing is `PriorPeriodAdjustments.AdjustmentStale`, the same refusal and the same words as an adjustment's own approval. Never the approval's against the proposal's: a pending reopen does not stop an adjustment's approval, so the set moves between the two acts, and an approver attests to what stands when they act. **No inversion, because the new read takes no lock**: a plain statement after the period's row is held, sound because every writer into `POSTED` holds that row `FOR SHARE` for its whole transaction and `POSTED` is terminal. An approval first: the reopen waits on the row, reads it posted, and is refused until it names it. The reopen first: the approval waits, reads `OPEN` at the next revision, and is `PeriodStale`. A proposal holds the row and changes nothing on it, so the approval it delayed proceeds, and the reopen's approval lists it. Nothing about the naming is stored, since what was named is what was posted, on the adjustments' own rows; the stored receipt's format is unchanged, and a body naming none fingerprints as it did. **The approval of a prior-period adjustment and of a `MANUAL` adjustment takes `MULTI_FACTOR`**; neither door's proposal, read or rejection does. **The bound** (its review, M-1): a reopen's body names up to 201, one above an approval's 200, because an approval names those posted and adds itself, so 201 is the most a month holds over its whole life: every revision's count, and nothing else moves a row to `POSTED`. Equal bounds would have left a full month unable to reopen, its last door. Naming proves the person read them: the generic `MANUAL` door dated in a reopened month still knows nothing of them. **What a client could notice, each the owner's ruling under version 1 and not a new version, no client existing (ADR-0015's precedent for a reviewed change):** a reopen's body naming none on a month with adjustments is refused; an approval below `MULTI_FACTOR` is refused; an adjustment's statement line serves no reference, and reads `Account adjustment` where a resolution's read `RECONCILIATION_ATTRIBUTION`. New fields are additive: `postedAdjustments`, `accountingCorrections`, the report's `label`.

*(As built, `P14-REM-009`, 2026-11-09: decision 3's grace is judged on the database's date, `AccountingPeriodStore.today`, read holding the period's row, and the schedule's open leg chooses its months on the same date, as does the month a prior-period correction lands in. It was the instance's `Clock`, which now only stamps audit records and events here. The ledger's own instance-clock pre-check of an advance, `PostingCutoffs.advance`, is unchanged: it fails closed, and ledger `V028` judges every writer on the database's clock.)*

*(As built, `P14-TSK-020`, 2026-11-05, no migration: the close's meters. `finapp.accounting.period.open.age` measures from the database's date, `AccountingPeriodStore.today`, and no longer the instance's clock, and publishes as `finapp_accounting_period_open_age_days`. The owner's ruling on `P14-TSK-017`'s I-5 is `finapp.accounting.close.check.stalled`: a present run's attempts claimable past the run's own bound, `JdbcCloseRunStore.STALLED_ATTEMPTS_SQL` holding what `CLAIM_SQL` holds back in one string, **refined beyond the ruling by "and no claim of a present run is live"**, so a small fleet draining a queue past a long judge is no stall. **Its cost**: a hung judge's heartbeat hides one for `finapp.accounting.close.max-run` plus a lease from its last claim, and a judge hanging on every reclaim walks the fleet, firing near N x 2 h 02 on 1 to 4 instances and never on 5 or more, where it ends `WORKERS_EXHAUSTED` at about 8 h 08, a ticket. A repudiation after `CLOSED` is `finapp.accounting.close.month.unrun`, the latest closed month only, what follows it `P14-TSK-012`'s owner item (l), open *(corrected by `P14-DOC-001`, the Phase 14 exit review, 2026-11-06: ruled by the owner on 2026-11-06 at the recommendation - a repudiation after the approval's read is caught only by a reopen, and `AccountingClosedMonthLostItsRun` stays a ticket, the way on the reopen; `DECISIONS.md` "The close's open questions", `OPERATIONS_RUNBOOK.md` section 10)*. `finapp.accounting.cutoff.refused`'s `command` joined `MetricNames.ALLOWED_TAG_KEYS`, which `P14-TSK-004` never did: `MetricConventionTest` stood red until this task's first run of that package.)*


*`P14-TSK-024`, 2026-11-06 - the close's withdrawals and the approver's read (accounting `V011`; the owner's acceptance of `P14-TSK-012`'s (d) and (j) and `P14-TSK-023`'s (a)):* decision 3's four-eyes unlock and reopen gain **an end that is not their effect**, each its own terminal state: `REJECTED`, a second person's decision under `ACCOUNTING_CLOSE_APPROVE`, never the proposer's; `WITHDRAWN`, the proposer's own under `ACCOUNTING_CLOSE_PROPOSE`, nobody else's (`accounting.NotTheProposer`). Four eyes guard an effect; an end moves no cutoff, pin, revision or report run, so a proposer may retract their own, and keeping the two apart keeps `REJECTED` meaning two persons. The unlock's request stays on the period row and its end is a `period_unlock_end` row, copied from that row, the proposal cleared beside it; the reopen's row gains `REJECTED` and `WITHDRAWN`, and one pending per period and one approved per revision replace one per revision, so an ended month is free. An end serialises with the approval on the period row (A1, then the reopen row, A4), the loser `accounting.PeriodStale` naming what happened. No period state, event or meter. Decision 4's approval is told as of when: `GET .../close-proposal` serves each frozen attempt's `concludedAt`, the database's stamp as stored, and, on a pending reopen, the returns it would overtake, read holding nothing and deciding nothing. **Its fix round (2026-11-06; the independent review's M-1 and M-2, and the owner's acceptance of its item (b)):** an end makes a pending request replaceable within seconds, so **every decision on a pending request names it** - the unlock by `unlockProposedAt` (it has no id; its row's instant, stamped by the database under the row, is its identity), the reopen by `reopenId` - the approval, the rejection and the withdrawal alike, as the close's approval and rejection named their proposal from the first. Another pending is `accounting.RequestStale` (409), judged holding the period's row and recorded `FAILED`; the fingerprint covers the name, so the same key naming another request is a conflict. `GET .../periods/{periodId}/requests` (`ACCOUNTING_CLOSE_APPROVE`) serves the controller what it names, the period list the accountant. Another person's withdrawal is recorded `FAILED` as `accounting.NotTheProposer`. **The unlock's, the close's and the reopen's approvals take `MULTI_FACTOR`**, as a prior-period adjustment's does: they move the ledger's cutoff or freeze and supersede FINAL runs; the ends, proposals, the close's rejection and the reads do not. **What a client could notice under version 1, no client existing (ADR-0015's precedent for a reviewed change, as `P14-TSK-022`'s):** the unlock's and the reopen's approval bodies are `UnlockDecisionRequest` and `ReopenApprovalRequest`, each naming the request as a required field, and a session below `MULTI_FACTOR` is refused `403 identity.AssuranceRequired` at the three approvals; the owner confirms it at the exit review. Status stays `Proposed` until the phase's exit review.

*The Phase 14 exit review (`P14-DOC-001`, 2026-11-06):* **decision 2 holds for a line too** (ledger `V032`, the review's financial audit, F1): `V028` refused an entry dated on or before the cutoff, but the application role may insert `journal_line` and `V004`'s balance trigger is deferred, so a raw writer could have appended a balancing pair of lines to an entry committed long ago in a `LOCKED` or `CLOSED` month. A `BEFORE INSERT` trigger on `journal_line` now takes namespace `14` key `cutoff` **shared**, reads its entry's posting date and refuses it as `V028` does (`LDCUT`, a missing cutoff row fail-closed, the cutoff `FOR SHARE` under REPEATABLE READ/SERIALIZABLE). For a line of an entry inserted in the same transaction - every production line - it re-judges what the entry's trigger just judged, the key re-entrant. `PostingCutoffDatabaseTest#aLineAppendedToALockedMonthsEntryIsRefused` and `LineCutoffMigrationTest` hold it. No production path changed. **An end's repeat converges by the request named** (`P14-TSK-024`'s re-review, item (a)). A repeat of a rejection or a withdrawal, under any key, looks up the request it names under the period's row - an unlock's end by `period_unlock_end_once`'s key, the period, the lock instance and `unlockProposedAt` (`JdbcAccountingPeriodStore.unlockEnd`, `V011:46`), a reopen's by its id (`JdbcPeriodCloseStore.endedReopen`) - and, ended that way by that person, converges and writes nothing. Until then it read the lock's latest end, so a retry after another unlock ended in between was told no unlock was pending, and one while another was pending `accounting.RequestStale`. `AccountingPeriodsTest#aRepeatConvergesWhateverEndedSince`, `PeriodClosesTest#aReopensRepeatConvergesWhateverEndedSince`, `PeriodCloseDatabaseTest#aRepeatConvergesWhateverEndedSince` and `PeriodReopenDatabaseTest#aRepeatUnderANewKeyConvergesWhateverEndedSince` hold it. **The contract change under version 1 is confirmed**: the unlock's and the reopen's approval bodies (`UnlockDecisionRequest`, `ReopenApprovalRequest`, naming the request) and `MULTI_FACTOR` at the three approvals stand under version 1 by the owner's standing acceptance (`DECISIONS.md`, 2026-11-06), no client existing (ADR-0015's precedent for a reviewed change).

## Follow-up

The storm (PI-004) measures the lock's pause. Daily periods if ever needed (C9 B). The calendar grace is configuration.
