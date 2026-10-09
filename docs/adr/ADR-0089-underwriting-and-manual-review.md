# ADR-0089 — Underwriting and manual review: a referral opens a case, a person decides with reasons, never overrides a hard decline, never second-approves their own decision, and an unworked case expires with a recorded reason

Status: Accepted (2026-10-09, `P10-DOC-001` — read against the code and corrected first)
Date: 2026-10-07
Phase: 10
Context: Credit · Identity · Audit
Supersedes: nothing. Applies the platform's four-eyes discipline (`INV-AUD-04`; ADR-0071's
authority thresholds, ADR-0075 §7's approver ≠ proposer at the domain and by `CHECK`) to credit
referrals, and ADR-0081 §4's "a person decides with a reason" to credit. Rests on
`PHASE_10_PLAN.md` §4, §5, §7, §9, §11, §12.7, §14, §18 and `INV-CRD-02`, `INV-CRD-09`,
`INV-CRD-11`.

## Context

Some applications should not be decided by rules alone: thin files, borderline affordability,
an unavailable source whose policy says "refer". The policy's `REFER` effect sends them to a
person. That person is powerful, and the design must bound the power:

1. **A person's decision is still a credit decision.** It must be as immutable, as explained and
   as exposure-safe as the system's (`INV-CRD-02`, `INV-CRD-09`) — not a side channel around
   them.
2. **Some outcomes must not be overridable.** A hard decline (a confirmed insolvency, a fraud
   flag) is policy saying "never"; an underwriter approving it defeats the policy and its
   versioning.
3. **Large approvals need a second person** (`INV-AUD-04`), and the second person must not be
   the first.
4. **Two underwriters on N instances will take the same case.** The queue must give each case to
   one person.
5. **A referral queue nobody works is a silent decline** (plan §18). The applicant waits forever
   unless the platform closes the request, and says why.

## Decision

