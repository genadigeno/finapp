# ADR-0086 — Credit policy and model as versioned data: rules as rows over a closed vocabulary, a deterministic evaluator with its own engine version, four-eyes activation, and the active version answerable at any instant

Status: Accepted (2026-10-09, `P10-DOC-001` — read against the code and corrected first)
Date: 2026-10-07
Phase: 10
Context: Credit · Identity · Platform
Supersedes: nothing. Resolves, with ADR-0087, `docs/adr/README.md`'s anticipated Phase 10
decision "Credit policy versioning and decision reproducibility". Takes the versioned four-eyes
policy shape of ADR-0075 §7 (pricing policy) and ADR-0080 §4 (corridor policy): versions
`PROPOSED → ACTIVE → RETIRED`, one `ACTIVE` per scope by partial unique, activation retiring the
predecessor in the same transaction, four-eyes at the domain and the `CHECK`, no version seeded
active by migration (D26). Rests on `PHASE_10_PLAN.md` §4, §5, §7, §8, §12.5, §12.6, §18 and
`INV-CRD-01`, `-05`, `-10`, `INV-HIST-04`, `INV-AUD-04`.

## Context

`CREDIT_MODEL.md` forbids encoding credit policy "as opaque application conditionals with no
versioning or audit trail". The reason is reproducibility: a decision must be re-derivable years
later from its inputs and the exact policy and model that judged them (`INV-CRD-01`). Four
pressures shape how:

1. **Policy changes more often than code, and must be reviewable as policy.** A threshold change
   is a credit-risk decision, made by credit officers, not a deployment.
2. **But arbitrary expressiveness is opacity again.** A policy language that can express
   anything (a script, an expression language) is code by another name: unreviewable, and its
   semantics drift with its interpreter.
3. **The interpreter itself is part of the decision.** If the evaluator's severity order, cap
   arithmetic or reason ordering change, every past decision's replay silently changes with it.
4. **Activation races decisions across N instances.** A decision in flight while a new version
   activates must keep the version it started with (`INV-HIST-04`), and ten approvers of one
   proposal must make one activation.

## Decision

1. **A policy version is rows over a closed vocabulary.** A `credit_policy_version` per product
   carries the policy's parameters — assessment rate, minimum disposable income, minimum payment
   ratio, maximum exposure, maximum data age per source kind, the fallback for an unavailable
   source (`REFER` or `DECLINE`, never approve), the auto-approval ceiling — and its rules as
   `credit_policy_rule` rows: `(ordinal, rule_code, attribute or derived figure, operator,
   operand, effect, reason_code)`.
   - **Operators** are closed: `LT, LE, GT, GE, EQ, NE, IN, NOT_IN, IS_ABSENT, IS_PRESENT`.
   - **Derived figures** are closed: `SCORE`, `DISPOSABLE_INCOME`, `AFFORDABLE`, `EXPOSURE`,
     `EXPOSURE_HEADROOM` (their arithmetic is ADR-0088's and the scorecard's, point 3).
   - **Effects** are closed: `HARD_DECLINE`, `DECLINE`, `REFER`, `CAP_AMOUNT` (the operand a
     ceiling).
   - Every rule names a `reason_code` from the seeded catalogue (ADR-0084 §7), enforced by
     foreign key.
   A rule the vocabulary cannot express is a new operator, figure or effect — a reviewed code
   change with a new engine version (point 2) — never a free-form expression (plan §18).

