# ADR-0154 — The operator's money acts: four eyes for a transfer reversal and a merchant closure, four eyes above a versioned per-currency threshold for a refund and an operator payout, and a key on every one

Status: Accepted (2026-11-20, at `P17-TSK-031`'s gate)

Date: 2026-11-20

Phase: 17 (`P17-TSK-031`; finapp-admin-panel B7.1, gaps G-36, G-41)

Context: Transfers (reversal) · Payments (refund, void) · Merchant (closure, standing, operator payout) · Identity (permissions)

Rests on: finapp-admin-panel **ADR-C135** (single-actor money controls) decisions 2-4, adopted into this register here; ADR-0043
(the transfer's reversal), ADR-0045/ADR-0046 (the refund's two transactions), ADR-0048 (the void), ADR-0052 (the merchant payout),
ADR-0010 (audit), ADR-0031 (RBAC), the owner's C12 (roles a partition) and **the owner's standing ruling of 2026-11-20, verbatim:
"if you rise questions, my answer is your recommended options"** - every value and choice below marked *(ruling)* is this task's
recommendation, taken under it.

## Context

Inventorying the operator surface for the console found money acts below the platform's own standard (ADR-C135's table). By this
task, every one already asked for a second factor (`P17-TSK-102`); a reversal, a void, a merchant's suspension, reinstatement and
closure took no idempotency key; and a reversal, a refund, a closure and an operator payout were one person's act.

## Decision

1. **A proposal is a new state before the existing effect** (ADR-C135 decision 4). Each four-eyes act gets a proposal row in its
   owning module - `transfers.transfer_reversal_proposal` (V005), `merchant.merchant_closure_proposal` (V019),
   `payments.refund_proposal` (V043), `merchant.payout_proposal` (V020) - born `PROPOSED` by the act's existing permission, decided
   once (`APPROVED` | `REJECTED`) by a holder of the new approval permission who is not the proposer. The approval performs **the
   existing effect, unchanged**: the same ledger reversal under the same key, the same `MerchantAdministration.close`, the same
   refund and payout commands. No financial rule changes: the same journal entries, one step later. A rejection moves nothing.
   - **Four eyes for every writer**: refused at the domain, and a `CHECK (decided_by IS NULL OR decided_by <> proposed_by)` on each
     row. The row is the history: decided once by a conditional `UPDATE` of the decision's columns (a column grant), never again (a
     trigger), never deleted (no grant). One open proposal per subject (a partial unique index).
   - **Serialization**: the proposer locks the subject (`FOR UPDATE`); the decider locks the proposal, then reaches the subject
     through the existing effect. A proposer never waits on a proposal's lock, so no cycle. Two approvers, or an approval racing a
     rejection, produce one winner.
   - **A decision is never dated before its proposal**: two instances' clocks may disagree.

2. **Who** *(ruling)*: `FINANCIAL_CONTROLLER` - already the approving desk of the accounting acts, proposing nothing - holds the
   five new approval permissions: `TRANSFER_REVERSE_APPROVE`, `PAYMENT_REFUND_APPROVE`, `MERCHANT_PAYOUT_APPROVE`,
   `MERCHANT_CLOSE_APPROVE` and `FEE_SCHEDULE_APPROVE` (the last for `P17-TSK-123`). The proposers keep their roles
   (`LEDGER_OPERATOR`, `MERCHANT_ADMINISTRATOR`), so no role proposes and approves the same act (`RoleNameTest` pins the pairs).

3. **Transfer reversal: always four eyes.** `POST /v1/transfers/{id}/reversal` is now the keyed proposal; the decision is
   `POST /v1/operator/transfer-reversals/{id}/approval` and `/rejection`.

4. **Refund: four eyes above a threshold**, versioned per currency (`payments.refund_four_eyes_threshold`), *(ruling)* above
   1,000.00 EUR, GBP and USD, 150,000 JPY, 400.000 BHD. Judged **cumulatively** - this refund with the payment's non-failed refunds
   before it, the BNPL dispute pattern - **inside the refund command's dispatch transaction, under the attempt lock** every refund
   of the payment already takes, so a refund split under the threshold, or ten racing under it, cannot add up past it unseen. At or
   below it the door refunds exactly as before; above it the door's answer is a keyed proposal, `PROPOSED`, nothing held or sent.
   The approval (`POST /v1/operator/refund-proposals/{id}/approval`) runs the same command with the decision as a step **inside its
   dispatch transaction**, beside the refund it writes - so the decision and the dispatch commit together or not at all. The
   approval is **admitted first**, under the command's lock and before the bound: its proposal locked and still `PROPOSED`. So a
   decided proposal approved again under a new key answers `409 RefundProposalAlreadyDecided`, not the bound's `422`, which the
   first approval's own refund would otherwise cause. A retry under the first approval's key replays before this.