1. **A `REFER` opens one case.** When the evaluation's outcome is `REFER`, the deciding step
   (ADR-0087 §5) opens an `underwriting_case` instead of recording a decision — born once per
   referral, `UNIQUE (decision_request_id)` — moves the request `IN_REVIEW` and emits
   `ManualReviewRequired` (request, case, the referral's reason codes; never attributes). The
   system evaluation, with its triggered rules and reason codes, is kept as the case's basis.

2. **What a person may decide, and what they may not.**
   - **May:** `APPROVED` or `DECLINED`, always with at least one reason code from the seeded
     catalogue (`422 credit.ReasonRequired` otherwise). *(As built (`P10-DOC-001`, 2026-10-09):
     the codes must be the catalogue's adverse codes, each given once — `CRD-AUTO-APPROVAL-CEILING`
     speaks of automation and is refused `422 credit.ReasonRequired` (`UnderwritingCases`) — and a
     free-text reason is required beside them (§Follow-up (4)). A malformed judgement — a decline
     naming an amount, an approval without one, in another currency, not positive, or below the
     product's minimum amount — is `JudgementInvalid`, answered `422 api.ValidationFailed`.)*
   - **May not override a hard decline.** A request whose evaluation included a `HARD_DECLINE` is
     never referred — the evaluator's severity order makes `HARD_DECLINE` the outcome
     (ADR-0086 §2) — and a person's approval of a request whose evaluation included one is
     refused at the domain, `422 credit.HardDeclineNotOverridable` (failure scenario 20).
   - **May not be their own second approval.** The second approver is never the first decider,
     at the domain and by `CHECK` (`403 credit.SelfApprovalRefused`, scenario 19).
   - **May not exceed the evaluation or the limit.** A person's approval is bounded by the
     evaluation's approved amount and by the party's exposure limit re-read under the profile
     lock (point 6) *(as built (`P10-DOC-001`, 2026-10-09): a `REFER` evaluation has no approved
     amount (`credit V009`), so the bound is the referral's ceiling `approvable_minor` — the
     request capped by every `CAP_AMOUNT` rule the basis evaluation triggered, stamped on the case
     at its birth (`credit V013`, which also bounds a first approval by it); at the deciding
     transaction the lesser of it and the successor evaluation's ceiling; the auto-approval ceiling
     not applied — §Follow-up (1))*; beyond either, `422 credit.ExposureLimitExceeded`, nothing recorded, and the
     person decides again — a decline, or a smaller approval. A person never makes a counter-offer
     (Phase 11's offer).
   - **May not change a decision once recorded.** The person's decision *is* the decision, recorded
     once; a later change of mind is a new request (ADR-0087 §4).

3. **The case machine: `OPEN → ASSIGNED → DECIDED`, or `ASSIGNED → AWAITING_SECOND → DECIDED`;
   `ASSIGNED → OPEN` (released by its assignee); `AWAITING_SECOND → ASSIGNED` (the second
   approver disagrees and refuses the second approval, point 4); and the terminal `CLOSED` when
   the case's request closes undecided — from `OPEN` when the request expires (point 7) or the
   progress sweep abandons it for lost standing, and from `ASSIGNED` or `AWAITING_SECOND` only when
   the person's own deciding transaction abandons the request (point 6).** Terminal: `DECIDED`,
   `CLOSED`. Held by a status `CHECK` (a hand-written list of the five states, `credit V013`) and
   a state-shape `CHECK` per status, an every-writer edge trigger and the domain; history in
   `underwriting_case_event`, append-only. The full machine is in
   `CREDIT_DECISIONING_LIFECYCLES.md`.

4. **Four-eyes above the product's threshold.** A person's approval above the product's four-eyes
   threshold (declared on `CreditProduct`, ADR-0084 §6) moves the case `AWAITING_SECOND` instead
   of deciding; a second holder of `CREDIT_UNDERWRITE`, different from the first, approves it
   (`POST …/{id}/second-approval`) and only then is the decision recorded. *(As built
   (`P10-DOC-001`, 2026-10-09): the first approval above the threshold takes only the request and
   case locks — no profile lock — checks the hard decline and the referral's ceiling, records the
   first decision on the case and no credit decision; the exposure bound is applied at the second
   approval's deciding transaction (point 6). If the exposure check refuses the second approval
   (`422 credit.ExposureLimitExceeded`, nothing recorded), the case stays `AWAITING_SECOND`; the
   first underwriter decides again only after a second approver refuses the second approval.)* **A second approver
   who disagrees refuses the second approval** through the same door, with a reason:
   `AWAITING_SECOND → ASSIGNED`, the case back with its first underwriter, who decides again,
   audited `credit.ReviewSecondApprovalRefused` (reason required); nothing is recorded as a
   decision. A decline, and an approval at or below the threshold, are one person's. The
   four-eyes `CHECK` on the case (second approver ≠ first decider) holds for every writer.

5. **The assignment race has one winner.** `POST /v1/operator/credit/review-cases/{id}/assignment`
   locks the request row, then the case row, `FOR UPDATE` (the lock order: request → case) and
   transitions conditionally `OPEN → ASSIGNED` under an `IN_REVIEW` request; of two underwriters
   taking one case, one is assigned and the other answers `409 credit.CaseTaken` (scenario 21).
   Because the request's expiry takes the same two locks in the same order, an assignment and an
   expiry serialise — exactly one of `ASSIGNED`, `EXPIRED`. Assignment is audited
   (`credit.ReviewCaseAssigned`) and emits no event (`UnderwritingStarted` is refused,
   ADR-0087 §9). The assignee may release a taken case
   (`POST …/{id}/release`, `ASSIGNED → OPEN`, the same two locks), audited
   `credit.ReviewCaseReleased`.

6. **A person's decision runs the system's deciding transaction.** Profile first, then the
   request (`IN_REVIEW`), then the case (plan §7's lock order: profile → request → case).
   *(As built (`P10-DOC-001`, 2026-10-09): this holds for a decline, an approval at or below the
   four-eyes threshold and a second approval; a first approval above the threshold records no
   decision and takes no profile lock (point 4's note).)* **It
   judges the case, not the request's expiry**: the case must be `ASSIGNED` to the deciding
   person (or `AWAITING_SECOND`, for a second approver who is not the first), and a taken case is
   decided by its person whatever the request's validity — so a taken case is never stuck between
   a decision the clock forbids and an expiry the machine forbids. The party's standing and the
   consent gate are re-read in the transaction, as in the system's; either lost abandons the
   request (`ABANDONED`, `STANDING_LOST` or `CONSENT_WITHDRAWN`) and closes the case (`CLOSED`),
   nothing decided. *(As built (`P10-DOC-001`, 2026-10-09): the person's act is then audited —
   `credit.ReviewDecided` or `credit.ReviewSecondApproval` — with outcome `FAILED`, naming the
   abandonment, and commits with the closure. For an `OPEN` case, the progress sweep abandons the
   request only on lost standing (`DecisionProgress`); it does not re-read the consent gate, so a
   consent withdrawal on an untaken case surfaces at the person's deciding transaction once the
   case is taken, or the request expires (point 7).)* Reserved exposure is re-read under the profile lock and, if it moved, a
   successor snapshot re-evaluated (ADR-0088 §5) — a person's approval can no more exceed the
   party's exposure limit than the system's (`INV-CRD-09`, scenario 15's manual variant): an
   approval above the evaluation's approved amount (as built, the referral's ceiling — point 2's
   note) or beyond the limit is refused `422 credit.ExposureLimitExceeded`, nothing recorded, the
   case unchanged, and the person decides again. The decision is inserted with `decided_by` the
   person and the case's reason codes; the request moves `DECIDED`, the case `DECIDED`;
   `CreditDecisionRecorded` is emitted; the acts are audited — `credit.ReviewDecided` (reason
   required) and, for the second person, `credit.ReviewSecondApproval` — each in its own
   transaction, losers recording nothing.

7. **Only an unassigned case expires, with its request and a recorded reason** (plan §5 and §18
   — `INV-CRD-11` bounds what a person may decide; this bounds the wait for one). A case still
   `OPEN` — never taken, or released back (`ASSIGNED → OPEN`) — when the request's validity
   passes on the database clock is closed by the progress sweep: the request moves `EXPIRED` with
   a recorded reason (`CreditDecisionRequestClosed`) and the case `CLOSED` with it, by the same
   complementary conditional that separates decision from expiry (ADR-0087 §7). Once a person has taken the case (`ASSIGNED` or
   `AWAITING_SECOND`) the request does not expire under them (`IN_REVIEW → EXPIRED` is refused
   once taken), and point 6 lets them decide it.
   `finapp.credit.review.age` (the oldest open case) alerts above the review objective long before
   validity runs out.

8. **The doors and the role.** `GET /v1/operator/credit/review-cases?status=`, `POST
   …/{id}/assignment`, `POST …/{id}/release`, `POST …/{id}/decision`, `POST
   …/{id}/second-approval` (approve, or refuse with a reason), all under `CREDIT_UNDERWRITE` (held
   by `UNDERWRITER`), the four `POST`s keyed (`credit.review:<actorType>:<actorId>`), each with a
   negative test in `RoutePermissionRegisterTest`. *(As built (`P10-DOC-001`, 2026-10-09): the
   `GET` queue read is not keyed — it is audited per serving, `credit.ReviewCasesRead`
   (§Follow-up). The key's actor type is the session actor's as `SessionAuthenticationInterceptor`
   types it — every session actor is `CUSTOMER`, an operator's included — so an underwriter's key
   reads `credit.review:CUSTOMER:<identityId>`, not `EMPLOYEE` as first written; the decision's
   `decided_by_type` is `EMPLOYEE` regardless (§Follow-up (5)).)* Audited acts: `credit.ReviewCaseAssigned`,
   `credit.ReviewCaseReleased`, `credit.ReviewDecided` (reason required),
   `credit.ReviewSecondApproval`, `credit.ReviewSecondApprovalRefused` (reason required). The
   underwriter sees the case's basis — the explanation's normalised attributes and triggered
   rules — never the raw evidence.

## Alternatives Considered

### Let an underwriter override any outcome, hard declines included
Pros:
- Maximum flexibility for exceptional cases.

Cons:
- A hard decline exists precisely to be non-negotiable; an override path makes the policy
  advisory and its four-eyes activation meaningless. An exceptional case that should be approvable
  is a policy change, proposed and approved as one.

Refused (point 2).

### The underwriter edits the system decision rather than recording one
Pros:
- One decision row per request regardless of path.

Cons:
- The system would have recorded a decision that then changed — `INV-CRD-02` broken, and
  `CreditDecisionUpdated` would have to exist. A referral is *not* a decision; the person's is.

Refused (points 1, 6).

### Four-eyes on every manual decision
Pros:
- Uniform; maximal control.

Cons:
- Doubles the queue for small approvals and declines whose risk does not warrant it; the
  product's threshold puts the second person where the money is.

Refused in favour of a per-product threshold (point 4).

### Auto-decline an unworked case at validity
Pros:
- The applicant gets an outcome rather than an expiry.

Cons:
- A decline is an adverse decision that must carry reasons an adverse-action notice can explain;
  "nobody looked" is not a credit reason. An expiry with its reason is the honest record.

Refused (point 7).

## Consequences

Positive:
- A person's decision is as immutable, explained and exposure-safe as the system's; it is the same
  transaction with a different `decided_by`.
- Hard declines are structurally non-overridable; large approvals always meet two people; one
  person per case under any concurrency.
- An abandoned queue becomes visible (the review-age alert) and then closes honestly.

Negative:
- Underwriters cannot rescue a hard-declined applicant; that requires a policy change through the
  four-eyes door.
- An approval above the threshold waits for a second underwriter.

Operational impact: `finapp.credit.review.age`, `finapp.credit.decision{…, decision_maker}`
(`system` / `person` — the plan's `decided_by` tag, as built `decision_maker`, ADR-0087
§Follow-up `P10-TSK-016` (4)); the runbook names the review objective and the second-approval
expectation. *(As built (`P10-DOC-001`, 2026-10-09): the runbook's credit section
(`OPERATIONS_RUNBOOK.md`) was missing until the exit review and was written by `P10-DOC-001`. The
review objective is `CreditObjectives.REVIEW_AGE` — no `OPEN` case waits longer than a day — and
its alert is `CreditReviewAged` (`infra/prometheus/rules/credit.yml`,
`max(finapp_credit_review_age_seconds) > 86400` for 30 minutes).)*
Security impact: `CREDIT_UNDERWRITE` is a dedicated role (`UNDERWRITER`, identity `V020`) in the
pairwise-disjoint role model; every act audited, a decision and a refused second approval with
their reason (*as built*: assignment, release and a second approval need none); self-approval
refused at two ranks.
Financial impact: none posted. A person's approval reserves exposure exactly as a system approval
does (ADR-0088).

## Invariants / Constraints

`INV-CRD-11` (never one's own second approval, never overriding a hard decline, always with
reasons), `INV-CRD-02` (the person's decision immutable, adverse ⇒ reasons), `INV-CRD-09` (the
same profile-first deciding transaction), `INV-CRD-06` (one case and one decision per request),
`INV-AUD-01`…`04` (every act audited; four-eyes), `INV-CON-02` (two underwriters, one
assignment).

## Follow-up

- **As built**: `P10-TSK-018` built the case, its machine (`CLOSED` and the refused second approval
  included) and doors (release included), the four-eyes threshold, the assignment race against
  expiry, the manual deciding transaction bounded by the exposure limit, and its expiry;
  `P10-TST-001` ran underwriters on both instances of the storm.
- *As built by `P10-TSK-018` (2026-10-08), points 1-8.* `credit V013`: `underwriting_case` (born `OPEN` once per
  referred request by the deciding transaction, with its basis evaluation, the referral's ceiling and the product's
  four-eyes threshold copied at birth; the four-eyes `CHECK (second_decided_by <> first_decided_by)`; a first decision
  whole or absent with at least one reason code; a state-shape `CHECK` per status, `DECIDED` carrying a second approver
  exactly when the approval is above the threshold; the machine trigger - the birth on a `REFER` basis with no triggered
  hard decline, assignment only under an `IN_REVIEW` request, a taken case kept by its underwriter, `CLOSED` only with
  its closed request and carrying its reason, `DECIDED` only beside the request's decision) and `underwriting_case_event`
  (append-only; a decision's edge reasoned, a second person's act never the first decider's - by `CHECK`); the request's
  trigger redefined (`IN_REVIEW` only beside its open case, `IN_REVIEW → EXPIRED` only while the case is `OPEN`, the
  system's referral refused once expired) and a deferred trigger letting a request leave `IN_REVIEW` only with its case
  terminal. `UnderwritingCases` (the acts), `DecisionMaking`'s steps shared with it, `DecisionProgress`'s `IN_REVIEW`
  step, `UnderwritingCaseDesk` and the five doors behind `CREDIT_UNDERWRITE`, keyed `credit.review:<type>:<id>`, and
  `finapp.credit.review.age`. Five decisions taken in the building, recorded here. (1) **"The evaluation's approved
  amount" for a referral is the referral's ceiling**: a `REFER` evaluation approves no amount (`EvaluationResult`
  refuses one), so the bound is the requested amount capped by every `CAP_AMOUNT` rule the evaluation triggered - what
  the evaluation's rules let through - and, when a successor is frozen under the person, the lesser of the case's and
  the successor's. The auto-approval ceiling is not one: it bounds an AUTOMATED approval, and above the product's
  four-eyes threshold a second person meets the approval instead. (2) **The limit is judged on what is known, with the
  person's amount**: on the deciding snapshot (its reservation re-read under the profile lock), the platform's
  outstanding credit plus reserved exposure plus the approval, plus the bureau's total balance when it was read - an
  absent bureau balance is the referral's question, which the person answers, while the platform's own terms stay bound
  by the limit. (3) **A person's decision names its deciding snapshot**: the basis, or the successor frozen under the
  person; a successor evaluation that hard-declines refuses an approval (`422 credit.HardDeclineNotOverridable`) as the
  basis would. `P10-TSK-019`'s replay re-derives the decision's snapshot to its STORED evaluation - the basis `REFER`,
  or that successor's - and verifies the decision against the case's recorded decision. (4) **Two kinds of reason**: the
  person's catalogued codes (at least one, about the applicant - `CRD-AUTO-APPROVAL-CEILING` speaks of automation and is
  refused) are the decision's reason codes, as plan section 12.7 says; a free-text reason is required beside them and
  goes to the case history and the audit row. (5) **`decided_by` is the first decider**, the judgement's maker; the
  second approver is the case row's and the actor of `credit.ReviewSecondApproval` and `credit.DecisionRecorded`; the
  decision's `decided_by_type` is `EMPLOYEE` whatever type the acting session's actor carries. The queue read serves each
  case's basis (normalised attributes, the rules and their results), so it is audited per serving -
  `credit.ReviewCasesRead`, a sixth act beside point 8's five. `ManualReviewRequired` carries its referral codes one
  field each (`referralReasonCode1`…`N`), so no number of codes outgrows a payload value.
- **Acceptance.** **As built** (`P10-DOC-001`, 2026-10-09): the Phase 10 review read this ADR
  against the code, corrected it in place above, and accepted it.
