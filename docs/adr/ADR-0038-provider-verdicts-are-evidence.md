# ADR-0038 — A provider verdict is evidence; the decision is ours, and review is a case

Status: Proposed

Date: 2026-09-09

## Context

Phase 2 integrates the platform's first external decision-shaped inputs: identity verification,
document verification, sanctions, PEP and adverse-media screening. The failure
`DELIVERY_PLAN.md` §Phase 2.17 names first is *"treating a provider's response as the decision"*
— tempting because for the happy path they coincide, and wrong because the platform, not the
vendor, answers to the regulator for the decision, and because providers disagree, time out,
return unknowns, and revise their answers.

ADR-0008 already settles the adapter boundary (provider vocabulary never enters the domain,
unknown outcomes are modelled states). What it does not settle is the **decision model** above
the adapters.

## Decision

**Three separated concepts, in one context:**

1. **A check** is one question put to one provider: its request, its raw response retained
   verbatim (`INV-HIST-02`), and its *normalised outcome* in our vocabulary — including
   `INDETERMINATE` for a timeout or an unparseable answer (`INV-LIFE-03`, arriving three phases
   before its catalogued owner, as `INV-CON-03` did in Phase 1). A check is **evidence**
   (`INV-KYC-01`).
2. **A decision** is the platform's own recorded act on a case — automated where every check is
   clean by our stated policy, otherwise a **reviewer's**. It is immutable, attributable,
   reason-carrying, and references the checks it rested on (`INV-KYC-02`). A revised provider
   answer or an updated list produces a **new case event**, never an edit of a decision
   (`INV-HIST-01`'s shape).
3. **A review task** is the explicit work item a non-clean case becomes. A screening **hit never
   auto-clears** and never auto-rejects: a name match is a probability, and both silent outcomes
   are wrong ways to handle one — silently cleared is a sanctions breach, silently rejected is a
   person refused service by string similarity. Review requires elevated authorization, records a
   reason, and is audited (`INV-KYC-04`); above-threshold approvals carry the four-eyes
   obligation `INV-AUD-04` already catalogues, to the extent one actor column can (recorded debt,
   ADR-0010).

Duplicate provider callbacks deduplicate at `DB-CONSTRAINT` through the platform inbox
(`INV-IDEM-04`, `P0-TSK-021`'s mechanism keyed per consumer) — Phase 2 does not invent a second
dedupe.

## Alternatives Considered

### Provider outcome mapped directly to case status
Pros: least code.
Cons: the vendor becomes the decision-maker of record; a flaky provider flaps a case; nothing to
show a regulator but a third party's JSON.

### A rules engine for decisioning
Pros: policy changes without deploys.
Cons: ADR-0031's reasoning — an engine is right when policy changes faster than code, which is
Phase 13's world, not onboarding policy. A versioned policy in code, pinned on the decision
(`INV-HIST-04`), is auditable and reproducible.

### Chosen: check / decision / review, policy in code
Pros: every gate criterion becomes a property of a named record; provider failure modes map onto
the harness `P0-TSK-037` already built.
Cons: more tables than a status column — the point, not a cost.

## Consequences

Positive: "why was this person approved?" is answered by one decision row and its referenced
evidence, permanently.
Negative: reviewer tooling is API-only in Phase 2 (no UI); accepted, as all operator tooling is.
Security impact: review endpoints are the phase's privileged surface, behind
`@RequiresPermission` with negative tests — the `P1-TSK-028` shape.
Financial impact: none directly; Phase 3+ gates on the decision via ADR-0035's projection.

## Invariants / Constraints

`INV-KYC-01`…`06`, `INV-IDEM-04`, `INV-LIFE-02`/`03`/`04`, `INV-HIST-02`, ADR-0008.

## Follow-up

Ongoing rescreening (list updates against an existing book) is Phase 13; Phase 2 records the
seam — a screening check is re-runnable against a case — and builds no scheduler.
