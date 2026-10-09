# Credit Model

Separate:
- credit data
- credit profile
- risk assessment
- underwriting
- decisioning
- loan servicing

A decision should be reproducible from:
- data inputs or references
- policy version
- model version where applicable
- reason codes
- decision outcome
- timestamp
- decision context

Never encode the entire credit policy as opaque application conditionals with no versioning or audit trail.

## Phase 10 — where this model is made concrete

*Added by the Phase 9 → 10 transition (2026-10-07); built by Phase 10's tasks and read against the
code by its exit review (`P10-DOC-001`, 2026-10-09). (This read "nothing of Phase 10 is built yet"
until the exit review.)* The statement above is unchanged and binding; Phase 10 (Credit
Decisioning) makes it concrete, in the `credit` module (schema `credit`, migrations `V001`…`V015`):

- [`PHASE_10_PLAN.md`](../project/PHASE_10_PLAN.md) — the engineering plan: the `credit` module,
  its aggregates, the decision model (snapshot, affordability, exposure, scorecard, policy,
  evaluator, explanation and replay), the multi-instance arbiters and lock order, and the tasks.
- [`CREDIT_DECISIONING_LIFECYCLES.md`](CREDIT_DECISIONING_LIFECYCLES.md) — the machines (decision
  request, credit data request, underwriting case, policy and model versions), every valid and
  invalid edge with the database rank that refuses it, and the born-once facts.
- ADR-0084 (the credit bounded context; the risk score is `risk`'s), ADR-0085 (credit data
  collection), ADR-0086 (policy and model as versioned data), ADR-0087 (the decision, its
  snapshot, explanation and replay), ADR-0088 (affordability and exposure), ADR-0089
  (underwriting) — written `Proposed` at the transition, accepted by the exit review, indexed in
  [`docs/adr/README.md`](../adr/README.md).
- `INV-CRD-01`…`INV-CRD-12` in [`FINANCIAL_INVARIANTS.md`](FINANCIAL_INVARIANTS.md) — the four
  catalogued at initiation, each given its Phase 10 subject, and the eight added by the
  transition.

Of the six concepts listed above, Phase 10 built credit data, the credit profile, the
assessment (affordability, exposure and the credit score), underwriting and decisioning. The
risk assessment's risk score is Phase 13's (`risk`), consumed by credit only as a recorded
signal (`NOT_ASSESSED` in Phase 10, recorded with its seam's version); loan servicing is Phase 11's.