5. **Operator payout: four eyes above a threshold**, the same shape (`merchant.payout_four_eyes_threshold`), *(ruling)* above
   10,000.00 EUR, GBP and USD, 1,500,000 JPY, 4,000.000 BHD, judged per payout under the merchant's `FOR UPDATE`. A merchant's
   own payout, by its key, is never judged: the control is on the operator surface. Its approval is admitted the same way,
   under the merchant's lock before the merchant's state and the payable are judged.

6. **No reservation at the proposal** *(ruling)*: the approval's effect judges the capture bound and the funds under its own
   locks, on the state then; refused there, nothing is written and the proposal stays open, for a later approval or a rejection.
   **No expiry** *(ruling)*: a proposal stands until decided. **A threshold changes by a new version**, by a migration until a
   four-eyes door for it exists; a currency with no threshold requires four eyes (fail closed).

7. **Void: one person, keyed** (ADR-C135: it releases an authorization and moves no captured money). The key guards the act: a
   different request under the key is `409`, judged before anything is dispatched; a retry converges on the void's own machine.
   **Merchant suspension and reinstatement: one person, keyed. Merchant closure: four eyes** (terminal) - `POST
   /v1/operator/merchants/{id}/closure` is the keyed proposal, decided at `/v1/operator/merchant-closures/{id}/approval`.

8. **Fee-schedule versions and merchant fee assignment** (ADR-C135 decision 3, ADR-0125 decision 4) are **split into
   `P17-TSK-123`** *(ruling)*: their four eyes reshapes every merchant set-up across about fifteen test suites, a blast radius
   larger than the five doors here together, and deserves its own design. `FEE_SCHEDULE_APPROVE` exists already.

## Alternatives Considered

### Option A — a proposal state on each aggregate (transfer, payment, merchant)
Pros: one row per subject. Cons: edits every existing machine and every reader of its status (a customer's transfer would show
`REVERSAL_PROPOSED`); ADR-C135 decision 4 adds transitions rather than editing them. Rejected for a proposal row per act.

### Option B — judge the threshold at the door, before the command
Pros: no change to the refund and payout commands. Cons: not under the lock that serialises a payment's refunds - two refunds
under the threshold racing each other add up past it unseen. Rejected: the judgement lives where the capture bound already does.

### Option C — a platform-wide generic proposal table
Pros: one machine. Cons: one module's table holding every domain's lifecycle state - the shared mutable ownership the platform
refuses. Rejected.

## Consequences

Positive: no single person moves money on the operator surface above the thresholds; every retry is safe.
Negative: four new proposal tables and two threshold tables; the reversal and closure doors' responses change (a proposal
receipt); the existing tests of the single-step acts changed.
Operational impact: pending queues for the console (ADR-C133) are a follow-up; the thresholds are reviewed changes.
Security impact: five approval permissions on `FINANCIAL_CONTROLLER`; every act `MULTI_FACTOR`, keyed, reasoned, audited.
Financial impact: none to the accounting - the same entries, by the same code, one step later.

## Invariants / Constraints

`INV-AUD-04` (proposer ≠ decider, domain and `CHECK`); `INV-IDEM-01`/`-03` (one effect per key, a different request `409`);
`INV-LIFE-02` (the machines unchanged); `INV-PAY-05` (the capture bound, unchanged, at the approval).

## Follow-up

- `P17-TSK-123`: fee-schedule versions and merchant fee assignment by four eyes.
- Four-eyes doors for the two thresholds' versions (today a migration).
- The approvers' pending queues (§7 lists) and a read of one proposal, for the console.

## Amendment — two BNPL acts on the same pattern, the reads an approver lacks, proposers named (`P17-TSK-116`, 2026-11-20)

Taken under the owner's standing ruling of 2026-11-20, verbatim: "if you rise questions, my answer is your recommended
options" - each *(ruling)* below is this task's recommendation. Decisions 1-7 are unchanged; these extend them.

