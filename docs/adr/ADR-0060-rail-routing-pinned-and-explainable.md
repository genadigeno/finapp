# ADR-0060 — Rail routing is a versioned policy, decided once per payment, pinned, and explainable from stored data

Status: Proposed (2026-09-24, the Phase 6 → 7 transition)
Date: 2026-09-24
Phase: 7
Context: Payments
Supersedes: nothing. Applies `INV-HIST-04` (decisions record the policy version that produced
them) to its third subject, after fees (ADR-0050) and credit decisions (Phase 10's, planned).

## Context

With one rail there was nothing to choose. With several, a payment must be sent on exactly one
of them, and the choice has financial consequences. The rail decides whether the payment can
later be reversed, how long its outcome can stay unknown, which clearing position it lands in,
and whether a chargeback can follow it (ADR-0059). The Phase 7 gate asks for routing that is
"deterministic, version-pinned and explainable from stored data".

Two failure shapes make this more than bookkeeping:

- **Routing on live state that is not recorded.** An instance-local circuit breaker, or
  "whichever rail answered fastest lately", produces decisions that differ between instances
  and cannot be explained afterwards. Ten instances deciding the same kind of payment in the same
  second would disagree, and nothing stored would say why.
- **Fallback after an ambiguous dispatch.** If a send's outcome is unknown, the first rail may
  already have executed. A second dispatch on another rail is a second payment. It is the
  double-effect class `INV-PAY-04` exists for, reached through routing instead of through a retry.

## Decision

1. **The routing policy is versioned and immutable, like the fee schedule.** A policy version is
   data. It is an ordered list of rules, each matching on the payment's *direction* (in or out),
   the *instrument kind* (card token, bank account, wallet), the *currency* and an *amount band*,
   and each naming an ordered list of candidate rails. A version is immutable from creation.
   Change creates a new version, effective forward, and reprices nothing in flight. Creating a
   version is a privileged operator act, with a required reason, audited, under a permission of
   its own, `PAYMENT_ROUTING_ADMINISTER` (its role decided at `P7-TSK-003`'s design).

2. **A payment is routed once, before anything is sent, and the decision is pinned.** Routing
   happens when an intent is confirmed, and when an outbound payment (a withdrawal, or a return
   payment) is initiated. The decision is a row committed in the same transaction as the attempt
   it governs, and frozen by trigger. It records:
   - the policy version;
   - the inputs: direction, instrument kind, currency, amount, the destination's reachability
     from the grant exchange (ADR-0062), and each rail's availability observation;
   - every candidate rail, in order, with the reason it was rejected, or `CHOSEN`;
   - each rail's capability descriptor version.

   Recomputing the pinned version over the stored inputs reproduces the choice. That is the
   determinism test, and `INV-RAIL-02`'s verification.

3. **Eligibility is judged from declared capabilities, never from a rail's name.** A candidate is
   rejected, with the reason recorded, when:
   - its descriptor does not support the currency, or the amount exceeds its maximum;
   - its interaction model cannot carry the instrument (a card token cannot ride a push rail);
   - the destination is unreachable on it;
   - it is unavailable.

   The first eligible candidate is chosen. If no candidate is eligible, the payment is refused
   with `payments.NoEligibleRail` (`422`), nothing dispatched and the refusal recorded.

4. **Rail availability is a recorded fact in the shared database, never a hidden switch.**
   Availability is an operator's act (a rail disabled or re-enabled, with a reason, audited),
   plus the rail's declared operating hours. It is read from the database inside the decision's
   transaction, so N instances deciding in the same second read the same answer, and the decision
   records which observation it used. **No instance-local health state takes part in routing.**
   Automatic availability from observed failure rates is deliberately not built here. When it is
   built, it will write the same recorded fact.

5. **Fallback advances the decision only on knowledge.** A decision may move to its next
   candidate only when:
   - the chosen rail refused eligibility before dispatch; or
   - its adapter reported `NOTHING_SENT`: the connection was refused before anything left, which
     is knowledge, not ambiguity (`ProviderAnswer`'s own distinction).

   **Never after a dispatch whose outcome is unknown.** That attempt stays `*_UNKNOWN` on its rail
   until its own resolution (`INV-LIFE-03`). Each advance is an appended step on the same decision,
   so the explanation shows every rail tried, why each was abandoned, and on what evidence. A
   payment is dispatched on at most one rail at a time.

6. **Routing chooses the rail and nothing else.** The amount, the currency, the parties and the
   fee are the intent's and the merchant's (ADR-0050). Nothing about the payment's price changes
   with the rail in Phase 7. Per-rail cost is observed (a meter), not charged.

## Alternatives Considered

### Instance-local health and circuit breakers in the routing path
Pros: reacts in milliseconds.
Cons: decisions differ by instance and are not reproducible from stored data. It also puts a
JVM-local input into a financial decision, which `CLAUDE.md`'s multi-instance rule forbids.

### Route at every dispatch rather than once
Pros: always uses the current policy.
Cons: a payment could change rail mid-flight. The explanation would be several decisions with no
stored relationship between them, and a retry after an ambiguous dispatch could land on a
different rail.

### Hard-coded routing, one rail per instrument kind
Pros: trivial, and true today for most pay-ins.
Cons: no versioning, no explanation, no operator control. It leaves no place for the choice that
is real in Phase 7: an outbound payment that could go on more than one push rail, or a rail taken
out of service.

## Consequences

Positive:
- The gate's routing criterion becomes a stored-data property with its own recomputation test.
- Taking a rail out of service is an audited operator act, applied consistently by every instance.
- A double dispatch through fallback is structurally impossible: the decision has one open step,
  and the attempt a single live row per intent (`V003`'s partial unique index, Phase 5).

Negative:
- Every payment pays one more row and one more read at routing time.
- With one eligible rail per pay-in instrument today, most decisions have a single candidate.
  The machinery is ahead of its heaviest use, which arrives with the outbound push rails.

Operational impact: a routing-decision meter by chosen rail and rejection reason (bounded
tags); an operator read of one decision's explanation.
Security impact: one new permission and its audited act; the explanation is operator-only.
Financial impact: none on amounts. The rail decides the clearing position (ADR-0059 §4).

## Invariants / Constraints

`INV-HIST-04` (its routing element, Phase 7), `INV-RAIL-02` (catalogued with this ADR),
`INV-PAY-04` (no second dispatch through fallback), `INV-LIFE-03`.

## Follow-up

- `P7-TSK-003` builds the policy, the decision, the operator surface and `RailSelected`.
- Automatic availability from failure rates: recorded as a candidate for Phase 15 or 16, and it
  must write the same recorded fact.
