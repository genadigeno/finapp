# Risk Lifecycles

*Planned — written at the Phase 12 → 13 transition (2026-10-22), when nothing here is built; the building task is the
record.* At the transition no `risk` module, schema, table, trigger, door or sweep exists: every machine, edge, lock,
refusal, event and audit act below is the decided design of [`PHASE_13_PLAN.md`](../project/PHASE_13_PLAN.md) (§3
ownership, §4 aggregates and commands, §5 lifecycles, §7 the multi-instance architecture, §8 data, §9 doors, §10 events,
§11 security, §12 the model, §24 the settled questions), and the task named beside each machine corrects this document
where the code teaches otherwise. Where this document and the plan disagree, the plan governs until the building task
corrects one of them; §8 below lists every point where this document had to read the plan one way, for the task to
confirm.

Written on the `BNPL_LIFECYCLES.md` precedent (itself on `LENDING_LIFECYCLES.md`'s and
`CREDIT_DECISIONING_LIFECYCLES.md`'s): the document that names a phase's machines is written before the phase's first
task. The invariants are `INV-RSK-01`…`-10` in [`FINANCIAL_INVARIANTS.md`](FINANCIAL_INVARIANTS.md), catalogued by the
same transition; the terms are in [`GLOSSARY.md`](GLOSSARY.md) §8a; the module is
[`MODULE_ARCHITECTURE.md`](../architecture/MODULE_ARCHITECTURE.md) §4 `risk`; the contexts are
[`BOUNDED_CONTEXTS.md`](../architecture/BOUNDED_CONTEXTS.md) 20–23.

Related: ADR-0107 (the `risk` bounded context and module: one module, three capabilities, no money, every domain through
ports) · ADR-0108 (synchronous evaluation in the caller's transaction: the decision model, the seam mapping, the
fail-safe as a recorded decision, the latency budget, the override) · ADR-0109 (policy as versioned data: rule kinds in
code, parameters as data, the deterministic engine and replay) · ADR-0110 (the case model: born-once alerts, one open
case per subject and kind, concurrent updates by revision, four-eyes dispositions) · ADR-0111 (AML monitoring from the
ledger journal; the regulatory report as an artefact distinct from the investigation, behind a filing port) · ADR-0112
(downstream actions as keyed commands through the owning domains; risk never mutates authoritative state) — all
`Proposed` at the transition, indexed in [`docs/adr/README.md`](../adr/README.md).

**Phase 13 moves no money.** No machine below posts, holds, releases a hold or writes another module's row. Where a
risk decision changes a money movement, the change is the owning domain's own committed outcome in its own machine
(§4.12); where a case acts, the act is a keyed command through the owning domain's existing door (§4.7).

---

## 1. The concepts, kept apart

| Kept apart | Why |
|---|---|
| **Risk Assessment** vs **Risk Decision** vs **Risk Score** | The assessment is the sealed record of one evaluation (`INSERT`-only, `UNIQUE (operation, command_ref)`); the decision is one of its fields — `ALLOW \| CHALLENGE \| DENY \| HOLD \| REVIEW` with ordered reason codes; the score another — an integer 0–1000, Σ rule contributions clamped. None is ever updated: an override is a separate aggregate (§4.2a), and a score is never a decision (`INV-CRD-04`, the credit rule applied here) |
| **Risk Policy Version** vs **Rule** vs the **engine version** | The policy version is the four-eyes machine row and its immutable content; a rule is one row inside it — a closed `RuleKind` with typed parameters and a weight or a hard decision, data never code; the engine version (`RISK_ENGINE_V1`) is code, pinned by every assessment so the replay is `IDENTICAL` (`INV-RSK-01`, `-04`) |
| **Fail-safe decision** vs **silent default** | When the evaluation is unavailable (no `ACTIVE` policy; the pin's read failing; a rule throwing; the statement timeout) the answer is the policy's fail-safe band for the judged amount — or, with no policy at all, the compiled `ALLOW` — **recorded** as an assessment with reason `RISK_UNAVAILABLE` or `NO_POLICY_ACTIVE`, counted, and an operational alert; never a value nobody wrote down (`INV-RSK-04`) |
| **Velocity Window** vs a cache counter | A durable row per (dimension, subject, window kind, window start), upserted in the judged command's own transaction after the caller's locks, proven against the monitored transactions (`INV-RSK-03`, `INV-CON-03`). There is no Redis counter and nothing to lose |
| **Risk Signal** vs **Monitored Transaction** vs **Alert** | A signal is an append-only fact about a subject born once per source event (`UNIQUE (source_event_id)`); a monitored transaction is the channel-neutral record of one money movement born once per ledger journal entry (`UNIQUE (journal_entry_id)`); an alert is a rule's born-once observation over them (`UNIQUE (subject_ref, rule_code, window_start)`). Signals and monitored transactions are inputs; an alert is an output; none is a judgement a person made |
| **Alert** vs **Case** vs **Regulatory Report** | The alert is the observation, born once and attached or suppressed; the case is the investigation — one open per (subject, kind), worked by append-only investigator actions, disposed under four eyes; the regulatory report is the filing to an authority, a different aggregate with its own lifecycle, permissions and retention, drafted from a disposed case and never the case itself (ADR-0111; never "the SAR" in code — the jurisdiction names it) |
| **Disposition** vs **Escalation** vs **Investigator Action** | A disposition ends the case (`CLOSED_FALSE_POSITIVE \| CLOSED_NO_ACTION \| CONFIRMED_FRAUD \| CONFIRMED_SUSPICIOUS \| REFERRED`); an escalation raises its level and queue and the case goes on; both are recorded as investigator actions, the append-only, reasoned, audited facts of the investigation |
| **Risk Action** (the record) vs the **effect** (the owning domain's) | `risk` records `PROPOSED → APPROVED → DISPATCHED → DONE \| FAILED`; the effect — a suspension, a freeze, a release — is the owning domain's existing command, keyed `risk.action:<id>`, executed in its own transaction on its own lock order, recorded on both sides (`INV-RSK-02`, ADR-0112). Risk never writes the domain's row and never reverses a transaction |
| **Decision Override** vs the **decision** | The override is a four-eyes proposal over a `DENY`, `HOLD` or `REVIEW` decision that records the action it issued; the decision is never edited; an `ALLOW` is never overridden after the fact — the money has moved, a case is the remedy (OQ-5) |
| **Customer Risk Profile** vs **Credit Profile** vs **Risk Rating** (kyc's) | The customer risk profile is `risk`'s versioned rating per party (`LOW \| MEDIUM \| HIGH \| PROHIBITED`), recomputed by the sweep, read by credit's seam and by every evaluation; credit's profile is the deciding transaction's anchor and holds no rating; kyc's Risk Rating is onboarding's own. A rating change never rewrites a decision already made (`INV-RSK-10`) |
| **Screening Result** (kyc's) vs **Screening List Version** vs the rescreening permit | kyc owns the screening and its evidence; `risk` records that a list version is in force and, on its activation, one rescreening permit per `ACTIVE` party, requested through kyc's ports — the outcome a signal, a hit an alert (`ROADMAP.md` Refinement 3; OQ-8) |
| **Monitoring watermark** vs an event offset | The watermark is a row per source, claimed `FOR UPDATE SKIP LOCKED`, advanced only after the monitored transactions it covers are committed; events are hints that pull its permit forward and never the record (`INV-RSK-09`) |
| **`HELD`** (payments' state) vs a risk state | A held withdrawal is payments' row in payments' machine, its ledger hold kept; `risk` holds nothing — the `HOLD` decision is what the withdrawal's own transaction read and committed (§4.12) |

## 2. When each act takes effect

Nothing here has a ledger effect; the column names where the money moves, if anywhere.

| Act | Record (`risk`) | Money effect |
|---|---|---|
| Policy version proposed · activated · rejected · retired | `risk_policy_version` and its rules; its history | **None** |
| A judged command evaluated (T-eval) | the assessment with its inputs, rule hits and reasons; the velocity windows' increments (only when the decision permits the movement) | **None of risk's.** The caller commits its own outcome in the same transaction: a transfer `FAILED(RISK_REFUSED)`, a withdrawal `HELD` or `FAILED(RISK_REFUSED)` with its hold kept or released, a payment intent's refusal — §4.12 |
| Advice at a door (T-advise) | nothing | none; a `403 identity.AssuranceRequired` before any row exists |
| Signal recorded (T-sig) · monitored transaction born (T-mon) | born-once rows | none |
| Alert raised · attached · suppressed | the alert row; its folding into a case | none |
| Case opened · taken · noted · escalated · disposed | the case row and its append-only actions | none |
| Override proposed · approved · rejected | the override row and the action it issues | none of risk's; the issued action's effect is the owning domain's (next row) |
| Risk action dispatched · concluded | the action row with its outcome | **the owning domain's**, in its own transaction through its door: a released withdrawal dispatches (its hold then released by payments' completion as today), a denied one fails and releases its hold; a suspension, a block, a revocation move no money |
| Regulatory report drafted · approved · filed · acknowledged | the report and its history | none |
| Profile recomputed | a new profile version | none |
| List version recorded · activated | the list row; rescreening permits | none |

---

## 3. Rules every machine shares

**Actors.** **P** — a person holding a Phase 13 permission (identity `V023`; `-001`): `RISK_INVESTIGATE`,
`RISK_CASE_APPROVE`, `RISK_POLICY_ADMINISTER`, `RISK_OVERRIDE`, `AML_REPORT_APPROVE`, `AML_REPORT_FILE`, in the roles
`RISK_ANALYST` {INVESTIGATE, CASE_APPROVE}, `RISK_OFFICER` {POLICY_ADMINISTER, OVERRIDE} and `AML_OFFICER`
{AML_REPORT_APPROVE, AML_REPORT_FILE} — a partition (`P13-TSK-001`: one live role per identity, every pair of grants
disjoint; the override door serves the assessment it overrides under `RISK_OVERRIDE`, the report draft door the
disposed case's evidence under `AML_REPORT_APPROVE`); pairwise-disjoint grants with every existing role
(`INV-AUD-03`); keyed per actor (`risk.policy:<actor>`, `risk.override:<actor>`, `risk.case:<actor>`,
`risk.report:<actor>`, `risk.list:<actor>`); reasoned where the plan says so (`422 risk.ReasonRequired`; reasons and
notes bounded, screened by `InstrumentShapes`, classified `CONFIDENTIAL`); **four eyes** where marked ★ — two persons,
the proposer never the approver, by the domain and by a `CHECK`; a refused self-approval `403 risk.SelfApprovalRefused`,
**audited `FAILED`** (`INV-RSK-07`, `INV-AUD-04`); and **self-dealing fails closed**: no holder reads or works a case,
approves an override or files a report on their own party's subject, and an operator with no placeable party is
refused — `risk.SelfDealingRefused`, audited `FAILED` (the Phase 12 → 13 gate's A7-6 applied forward). Policy
activation, override approval, report approval and filing, and every `CONFIRMED_*` disposition require a
`MULTI_FACTOR` operator session. **S** — the system: T-eval inside a caller's transaction, a sweep's step
(`RiskMonitoringSchedule`, `RiskActionSchedule`: leaderless, `SKIP LOCKED`, off in test contexts, each with its gauge
and its claim failures metered from the first task — the transition's N5), or an inbox handler (`risk.<component>`).
**C** — the customer and **M** — the merchant: **never an actor of any machine here**, and never a reader of any `risk`
table (`INV-RSK-08`, `NoCustomerSurfaceReadsRiskTest`); what either sees is the owning operation's generic outcome
(owner decision C6).

**The three ranks** (`INV-LIFE-01`, `-02`; the ADR-0044 doctrine as Phases 10–12 built it): **the domain** — each
act's conditional `UPDATE … WHERE status IN (the act's from-states)` under the row lock, so a loser finds the row moved
and writes nothing; **the database** — a hand-written every-writer trigger `<table>_permits_only_machine_edges`
admitting exactly the edges below, with the frozen columns and the cross-table preconditions named per machine, and a
status `CHECK` that is a membership list of the machine's states; **an append-only history** per machine
(`<table>_event`). Schema `risk`, `REVOKE ALL FROM PUBLIC`; the application role is granted `SELECT, INSERT` on fact
tables and a conditional `UPDATE` only of status, permit, revision and decision columns on the machine tables; **no
`DELETE` for any role**; no money column that is a balance — `amount_minor` + `currency` appear only as the judged
amount on `INSERT`-only rows and as the velocity windows' sums, bounded by a `CHECK` that a sum never decreases within a
window (`PHASE_13_PLAN.md` §8). `risk`'s migrations name no other schema; a precondition that lives in payments,
identity, kyc or the ledger is the domain's, through `app`'s port.

**The lock order** (`PHASE_13_PLAN.md` §7.2; to be registered in `DISTRIBUTED_EXECUTION.md` §3 as "The Phase 13 lock
order" — *planned*). Risk rows are taken **after** every money row and **only** in two shapes: last inside a caller's
transaction, or first in a risk-only transaction that takes no money row.

| Position | Row | Mode | Taken by |
|---|---|---|---|
| **A0** | advisory namespace **13**, `hashtext(operation)` | exclusive (T-pol); **shared** by the pin's re-read | T-pol; T-eval only when its first `FOR SHARE` read is empty |
| **(R1)** | the operation's `ACTIVE` `risk_policy_version` | `FOR SHARE` — the pin | T-eval, after the caller's locks and reads; when the first read is empty the pin takes A0 shared and reads once more (the gate's A3-I1 rule), so the pin never answers "neither" (`INV-RSK-05`) |
| **(R2)** | the judged command's `velocity_window` rows | one statement each, `INSERT … ON CONFLICT DO UPDATE … RETURNING`, in **sorted key order** (dimension, subject, window kind, window start) | T-eval, only when the decision permits the movement |
| **(R3)** | inserts — the assessment, a signal | — | T-eval, T-sig |
| **(R4)** | the `risk_case` row (or the override / report row of an own transaction) | `FOR UPDATE` | T-case, T-ovr, T-rep; T-eval when `REVIEW`/`HOLD` opens a case (OQ-2: the case row is risk's and takes no money lock) |
| **(R5)** | alert rows | — | T-case (attach), T-mon (raise), T-eval |
| **(R6)** | the `risk_action` row | `FOR UPDATE`, **then the owning domain's command on that domain's own order, no other risk row held** | T-act |
| **(R7)** | the monitoring watermark row | `FOR UPDATE SKIP LOCKED` — the claim; commits alone | T-mon |
| **(R8)** | the `customer_risk_profile` head row | `FOR UPDATE` | T-mon's per-subject leg; T-case's disposition |

**No cycle, by construction:** a money transaction holds its money rows and then takes (R1)–(R3), which no risk
transaction holds while waiting on a money row — T-case, T-mon and T-rep take no money row at all, and T-act takes (R6)
and then enters the owning domain's command, which takes money rows in that domain's order with no risk row held but
(R6), which no money transaction ever takes. (R1) is `FOR SHARE` and conflicts only with T-pol's activation, which
holds no money row. The only risk rows a money transaction writes are its own velocity windows, in sorted order, so two
money transactions upserting overlapping windows cannot deadlock. A sweep's claim commits before its step; `risk` holds
no connection across a provider call (the filing call — ADR-0046).

**The latency budget.** T-eval runs in-lock, so its cost is paid by every money movement: **p99 ≤ 50 ms** for its
reads and upserts (the storm measures it); `risk`'s reads run under a per-statement `SET LOCAL statement_timeout` of
250 ms inside the caller's transaction, behind a savepoint and restored after; a timeout is "unavailable" and becomes
the band's fail-safe decision, recorded `RISK_UNAVAILABLE` — the caller's transaction is not aborted (§7.6 of the plan).

**The database clock, at every boundary.** Every window start, deadline, effective date and stamp is
`clock_timestamp()` read **after** the relevant row lock, never an instance clock, so ±5 s of instance skew cannot move
a window (`VelocityWindowDatabaseTest#aWindowIsCutByTheDatabaseClock`); a permit (`next_sweep_at`,
`next_recompute_at`, `next_attempt_at`) binds nothing — the step re-judges under its lock.

| Boundary | Judged as |
|---|---|
| A velocity window's start | computed from `clock_timestamp()` read after the caller's lock: `H1`, `D1`, `D7`, `D30` cut on the database clock |
| A policy version's effective-from | stamped on the database clock at activation; the retirement stamps its successor's start in the same transaction (credit `V014`'s `GREATEST(transaction_timestamp(), effective_from + 1 µs)` rule — for `-002` to confirm, §8) |
| A case's SLA deadline | stamped at opening from the pinned monitoring policy's SLA per kind; the gauge `finapp.risk.case.age{kind}` and the alert "a case older than its SLA" read it; nothing transitions on it |
| A rule's lookback | the pinned rule's `lookback days`, judged against the database clock read after (R1) |
| A held withdrawal's `held_until` | stamped at the hold: held at + the pinned policy version's **hold validity** (default 72 h); judged on the database clock under the withdrawal row lock by a leg of payments' existing `WithdrawalResolutionSchedule` — §4.12 |

---

## 4. The state machines

### 4.1 Risk policy version (`risk.risk_policy_version`, `risk_rule`) — `P13-TSK-002` (`risk V002`)

```
 (proposal) ──> PROPOSED ──★ a second person activates──> ACTIVE ──a successor activated──> RETIRED
                   └──rejected (the proposer may withdraw)──> REJECTED
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `PROPOSED` | P, `POST /v1/operator/risk/policies`, `RISK_POLICY_ADMINISTER`, key `risk.policy:<actor>` (T-pol) | A0 (namespace `13`, `hashtext(operation)`); the content judged whole at the door — every rule's parameters against its `RuleKind` schema, every band mapping to a decision the operation admits (plan §12.1), the fail-safe bands (C1), the hold validity (`WITHDRAWAL`; default 72 h), the rating bands (`MONITORING`), the challenge assurance `MULTI_FACTOR`, the override threshold (C7), the engine version `RISK_ENGINE_V1`; one `PROPOSED` per operation (partial unique, the backstop — read one way, §8) | `422 risk.PolicyInvalid`, `422 risk.ReasonRequired` | — | `risk.PolicyProposed` |
| `PROPOSED → ACTIVE` ★ | P ≠ proposer, `…/{id}/activation`, `MULTI_FACTOR` | A0 exclusive; the proposal row conditional `PROPOSED`; the current `ACTIVE` of the operation `FOR UPDATE` → `RETIRED` **in the same transaction**, so the pin never reads "neither" (`INV-RSK-05`; `RiskPolicyDatabaseTest#anEvaluationBehindAnActivationPinsTheSuccessor`); the four-eyes `CHECK` | `403 risk.SelfApprovalRefused` (audited `FAILED`), `409 risk.PolicyStale` (the loser of a concurrent decision — read one way, §8) | `RiskPolicyActivated` (operation, version) | `risk.PolicyActivated` |
| `ACTIVE → RETIRED` | S, the successor's activation, same transaction | A0; the active row | — | — | `risk.PolicyRetired` |
| `PROPOSED → REJECTED` | P, `…/{id}/rejection` (the proposer may withdraw) | A0; the proposal row | `422 risk.ReasonRequired` | — | `risk.PolicyRejected` |

One machine row per version, money-free, and its rules as `INSERT`-only rows (`risk_rule`), each a closed `RuleKind`
(`AMOUNT_BAND`, `VELOCITY_COUNT`, `VELOCITY_SUM`, `NEW_PAYEE`, `NEW_DEVICE`, `SESSION_ASSURANCE_BELOW`,
`RECENT_AUTH_FAILURES`, `RECENT_CREDENTIAL_CHANGE`, `RECENT_RECOVERY`, `ACCOUNT_AGE_BELOW`, `KYC_NOT_APPROVED`,
`SCREENING_HIT`, `PROFILE_RATING`, `MERCHANT_SUSPENDED`, `CORRIDOR_HIGH_RISK`, `DORMANT_THEN_ACTIVE`, `RAPID_IN_OUT`,
`STRUCTURING`, `ROUND_AMOUNT`, `REFUND_RATIO`, `CHARGEBACK_RATIO`, `BNPL_MULTI_APPLICATION`) with typed parameters, a
weight and/or a hard decision, in a declared order. Content is immutable once proposed; an assessment pins the version
for life (`INV-HIST-04`). A policy version is **per operation** (`TRANSFER`, `WITHDRAWAL`, `CARD_PAYMENT`,
`WALLET_PAYMENT`, `CROSS_BORDER`, `CREDIT_SIGNAL`, and `MONITORING` — the sweep's policy, with no seam, whose score bands are
rating bands over the party's signal score); at most one `ACTIVE` per operation
(partial unique). **Never migration-activated**: production activates none; every seam answers `ALLOW(NO_POLICY_ACTIVE)`
recorded until a version is activated under four eyes and `OPERATIONS_RUNBOOK.md` §9's activation gate is passed (plan
§1.1); the C8 fixtures are test-only. The reads: `GET /v1/operator/risk/policies/{id}` and `GET …?operation=`
(`RISK_POLICY_ADMINISTER`, `RISK_INVESTIGATE`; the in-force read with none active answers `risk.PolicyNotActive` — its
status is `-002`'s to assign). **Invalid:** anything out of `RETIRED` or `REJECTED`; `PROPOSED → RETIRED`;
`ACTIVE → PROPOSED | REJECTED`; an `ACTIVE` retired without its successor activated in the same transaction; activation
by the proposer (`CHECK`); a content edit of any version (no `UPDATE` grant on the content columns).

*(As built by `P13-TSK-002`, 2026-10-23: as drawn, with three readings settled by the build. **Several `PROPOSED` per
operation** — the backlog entry's "ten proposals leave ten versions numbered without a gap" is the rule; there is no
one-`PROPOSED` partial unique, and activation is monotonic in version number: a proposal numbered below the version in
force answers `409 risk.PolicyStale` (superseded) and is rejected or left, so the retire-beside-successor trigger's
"later successor" rank always holds. **The codes' statuses**: `risk.PolicyStale` `409` on a decided version re-decided
and on a superseded proposal; `risk.PolicyNotActive` `409` on the in-force read with none active (the register's
planned status kept). **The effective-from arithmetic**: credit `V014`'s rule confirmed for this table — the
retirement's `effective_to` is `GREATEST(clock_timestamp(), transaction_timestamp(), effective_from + 1 µs)` and the
successor's `effective_from` `GREATEST(transaction_timestamp(), max(effective_to))`, so the periods meet. The content
is six tables, not two: the machine row, `risk_policy` (the knobs), `risk_rule`, `risk_rule_parameter` (one typed row
per declared parameter), `risk_policy_band` and the history; `CREDIT_SIGNAL` carries no policy — refused at the door.)*

### 4.2 Risk assessment (`risk.risk_assessment`, `risk_assessment_input`, `risk_rule_hit`, `risk_reason`) — `P13-TSK-004` (`risk V007`); evaluated by `-003`'s engine; the seams wired by `-008`…`-011`

```
 (T-eval, in the caller's transaction) ──> DECIDED      (born sealed; no edge out)
```

Not a machine: one state, born once, never changed. The table of the act:

| Act | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `DECIDED` | S, T-eval — inside the caller's transaction after the caller's locks and reads, through `app`'s seam adapter (`RiskTransferLimitCheck` / `RiskTransferRiskDecision`, `RiskWithdrawalRiskDecision`, `RiskPaymentRiskDecision`, `RiskCrossBorderLimitCheck` / `RiskCrossBorderRiskDecision`) over `risk`'s one channel-neutral evaluation API | (R1) the pin; the inputs read (signals, the profile, the velocity figures, the session's assurance, kyc's clearance through a port); (R2) the windows upserted only when the decision permits; (R3) the insert — `UNIQUE (operation, command_ref)` under the caller's lock, so ten retries make one assessment (`RiskEvaluationDatabaseTest#tenRetriesOneAssessment`); the statement timeout behind a savepoint | none to the caller — "unavailable" is a decision (§3's budget): the band's fail-safe recorded `RISK_UNAVAILABLE` / `NO_POLICY_ACTIVE`, its decision `ALLOW` or `DENY` per C1, the operational alert raised (§8) | `RiskDecisionMade` (assessment id, operation, command ref, decision, reason codes); `LimitBreached` (assessment id, rule code) when a velocity or amount rule fired `DENY`/`HOLD`; `AlertRaised` when a rule raises one | — (the caller's act is the audited one; the assessment's reads are: `risk.AssessmentRead`, `risk.Replayed`) |
| advise (no row) | S, T-advise at a door (the transfer door's step-up check before the command, `-008`) | the same evaluation with no upsert and no insert; never authoritative | `403 identity.AssuranceRequired` for an unsatisfied `CHALLENGE` | — | — |
| read · replay | P, `GET /v1/operator/risk/assessments/{id}`, `POST …/{id}/replay`, `RISK_INVESTIGATE` | a read-only snapshot; the replay re-runs the pinned engine version over the sealed inputs → `IDENTICAL` / `DIVERGED` (`INV-RSK-01`; credit's `DecisionReplayer` shape) | `404 risk.NotFound`; self-dealing refused (audited `FAILED`) | — | `risk.AssessmentRead`, `risk.Replayed` |

The record: operation, subject refs, the judged amount (`amount_minor` + `currency`, `INSERT`-only), the inputs read
(`risk_assessment_input` — the Transaction Risk Profile: amount band, counterparty novelty, device novelty, velocity
readings — stored only here), the policy and engine versions, every rule that fired and its contribution
(`risk_rule_hit`), the score, the decision, the ordered reason codes (`risk_reason`), whether the fail-safe applied,
the latency (`INV-RSK-04`). **The decision** is one of `ALLOW`, `CHALLENGE`, `DENY`, `HOLD`, `REVIEW` as the operation
admits it (plan §12.1: `HOLD` for `WITHDRAWAL`, and for `CARD_PAYMENT` in `-010`'s slice 2; `CREDIT_SIGNAL` answers the
rating code); precedence when rules disagree `DENY` > `HOLD` > `REVIEW` > `CHALLENGE` > `ALLOW`, a hard rule's decision
beating a score band's. **The seam mapping** (ADR-0108): `ALLOW` → `PERMIT`; `CHALLENGE` → `PERMIT` when the session
already satisfies `MULTI_FACTOR`, else `REFUSE` committed as the reserved `RISK_REFUSED` with reason
`CHALLENGE_REQUIRED` (the race's answer — the door's T-advise turns the normal case into `403
identity.AssuranceRequired` before any row exists); `DENY` → `REFUSE`, a case when the policy says so; `HOLD` → the
withdrawal commits `HELD` with its hold kept and a case opens; `REVIEW` → `REFUSE` and a case of the operation's kind
opens in T-eval's transaction with the assessment as its first evidence. **Invalid:** any edge out of `DECIDED`; any
`UPDATE` of any column (no grant); a second assessment for one (operation, command ref); a decision an operation does
not admit (refused at the policy door, `422 risk.PolicyInvalid`); an assessment written outside the judged command's
transaction.

*(As built by `P13-TSK-004`, 2026-10-23 — `risk V007`, `RiskEvaluation`, `RiskAssessments`, `AssessmentReplay`. **The
order:** a lookup of (operation, command ref) first — a retry, and the transfer's second seam in one transaction, are
answered with the sealed decision and nothing below runs; then the pin (R1) behind a savepoint **with no statement
timeout** (an evaluation meeting an activation waits for it and pins the successor — A3-I1; a timeout there would turn
every activation into unpinned fail-safes); then, behind a second savepoint under `statement_timeout = 250ms`, the cut
(R2a), the reads (the windows the policy reads, the profile, the signals, `RiskInputs`), the engine and the count (R2)
when the decision permits; then the seal (R3), `risk.RiskDecisionMade` and `risk.LimitBreached`, and `CaseOpener` for
`REVIEW`/`HOLD` (`NONE` until `-016`). **The record:** the head (`risk_assessment` — the subjects exactly as the windows
count them, the judged amount, the pin, the engine, the score, the decision, `fail_safe_reason` with
`unavailable_cause` — `NO_POLICY`, `PIN_UNREADABLE`, `READ_FAILED`, `TIMEOUT`, `ENGINE_REFUSED` — the challenge's
answer, `velocity_outcome` with the cut instant, the digests of the canonical inputs and verdict, the latency),
`risk_assessment_input` (the canonical inputs text, its digest a `CHECK`), `risk_rule_hit`, `risk_reason`; every table
sealed for every writer by trigger and granted `SELECT, INSERT` alone. **Three readings the build settled:** the pin's
own read failing has no band to read and answers the compiled `ALLOW` under `RISK_UNAVAILABLE` with no version named
(the policy-version `CHECK` admits a null pin under `NO_POLICY` and `PIN_UNREADABLE` alike); an amount in another
currency than the policy's is answered by the open-ended band (`DENY` in every C1 band set) — multi-currency judging is
an owner item; a permitted fail-safe is counted behind a savepoint of its own and recorded `UNCOUNTED` when that count
fails. **A `DENY` opens no case:** the policy version carries no "case on deny" field, so T-eval calls the opener for
`REVIEW` and `HOLD` alone — the field is `-016`'s. The replay re-derives the verdict — the engine over the parsed sealed
inputs, or the fail-safe the recorded cause names — and answers `IDENTICAL` or `DIVERGED` with the kinds
(`INPUTS`, `POLICY`, `EVALUATION`, `SEAL`, `DECISION`, `SCORE`, `HITS`, `REASONS`, `FAIL_SAFE`, `CHALLENGE`) and the
first diverged rule.)*

#### 4.2a Decision override (`risk.decision_override`) — `P13-TSK-012` (`risk V007`'s table)

```
 (proposal over a DENY | HOLD | REVIEW decision) ──> PROPOSED ──★ a second RISK_OVERRIDE holder approves (at or above the threshold)──> APPROVED
                                                        │──one holder below the threshold (reasoned, audited)──────────────────────> APPROVED
                                                        └──rejected (the proposer may withdraw)─────────────────────────────────────> REJECTED
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `PROPOSED` | P, `RISK_OVERRIDE`, `POST /v1/operator/risk/assessments/{id}/overrides` (reason code and text), key `risk.override:<actor>` (T-ovr) | the assessment read plainly (immutable); **(R4)** the override row; `UNIQUE (assessment_id)` over a live proposal; the decision one of `DENY`, `HOLD`, `REVIEW` — an `ALLOW` is never overridden (OQ-5) | `409 risk.ActionNotProposable` (an `ALLOW` or a `CHALLENGE`, or a live proposal exists — read one way, §8), `422 risk.ReasonRequired`, `risk.SelfDealingRefused` (audited `FAILED`) | — | `risk.OverrideProposed` |
| `PROPOSED → APPROVED` ★ at or above the threshold (C7: the judged amount at or above EUR 1,000, or the decision `DENY`) | P ≠ proposer, `…/{id}/approval`, `MULTI_FACTOR` | (R4) conditional `PROPOSED`; the four-eyes `CHECK`; **the action it issues recorded in the same transaction**: for a held withdrawal the release — run in T-act's shape from this approval door, keyed `risk.action:<override id>`, through payments' existing door on payments' lock order (`-012`), and folded into §4.7's machine as a `RELEASE_HELD_WITHDRAWAL` action on the hold's case once `-017` lands; for a denied transfer **nothing to dispatch** — a one-time allowance for the same command ref, read as an input by the customer's retry under a new key | `403 risk.SelfApprovalRefused` (audited `FAILED`) | `RiskActionRequested` where an action is issued | `risk.OverrideApproved` |
| `PROPOSED → APPROVED` below the threshold | P, the proposer, the same door's one-holder path (reasoned, audited) | (R4) conditional `PROPOSED` | as above | as above | `risk.OverrideApproved` |
| `PROPOSED → REJECTED` | P, `…/{id}/rejection` | (R4) conditional `PROPOSED` | `422 risk.ReasonRequired` | — | `risk.OverrideRejected` |

The override **never edits the decision**: the assessment stands `DECIDED` with its decision, and the override row
names it, its reason, its approver and the action it issued. `WithdrawalHoldDatabaseTest#releaseAndExpiryLeaveExactlyOne`
is this machine's race with payments' `held_until` expiry (§4.12): the first committed edge wins, the loser a recorded
`DONE(ALREADY)` outcome. **Invalid:** anything out of `APPROVED` or `REJECTED`; a second
live proposal per assessment; an override of an `ALLOW` or of a `CHALLENGE` (the customer satisfies it or does not);
approval by the proposer at or above the threshold (`CHECK`); an approval that writes the withdrawal's row (the release
is payments' act through its door).

*(The table as `P13-TSK-004` landed it in `risk V007`, 2026-10-23: `decision_override` with `requires_second_person`
fixed at the proposal (C7) and the four-eyes `CHECK (status <> 'APPROVED' OR NOT requires_second_person OR decided_by <>
proposed_by)`, `decision_override_one_live` (one `PROPOSED` per assessment), a birth trigger refusing a row born decided
or over an assessment whose decision is not `DENY`, `HOLD` or `REVIEW`, reasons screened by `risk.holds_instrument_shape`;
`SELECT, INSERT` for the application, **every `UPDATE` refused by the edge trigger until `P13-TSK-012` replaces its
function with the machine** and grants the decision columns; never deleted.)*

*(As built by `P13-TSK-012`, 2026-10-24 - `risk V009`, `DecisionOverrides`, `OverrideActions`, the five doors under
`/v1/operator/risk/assessments/{assessmentId}/overrides` (`RISK_OVERRIDE`). **The proposal names its effect**, frozen
and judged against the assessment by the domain (`OverrideEffect.of`) and by the birth trigger, each alone: a
`WITHDRAWAL` `HOLD` - `RELEASE_HELD_WITHDRAWAL` (`resolution: PROCEED`) or `DENY_HELD_WITHDRAWAL` (`REFUSE`); a
`CARD_PAYMENT` `HOLD` - `CAPTURE_HELD_PAYMENT` or `VOID_HELD_PAYMENT`; any `DENY` or `REVIEW` - `RESUBMISSION`, nothing
dispatched (the command is terminal in its owner's machine; the customer's resubmission is judged afresh). **The
table's "a rejected one that the rejecter marks final" is re-shaped**: a denial or a void is an *approved* override's
effect (C7's four eyes and `MULTI_FACTOR`), never a rejection's - a rejection is open to anyone, the proposer's
withdrawal included, and an action it issued would be one person's downstream action (`INV-RSK-07`); a rejection issues
nothing. **The denied transfer's one-time allowance is not built** - for neither transfers nor cross-border (a
transfer's retry carries a new command ref; a cross-border retry on the same quote is answered from its seal before any
input is read), an owner item (`DESIGN-012` D4). **C7 is derived at the birth twice**: the domain's
`requiresSecondPerson` and the trigger's own reading of the assessment and the pinned `override_threshold_minor`, which
refuses `false` where a second holder is required - failing closed for no pin, no threshold, another currency or
scale. **The read-one-way refusals settled**: an `ALLOW`, a `CHALLENGE`, a `REFUSE` of a refusal and a standing override
are `409 risk.ActionNotProposable`; a decided override is `409 risk.OverrideStale` (new; the same person's retry
converges); one standing override per assessment (`decision_override_one_standing`, `PROPOSED` or `APPROVED`) beside
`V007`'s live one, so a rejected proposal may be followed by a new one and an approved one may not. **The issued action
(T-act's shape before `-017`)**: the approval records `action = effect` and, for a dispatchable one, a permit
(`action_due_at` = the approval's instant + the lease, `finapp.risk.override.action-lease`, 2 min) and its first attempt;
after the commit - on no transaction - the approval door calls payments' keyed door through `OverrideActions` under
`risk.action:<override id>` with the override's frozen reason code; the outcome (`DONE`, `ALREADY`, `REFUSED`) recorded
once by a conditional edge (`action_concluded_at` stamped, the permit cleared), audited `risk.ActionConcluded`,
announced `risk.RiskActionCompleted`; a dispatch that throws records nothing. **The re-drive**: the approver's retry
(the key replays the approval and re-drives the action) and, at least once, the override action leg on
`RiskMonitoringSchedule` - one due permit per transaction `FOR UPDATE SKIP LOCKED`, re-stamped a lease ahead and an
attempt counted before the dispatch, as the platform. Release racing the expiry: payments' row lock decides; the
override records `DONE` exactly when its release won, `ALREADY` or `REFUSED` when the expiry did
(`WithdrawalHoldDatabaseTest#releaseAndExpiryLeaveExactlyOne`, its override half). A released hold is **not** counted
in the velocity windows (`DESIGN-012` D5, an owner item). Counted by `DecisionOverrideDatabaseTest` (written, owed -
C5) and proven hermetically by `DecisionOverrideTest`, `OverrideActionTest`, `RiskOverrideDeskTest`.)*

*(Folded by `P13-TSK-017`, 2026-10-25, `risk V013`: the approval issues its action into `risk_action` (§4.7) under the
**override's own id**, born `APPROVED` and due at once in the approval's transaction (`decision_override_issues_its_action`,
deferred, for every writer) - so the key stays `risk.action:<override id>`; every action `V009` had issued was copied
first (awaiting - `DISPATCHED` with its permit and attempts; concluded - `DONE` or `FAILED` with its outcome) and the four
columns `action_outcome`, `action_due_at`, `action_attempts`, `action_concluded_at` dropped, the override's read keeping
its shape through a join on the action. `OverrideActions`, `OverrideActionDriver`, the leg on `RiskMonitoringSchedule`
and `OverrideActionTest` are gone - the proofs moved to `ActionDispatchTest` and `RiskActionDispatchTest`. The dispatch
now runs **as the platform** (T-act, §4.7), never under the approving officer's session: `P13-TSK-016`'s gate found the
door's first dispatch under an operator's `CUSTOMER`-typed session refused by payments' held doors and left to the leg's
re-drive - fixed here without widening payments' door (`RiskOverrideDeskTest`, `RiskActionDispatchTest#anApprovalsDispatchSucceedsOnTheFirstAttempt`).)*

### 4.3 Velocity window (`risk.velocity_window`) — `P13-TSK-006` (`risk V005`) — not a machine

A velocity window has no status and no edges: it is a durable counter per primary key (dimension, subject_ref,
window_kind, window_start) — dimensions party, source account, destination account or beneficiary, device, merchant;
kinds `H1`, `D1`, `D7`, `D30` — with `count`, `sum_minor` and `currency`. **The upsert** is one statement per row in
T-eval, in sorted key order, after the caller's locks: `INSERT … ON CONFLICT (…) DO UPDATE SET count = count + 1,
sum_minor = sum_minor + EXCLUDED.sum_minor RETURNING count, sum_minor` — the row lock the statement takes is the
arbiter, so ten concurrent movements of one subject count ten (`VelocityWindowDatabaseTest#tenMovementsCountTen`).
T-eval **reads** the current figures first, evaluates, and upserts the increments **only when the decision permits the
movement**, in the same transaction as the money, so a refused movement counts for nothing and a committed one counts
exactly once (OQ-1; `INV-RSK-03`). The only `UPDATE` the application role holds on the table is this statement's, and
a `CHECK` holds that a sum never decreases within a window. **The proof:** `VelocityProof` recomputes every live window
from the monitored transactions (§4.11) and compares — a difference is an alert (`finapp.risk.velocity.proof{verdict}`),
never self-corrected. **Invalid:** a decrement; a window written outside the judged command's transaction; a window
written for a refused movement; a window keyed on an instance clock.

*(As built by `P13-TSK-006`, 2026-10-23 — `risk V005`, `VelocityWindows`, `VelocityProof`. The dimensions are
`VelocityDimension` (`PARTY`, `SOURCE_ACCOUNT`, `DESTINATION`, `DEVICE`, `MERCHANT`), the coordinator's decision. The
read is **serialised (R2a)**: one advisory lock per (dimension, subject), namespace `131`, sorted, before the database
clock and the figures are read — the plan's unlocked read let two movements of one party from two accounts both pass.
The window is a tumbling bucket anchored at Monday 1970-01-05 UTC, its start a pure function of the database instant
read after R2a; every dimension a movement carries counts in all four kinds. **The database holds the machine-less
rules for every role** (`velocity_window_counts_forward`): born at count 1; an update exactly +1 with a non-decreasing
sum, the key, currency and scale unchanged; written only while the database clock is inside the window (one minute's
grace after its end) — so "keyed on an instance clock" is refused by the table, not only avoided by the code; no
`DELETE` and no `TRUNCATE`. A window in another currency is never summed (the upsert's `WHERE`; refused on the read).
**The proof** compares the windows of the dimensions its evidence can name — the journal's transfers prove
`SOURCE_ACCOUNT` and `DESTINATION`; `PARTY`, `DEVICE` and `MERCHANT` are reported unprovable — within a ±5 s edge
skew, a window counted after the evidence is complete `PENDING`; see `PHASE_13_PLAN.md` §12.4's note for the evidence
contract `-004`…`-011` inherit.)*

### 4.4 Risk signal (`risk.risk_signal`) — `P13-TSK-005` (`risk V003`) — not a machine

Born once per source event (`UNIQUE (source_event_id)`), `INSERT`-only, never updated: kind from a closed vocabulary,
subject (`SubjectKind` — party, account, device, merchant, beneficiary, session — and ref), observed-at, and the
attributes the event carries — nothing read from the source's tables; identity's device description arrives hashed at
the source and no description is stored (OQ-4). Recorded by inbox handlers (`InboxEventHandler`s named
`risk.<component>`, consumer `risk.signals`, one per source module) in the handler's transaction (T-sig) — the inbox's
dedupe and the unique together make the same event three times one signal
(`SignalIntakeDatabaseTest#theSameEventTwiceRecordsOnce`; `INV-IDEM-04`, `INV-EVT-04`) — and by the sweep (T-mon) for
what it derives. Publishes `RiskSignalRecorded` (signal id, kind, subject kind and ref). A delayed signal after its
subject's case is disposed records normally and re-opens nothing (§4.6). **Invalid:** any `UPDATE`; a second signal per
source event; an attribute above its bound or of an unknown kind.

*(As built, `P13-TSK-005`, 2026-10-23: `SignalKind` — thirteen kinds, one per consumed event that exists, each
declaring its subject kind and its typed, bounded attribute schema; `SubjectKind` + `IDENTITY` and `CREDIT_AGREEMENT`,
the aggregates the events name; every handler under the one consumer name `risk.signals`; the row's `observed_at` the
event's own instant and `recorded_at` the database's by trigger; `UPDATE`, `DELETE` and `TRUNCATE` refused for every
writer by trigger, the grant `SELECT, INSERT`; an attribute outside its kind's shape refused by
`risk_signal_attributes_match_kind` as well as by the domain; a version or payload the build cannot read thrown, so the
inbox seeks back and stalls loudly — never truncated, never acknowledged. The completions record no signal: they are
hints to `MonitoringHints` (`-019`).)*

*(As built, `P13-TSK-014`, 2026-10-25, `risk V011`: three kinds about the `PARTY` - `REFUND_COMPLETED`,
`CHARGEBACK_RECEIVED` (attributed from risk's own sealed assessment of the payment: the payer the subject, the merchant
`merchant_subject_ref`, the intent `intent_ref`; a payment risk never judged records none, acknowledged) and
`BNPL_APPLICATION_SUBMITTED` (the applicant the event names). Still not a machine.)*

*(As built, `P13-TSK-025`, 2026-10-26: no kind added - the entry's "`OBLIGATION_DEFAULTED` carrying the obligation's
kind and id" is `-005`'s `LOAN_DEFAULTED` / `BNPL_DEFAULTED`, the kind naming the obligation's module and the
`CREDIT_AGREEMENT` subject its id. A signal of a kind that raises an alert of its own (`SignalAlerts`; a default, §4.5)
has it raised in T-sig **on every delivery that reaches the handler** - the born row, or the standing row read back by
its source event (`RiskSignalStore.recordedFrom`) - and that recording runs under the platform's actor
(`RiskSignalIntake.asThePlatform`); every other kind records with no actor, as before.)*

### 4.5 Alert (`risk.risk_alert`) — `P13-TSK-015` (`risk V010` - planned `V008`, the next free at its build: the table, raise, dedupe and suppression); the folding into a case `-016`; the monitoring rules `-020`; the fraud rules `-013`, `-014`

```
 (raised by a rule — T-eval or T-mon) OPEN ──attached to a case (opened or found open)──> ATTACHED
                                       └──★ suppressed by an operator with a reason, a second holder approving (never the system)──> SUPPRESSED
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `OPEN` | S — T-mon (a monitoring rule over a subject's monitored transactions and windows) or T-eval (a fraud rule, synchronously; the retrospective fraud rules run in the sweep) | `UNIQUE (subject_ref, rule_code, window_start)` with `ON CONFLICT DO NOTHING` — two instances raise once (`AlertDatabaseTest#twoInstancesRaiseOnce`; `INV-RSK-06`); carries evidence refs (signal, assessment, monitored transaction ids — never copies), kind (`AlertKind`), severity, the policy version | — | `AlertRaised` (alert id, kind, severity, subject ref) | — |
| `OPEN → ATTACHED` | S, in the raising transaction (`-016`'s folding): an alert raised while its subject has an open case of the kind attaches to it — **the system's dedupe** — else T-mon opens the case (T-eval: the case `REVIEW`/`HOLD` opened); or P, `POST /v1/operator/risk/cases` from an alert, `RISK_INVESTIGATE`, key `risk.case:<actor>` (T-case) | (R4) the case row; (R5) the alert conditional `OPEN`; one open case per (subject, kind) — ten alerts, one open case (`CaseDatabaseTest#tenAlertsOneOpenCase`) | `409 risk.AlertNotOpen` | `CaseOpened` when the attach opened the case | `risk.CaseOpened` (when opened) |
| `OPEN → SUPPRESSED` ★ | P only — never the system: `POST /v1/operator/risk/alerts/{id}/suppression` proposed by a `RISK_INVESTIGATE` holder with a reason, approved by a second holder with `RISK_CASE_APPROVE` at `…/suppression/approval` (or rejected at `…/suppression/rejection`), key `risk.alert:<actor>` (`-018`) | (R5) conditional `OPEN`; the reason code; the four-eyes `CHECK` | `409 risk.AlertNotOpen`, `422 risk.ReasonRequired`, `403 risk.SelfApprovalRefused` (audited `FAILED`) | — | `risk.AlertSuppressed` |

The queue: `GET /v1/operator/risk/alerts?status=&kind=&after=` (`RISK_INVESTIGATE`, oldest first, keyset paged,
audited). An alert is **never deleted** and never re-opened; a later observation of the same (subject, rule, window) is
the same row, a later window a new alert — which attaches to the subject's open case of the kind rather than opening a
second one (`-016`). The fail-safe's `RiskEvaluationUnavailable` / `RiskPolicyNoneActive`, the
velocity proof's difference and an `UNCLASSIFIED` journal entry are read here as **operational alerts**
(`infra/prometheus/rules/risk.yml`, fed by `finapp.risk.evaluation.unavailable`, `velocity.proof{verdict}` and
`monitoring.unclassified`), not rows of this machine — §8. **Invalid:** anything out of `ATTACHED` or `SUPPRESSED`;
`ATTACHED → OPEN`; a second alert per (subject, rule, window start); an alert carrying a reason text, a score or PII
into an event (`INV-RSK-08`); an alert reaching any customer or merchant surface.

*(As built, `P13-TSK-015`, 2026-10-25, `risk V010`.)* **The raise** - `Alerts.raise` / `raiseAll` on the caller's unit
of work (T-eval's or T-mon's, READ COMMITTED): each birth ONE statement, `INSERT … ON CONFLICT ON CONSTRAINT
risk_alert_born_once DO NOTHING RETURNING` (the named constraint - a primary-key collision is never swallowed); a
conflict reads the standing row and writes nothing; `risk.AlertRaised` (`alertId`, `kind`, `severity`, `subjectKind`,
`subjectRef`) only when born; `raiseAll` raises a transaction's alerts in `AlertKey` order and every birth before any
fold (a birth is an insert at (R3)'s position, the fold the case's (R4) then the alert's (R5)), so overlapping raisers
meet on the unique in one order. **The window is a bucket**: `window_kind` + `window_start`, aligned by `CHECK` to the
velocity windows' anchor (`date_bin` over `risk.velocity_window_length`), computed from a database instant that is the
evidence's own (`AlertWindow.of(kind, WindowCut)` in T-eval; a born-once row's stamp in T-mon) - so a replay recomputes
the same start; the unique stays the plan's triple. **The folding** is the port `AlertFolding` (`-016` implements it;
`NONE` wired - every alert stays `OPEN`, queued in `risk_alert_open_queue`); only a born alert of a kind that opens a
case is folded; `Alerts.attach` is the alert's own `OPEN → ATTACHED` edge (`attached_case_id`'s foreign key is
`-016`'s migration). **The suppression** (P only, `EMPLOYEE` by domain and `CHECK`): `proposeSuppression` (a closed
`SuppressionReason` - `FALSE_POSITIVE`, `ACTIVITY_EXPLAINED`, `RULE_DEFECT`, `DATA_DEFECT`, deliberately no
`DUPLICATE` - and a screened text; one live proposal), `approveSuppression` (another holder; the proposer `403
risk.SelfApprovalRefused` recorded `FAILED`, `risk_alert_suppression_four_eyes` holding every writer alone - the trigger
deliberately does not judge it), `rejectSuppression` (anyone: the proposal cleared, the alert `OPEN`); audited
`risk.AlertSuppressionProposed`, `risk.AlertSuppressed`, `risk.AlertSuppressionRejected`; refusals `409
risk.AlertNotOpen` (the register's status - the table above reads 409 too; the backlog entry 422), `409
risk.SuppressionStale`, `422 risk.ReasonRequired`, `403 risk.SelfDealingRefused` - **fail closed for the subject too**:
an alert whose subject's party cannot be placed (`RiskSubjectParties` places a party and an identity today) is not
suppressible until its join exists. **The operational kinds** (`UNCLASSIFIED_TRANSACTION`, `VELOCITY_DIVERGENCE`) are
declared and admitted by the table (their own code as rule, no policy, never attached) but **raised by no writer**: the
reading settled in §8 stands - their alert is the Prometheus rule on `monitoring.unclassified` and
`velocity.proof{verdict}`; the fail-safe's alert the rule on `evaluation.unavailable` (no `AlertKind` exists for it).
Meters `finapp.risk.alert{kind,severity}` and `.alert.suppressed{reason_code}` (`app`'s `RiskAlertMetrics`); span
`risk.alert.raise`.

*(As built, `P13-TSK-013`, 2026-10-25 - the first births, the account-takeover alert.)* **Two raisers, one key.**
`ACCOUNT_TAKEOVER` (severity `HIGH`, constant) is keyed (the `PARTY`, the anchor rule - `RECENT_CREDENTIAL_CHANGE` or
`RECENT_RECOVERY` -, the `D1` bucket of the anchor signal's own `observed_at`), so the synchronous and the retrospective
raise of one sequence meet on one row. **T-eval** (S, inside the judged movement's transaction): after the seal and
`risk.RiskDecisionMade`, before `CaseOpener`, when the sealed hits show the sequence (`AtoSequence` - an anchor fired, no
judged continuation refuting it, the movement a transfer, withdrawal or cross-border payment), one `raiseAll` through
`AlertRaiser` **behind a savepoint of its own under the 250 ms budget** - a birth that fails or waits past the budget is
undone alone and reported, the decision and the money transaction standing; evidence the anchor signal and the
assessment. **T-mon's profile leg** (S, under the head's lock R8): the `MONITORING` version's anchors over the party's
identities' signals; a fired anchor raises in the recompute's transaction, rating changed or not; a failed raise fails
the recompute whole; evidence the anchor signal. Both born `OPEN` and queued until `-016`'s folding.

*(As built, `P13-TSK-014`, 2026-10-25 - the abuse alerts, no edge added.)* **T-eval** raises `MERCHANT_ABUSE` (folding
into a `MERCHANT_ABUSE` case) on the **merchant** a checkout payment pays when its `REFUND_RATIO` or `CHARGEBACK_RATIO`
fired, keyed (the merchant, the rule, the rule window's bucket containing the cut), the sealed assessment its evidence,
severity `MEDIUM` for refunds and `HIGH` for chargebacks; **the profile leg** raises `FRAUD` on the **party** for its own
ratios and `BNPL_MULTI_APPLICATION`, keyed (the party, the rule, the ratio's bucket or the latest application's), the
latest evidence signal its evidence - both in the existing one `raiseAll` per transaction, both born `OPEN`. A
`MERCHANT_ABUSE` alert's subject is not yet placeable in a party, so its suppression fails closed until a merchant join
exists (`-015`'s D6; `-018`'s door).

*(As built, `P13-TSK-018`, 2026-10-25 - the door, the persons, the queue; `risk V014`.)* **The suppression's persons
are authenticated humans** - `CUSTOMER`-typed as the platform's session establishes every operator, or `EMPLOYEE`; never
`SYSTEM`, `SERVICE` or `MERCHANT` - by the domain (`Cases.person`) and by `risk_alert_suppression_by_persons`, re-made by
name in `V014` (the "`EMPLOYEE` by domain and `CHECK`" above read so until then, and would have refused every analyst).
**The door**: `POST /v1/operator/risk/alerts/{alertId}/suppression` (`RISK_INVESTIGATE`, `201`), `…/approval`
(`RISK_CASE_APPROVE` from a `MULTI_FACTOR` session, `200`), `…/rejection` (`RISK_CASE_APPROVE`, `200`), each keyed
`risk.alert:<type>:<id>`, the answer the alert as it stands. **The merchant's alert is now placeable**: the subject's
party is the accountable join `-016` built for the case (`RiskSubjectParties.accountablePartyOf` - a merchant's
organisation), so a `MERCHANT_ABUSE` alert is suppressible by any analyst but one of its organisation's party
(`DESIGN-018` D6: needed and resolved by reuse - no port added, nothing enrolled in the profile sweep). **The queue**
(`GET /v1/operator/risk/alerts`): oldest first by `(raised_at, id)` of one status (`OPEN` unless named), keyset-paged,
one snapshot a page, audited `risk.AlertRead`, every alert of the reader's own party - or whose party cannot be placed -
withheld.

*(As built, `P13-TSK-025`, 2026-10-26 - the fact's alert; `risk V017`.)* **A third origin beside the rule and the
operational kind: the fact.** `OBLIGATION_DEFAULTED` is raised in **T-sig** (S, the inbox handler's transaction, under
the platform's actor) for each recorded default - `-005`'s `LOAN_DEFAULTED` / `BNPL_DEFAULTED` signal, on every delivery
that reaches the handler (born now, or standing from an earlier one, read back by its source event) - by
`CollectionsCases` through the one `raiseAll`: about the `CREDIT_AGREEMENT`, **its own code its rule and no policy**
(a default alerts whether or not a `MONITORING` version is active), its evidence exactly its one signal, severity
`MEDIUM`, keyed (the agreement, `OBLIGATION_DEFAULTED`, the `D1` bucket of the default's business date) - born once per
default of an obligation, a re-default after a cure its own alert. `V017` re-makes `risk_alert_kind_is_known`,
`risk_alert_rule_code_is_known`, `risk_alert_rule_matches_kind` and `risk_alert_policy_named` by name over the own-code
kinds and adds `risk_alert_default_names_its_agreement`. It folds into the **agreement's party's** `COLLECTIONS` case
(§4.6), is placed for the queue and the self-dealing judgement through the accountable join (a loan's borrower, a BNPL
obligation's customer - `app`'s `JdbcCreditAgreementParties` over lending's and bnpl's published views), and is
`ATTACHED` at birth, so it is never suppressible.

### 4.6 Case (`risk.risk_case`, `risk_case_action`, `risk_case_event`) — `P13-TSK-016` (`risk V012` - planned `V009`, the next free at its build; the folding of alerts into cases is this task's); the queues and the tipping-off control `-018`; the AML case `-021`; collections `-025` (C3)

```
 (opened — T-eval on REVIEW | HOLD | a policy-flagged DENY; the sweep on an alert with no open case; by hand; -025 on LoanDefaulted | BnplDefaulted)
      OPEN ──taken by an investigator──> IN_INVESTIGATION ──escalated (level and queue raised)──> ESCALATED ──★ disposed──> DISPOSED
       │                                       └──────────────────────────────────────────────────────────────★ disposed──> DISPOSED
       └──★ disposed (CLOSED_FALSE_POSITIVE | CLOSED_NO_ACTION, from OPEN, one person, reasoned — plan §5)──────────────────────────> DISPOSED
```

Kinds `CaseKind { FRAUD, ATO, MERCHANT_ABUSE, AML, COLLECTIONS }`; status
`CaseStatus { OPEN, IN_INVESTIGATION, ESCALATED, DISPOSED }`; **one open per (subject, kind)** (partial unique over the
three live states); a `revision` counter for concurrent updates; an SLA deadline stamped at opening; a level and a
queue.

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `OPEN` | S — T-eval (`REVIEW`, `HOLD`, a `DENY` the policy flags), with the assessment as its first evidence; T-mon (an alert with no open case of the kind); `-025`'s handler on `lending.LoanDefaulted` / `bnpl.BnplDefaulted` (`COLLECTIONS`, facts only — C3). P — `POST /v1/operator/risk/cases` (from an alert, or by hand), `RISK_INVESTIGATE`, key `risk.case:<actor>` (T-case) | the partial unique one-open per (subject, kind): a loser finds the open case and attaches to it; **never the operator's own party** (self-dealing, fail closed); the SLA deadline and `revision = 0` stamped | `risk.SelfDealingRefused` (audited `FAILED`); `409 risk.AlertNotOpen` | `CaseOpened` (case id, kind, status) | `risk.CaseOpened` |
| `OPEN → IN_INVESTIGATION` (take) | P, `POST /v1/operator/risk/cases/{id}/actions` (`take`), `RISK_INVESTIGATE`; the body carries the `revision` | (R4) `FOR UPDATE`; the `revision` compare-and-set — the loser answers `409 risk.CaseRevisionStale` (`CaseDatabaseTest#concurrentUpdatesKeepEveryAction`); a take is one person's act | `409 risk.CaseRevisionStale`, `409 risk.CaseNotOpen`, `risk.SelfDealingRefused` | — | `risk.CaseTaken` |
| (no edge) note · evidence attached | P, the same door (`note`, `evidence`) from any live state | (R4); the compare-and-set; the note bounded and screened (`InstrumentShapes`); evidence as references only (signal, assessment, journal entry ids), resolved through audited reads | as above; `422 risk.ReasonRequired` | — | `risk.CaseNoted` |
| `IN_INVESTIGATION → ESCALATED` | P, the same door (`escalate`, reasoned) | (R4); the compare-and-set; level and queue raised, recorded as an investigator action | as above | `CaseEscalated` | `risk.CaseEscalated` |
| disposition proposed (no edge) | P, `POST /v1/operator/risk/cases/{id}/dispositions` (the `Disposition`, a reason), `RISK_INVESTIGATE` | (R4); the compare-and-set; one live disposition proposal per case; a `CONFIRMED_*` or an action-issuing disposition needs a second person (★ below); a `REFERRED` disposition recommends — never performs — a reversal (ADR-0112) | `409 risk.CaseNotOpen`, `422 risk.ReasonRequired` | — | `risk.DispositionProposed` |
| `IN_INVESTIGATION \| ESCALATED → DISPOSED` ★ for every `CONFIRMED_*` and for any disposition that issues an action; one person for `CLOSED_FALSE_POSITIVE` / `CLOSED_NO_ACTION` / `REFERRED` with no action | P ≠ proposer, `…/approval`, `RISK_CASE_APPROVE`, `MULTI_FACTOR` for a `CONFIRMED_*` | (R4) conditional live; the four-eyes `CHECK`; **in the same transaction**: the risk actions the disposition issues `PROPOSED → APPROVED` (§4.7), the profile's recompute under (R8) — `PROHIBITED` only here (`INV-RSK-10`), the report draft permitted for `CONFIRMED_SUSPICIOUS` (§4.8) | `403 risk.SelfApprovalRefused` (audited `FAILED`), `risk.SelfDealingRefused` | `CaseDisposed` (case id, kind, status, disposition code); `RiskActionRequested` per action; `CustomerRiskRatingChanged` when the rating moved | `risk.CaseDisposed`, `risk.DispositionApproved` |
| disposition rejected (no edge) | P, `…/rejection` | (R4); the compare-and-set | `422 risk.ReasonRequired` | — | `risk.DispositionRejected` |

**A case re-opens never** (OQ-6): a delayed event after a disposition records its signal and, if a rule fires, raises a
new alert which opens a **new** case referencing the old (`CaseDatabaseTest#aLateEventAfterDispositionOpensANewCase`).
Every act of the investigation is an **investigator action** — append-only, reasoned, audited (`risk_case_action`:
note, evidence attached, escalation, action proposed/approved/rejected, disposition proposed/approved/rejected, report
drafted) — and both concurrent investigators' actions are kept while one answers `409` (mandatory scenario 5). The
queue: `GET /v1/operator/risk/cases?status=&kind=&after=` and `GET …/{id}` (`RISK_INVESTIGATE`, audited; never the
operator's own party). An investigator who leaves mid-case changes no state: the SLA gauge and alert find the case, the
queue is oldest-first. **The tipping-off control** (`INV-RSK-08`, `-018`): no customer or merchant route reads a case,
an alert or a report; no response differs with a case open (a timing difference outside this phase's claims); no event,
log line or metric tag carries a note, a reason text, a score or a name. **Invalid:** anything out of `DISPOSED`;
`DISPOSED → OPEN | IN_INVESTIGATION | ESCALATED`; a second open case per (subject, kind); an action or a disposition on
a `DISPOSED` case (`409 risk.CaseNotOpen`); an update without the current `revision`; a `CONFIRMED_*` disposition by
one person (`CHECK`); a `PROHIBITED` rating from anywhere but an approved disposition; a case whose subject is the
actor's own party; a case row written by any module but `risk`.

*As built (`P13-TSK-016`, 2026-10-25, `risk V012`; `DESIGN-016`):* the machine as drawn, every edge judged by the domain
(`RiskCase`, `Cases`) and again by `risk_case_permits_only_machine_edges` - born `OPEN` at revision 0, `L1`,
`FIRST_LINE`; the facts frozen; the revision exactly one up a step; nothing out of `DISPOSED`; an escalation one level up
to a queue above the first line; a two persons' proposal only on a taken case; a close from `OPEN` only as a one-person
`CLOSED_*`; the stamps the database's - and every revision its `risk_case_event` row (the deferred
`risk_case_has_its_history`). **Read differently from the table above, recorded:** (1) a one-person close is decided by
its own proposal (`POST …/dispositions` answers `DISPOSED`), so the approval door serves only the two persons'
dispositions and is stepped up `MULTI_FACTOR` for every one - `REFERRED` and a prohibiting one as well as every
`CONFIRMED_*` (D2, D14 of the design); (2) **the profile's `PROHIBITED` is not written in the approval's transaction** -
the profile leg takes the head (R8) before its folds take a case (R4), so a case act must never take R8 under R4
(`P13-TSK-013`'s finding): the approval disposes the case alone and `ProfileRecompute.applyDisposition` writes the
`DISPOSITION` version in a transaction of its own after the commit - the door's follow-up, again on the key's replay, and
the case leg's re-drive - one version per disposition (`customer_risk_profile_version_one_per_disposition`);
`PROHIBITED` is **asked** by the proposal (`prohibit`), admitted for a `CONFIRMED_*` of a `PARTY`'s case alone (D3: an
`ATO` case's confirmed takeover names the victim; a confirmed suspicion that refused the customer everywhere would tip
them off); lifting it has no door yet; (3) no disposition issues a risk action until `-017` - the four-eyes set is
`CONFIRMED_*`, `REFERRED` and a prohibiting one, `-017` widens it *(as built by `-017`: **no disposition issues an
action** - an action is the case's own four-eyes act on its own row (§4.7), so the set is not widened; a disposition is
refused `409 risk.CaseActNotAdmitted` while an action proposed on the case awaits its decision, the domain under (R4) and
`risk_case_disposes_no_standing_action` for every writer)*; (4) the take admits a take-over by another
investigator (a reassignment, D12); every act, the approval and rejection included, carries the revision; (5) the codes
`409 risk.CaseActNotAdmitted` (a standing proposal; a decision on another; the top level), `422 risk.CaseNotTaken` and
`422 risk.CaseRequestInvalid` beside the planned two (D10); (6) the queue and the case read are `-018`'s - the acts answer
the case's head; (7) the system's opening is audited `risk.CaseOpened` under the acting principal (T-eval's caller, the
leg's `SYSTEM`), an attach recorded by its history row alone; the alerts born `OPEN` before this task are drained by the
case leg on `RiskMonitoringSchedule`.

*As built (`P13-TSK-025`, 2026-10-26, `risk V017`; `DESIGN-025`) - the `COLLECTIONS` kind (owner decision C3, facts
only):* **(birth) → `OPEN`** only by its default's alert (§4.5) folding in T-sig - `lending.LoanDefaulted` /
`bnpl.BnplDefaulted` recorded by `-005`'s handler under the platform's actor, `risk.CaseOpened` audited `SYSTEM` - never by
hand, never from an alert by hand (`422 risk.CaseRequestInvalid`), never by an assessment; **its subject is the defaulted
agreement's party** (`Cases`' kind rule, the accountable join placing a loan's borrower or an obligation's customer), so
one open per (party, `COLLECTIONS`) holds and a second default of one party attaches (`ALERT_ATTACHED`, a revision of its
own); a borrower that cannot be placed refuses the delivery whole - signal, alert and dedupe rolled back, the partition
stalled loudly, never a default without its case. Born `L1`, `FIRST_LINE`, SLA 168 h (`SlaDeadlines`; C3 fixes no
figure - owner item). Worked by **facts**: take, note, evidence, escalation as every kind; **disposed `CLOSED_NO_ACTION`**
(one person, from `OPEN` too - worked, or cured) **or `REFERRED`** (two persons on a taken case - a referral to the owning
domain's own restructuring or write-off path, a recommendation, never an act); `CLOSED_FALSE_POSITIVE`, `CONFIRMED_*` and
`PROHIBITED` refused `422 risk.CaseRequestInvalid` (`DispositionRules.admitted`) and, for every writer,
`risk_case_collections_disposed_by_facts` (with `risk_case_collections_of_a_party` and
`risk_case_collections_opened_by_a_default`). **No `ActionKind`** (`ActionRules`, `V013`'s trigger - unchanged). A cure
(`*DefaultCleared`) is not consumed: the case waits for a person. Nothing of it reaches lending, bnpl, the ledger or a
customer (`INV-RSK-02`, `INV-RSK-08`).

### 4.7 Risk action (`risk.risk_action`) — `P13-TSK-017` (`risk V013` - planned `V010`, the next free at its build); the override's release ran in this shape from `-012`'s approval door until `-017` folded it in

```
 (proposed from a case, or issued by an approved override | disposition) PROPOSED ──★ a second person approves──> APPROVED ──RiskActionSchedule claims and dispatches──> DISPATCHED ──the owning domain's outcome──> DONE
                                                                           │                                                                                                 └──> FAILED (re-proposable)
                                                                           └──rejected──> REJECTED
```

Kinds `ActionKind { SUSPEND_IDENTITY, REINSTATE_IDENTITY, REVOKE_SESSIONS, SUSPEND_ACCOUNT, REINSTATE_ACCOUNT,
SUSPEND_MERCHANT, BLOCK_BENEFICIARY, RELEASE_HELD_WITHDRAWAL, DENY_HELD_WITHDRAWAL, RESCREEN_PARTY }` (plan §12.8; C10
for the ATO pair) — each a keyed command through the owning domain's **existing** door, never a new mutation (plan §20:
no credential reset; no reversal).

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `PROPOSED` | P, `POST /v1/operator/risk/cases/{id}/risk-actions` (kind, target, reason), `RISK_INVESTIGATE`, key `risk.case:<actor>` (T-case); or S — issued by an approved disposition (§4.6), born `PROPOSED` and approved in that act's transaction (an approved override's release takes this shape from `-017` on; before it, `-012` runs the release from its approval door — §4.2a) | (R4) the case; `UNIQUE (case_id, kind, target_ref)` over a live action; the kind admitted for the target's kind | `409 risk.ActionNotProposable` (a live twin; a kind the target does not admit; a `DISPOSED` case — read one way, §8), `409 risk.CaseNotOpen`, `422 risk.ReasonRequired` | — | `risk.ActionProposed` |
| `PROPOSED → APPROVED` ★ | P ≠ proposer, `…/approval`, `RISK_CASE_APPROVE` (`202`) | (R4); (R6) conditional `PROPOSED`; the four-eyes `CHECK`; `next_attempt_at` stamped | `403 risk.SelfApprovalRefused` (audited `FAILED`) | `RiskActionRequested` (action id, kind, target kind and ref) | `risk.ActionApproved` |
| `PROPOSED → REJECTED` | P, `…/rejection` | (R4); (R6) | `422 risk.ReasonRequired` | — | `risk.ActionRejected` |
| `APPROVED → DISPATCHED` | S, `RiskActionSchedule` (T-act): the claim on `next_attempt_at` (`SKIP LOCKED`), then **(R6) `FOR UPDATE` and nothing else of risk's**, then the owning domain's command on **its** lock order with key `risk.action:<id>` — ten approvers, one dispatch (`RiskActionDatabaseTest#tenApproversOneDispatch`) | (R6) conditional `APPROVED` | — | — | `risk.ActionDispatched` |
| `DISPATCHED → DONE \| FAILED` | S, T-act — the domain's answer in the same call, or a later claim for a domain whose effect concludes asynchronously (a released withdrawal's dispatch); a domain's refusal (an identity already suspended) is `DONE(ALREADY)`, a recorded outcome, never an error; a timeout is re-driven under the same key and replays (`RiskActionDatabaseTest#aReDriveReplays`; `INV-IDEM-01`) | (R6) conditional `DISPATCHED` | — | `RiskActionCompleted` (action id, kind, target kind and ref, outcome) | `risk.ActionConcluded` |

A `FAILED` action is **re-proposable, never re-dispatched by itself**: a new proposal, four eyes again. Risk never
retries the owning domain's business failure (mandatory scenario 7): a downstream payment operation that fails after
the decision leaves the decision standing and the outcome recorded. **Invalid:** anything out of `DONE`, `FAILED` or
`REJECTED`; `APPROVED → PROPOSED`; `DISPATCHED → APPROVED`; a dispatch holding (R4) or any money row before entering
the domain; a direct write to any other module's table; a reversal kind (a reversal is the owning domain's four-eyes
act; a case may only recommend it — `REFERRED`); approval by the proposer (`CHECK`).

*(As built by `P13-TSK-017`, 2026-10-25 - `risk V013`, `RiskActions`, `ActionRules`, `ActionDispatch`,
`JdbcRiskActionStore`; `app`'s `RiskActionController` / `RiskActionDesk`, `RiskActionDriver`, `RiskActionSchedule`,
`IdentityRiskActions`, `MerchantRiskActions`. **The key is the id**: a case's action takes the id of its `ACTION_PROPOSED`
investigator action (a foreign key on (id, case)), an override's the override's, so `risk.action:<id>` is fixed at birth
and every re-drive presents it. **What a case proposes** (`ActionRules`, judged under (R4) and again by the row's
trigger): the case not `DISPOSED` and taken (`422 risk.CaseNotTaken` while `OPEN` - an action has an accountable
investigator), the revision compared; a party's case (`FRAUD`, `ATO`, `AML`) - `SUSPEND_IDENTITY`, `REINSTATE_IDENTITY`,
`REVOKE_SESSIONS` on **its own party**; a `MERCHANT_ABUSE` case - `SUSPEND_MERCHANT` on **its own merchant**; any case
but `COLLECTIONS` - `RELEASE_HELD_WITHDRAWAL`, `DENY_HELD_WITHDRAWAL`, `CAPTURE_HELD_PAYMENT`, `VOID_HELD_PAYMENT` on a
movement **the case holds** (its opening assessment or an attached one, sealed `HOLD` for that operation); the reason
code handed to the owner `RISK_CASE_<case kind>` (an override's its `OverrideReason`), never the analyst's text.
**No owning command, no kind**: accounts has no suspension command and crossborder no beneficiary block, so
`SUSPEND_ACCOUNT`, `REINSTATE_ACCOUNT` and `BLOCK_BENEFICIARY` are unavailable (`ActionKind.available()`), refused `409
risk.ActionNotProposable` and refused by the row's trigger - their ports stay unimplemented and nothing is added to their
owners; `RESCREEN_PARTY` is `-022`'s sweep, never a case's; no credential reset exists (`RiskVocabulariesTest`).
**Four eyes**: the approval `RISK_CASE_APPROVE` from a `MULTI_FACTOR` session by a person other than the proposer -
`403 risk.SelfApprovalRefused` recorded `FAILED` (`INV-RSK-07`, `INV-AUD-04`) and `risk_action_four_eyes` for every
writer; the rejection open to any `RISK_CASE_APPROVE` holder, the proposer's withdrawal included; a decided action is
`409 risk.CaseActNotAdmitted` to another person and converges for the same one. The approval's permit is due at once
(`clock_timestamp()`), `risk.RiskActionRequested` in its transaction. **T-act in three steps**: (1) the claim - one
statement, (R6) `FOR UPDATE SKIP LOCKED` where the permit is due and the status `APPROVED` or `DISPATCHED`, moved
`DISPATCHED`, the permit a lease ahead (2 min) and one attempt counted, **committed**; (2) the owner's existing command
on no transaction **as the platform** (`SecurityContext.enterSystem()` in `RiskActionDriver.asThePlatform` - a person
decided, the platform executes) under `risk.action:<id>`; (3) the record - `DONE` for the owner's `DONE` or `ALREADY`,
`FAILED` for its `REFUSED`, conditional on `DISPATCHED`, once. **The owner's refusal is `FAILED(REFUSED)`**: the
table's "a domain's refusal (an identity already suspended) is `DONE(ALREADY)`" is read as *the effect already in
place* - an identity already suspended, a merchant already suspended, no live session answers `ALREADY`; no identity,
identity's not-self rule (the approver's identity the decider) or a closed merchant answers `REFUSED`, recorded and never
retried (re-proposable). A thrown dispatch - or a record that fails to commit after the owner answered - records nothing
and keeps its permit - re-driven under the same key, the owner replaying it: payments by its own claim, identity and merchant through `app`'s adapters keying the command with the
platform's `IdempotentExecutor` in the owner's own `REQUIRES_NEW` transaction (scopes `identity.risk-action`,
`merchant.risk-action`). Identity's three act on every identity of the party (a bounded read). The approval door drives
its action after the commit; `RiskActionSchedule` (every instance, no leader) re-drives the due and the lapsed. **No
disposition issues an action** (`DESIGN-017` D11, §4.6 (3)). Doors `POST /v1/operator/risk/cases/{caseId}/risk-actions`
(`201`, `RISK_INVESTIGATE`), `…/risk-actions/{actionId}/approval` (`202`) and `…/rejection` (`200`), each keyed under
the case desk's scope with a closed body carrying the case's revision; every answer is the action with the case's
revision. Counted by `RiskActionDatabaseTest` (risk) and `RiskActionApiTest` (`app`) - written, owed (C5); proven
hermetically by `RiskActionTest`, `ActionDispatchTest`, `RiskActionMigrationTest`, `RiskActionDispatchTest`,
`RiskActionDeskTest`, `RiskActionScheduleTest`, `IdentityRiskActionsTest`, `MerchantRiskActionsTest`.)*

### 4.8 Regulatory report (`risk.regulatory_report`, `regulatory_report_event`) — `P13-TSK-021` (`risk V011`)

```
 (drafted from a DISPOSED CONFIRMED_SUSPICIOUS case) DRAFT ──★ a second AML_REPORT_APPROVE holder approves──> APPROVED ──filed (AML_REPORT_FILE)──> FILED ──the authority's acknowledgement──> ACKNOWLEDGED
                                                                                                                                                      └──the authority's rejection──> REJECTED  (a new draft follows)
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `DRAFT` | P, `AML_REPORT_APPROVE`, `POST /v1/operator/risk/cases/{id}/reports`, key `risk.report:<actor>` (T-rep) | (R4) the case: `DISPOSED` with `CONFIRMED_SUSPICIOUS`; `UNIQUE (case_id)` over an open draft; the artefact's fields — jurisdiction code, format version, the reporting entity's reference, the subject and account references, the transactions referenced, the grounds (the case's reason codes and the narrative, `RESTRICTED-PII`), the attachments' references; recorded as an investigator action (`report drafted`) | `409 risk.ReportNotFileable` (the case not so disposed; a live draft exists — read one way, §8), `risk.SelfDealingRefused` (audited `FAILED`) | — | `risk.ReportDrafted` |
| `DRAFT → APPROVED` ★ | P ≠ drafter, `…/approval`, `AML_REPORT_APPROVE`, `MULTI_FACTOR` | (R4) the report row conditional `DRAFT`; the four-eyes `CHECK` | `403 risk.SelfApprovalRefused` (audited `FAILED`) | — | `risk.ReportApproved` |
| `APPROVED → FILED` | P, `…/filing`, `AML_REPORT_FILE`, `MULTI_FACTOR` (`202`) | (R4) conditional `APPROVED`; **`FILED` committed before the call** with the provider's reference pending (the payout precedent, ADR-0046: no connection held across `RegulatoryFilingProvider`); the call after the commit; its reference recorded by a second transaction | `409 risk.ReportNotFileable` | `RegulatoryReportFiled` (report id, jurisdiction — **never** the subject) | `risk.ReportFiled` |
| `FILED → ACKNOWLEDGED \| REJECTED` | S, the filing port's inquiry leg (`RegulatoryFilingProvider` inquiry on the `FILED` row's permit; the simulated FIU endpoint, the `ScreeningAdapter` precedent) — an `UNKNOWN` filing is a permit on the `FILED` row, reconciled by inquiry, never re-filed | (R4) conditional `FILED` | — | — | `risk.ReportAcknowledged` / `risk.ReportRejected` (the system actor) |

**Immutable once `FILED`**: no column of a filed report is ever updated but its status and the authority's reference; a
`REJECTED` filing is a **new draft** from the same case (`UNIQUE (case_id)` admits it once the previous is terminal).
The narrative is readable only by `AML_REPORT_*` holders. **Invalid:** anything out of `ACKNOWLEDGED` or `REJECTED`;
`APPROVED → DRAFT`; `FILED → DRAFT | APPROVED`; a draft from a case not `DISPOSED` `CONFIRMED_SUSPICIOUS`; approval by
the drafter (`CHECK`); a filing without `AML_REPORT_FILE`; a second filing of one report; the subject in any event or
log; a customer or merchant surface that can tell a report exists.

*(As built by `P13-TSK-021`, 2026-10-25 - `risk V016` (the heading's `V011` went to `-014`), `RegulatoryReports`,
`RegulatoryReport`, `ReportContent`, `FilingOutcome`, `FilingInquiry`, `JdbcRegulatoryReportStore`; `app`'s
`SimulatedFilingProvider`, `RegulatoryFilingDriver`, `RiskReportDesk` / `RiskReportController`, the report leg of
`RiskActionSchedule`; `DESIGN-021`. **Drafted only from an `AML` case of a party** disposed `CONFIRMED_SUSPICIOUS` (D12 -
an `ATO` case's subject is the victim; a fraud suspicion to report is opened as an AML case), under the case row's lock
(R4, `Cases.lockForAct` - the case's own protocol, self-dealing fail closed), its content **drawn from the case** (D5):
the grounds the attached alerts' rule codes, the transactions the monitored transactions born from the journal entries
the case's alerts and evidence acts name and touching one of the party's accounts (the first 100; the rest counted
`unreferenced` in the audit summary), the accounts the party's (`MonitoredParties`), the jurisdiction's format and the
reporting entity's reference the adapter's declaration; the drafter writes the narrative, names the jurisdiction and the
attachments' references. **The case's act** (D2): an investigator action `REPORT_DRAFTED` - never a `-017` risk action
(no owning domain executes a report; `-017`'s D11 stands) - written in the draft's transaction, its ordinal past the
case's last revision (a disposed case never steps: no revision, no case history row), its id the report's;
`risk_case_action_report_drafted_coherent` and `_has_its_report` bind the two both ways, and the analyst's case file
withholds it (`CaseWorklist.read`). **One live or accepted report per case** (D3): `UNIQUE (case_id) WHERE status <>
'REJECTED'` - the heading's "open draft" widened, since a draft beside a `FILED` or `ACKNOWLEDGED` report would be a second
filing; read "a live draft exists" as "the case has a report that is not `REJECTED`". **Immutable by both shapes** (D4):
no content column in the application's `UPDATE` grant, and the trigger freezing the content and the drafter from birth
for every writer - so a draft is not edited either (no door edits one; a draft its second person will not approve has no
exit - owner item). The approval and the filing take the report row (R4′) alone, never the case. **The drive** (D6, D7) -
T-act's three steps: `FILED` commits the permit due at once with no attempt; the claim (`FOR UPDATE SKIP LOCKED` on a due
permit, one attempt, the permit a lease ahead) commits; the call on no connection as the platform - the first attempt
files under the **filing reference, the report's id** (the provider deduplicates on it), every later one inquires by it
and files under the same reference only on `NOT_RECEIVED` (nothing arrived - the filing still owed, never a second);
the record conditional on `FILED` and the claimed attempt: `ACKNOWLEDGED` (the authority's reference, `risk.ReportAcknowledged`
as `SYSTEM`) `| REJECTED` (its code, `risk.ReportRejected`) or `FILED` kept with the permit an inquiry interval ahead
(`finapp.risk.filing.inquiry-interval`, 15 min) and the authority's reference recorded once. The filing door drives after
its commit (`202`); the report leg of `RiskActionSchedule` re-drives. **A read door added** (D9): `GET
…/reports/{reportId}` under `AML_REPORT_APPROVE`, audited `risk.RegulatoryReportRead` before the answer - the second
person reads what they approve. Persons `CUSTOMER` or `EMPLOYEE` throughout (`regulatory_report_by_persons`); the history
`regulatory_report_event`, one row per status reached (`PRIMARY KEY (report_id, status)`). Counted by
`RegulatoryReportDatabaseTest` (written, owed - C5); proven hermetically by `RegulatoryReportTest`, `ReportContentTest`,
`RegulatoryReportMigrationTest`, `RiskReportDeskTest`, `SimulatedFilingProviderTest`, `RiskActionScheduleTest`.)*

### 4.9 Customer risk profile (`risk.customer_risk_profile`, `customer_risk_profile_version`) — `P13-TSK-007` (`risk V006`) — versioned ratings, no machine

The head row per party (`customer_risk_profile`, locked `FOR UPDATE` at (R8), carrying the current version and
`next_recompute_at`) and `INSERT`-only versions (`customer_risk_profile_version`, `UNIQUE (party_id, version)`):
rating `LOW | MEDIUM | HIGH | PROHIBITED`, the assessment or disposition that set it, the policy version, the stamp.
**Recompute** — S, T-mon's per-subject leg when a signal or a case outcome arrives for the party (the `MONITORING`
policy version's rule kinds fire over the party's signals and case history, and its **rating bands** map the resulting
signal score to `LOW`, `MEDIUM` or `HIGH` — there is no `PROFILE` rule kind), or T-case's disposition: (R8), then a new version only when
the rating moved (`ProfileDatabaseTest#tenRecomputesOneVersion`), `CustomerRiskRatingChanged` (party id, rating,
version). **`PROHIBITED` is set only by a four-eyes disposition** (§4.6); the sweep never raises a rating to it and
never lowers one from it. **Read** by every T-eval (the `PROFILE_RATING` rule) and by credit's seam: `app`'s
`RiskCreditRiskSignal` answers the current rating's code with **seam version 2** and the profile version, recorded in
the decision's snapshot — a decision made under version 1 replays with `NOT_ASSESSED`, one made under version 2 with
the rating it was given, `IDENTICAL` whatever the profile does after (`INV-RSK-10`, `INV-CRD-01`; mandatory scenario
13). The operator read: `GET /v1/operator/risk/parties/{partyId}/profile` (`RISK_INVESTIGATE`, `risk.ProfileRead`).
**Invalid:** an `UPDATE` of any version row; a version out of sequence; a `PROHIBITED` version from the sweep; a rating
edited by hand (there is no door); a rating read by a customer or merchant surface.
*(As built, `P13-TSK-007`, `risk V006`, 2026-10-23: each invalid edge is refused by the schema for every writer - the
versions `INSERT`-only by grant and trigger, numbered without a gap by trigger, `PROHIBITED` only with cause
`DISPOSITION` and its `disposition_id` (`CHECK`), no `RECOMPUTE` version after a `PROHIBITED` one (trigger), the head born
unassessed and moved one version at a time beside a version of the same rating (trigger). A version is written only when
the rating changes; no `MONITORING` version `ACTIVE` writes nothing (an unassessed party is `NOT_ASSESSED` at the seam);
a `PROHIBITED` head is never evaluated by the sweep. The recompute is `ProfileRecompute` - the pin (R1), then the head
(R8) claimed `SKIP LOCKED` by the sweep's leg or locked waiting by a direct call - and the hint is T-sig's
(`ProfileHints`), waiting on a held head rather than skipping it.)*

### 4.10 Screening list version and the rescreening permit (`risk.screening_list_version`, `rescreening_permit`) — `P13-TSK-022` (`risk V012`; cut candidate)

```
 (recorded) RECORDED ──activated (rescreening enqueued)──> ACTIVE
 per ACTIVE party:  (enqueued at activation) PENDING ──the sweep's rescreening leg requests through kyc's ports──> REQUESTED ──kyc's outcome recorded as a signal (a hit also an alert)──> DONE
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `RECORDED` | P, `POST /v1/operator/risk/screening-lists` (list, version), `RISK_POLICY_ADMINISTER`, key `risk.list:<actor>` (T-list) | `UNIQUE (list, version)` | `422 risk.ReasonRequired` | — | `risk.ListVersionRecorded` |
| `RECORDED → ACTIVE` | P, `…/{id}/activation` (`202`) | the list row conditional `RECORDED`; **one rescreening permit per `ACTIVE` party enqueued in the same transaction** (`UNIQUE (list_version_id, party_id)` — read one way, §8) | — | — | `risk.ListVersionActivated` |
| permit `PENDING → REQUESTED` | S, `RiskMonitoringSchedule`'s rescreening leg (T-mon's claim shape: `SKIP LOCKED`) — the screening requested through kyc's existing ports (`CounterpartyScreenings` for counterparties; the case-bound screening for parties); **kyc stays the screening owner** (Refinement 3, OQ-8) | the permit conditional `PENDING` | — | — | — |
| permit `REQUESTED → DONE` | S — kyc's outcome arrives (its event, through `risk.<component>`'s handler): the clearance recorded as a **Risk Signal** (`SCREENING_HIT`, or clear); a hit raises an **Alert** born once and folds into the party's case | the permit conditional `REQUESTED`; `UNIQUE (source_event_id)` | — | `RiskSignalRecorded`; `AlertRaised` on a hit | — |

Whether the predecessor list version is marked superseded, and the permit's states, are `-022`'s to confirm (§8).
**Invalid:** `ACTIVE → RECORDED`; a second activation; a permit for a party not `ACTIVE`; a screening performed by
`risk` (it asks; kyc screens); a Screening Result written by `risk`.

### 4.11 Monitored transaction and the watermark (`risk.monitored_transaction`, `monitoring_watermark`) — `P13-TSK-019` (`risk V004`) — born once; the watermark a claim, not a machine

**Monitored transaction** — born once per `ledger` journal entry (`UNIQUE (journal_entry_id)`), `INSERT`-only, never
updated: kind (classified by the entry's posting-key namespace — `transfer:`, `payments.`, `merchant-payout:`,
`crossborder.`, `lending.`, `bnpl.`… — through `MonitoredLedger`, the port `app` implements over the ledger's journal
entry store ordered by its sequence), subject refs, counterparties, amount and value date; an entry of a namespace the
register does not know is recorded **`UNCLASSIFIED`** and alerted — nothing passes unseen. The record never diverges
from the journal: a census proves assessments = judged commands and windows = monitored sums at every storm round
(`INV-RSK-09`).

**The watermark** — one row per source, `next_sweep_at` its permit: **T-mon** claims it `FOR UPDATE SKIP LOCKED` (R7)
and commits the claim alone; then reads the journal entries since it through the port, inserts the monitored
transactions (`ON CONFLICT DO NOTHING`), and advances the watermark — ten sweepers ingest once
(`MonitoringIntakeDatabaseTest#tenSweepersIngestOnce`); a crash mid-claim lets the permit lapse and the next claim
re-reads from the committed watermark (plan §14). Then, per subject: (R8) the profile head, the monitoring policy's
rules over the subject's monitored transactions and windows (`STRUCTURING`, `RAPID_IN_OUT`, `DORMANT_THEN_ACTIVE`,
`ROUND_AMOUNT`, `CORRIDOR_HIGH_RISK`, `VELOCITY_*`), alerts raised born once (§4.5) and folded into the subject's open
`AML` case (§4.6), the profile recomputed (§4.9). Events (`transfers`, `payments`, `crossborder` completions) are
**hints** that pull the permit forward; the sweep reads rows (OQ-3). **Reprocessing** — re-running from an earlier
watermark after a policy change or an incident — is ordered at `POST /v1/operator/risk/monitoring/reprocessing` by a `RISK_POLICY_ADMINISTER` holder, keyed
`risk.reprocess:<actor>`, `202`, audited `risk.ReprocessingOrdered`, and creates nothing new: born-once rows, alert and case uniques
(`MonitoringIntakeDatabaseTest#reprocessingCreatesNothingNew`; mandatory scenario 8). The gauges:
`finapp.risk.monitoring.lag` (journal head − watermark), `finapp.risk.monitoring.unclassified`. The monitoring sweep
never runs inside a money transaction (M3's split trigger, watched by the storm). **Invalid:** an `UPDATE` of a
monitored transaction; a second row per journal entry; a watermark advanced before its rows are committed; an entry
skipped for an unknown namespace; a monitored transaction born from an event rather than the journal.

*(As built by `P13-TSK-019`, 2026-10-23 — three readings the code fixes. **"Ordered by its sequence":** the journal has
no sequence column; its entry id is a UUIDv7 whose first 48 bits are the minting millisecond and whose primary key is
the journal's one ordered access path, so the intake reads by id as a keyset — `last_entry_id` the exact position,
`last_sequence` the id's millisecond derived by the database — and only entries minted before `clock_timestamp() −`
the settle horizon (`finapp.risk.monitoring.horizon`, PT60S), on the database clock, so an entry still in flight is
not overtaken by a later-minted one that committed first; an entry committed later than the horizon after its mint is
the design's recorded bound, caught by the census and repaired by reprocessing (`DESIGN-019` §0). **"Commits the claim
alone":** the claim holds no other row; the claim, the journal read (no lock) and the batch's inserts are ONE
transaction, so a partial batch never advances and a "crash mid-claim" is a rollback the next claim re-reads from.
**"Alerted":** the row is recorded `UNCLASSIFIED` and gauged (`finapp.risk.monitoring.unclassified`); the alert row
is `-015`'s (`AlertKind.UNCLASSIFIED_TRANSACTION`). A row born `UNCLASSIFIED` stays so — `INSERT`-only — when the
register later learns its namespace; the remedy is the register knowing every namespace on the day
(`PostingKeyNamespaceRegisterTest`), the owner's open question a later reclassification fact. The reprocessing door
refuses a point ahead of the watermark (`422 risk.ReprocessingNotBehind`) and restarts at the millisecond's floor.)*

*(As built by `P13-TSK-020`, 2026-10-25 — "then, per subject" is **inside** the claim: after the batch's inserts and
before the advance, every touched **party**'s head (its customer accounts placed through `MonitoredParties`) locked first,
ascending (R8), then each party under a savepoint - each touched movement judged over its value date's windows, the profile's own evaluation, every alert born
once and folded into the party's open `AML` case in one `raiseAll`, the profile's version; a party that throws is rolled
back alone and counted `finapp.risk.monitoring.subject{outcome=failed}`. With no `ACTIVE` `MONITORING` version nothing is
raised and no head enrolled. A reprocessing re-judges the same rows into the same keys - nothing new
(`MonitoringRulesDatabaseTest#reprocessingRaisesNoSecondAlert`, owed).)*

### 4.12 The consumers' machines, as Phase 13 changes them

**Transfers (`transfers.transfer`) — unchanged.** `TransferExecution` consults both seams after `lockBothInFixedOrder`
and the availability read, before the posting; `app`'s `RiskTransferLimitCheck` and `RiskTransferRiskDecision` run
T-eval there (`-008`). A refusal commits `FAILED(LIMIT_REFUSED)` / `FAILED(RISK_REFUSED)` as today (`FailureReason`,
`P4-TSK-010`'s reserved reasons); the door's T-advise answers an unsatisfied `CHALLENGE` `403
identity.AssuranceRequired` before the command. `PermitAllUntilPhase13`'s wiring in `app` is replaced; the modules
change nothing. *(As built, `P13-TSK-008`, 2026-10-23 — the machine is untouched; one assessment per transfer carries
both seams' answers (the limit seam's call seals, the risk seam's is answered from the seal); `LIMIT_REFUSED` exactly
when a fired amount or velocity rule's own hard decision is the refusing decision, `RISK_REFUSED` for every other
refusal, an unsatisfied `CHALLENGE` among them. A posting refused after the permit (`DESTINATION_NOT_POSTABLE`, the
savepoint after the seams) leaves the assessment `ALLOW`/`COUNTED` standing — scenario 7's transfer half: the decision
stands, the domain's outcome is recorded, risk retries nothing.)*

**Cross-border (`crossborder.payment`) — unchanged.** `CrossBorderAuthorization` consults the seams in its first
transaction before the offer is accepted; a refusal consumes nothing (`422 crossborder.RiskRefused` /
`LimitRefused`); `app`'s `RiskCrossBorderLimitCheck` and `RiskCrossBorderRiskDecision` (`-011`).

**Withdrawals (`payments.withdrawal`) — payments' own machine, changed by `-009` with its own migration (payments
`V033`).** Today (`RAIL_AND_DISPUTE_LIFECYCLES.md` §5): born `DISPATCHED` with the wallet hold placed in its lock;
`DISPATCHED → COMPLETED | FAILED | UNKNOWN`, `UNKNOWN → COMPLETED | FAILED`. Phase 13 adds **`HELD`** before
`DISPATCHED`, the `held_until` column, and **`WithdrawalFailureReason.RISK_REFUSED`** with its `HOLD_EXPIRED` reason detail:

```
 (the hold judged under the wallet account's lock; the new WithdrawalRiskDecision consulted there, in-lock, after the hold is placed and before the dispatch is committed)
     ALLOW, or a satisfied CHALLENGE ──> DISPATCHED          (as today)
     HOLD ──> HELD                                           (the ledger hold KEPT; the customer reads `HELD`, a status word, no reason — C6)
     DENY ──> FAILED(RISK_REFUSED)                           (the hold released in the same transaction)

     HELD ──RELEASE_HELD_WITHDRAWAL (an approved override's or a disposition's risk action, T-act, key risk.action:<id>)──> DISPATCHED
     HELD ──DENY_HELD_WITHDRAWAL (a disposition's risk action)──────────────────────────────────────────────────────────> FAILED(RISK_REFUSED)   (the hold released)
     HELD ──held_until reached (held at + the policy version's hold validity, default 72 h; payments' WithdrawalResolutionSchedule leg)──> FAILED(RISK_REFUSED, HOLD_EXPIRED)   (the hold released; the case stays open)
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `HELD` | S, the withdrawal's own transaction, `HOLD` read from T-eval | the wallet account's lock (`INV-BAL-04`); the hold kept; `held_until` stamped = held at + the pinned policy version's hold validity (default 72 h); a case opened in the same transaction (R4) | — | payments' own (the withdrawal's birth with `HELD`) and `RiskDecisionMade` | payments' own |
| (birth) → `FAILED(RISK_REFUSED)` | S, the same transaction, `DENY` | the wallet lock; the hold released | the operation's generic answer | payments' own; `RiskDecisionMade` | payments' own |
| `HELD → DISPATCHED` | S — the approved override's release in T-act's shape from the approval door, keyed `risk.action:<override id>` (`-012`), or T-act dispatching `RELEASE_HELD_WITHDRAWAL` (`-017`) *(as built by `-017`: both are T-act - the override's action folded into `risk_action`, every dispatch as the platform)* — through payments' existing door (keyed, audited by payments) | the withdrawal row lock **in payments' order**; conditional `HELD` with `held_until` not reached — release and expiry leave exactly one, the first committed edge winning (`WithdrawalHoldDatabaseTest#releaseAndExpiryLeaveExactlyOne`); then the dispatch as today (`DISPATCHED` with the send permit, the send after the commit) | the domain's refusal (already moved or expired) is the action's `DONE(ALREADY)` | payments' own | payments' own; `risk.ActionConcluded` |
| `HELD → FAILED(RISK_REFUSED)` | S — T-act dispatching `DENY_HELD_WITHDRAWAL` (an approved disposition's denial); or **the expiry**: a new leg of payments' existing `WithdrawalResolutionSchedule` (no new scheduler) finding `held_until` reached on the database clock, reason detail `HOLD_EXPIRED` (`-009`) | the row lock in payments' order; conditional `HELD`; the hold released once; the case stays open and its age alerts | — | payments' own | payments' own |

A customer whose withdrawal is `HELD` cannot close the wallet while the hold is `ACTIVE` (accounts' existing rule,
plan §14). `held_until` is payments' column (`V033`), stamped at the hold from the decision's pinned policy version. `WithdrawalStatus.permittedTransitions()` and `V016`'s every-writer trigger gain the edges (`V033`);
`PaymentsMigrationTest` reconciles. **Invalid:** `HELD → COMPLETED | UNKNOWN` (nothing was sent); `DISPATCHED → HELD`;
a release by any door but payments' own; a `HELD` row whose hold was released; `HELD` reaching the customer with a
reason.

*(As built, `P13-TSK-009`, 2026-10-24 — payments `V033`, as drawn with these readings. **No `SUBMITTED`**: payments'
machine has no request state (`P7-TSK-008`), so the withdrawal is **born** in the state its in-lock verdict decides -
`DISPATCHED`, `HELD` or `FAILED(RISK_REFUSED)` - and the every-writer birth trigger (`withdrawal_is_born_judged`, in
place of `withdrawal_is_born_dispatched`) admits exactly those, an instructed (SYSTEM) row only `DISPATCHED`: the
instruction is **not judged** (it carries out a credit decision; owner item). **`held_at`** is the birth permit's own
instant and **`held_until`** `held_at` + the validity the seam answered with its `HOLD` (`Assessed.holdValidity`, the
pinned version's), both stamped by the database in the insert. `HELD` is not terminal and **not resolvable** (no
inquiry, no permit renewal). **The release** is `Withdrawals.releaseHeld(key, id, reasonCode)`: the claim
`payments.withdrawal.release`, the row `FOR UPDATE`, the conditional edge **within `held_until` on the database clock**
(past it: `REFUSED`, nothing written - the expiry is the policy's outcome), the first real send permit and the key on the
row (`held_resolution_key`), then the wire and the platform's answer (Tx2); a re-driven key re-sends behind a renewed
permit. **The denial** is `Withdrawals.denyHeld(key, id, reasonCode)`, one transaction, the key recorded. **The
expiry** is `WithdrawalResolution.expireHolds`: one `HELD` row past `held_until` per transaction, claimed `FOR UPDATE
SKIP LOCKED`, failed with `failure_detail` `HOLD_EXPIRED`, its hold released, as the platform. The doors answer
`DONE` / `ALREADY` (no longer `HELD`) / `REFUSED` (no such withdrawal, or a lapsed release). A `HOLD` is **not counted**
in the windows (T-eval counts what the decision permits). No case is opened yet: `CaseOpener` is `NONE` until `-016`.
The machine as built: `DISPATCHED → COMPLETED | FAILED | UNKNOWN`, `UNKNOWN → COMPLETED | FAILED`, `HELD → DISPATCHED |
FAILED`; `RISK_REFUSED` exactly out of `HELD` (or at birth), the scheme's reasons exactly after a dispatch; the held
window frozen; the key and the detail arriving only with the edge out of `HELD`.)*

**Checkout payment confirmation (`payments.payment_intent`) — payments' own, changed by `-010` (payments `V034`).**
`PaymentConfirmation.confirm` decides routing in Tx1 under the intent; the new `PaymentRiskDecision` (`app`'s
`RiskPaymentRiskDecision`) is consulted in Tx1 after the eligibility checks and before the routing decision commits,
for `CARD_PAYMENT` and `WALLET_PAYMENT`. `DENY` is **terminal for the intent** (plan §7.3): not re-confirmable — the
customer creates a new intent, where routing's refusal today leaves the intent re-confirmable (`payments.NoEligibleRail`,
ADR-0060 §3) and a risk refusal must not — its decision row sealed in Tx1 with `PaymentFailureReason.RISK_REFUSED`
(payments `V034`); whether that is a `FAILED(RISK_REFUSED)` attempt row written before any dispatch or an intent-level
refused state is `-010`'s to decide, and the customer sees the intent's generic failure; the book rail's wallet pair
lock is unchanged. `HOLD` (slice 2, cuttable) authorises and defers the capture: the intent `PROCESSING` with an
`AUTHORIZED` attempt whose capture waits on an approved override or disposition (the `MANUAL` capture-mode shape,
ADR-0059) — `-010` confirms. `ALLOW` and a satisfied `CHALLENGE` route as today. **Invalid:** a payment moved by
`risk`; a capture deferred without the attempt's own machine recording it; a risk-refused intent re-confirmed.

*(As built, `P13-TSK-010`, 2026-10-24 — payments `V034` and risk `V008`, both slices. **The refusal is intent-level**,
not an attempt row: `REQUIRES_CONFIRMATION → FAILED` carrying the intent's own `RISK_REFUSED` (`payment_intent.failure_reason`,
the one reason an intent carries - no attempt exists to carry it), committed in Tx1 under the intent row's lock with the
sealed assessment, the history row, `payments.PaymentRiskRefused` (the person's act, `FAILED`) and `payments.PaymentFailed`;
no attempt (a trigger refuses one for a refused intent, every writer), no routing decision, nothing sent - so no
provider-facing record claims a try that never happened, and routing's own refusal stays the re-confirmable one. `FAILED`
is terminal: a re-confirmation is the machine's `409 payments.NotConfirmable` and is never judged again. **The seam's
place:** Tx1 locks the intent first (`findOwnedForUpdate` - concurrent confirmations serialised before any judgement),
then the instrument, the credit account `FOR SHARE` (card/bank), the routing plan (a plan with no rail commits routing's
refusal unjudged), the book rail's pair `FOR UPDATE` for the wallet, and only then T-eval - risk's rows last (the
backlog's "T-eval before the pair lock" refused: R2a held to commit ahead of the wallet row would cycle with a transfer
of the same party). **The mapping** (`RiskPaymentRiskDecision`): `ALLOW` (fail-safes included; C1 below EUR 250) and a
satisfied `CHALLENGE` → `PERMIT`; a card `HOLD` → `HOLD(validity)`, not counted; `DENY` (C1 at or above EUR 250 among
them) and an unsatisfied `CHALLENGE` → `REFUSE`; a top-up and a pay-by-bank checkout `PERMIT` unjudged. **Slice 2:** the
held card payment is `PROCESSING` with an `AUTHORIZED` attempt and an open `payment_capture_hold` (no new intent
status - `AUTHORISED_HELD` named and refused, every reader of the intent would have learned a word C6 forbids them), its
`held_until` = `held_at` + the pinned `CARD_PAYMENT` version's hold validity on the database clock; the attempt's capture
is refused while the hold is open, voided or expired (the conditional and `payment_attempt_capture_waits_for_its_hold`),
so neither the chain nor the stranded-chain leg captures it. It ends exactly once: `HeldPayments.captureHeld` (keyed,
within `held_until`, then `PaymentCapture`), `voidHeld` (keyed, then `PaymentVoid`), or the deadline -
`HeldPayments.expireHolds` on `PaymentSweeperSchedule`, the hold claimed `FOR UPDATE SKIP LOCKED` and resolved `EXPIRED`,
its authorisation then voided by the leg's finisher (which also voids a door's `VOID` a crash left undispatched); a
capture racing the deadline leaves one resolution and the loser `ALREADY` (or `REFUSED` past `held_until`). The doors are
reached through risk's new `HeldPaymentActions`, implemented in `app` and called by nothing until `-012`/`-017`; the case
`CaseOpener` would open is `NONE` until `-016`.)*

**Credit (`credit.CreditRiskSignal`) — no machine change.** `SnapshotFreezer` records the signal in the snapshot;
`app`'s `RiskCreditRiskSignal` replaces `NotAssessedUntilPhase13` with **seam version 2** (§4.9); nothing of credit's
rows changes and every earlier decision replays as it was sealed.

---

## 5. The ranks, per machine (planned)

| Machine | Table (task) | History | Database rank beyond the edge trigger |
|---|---|---|---|
| Policy version | `risk_policy_version`, `risk_rule` (`-002`, `V002`) | its event table | one `ACTIVE` per operation (partial unique); four-eyes `CHECK`; content frozen from insert; retirement only beside a successor (deferred); rules `INSERT`-only; namespace `13` |
| Assessment | `risk_assessment` + `_input`, `risk_rule_hit`, `risk_reason` (`-004`, `V007`) | — (`INSERT`-only) | `UNIQUE (operation, command_ref)`; no `UPDATE` grant; the decision in the operation's admitted set; reasons ordered |
| Override | `decision_override` (`-004`'s `V007` table, `-012`) | the row's decided columns | four-eyes `CHECK` above the threshold; `UNIQUE (assessment_id)` over a live proposal; the decision not `ALLOW` |
| Signal | `risk_signal` (`-005`, `V003`) | — | `UNIQUE (source_event_id)`; attributes typed and bounded |
| Velocity window | `velocity_window` (`-006`, `V005`) | — | primary key (dimension, subject_ref, window_kind, window_start); the one-statement upsert the only `UPDATE`; a sum never decreases |
| Profile | `customer_risk_profile`, `_version` (`-007`, `V006`) | the versions | `UNIQUE (party_id, version)`; `PROHIBITED` only beside an approved disposition (deferred) |
| Alert | `risk_alert` (`-015`, `V008` - *built as `V010`, the next free*) | — | `UNIQUE (subject_ref, rule_code, window_start)`; `ATTACHED` only naming a case; never deleted *(built: the window aligned by `CHECK`, four eyes and persons by `CHECK`, the facts frozen and `DELETE`/`TRUNCATE` refused for every writer by `risk_alert_permits_only_machine_edges`)* |
| Case | `risk_case`, `risk_case_action`, `risk_case_event` (`-016`, `V009`) | `risk_case_event`; actions append-only | partial unique one open per (subject, kind); `revision` monotonic; four-eyes `CHECK` on `CONFIRMED_*`; no action on `DISPOSED` |
| Risk action | `risk_action` (`-017`, `V010` - *built as `V013`, the next free*) | the row's stamps | `UNIQUE (case_id, kind, target_ref)` over live; four-eyes `CHECK`; the `next_attempt_at` permit *(built: `risk_action_one_live`, `risk_action_four_eyes` for a case's action, an override's action one per override and its id the override's, the kind's target and availability by `CHECK` and trigger, `DELETE`/`TRUNCATE` refused for every writer by `risk_action_permits_only_machine_edges`; a disposition refused beside a `PROPOSED` action by `risk_case_disposes_no_standing_action`)* |
| Monitored transaction · watermark | `monitored_transaction`, `monitoring_watermark` (`-019`, `V004`) | — | `UNIQUE (journal_entry_id)`; one watermark per source; `next_sweep_at` |
| Regulatory report | `regulatory_report`, `_event` (`-021`, `V011` - *built as `V016`, the next free*) | `regulatory_report_event` | `UNIQUE (case_id)` over an open draft; four-eyes `CHECK`; `FILED` columns frozen (trigger) *(built: `regulatory_report_one_live_per_case` over every status but `REJECTED`; `regulatory_report_four_eyes` and the persons `CHECK`; the content frozen from birth by the trigger and in no `UPDATE` grant; one history row per status, `PRIMARY KEY (report_id, status)`, deferred; the `REPORT_DRAFTED` act bound both ways)* |
| List version · permit | `screening_list_version`, `rescreening_permit` (`-022`, `V012`) | — | `UNIQUE (list, version)`; one permit per (list version, party) |
| Withdrawal (payments') | `withdrawal` (payments `V033`, `-009`) | payments' | `HELD` in the status `CHECK` and the edge trigger; `RISK_REFUSED` in the reason list, with the `HOLD_EXPIRED` detail; `held_until` stamped at the hold *(built: the judged births by trigger, `held_at`/`held_until` frozen and stamped by the database, `held_resolution_key`, the expiry's partial index `withdrawal_hold_expiry`)* |
| Payment intent (payments') | the refused-by-risk decision row (payments `V034`, `-010`) | payments' | `RISK_REFUSED` in `PaymentFailureReason`'s list; the refusal terminal for the intent — no edge back to `REQUIRES_CONFIRMATION` |

Every trigger is `<table>_permits_only_machine_edges`, written by hand; every status `CHECK` a membership list; every
migration test asserts constraint-name uniqueness per table (the Phase 12 `V012` lesson, plan §13.1); raw-SQL terminal
edges are refused for every role in each task's database tests. Migration numbers are assigned in the waves' landing
order, Flyway `outOfOrder` off (plan §8, §17): `-001` `V001`, `-002` `V002`, `-005` `V003`, `-019` `V004`, `-006` `V005`,
`-007` `V006`, `-004` `V007`, `-015` `V008`, `-016` `V009`, `-017` `V010`, `-021` `V011`, `-022` `V012`. *(As
landed: `-010` `V008`, `-012` `V009`, `-015` `V010` - each the next free at its build; the later rows take theirs.
Then `-014` `V011`, `-016` `V012`, `-017` `V013`.)*

---

## 6. Settled points

The owner's transition decisions (2026-10-22, `PHASE_13_PLAN.md` §2.2 and §24), as they bear on the machines:

| # | Decision | Where it lands |
|---|---|---|
| C1 | Fail-safe per operation and value band: transfers below EUR 1,000 `ALLOW` recorded, at or above `DENY`; withdrawals and cross-border `DENY` at every band; checkout payments below EUR 250 `ALLOW` recorded, at or above `DENY` — a field of the policy version | §4.1 (the fail-safe bands); §4.2 (the recorded fail-safe) |
| C2 | The vocabulary `ALLOW`, `CHALLENGE`, `DENY`, `HOLD`, `REVIEW`; `HOLD` only where the operation has a native hold-then-dispatch shape | §4.2; §4.12 |
| C3 | Collections: a `COLLECTIONS` case opened on `LoanDefaulted` / `BnplDefaulted`, facts only; cut candidate | §4.6 (`-025`; built 2026-10-26, `risk V017` - §4.5's fact alert, §4.6's kind rules) |
| C4 | The neutral EUR reference; the EU shape behind a simulated filing adapter | §4.8 |
| C5 | Criterion 7: fleet tiers skipped per task; database suites written and owed; one owner-authorised database pass before the exit review | none in the machines — §5's ranks are proven by each task's own suites |
| C6 | The customer sees the operation's generic answer; `HELD` reads `HELD` with no reason | §4.12; §3's actors |
| C7 | The override threshold: a second holder at or above EUR 1,000 or for a `DENY` | §4.2a |
| C8 | Policy v1 fixtures test-only; production activates none | §4.1 |
| C9 | `rule` a metric tag key; no `decision_id`-shaped key | none in the machines (`finapp.risk.rule.hit{operation,rule}`) |
| C10 | A confirmed ATO case may propose `REVOKE_SESSIONS` and `SUSPEND_IDENTITY`, through identity's existing commands under four eyes; never a credential reset | §4.7 |
| OQ | OQ-1 a refused movement counts for nothing (§4.3) · OQ-2 `REVIEW` opens the case in the money transaction (§4.2, §4.6) · OQ-3 the journal, not events (§4.11) · OQ-4 a device hash only (§4.4) · OQ-5 an `ALLOW` is never overridden (§4.2a) · OQ-6 a case never re-opens (§4.6) · OQ-7 the report is not the case (§4.8) · OQ-8 risk asks, kyc screens (§4.10) · OQ-9 a merchant sees only the generic refusal (§4.12) · OQ-10 collections a thin slice (§4.6) | |

The fifteen mandatory scenarios (`PHASE_13_PLAN.md` §13.2): 1 §4.4–§4.6 · 2 §4.5 · 3 §4.1 · 4 §4.7 · 5 §4.6 · 6 §4.6 ·
7 §4.7 · 8 §4.11 · 9 §4.2 · 10 §4.2a, §4.12 · 11 §4.5–§4.7, §4.12 · 12 §4.5, §4.11 · 13 §4.9 · 14 §4.3 · 15 §4.1,
§4.2a, §4.6.

---

## 7. Cross-cutting rules the machines inherit

- **Every stamp from the database clock after the lock**; no instance clock decides a window, a deadline or an
  effective date (the Phase 9 → 10 transition's rule; the Phase 12 → 13 gate's A3-M1).
- **Every event through the outbox in its state change's transaction**, producer `risk`, event version 1, additive
  evolution; ids, codes and statuses only — no reason text, score, rule name, note or PII (`INV-RSK-08`, `INV-AUD-02`).
  `TransactionBlocked` is not a risk event: the blocked movement's owner announces its outcome.
- **Every door `/v1`, closed bodies, the error contract, `Idempotency-Key` on every keyed command, owner-scoped**; the
  uniform `404 risk.NotFound`.
- **Data classification**: assessment inputs and rule hits `CONFIDENTIAL`; signal attributes at their source's level
  (`device` `RESTRICTED-PII`); case notes and evidence refs `CONFIDENTIAL`, screened; the report narrative
  `RESTRICTED-PII`; nothing `INTERNAL` but identifiers and timestamps (`ColumnClassificationTest`).
- **Architecture guards**: `RiskModuleIsolationTest` (`risk → platform, sharedkernel` only; every business module and
  `app` refused; every sibling refuses `risk`), `NoCustomerSurfaceReadsRiskTest`, `NoSingleInstanceAssumptionRulesTest`,
  `RoutePermissionRegisterTest`, `NoFloatingPointMoneyRulesTest` over `risk`.

---

## 8. Read one way here, for the building task to confirm

Points where the plan was silent, or said two things, and this document had to choose. *Reconciled by the transition's
consistency pass (2026-10-22), after this section was written — the plan now says, and the points marked **settled**
stand settled rather than open; the few left stand as this document's reading until their task's design settles
them.*

- **The payment intent's status on a risk `DENY`** (`-010`) — **settled** (plan §7.3): a risk `DENY` is terminal for
  the intent — not re-confirmable, the customer creates a new intent — where routing's refusal today leaves the intent
  `REQUIRES_CONFIRMATION` (`payments.NoEligibleRail`, retryable by design) and a risk refusal must not; the decision row
  is sealed in Tx1; `-010` decides whether that is a `FAILED(RISK_REFUSED)` attempt row written before any dispatch or
  an intent-level refused state (`PaymentFailureReason.RISK_REFUSED`, payments `V034`), and the customer sees the
  intent's generic failure (§4.12).
- **A held withdrawal's expiry** (`-009`, `-012`) — **settled** (plan §5, §12.2, §14): `held_until` = held at + the
  policy version's hold validity (a field of the policy version, default 72 h), judged on the database clock under the
  withdrawal row lock by a new leg of payments' existing `WithdrawalResolutionSchedule` (no new scheduler); on expiry the
  withdrawal commits `FAILED(RISK_REFUSED)` with reason detail `HOLD_EXPIRED` and the hold is released, once; the case
  stays open; the race is `WithdrawalHoldDatabaseTest#releaseAndExpiryLeaveExactlyOne` (§4.12).
- **The override's issued action and its case** (`-012`, `-017`) — **settled** (plan §17): `-012` lists no migration
  (`V007`'s table); the release it issues runs in T-act's shape from the approval door, keyed
  `risk.action:<override id>`, through payments' existing door, until `-017` folds it into the action machine as
  `RELEASE_HELD_WITHDRAWAL`; `-017` no longer depends on `-012`. A denied transfer's "one-time allowance for the same
  command ref" is an assessment input, not an action (§4.2a, §4.7). *(Built by `P13-TSK-012`, 2026-10-24, as settled -
  the release, and the denial, capture and void beside it, in T-act's shape from the approval door with the re-drive leg
  on `RiskMonitoringSchedule`; **the allowance is not built** - for neither transfers nor cross-border - and an approved
  override of a refusal records `RESUBMISSION`, an owner item (§4.2a's as-built note). Folded by `P13-TSK-017`,
  2026-10-25, as settled: the issued action is a `risk_action` row under the override's id - its key kept - driven by
  `RiskActionSchedule`, the leg on `RiskMonitoringSchedule` gone (§4.2a's fold note).)*
- **Operational alerts versus alert rows** (`-004`, `-006`, `-019`) — **settled** (plan §15's alert table):
  `RiskEvaluationUnavailable` and `RiskPolicyNoneActive` are `infra/prometheus/rules/risk.yml` alerts on
  `finapp.risk.evaluation.unavailable`, and the velocity proof's difference and an `UNCLASSIFIED` entry alert on their
  gauges — none a row of §4.5's machine, which a policy's rules alone populate (`-024` names the rest).
- **Where T-eval's alert goes, and the dedupe** (`-015`, `-016`) — **settled** (plan §5): an alert raised while its
  subject has an open case of the kind attaches to it — the system's dedupe is `ATTACHED`, never `SUPPRESSED`; T-eval's
  `REVIEW`/`HOLD` alert attaches to the case it opens (the assessment its first evidence); `SUPPRESSED` is only an
  operator's four-eyes act at `POST /v1/operator/risk/alerts/{id}/suppression` (proposed by `RISK_INVESTIGATE`, approved
  by `RISK_CASE_APPROVE` at `…/suppression/approval`, audited `risk.AlertSuppressed`, `-018`). The alert table, raise,
  dedupe and suppression are `-015`'s; the folding into cases is `-016`'s (§4.5, §4.6).
- **Disposition from `OPEN`** (`-016`) — **settled** (plan §5): a `CLOSED_FALSE_POSITIVE` / `CLOSED_NO_ACTION`
  disposition is admitted straight from `OPEN`, one person, reasoned; a `CONFIRMED_*` or `REFERRED` requires
  `IN_INVESTIGATION` or `ESCALATED` (§4.6).
- **The monitoring policy as an operation** (`-002`, `-007`, `-020`) — **settled** (plan §12.1, §12.2, §12.5):
  `MONITORING` is the seventh `RiskOperation` — the sweep's policy, no seam — whose policy version carries **rating
  bands** mapping the party's signal score to `LOW`, `MEDIUM` or `HIGH` (`PROHIBITED` only by a four-eyes disposition);
  there is no `PROFILE` rule kind (§4.1, §4.9).
- **The reprocessing door and its audit action** (`-019`) — **settled** (plan §9, §12.7):
  `POST /v1/operator/risk/monitoring/reprocessing`, `RISK_POLICY_ADMINISTER`, keyed `risk.reprocess:<actor>`, `202`,
  audited `risk.ReprocessingOrdered` (§4.11).
- **The acknowledgement's record and the `UNKNOWN` filing** (`-021`) — **settled** (plan §5): the acknowledgement is
  recorded by the filing port's inquiry leg (`RegulatoryFilingProvider` inquiry, the system actor) as
  `FILED → ACKNOWLEDGED | REJECTED`, audited `risk.ReportAcknowledged` / `risk.ReportRejected`; an `UNKNOWN` filing is a
  permit on the `FILED` row, never re-filed (§4.8).
- **Migration numbers** — **settled** (plan §8, §17): assigned in the waves' landing order, Flyway `outOfOrder` off —
  `-001` `V001`, `-002` `V002`, `-005` `V003`, `-019` `V004`, `-006` `V005`, `-007` `V006`, `-004` `V007` (the
  assessment tables and `decision_override`), `-015` `V008`, `-016` `V009`, `-017` `V010`, `-021` `V011`, `-022` `V012`
  (the headings of §4 and the table of §5). *(As landed: `-021`'s is `V016` - the next free number at its build, after
  `-014` `V011`, `-016` `V012`, `-017` `V013`, `-018` `V014`, `-020` `V015`.)*
- **One `PROPOSED` per operation** (`-002`) — **settled by the build** (`P13-TSK-002`, 2026-10-23): several `PROPOSED`
  per operation, numbered without a gap under the advisory lock (the backlog entry's "ten proposals leave ten
  versions"); activation monotonic in version number, a proposal below the version in force superseded; the loser of a
  concurrent decision and a superseded proposal both `409 risk.PolicyStale`; `risk.PolicyNotActive` `409` on the
  in-force read with none active (§4.1's as-built note). *(Read "one `PROPOSED` per operation (the credit and BNPL
  shape)" until then.)*
- **"One watermark per source"** (`-019`) — still this document's reading: with one journal there is one source — the
  `MonitoredLedger` port's sequence, one row; a second source arrives only with a second journal.
- **Live-twin refusals** (`-012`, `-017`, `-021`) — still this document's reading: `409 risk.ActionNotProposable` for
  a second live override and for a second live action of one (case, kind, target), `409 risk.ReportNotFileable` for a
  second open report draft — the tasks assign them. *(The override's half settled by `P13-TSK-012`, 2026-10-24: `409
  risk.ActionNotProposable` for a live proposal or an approved override of the assessment, an `ALLOW`, a `CHALLENGE` and a
  `REFUSE` of a refusal; `409 risk.OverrideStale` - new - for a decided override. The action's half settled by
  `P13-TSK-017`, 2026-10-25: `409 risk.ActionNotProposable` for a live twin of (case, kind, target) -
  `risk_action_one_live` - as for a kind or target the case does not admit; a decided action `409 risk.CaseActNotAdmitted`.)*
  *(The report's half settled by `P13-TSK-021`, 2026-10-25: `409 risk.ReportNotFileable` for a second report of a case
  while one is `DRAFT`, `APPROVED`, `FILED` or `ACKNOWLEDGED` - `regulatory_report_one_live_per_case`, wider than "a
  second open draft" so that no case is filed twice - and for every other state conflict of a report (a case not so
  disposed, a report not awaiting the act); a draft not taken as asked `422 risk.ReportRequestInvalid` - new.)*
- **The rescreening permit's states and the superseded list** (`-022`) — still this document's reading: the permit
  `PENDING → REQUESTED → DONE` keyed (list version, party); the predecessor list version keeps `ACTIVE` until `-022`
  decides whether a `SUPERSEDED` state is wanted.
- **The effective-from arithmetic** (`-002`) — **confirmed by the build** (`P13-TSK-002`, 2026-10-23): credit `V014`'s
  rule in `risk V002`'s edge trigger — the retirement's end `GREATEST(clock_timestamp(), transaction_timestamp(),
  effective_from + 1 µs)`, the successor's start `GREATEST(transaction_timestamp(), max(effective_to))` — so a backward
  database clock neither refuses an activation nor overlaps two periods (`RiskPolicyMigrationTest#theDecisionIsStampedAfterTheLock`).
