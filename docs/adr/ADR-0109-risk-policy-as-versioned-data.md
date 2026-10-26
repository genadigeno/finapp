# ADR-0109 — Risk policy as versioned data: rule kinds in code, parameters as data, score and fail-safe bands, four-eyes activation, the deterministic engine and replay

Status: Proposed
Date: 2026-10-22
Phase: 13
Context: Risk · Identity · Audit · Platform
Supersedes: nothing. Applies ADR-0107 (the module) and ADR-0108 (the decision model the policy maps to; the pin at
R1). Follows the credit policy precedent the plan names — policy and model as versioned data with a deterministic
engine, test-only fixtures, production activating none — and the Phase 12 → 13 gate's A3-I1 rule for a pin that reads
"neither" during an activation. Rests on `PHASE_13_PLAN.md` §2.1 ("rules and thresholds are versioned artefacts"),
§2.2 (C1, C7, C8), §4, §5, §7.1 (T-pol), §7.2 (R1), §7.4, §9, §12.2, §12.3, §13.3 and `INV-RSK-04`, `-05`.

## Context

1. **Rules change faster than code, and must be explainable.** `DELIVERY_PLAN.md` §Phase 13 §5 requires rules and
   thresholds to be versioned artefacts and every decision to record the version it was made under. A threshold in
   a Java conditional is changed by a release, reviewed by a developer, and unreadable to the officer who answers for
   it; a threshold in a document is applied by nobody.
2. **No machine-learned model** (`DECISIONS.md` §Deferred). A score must be an explainable sum of rule
   contributions, reproducible from the sealed inputs in any JVM; a model adds a reproducibility burden without the
   domain insight the phase needs.
3. **The fail-safe and the thresholds are the owner's to change** (C1, C7). If they were constants, changing them
   would be a code change under the engineering gate rather than an activation under four eyes.
4. **Activation races evaluation.** An evaluation runs in a money transaction and must pin exactly one version; an
   activation runs in its own transaction and must never wait on a money row. The gate's A3-I1 found a pin that
   could read "neither" between a retirement and an activation; the rule it set is applied here forward.

## Decision

1. **A policy version is data, per operation** (plan §12.2). It holds: the operation; the engine version it is
   written for (`RISK_ENGINE_V1`); its **rules** — each a `RuleKind` from the closed vocabulary with typed
   parameters, a weight (its score contribution when it fires) and/or a hard decision; **score bands** mapping
   intervals of [0, 1000] to decisions; **fail-safe bands** mapping amount bands to the decision taken when the
   evaluation is unavailable (C1; ADR-0108); the **challenge assurance** (`MULTI_FACTOR`); the **override threshold**
   (C7); **effective from**; the proposer, the activator and the decision stamps, on the database clock. A band
   mapping to a decision the operation does not admit is refused at the door (`422 risk.PolicyInvalid`).

2. **Rule kinds are code; their parameters are data.** `RuleKind` v1 is a closed enum: `AMOUNT_BAND`,
   `VELOCITY_COUNT`, `VELOCITY_SUM`, `NEW_PAYEE`, `NEW_DEVICE`, `SESSION_ASSURANCE_BELOW`, `RECENT_AUTH_FAILURES`,
   `RECENT_CREDENTIAL_CHANGE`, `RECENT_RECOVERY`, `ACCOUNT_AGE_BELOW`, `KYC_NOT_APPROVED`, `SCREENING_HIT`,
   `PROFILE_RATING`, `MERCHANT_SUSPENDED`, `CORRIDOR_HIGH_RISK`, `DORMANT_THEN_ACTIVE`, `RAPID_IN_OUT`, `STRUCTURING`,
   `ROUND_AMOUNT`, `REFUND_RATIO`, `CHARGEBACK_RATIO`, `BNPL_MULTI_APPLICATION`. Each kind declares its parameter
   schema (window kind, threshold count or amount, lookback days, ratio basis points, the set of corridors, …) and
   the inputs it reads; a parameter outside its schema is `422 risk.PolicyInvalid` at the policy door, so a policy
   that cannot be evaluated is never stored. A new kind is a code change with a new engine version; a new threshold
   is a new policy version. Nothing in a rule is an expression, a script or a reference to code by name.

