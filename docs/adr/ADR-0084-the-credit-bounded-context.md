# ADR-0084 — The credit bounded context: data, assessment, policy and decision kept apart in one module that moves no money, and the risk score is risk's

Status: Proposed
Date: 2026-10-07
Phase: 10
Context: Credit · Consent · KYC · Risk · Platform
Supersedes: nothing. Makes `CREDIT_MODEL.md` (Phase 0) concrete. Settles the question
`GLOSSARY.md` §10 and `MODULE_ARCHITECTURE.md` left to "Phase 10 or 13" — who owns the risk
score. Resolves, with ADR-0086 and ADR-0087, `docs/adr/README.md`'s anticipated Phase 10 decision
"Credit policy versioning and decision reproducibility", and with ADR-0085 "Bureau adapter and
credit-data retention". Rests on `PHASE_10_PLAN.md` §1, §3, §4, §12.1 and §17.

## Context

Phase 10 builds credit decisioning — the first capability whose output is a *judgement* about a
person rather than a movement of money. The repository already decided several things that
constrain where it lives:

1. **The concepts must not collapse.** `CLAUDE.md` §Domain Distinctions separates Credit Score /
   Risk Score / Credit Decision / Underwriting, and `CREDIT_MODEL.md` separates credit data,
   credit profile, risk assessment, underwriting, decisioning and loan servicing.
   `INV-CRD-04` makes the separation an invariant: score, risk signal, assessment, evaluation
   and decision are separately modelled and recorded. A design in which "the decision" is a
   single row with a score column and a status column would satisfy none of them.
2. **The consistency boundary is the decision and what it was made from.** A decision is
   defensible only if the snapshot of data it used, the assessment derived from that snapshot,
   the policy evaluation over the assessment and the decision itself commit together, and if
   the exposure an approval reserves is judged in the same transaction that records it
   (`INV-CRD-06`, `INV-CRD-07`, `INV-CRD-09`). Whatever module boundary is drawn must not cut
   through that.
3. **Data collection looks like a separate concern.** Bureau and financial-data retrieval has
   its own providers, its own evidence regime and its own consent purpose; a second module was a
   real candidate.
4. **Phase 11 is lending.** `DELIVERY_PLAN.md` §Phase 11 gives loan application, offer,
   acceptance, disbursement and servicing to a later phase, with `Loan Application` a distinct
   aggregate. Credit must stop exactly where lending begins, or Phase 10 builds Phase 11 by
   accident.
5. **The risk score has had no owner.** The glossary and the module architecture left it to
   "Phase 10 or 13". Credit needs *some* risk input — a confirmed fraud flag must hard-decline —
   but computing a fraud score from fraud inputs is not credit's question.
6. **The module graph is guarded.** ADR-0006 enforces boundaries mechanically; every module so
   far reaches other modules' state only through ports `app` implements, never through a build
   edge to a peer domain.

## Decision

