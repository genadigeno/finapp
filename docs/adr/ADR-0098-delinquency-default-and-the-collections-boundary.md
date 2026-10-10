# ADR-0098 — Delinquency, default and the collections boundary: days past due derived from dates, conditions apart from the lifecycle, recorded append-only on change, and nothing legally sensitive built

Status: Proposed
Date: 2026-10-10
Phase: 11
Context: Lending · Risk · Ledger · Events
Supersedes: nothing. Applies the lifecycle-versus-condition separation of ADR-0090 §4, the
database-clock discipline (`INV-LND-10`) and ADR-0005 (facts published through the outbox for
others to consume). Rests on `PHASE_11_PLAN.md` §2.3 (L4, L7, L8), §2.4 (A8, A9, A18, A25), §5.5,
§7.4 (scenario 10), §12.7, §17 and `INV-LND-12`.

## Context

1. **Delinquency is a fact about dates, not a counter.** "Days past due" stored and incremented by
   a daily job drifts the moment the job runs twice, skips a day, or races a repayment. It must be
   derivable from what was billed and what was paid at any instant.
2. **Delinquency is not a lifecycle state.** An account 120 days past due is still `ACTIVE`: it
   accrues, can be repaid, paid off or restructured. Encoding delinquency in the status would
   multiply every machine edge by every bucket.
3. **Default has legal consequences elsewhere.** Acceleration, penalty interest, arrears notices,
   bureau reporting, statutory contact limits and insolvency handling are jurisdiction-specific,
   and the owner excluded consumer-credit-law features (L4, L8).
4. **Collections is another context's job.** Contact strategy, promises to pay and placement
   belong to a collections owner (Phase 13), not to the account's servicing.
5. **The worker races closure.** A payoff can commit while the delinquency step runs (scenario 10);
   a condition written after closure would describe an account that no longer exists.

## Decision