3. **The engine is deterministic and pure.** `RISK_ENGINE_V1` takes the sealed inputs and the pinned version and
   returns the hits, the score (Σ contributions, clamped to [0, 1000]) and the decision: rules are evaluated in the
   version's declared order, every rule is evaluated (no first-match), a hard rule's decision beats a score band's,
   and when rules disagree `DENY` > `HOLD` > `REVIEW` > `CHALLENGE` > `ALLOW` (ADR-0108). The same sealed inputs give
   the same hits, score and decision in any JVM; the battery proves it in two with ≥ 10,000 generated subjects and a
   committed fingerprint, and a perturbation of one input, one parameter or the version flips the verdict and names
   its row (plan §13.3). The engine reads no clock, no row and no configuration: the window figures and the
   database time are inputs sealed by T-eval.

4. **Replay.** `POST /v1/operator/risk/assessments/{id}/replay` re-runs the engine named by the assessment over its
   sealed inputs and answers `IDENTICAL` or `DIVERGED` (`INV-RSK-01`), audited `risk.Replayed`; it writes nothing. A
   `DIVERGED` is a defect of the engine or a corrupted row, never a changed policy — the policy is pinned by id.

5. **The lifecycle, four eyes, immutability** (plan §5; `RISK_LIFECYCLES.md`). `PROPOSED → ACTIVE` by a **second**
   `RISK_POLICY_ADMINISTER` holder with a `MULTI_FACTOR` session, `| REJECTED`; `ACTIVE → RETIRED` only beside its
   successor's activation, in one transaction. A version is immutable once decided (`INV-RSK-05`): a change is a new
   `PROPOSED` version. The proposer never activates (`403 risk.SelfApprovalRefused`, recorded `FAILED`; a four-eyes
   `CHECK` holds every writer to it; `INV-RSK-07`). Routes `POST /v1/operator/risk/policies`, `…/{id}/activation`,
   `…/{id}/rejection`, keyed `risk.policy:<actor>`; reads under `RISK_POLICY_ADMINISTER` or `RISK_INVESTIGATE`. Audit
   `risk.PolicyProposed/Activated/Rejected/Retired`; event `RiskPolicyActivated` (operation, version).

6. **One `ACTIVE` per operation, and the pin that never reads "neither."** A partial unique index holds one
   `ACTIVE` row per operation. **T-pol** (activation) takes `pg_advisory_xact_lock(13, hashtext(operation))` first,
   then retires the predecessor and activates the successor in one transaction — so there is never a committed
   instant with zero `ACTIVE` versions for an operation that had one. **T-eval's pin** (R1) reads the `ACTIVE` row
   `FOR SHARE`; when its first read is empty it takes `pg_advisory_xact_lock_shared(13, hashtext(operation))` and reads
   once more, so an evaluation that arrives inside an activation waits for it and pins the successor rather than
   answering `NO_POLICY_ACTIVE` falsely. The empty-read case is the only one that takes the advisory lock, so the
   normal path costs one row read. (R1) conflicts only with T-pol, which holds no money row, so the activation never
   waits on a money transaction and no money transaction waits on anything but the activation's own commit.

7. **Policy v1 content is test-only** (C8). Per operation a fixture version with `AMOUNT_BAND` (challenge at
   EUR 500, deny at EUR 10,000 for transfers; withdrawals hold at EUR 2,500), `VELOCITY_COUNT` (10 per 24 h per source
   account), `VELOCITY_SUM` (EUR 5,000 per 24 h per party), a `NEW_PAYEE` + `AMOUNT_BAND` composite weight,
   `NEW_DEVICE`, `RECENT_CREDENTIAL_CHANGE` + withdrawal → `HOLD`, `SCREENING_HIT` → `DENY`,
   `PROFILE_RATING = PROHIBITED` → `DENY`; a monitoring version with `STRUCTURING` (≥ 3 movements each below EUR 1,000
   summing above EUR 2,500 in 24 h), `RAPID_IN_OUT` (≥ 80 % out within 24 h of in), `DORMANT_THEN_ACTIVE` (90 days
   dormant, then ≥ EUR 1,000 in 24 h), `ROUND_AMOUNT`, `CORRIDOR_HIGH_RISK`; and the C1 fail-safe bands. The fixtures
   are the battery's and the storm's input; **production activates none** — no version is seeded `ACTIVE` by
   migration, and the activation gate (`OPERATIONS_RUNBOOK.md` §9) is a person's recorded act.