1. **One new module, `credit`, holding five concepts apart inside it.** Credit Data (evidence as
   received, encrypted, normalised to attributes), Credit Profile (the party's credit identity —
   one row per party, the row every decision for the party serialises on, holding no figures of
   its own), Credit Assessment (the derived figures for one request: affordability, exposure, the
   scorecard's score), Credit Policy (a versioned set of rules as data, per product) and Credit
   Decision (the recorded, immutable outcome with ordered reason codes). Each is its own
   aggregate, its own table and, where it has one, its own lifecycle (plan §3's table, §4's
   aggregates). The `CLAUDE.md` distinctions are made physical:
   - a **credit score** is one derived figure on the assessment — the scorecard's points over the
     snapshot (ADR-0086 §3); a bureau's own score is an *attribute*
     (`BUREAU_EXTERNAL_SCORE`), never the credit score;
   - **underwriting** is the act that weighs the figures against policy — the evaluator's run or
     a person's review of a referral (ADR-0089) — and is not the decision it produces;
   - the **decision** is a separate, born-once row (ADR-0087), never a status on the assessment.

2. **Data collection stays inside `credit`; a second module was weighed and refused.** Its only
   consumer is credit's own snapshot. The evidence's security boundary is a table-level grant
   inside one schema (`credit_evidence` with `SELECT` revoked from the application role,
   ADR-0085 §5), which a module boundary would not strengthen. And a second module would put the
   freshness judgement (`INV-CRD-08`) and the second consent check (`INV-CRD-03`) on the far
   side of a port from the freeze and the decision that depend on them — turning one locked
   read into a cross-module protocol. One module, one consistency boundary: the decision and
   the snapshot it was made from commit together; the exposure an approval reserves is judged in
   the transaction that records it.

3. **Build edges: `credit` → `platform`, `sharedkernel` only.** Credit reaches everything else
   through ports declared in `credit` and implemented in `app`:
   - **`CreditConsentGate`** — the consent gate for the two new purposes `CREDIT_BUREAU_ACCESS`
     and `FINANCIAL_DATA_ACCESS` (consent `V003`, ADR-0037's closed `ConsentPurpose`, whose javadoc
     reserved bureau access for this phase as a single member — it becomes two when `P10-TSK-002`
     lands);
   - **`CreditPartyStanding`** — the party's verification standing (`ACTIVE`, KYC `VERIFIED`) and
     the party facts a snapshot records (age, residency country), implemented over the existing
     customer-standing port — kyc gains nothing;
   - **`CreditBureau`** and **`FinancialDataProvider`** — the provider-neutral data ports
     (ADR-0085 §1), one adapter per provider in `app`;
   - **`CreditRiskSignal`** — the risk signal (point 5) — and **`PlatformCreditExposure`** — the
     platform's outstanding credit (ADR-0088 §6).

   No edge to `consent`, `kyc`, `party`, `risk` or `ledger`. No module depends on `credit` in
   Phase 10; Phase 11's `lending` will, through credit's published decision-read port
   **`CreditDecisions`** (declared by `P10-TSK-016`, with no consumer in Phase 10).
   `CreditModuleIsolationTest` pins both directions. Every port read that a decision depends on
   is taken inside the acting transaction as a plain `READ COMMITTED` read (the gate's
   authoritative read, `ConsentGateDatabaseTest`'s lesson), and no credit transaction takes a
   lock outside `credit` (plan §7).

4. **Credit moves no money and stops where lending begins.** Credit posts nothing to the
   ledger, places no hold, disburses nothing, prices nothing. Its outputs are a decision and the
   exposure an approval *reserves* until it lapses or is consumed (ADR-0088 §2). Loan
   applications, offers and counter-offers, acceptance, disbursement, schedules, interest,
   servicing, delinquency, collections and BNPL are Phases 11–12's (plan §17). The
   `DecisionRequest` is the *input envelope* of a decision — explicitly not a loan application —
   and Phase 11's `Loan Application` will *reference* a `CreditDecision`, never be one. The
   consumption a loan will record against a decision is a separate born-once fact,
   `credit_decision_consumption`, whose table exists in Phase 10 and is written by nothing until
   Phase 11 — never a column of the decision, which no role ever updates (ADR-0087 §4).

5. **The risk score is `risk`'s (Phase 13); credit consumes a signal through a seam that
   answers `NOT_ASSESSED`.** A risk score answers a fraud question, from fraud inputs, with a
   fraud consequence; it is owned by `risk`. Credit declares the `CreditRiskSignal` port — a
   required parameter consulted in the evaluation, the shape of Phase 9's
   `CrossBorderRiskDecision` seam (ADR-0081 §8). Phase 10's composition answers `NOT_ASSESSED`
   for every party, deterministically; the snapshot records that answer as the `RISK_SIGNAL`
   attribute together with the seam's version, so a decision made before Phase 13 replays
   identically after it (`INV-CRD-01`). `MODULE_ARCHITECTURE.md` moves `Risk Score` from
   `credit` to `risk`.

6. **Credit products are a closed enumeration.** `CreditProduct` declares, per member, its
   currency, its amount and term bounds, its four-eyes threshold for manual approvals, its
   request validity, its decision validity and its evidence retention. Phase 10 has two:
   `PERSONAL_LOAN` (EUR, 500.00–25,000.00, 6–60 months) and `CREDIT_LINE` (EUR, 250.00–5,000.00,
   revolving, term absent), each with a request validity of 7 days and a decision validity of
   30 days. A new product is a reviewed code change with its migration, never a string; an
   unknown product is `422 credit.ProductNotOffered`, an amount outside the bounds
   `422 credit.AmountOutOfRange`.

7. **The closed vocabularies land first, with no behaviour.** `CreditProduct`,
   `CreditAttributeCode` (ADR-0085 §3), `ReasonCode` with its migration-seeded catalogue table
   (code, category, customer text, adverse flag; `SELECT` only) and `DecisionOutcome` are the
   first task's (`P10-TSK-001`), together with the module, the schema and the grants floor.

## Alternatives Considered

### Two modules: `creditdata` (collection, evidence) and `credit` (assessment, policy, decision)
Pros:
- The evidence and its providers sit behind a module boundary; the collection machinery could
  in principle serve another consumer.

Cons:
- No other consumer exists or is planned; the boundary would protect nothing a table grant does
  not already protect.
- Freshness (`INV-CRD-08`) and the second consent check (`INV-CRD-03`) would be judged across a
  port, outside the transaction that freezes the snapshot — either duplicated or made eventually
  consistent with the decision that depends on them.
- Two schemas, two isolation tests and a cross-module protocol for what is one locked read.

Refused (plan §3).

### Credit owns the risk score
Pros:
- Phase 10 would ship a complete-looking decision engine; no seam to carry.

Cons:
- A fraud score computed by the credit module collapses Credit Score / Risk Score, the
  distinction `CLAUDE.md` names; Phase 13 would either duplicate it or inherit a fraud model
  owned by the wrong context.
- Credit has no fraud inputs (device, velocity, behaviour) and must not acquire them.

Refused; the seam records a deterministic `NOT_ASSESSED` instead (point 5).

### Build the loan application in Phase 10 so a decision has somewhere to go
Pros:
- An end-to-end demonstration from application to approval.

Cons:
- It implements future-phase functionality (forbidden by the execution protocol) and fixes
  Phase 11's aggregate before its design.
- It invites a decision to become a status on a loan — the collapse `INV-CRD-04` forbids.

Refused (point 4, plan §17).

### Products as configuration rows rather than an enumeration
Pros:
- New products without a deployment.

Cons:
- A product carries financial semantics (currency, bounds, four-eyes threshold, validity,
  retention) that every rule and every arithmetic step depends on; a string-typed product is the
  unreviewed-policy door `CREDIT_MODEL.md` warns against.

Refused (point 6). Policy *thresholds* are data (ADR-0086); the product's *existence* is code.

## Consequences

Positive:
- Every `CLAUDE.md` credit distinction is a separate table with its own arbiter; a reviewer can
  point at the row that is the score, the evaluation and the decision.
- One consistency boundary: snapshot, assessment, evaluation and decision commit inside one
  schema; no cross-module protocol stands between a decision and its inputs.
- Phase 13 inherits a seam whose Phase 10 answers are recorded, so introducing a real risk
  signal changes future decisions only, never the replay of past ones.
- Phase 11 inherits a decision it references and an exposure reservation it consumes, with no
  Phase 10 lending artefact to unwind.

Negative:
- The `credit` module is large (twelve aggregates and facts, plan §4); its package structure must
  keep the concepts apart without a module boundary's help — `INV-CRD-04` is held by schema and
  review, not by the build.
- Four seam ports implemented in `app` (`CreditConsentGate`, `CreditPartyStanding`,
  `CreditRiskSignal`, `PlatformCreditExposure`), beside the two provider ports — wiring that a
  direct edge would have avoided, accepted as ADR-0006's price.

Operational impact: one module, one schema (`credit`), two leaderless schedules (ADR-0087 §6,
ADR-0085 §4); `NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS` grows from twenty
to twenty-two.
Security impact: credit holds the platform's most sensitive financial PII (bureau data); keeping
it in one schema with table-level grants keeps the blast radius one module wide. Three
permissions and two roles arrive in identity `V020` (`CREDIT_POLICY_ADMINISTER`,
`CREDIT_INVESTIGATE` under `CREDIT_POLICY_OFFICER`; `CREDIT_UNDERWRITE` under `UNDERWRITER`).
Financial impact: none posted. A decision is a promise-shaped fact that moves no money; the only
financially material output is the reserved exposure (ADR-0088), which bounds what Phase 11 may
lend.

## Invariants / Constraints

`INV-CRD-04` (score, risk signal, assessment, evaluation and decision separately modelled and
recorded), `INV-CRD-01` (the risk seam's answer recorded so replay survives Phase 13),
`INV-CRD-06`/`-07`/`-09` (the consistency boundary this module boundary preserves),
`INV-CRD-03` (consent reached through the gate's port, read in the acting transaction),
`INV-CNS-01` (the gate), `INV-CNS-02` (consent history append-only), ADR-0006 (boundaries
enforced mechanically), ADR-0037 (the closed `ConsentPurpose`, whose purposes are never removed —
ADR-0037's rule, not an invariant), ADR-0081 §8 (the seam with a recorded answer).

## Follow-up

- `P10-TSK-001`: the module, schema `credit` (`V001`, the grants floor),
  `CreditModuleIsolationTest`, the closed vocabularies and the seeded reason-code catalogue, the
  package documentation. No behaviour, no endpoint.
- `P10-TSK-002` / `-003`: consent `V003` (the two purposes and their texts); identity `V020` (the
  permissions and roles), each route and grant in `RoutePermissionRegisterTest` and
  `RoleNameTest`.
- The transition moves `Risk Score` to `risk` in `MODULE_ARCHITECTURE.md` and settles
  `GLOSSARY.md` §10.
- Phase 13: a real `CreditRiskSignal` composition; the seam's version changes, and decisions
  pinned to the Phase 10 version keep replaying under it.
- Phase 11: `lending` depends on `credit` through the `CreditDecisions` port and writes the
  consumption fact, `credit_decision_consumption` (ADR-0088 §4).
- **Acceptance.** The Phase 10 review (`P10-DOC-001`) reads this ADR against the code before
  accepting it, the `P9-DOC-001` precedent.