1. **The definitions** (database clock, the agreement's `servicing_zone`):

   | Concept | Definition |
   |---|---|
   | Upcoming | a due item (instalment, or a statement's minimum payment) whose due date is in the future |
   | Due | billed and due today — DPD 0 |
   | Past due | billed and unpaid at the end of its due date |
   | **DPD** | days between the oldest past-due item's due date and the current business date; 0 when nothing is past due — **derived, never stored as a mutable counter** |
   | Bucket | from the terms version's bounds (default `B1_1_29`, `B2_30_59`, `B3_60_89`, `B4_90_PLUS`) |
   | Cure | DPD back to 0 because every billed amount is paid |
   | **Default** | DPD ≥ the terms' default threshold (90 days, L7); cleared at cure, no probation (A8) |

   "Unpaid" is read from the item's billed amounts less its allocations (ADR-0095) and
   cross-checked by the due accounts' ledger balances; the bounds, the threshold and the grace
   are agreement-pinned terms data (ADR-0092), so a jurisdiction's rules are configuration.

2. **Conditions, apart from the lifecycle.** Delinquency (`CURRENT` or `PAST_DUE` with DPD and
   bucket), default (a `DEFAULTED` flag), draws suspended (line, derived) and awaiting payout (loan,
   path X) are conditions. The servicing hold (legal, dispute, insolvency, deceased) is a designed
   slot, not built (A9) — it would suspend late fees and auto-collection.

3. **Recorded append-only, only on a change.** The servicing step (T-svc, `P11-TSK-024`), under
   **L3** after the day's accruals, billings and auto-collection, derives the condition and appends
   a `loan_condition_event` **only when it differs** from the latest recorded one, born once per
   `(loan_id, business_date, kind)`. Re-running a day writes nothing; a repayment that cures
   re-derives in its own transaction (T-rep) and appends the cure; nothing is ever updated or
   deleted. Events: `LoanDelinquencyChanged` (old and new bucket, DPD — it also announces cure;
   `DELIVERY_PLAN.md`'s `LoanDelinquent` renamed), `LoanDefaulted`, `LoanDefaultCleared`.

4. **Closed accounts are skipped** (scenario 10). The step claims only `ACTIVE` and `CLOSING`
   accounts and re-checks the status under L3; a payoff that committed first leaves `CLOSED`, and
   the step writes nothing; a payoff that waits on L3 sees the step's writes and then closes. DPD
   is never computed on a closed account.

5. **What conditions do in Phase 11.** Past due makes an item eligible for the late fee on the day
   DPD reaches the terms' `late_fee_day` (beyond grace), born once per (due item, kind), capped per
   account (A18; ADR-0099 governs its waiver). Past due or defaulted suspends a line's draws
   (`409 lending.DrawsSuspended`, A25). Auto-collection retries daily while past due (A21). Grace
   delays only the late fee, never DPD. A payoff is permitted in any condition. A restructuring
   (ADR-0099) may re-schedule arrears; overdue interest is never capitalised.

6. **Not built — legally sensitive, each recorded with its default:** acceleration (none: a
   defaulted loan keeps its schedule); default or penalty interest and interest on interest (none,
   L8, A4); arrears notifications (none: notifications are a later module); credit-bureau reporting
   (none: no bureau is connected, #13); statutory contact limits and insolvency handling (none). A
   reviewer finding any of it in a Phase 11 change refuses the change (§17).

7. **The collections boundary.** Lending owns the contractual facts — what is due, what is past due,
   for how long, and the default flag. Risk (Phase 13) owns fraud and abuse judgements; a future
   collections context (Phase 13's case management) owns contact strategy, promises to pay and
   placement, consuming `LoanDelinquencyChanged`, `LoanDefaulted` and `LoanDefaultCleared`. None of
   them writes lending's rows; anything that changes what is owed reaches lending as a servicing
   correction (ADR-0099). No write-off posting in Phase 11 (Phase 14; the expense account is seeded).

## Alternatives Considered

### A stored DPD counter incremented daily
Pros:
- One read per account; trivial reporting.

Cons:
- A double run adds a day; a skipped run loses one; a repayment racing the job leaves it wrong.

Refused: derived from dates, recorded only as changes.

### Delinquency buckets as lifecycle states
Pros:
- One status column answers everything.

Cons:
- Every servicing edge multiplied by every bucket; a cure becomes a state transition racing every
  other transition; a restructured or paid-off account's status must reason about arrears.

Refused: conditions apart from the lifecycle.

### A daily snapshot row per account
Pros:
- Point-in-time reporting without derivation.

Cons:
- 365 rows per account per year saying "nothing changed"; a re-run must decide whether to rewrite.

Refused: append only on change; the state at any date is the latest change at or before it.

### Acceleration and default interest at default
Pros:
- What many commercial contracts do.

Cons:
- Legally sensitive, jurisdiction-specific and excluded by the owner (L4, L8).

Refused for Phase 11.

### Collections operations inside lending
Pros:
- One module for the whole account story.

Cons:
- Contact strategy and placement are a separate context with its own case model (Phase 13);
  lending would accrete operational workflow over financial truth.

Refused.

## Consequences

Positive:
- Delinquency is reproducible from rows at any date, immune to duplicate or missed runs.
- Consumers get explicit, versioned facts without reaching into lending.
- Nothing legally sensitive ships under a neutral jurisdiction.

Negative:
- No arrears notices or acceleration in Phase 11 — a defaulted account simply waits for repayment,
  restructuring or Phase 13/14's tooling.
- The derivation reads billings and allocations per step; measured in the storm.

Operational impact: `finapp.lending.delinquency{product,bucket}` and `finapp.lending.default{product}`
gauges (counts only); delinquency sums are an audited report (ADR-0072).
Security impact: DPD, bucket and default are `RESTRICTED-FINANCIAL`; never in a log line or tag.
Financial impact: none posted by a condition; the late fee is its own posting (rule 4).

## Invariants / Constraints

`INV-LND-12` (delinquency is derived and its history append-only), `INV-LND-08` (no condition
after closure), `INV-LND-10`, `INV-LIFE-01`…`04`, `INV-EVT-01`…`04`.

## Follow-up

- Built by `P11-TSK-024` (conditions, the transitions current → each bucket → cure → default →
  cure, the events), `P11-TSK-025` (the late fee) and `P11-TSK-026` (scenario 10,
  `PayoffDatabaseTest#payoffBesideTheDelinquencyWorkerWritesNoConditionAfterClosure`).
- Phase 13 names the collections context that consumes the events; Phase 14 owns write-off.
- **Acceptance.** `Proposed` at the Phase 10 → 11 transition (2026-10-10); accepted by the Phase 11
  review (`P11-DOC-001`) after reading it against the code.