9. **The operator's BNPL refund: four eyes above a threshold** (finapp-admin-panel ADR-C135 decision 3 - a refund is a refund;
   OQ-9's "no four-eyes and no threshold review" for `POST /v1/operator/bnpl-purchases/{id}/refunds` is superseded). A
   threshold of its own, `bnpl.refund_four_eyes_threshold` (bnpl V024, versioned per currency, seeded as payments V043's
   values *(ruling)*: bnpl reads no other module's table). Judged **cumulatively** - with the purchase's committed refunds of
   every door - in T-ref under B5, the purchase's `FOR UPDATE` that already serialises its refunds, after the self-dealing rule
   and the bound, before B4: nothing further is locked or written, and T-ref rolls back whole. The **operator's door only**
   *(ruling)*: the merchant's refund is its own sale (ADR-0105 decision 9), a dispute's refund is the dispute's resolution
   (ADR-0106's own four eyes above its threshold). The proposal is `bnpl.refund_proposal` (decision 1's shape). **The approval
   locks the proposal first** *(ruling)*, then runs T-ref by the approver (self-dealing judged for them; no threshold - this is
   the second person) with the decision as a step right after the refund row, naming it: a second approver, or an approval
   racing a rejection, waits on the proposal and is answered `409 bnpl.RefundProposalAlreadyDecided`, never a refund refusal of
   a race it lost. The step's `admit` (decision 4's admit-before-judging, `P17-TSK-031`'s hook) runs under B5 right after the
   key's arbiter and before self-dealing and the bound, so the approver's own second approval under a new key is refused as
   decided too - never by the bound or the purchase's `FULLY_REFUNDED` its first refund brought. No proposer, T-ref or T-dsp takes a proposal's lock, so proposal → B5 closes no cycle. The approval's key is
   the approver's in the refund's own scope; a lapsed claim's retry converges on the refund row's arbiter
   (`UNIQUE (merchant_id, requested_by, refund_key)`), and under another key the step's decision refuses it. The door's answer
   gains `status` and `proposalId`.

10. **A merchant's BNPL fee-schedule assignment: four eyes** (ADR-C135 decision 3, merchant fee assignment; the BNPL half of
    decision 8 - the card fee schedule's versions and assignment stay `P17-TSK-123`'s). `PUT
    /v1/operator/merchants/{merchantId}/bnpl-fee-schedules/{product}` (`FEE_ADMINISTER`) now requires an `Idempotency-Key`
    *(ruling: the precedent of decision 7, replaced in place)* and proposes - judged first by `BnplPricing.assign`'s own rules
    on an unlocked read, so what would be refused is refused at once, and a pointer already there converges, `ASSIGNED`,
    nothing proposed. `merchant.bnpl_fee_assignment_proposal` (merchant V021), one open per (merchant, product), the index the
    arbiter (the proposer takes no lock). The approval (`FEE_SCHEDULE_APPROVE`, `FINANCIAL_CONTROLLER`) locks the proposal,
    performs `BnplPricing.assign` unchanged under the approver - every rule re-judged under the merchant's `FOR UPDATE`, merchant
    V010's trigger for every writer - and decides it in the same transaction.

