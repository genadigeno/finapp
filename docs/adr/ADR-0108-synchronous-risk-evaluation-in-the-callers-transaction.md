# ADR-0108 — Synchronous risk evaluation in the caller's transaction: the decision model, the seam mapping, the fail-safe as a recorded decision, the latency budget and the override

Status: Proposed
Date: 2026-10-22
Phase: 13
Context: Risk · Transfers · Payments (withdrawals, checkout confirmation) · Cross-border · Credit · Identity · Audit
Supersedes: nothing. Applies ADR-0107 (the `risk` module and its ports), ADR-0024 and `INV-CON-03` (an authoritative
check reads durable state in-lock), ADR-0062 §6 (the withdrawal is hold-then-dispatch under the wallet account's
lock) and ADR-0084 §5 (credit consumes a signal and never computes a score). Answers `CURRENT_STATE.md`'s unresolved
question #11, "Fail-safe policy for risk evaluation: block or allow on unavailability". Rests on `PHASE_13_PLAN.md`
§2.2 (C1, C2, C6, C7), §7.1 (T-eval, T-advise, T-ovr), §7.2, §7.3, §7.4, §7.6, §12.1, §12.3, §12.4, §14 and
`INV-RSK-01`, `-02`, `-03`, `-04`.

## Context

1. **The seam runs in-lock, by contract.** `TransferExecution` consults both seams after `lockBothInFixedOrder` and
   the availability read, before the posting; `CrossBorderAuthorization` consults them in its first transaction
   before the offer is accepted; the withdrawal judges its hold under the wallet account's lock (ADR-0062 §6);
   `PaymentConfirmation.confirm` decides routing in Tx1 under the intent. A risk check that ran anywhere else would
   read inputs a concurrent movement could have changed, and a velocity window outside the money transaction could
   be passed twice (plan §2.1; `INV-CON-03`).
2. **A seam's answer is two values and no reason.** The Phase 4 contract maps `PERMIT`/`REFUSE` to the caller's
   committed reason (`transfers.RISK_REFUSED`, `crossborder.RiskRefused`). Richer outcomes — a hold, a step-up, a
   case — need a vocabulary and a place to live without changing that contract.
3. **The fail-safe was left open since initiation.** Unresolved question #11 asks whether to block or allow when the
   evaluation is unavailable; `DELIVERY_PLAN.md` §Phase 13 §5 requires the answer to be explicit, per operation and
   value band. A wrong default is either an outage or an open door — and a silent default of either kind is worse
   than both, because nobody counts it.
4. **The cost is paid by every money movement.** T-eval runs on the caller's connection inside its locks, so its
   latency is the transfer's, the withdrawal's and the checkout's; the phase's first named risk is that the
   evaluation slows all movement (plan §21).

## Decision

1. **T-eval: inside the caller's transaction, after the caller's locks, last.** The adapter calls `risk`'s one
   channel-neutral evaluation with the operation, the subject references, the amount and the command reference; `risk`
   pins the operation's `ACTIVE` policy version (R1), reads the inputs (signals, the profile, session assurance, the
   current velocity figures), evaluates, inserts the sealed assessment (R3) and — only when the decision permits the
   movement — upserts the velocity increments (R2) in the same transaction, so a refused movement counts for nothing
   and a committed one counts exactly once (OQ-1; `INV-RSK-03`). The assessment is `INSERT`-only, one per judged
   command by `UNIQUE (operation, command_ref)` under the caller's lock (`INV-RSK-01`); it records the policy and
   engine versions, every input read, every rule hit and its contribution, the score, the decision, the ordered
   reason codes, whether the fail-safe applied, and the latency (`INV-RSK-04`). **T-advise** is the same evaluation at
   a door — no upsert, no insert, no transaction of the command — for the step-up answer before anything exists; it is
   never authoritative.