8. **Policy and engine drift are refused mechanically.** The door validates every parameter against its kind's
   schema; the battery replays every fixture version under the engine in two JVMs; a version written for an engine
   version the code no longer carries is refused at activation (`risk.PolicyInvalid`), and an assessment sealed under
   it still replays through the engine version it names.

9. **Would this remain correct with ten instances?** Ten activations of one operation serialise on advisory
   namespace 13 and the partial unique; ten evaluations behind an activation each pin exactly one version, none
   "neither" (`RiskPolicyDatabaseTest#anEvaluationBehindAnActivationPinsTheSuccessor`; scenario 3); the proposer's
   self-activation is refused in the statement and by `CHECK` on every instance and recorded `FAILED` (scenario 15).
   The engine holds no state, so two instances evaluating one sealed input agree byte for byte (the battery's two
   JVMs). Effective-from and every decision stamp are the database's clock, read after the row lock.

## Alternatives Considered

### Policy as code — thresholds in Java behind a version constant
Pros: type-checked, reviewed, no door to validate.
Cons: a threshold change is a release; the officer who answers for the policy cannot read or change it; four eyes
would be a code review, not a recorded activation. Refused.

### An expression language or rules engine (SpEL, Drools, a scripting DSL)
Pros: any rule expressible without a code change.
Cons: a rule becomes a program stored as data — unbounded in what it reads and costs, unverifiable at the door,
and a determinism claim the battery could not make over an open language. Refused: the vocabulary of kinds is closed
in code and only parameters are data.

### A machine-learned score
Refused by `DECISIONS.md` §Deferred and plan §20: not reproducible from sealed inputs across versions of a model,
and not explainable as a sum of named contributions. Kept deferred; Phase 13 does not revisit it.

### First-match evaluation (the first triggered rule decides)
Pros: cheaper, simple to reason about.
Cons: a reorder of rules silently changes decisions, and a hit the operator needs to see (a screening hit behind an
amount rule) is never recorded. Refused: every rule is evaluated, every hit recorded, precedence resolves conflicts.

### Edit a version in place while `PROPOSED`
Cons: the second person approves text that may have changed since the first proposed it. Refused: a changed proposal
is a new version; `PROPOSED` is immutable like every other state.

### Seed v1 `ACTIVE` by migration
Cons: a migration is not a person's four-eyes act, and production would judge money on a fixture nobody activated.
Refused: production activates none (C8); the activation gate is the runbook's.

### One global policy across operations
Cons: the operations admit different decisions (`HOLD` only on withdrawals) and carry different fail-safe bands;
one policy would need per-operation branches inside its rules. Refused: a version is per operation.

## Consequences

Positive:
- Every decision names the version and engine it was made under and can be replayed to the byte; the policy an
  officer reads is the policy the engine ran.
- The owner changes behaviour by activation under four eyes, with an audit row and an event, never by release.
- Rule kinds are reviewed code with declared inputs and costs, so the latency budget is a property of a closed set.

Negative:
- A genuinely new rule shape needs a code change and an engine version; the vocabulary will grow by ADR amendment.
- Every parameter needs a schema and a door test; the battery must be re-run for every fixture change.
- The advisory lock on an empty pin adds a wait inside a money transaction for the duration of an activation's
  commit — bounded, measured by the storm, and only during an activation.

Operational impact: `finapp.risk.policy.active{operation,policy_version}` (gauge), `rule.hit{operation,rule}` with
`rule` added to the tag allow-list (C9); the alert "no active policy where judging is expected"; the activation
gate and the policy report.
Security impact: `RISK_POLICY_ADMINISTER` with four eyes and step-up; the proposer never activates; the policy's
content is `CONFIDENTIAL` and read only under the two permissions, audited.
Financial impact: none directly; indirectly, every threshold that refuses, holds or challenges money is a field of a
version a person activated.

## Invariants / Constraints

`INV-RSK-04` (every decision records the policy version, the engine version, every hit and the ordered reasons),
`INV-RSK-05` (a decided version is immutable; only `ACTIVE` is evaluated; activation is four eyes; the pin never
answers "neither"), `INV-RSK-01` (replay `IDENTICAL`), `INV-RSK-07`, `INV-HIST-04` (versioned artefacts carry
effective dates), `INV-AUD-04` (four eyes are two persons), ADR-0107, ADR-0108.

## Follow-up