11. **The reads an approver lacks, each under the decider's own permission** *(ruling)*: a lending terms version by id
    (`LENDING_ADMINISTER`), a capital contribution by id (`LENDING_CAPITAL_ADMINISTER` - a treasury officer read nothing
    before), a merchant's BNPL enablement by id under its merchant (`MERCHANT_ADMINISTER`), a review case by id
    (`CREDIT_UNDERWRITE`, audited `credit.ReviewCaseRead` as the queue is), and the agreement a servicing agent services
    (`BNPL_SERVICE`, `GET /v1/operator/bnpl-agreements/{id}/servicing`, audited `bnpl.ServicingAgreementRead`, the agent's own
    party refused - never the journal or the replay, which stay `BNPL_INVESTIGATE`'s). The reads keep their neighbours'
    footing: no step-up, as the queue and the investigation reads. The credit policy version by id is `P17-TSK-035`'s.

12. **Proposers named** (the raw actor id, the write-off attestations' precedent) on the loan and BNPL waivers and repayment
    reversals, loan amendments, both terms views and capital contributions - last in each keyed answer, so a receipt stored
    before it replays with none. BNPL amendments do not exist: nothing to name.

13. **Credit evidence names its media type** *(ruling)*: `credit.credit_evidence.media_type` (credit V027, nullable, its CHECK
    `NOT VALID` - expand-only), the provider's `Content-Type` as the adapter received it, `application/octet-stream` when
    absent or not a media type's shape and for every row before V027. Read through a **column grant**, not a new
    `SECURITY DEFINER` function - it is metadata, never the content, and `MigrationLint` refuses replacing a released body.


## Amendment — fee-schedule versions and merchant fee assignment by four eyes (`P17-TSK-123`, 2026-11-20)

Decision 8's split, built. Taken under the owner's standing ruling of 2026-11-20, verbatim: "if you rise questions, my
answer is your recommended options" - each *(ruling)* below is this task's recommendation, the alternative named.
Decisions 1-7 and 9-13 are unchanged; these extend them.

14. **A fee schedule version is a keyed proposal** (finapp-admin-panel ADR-C135 decision 3, ADR-0125 decision 4). `POST
    /v1/operator/fee-schedules/{id}/versions` (`FEE_ADMINISTER`) now requires an `Idempotency-Key` *(decision 7's precedent,
    replaced in place)* and answers `201` with a proposal receipt `{proposalId, feeScheduleId, status, feeScheduleVersionId}`,
    `PROPOSED` - judged first by `FeeSchedules.judgeVersion`, the version's own rules on an unlocked read (the schedule, the
    fixed part's currency, a named start not past: `INV-MER-03`), so what would be refused is refused at once.
    `merchant.fee_schedule_version_proposal` (merchant V022) holds the terms as V004's version row would, `effective_from`
    `NULL` for "when it is approved"; **one open proposal per schedule** *(ruling: decision 1's "one open per subject";
    alternative: several open per schedule, each racing the mint at approval)*, the partial unique index the arbiter. The
    approval (`POST /v1/operator/fee-schedule-version-proposals/{id}/approval`, `FEE_SCHEDULE_APPROVE`) locks the proposal,
    performs `FeeSchedules.addVersion` unchanged under the approver - the number minted by V004's unique index, the start
    judged again - and decides the proposal in the same transaction, **naming the version it minted** (a `CHECK`: approved
    exactly when named), so a price is explained as proposal → approval → version → pin. A start named and passed before the
    approval is refused there (`422 merchant.FeeScheduleNotForward`), nothing minted, the proposal left open for a rejection
    (decision 6: no expiry).

15. **A merchant's fee assignment is a keyed proposal**, the BNPL door's shape (decision 10). `PUT
    /v1/operator/merchants/{merchantId}/fee-schedule` keyed: `PROPOSED`, or `ASSIGNED` with nothing proposed when the pointer
    is there already (`FeeSchedules.wouldAssign`, `assign`'s rules on an unlocked read); `merchant.fee_assignment_proposal`,
    one open per merchant. The approval (`POST /v1/operator/fee-assignment-proposals/{id}/approval`) locks the proposal and
    performs `FeeSchedules.assign` unchanged under the approver - the merchant `FOR UPDATE`, the pointer and its event - with
    the proposer's reason, in the decision's transaction. The view gains `status` and `proposalId` (additive); the `GET`
    answers `ASSIGNED`.

16. **A schedule's creation stays one person's, keyed** (ADR-C135 decision 2): a named container carries no price, so no
    four eyes; `POST /v1/operator/fee-schedules` requires an `Idempotency-Key` and a retry answers the schedule created first.
    `FeeSchedules`' "no idempotency key" reasoning held while a duplicate version priced identically; a duplicate proposal
    is a second row a second person would decide, so it no longer holds.

17. **The approver's queues and read, under `FEE_SCHEDULE_APPROVE`**, in `P17-TSK-040`'s one pending-decision shape
    (finapp-admin-panel ADR-C133 decision 3; `PendingDecisions`): one queue per kind, `GET
    /v1/operator/pending-decisions/merchant/fee-schedule-versions` and `.../merchant/fee-assignments` - `PROPOSED` or no
    status, items `{id, kind: merchant.FeeScheduleVersion | merchant.FeeAssignment, proposedBy, proposedAt, reasonSummary,
    subjectId}`, proposed at ascending, id ascending, on the approve door's factor. *(Read "one queue of every kind, the BNPL
    fee assignments included, with `subjectKind` and `proposedByCaller`, at `GET /v1/operator/fee-schedule-proposals`" until
    `-040` fixed the shape, the same day: the BNPL fee assignments' queue is `-116`'s kind, `-040`'s to route.)* `GET
    /v1/operator/fee-schedule-proposals/{id}` serves one version or assignment proposal - recorded `merchant.FeeProposalRead`
    on the decision's factor, `P17-TSK-124`'s rule for the reads an approver lacks; a BNPL fee assignment proposal at
    `-124`'s own door *(read "any kind, the BNPL fee assignments too, unrecorded" until the rebase onto `-124`)* - with its terms,
    the schedule it names and every version of it, and an assignment's current pointer *(ruling: the approver holds no
    `FEE_ADMINISTER` and four eyes that cannot see the price decide nothing - decision 11's reasoning; alternative: grant the
    controller `FEE_ADMINISTER`, breaking the partition `RoleNameTest` pins)*. Both configuration: unrecorded, as the fee
    reads beside them are.

18. **The fixtures take the two-person path**: every suite that priced a merchant over HTTP now proposes as a
    `MERCHANT_ADMINISTRATOR` and approves as a `FINANCIAL_CONTROLLER` (`FeePricing`), the kind rehearsal's onboarding too.
    Domain-level fixtures calling `FeeSchedules` directly are unchanged: the four eyes are the operator surface's, as
    decision 1's are.