2. **The operations judged and what each admits** (plan §12.1): `TRANSFER` admits `ALLOW`, `CHALLENGE`, `DENY`,
   `REVIEW`; `WITHDRAWAL` additionally `HOLD`; `CARD_PAYMENT` and `WALLET_PAYMENT` admit `ALLOW`, `CHALLENGE`, `DENY`
   (`HOLD` in `-010`'s cuttable second slice); `CROSS_BORDER` admits `ALLOW`, `CHALLENGE`, `DENY`, `REVIEW`;
   `CREDIT_SIGNAL` is not a movement and answers the profile's rating code (seam version 2). A policy version is per
   operation, and a band mapping to a decision the operation does not admit is refused at the policy door
   (`422 risk.PolicyInvalid`, ADR-0109). BNPL and loan applications are judged through credit's signal, not a seam of
   their own.

3. **The decision model** (C2; plan §12.3) is derived from what each operation can do with a judgement:

   | Decision | Meaning | The seam's answer and the caller's outcome |
   |---|---|---|
   | `ALLOW` | the movement may proceed | `PERMIT` |
   | `CHALLENGE` | only under `MULTI_FACTOR` assurance | `PERMIT` when the session already satisfies it, else `REFUSE` committed as the reserved `RISK_REFUSED` with reason `CHALLENGE_REQUIRED`; the door's T-advise turns an unsatisfied `CHALLENGE` into `403 identity.AssuranceRequired` before any row exists, so the committed refusal is the race's answer, not the normal path |
   | `DENY` | refuse | `REFUSE`; the caller commits its reserved reason; a case opens when the policy says so |
   | `HOLD` | accept the command, move nothing until a person decides | only where the operation is natively hold-then-dispatch: the withdrawal commits `HELD` with its ledger hold kept; a card payment authorises and defers the capture (slice 2); a case opens; release or denial is an approved override or a disposition issuing the action (ADR-0112) |
   | `REVIEW` | refuse now and open a case | `REFUSE`; a case of the operation's kind opens in T-eval's transaction with the assessment as its first evidence (OQ-2: the case row is `risk`'s and takes no money lock) |

   **Precedence when rules disagree:** `DENY` > `HOLD` > `REVIEW` > `CHALLENGE` > `ALLOW`; a hard rule's decision
   beats a score band's. The decision is immutable; an override is a separate aggregate (point 7).

4. **The fail-safe is a recorded decision, never a silent default** (C1; unresolved #11 answered). "Unavailable" is
   any of: no `ACTIVE` policy for the operation; the pin's read failing; a rule evaluation throwing; the statement
   timeout (point 5). The answer is **the policy's fail-safe band for the judged amount** — or, with no policy at
   all, the compiled `ALLOW` — always written as an assessment with reason code `RISK_UNAVAILABLE` or
   `NO_POLICY_ACTIVE`, the alert `RiskEvaluationUnavailable` (or `RiskPolicyNoneActive` where production is meant to
   judge) raised, and `finapp.risk.evaluation.unavailable{operation}` counted. The owner's bands, recorded as accepted
   defaults pending ratification and held as **fields of the policy version**, changed by activation and never by
   code:

   | Operation | Below the band | At or above |
   |---|---|---|
   | Internal transfer | `ALLOW`, recorded, below EUR 1,000 | `DENY` |
   | Wallet withdrawal | `DENY` at every band | `DENY` |
   | Cross-border | `DENY` at every band | `DENY` |
   | Checkout card and wallet payment | `ALLOW`, recorded, below EUR 250 | `DENY` |

   A fail-open is therefore a visible, counted event with an assessment row; a fail-closed is the band's refusal
   committed by the owning domain under its reserved reason. Production starts with no policy — closed to judging,
   `ALLOW(NO_POLICY_ACTIVE)` recorded — and opens by the activation gate (plan §1.1).

5. **The latency budget and what a breach becomes** (plan §7.6). Budget: **p99 ≤ 50 ms** for T-eval's reads and
   upserts, measured per operation by the storm with the monitoring sweep running beside it (M3's split trigger).
   Enforcement: `risk`'s reads run under a per-statement `SET LOCAL statement_timeout` of 250 ms inside the caller's
   transaction, restored after, each statement **behind a savepoint** so a timeout does not abort the caller's
   transaction; a timeout is "unavailable" and becomes the band's fail-safe decision recorded `RISK_UNAVAILABLE`. The
   sweep never runs inside a money transaction, so it cannot breach the budget except through row contention on
   windows, which the storm measures.

6. **The lock order, and why no cycle closes** (plan §7.2, §7.3; registered in `DISTRIBUTED_EXECUTION.md` §3 as
   "The Phase 13 lock order", planned). Risk rows are taken after every money row and only in two shapes: last inside
   a caller's transaction, or first in a risk-only transaction that takes no money row. Inside a money transaction:
   **(R1)** the operation's `ACTIVE` `risk_policy_version` row `FOR SHARE` — when the first read is empty the pin
   takes `pg_advisory_xact_lock_shared(13, hashtext(operation))` and reads once more, so an activation in flight never
   answers "neither" (the Phase 12 → 13 gate's A3-I1 applied forward; ADR-0109); **(R2)** the judged command's
   `velocity_window` rows, upserted in **sorted key order** (dimension, subject, window kind, window start), each one
   statement `INSERT … ON CONFLICT DO UPDATE … RETURNING`; **(R3)** the inserts (assessment, signal, and a `REVIEW`'s
   case row). A money transaction holds its money rows and then takes (R1)–(R3), which no risk transaction holds while
   waiting on a money row: T-case, T-mon, T-rep take no money row; T-act takes its action row and then enters the
   owning domain's command on that domain's own order with no other risk row held (ADR-0112). (R1) is `FOR SHARE` and
   conflicts only with the activation, which holds no money row. Two money transactions upserting overlapping windows
   take them in the same sorted order, so they cannot deadlock. The couplings, read in the code: transfers after
   `lockBothInFixedOrder` and the availability read, before the posting; cross-border in the first transaction before
   the offer is accepted, a refusal consuming nothing; the withdrawal in-lock after the hold is placed and before the
   dispatch is committed — `HOLD` commits `HELD` with the hold kept, `DENY` commits `FAILED(RISK_REFUSED)` and releases
   the hold in the same transaction, `ALLOW` and a satisfied `CHALLENGE` commit `DISPATCHED` as today (payments
   `V033`); the checkout in Tx1 after the eligibility checks and before the routing decision commits (payments
   `V034`); credit's `SnapshotFreezer` records the profile's rating with seam version 2, a decision made under
   version 1 replaying with `NOT_ASSESSED` (`INV-CRD-01`).
   *(Amendment, `P13-TSK-006`, 2026-10-23 — **(R2a)** precedes (R2): one transaction-scoped advisory lock per
   (dimension, subject) the judged movement carries, namespace `131`, in ascending lock-key order, before the
   database clock and the window figures are read. The caller's lock serialises only the subject it locks (the
   transfer's source account); without (R2a) two movements of one party, device or merchant on two money locks each
   read the window below its threshold and both pass — this ADR's own refused alternative, reached by the side door.
   No new cycle: one global order, where (R2)'s row locks already sat.)*

7. **The override** (C7; T-ovr, its own transaction). A `RISK_OVERRIDE` holder proposes an override of a `DENY`,
   `HOLD` or `REVIEW` decision with a reason code and text; a second holder approves when the judged amount is at or
   above EUR 1,000 **or** the decision was `DENY` — below that, one holder with a reason, audited. The proposer never
   approves (`403 risk.SelfApprovalRefused`, recorded `FAILED`; `INV-RSK-07`); approval needs a `MULTI_FACTOR`
   operator session. The override **never edits the decision**: it is `UNIQUE (assessment_id)` while live, records
   the action it issues — the held withdrawal's release through payments' command (ADR-0112); for a denied transfer
   nothing, the customer retries under a new key and the override sets a one-time allowance for the same command
   reference that the retry's evaluation reads as an input — and is itself an assessment's sibling, replayable. An
   `ALLOW` is never overridden after the fact — the money has moved; a case is the remedy (OQ-5).

8. **What the customer sees** (C6). The operation's generic answer: `transfers.RISK_REFUSED` → the existing error;
   a `HELD` withdrawal reads `HELD` with no reason; the checkout's refused decision carries
   `PaymentFailureReason.RISK_REFUSED`. Nothing of an assessment, alert, case or report reaches a customer or merchant
   surface (`INV-RSK-08`); a customer-facing dispute of a risk decision is not built — the support path and the
   override stand (plan §20).

9. **Would this remain correct with ten instances?** Ten retries of one command under the caller's lock seal one
   assessment (`RiskEvaluationDatabaseTest#tenRetriesOneAssessment`); ten concurrent movements of one subject count
   ten, a refused one zero (`VelocityWindowDatabaseTest#tenMovementsCountTen`, scenario 14); a window boundary across
   instances with clocks ±5 s is cut by the database clock read after the lock (`#aWindowIsCutByTheDatabaseClock`); a
   policy activated while ten evaluations run gives every assessment exactly one version, none "neither", and the
   activation never waits on a money row (`RiskPolicyDatabaseTest#anEvaluationBehindAnActivationPinsTheSuccessor`,
   scenario 3); an approved release racing the held withdrawal's expiry (`held_until`, the policy's hold validity) leaves exactly one outcome
   (`WithdrawalHoldDatabaseTest#releaseAndExpiryLeaveExactlyOne`, scenario 10) — the withdrawal row lock in payments'
   order arbitrates and the action is keyed. The unavailable case is scenario 9: the fail-safe is recorded, the alert
   fires, the movement commits the band's outcome. `VelocityProof` recomputes every live window from the monitored
   transactions; a difference is an alert, never self-corrected (ADR-0111).

## Alternatives Considered

### Evaluate asynchronously, after the movement commits
Pros: no cost on the money path.
Cons: the money has moved before it was judged; a refusal becomes a reversal, which `risk` must never make
(ADR-0112). Monitoring is retrospective by design (ADR-0111); decisioning cannot be. Refused.

### Evaluate before the caller takes its locks
Pros: a shorter critical section.
Cons: the inputs are read outside the lock that orders the movement, so two concurrent movements each read a window
the other is about to increment and both pass — the exact defect `INV-CON-03` and the seam contract exclude. Refused.

### A remote risk service with a latency budget
Refused for Phase 13 for the reason recorded in ADR-0107: an out-of-process call inside a money transaction is a
single point of failure for all movement. The in-process evaluation keeps the budget's breach a recorded decision
rather than an outage.

### A fixed fail-safe in code — fail open everywhere, or fail closed everywhere
Pros: simple; no policy field to misconfigure.
Cons: fail-open lets fraud through silently when the control is down; fail-closed turns a timeout into an outage of
every movement. Neither is a per-operation, per-band answer, and a compiled constant cannot be changed by an
activation under four eyes. Refused: the bands are policy data (C1), the compiled `ALLOW` applies only with no policy
at all, and both are recorded and counted.

### Abort the caller's transaction on a timeout
Cons: the timeout becomes a `5xx` on every movement that meets it, and the fail-safe can never be applied. Refused:
the statement runs behind a savepoint, the timeout is "unavailable", and the band decides.

### `HOLD` for every operation
Cons: a transfer and a checkout have no native hold-then-dispatch shape; holding them would require `risk` to place
a hold of its own, which it must never do. Refused: `HOLD` is admitted only where the owning domain's machine already
has the state (withdrawals; card payments in slice 2).

### An override that rewrites the decision
Cons: the sealed assessment would no longer replay, and the record of what the control said would be lost. Refused:
the override is its own aggregate recording the effect it issued.

## Consequences

Positive:
- Every judged movement has one sealed, replayable record of what was read and decided, including when the control
  itself was unavailable; "the control was down" is a counted row, never an absence.
- The seams' contract and every consumer's lifecycle are unchanged; the richer vocabulary lives in `risk`.
- The owner changes the fail-safe, the thresholds and the challenge level by activation, not by release.

Negative:
- Every money movement pays T-eval's cost in-lock; the budget is a target the storm measures, not a guarantee.
- A `CHALLENGE` met at the command rather than at the door commits a refusal the customer must retry.
- Three consumer migrations (payments `V033`, `V034`; identity `V023`) and five adapters widen the phase.

Operational impact: `finapp.risk.evaluation{operation,verdict}`, `evaluation.latency{operation}` (p50/p99/max),
`evaluation.unavailable{operation}`, `challenge{operation}`, `override{outcome}`; alerts on unavailable > 0, p99 over
budget, no active policy where judging is expected; the activation gate in `OPERATIONS_RUNBOOK.md` §9.
Security impact: `RISK_OVERRIDE` with four eyes at the threshold and step-up; the override and every assessment read
audited (`risk.AssessmentRead`, `risk.Replayed`, `risk.Override*`); no reason, score or rule name leaves `risk`.
Financial impact: no posting by `risk`. A `HOLD` keeps the withdrawal's existing ledger hold under the wallet lock
(`INV-BAL-04`); a `DENY` releases it in the same transaction; nothing is created or destroyed by a decision.

## Invariants / Constraints

`INV-RSK-01` (one sealed assessment per judged command, replayable `IDENTICAL`), `INV-RSK-02` (no money moved by a
decision), `INV-RSK-03` (durable windows written in the judged command's transaction), `INV-RSK-04` (versions, hits,
reasons; the fail-safe recorded), `INV-RSK-07` (the override is two persons' act above the threshold), `INV-RSK-08`,
`INV-CON-03`, `INV-BAL-04`, `INV-IDEM-01`, `INV-CRD-01`, `INV-CRD-04` (a risk score never summed with a credit
score), ADR-0024, ADR-0062 §6, ADR-0084 §5.

## Follow-up

- `P13-TSK-004` — the sealed assessment, the decision vocabulary, reason codes and replay (`risk V003`);
  `P13-TSK-006` — velocity windows in the caller's transaction and `VelocityProof` (`risk V005`).
  *(Built by `P13-TSK-004`, 2026-10-23, as `risk V007` — the migration number the waves gave it, not `V003`:
  `RiskEvaluation` (T-eval, T-advise), `RiskAssessments` and `AssessmentReplay` (the read and the replay doors), the
  fail-safe a recorded decision with its reason and its cause. Three readings of this ADR the build settled, for
  `P13-DOC-001`: **(i)** decision 5's "risk's reads run under ... 250 ms ... behind a savepoint" holds for every read but
  the pin, which runs behind a savepoint of its own **without** the timeout — ADR-0109 decision 6's waiting pin is the
  A3-I1 rule, and a timeout on it would turn every activation into a burst of unpinned fail-safes; **(ii)** decision 4's
  "the pin's read failing" has no policy to take a band from: the compiled `ALLOW` is recorded under `RISK_UNAVAILABLE`
  (never `NO_POLICY_ACTIVE`) with no version named, the alternative — a compiled `DENY` — being this ADR's refused "fixed
  fail-safe in code"; **(iii)** an amount the policy's bands cannot place (another currency than the version's) is
  answered by the open-ended band — `DENY` in every C1 band set — so activating a EUR version refuses every non-EUR
  judged movement until per-currency versions or a pinned conversion exist: an owner item before the activation gate.
  A permitted fail-safe is counted in the windows behind its own savepoint, recorded `UNCOUNTED` if that fails; a
  `DENY` opens no case — the version has no "case on deny" field, `-016`'s.)*
- `P13-TSK-008`…`-011` — the adapters on transfers (with the door's step-up advice), withdrawals (`HELD`, payments
  `V033`), checkout (payments `V034`; slice 2 cuttable) and cross-border; `P13-TSK-012` — the override and the held
  withdrawal's release.
  *(Transfers built by `P13-TSK-008`, 2026-10-23 — no migration, the `transfers` module unchanged. Decision 3's table
  read for a consumer with **two** seams: one assessment per transfer carries both answers — the limit seam's call
  (consulted first by `TransferExecution`) runs T-eval and seals, the risk seam's is answered from the seal by
  `(TRANSFER, transfer id)`; the limit seam answers `REFUSE` exactly when a fired `AMOUNT_BAND`/`VELOCITY_COUNT`/
  `VELOCITY_SUM` rule's own hard decision is the refusing decision (`Assessed.limitDecided`, `DENY` or `REVIEW` —
  `FAILED(LIMIT_REFUSED)`), the risk seam `REFUSE` for every non-permitting decision (`FAILED(RISK_REFUSED)`; a superset,
  so money never moves on the limit seam's wiring alone), an unsatisfied `CHALLENGE` always the risk seam's with
  `CHALLENGE_REQUIRED`. The `SessionAssurance` port is implemented over identity's session — the one the request
  presented, re-read live through the caller's unit of work — so a `CHALLENGE` is satisfiable. The door's T-advise runs
  in a transaction of its own that writes nothing and is **not** `READ ONLY`: the pin is `SELECT … FOR SHARE`, which
  PostgreSQL refuses there, and a refused pin is the compiled `ALLOW` — an advice that would never step anyone up. The
  advice never runs for a recorded idempotency key (the retry replays, `INV-IDEM-01`). Recorded for `-012`: the transfer's
  command ref is its id, fresh per execution, so "a one-time allowance for the same command reference that the retry's
  evaluation reads" (decision 7) cannot match a retry under a new key — the override must key its allowance on what the
  retry shares.)*
  *(Withdrawals built by `P13-TSK-009`, 2026-10-24 — payments `V033`, no risk migration. Decision 3's `HOLD` row as
  built: payments' own seam `WithdrawalRiskDecision` answers `PERMIT`, `HOLD(validity)` or `REFUSE`, consulted in the
  dispatch transaction after the hold under the withdrawal's own id; `HOLD` commits `HELD` with the hold kept and
  `held_until` = `held_at` + **the pinned version's hold validity**, answered by risk with the verdict
  (`Assessed.holdValidity`) and stamped by the database — policy data, never a payments constant; `DENY`, `REVIEW` and an
  unsatisfied `CHALLENGE` commit `FAILED(RISK_REFUSED)` with the hold released in the same transaction; C1's fail-safe
  `DENY` at every band for withdrawals is that refusal. **A `HOLD` is not counted** in the windows (T-eval counts what
  the decision permits; OQ-1 read for `HOLD`). The expiry past `held_until` is payments' (`WithdrawalResolutionSchedule`'s
  leg, `HOLD_EXPIRED`), the release and the denial payments' keyed doors behind risk's `HeldWithdrawalActions`; a release
  past `held_until` is refused — the expiry is the policy's outcome. The SYSTEM withdrawal on an instruction (a loan's
  payout) is not judged — it carries out a credit decision (owner item). `CaseOpener` is still `NONE` (`-016`).)*
  *(Checkout payments built by `P13-TSK-010`, 2026-10-24 — payments `V034`, risk `V008`, both slices. Payments' own seam
  `PaymentRiskDecision` answers `PERMIT`, `HOLD(validity)` (a card only) or `REFUSE`, consulted in the confirmation's Tx1
  under the intent row's lock, after the eligibility checks and the routing plan and before anything of the dispatch is
  written; the command reference is the intent's id, so one intent has one assessment and a re-evaluation replays the
  sealed decision. Decision 3's table for checkout: `ALLOW` and a satisfied `CHALLENGE` permit; `DENY` (C1's fail-safe at
  or above EUR 250 among them) and an unsatisfied `CHALLENGE` refuse - **terminally**: the intent `FAILED` with its own
  `RISK_REFUSED` and no attempt, never re-confirmable (routing's refusal stays the re-confirmable one); the customer and
  the merchant read the generic `FAILED` with no reason (C6). Decision 3's `HOLD` row for a card: the authorisation
  dispatched as any card payment's and the capture deferred behind a capture hold whose `held_until` is the pinned
  version's hold validity on the database clock - captured or voided exactly once by payments' keyed doors, or voided by
  the deadline; `RiskDecision.admittedBy(CARD_PAYMENT)` gained `HOLD` (risk `V008`; the wallet payment's did not, C2),
  and a card version names a hold validity exactly when it can decide `HOLD`. A `HOLD` is not counted. A top-up and a
  pay-by-bank checkout are not judged (owner item). **The lock order:** the wallet payment's T-eval runs after the book
  rail's pair lock, not before as the plan's §7.3 sketch read - risk rows last in the caller's transaction, the order
  every transfer and withdrawal keeps.)*
  *(Cross-border built by `P13-TSK-011`, 2026-10-24 — no migration; crossborder's one change, `CrossBorderInstruction`
  gaining `quoteId`. Decision 3's table for a consumer whose **risk** seam is consulted first (crossborder's
  authorization: risk, then limit): the limit seam refuses exactly a limit-decided refusal (`422 crossborder.LimitRefused`),
  the risk seam every other non-permitting decision (`422 crossborder.RiskRefused`) - two disjoint sets covering every
  refusal, so the committed code is `-008`'s in either order; the composition wires the pair together. The command
  reference is the **quote's** id (one payment per quote): one assessment per movement however many keys try it, a
  sealed refusal sticky for that quote. The judged amount is the quote's EUR figure (its source when EUR, else its
  destination when the corridor converts to EUR) - reading (iii) above met: a corridor with no EUR leg (`USD-BHD-BH`,
  `GBP-USD-US` in the O7 policy) is decided by the open-ended fail-safe band once a EUR version is active, the owner's
  item. Decision 4's "always recorded" needed the door's help: crossborder's desk rolled every refusal back to its
  savepoint, so it now keeps what was written since the savepoint for exactly the two seam refusals (by the seams'
  position only risk's record), while a permit later refused by fx or payments rolls back with its count - `INV-RSK-01`'s
  "a refused command's assessment commits with the refusal, and a rolled-back command leaves none". **The lock order:**
  the cross-border first transaction took the quote and the source wallet `FOR UPDATE` *after* the seams, with (R2a)
  held - neither of decision 6's shapes; `app`'s judgement now takes those rows first, in the Phase 9 order, then
  (R1)–(R3).)*
- `P13-TST-001` measures T-eval p50/p99/max per operation beside the sweep; `P13-TST-002` replays every decision in
  two JVMs.
- `CURRENT_STATE.md` §Unresolved questions: #11 is answered by this ADR (C1) and moves to "Resolved since" when the
  owner ratifies it; `FINANCIAL_INVARIANTS.md` gains `INV-RSK-01`…`-04`; `ERROR_CONTRACT.md` and
  `AUDITABLE_ACTIONS.md` gain the planned entries.
- **Acceptance.** `Proposed` at the Phase 12 → 13 transition (2026-10-22); the Phase 13 review (`P13-DOC-001`) reads
  this ADR against the code, corrects it in place with dated notes, and accepts it.