- `P13-TSK-002` — policy versions as data: `risk V002` (`risk_policy_version`, `risk_rule`), the kinds' parameter
  schemas, the door, four-eyes activation, the pin with advisory namespace 13, the v1 fixtures. *(Built 2026-10-23, as
  decided, with the build's readings recorded for the review: several `PROPOSED` per operation numbered without a gap,
  activation monotonic in version number (a proposal below the version in force `409 risk.PolicyStale`); the content
  split along the money line into `risk_policy`, `risk_rule`, `risk_rule_parameter` (one typed row per declared
  parameter) and `risk_policy_band` beside the money-free machine row; decision 8's engine-drift refusal at activation
  and the `CHECK` on the engine name at insert; the gauge's `policy_version` as the value, not a tag; `CREDIT_SIGNAL`
  carries no version — `RISK_LIFECYCLES.md` §4.1's as-built note, `PHASE_13_PLAN.md` §22.)*
- `P13-TSK-003` — `RISK_ENGINE_V1`, pure and deterministic, with its two-JVM determinism test; `P13-TSK-004` — the
  sealed assessment and replay; `P13-TST-002` — the battery and its fingerprint. *(The engine built 2026-10-23, as
  decision 3 states it, with three readings settled by the build and recorded in `PHASE_13_PLAN.md` §12.2–12.4 for
  the review: **the composition** — the decision is the strictest of the score band's decision and every fired hard
  decision, so "a hard rule's decision beats a score band's" means a band never *softens* a hard rule while it may
  raise one — the only reading under which the C8 fixtures' hard `CHALLENGE` at EUR 500 and `DENY` band from 800 both
  work; **nothing fired** gives score 0 and the band covering 0 decides (the fixtures' is `ALLOW`; the engine compiles
  no `ALLOW`); **velocity readings** are the current figures before the judged movement, compared at-or-above the
  threshold, never added to. The sealed inputs are `EvaluationInputs`, every reading `PRESENT` or `ABSENT` — an absent
  reading never fires and its hit says so; a rule that throws, inputs of another operation, a policy for another
  engine and an amount in another currency are `EvaluationFailed`, never a partial verdict; the canonical form is
  `CanonicalEvaluation` format 1, SHA-256 over its UTF-8 bytes, the inputs parsed back strictly; the two-JVM proof is
  `RiskEngineDigestTest` over a seeded corpus through risk's own `SecondJvm`. One gap for `-004`/`-006` to reconcile:
  this ADR and `-002` type a velocity rule's `dimension` as `SubjectKind`, `-006`'s entry names a
  `VelocityDimension` — the engine evaluates what the policy stores.)*
  *(Amendment, `P13-TSK-006`, 2026-10-23 — the gap is closed by the coordinator's decision: a velocity rule's
  `dimension` is a `VelocityDimension` (`PARTY`, `SOURCE_ACCOUNT`, `DESTINATION`, `DEVICE`, `MERCHANT`), the parameter
  type `VELOCITY_DIMENSION`; `SUBJECT_KIND` left the parameter types (no kind used it after); `risk V005` carries the
  storage change (column `value_velocity_dimension`, the two `CHECK`s re-made behind a guard refusing a stored
  `SUBJECT_KIND`), `V002` untouched; the engine's `VelocityKey` re-keyed, `RISK_ENGINE_V1` and canonical format 1
  unchanged — nothing had been sealed.)*
- `RuleKind` additions after this ADR are recorded as dated amendments here with their engine version.
- `DISTRIBUTED_EXECUTION.md` registers advisory namespace 13 and the pin's rule; `RISK_LIFECYCLES.md` §Policy
  version is the machine's record.
- *(As built, `P13-TSK-023`, 2026-10-26 - decision 4 over a day.)* `DailyReplay` replays every assessment of a UTC day on one snapshot, each under the version it pinned by id and the engine it names - never the version active now, so an activation mid-day changes nothing of the morning - the versions memoised for the run (they are immutable from proposal); `IDENTICAL` / `DIVERGED` per operation, the first divergent named by id and the kinds that diverged, never a figure and never one of the reader's own party. *(The gate review's I3: a bounded page at a time - at most `limit` assessments after the cursor `after`, the answer's `next` the page that follows, each page's snapshot under `statement_timeout`; the pin is per assessment, so pages replay exactly as one run would.)*
- **Acceptance.** `Proposed` at the Phase 12 → 13 transition (2026-10-22); the Phase 13 review (`P13-DOC-001`) reads
  this ADR against the code, corrects it in place with dated notes, and accepts it.