2. **The evaluator is pure, deterministic and versioned** (`engine_version` 1).
   - It reads only the snapshot (ADR-0087 §2) and the pinned versions' rows: no clock, no locale,
     no hash-map iteration order, no I/O.
   - Every rule is evaluated in ordinal order. **The outcome is the most severe effect
     triggered: `HARD_DECLINE > DECLINE > REFER > APPROVE`** (nothing triggered is `APPROVE`).
   - **The approved amount is `min(requested, every CAP_AMOUNT triggered, the auto-approval
     ceiling)`**; an approval below the request carries the cap's reason code — the triggered
     `CAP_AMOUNT` rule's, or, when the auto-approval ceiling (a policy parameter, not a rule)
     binds, the catalogue's `CRD-AUTO-APPROVAL-CEILING`.
   - **Reason codes are the triggered rules' codes in ordinal order, deduplicated keeping the
     first.** An adverse outcome with no reason code is unrepresentable — in the domain, by the
     `CHECK` on the evaluation (`policy_evaluation_adverse_has_a_reason`, credit `V009`) and, for
     the decision, by the deferred constraint trigger `credit_decision_is_explained` (credit `V011`:
     a decline or an approval below its request commits only with its reason rows) (`INV-CRD-02`).
     *(Corrected by `P10-DOC-001`: this read "the `CHECK` on the evaluation and the decision"; the
     decision's reasons are rows, which a `CHECK` cannot see.)*
   - A rule, the scorecard or the arithmetic reading an attribute the snapshot lacks is an
     **evaluation error**, never a default (`INV-CRD-07`); `ABSENT` is a value, reasoned about
     with `IS_ABSENT` / `IS_PRESENT`.
   - **Changing the evaluator's semantics is a new `engine_version`, and every old one stays in
     the code, forever, for replay** (`INV-CRD-01`, failure scenario 26). The evaluation records
     the engine version it ran under; replay dispatches on it.
   The evaluation is recorded as a `policy_evaluation` row (outcome, engine version, pinned
   versions) with its triggered rules in order (`policy_evaluation_rule`), born once per
   assessment (`UNIQUE (assessment_id)`, `INV-CRD-06`). *(As built (credit `V009`, read by
   `P10-DOC-001`, 2026-10-09): `policy_evaluation` records the pinned policy version and the
   engine version — not the model version, which its assessment and snapshot carry — and
   `policy_evaluation_rule` records **every** rule, each with whether it was assessed and whether
   it triggered, not only the triggered ones.)*

3. **The scorecard is a points table, versioned the same way.** A `scorecard_model_version` (model
   family `RETAIL_SCORECARD`) holds a base and, per attribute, ordered bands — `[lower, upper)`
   or a code set — each with integer points, plus a declared band for `ABSENT`. Score = base + Σ
   the points of the band each attribute falls in. Integer arithmetic only. The score is one
   derived figure on the assessment (`SCORE`), never the decision (`INV-CRD-04`). Machine-learned
   models are out of scope (plan §17); a statistical model is a later model family under the same
   versioning.

4. **Versions are immutable, four-eyes, and one is `ACTIVE` per scope** (`INV-CRD-05`,
   `INV-AUD-04`).
   - The machine: `PROPOSED → ACTIVE → RETIRED`, `PROPOSED → REJECTED`, held by a status
     `CHECK` (a hand-written `IN` list — *corrected by `P10-DOC-001`; this read "generated"*), an
     every-writer edge trigger — the control — and the domain.
   - **Proposal** carries the full rule set (or points table) as one body, written in the
     proposing transaction; **rules and bands are born with their version and immutable for
     every writer from insert** — a trigger refuses any `UPDATE` or `DELETE` of them, and any
     insert into a version outside its proposing transaction, in every status (failure scenario
     24); the domain offers no edit — a correction is a rejection and a new proposal.
   - **Approval** is a second person: approver ≠ proposer at the domain and by `CHECK`, each rank
     proven alone (`403 credit.SelfApprovalRefused`). Rejection carries a reason. *(As built
     (credit `V006`/`V008`, read by `P10-DOC-001`): the column is `decided_by`, and the four-eyes
     `CHECK` binds only `ACTIVE` and `RETIRED` — any holder, the proposer included, may reject a
     proposal (a withdrawal), reasoned.)* Proposal,
     activation and rejection are distinct audited acts (`credit.PolicyVersionProposed` /
     `credit.PolicyVersionActivated` / `credit.PolicyVersionRejected`, and for scorecards
     `credit.ScorecardVersionProposed` / `credit.ScorecardVersionActivated` /
     `credit.ScorecardVersionRejected`). A points table whose bands overlap or leave a gap, lack an
     absent band, or name a code outside the vocabulary is refused at proposal,
     `422 credit.ScorecardInvalid`.
   - **Activation** locks the proposal `FOR UPDATE` conditional on `PROPOSED`, then the active
     row `FOR UPDATE` to retire it, and commits the retirement beside its successor in one
     transaction. Ten approvers: one activation, nine `409 credit.PolicyStale`.
   - **One `PROPOSED` and one `ACTIVE` per product** (per model family for scorecards) by partial
     unique; a second proposal while one is pending is `409 credit.ProposalPending`.
   - **No version is migration-activated**: scorecard v1 is seeded *as a proposal* by the task
     that builds it and activated by two persons in the suites and the runbook alike — the rule-set
     precedent (D26). Policy v1 per product goes through the same door. *(As built (`P10-DOC-001`,
     2026-10-09; Follow-up `P10-TSK-011` (1)): the seeds' proposer is the reviewed migration —
     `migration:V006` for `RETAIL_SCORECARD` v1, `migration:V008` for `PERSONAL_LOAN` and
     `CREDIT_LINE` v1 — so each v1 is activated by **one** person holding
     `CREDIT_POLICY_ADMINISTER`, distinct from its proposer by the same `CHECK`; every later version
     is one person's proposal and another's approval. No version is migration-activated.
     `OPERATIONS_RUNBOOK.md` §6 is the procedure.)*
   - Activation emits `credit.CreditPolicyVersionActivated` / `credit.ScorecardModelVersionActivated`
     (version id, product or family, effective-from, predecessor; never the rules).

5. **Which version was active at any past instant is answerable from the rows** (`INV-CRD-05`).
   Each version records `effective_from` and, on retirement, `effective_to`, both from the
   database clock and never behind the version before them (`X-TSK-017`, below); the intervals of
   one scope never overlap. `GET /v1/operator/credit/policies
   ?product=&at=` (under `CREDIT_INVESTIGATE`) answers the version active at an instant. A
   decision does not rely on this query — it **pins** the versions it used (point 7) — but an
   auditor asking "what policy was in force on that date" gets an exact answer.

6. **A policy must be complete before it may be proposed** (`INV-CRD-10`). A policy without a
   fallback rule for every source kind it reads — an `IS_ABSENT` / `SOURCE_UNAVAILABLE` rule
   with the declared fallback effect (`REFER` or `DECLINE`) and reason `CRD-SOURCE-UNAVAILABLE`
   — is refused at proposal, `422 credit.PolicyIncomplete`. Every approving path therefore
   requires the source's attributes `IS_PRESENT`, and an approval can never arise from missing
   data. *(As built: the fallback requirement is judged when the `CreditPolicy` is constructed
   from the proposal's body, Follow-up `P10-TSK-012` (2).)*
   **A policy must also bound its exposure** (`INV-CRD-09`; *added by `P10-DOC-001`, 2026-10-09,
   a code correction*). The evaluator judges exposure only through rules, so a four-eyes policy
   with no rule refusing past its maximum exposure would have approved past its own limit. The
   proposal door (`CreditPolicyAdministration.propose`) now refuses such a policy
   `422 credit.PolicyIncomplete`: `CreditPolicy.boundsExposure()` requires a rule
   (`PolicyRule.refusesExposurePast(limit)`) guaranteed to stop such an approval — `EXPOSURE_HEADROOM`
   `LT` or `LE` x with x ≥ 0, or `EXPOSURE` `GT` or `GE` x with x ≤ the maximum exposure, with
   effect `HARD_DECLINE`, `DECLINE` or `REFER`, never `CAP_AMOUNT`. It is judged at proposal, not at
   construction, so every stored version still reads back. Both seeded v1 policies carry one
   (`EXPOSURE_LIMIT`: `EXPOSURE_HEADROOM LT 0`, `DECLINE`, `CRD-EXPOSURE-LIMIT`).

7. **Pinning under concurrency** (`INV-HIST-04`, failure scenarios 16–17). The versions are
   **pinned on the request at `SUBMITTED → COLLECTING`** — the progress step reads the `ACTIVE`
   policy and model versions `FOR SHARE` and writes them on the request once (the claim-time
   precedent: the sources collected, and their maximum age, are the pinned policy's, so an
   activation between collection and the freeze can never leave the evaluating policy reading a
   source nobody collected). The freeze, the evaluation and the deciding transaction re-read
   exactly those rows `FOR SHARE` — even after their retirement — and record them on the snapshot,
   the evaluation and the decision; activation's `FOR UPDATE` on the active row waits for each
   share lock. The decision keeps the versions it pinned even if a successor activates before the
   decision is recorded — never a retired-but-unpinned mix. The pinned versions are the last step of the credit lock order
   (plan §7: profile → request → case → data requests → versions `FOR SHARE`). *(As built
   (`P10-DOC-001`, 2026-10-09): the versions are recorded on the snapshot (policy, model, engine),
   the evaluation (policy, engine) and the decision (policy, model, engine). The review found the
   evaluating step (`DecisionProgress.evaluate`, `READY → EVALUATED`) sharing the pinned versions
   (5) before locking its data requests (4); no deadlock was possible — no writer holding a data
   request's lock waits on a version — but the order was not the documented one. **Corrected by
   `P10-DOC-001`:** the step now locks its data requests `FOR UPDATE` by id
   (`SnapshotFreezer.lockDataRequests`) before sharing the versions, so the order holds in every
   credit transaction.)*

8. **Advisory namespace `10` serialises the administration's writers.** The policy and model
   writers take `pg_advisory_xact_lock(10, hashtext(product))` / `(10, hashtext(family))`,
   blocking, so ten concurrent proposers leave exactly one proposal and the partial unique is the
   backstop rather than the only arbiter. The administration takes only version rows, in its own
   order (the proposal, then the active row). Registered in `DISTRIBUTED_EXECUTION.md` §3 by
   `P10-TSK-011`, its first writer (the scorecard administration); `P10-TSK-012` extends it to
   the policy. *(As built (`P10-DOC-001`): only proposals take namespace 10
   (`CreditPolicyAdministration.propose`, `ScorecardAdministration.propose`); approval and
   rejection arbitrate on the version rows `FOR UPDATE` alone.)*

## Alternatives Considered

### Policy as code (Java conditionals behind a version constant)
Pros:
- Full expressiveness; type-checked; no interpreter.

Cons:
- Exactly what `CREDIT_MODEL.md` forbids: a threshold change is a deployment, the version is a
  label nobody enforces, and four-eyes on policy becomes code review by engineers rather than
  approval by credit officers.
- Old versions vanish from the code unless deliberately kept, so replay decays.

Refused.

### An expression language or rules engine (SpEL, Drools, a scripting DSL)
Pros:
- Credit officers could express any rule without a deployment.

Cons:
- Opacity again: an arbitrary expression is unreviewable as policy, and its semantics are the
  library's, versioned by a dependency bump nobody connects to replay.
- Determinism (ordering, numeric types, null semantics) is not ours to guarantee.

Refused: a closed vocabulary whose every member is ours, extended only by a new engine version
(point 1).

### Evaluate in first-match order (the first triggered rule decides)
Pros:
- Familiar from firewall rules; cheap.

Cons:
- The outcome depends on rule *order* rather than *severity*: a misplaced `REFER` above a
  `HARD_DECLINE` would refer a confirmed insolvency. Reason codes would stop at the first rule,
  leaving the adverse-action explanation incomplete.

Refused: all rules evaluated, the most severe wins, all reasons kept in ordinal order (point 2).

### Edit a version in place while it is `PROPOSED`
Pros:
- Small corrections without a rejection round-trip.

Cons:
- The approver would approve something other than what was proposed unless every edit
  re-opened review; the audit trail of a proposal would no longer be its content.

Refused: a proposal is written whole and corrected by rejection and re-proposal (point 4).

### Seed v1 active by migration
Pros:
- Decisions possible from first boot.

Cons:
- The first credit policy would enter history with no named approver — the D26 rule this
  repository has held since Phase 8.

Refused (point 4).

## Consequences

Positive:
- Every decision is replayable against the exact rule rows, points table and engine semantics
  that made it; tampering with a frozen rule is refused by trigger, and if forced, caught by
  replay (`P10-TSK-019`).
- Credit officers change policy through an audited four-eyes door with no deployment; engineers
  change the vocabulary through a reviewed engine version.
- Ten instances agree: one proposal, one activation, every in-flight decision on its pinned
  version.

Negative:
- The vocabulary is deliberately small; a rule it cannot express waits for a new engine version.
- Every engine version stays in the code forever — a growing, intentionally permanent surface.
- Activation waits for in-flight evaluations holding the active row `FOR SHARE` — a short,
  bounded wait.

Operational impact: `finapp.credit.policy.active{product}` (alerting when an offered product has
no active version), `finapp.credit.decision{…, policy_version}`; the runbook gains the two-person
activation of policy and scorecard v1. *(As built (`P10-DOC-001`, 2026-10-09): the runbook entry
was not delivered by `P10-TSK-011`/`-012`; the review wrote it — `OPERATIONS_RUNBOOK.md` §6 —
and, because the seeds' proposer is the migration, v1's activation is one person's act (§4's
note). The gauge and its alert, `CreditPolicyMissing`, cover the policy only: no gauge reports a
missing `ACTIVE` scorecard, which equally holds every request at `SUBMITTED`.)*
Security impact: `CREDIT_POLICY_ADMINISTER` (held by `CREDIT_POLICY_OFFICER`) proposes, approves
and rejects — never approving one's own proposal (*corrected by `P10-DOC-001`*: any holder, the
proposer included, may reject — a withdrawal — with a reason); every act audited with a reason
where required.
Financial impact: none posted. Policy fixes the *risk appetite* — the ceilings and limits that
bound what Phase 11 may lend.

