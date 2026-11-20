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
   dispatch transaction**, beside the refund it writes - so the decision and the dispatch commit together or not at all.

5. **Operator payout: four eyes above a threshold**, the same shape (`merchant.payout_four_eyes_threshold`), *(ruling)* above
   10,000.00 EUR, GBP and USD, 1,500,000 JPY, 4,000.000 BHD, judged per payout under the merchant's `FOR UPDATE`. A merchant's
   own payout, by its key, is never judged: the control is on the operator surface.

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