## Invariants / Constraints

`INV-CRD-01` (deterministic, versioned evaluation; old engines kept), `INV-CRD-02` (no adverse
outcome without reasons), `INV-CRD-04` (score is not decision), `INV-CRD-05` (immutable versions,
one active per scope, active-at-instant answerable), `INV-CRD-06` (one evaluation per
assessment), `INV-CRD-07` (no default for a missing attribute), `INV-CRD-10` (policy
completeness), `INV-HIST-04` (pinned versions never change under a decision), `INV-AUD-04`
(four-eyes, no seed), ADR-0075 §7, ADR-0080 §4.

## Follow-up

- *As built by `P10-TSK-011` (2026-10-08), the scorecard half of §3.* `credit V006`: `scorecard_model_version`, `scorecard_band`, `scorecard_model_event`; `V007`: `credit_assessment`. Three decisions taken in the building, recorded here. (1) **The seed's proposer is the reviewed migration** (`migration:V006`): no model is migration-activated, so v1 is activated by one person, distinct from its proposer by the same `CHECK` as every version; every later version is one person's proposal and another's activation (both shown over HTTP by `ScorecardAdministrationEndpointDatabaseTest`). (2) **The decider column is `decided_by`**, the corridor-policy precedent, not the plan's `approved_by` - a rejection's decider is not an approver; the four-eyes `CHECK` binds `ACTIVE` and `RETIRED`. (3) **A scorecard bands credit behaviour, never money**: `INTEGER` attributes take contiguous `[lower, upper)` ranges covering every value, `BOOLEAN` and `CODE` attributes disjoint code sets; a `MONEY` attribute is affordability's and exposure's and a marker is not scored; a code in no set is `UnscoredValue`, never a zero. A missing idempotency key answers the platform's `api.IdempotencyKeyRequired` (`422`), and `credit.SelfApprovalRefused` is a `403`.
- *As built by `P10-TSK-012` (2026-10-08), the policy half of §§1, 4-6 and 8.* `credit V008`: `credit_policy_version`, `credit_policy_rule`, `credit_policy_event`, and the pinned policy's foreign keys from `decision_snapshot` and `credit_assessment`. Five decisions taken in the building, recorded here. (1) **The source kinds a policy reads are exactly those it declares a maximum data age for** - one fact, so "reads a kind" and "has an age for it" cannot disagree. (2) **"A fallback for every source kind it reads" means a rule GUARANTEED to trigger whenever that kind is unavailable**, decided from the rule alone: it carries the declared fallback effect and `CRD-SOURCE-UNAVAILABLE`, and is `SOURCE_UNAVAILABLE IS_PRESENT`, `SOURCE_UNAVAILABLE IN` a set holding every marker value naming the kind, or `IS_ABSENT` on one of the kind's own attributes (an unavailable source leaves all of them `ABSENT`) - any other shape could miss an outage, so it does not count. (3) **Every effect is adverse** - a hard decline, a decline, a referral or a reduced amount - so every rule carries an adverse code; `CRD-AUTO-APPROVAL-CEILING` belongs to the ceiling parameter, never to a rule. (4) **Money and rates are exact and explicit**: the version states its currency and scale once (the product's, refused otherwise), amounts are integer minor units, rates integer basis points; a `CAP_AMOUNT` rule carries its ceiling in its own column beside its condition's operand. (5) **Only an attribute can be `IS_ABSENT` / `IS_PRESENT`**: a figure is derived, and its absence is the evaluator's error (`INV-CRD-07`), not a rule's condition. The read with no instant answers the version `ACTIVE` by status, so no application clock decides what is in force.
- *As built by `P10-TSK-013` (2026-10-08), §2 - the evaluator.* `credit V009`: `policy_evaluation` (born once per assessment, the engine and pinned policy versions recorded, an adverse outcome without a reason refused by `CHECK`, an approval's amount and its one binding code by `CHECK`, every code the catalogue's by the birth trigger) and `policy_evaluation_rule` (every rule's ordinal, code, effect, and whether it was assessed and triggered - born with its evaluation by `V006`'s `xmin` test). `PolicyEvaluatorV1`, held by `EngineVersions` (replay selects the PINNED engine; a version the build lacks is a defect, never a fallback to another), and `PolicyEvaluations`. Four decisions taken in the building, recorded here as engine 1's semantics - changing any is engine 2. (1) **A comparison of a missing value is `UNASSESSED`**: an `ABSENT` attribute, or a figure the assessment could not compute, never satisfies `LT`…`NOT_IN` (nor their negations) - it is recorded as not read; only `IS_ABSENT`/`IS_PRESENT` reason about absence, which is §14 row 2's "rules decide explicitly"; an attribute the snapshot was never given remains an evaluation error (`INV-CRD-07`). (2) **An approval never rests on an unread value** (`INV-CRD-10`'s statement: a partial answer never becomes an approval): when every rule would approve but one was `UNASSESSED` - a partial answer the policy did not decide explicitly - the policy's declared fallback decides (`REFER` or `DECLINE`, `CRD-SOURCE-UNAVAILABLE`), recorded as `fallback_applied`; an adverse outcome is never softened by it. §6's proposal check covers a whole source missing; this covers the attribute a policy forgot. (3) **An approval's reasons are its binding cap's code alone**: below the request, the first triggered `CAP_AMOUNT` rule whose cap is the approved amount (a rule tied with the ceiling gives the rule's own, the judgement on the applicant), else `CRD-AUTO-APPROVAL-CEILING`; a full approval gives none, so a cap above the request explains nothing. An adverse outcome gives every triggered rule's code - caps' included - in ordinal order, the first kept. (4) **The rule list's order is its ordinals**: the evaluator reads `CreditPolicy.rules()` as ordinal 1…n, the store loads it `ORDER BY ordinal`, and a set operand's codes are held sorted, so no load order reaches a result (`EvaluatorDeterminismTest`).
- *Amended by `X-TSK-017` (2026-10-09), §5 - a database clock that steps back.* V006 and V008 stamped both ends of a period with `transaction_timestamp()`, and the database clock is not monotonic (ADR-0063's Option B: measured stepping back on the local VM). An activation whose transaction began on a clock still behind its predecessor's `effective_from` stamped an `effective_to` before it, and `effective_coherent` refused the retirement, `23514`, surfacing as an opaque `CreditStorageException` (met intermittently by `UnderwritingCaseDatabaseTest` under load). `credit V014` replaces only the two stamps, in ADR-0063 decision (2)'s form and `X-TSK-013`'s database-permit shape: a retirement stamps `GREATEST(transaction_timestamp(), effective_from + 1 µs)`, an activation `GREATEST(transaction_timestamp(), the scope's latest effective_to)` - the predecessor's, retired first in the same transaction. The continuity trigger's equality, the `CHECK`s and `INV-CRD-05` are unchanged; every activated version keeps a period of at least one microsecond, so the version active at any instant stays one row. Rejected: refusing with a named retryable error (an administrative act unavailable for as long as the clock stands behind, the order already decided by the row locks), and a test-only guard (it leaves production's step unhandled). Cost: inside a step, a period's start can lie ahead of the database's own clock by at most the step; a decision is defended by its pins (§7), never by this read.

- `P10-TSK-011`: the scorecard model version, its points table and administration; v1 seeded as a
  proposal; advisory namespace `10` registered. `-012`: the policy version, rules as rows, the
  rule-immutability trigger, four-eyes, completeness, namespace `10` extended to products.
  `-013`: the evaluator, `engine_version` 1, its hermetic decision-rule battery. `-015`: the pin at
  `SUBMITTED → COLLECTING`.
- `P10-TST-002`: the reproducibility battery — every reason code exercised, every decision
  replayed `IDENTICAL`, a perturbed rule flipping the verdict.
- **As built** (2026-10-09, read against the code by `P10-DOC-001`): every item above is built —
  `P10-TSK-011` (credit `V006`/`V007`, `ScorecardAdministration`, namespace 10), `-012` (`V008`,
  `CreditPolicyAdministration`, the rule-immutability trigger, four-eyes, completeness), `-013`
  (`V009`, `PolicyEvaluatorV1` held by `EngineVersions`), `-015` (the pin at
  `SUBMITTED → COLLECTING`, `DecisionProgress.collect`) and `P10-TST-002` (the battery), all
  `COMPLETE`; and `X-TSK-017`'s credit `V014` stamps the period ends with `GREATEST`, as its
  amendment above records. The review's code corrections: the exposure-bound requirement at
  proposal (§6) and the evaluating step's lock order (§7). Its text corrections: the decision's
  reason requirement is a deferred constraint trigger (§2); what the evaluation records (§2); the
  status `CHECK` (§4); rejection by any holder (§4, Security impact); the seeds activated by one
  person (§4); the prefixed event types (§4); namespace 10 taken by proposals only (§8); the
  runbook entry written by the review (Operational impact).
- **Acceptance.** The Phase 10 review (`P10-DOC-001`) read this ADR against the code, corrected it
  where it had drifted, and accepted it on 2026-10-09.
