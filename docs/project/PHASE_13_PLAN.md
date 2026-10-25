# Phase 13 — Risk, Fraud and AML

*Written by the Phase 12 → 13 transition (2026-10-22), as documents only: nothing in this plan is implemented until
`P13-TSK-001` lands. Decided in ADR-0107…ADR-0112 (`Proposed`); the machines in
[`RISK_LIFECYCLES.md`](../domain/RISK_LIFECYCLES.md); the invariants `INV-RSK-01`…`-10`; the exit criteria in
`PHASE_GATES.md` §5 Phase 13, extended by this transition; the backlog in [`BACKLOG.md`](BACKLOG.md) §Phase 13. Where
this plan and an earlier document disagree, this plan is right and the earlier document carries a dated note saying so.
Where this plan and the code the tasks build disagree, the task's record is right and this plan gains an erratum.*

**Status of this document:** the decided design, drafted from `DELIVERY_PLAN.md` §Phase 13, `ROADMAP.md` Refinements
2 and 3, `MODULE_ARCHITECTURE.md` M3 and M6, the seams Phases 4, 9 and 10 reserved for this phase, and the code as it
stands at master `e314387f`. Owner decisions C1–C10 (§2.2) are recorded as the owner's accepted defaults pending
ratification; the backlog's first task does not depend on any of them being revisited.

---

## 1. Objective

Make every money movement and every account **judged** before it happens and **watched** after it happens, by a
platform capability that owns the judgement and none of the money.

Phase 13 implements the seams the platform has carried since Phase 4 — `TransferLimitCheck` and `TransferRiskDecision`
(`P4-TSK-010`), `CrossBorderLimitCheck` and `CrossBorderRiskDecision` (`P9-TSK-016`), and credit's `CreditRiskSignal`
(`P10-TSK-008`) — and adds the two the money-moving commands never had: a risk seam on the wallet withdrawal and on
the checkout payment confirmation. Behind every seam stands one channel-neutral capability, `risk`: versioned policies
of rules as data, a deterministic scoring engine, durable velocity windows written in the caller's own transaction,
an immutable assessment per judged command, an explicit decision with reason codes, a fail-safe answer that is a
recorded decision and never a silent default, manual override under four eyes, and a case for every judgement a
person must make. After the money moves, the same capability monitors it: a leaderless sweep over the ledger's own
journal — the one financial truth — classifies every movement into a monitored transaction, evaluates the monitoring
rules per subject over durable aggregates, raises alerts born once, folds them into cases, and keeps the distinction
between an internal investigation and a regulatory filing physical.

**Phase 13 moves no money.** It never posts, never holds, never releases a hold of its own and never writes another
module's row. A decision changes a money movement only because the owning domain consulted the seam in its own
transaction and committed its own outcome; an action — a suspension, a freeze, a release — is a keyed command
issued through the owning domain's existing door, recorded on both sides.

### 1.1 The production limitation, recorded up front

Production evaluates nothing until a risk policy version is activated under four eyes and `OPERATIONS_RUNBOOK.md`
§9's activation gate is passed. Until then every seam answers `ALLOW` with the reason code `NO_POLICY_ACTIVE`,
**recorded as a decision** (the control ran; it had no policy), so the first activation changes behaviour and not
the shape of any record. The monitoring sweep runs from the first deployment and raises no alert until a monitoring
policy is active. The regulatory filing adapter is simulated: the platform's reference jurisdiction is still the
neutral EUR one (`DECISIONS.md` §Deferred), and §12.9 records what was researched for the EU and what must be verified
when a real jurisdiction is chosen.

---

## 2. Why this phase is shaped by decisions already taken

### 2.1 Inherited, binding

| Decision | Where | Consequence here |
|---|---|---|
| The seams are required parameters with no defaulted overload; a skipped control must not compile | `TransferExecution`, `CrossBorderAuthorization` | Phase 13 replaces the wiring of `PermitAllUntilPhase13` in `app`; the modules change nothing |
| A seam's verdict is two values, no reason; the mapping to the committed reason is the caller's | `SeamVerdict`, `CrossBorderVerdict` | The richer decision lives in `risk`'s assessment; the seam adapter maps it (§12.3). The Phase 4 contract is unchanged |
| An authoritative check is anchored to durable state read through the caller's unit of work, in-lock; never a cache, never process memory | `TransferLimitCheck` javadoc, `INV-CON-03`, ADR-0024 | Velocity windows are rows upserted in the caller's transaction (§12.4); there is no Redis counter and nothing to lose |
| The risk score is `risk`'s; credit consumes a signal through `CreditRiskSignal` and never computes one | ADR-0084 §5, `GLOSSARY.md` §10 | `-007` implements the seam over the customer risk profile, with a new seam version; credit's snapshots replay identically (`INV-CRD-01`) |
| Fraud and AML share the `risk` module; Case Management is in it; onboarding screening is `kyc`'s, ongoing monitoring and rescreening `risk`'s | `MODULE_ARCHITECTURE.md` M3, M6; `ROADMAP.md` Refinement 3 | One module, three capabilities kept distinct by timing and obligation (§3.2); rescreening calls kyc's ports |
| Risk evaluation must fail safe, explicitly, per operation and value band | `DELIVERY_PLAN.md` §Phase 13 §5; unresolved question #11 | ADR-0108 (§12.3) |
| Rules and thresholds are versioned artefacts; decisions record the version | `DELIVERY_PLAN.md` §Phase 13 §5 | ADR-0109 (§12.2) |
| Kafka and caches are not financial truth; events are hints, sweeps read rows | ADR-0024; the lending and BNPL schedules | Monitoring intake reads the ledger journal through a port; events only pull a permit forward (§12.7) |
| Time decided across instances is the database's, read after the lock | Phase 9 → 10 transition; the Phase 12 → 13 gate's A3-M1 | Every window boundary, deadline and effective date is judged on `clock_timestamp()` after the row lock |
| No machine-learned risk model | `DECISIONS.md` §Deferred | Rules as data through a versioned engine; a score is an explainable sum of rule contributions |
| Jurisdiction-neutral core; specifics behind adapters | `PRODUCT_VISION.md`; `DECISIONS.md` | The regulatory report is an artefact with a filing port; the jurisdiction is a field (§12.9) |

### 2.2 Owner decisions (2026-10-22) — C1–C10, recorded as accepted defaults pending ratification

| # | Decision | Default taken | Where it lands |
|---|---|---|---|
| C1 | **Fail-safe per operation and value band** (unresolved #11) | Internal transfers below EUR 1,000: `ALLOW` recorded; at or above: `DENY`. Wallet withdrawals and cross-border: `DENY` at every band. Checkout card and wallet payments below EUR 250: `ALLOW` recorded; at or above: `DENY`. All of it a field of the policy version, so the owner changes it by activation, not by code | ADR-0108, §12.3, policy v1 fixtures |
| C2 | **The decision vocabulary** | `ALLOW`, `CHALLENGE`, `DENY`, `HOLD`, `REVIEW` — each with a fixed meaning and the operations that admit it (§12.3); `HOLD` admitted only where the operation has a native hold-then-dispatch shape (withdrawals; card payments in `-010`'s second slice) | ADR-0108 |
| C3 | **Collections case management** | A thin slice only, cut candidate: `-025` opens a `COLLECTIONS` case on `LoanDefaulted` / `BnplDefaulted` with investigator actions as facts; contact strategy, promises to pay, placement and write-off stay out (Phase 14 and a later phase) | §3.1, §17 |
| C4 | **The reporting jurisdiction** | The reference remains the neutral EUR one; the regulatory report models the EU shape researched in §12.9 behind a simulated filing adapter; every field a real jurisdiction adds is adapter data | ADR-0111, §12.9 |
| C5 | **Criterion 7 in Phase 13** | The standing deviation continues (owner, 2026-10-22: keep skipping fleet tiers); database suites are written and owed per task; **one owner-authorised database pass before the exit review** is recommended and scheduled as a gate agenda item, not a task | §13.1 |
| C6 | **What a customer sees of a held or refused movement** | The operation's generic answer (`transfers.RISK_REFUSED` → the existing error; a `HELD` withdrawal reads `HELD` with no reason); nothing of an AML alert, case or report ever reaches a customer surface | §11, `INV-RSK-08` |
| C7 | **Override threshold** | An override of a `DENY`/`HOLD`/`REVIEW` decision needs a second `RISK_OVERRIDE` holder when the judged amount is at or above EUR 1,000 or the decision was `DENY`; below that, one holder with a reason, audited | ADR-0108 §Override |
| C8 | **Policy v1 content** | A test-only policy version per operation (the credit policy fixture precedent): rule parameters in §12.2's table; production activates none | §12.2, `-002` |
| C9 | **Metric tag keys** | `rule` added to `MetricNames.ALLOWED_TAG_KEYS` (bounded rule codes of a policy); no `decision_id`-shaped key | §15 |
| C10 | **ATO response** | A confirmed account-takeover case may propose `REVOKE_SESSIONS` and `SUSPEND_IDENTITY`; both execute through identity's existing commands under four eyes; the platform never resets a credential | ADR-0112, §12.8 |

---

## 3. Bounded contexts and modules

### 3.1 Ownership

| Concept | Owner | Phase 13 | Relationships, lifecycle |
|---|---|---|---|
| **Risk Signal** | `risk` | yes | An append-only fact about a subject (party, account, device, merchant, beneficiary, session): kind from a closed vocabulary, observed at, attributes; born once per source event (`UNIQUE (source_event_id)`); from identity, kyc, accounts, merchant, crossborder, lending, bnpl and payments events, and from monitoring. *(As built, `P13-TSK-005`, 2026-10-23: the subject is the aggregate the event names — the platform's events carry their own aggregate and enumerated codes, never the party behind it (`EventPayload`, `INV-AUD-02`) — so `SubjectKind` gained `IDENTITY` and `CREDIT_AGREEMENT` (a loan or a BNPL obligation); the party join is the consumer's, through a port at evaluation time. The attributes are typed columns bounded per kind; a device is only ever identity's SHA-256 of the description, `DeviceHash`.)* |
| **Risk Policy Version** | `risk` | yes | Per operation: rules as data, weights, score bands, fail-safe bands, challenge and override thresholds, effective-from; `PROPOSED → ACTIVE \| REJECTED`, `ACTIVE → RETIRED`; four eyes; one `ACTIVE` per operation |
| **Rule** | `risk` (inside the policy version) | yes | One row of a closed `RuleKind` with parameters and a weight or a hard decision — data, never code |
| **Risk Assessment** | `risk` | yes | The sealed record of one evaluation: operation, subject refs, amount, the inputs read, the policy and engine versions, rule hits, score, decision, ordered reason codes, fail-safe applied, latency; `INSERT`-only; `UNIQUE (operation, command_ref)` |
| **Risk Score** | `risk` (a field of the assessment) | yes | An integer 0–1000, Σ rule contributions clamped; never summed with a credit score (`INV-CRD-04`) |
| **Risk Decision** | `risk` (a field of the assessment; the seam adapter maps it) | yes | `ALLOW \| CHALLENGE \| DENY \| HOLD \| REVIEW` with reason codes; immutable; an override is a separate aggregate |
| **Decision Override** | `risk` | yes | `PROPOSED → APPROVED \| REJECTED`; four eyes above the threshold (C7); never edits the decision; issues the action the decision withheld (a held withdrawal's release) |
| **Velocity Window** | `risk` | yes | A durable counter per (dimension, subject, window kind, window start): count and sum; upserted in the judged command's transaction; proven against monitored transactions |
| **Customer Risk Profile** | `risk` | yes | Per party: rating `LOW \| MEDIUM \| HIGH \| PROHIBITED`, versioned (`UNIQUE (party_id, version)`), the assessment that set it; recomputed by the sweep on signals and case outcomes; read by credit's seam and by every evaluation |
| **Transaction Risk Profile** | `risk` (a value inside the assessment) | yes | The computed view of one judged movement — amount band, counterparty novelty, device novelty, velocity readings — stored only as the assessment's inputs |
| **Monitored Transaction** | `risk` | yes | The channel-neutral record of one money movement, born once per `ledger` journal entry (`UNIQUE (journal_entry_id)`), classified by posting-key namespace through a port over the ledger; the monitoring rules' subject |
| **Alert** | `risk` | yes | Born once per (subject, rule, window start) from a monitoring or fraud rule; `OPEN → ATTACHED \| SUPPRESSED`; carries evidence refs, severity, policy version; never deleted |
| **Case** | `risk` | yes | Kinds `FRAUD \| ATO \| MERCHANT_ABUSE \| AML \| COLLECTIONS` (C3); `OPEN → IN_INVESTIGATION → ESCALATED → DISPOSED`; one open per (subject, kind); a `revision` for concurrent updates; an SLA deadline |
| **Investigator Action** | `risk` | yes | Append-only, reasoned, audited: note, evidence attached, escalation, action proposed/approved/rejected, disposition proposed/approved/rejected, report drafted |
| **Disposition** | `risk` | yes | `CLOSED_FALSE_POSITIVE \| CLOSED_NO_ACTION \| CONFIRMED_FRAUD \| CONFIRMED_SUSPICIOUS \| REFERRED`; four eyes for every `CONFIRMED_*` and for any disposition that issues an action |
| **Escalation** | `risk` (a case transition and action) | yes | Raises the case's level and queue; recorded, reasoned |
| **Risk Action** (downstream) | `risk` (the record) / the owning domain (the effect) | yes | `PROPOSED → APPROVED → DISPATCHED → DONE \| FAILED`; executed in its own transaction through a port over the owning domain's existing command, keyed; never a direct write |
| **Screening Result** | `kyc` | exists | `risk` reads clearances through a port and records a signal; rescreening is requested through kyc's ports (`-022`) |
| **Screening List Version** | `risk` | yes | An operator-recorded fact that a sanctions/PEP list version is in force; its activation enqueues rescreening of `ACTIVE` parties |
| **Regulatory Report** | `risk` | yes | The filing artefact, distinct from the case: `DRAFT → APPROVED → FILED → ACKNOWLEDGED \| REJECTED`; four eyes to approve; a port to file (simulated); immutable once filed; a jurisdiction field |
| Transfer, Withdrawal, Payment, Cross-border payment, BNPL application, Loan | their modules | exist | Consult the seams; commit their own outcomes; keep their lifecycles (`transfers.RISK_REFUSED`, `payments` `HELD`) |
| Identity, Account, Merchant, Beneficiary | `identity`, `accounts`, `merchant`, `crossborder` | exist | The targets of actions, through their own commands |
| Collections operations (contact strategy, promises, placement) | a later collections context | **out** | C3: Phase 13 opens the case only |
| Write-off, provisioning | `accounting` | Phase 14 | unchanged |

**Canonical names** (`GLOSSARY.md`): **Risk Assessment** is the record of one evaluation; **Risk Decision** its
outcome; **Risk Score** its number; **Alert** the born-once observation; **Case** the investigation; **Disposition**
its end; **Regulatory Report** the filing artefact (never "the SAR" in code — the jurisdiction names it).

### 3.2 The module decision: one `risk` module, three capabilities, none of the money

**Decision (ADR-0107, proposed).** A new module **`risk`**, schema `risk`, Flyway `risk/V001…`, owning contexts 20
(Risk), 21 (Fraud), 22 (AML / Transaction Monitoring) and 23 (Case Management) as `MODULE_ARCHITECTURE.md` M3 and M6
already decided. Edges: **`risk → platform, sharedkernel` only.** No ledger edge — `risk` posts nothing — and no edge to
any business module: every other domain is reached through ports `risk` declares and `app` implements (the
crossborder, lending and bnpl precedent), and every seam the consuming modules declare (`transfers.TransferLimitCheck`,
`crossborder.CrossBorderRiskDecision`, `credit.CreditRiskSignal`, payments' new ones) is implemented in `app` as an
adapter over `risk`'s one channel-neutral evaluation API. `RiskModuleIsolationTest` refuses every business module and
`app`; every sibling's isolation test refuses `risk`.

**Three capabilities kept distinct inside it** (M3's condition): **fraud decisioning** is synchronous, in the
caller's transaction, latency-bounded (§7.6); **AML monitoring** is asynchronous, retrospective, a leaderless sweep in
its own transactions; **case management** serves both and never decides money. They share the policy model, the
engine, the signal store and the case model. M3's split triggers stand: AML acquiring retention or isolation
obligations fraud does not share, or monitoring workload harming the synchronous budget — the storm measures the
second (§13.3).

**Alternatives weighed.**

1. *A rules engine in `platform`.* Refused: the engine is a domain capability with versioned policy, cases and
   obligations, not a cross-cutting mechanism; `platform` is depended on by everything and must own no business
   lifecycle (M4's argument).
2. *Risk evaluation as a remote service with a latency budget.* Refused for Phase 13: the in-lock contract of the
   seams makes an out-of-process call inside a money transaction a single point of failure for all movement
   (`DELIVERY_PLAN.md` §Phase 13 §17 names the risk). In the modular monolith the evaluation is a method on the
   caller's connection; the latency budget is enforced by a statement timeout on `risk`'s reads (§7.6), and the
   fail-safe decision is what a budget breach becomes. Extraction is Phase 16's measured decision (ADR-0001).
3. *Separate `fraud` and `aml` modules.* Refused by M3; both would need the engine, the signals and the case model.
4. *Velocity in Redis.* Refused by `INV-CON-03` and the seam contract: a counter that can be lost is not an authority,
   and a counter outside the money transaction can be passed twice.

---

## 4. Aggregates and commands

| Aggregate | Commands | Arbiters (§7.4) |
|---|---|---|
| Risk Policy Version | propose, activate (second person), reject, retire | one `ACTIVE` per operation (partial unique); advisory namespace **13** `hashtext(operation)`; four-eyes `CHECK` |
| Risk Assessment | evaluate (in the caller's transaction); advise (read-only, at a door) | `UNIQUE (operation, command_ref)`; `INSERT`-only |
| Decision Override | propose, approve, reject | `UNIQUE (assessment_id)` live proposal; four-eyes `CHECK` |
| Velocity Window | increment (one `UPSERT … RETURNING`) | primary key (dimension, subject, window kind, window start) |
| Risk Signal | record (from an event or the sweep) | `UNIQUE (source_event_id)` |
| Customer Risk Profile | recompute (the sweep; a case outcome) | `UNIQUE (party_id, version)`; the profile head row `FOR UPDATE` |
| Monitored Transaction | intake (the sweep) | `UNIQUE (journal_entry_id)`; the watermark row |
| Alert | raise, attach, suppress | `UNIQUE (subject_ref, rule_code, window_start)` |
| Case | open, take, note, attach evidence, escalate, propose/approve/reject disposition, dispose | partial unique one open per (subject, kind); `revision` compare-and-set; four-eyes `CHECK` |
| Risk Action | propose, approve, dispatch, conclude | `UNIQUE (case_id, kind, target_ref)` live; keyed through the owning domain |
| Regulatory Report | draft, approve (second person), file, acknowledge | `UNIQUE (case_id)` open draft; `FILED` immutable |
| Screening List Version | record, activate | `UNIQUE (list, version)` |

---

## 5. Lifecycles

Drafted in full in [`RISK_LIFECYCLES.md`](../domain/RISK_LIFECYCLES.md); summarised:

- **Policy version:** `PROPOSED → ACTIVE` (a second person) `| REJECTED`; `ACTIVE → RETIRED` beside its successor's
  activation in one transaction (the BNPL terms shape, so the pin never reads "neither" — the Phase 12 → 13 gate's A3-I1
  applied forward: the pin takes the shared advisory lock when its first read is empty). *(Built by `P13-TSK-002`,
  2026-10-23, as written; several `PROPOSED` per operation numbered without a gap, activation monotonic in version
  number — a proposal below the version in force `409 risk.PolicyStale`; `RISK_LIFECYCLES.md` §4.1's as-built note.)*
- **Assessment / decision:** born `DECIDED` with one decision; never changes. An **override** is `PROPOSED → APPROVED |
  REJECTED` and records the effect it issued.
- **Withdrawal with a `HOLD`:** payments' machine gains `HELD` before `DISPATCHED`: `HELD → DISPATCHED` (released by an
  approved override or a case disposition) `| FAILED(RISK_REFUSED)` (denied by an approved override or disposition — or
  **expired**: `held_until` = held at + the policy version's hold validity, default 72 h, judged on the database clock
  under the withdrawal row lock by a new leg of payments' existing `WithdrawalResolutionSchedule`, reason detail
  `HOLD_EXPIRED`; the case stays open); the ledger hold placed at the withdrawal is kept through `HELD` and released
  on `FAILED` — payments' own machine, changed by `-009` with its own migration. *(As built, `P13-TSK-009`,
  2026-10-24, payments `V033`: born in the verdict's state — no `SUBMITTED` exists, so `HELD` is a birth beside
  `DISPATCHED` and `FAILED(RISK_REFUSED)`; `held_at`/`held_until` stamped by the database from the validity the seam
  answers with its `HOLD` (`Assessed.holdValidity`, the pinned version's); the release is payments' keyed
  `Withdrawals.releaseHeld` (within `held_until` — past it `REFUSED`, the expiry being the policy's outcome), the
  denial `Withdrawals.denyHeld`, the expiry `WithdrawalResolution.expireHolds` on the existing schedule; the doors reached
  through risk's `HeldWithdrawalActions`, implemented in `app` and called by nothing until `-012`/`-017`.
  `RISK_LIFECYCLES.md` §4.12's as-built note.)*
- **Alert:** `OPEN → ATTACHED` (to a case — an alert raised while its subject has an open case of the kind attaches
  to it, the system's dedupe) `| SUPPRESSED` (only an operator's reasoned suppression under four eyes:
  `POST /v1/operator/risk/alerts/{id}/suppression`, a second holder's approval, audited `risk.AlertSuppressed`).
- **Case:** `OPEN → IN_INVESTIGATION` (taken) `→ ESCALATED` (optional) `→ DISPOSED`; a case may be disposed
  `CLOSED_FALSE_POSITIVE` or `CLOSED_NO_ACTION` straight from `OPEN` (a false positive needs no investigation, one
  person, reasoned); a case re-opens never — a new case references it.
- **Risk action:** `PROPOSED → APPROVED` (second person) `| REJECTED`; `APPROVED → DISPATCHED → DONE | FAILED`; a
  `FAILED` action is re-proposable, never re-dispatched by itself.
- **Regulatory report:** `DRAFT → APPROVED` (second person) `→ FILED → ACKNOWLEDGED | REJECTED` (recorded by the
  filing port's inquiry leg, the system actor, audited `risk.ReportAcknowledged` / `risk.ReportRejected`; an
  `UNKNOWN` filing is a permit on the `FILED` row, never re-filed); a rejected filing is a new draft.
- **Customer risk profile:** versioned ratings; never edited; `PROHIBITED` set only by a disposition under four eyes.

---

## 6. Financial invariants

Phase 13 moves no money; its invariants are about judgement, evidence and separation. Ten new ones (`INV-RSK-01`…`-10`,
the catalogue 152 → 162) and the existing ones this phase must protect:

| ID | One line |
|---|---|
| `INV-RSK-01` | Every judged money-moving command has exactly one risk assessment, sealed in the command's own transaction, replayable `IDENTICAL` |
| `INV-RSK-02` | A risk decision never moves money, holds money or writes another domain's row; every effect is the owning domain's own committed outcome or a keyed command through its door |
| `INV-RSK-03` | A velocity window is a durable row written in the judged command's transaction, and its figures equal the monitored transactions it counts |
| `INV-RSK-04` | Every decision records the policy version, the engine version, every rule that fired and its contribution, and the ordered reason codes; the fail-safe is a recorded decision, never a silent default |
| `INV-RSK-05` | A policy version is immutable once decided; only an `ACTIVE` version is evaluated; activation is four eyes; the pin never answers "neither" during an activation |
| `INV-RSK-06` | An alert is born once per (subject, rule, window); a case is open at most once per (subject, kind); a replayed event or a reprocessed history creates no second alert, case, action or filing |
| `INV-RSK-07` | A disposition that confirms fraud or suspicion, an override, a policy activation, a report approval and every downstream action are two persons' acts; the proposer never approves; a refused self-approval is recorded `FAILED` |
| `INV-RSK-08` | No AML alert, case, report or investigation evidence reaches a customer- or merchant-facing surface, a log, an event payload or a metric tag (tipping-off); a customer's answer is the operation's generic outcome |
| `INV-RSK-09` | Every monitored transaction is born once from a ledger journal entry; the monitoring record never diverges from the journal (a census proves it); reprocessing from any watermark is idempotent |
| `INV-RSK-10` | The customer risk profile is versioned and append-only, and credit's snapshot records the rating it was given; a rating change never rewrites a decision already made |

Restated here, not re-numbered: `INV-CON-03` (durable authority), `INV-IDEM-01`/`-04` (keyed commands; inbox dedupe),
`INV-HIST-04` (versioned artefacts carry effective dates), `INV-AUD-02`/`-03`/`-04` (no secrets in records; disjoint
populations; four eyes are two persons), `INV-CRD-01`/`-04` (credit replay; score separation), `INV-EVT-04`
(tolerate duplication, delay, replay), `INV-BAL-04` (a withdrawal's hold under the wallet lock).

---

## 7. Multi-instance architecture

### 7.1 Transactions

| Name | Where it runs | What it does |
|---|---|---|
| **T-eval** | inside the caller's transaction, after the caller's locks | pin the `ACTIVE` policy (R1), read the inputs and the current velocity figures, evaluate, then — only when the decision permits the movement — upsert the velocity windows (R2), insert the assessment (R3); return the decision (§12.4: a refused movement counts for nothing) |
| **T-advise** | a read at a door, no transaction of the command, no row | the same evaluation without the upserts and without the insert — for the step-up answer before anything exists; never authoritative |
| **T-pol** | own | propose / activate / reject / retire a policy version (A0 advisory → the version rows) |
| **T-ovr** | own | propose / approve / reject an override (R1 → the assessment's override row R4) and record the action it issues |
| **T-sig** | the inbox handler's transaction | record a signal born once; pull the sweep's permit forward |
| **T-mon** | own, per claim | the monitoring sweep: claim the watermark (R7), read journal entries since it through the port, insert monitored transactions, advance the watermark; then per subject: lock the profile head (R8), evaluate the monitoring policy over the subject's windows, raise alerts, fold into a case, recompute the profile |
| **T-case** | own | every case act under the case row (R4) and the `revision` compare-and-set; alerts attached (R5) |
| **T-act** | own, per action | dispatch an approved action: the action row (R6), then the owning domain's command on **its** lock order, then the outcome — never holding R4 |
| **T-rep** | own | draft / approve / file a regulatory report (R4 → the report row); the filing call holds no connection |
| **T-list** | own | record / activate a screening list version; enqueue rescreening permits |

### 7.2 The Phase 13 lock order (`DISTRIBUTED_EXECUTION.md` §3's row "The Phase 13 lock order", *planned*)

Risk rows are taken **after** every money row and **only** in two shapes: last inside a caller's transaction, or first
in a risk-only transaction that takes no money row. Positions, in parentheses:

**(R1)** the operation's `ACTIVE` `risk_policy_version` row `FOR SHARE` (the pin; when the first read is empty the pin
takes `pg_advisory_xact_lock_shared(13, hashtext(operation))` and reads once more — the gate's A3-I1 rule); **(R2)**
the judged command's `velocity_window` rows, upserted in **sorted key order** (dimension, subject, window kind, window
start), each one statement `INSERT … ON CONFLICT DO UPDATE … RETURNING`; **(R3)** inserts (assessment, signal); **(R4)**
the `risk_case` row `FOR UPDATE` (or the override / report row of an own transaction); **(R5)** alert rows; **(R6)** the
`risk_action` row `FOR UPDATE`, then the owning domain's command on that domain's own order; **(R7)** the monitoring
watermark row `FOR UPDATE SKIP LOCKED` (the claim; commits alone); **(R8)** the `customer_risk_profile` head row
`FOR UPDATE`.

**No cycle, by construction (§7.3):** a money transaction holds its money rows and then takes (R1)–(R3), which no risk
transaction holds while waiting on a money row — T-case, T-mon, T-rep take no money row at all, and T-act takes (R6)
and then enters the owning domain's command, which takes money rows in that domain's order **without any risk row
held except (R6)**, which no money transaction ever takes. (R1) is `FOR SHARE` and conflicts only with T-pol's
activation, which holds no money row. The only risk rows a money transaction writes are its own velocity windows, in
sorted order, so two money transactions upserting overlapping windows cannot deadlock.

*(As built, `P13-TSK-006`, 2026-10-23 — **(R2a)**, before (R2): one advisory lock per (dimension, subject) the judged
movement carries, namespace `131`, in ascending lock-key order (`VelocityWindows.lockKey`, SHA-256 of the dimension and subject — one global order, collisions included), taken
before the database clock and the window figures are read and held to commit, so each subject's read-evaluate-upsert
is one serialised step whether or not its row exists yet: the caller's money lock serialises the source account only,
and two movements of one party from two accounts would otherwise both pass a threshold the other was about to cross
(`INV-CON-03`). It sits where (R2)'s row locks already sat and adds no cycle among velocity writers. Recorded, not
introduced: the callers post **after** T-eval (`TransferExecution`), so (R2a)/(R2) are held while the posting takes its
balance-projection rows; the argument above needs that no transaction holding such a row waits on a velocity lock —
true for a transfer, for `-004`/`-009` to confirm for the withdrawal's in-lock judgement after its hold.)* *(Confirmed
by `P13-TSK-009`, 2026-10-24: after its R2a locks a withdrawal touches only rows it created and the wallet row it already
holds (a refusal's hold release re-locks that row), so it never waits on a money row while holding a velocity lock; the
held withdrawal's doors and expiry take the withdrawal row then the wallet — payments' order — and never (R1) or (R2a).
The argument in full: `DISTRIBUTED_EXECUTION.md` §3's Phase 13 row.)* *(Confirmed by `P13-TSK-010`, 2026-10-24: the
checkout confirmation's Tx1 takes the intent row, then its money rows - the credit account `FOR SHARE` for a card, the
book rail's wallet pair `FOR UPDATE` for the wallet - and only then (R1)–(R3), so a velocity lock is never held while a
wallet row is awaited; after T-eval it touches only rows it creates, rows it already holds, and (the wallet) the hold
placement and the posting's projection rows. The held card payment's doors and deadline take the capture hold row and
then the existing capture or void commands in their own transactions, never (R1) or (R2a). The argument in full:
`DISTRIBUTED_EXECUTION.md` §3's Phase 13 row.)* *(Built by `P13-TSK-011`, 2026-10-24 — **the cross-border case needed a
repair**: crossborder's first transaction holds no money row *at the seams' position*, but after them it takes the quote
`FOR UPDATE` (fx's acceptance) and the source wallet `FOR UPDATE` (the hold) while (R2a) is held to commit - neither of
this section's two shapes, and a cycle with a transfer, withdrawal or wallet payment of the same party, which takes the
wallet and then the party's R2a. `app`'s judgement therefore takes those two rows first, in the Phase 9 order (the
quote, then the wallet fx's acceptance resolves - `CrossBorderMovements.locked`), and only then (R1)–(R3); the
acceptance and the hold re-lock rows already held. The argument in full: `DISTRIBUTED_EXECUTION.md` §3's Phase 13
row.)*

### 7.3 Couplings read in the code

- **Transfers:** `TransferExecution` consults both seams after `lockBothInFixedOrder` and the availability read
  (`:233-248`), before the posting; the Phase 13 adapters run T-eval there. A refusal commits `FAILED(RISK_REFUSED)` /
  `FAILED(LIMIT_REFUSED)` as today. The door's advisory step-up check runs before the command (T-advise).
  *(As built, `P13-TSK-008`, 2026-10-23 — `RiskTransferLimitCheck` (consulted first) runs T-eval for
  `(TRANSFER, transfer id)` and seals; `RiskTransferRiskDecision` is answered from the seal — one assessment, one
  increment. The limit seam refuses exactly when a fired `AMOUNT_BAND`/`VELOCITY_*` rule's own hard decision is the
  refusing decision (`Assessed.limitDecided`); the risk seam refuses every non-permitting decision. The request's party
  is the initiating identity's, its accounts the wallets' `owner_ref`s re-read under the execution's own locks. The door
  (`TransferDoorStepUp`) advises in a transaction of its own — not `READ ONLY`, the pin being `FOR SHARE` — after the
  caller's 422s, never for a recorded key and never over a source not the caller's; no migration; `transfers`
  unchanged.)*
- **Cross-border:** `CrossBorderAuthorization` consults the seams in its first transaction before the offer is
  accepted; a refusal consumes nothing (`422 crossborder.RiskRefused` / `LimitRefused`). Unchanged contract.
  *(As built, `P13-TSK-011`, 2026-10-24 — `PaymentAuthorization` consults the risk seam, then the limit seam (the reverse
  of transfers); `app`'s `RiskCrossBorderRiskDecision` runs T-eval for `(CROSS_BORDER, quote id)` - the quote is the
  movement's identity before the payment's id exists, one payment per quote - and `RiskCrossBorderLimitCheck` is answered
  from the seal: the limit seam refuses exactly a limit-decided refusal, the risk seam every other non-permitting
  decision - disjoint, so the code is the same in either order. The contract's one change: `CrossBorderInstruction`
  gains `quoteId` (the Phase 9 instruction named neither the movement nor the beneficiary). The judged amount is the
  quote's EUR figure - its source when the source is EUR, else its destination when the corridor converts to EUR, else
  the source as is and the recorded fail-safe decides (`USD-BHD-BH`, `GBP-USD-US`: owner item). "A refusal consumes
  nothing" held by the authorization's position; **its assessment is kept** - the door spares its savepoint rollback for
  exactly the two seam refusals, so the seal commits beside the claim's outcome, while a permit later refused by fx or
  payments rolls back with its count. The door's T-advise before the claim (`403 identity.AssuranceRequired`, the `app`
  port `CrossBorderStepUpAdvice`). The lock order repaired - §7.2's note.)*
- **Withdrawals:** `Withdrawals` is hold-then-dispatch (ADR-0062 §6): the hold is judged under the wallet account's lock;
  the new `WithdrawalRiskDecision` is consulted there, in-lock, after the hold is placed and before the dispatch is
  committed; `HOLD` commits `HELD` with the hold kept; `DENY` commits `FAILED(RISK_REFUSED)` and releases the hold in
  the same transaction; `ALLOW` and a satisfied `CHALLENGE` commit `DISPATCHED` as today. Payments' migration `V033`.
  *(As built, `P13-TSK-009`, 2026-10-24 — `Withdrawals` mints the withdrawal's id after `HoldService.place` and asks
  `WithdrawalRiskDecision` (a required constructor parameter) under it; `app`'s `RiskWithdrawalRiskDecision` runs T-eval
  for `(WITHDRAWAL, withdrawal id)`: `PERMIT` for `ALLOW` and a satisfied `CHALLENGE` (counted), `HOLD` with the pinned
  hold validity (**not counted** — OQ-1 read for `HOLD`: a window counts what the decision permits), `REFUSE` for
  `DENY`, `REVIEW` and an unsatisfied `CHALLENGE` (C1's fail-safe `DENY` at every band among them). The request carries
  the party, the wallet's customer account and the acting identity; **no device** (`-008`'s finding) and **no
  destination** (`RiskEvaluationRequest.Destination` names a customer account or a beneficiary — an external bank
  instrument is neither, and a third kind is a risk migration: recorded). The person's door asks T-advise through the
  `app` port `WithdrawalStepUpAdvice` first (`403 identity.AssuranceRequired`, nothing written). The SYSTEM entry on an
  instruction is not judged (owner item). The lock order confirmed — `DISTRIBUTED_EXECUTION.md` §3's Phase 13 row.)*
- **Checkout payment confirmation:** `PaymentConfirmation.confirm` decides routing in Tx1 under the intent; the new
  `PaymentRiskDecision` is consulted in Tx1 after the eligibility checks and before the routing decision commits;
  `DENY` is **terminal for the intent** (not re-confirmable — the customer creates a new intent; routing's refusal
  today leaves the intent re-confirmable, a risk refusal must not), its decision row sealed in Tx1; `-010` decides
  whether that is a `FAILED(RISK_REFUSED)` attempt row written before any dispatch or an intent-level refused state
  (`PaymentFailureReason.RISK_REFUSED`, payments `V034`), and the customer sees the intent's generic failure; `HOLD` (slice 2 of `-010`, cuttable) authorises and defers the capture. The book rail's wallet pair
  lock is unchanged.
  *(As built, `P13-TSK-010`, 2026-10-24, both slices — Tx1 locks the intent row first (`findOwnedForUpdate`), then the
  instrument, the credit account `FOR SHARE` (card/bank), the routing plan (no rail: routing's refusal, unjudged,
  re-confirmable), the wallet's book pair `FOR UPDATE`, and then asks `PaymentRiskDecision` once, before anything of the
  dispatch is written - **risk's rows after the pair**, not before it as this bullet's backlog sketch read, because R2a
  is held to commit and a transfer of the same party takes the wallet row before its own R2a. The refusal is
  **intent-level**: `REQUIRES_CONFIRMATION → FAILED` with the intent's own `RISK_REFUSED`, no attempt, no routing row,
  terminal (`409 payments.NotConfirmable` for every re-confirmation); the customer reads `FAILED` with no reason and the
  session `PAYMENT_PENDING`, the merchant the same. `app`'s `RiskPaymentRiskDecision` judges merchant-bound card
  (`CARD_PAYMENT`) and wallet (`WALLET_PAYMENT`) payments under the intent's id - the party, the merchant from the
  payable, the wallet's customer account for the wallet, no device - and answers `PERMIT` unjudged for a top-up and a
  pay-by-bank checkout. Slice 2: a card `HOLD` authorises as any card payment and births an open capture hold
  (`held_until` the pinned version's validity on the database clock); `HeldPayments.captureHeld`/`voidHeld` (keyed) and
  the deadline leg on `PaymentSweeperSchedule` resolve it exactly once, the money then moved by the existing capture or
  void. The checkout door asks T-advise first (`403 identity.AssuranceRequired`, nothing written).)*
- **Credit:** `SnapshotFreezer` records `CreditRiskSignal.signal(uow, party)` in the snapshot (`:375`); the Phase 13
  adapter answers the profile's rating with seam version 2; a decision made under version 1 replays with `NOT_ASSESSED`.
- **Actions:** identity's `IdentityAdministration.suspend`, accounts' suspension, merchant's `MerchantAdministration
  .suspend`, crossborder's beneficiary block and payments' held-withdrawal release/deny are the existing commands; each
  is keyed and audited by its owner; `risk` records the request and the outcome.

### 7.4 Contention table (each row a counted test)

| Contended resource | Arbiter | Test |
|---|---|---|
| One assessment per judged command | `UNIQUE (operation, command_ref)` under the caller's lock | `RiskEvaluationDatabaseTest#tenRetriesOneAssessment` |
| Velocity windows under ten concurrent movements of one subject | one-statement `UPSERT … RETURNING` | `VelocityWindowDatabaseTest#tenMovementsCountTen` |
| The window boundary across instances with clocks ±5 s | the window start computed from the database clock read after the lock | `#aWindowIsCutByTheDatabaseClock` |
| Policy activation racing evaluations | `FOR SHARE` pin + the advisory re-read; the retire-and-activate in one transaction | `RiskPolicyDatabaseTest#anEvaluationBehindAnActivationPinsTheSuccessor` |
| Two instances raising one alert | `UNIQUE (subject_ref, rule_code, window_start)` with `ON CONFLICT DO NOTHING` | `AlertDatabaseTest#twoInstancesRaiseOnce` |
| Two investigators updating one case | the case row lock + `revision` compare-and-set; the loser answers `409 risk.CaseRevisionStale` | `CaseDatabaseTest#concurrentUpdatesKeepEveryAction` |
| Two cases opened for one subject and kind | the partial unique one-open | `CaseDatabaseTest#tenAlertsOneOpenCase` |
| Ten approvers of one action | the action row lock + the conditional edge | `RiskActionDatabaseTest#tenApproversOneDispatch` |
| A duplicated downstream command | the owning domain's idempotency key `risk.action:<id>` | `RiskActionDatabaseTest#aReDriveReplays` |
| Duplicate / delayed / replayed events | inbox dedupe per consumer + `UNIQUE (source_event_id)` + born-once alerts | `SignalIntakeDatabaseTest#theSameEventTwiceRecordsOnce` |
| Ten sweepers over one watermark | `FOR UPDATE SKIP LOCKED` claim; `UNIQUE (journal_entry_id)` | `MonitoringIntakeDatabaseTest#tenSweepersIngestOnce` |
| Reprocessing from an earlier watermark | born-once rows; alert and case uniques | `#reprocessingCreatesNothingNew` |
| A delayed event after a case is disposed | the event records a signal; a disposed case is never re-opened; a new alert opens a new case referencing the old | `CaseDatabaseTest#aLateEventAfterDispositionOpensANewCase` |
| The profile recomputed by two sweepers | the head row `FOR UPDATE`; `UNIQUE (party_id, version)` | `ProfileDatabaseTest#tenRecomputesOneVersion` |
| An approved release racing the held withdrawal's expiry (`held_until`) | the withdrawal row lock in payments' order; the action keyed; the first committed edge wins and the loser is a recorded `DONE(ALREADY)` outcome | `WithdrawalHoldDatabaseTest#releaseAndExpiryLeaveExactlyOne` |

### 7.5 Schedules

| Schedule | Claims | Permit |
|---|---|---|
| `RiskMonitoringSchedule` | the monitoring watermark per source (the intake leg); profiles due a recompute; rescreening permits (`-022`) | `next_sweep_at` on the watermark row; per-profile `next_recompute_at` |
| `RiskActionSchedule` | `APPROVED` actions not yet dispatched; `DISPATCHED` actions awaiting an outcome | `next_attempt_at` on the action row |

Both leaderless, `SKIP LOCKED`, off in test contexts, each with its gauge and its claim failures metered (N5);
`LEASE_PROTECTED_SCHEDULERS` 29 → 31. `RiskMonitoringSchedule` is created by `-019` (wave B, the intake leg); `-007`
and `-022` add their legs. The held withdrawal's expiry (§5) is a leg of payments' existing
`WithdrawalResolutionSchedule`, added by `-009` — not a risk schedule.

*(As built by `P13-TSK-019`, 2026-10-23: `RiskMonitoringSchedule` created in `app` with the intake leg — the 30th
`LEASE_PROTECTED_SCHEDULERS` entry; `finapp.risk.sweeper.enabled{kind=monitoring}` and
`finapp.risk.sweeper.claim.failed{kind=monitoring}`. The claim and its batch are ONE transaction: the watermark row
`FOR UPDATE SKIP LOCKED` on `clock_timestamp()`, the journal read with no lock, the rows inserted born once, the
position advanced and the permit re-stamped (now after a full batch, the idle cadence after a short one) — so a
partial batch never advances. One tick takes batches while they come back full, bounded (`batches-per-tick`). The
watermark's permit is also pulled forward by the hint `MonitoringWatermarkStore.hint`, declared here for `-005`'s
completion handlers.)*

### 7.6 The latency budget and what a breach becomes

T-eval runs in-lock, so its cost is paid by every money movement. Budget: **p99 ≤ 50 ms** for the reads and upserts
(the storm measures it, §13.3). Enforcement: `risk`'s reads run under a per-statement `SET LOCAL statement_timeout`
of 250 ms inside the caller's transaction, restored after; a timeout is "unavailable" and becomes the band's fail-safe
decision (§12.3), recorded with reason `RISK_UNAVAILABLE` — the transaction is not aborted by the timeout because the
statement runs behind a savepoint. The monitoring sweep never runs inside a money transaction, so its workload cannot
breach the budget except through row contention on windows, which the storm measures (M3's split trigger).

*(As built, `P13-TSK-004`, 2026-10-23 — `RiskEvaluation` over `EvaluationBudget`/`JdbcEvaluationBudget`: a JDBC
savepoint, then `set_config('statement_timeout', '250ms', true)` after it — so rolling back to it reverts the setting
with the step — and, on success, the transaction's own value restored before the savepoint is released. **Two
savepoints, one timeout:** the pin (R1) runs behind a savepoint of its own **without** the timeout — an evaluation
meeting an activation waits for it and pins the successor (A3-I1, §5); a 250 ms limit there would turn every activation
into unpinned fail-safes — and is kept, its `FOR SHARE` held to the caller's commit; the cut (R2a), the reads, the engine
and the count (R2) run behind the second, timed savepoint, whose rollback releases R2a and the reads but never the pin.
A failure there is classified (`TIMEOUT` by SQLSTATE 57014, `ENGINE_REFUSED`, else `READ_FAILED`) and sealed as the
band's decision; a permitting fail-safe gets one count attempt behind a third timed savepoint, recorded `UNCOUNTED`
when it fails — so a hot subject queued past the budget costs at most two budgets and is a recorded fail-safe, never
an aborted movement. Latency is measured on the injected clock and sealed (`latency_micros`) and metered
(`finapp.risk.evaluation.latency`, buckets around 50 and 250 ms).)*

---

## 8. Data architecture

Schema `risk`, `REVOKE ALL FROM PUBLIC`; `SELECT, INSERT` on facts; conditional `UPDATE` only of status, permit,
revision and decision columns on the machine tables; no `DELETE` for any role; no money column that is a balance —
`amount_minor` + `currency` appear only as the judged amount on `INSERT`-only rows and as window sums on the
velocity rows (whose `UPDATE` is the one-statement upsert, bounded by a `CHECK` that a sum never decreases within a
window). Every timestamp from `clock_timestamp()` after the row lock. Tables (migration numbers are the order in which the waves of §17 land them — Flyway runs with out-of-order off, the
Phase 12 wave-D lesson — so a task's number is below every number of a later wave):

| Table | Migration | Shape |
|---|---|---|
| `risk_policy_version`, `risk_rule` | `V002` (`-002`) | versioned, four-eyes, one `ACTIVE` per operation; rules as rows of a closed kind with typed parameters |
| `risk_assessment`, `risk_assessment_input`, `risk_rule_hit`, `risk_reason` | `V007` (`-004`) | `INSERT`-only; `UNIQUE (operation, command_ref)` |
| `decision_override` | `V007` (`-004`) | the four-eyes machine |
| `risk_signal` | `V003` (`-005`) | `UNIQUE (source_event_id)`; attributes typed and bounded |
| `velocity_window` | `V005` (`-006`) | primary key (dimension, subject_ref, window_kind, window_start); count, sum_minor, currency |
| `customer_risk_profile`, `customer_risk_profile_version` | `V006` (`-007`) | head row + versions |
| `risk_assessment`, `risk_policy` CHECKs regenerated for the card `HOLD` | `V008` (`-010` slice 2) | *added 2026-10-24: `-010` landed first in merge order and took the next free number; the rows below take the next free numbers at their builds (Flyway out-of-order off) - the coordinator's renumbering* |
| `risk_alert` | `V010` (`-015`) — *as built 2026-10-25, the next free at its build (`-010` took `V008`, `-012` `V009`)* | born-once alerts: `UNIQUE (subject_ref, rule_code, window_start)`; the window aligned by `CHECK`; the suppression's four eyes by `CHECK`; no `DELETE` |
| `risk_case`, `risk_case_action`, `risk_case_event` | `V009` (`-016`) | the case machine with `revision`; one open per (subject, kind) |
| `risk_action` | `V010` (`-017`) | the action machine; `UNIQUE (case_id, kind, target_ref)` live |
| `monitored_transaction`, `monitoring_watermark` | `V004` (`-019`) | `UNIQUE (journal_entry_id)`; one watermark per source |
| `regulatory_report`, `regulatory_report_event` | `V011` (`-021`) | the filing artefact |
| `screening_list_version`, `rescreening_permit` | `V012` (`-022`) | list facts; permits |
| payments `withdrawal` `HELD`; `WithdrawalFailureReason.RISK_REFUSED` | payments `V033` (`-009`) | payments' own machine |
| payments `PaymentFailureReason.RISK_REFUSED` and the intent's refused-by-risk decision row | payments `V034` (`-010`) | payments' own *(as built: the intent's `failure_reason`, the fifth intent edge, and slice 2's `payment_capture_hold`)* |
| identity roles | identity `V023` (`-001`) | `RISK_ANALYST`, `RISK_OFFICER`, `AML_OFFICER` |

Data classification: assessment inputs and rule hits `CONFIDENTIAL`; signal attributes at their source's level
(`device` `RESTRICTED-PII`); case notes and evidence refs `CONFIDENTIAL`, screened for instrument shapes; the
regulatory report's narrative `RESTRICTED-PII`; nothing in `risk` is `INTERNAL` except identifiers, timestamps, statuses and the closed code vocabularies (the register's standing convention).

---

## 9. API architecture

All `/v1`, closed bodies, the error contract, `Idempotency-Key` on every keyed command, owner-scoped; a route carries
exactly one permission (`RoutePermissionRegisterTest`), so each desk's reads are its own doors. **No customer
route reads a risk table** (`INV-RSK-08`; `NoCustomerSurfaceReadsRiskTest`). Operator routes:

| Route | Who | Command | Idempotency | Answer |
|---|---|---|---|---|
| `POST /v1/operator/risk/policies` · `…/{id}/activation` · `…/{id}/rejection` | `RISK_POLICY_ADMINISTER` | propose; activate (a second holder); reject | `risk.policy:<actor>` | `201` / `200` / `403 risk.SelfApprovalRefused` (the `bnpl`/`credit` precedent: a refused second person is a forbidden act, not a conflict) |
| `GET /v1/operator/risk/policies/{id}` · `GET …?operation=` | `RISK_POLICY_ADMINISTER` | read (an assessment names its policy version; the analyst reads it through the assessment, `-004`) | — | `200` |
| `GET /v1/operator/risk/assessments/{id}` | `RISK_INVESTIGATE` | the assessment with its inputs, hits, score, decision, reasons | — | `200`, audited `risk.AssessmentRead` |
| `POST /v1/operator/risk/assessments/{id}/replay` | `RISK_INVESTIGATE` | re-evaluate from the sealed inputs; `IDENTICAL` / `DIVERGED` | — | `200`, audited `risk.Replayed` |
| `POST /v1/operator/risk/assessments/{id}/overrides` · `…/{id}/approval` · `…/{id}/rejection` | `RISK_OVERRIDE` | propose; approve (a second holder, C7); reject | `risk.override:<actor>` | `201` / `200` |
| `GET /v1/operator/risk/alerts?status=&kind=&after=` | `RISK_INVESTIGATE` | the queue, oldest first, keyset paged | — | `200`, audited |
| `POST /v1/operator/risk/alerts/{id}/suppression` · `…/suppression/approval` · `…/suppression/rejection` | `RISK_INVESTIGATE` / `RISK_CASE_APPROVE` | propose a reasoned suppression; approve (a second holder); reject | `risk.alert:<actor>` | `201` / `200`, audited `risk.AlertSuppressed` |
| `POST /v1/operator/risk/cases` · `GET /v1/operator/risk/cases?status=&kind=&after=` · `GET …/{id}` | `RISK_INVESTIGATE` | open (from an alert or by hand); queue; read | `risk.case:<actor>` | `201` / `200`; never the operator's own party (self-dealing, fail closed) |
| `POST /v1/operator/risk/cases/{id}/actions` (note, evidence, escalate, take) | `RISK_INVESTIGATE` | append an investigator action; body carries the `revision` | `risk.case:<actor>` | `200` / `409 risk.CaseRevisionStale` |
| `POST /v1/operator/risk/cases/{id}/dispositions` · `…/approval` · `…/rejection` | `RISK_INVESTIGATE` / `RISK_CASE_APPROVE` | propose; approve (a second holder); reject | as above | `201` / `200` |
| `POST /v1/operator/risk/cases/{id}/risk-actions` · `…/approval` · `…/rejection` | `RISK_INVESTIGATE` / `RISK_CASE_APPROVE` | propose; approve (a second holder) → dispatched by the schedule | as above | `201` / `202` |
| `POST /v1/operator/risk/cases/{id}/reports` · `…/approval` · `…/filing` | `AML_REPORT_APPROVE` / `AML_REPORT_FILE` | draft; approve (a second holder); file | `risk.report:<actor>` | `201` / `200` / `202` |
| `POST /v1/operator/risk/monitoring/reprocessing` | `RISK_POLICY_ADMINISTER` | order a reprocessing of the monitoring intake from an earlier watermark (§12.7) | `risk.reprocess:<actor>` | `202`, audited `risk.ReprocessingOrdered` |
| `POST /v1/operator/risk/screening-lists` · `…/{id}/activation` | `RISK_POLICY_ADMINISTER` | record a list version; activate (rescreening enqueued) | `risk.list:<actor>` | `201` / `202` |
| `GET /v1/operator/risk/parties/{partyId}/profile` | `RISK_INVESTIGATE` | the rating history | — | `200`, audited |
| `GET /v1/operator/reports/risk/{decisions,alerts,cases,velocity}?date=` | `RISK_INVESTIGATE` | reports | — | `200`, audited `risk.ReportRead` |
| **Customer:** `GET /v1/me/withdrawals/{id}` (payments, existing) | customer | reads `HELD` as a status word only | — | the existing answer |

Errors (`ERROR_CONTRACT.md`, planned): `risk.PolicyNotActive`, `risk.PolicyStale`, `risk.PolicyInvalid`,
`risk.SelfApprovalRefused`, `risk.SelfDealingRefused`, `risk.CaseRevisionStale`, `risk.CaseNotOpen`,
`risk.AlertNotOpen`, `risk.ActionNotProposable`, `risk.ReportNotFileable`, `risk.ReasonRequired`, `risk.NotFound`;
and in the consumers: payments `RISK_REFUSED` as a failure reason on withdrawals and payment intents (their own
contracts), transfers' and crossborder's existing reserved codes unchanged.

---

## 10. Event architecture

Outbox, the platform envelope, producer `risk`, event version 1, additive evolution; every event in its state change's
transaction. **No event carries a reason text, a score, a rule name, a note or any PII** — ids, codes and statuses
only (`INV-RSK-08`); the consumer learns *that* a decision was made, never *why* beyond the reason codes' closed
vocabulary.

| Event | Aggregate | When | Carries |
|---|---|---|---|
| `RiskDecisionMade` | assessment | T-eval | assessment id, operation, command ref, decision, reason codes |
| `RiskSignalRecorded` | signal | T-sig / T-mon | signal id, kind, subject kind and ref |
| `LimitBreached` | assessment | a velocity or amount rule fired `DENY`/`HOLD` | assessment id, rule code |
| `AlertRaised` | alert | T-mon / T-eval | alert id, kind, severity, subject ref |
| `CaseOpened` / `CaseEscalated` / `CaseDisposed` | case | T-case | case id, kind, status, disposition code |
| `RiskActionRequested` / `RiskActionCompleted` | action | T-case / T-act | action id, kind, target kind and ref, outcome |
| `CustomerRiskRatingChanged` | profile | T-mon / T-case | party id, rating, version |
| `RiskPolicyActivated` | policy version | T-pol | operation, version |
| `RegulatoryReportFiled` | report | T-rep | report id, jurisdiction — **never** the subject |

`TransactionBlocked` (`DELIVERY_PLAN.md` §8) is not a risk event: the blocked movement's owner announces its own
outcome (`transfers` with `RISK_REFUSED`; payments with `HELD`/`FAILED`) and `RiskDecisionMade` carries the judgement.

**Consumed** (as hints and as signal sources, each through an `InboxEventHandler` named `risk.<component>`):
`identity.AuthenticationFailed`, `AuthenticationLocked`, `MfaChallengeFailed`, `CredentialChanged`,
`ContactChannelAdded`, `RecoveryInitiated`, `RecoveryCompleted`, `SessionRotated`, `IdentitySuspended`; kyc's case and
screening outcomes; `accounts.AccountOpened`; `crossborder.BeneficiaryRegistered`; `merchant` enablement and
suspension; `lending.LoanDelinquencyChanged`/`LoanDefaulted`; `bnpl.BnplDelinquencyChanged`/`BnplDefaulted`;
`transfers`, `payments` and `crossborder` completions as hints that pull the monitoring permit forward.

*(Erratum, `P13-TSK-005`, 2026-10-23 — the list above named events that do not exist; only events that exist are
consumed, every one under the consumer name `risk.signals` (one dedupe scope, one group `finapp.risk`, one handler
instance per event type — `app`'s `RiskSignalIntake`). **Consumed as signals:** identity's `CredentialChanged`,
`RecoveryInitiated`, `RecoveryCompleted`, `IdentitySuspended`; `accounts.AccountOpened`; crossborder's
`BeneficiaryRegistered`, `BeneficiaryActivated`, `BeneficiaryBlocked` (the counterparty screening's outcome as its
owner committed it); `merchant.MerchantOnboarded`; lending's `LoanDelinquencyChanged`, `LoanDefaulted`; bnpl's
`BnplDelinquencyChanged`, `BnplDefaulted`. **Consumed as hints, recording no signal:** `transfers.TransferCompleted`,
`payments.PaymentCaptured`, `payments.PaymentExecuted`, `payments.WithdrawalCompleted`,
`crossborder.CrossBorderPaymentDelivered` — each handed to `MonitoringHints`, `NONE` until `-019` implements the permit
pull; a signal per completed movement would be a second, event-derived copy of the journal's born-once
`monitored_transaction` (`INV-RSK-09`). **Named here but not existing today, recorded as planned:** identity's
`AuthenticationLocked`, `MfaChallengeFailed`, `ContactChannelAdded`, `SessionRotated` (audit actions only);
`identity.AuthenticationFailed` exists but names no identity by design (`INV-IDN-07`: its aggregate is a fresh id),
so a signal from it would have no subject — `recentAuthFailures` has no event source until the owner decides
otherwise, and `-004` may read identity's `authentication_failure` through a port; kyc's case outcomes
(`kyc.DecisionRecorded` is an audit action; `KycCaseOpened` is an opening, not an outcome, and names a customer);
`kyc.CounterpartyScreeningDecided` exists but names its subject only inside crossborder's private request-reference
convention; merchant enablement and suspension (audit actions only). The handler reads the payload — the first
consumer to do so — through `EventPayload.fields`, the writer's exact inverse; an unknown version or an unreadable
payload is thrown, never acknowledged: the inbox has no "parking", the loud stall is its mechanism.)*

---

## 11. Security, audit and privacy

- **Permissions** (identity `V023`): `RISK_INVESTIGATE` (read signals, assessments, profiles and cases; open and work
  cases; propose dispositions, actions and overrides' inputs), `RISK_CASE_APPROVE` (approve another holder's
  disposition or action), `RISK_POLICY_ADMINISTER` (propose and activate policy versions and list versions, under
  four eyes), `RISK_OVERRIDE` (propose and approve decision overrides, C7), `AML_REPORT_APPROVE` (approve a regulatory
  report), `AML_REPORT_FILE` (file it). **Roles:** `RISK_ANALYST` {INVESTIGATE, CASE_APPROVE}; `RISK_OFFICER`
  {POLICY_ADMINISTER, OVERRIDE}; `AML_OFFICER` {AML_REPORT_APPROVE, AML_REPORT_FILE} — a partition: pairwise-disjoint
  grants between the new roles, and with every existing one (`INV-AUD-03`); four eyes inside a role between two
  holders. *(Erratum, `P13-TSK-001`, 2026-10-22: the plan first wrote the analyst's reads into every desk - `RISK_OFFICER` and `AML_OFFICER` each holding `RISK_INVESTIGATE`, the AML officer `RISK_CASE_APPROVE` too. Identity holds one live role per identity (`V010`) and `RoleNameTest` holds every pair of grants disjoint, so the three desks are a partition: `RISK_ANALYST` {`RISK_INVESTIGATE`, `RISK_CASE_APPROVE`}, `RISK_OFFICER` {`RISK_POLICY_ADMINISTER`, `RISK_OVERRIDE`}, `AML_OFFICER` {`AML_REPORT_APPROVE`, `AML_REPORT_FILE`}. A route carries exactly one permission, so where a second desk needs a read it has its own door under its own permission: the override door serves the assessment it overrides under `RISK_OVERRIDE`; the report draft door serves the disposed case's evidence under `AML_REPORT_APPROVE`; an analyst never files and an AML officer never investigates - a stronger separation of the investigation from the filing than the plan had.)*
- **Self-dealing, fail closed:** no holder reads or works a case, approves an override or files a report on their own
  party's subject; an operator with no placeable party is refused (the gate's A7-6 applied forward); recorded `FAILED`.
- **Tipping-off (`INV-RSK-08`):** no customer or merchant route reads any `risk` table; the customer sees the
  operation's generic outcome; AML case existence is never derivable from a response, a timing difference being
  outside this phase's claims; logs carry ids and counts; events carry codes; metric tags are bounded vocabularies.
- **Evidence protection:** evidence is held as references (signal ids, assessment ids, journal entry ids) resolved
  through audited reads, never copied; notes are bounded and screened by `InstrumentShapes`; the report narrative is
  `RESTRICTED-PII` and readable only by `AML_REPORT_*` holders.
- **Audit** (`AUDITABLE_ACTIONS.md`, planned): `risk.PolicyProposed/Activated/Rejected/Retired`,
  `risk.AssessmentRead`, `risk.Replayed`, `risk.OverrideProposed/Approved/Rejected`, `risk.AlertSuppressed`,
  `risk.CaseOpened/Taken/Noted/Escalated/Disposed`, `risk.DispositionProposed/Approved/Rejected`,
  `risk.ActionProposed/Approved/Rejected/Dispatched/Concluded`, `risk.ReportDrafted/Approved/Filed/Acknowledged/Rejected`,
  `risk.ListVersionRecorded/Activated`, `risk.ProfileRead`, `risk.CaseRead`, `risk.AlertRead`, `risk.ReportRead`,
  `risk.ReprocessingOrdered`, `risk.SelfApprovalRefused`,
  `risk.SelfDealingRefused` — each with actor, time, target, reason where required, correlation and outcome; summaries
  carry counts and codes, never amounts or names.
- **Step-up:** policy activation, override approval, report approval and filing, and every `CONFIRMED_*` disposition
  require a `MULTI_FACTOR` operator session.

---

## 12. The risk model

### 12.1 Operations judged synchronously, and what each admits

| Operation | Seam (consumer's) | Adapter (`app`) | Admits | Task |
|---|---|---|---|---|
| `TRANSFER` (internal) | `transfers.TransferLimitCheck`, `TransferRiskDecision` | `RiskTransferLimitCheck`, `RiskTransferRiskDecision` | `ALLOW`, `CHALLENGE`, `DENY`, `REVIEW` | `-008` |
| `WITHDRAWAL` (wallet → external) | payments' new `WithdrawalRiskDecision` | `RiskWithdrawalRiskDecision` | `ALLOW`, `CHALLENGE`, `DENY`, `HOLD`, `REVIEW` | `-009` |
| `CARD_PAYMENT`, `WALLET_PAYMENT` (checkout confirmation) | payments' new `PaymentRiskDecision` | `RiskPaymentRiskDecision` | `ALLOW`, `CHALLENGE`, `DENY`; `HOLD` in slice 2 | `-010` |
| `CROSS_BORDER` | `crossborder.CrossBorderLimitCheck`, `CrossBorderRiskDecision` | `RiskCrossBorderLimitCheck`, `RiskCrossBorderRiskDecision` | `ALLOW`, `CHALLENGE`, `DENY`, `REVIEW` | `-011` |
| `CREDIT_SIGNAL` (not a movement) | `credit.CreditRiskSignal` | `RiskCreditRiskSignal` (seam version 2) | the rating code | `-007` |
| `MONITORING` (not a movement; the sweep's policy, no seam) | — | — | alerts (§12.7) and the rating bands (§12.5) | `-019`, `-020`, `-007` |

*(As built, `P13-TSK-010`, 2026-10-24: the card payment row's `HOLD` admitted - risk `V008`, `RiskDecision.admittedBy`
- with the slice; a `CARD_PAYMENT` version names a hold validity exactly when one of its bands or rules can decide
`HOLD`, so the C8 fixture, which holds nothing, stays valid; `WALLET_PAYMENT` admits no `HOLD`.)*
*(As built, `P13-TSK-011`, 2026-10-24: the `CROSS_BORDER` row unchanged - `ALLOW` and a satisfied `CHALLENGE` permit,
an unsatisfied `CHALLENGE` and every `DENY`/`REVIEW` refuse, `REVIEW` calling `CaseOpener` (`NONE` until `-016`); the
corridor (`SRC-DST-CC`) and the beneficiary (`DESTINATION`, `NEW_PAYEE`'s registration age) are inputs and dimensions,
the judged amount the quote's EUR figure.)*

A policy version is **per operation**; a band mapping to a decision the operation does not admit is refused at the
policy door (`422 risk.PolicyInvalid`). BNPL applications and loan applications are judged through credit (the signal),
not through a seam of their own — one decision per purchase (B5) stays credit's.

### 12.2 Policy as versioned data (ADR-0109)

A policy version holds: the operation; the engine version it is written for (`RISK_ENGINE_V1`); its **rules**, each a
`RuleKind` from the closed vocabulary with typed parameters, a weight (score contribution when it fires) and/or a hard
decision; **score bands** mapping [0,1000] intervals to decisions (for the `MONITORING` operation, **rating bands** mapping the
party's signal score to `LOW`, `MEDIUM` or `HIGH` — §12.5); **fail-safe bands** mapping amount bands to the
decision taken when the evaluation is unavailable (C1); the **hold validity** (how long a `HOLD` may stand before it
expires, default 72 h — §5); the **challenge assurance** (`MULTI_FACTOR`); the **override
threshold** (C7); **effective from**; the proposer, the activator, the decision stamps on the database clock. Immutable
once decided; the successor's activation retires it in one transaction.

`RuleKind` v1 (code, never configuration): `AMOUNT_BAND`, `VELOCITY_COUNT`, `VELOCITY_SUM`, `NEW_PAYEE`, `NEW_DEVICE`,
`SESSION_ASSURANCE_BELOW`, `RECENT_AUTH_FAILURES`, `RECENT_CREDENTIAL_CHANGE`, `RECENT_RECOVERY`, `ACCOUNT_AGE_BELOW`,
`KYC_NOT_APPROVED`, `SCREENING_HIT`, `PROFILE_RATING`, `MERCHANT_SUSPENDED`, `CORRIDOR_HIGH_RISK`,
`DORMANT_THEN_ACTIVE`, `RAPID_IN_OUT`, `STRUCTURING`, `ROUND_AMOUNT`, `REFUND_RATIO`, `CHARGEBACK_RATIO`,
`BNPL_MULTI_APPLICATION`. Each kind declares its parameter schema (window kind, threshold count or amount, lookback
days, ratio basis points, the set of corridors…), validated at the policy door; a parameter outside its schema is
`422 risk.PolicyInvalid`. Rules are evaluated in the version's declared order; the engine is deterministic: the same
sealed inputs give the same hits, score and decision in any JVM (the battery proves it in two).

**Policy v1 fixtures** (C8; test only; the credit policy fixture precedent): per operation, a version with
`AMOUNT_BAND` (challenge at EUR 500, deny at EUR 10,000 for transfers; withdrawals hold at EUR 2,500), `VELOCITY_COUNT`
(10 per 24 h per source account), `VELOCITY_SUM` (EUR 5,000 per 24 h per party), `NEW_PAYEE` + `AMOUNT_BAND`
composite weight, `NEW_DEVICE` weight, `RECENT_CREDENTIAL_CHANGE` + withdrawal → `HOLD`, `SCREENING_HIT` → `DENY`,
`PROFILE_RATING = PROHIBITED` → `DENY`; the monitoring version with `STRUCTURING` (≥ 3 movements each below EUR 1,000
summing above EUR 2,500 in 24 h), `RAPID_IN_OUT` (≥ 80% out within 24 h of in), `DORMANT_THEN_ACTIVE` (90 days
dormant, then ≥ EUR 1,000 in 24 h), `ROUND_AMOUNT` weight, `CORRIDOR_HIGH_RISK`.

*(As built, `P13-TSK-003`, 2026-10-23: `RiskEngineV1` evaluates every rule — no first-match — by one `RuleEvaluation`
per kind, the two enums held equal as the class loads; the sealed inputs are `EvaluationInputs`, every reading a
`Reading` `PRESENT` or `ABSENT`, typed and bounded (an input past its bound is refused, never clamped), and a rule over
an `ABSENT` reading does not fire and records `ABSENT` on its hit — "we did not look" kept apart from "we looked and it
was not so" (`FirstSeen.never()`, `LastSeen.never()` are present values). `STRUCTURING` reads the window's movement
amounts themselves (`Amounts`, sorted, ≤ 10,000) because its "below" threshold is the policy's, which the reader must
not know; `RAPID_IN_OUT`, `REFUND_RATIO` and `CHARGEBACK_RATIO` read the two figures of a share and compare by
cross-multiplication in `long`, never a quotient (`INV-MON-01`); `DORMANT_THEN_ACTIVE` reads the dormant days beside
the party's velocity sum in the window. A rule that throws is `EvaluationFailed`, named by ordinal and kind, never a
partial verdict — `ROUND_AMOUNT` with `modulus_minor = 0`, which the policy door admits, is the one such case found,
for `-002`'s door to tighten. The canonical form is `CanonicalEvaluation` format 1 (hand-written, byte-exact, SHA-256
as lowercase hex, the inputs form parsed back strictly); `RiskEngineDigestTest` proves the bytes identical in a second
JVM with another charset, locale, time zone and identity hash codes.)*

### 12.3 The decision model and the fail-safe (ADR-0108)

Derived from what each operation can do with a judgement, not adopted as a list:

| Decision | Meaning | Permitted downstream effect |
|---|---|---|
| `ALLOW` | the movement may proceed | the seam answers `PERMIT` |
| `CHALLENGE` | the movement may proceed only under `MULTI_FACTOR` assurance | the seam answers `PERMIT` when the session already satisfies it, else `REFUSE` — committed as the reserved `RISK_REFUSED` with reason `CHALLENGE_REQUIRED`; the door's T-advise turns an unsatisfied `CHALLENGE` into `403 identity.AssuranceRequired` before any row exists, so the committed refusal is the race's answer, not the normal path |
| `DENY` | refuse | the seam answers `REFUSE`; the caller commits its reserved reason; a case opens when the policy says so |
| `HOLD` | accept the command, move nothing until a person decides | only where the operation has a native hold-then-dispatch shape: the withdrawal commits `HELD` with its ledger hold kept until `held_until` (the policy's hold validity; expiry fails it `RISK_REFUSED`/`HOLD_EXPIRED` and releases the hold, §5); a card payment authorises and defers the capture (slice 2); a case opens; release or denial is an approved override or disposition issuing the action |
| `REVIEW` | refuse now and open a case | the seam answers `REFUSE`; a case of the operation's kind opens in T-eval's transaction with the assessment as its first evidence |

**Precedence when rules disagree:** `DENY` > `HOLD` > `REVIEW` > `CHALLENGE` > `ALLOW`; a hard rule's decision beats
a score band's.

*(As built, `P13-TSK-003`, 2026-10-23 — the composition read one way: the decision is the **strictest** of the score
band's decision and every fired rule's hard decision (`RiskDecision.strictest`), so "a hard rule's decision beats a
score band's" means a band can never *soften* a hard decision — a screening hit's `DENY` stands at score 0 — while a
band may raise one: the C8 transfer fixture's hard `CHALLENGE` at EUR 500 and its `DENY` band from 800 both work, and
a EUR 2,000 transfer to a new payee from a new device by a `HIGH`-rated party is `DENY`, not capped at `CHALLENGE`.
The other reading — the hard layer overriding the bands entirely — would make every band dead above EUR 500 and was
refused. With nothing fired the score is 0 and **the band covering 0 decides**; the engine compiles no `ALLOW`, so a
"challenge everything" version stays expressible (`-002` validates the bands contiguous from 0 for exactly this). The
`MONITORING` version's verdict is the rating its rating bands give and no decision; it has no fail-safe. The fail-safe
is `RiskEngineV1.failSafe`: the policy's band for the amount under `RISK_UNAVAILABLE`, or with no policy the compiled
`ALLOW` under `NO_POLICY_ACTIVE` — a `Verdict` with no hit and score 0, `failSafeApplied` true; the *cause* of the
unavailability is `-004`'s column, not the engine's. `CHALLENGE_REQUIRED` is in the closed vocabulary (`ReasonCode`,
source `SEAM`) and is never an engine verdict's reason.)*

**Fail-safe (unresolved #11, answered):** "unavailable" is any of: no `ACTIVE` policy for the operation; the pin's
read failing; a rule evaluation throwing; the statement timeout (§7.6). The answer is the policy's fail-safe band for
the judged amount — or, with no policy at all, the compiled `ALLOW` — **always recorded** as an assessment with the
reason code `RISK_UNAVAILABLE` or `NO_POLICY_ACTIVE`, its decision `ALLOW` or `DENY` per C1, and the alert
`RiskEvaluationUnavailable` raised. A fail-open is therefore a visible, counted event, never silence. The compiled
no-policy answer is `ALLOW` for every operation, withdrawals and cross-border included, because until the first
activation the platform has not yet chosen to judge (§1.1) and a compiled `DENY` would stop every withdrawal on the
day the module deploys; C1's bands — `DENY` at every band for withdrawals and cross-border — apply from the first
activated version, which is why the activation gate of `OPERATIONS_RUNBOOK.md` §9 is a production step and not a
default.

**Override:** a `RISK_OVERRIDE` holder proposes an override of a `DENY`, `HOLD` or `REVIEW` decision with a reason
code and text; at or above the threshold (C7) a second holder approves; the override records the action it issues
(release the held withdrawal; nothing for a denied transfer — the customer retries under a new key, and the override
sets a one-time allowance for the same command ref so the retry's evaluation reads it as an input); it never edits the
decision. An `ALLOW` is never overridden after the fact — the money has moved; a case is the remedy.

*(As built, `P13-TSK-012`, 2026-10-24 — **the override**: the effect is the proposal's, judged against the decision -
a held withdrawal released or denied, a held card payment captured or voided (each an *approved* override's effect,
four eyes at or above C7 and `MULTI_FACTOR`), a `DENY`/`REVIEW` overridden as `RESUBMISSION` with nothing dispatched;
**the one-time allowance is not built** - a transfer's retry carries a new command ref, and a cross-border retry on the
same quote is answered from its seal before any input is read - an owner item; a released hold is not counted in the
windows; the action dispatched through payments' keyed door after the approval commits, keyed `risk.action:<override
id>`, re-driven by the door's retry and a leg on `RiskMonitoringSchedule`; risk `V009`. `RISK_LIFECYCLES.md` §4.2a's
as-built note.)*

*(As built, `P13-TSK-004`, 2026-10-23 — the fail-safe as `RiskEvaluation` seals it, by cause (`UnavailableCause`, a
column beside the reason code): `NO_POLICY` → the compiled `ALLOW` under `NO_POLICY_ACTIVE` (the windows count it — the
money moves); `PIN_UNREADABLE` → the compiled `ALLOW` under `RISK_UNAVAILABLE`, **no version named** — the bands could
not be read, and a compiled `DENY` would be policy in code; `READ_FAILED`, `TIMEOUT`, `ENGINE_REFUSED` → the pinned
version's band for the judged amount under `RISK_UNAVAILABLE` — **or, for an amount in another currency than the
version's, the open-ended band** (`RiskEngineV1.failSafeUnplaced`; `DENY` in every C1 band set: an amount that cannot
be compared is not known to be below any threshold), an owner item before the activation gate since it refuses every
non-EUR judged movement once a EUR version is active. **The seam's answer** is `RiskEvaluation.Assessed`: `permits()`
for `ALLOW` and a `CHALLENGE` whose sealed session assurance satisfies the version's challenge assurance (an absent
session — every one until `-008` wires `SessionAssurance` — is unsatisfied), `refusalReason()` `CHALLENGE_REQUIRED` for
an unsatisfied one; T-advise's `Advice.stepUpRequired()` is the door's `403 identity.AssuranceRequired`. A `REVIEW`
and a `HOLD` call `CaseOpener` (`NONE` until `-016`); a `DENY` opens no case — the version has no "case on deny" field,
`-016`'s to add with its opener.)*

*(As built, `P13-TSK-008`, 2026-10-23 — the `CHALLENGE` row on transfers: `SessionAssurance` implemented
(`IdentitySessionAssurance`: the session the request presented, re-read live in the caller's transaction; `PASSWORD` →
`SINGLE_FACTOR`, `MULTI_FACTOR`/`STRONG` → `MULTI_FACTOR`), so a satisfied challenge permits and counts; the door's
T-advise answers `403 identity.AssuranceRequired` before any row exists, with identity's step-up words (step up, or enrol
and step up) and nothing of the judgement; the committed `RISK_REFUSED` (`CHALLENGE_REQUIRED` on the assessment) is the
race's answer — the windows moved, or the session was revoked or rotated, between the advice and the command. For a
consumer with a limit seam beside its risk seam (transfers, and cross-border at `-011`), `LIMIT_REFUSED` is exactly a
refusal a fired amount or velocity rule's own hard decision made (`DENY` or `REVIEW`); everything else refused is
`RISK_REFUSED`.)*

### 12.4 Velocity (INV-RSK-03)

Dimensions: party, source account, destination account or beneficiary, device, merchant. Windows: `H1`, `D1`, `D7`,
`D30`, each keyed by its start computed from `clock_timestamp()` read after the caller's lock. T-eval reads the current
figures, evaluates, and — only when the decision permits the movement — upserts the increments in the same
transaction, so a refused movement counts for nothing and a committed one counts exactly once. The windows are
derivable: `VelocityProof` recomputes every live window from the monitored transactions and compares; a difference is
an alert, never self-corrected.

*(As built, `P13-TSK-003`, 2026-10-23 — what the engine does with a velocity reading: `VELOCITY_COUNT` and
`VELOCITY_SUM` compare **the sealed figure** — the window's count or sum as T-eval read it, *before* the judged
movement — **at or above** the threshold, and the engine never adds the judged amount into a figure: a sealed figure is
the window's figure verbatim, which is what `VelocityProof` recomputes. So "10 per 24 h" refuses the eleventh movement
(prior count 10 ≥ 10) and "EUR 5,000 per 24 h" fires once 5,000 has been reached; the size of one movement is
`AMOUNT_BAND`'s business. A reading is keyed by (`SubjectKind` dimension, `WindowKind`) — the `dimension` parameter
`-002` stores (`ParameterType.SUBJECT_KIND`) — so `-004` maps `-006`'s window figures onto those keys; `-006`'s entry
names a `VelocityDimension { PARTY, SOURCE_ACCOUNT, DESTINATION, DEVICE, MERCHANT }`, and whether its two account
members fold into `ACCOUNT`/`BENEFICIARY` or `-002`'s parameter type grows is the coordinator's reconciliation between
`-006` and `-004`. A window the reader did not seal is `ABSENT` for the rule naming it.)*

*(As built, `P13-TSK-006`, 2026-10-23 — **the gap above is closed** by the coordinator's decision: the dimension is
`VelocityDimension { PARTY, SOURCE_ACCOUNT, DESTINATION, DEVICE, MERCHANT }` end to end — the parameter type
`VELOCITY_DIMENSION` (`SUBJECT_KIND` dropped; `risk V005` renames the column behind a guard, `V002` untouched),
`VelocityKey`, the evaluators and the canonical text (format still 1), so `-004` passes `VelocityWindows.read`'s
readings straight into `EvaluationInputs` with no mapping. Three readings the build fixed. **(R2a)** T-eval's read is
**serialised** — one advisory lock per (dimension, subject), namespace `131`, sorted, before the clock and the figures
are read (`VelocityWindows.cut`): the plan's unlocked read was safe only for the subject the caller's money lock
covers, and two movements of one party from two accounts would each read the party's window below its threshold
(`INV-CON-03`; §7.2's (R2) note). **The window** is a tumbling bucket anchored at Monday 1970-01-05 UTC (`H1` on the
hour, `D1` at UTC midnight, `D7` an ISO week, `D30` thirty days from the anchor), cut at the database instant read
after R2a; every dimension a movement carries counts in all four kinds, whichever the active policy reads.
**The proof's evidence** (`VelocityEvidence`): the journal can equal only what posts in the judged transaction — the
transfer — and names no party or device, so `JdbcVelocityEvidence` proves the `SOURCE_ACCOUNT` and `DESTINATION`
windows of transfers and reports the rest unprovable; a withdrawal, a payment and a cross-border payment post at their
outcome, so before `-009`…`-011` wire their increments the evidence moves to the permitting assessments (`-004`'s
sealed inputs) — recorded for the coordinator; "windows = monitored sums" (§4.11, `INV-RSK-09`) holds for transfers.)*

### 12.5 Signals and the customer risk profile

Signals are recorded by inbox handlers (one `InboxEventHandler` per source module, consumer `risk.signals`), born once
per event id, with the attributes the event carries and nothing read from the source's tables. The profile is
recomputed by the sweep when a signal or a case outcome arrives for the party: the `MONITORING` policy's rule kinds fire over the party's
signals and case history, and its **rating bands** map the resulting signal score to `LOW`, `MEDIUM` or `HIGH`;
`PROHIBITED` is set only by a four-eyes disposition. Credit's seam answers
the current rating's code (`LOW`…`PROHIBITED`) with seam version 2 and the profile version, recorded in the snapshot
(`INV-RSK-10`). Devices: identity's `session.device` description is hashed at the source (the handler receives the
hash only) and compared for novelty; no device description is stored in `risk`.

*(As built, `P13-TSK-005`, 2026-10-23: the canonical hash is `risk`'s `DeviceHash.of(description)` — SHA-256 over the
description's UTF-8 bytes, lowercase hex — declared once for identity to mirror when it first publishes a device;
today no identity event carries one, and `EventPayload`'s grammar could not carry a description. A `DEVICE` subject's
reference is that hash and nothing else, held by `SignalSubject`, by the handler (a `device` field is refused by name)
and by `risk V003`'s `CHECK`. The readings `-004` takes are `SignalReadings`: `recentCredentialChange` and
`recentRecovery` within a lookback on the identity, `deviceNovelty` as the absence of any signal about the hash,
`accountAge` from the first `ACCOUNT_OPENED`, a merchant's age from `MERCHANT_ONBOARDED`, a beneficiary's standing from
its latest `BENEFICIARY_*` signal; `screeningHit` is `ScreeningClearances`' answer (`app`'s `KycScreeningClearances`
over kyc's case checks); `recentAuthFailures` and `kycStatus` have no event source — see §10's erratum.)*

*(As built, `P13-TSK-007`, 2026-10-23 — four readings the build settled, `DESIGN-007` §0. **No profile is not a clean
bill of health:** a party risk has not assessed - no head, or one enrolled and not yet recomputed - answers credit's seam
`NOT_ASSESSED` at seam version 2 with no profile version, and `-004`'s `PROFILE_RATING` reading is `ABSENT`; **and with no
`MONITORING` version `ACTIVE` the recompute writes nothing** (the backlog's "`LOW` by the compiled default, recorded" was
refused: a rating nobody judged would reach credit's sealed snapshots as an assessment). **A version only when the
rating changes** (§4.9; a change of reasons alone writes nothing and announces nothing). **The profile version is pinned
in credit's snapshot as provenance** - `RISK_SIGNAL`'s `Port("risk-signal", 2, recordVersion)`, credit's canonical
format 2 (format 1 kept for replay; credit `V023` admits the format) - not as an attribute a credit rule could read.
**No `MONITORING` rule kind reads a signal** as the vocabulary stands (`RECENT_CREDENTIAL_CHANGE`, `RECENT_RECOVERY` are
judged-only; no kind reads a default or a delinquency): signals reach the profile as the hint that pulls its permit
forward (T-sig, `ProfileHints`, through the party join `RiskSubjectParties` - a party and an identity placed today) and
the rating moves on the screening hit until `-006`'s windows and `-020`'s readings arrive; admitting a signal-reading kind
for `MONITORING` is a policy-vocabulary decision recorded for the owner and `-020`. The operator read
`GET /v1/operator/risk/parties/{partyId}/profile` raises `risk.SelfDealingRefused` for the first time.)*

### 12.6 Fraud detection

Fraud rules run in T-eval (synchronous) and in the sweep (retrospective, over signals): **account takeover** — a
credential change or recovery followed within the lookback by a new payee, a new device and a withdrawal (`-013`);
**payment and merchant abuse** — chargeback and refund ratios per merchant and per customer, a merchant's refunds to
new accounts, BNPL applications across merchants within a window, a session confirming many checkouts (`-014`); each a
`RuleKind` with parameters, each firing an alert born once (`-015`) and folding into a case of the matching kind (`-016`).

*(As built, `P13-TSK-013`, 2026-10-25 - account takeover.)* The sequence is judged from the sealed hits (`AtoSequence`):
an anchor (`RECENT_CREDENTIAL_CHANGE`, `RECENT_RECOVERY`) fired, each continuation (`NEW_PAYEE`, `NEW_DEVICE`) fired or
unjudged (`ABSENT`, not carried) - a judged not-fired one breaks it -, the movement a transfer, withdrawal or cross-border
payment; the policy decides the movement (the WITHDRAWAL fixture's 24 h credential change holds). One alert
`ACCOUNT_TAKEOVER` per (party, anchor rule, the anchor signal's `D1` day), raised by T-eval behind its own savepoint and
by the profile leg over the party's identities' signals (the anchors admitted for `MONITORING`; there the anchor alone,
the payee, device and movement unobservable from signals today). Readings: the anchors live; `SESSION_ASSURANCE_BELOW`
live; `RECENT_AUTH_FAILURES` `ABSENT` (no source); an account payee's novelty its earliest counted `DESTINATION` window
(not "`D30` count = 0": a tumbling bucket on one anchor would call every payee new at each boundary), a beneficiary's its
registration, a withdrawal's `ABSENT` (no destination); a device's `ABSENT` (no seam carries one). No case or action yet
(`-016`, `-017`).

### 12.7 AML transaction monitoring (ADR-0111)

The monitoring intake reads the **ledger journal** — the one financial truth — through `MonitoredLedger`, a port
`app` implements over the ledger's journal entry store ordered by its sequence, from the watermark: each entry's
posting-key namespace classifies it (`transfer:`, `payments.`, `merchant-payout:`, `crossborder.`, `lending.`,
`bnpl.`…) into a monitored transaction with its kind, subject refs, counterparties, amount and value date; entries of
a namespace the register does not know are recorded as `UNCLASSIFIED` and alerted (nothing passes unseen). Events are
hints that pull the watermark's permit forward; the sweep reads rows. Per subject the monitoring policy's rules run
over the monitored transactions and the velocity windows (`STRUCTURING`, `RAPID_IN_OUT`, `DORMANT_THEN_ACTIVE`,
`ROUND_AMOUNT`, `CORRIDOR_HIGH_RISK`, `VELOCITY_*`), raising alerts born once per (subject, rule, window) and folding
them into the subject's open `AML` case. Reprocessing — re-running from an earlier watermark after a policy change or
an incident — is idempotent by construction (`INV-RSK-09`); an operator orders it at `POST /v1/operator/risk/monitoring/reprocessing` under `RISK_POLICY_ADMINISTER`, keyed,
audited `risk.ReprocessingOrdered`.

*(As built by `P13-TSK-019`, 2026-10-23. **The journal has no sequence column**: `MonitoredLedger` reads by the
entry's UUIDv7 id — the one ordered, indexed path — as a keyset, under a settle horizon on the database clock
(`finapp.risk.monitoring.horizon`, PT60S) so an entry still in flight is not overtaken; "the sequence" is the id's
48-bit millisecond prefix, what the watermark's `last_sequence` and the door's `fromSequence` speak in. An entry
committed later than the horizon after its mint is the design's recorded bound — caught by the census, repaired by
reprocessing (`DESIGN-019` §0). The namespace register is `MonitoredKind` (forty-four namespaces into twenty-three
kinds, `REVERSAL` and `ADJUSTMENT` by entry type), guarded by `PostingKeyNamespaceRegisterTest` over every module's
production source. `UNCLASSIFIED` is recorded and gauged here; the alert row is `-015`'s. The door answers `202
{source, fromSequence, previousSequence}`, refuses a point ahead of the watermark `422 risk.ReprocessingNotBehind`
and a missing reason `422 risk.ReasonRequired`, and restarts the intake at the millisecond's floor.)*

### 12.8 Cases, actions and the owning domains (ADR-0110, ADR-0112)

A case is the unit of a person's judgement: opened by T-eval (`REVIEW`, `HOLD`, a policy-flagged `DENY`), by the
sweep (an alert with no open case), or by hand; taken by an investigator; worked by append-only actions; escalated;
disposed under four eyes where the disposition confirms or acts. Every downstream effect is a **risk action**: a
keyed command through the owning domain's existing door — `SUSPEND_IDENTITY` / `REINSTATE_IDENTITY` and
`REVOKE_SESSIONS` (identity), `SUSPEND_ACCOUNT` / `REINSTATE_ACCOUNT` (accounts), `SUSPEND_MERCHANT` (merchant),
`BLOCK_BENEFICIARY` (crossborder), `RELEASE_HELD_WITHDRAWAL` / `DENY_HELD_WITHDRAWAL` and, with `-010`'s slice 2, `CAPTURE_HELD_PAYMENT` /
`VOID_HELD_PAYMENT` (payments), `RESCREEN_PARTY`
(kyc through `-022`). The action is proposed and approved in T-case, dispatched by `RiskActionSchedule` in T-act on
the owning domain's lock order with key `risk.action:<id>`, and its outcome recorded; a domain's refusal (an identity
already suspended) is a recorded outcome, not an error. **Risk never reverses a transaction**: a reversal is the
owning domain's four-eyes act, and a case may only recommend it (an investigator action of kind `REFERRED`).

### 12.9 The regulatory report — internal investigation versus filing (ADR-0111)

An internal alert or case is the platform's own; a **regulatory report** is a filing to an authority, and the two are
different aggregates with different lifecycles, permissions and retention. The report is drafted from a disposed
`CONFIRMED_SUSPICIOUS` case by an `AML_REPORT_APPROVE` holder, approved by a second, and filed by an `AML_REPORT_FILE`
holder through `RegulatoryFilingProvider`, a port whose Phase 13 implementation is a simulated FIU endpoint
(the `ScreeningAdapter` precedent); the artefact carries a jurisdiction, the reporting entity's reference, the subject
and account references, the transactions referenced, the grounds for suspicion (the case's reason codes and the
approved narrative), the attachments' references, and the filing's acknowledgement; it is immutable once `FILED`.

**Researched for the EU reference, to be verified when a jurisdiction is chosen (C4).** Regulation (EU) 2024/1624
(the AMLR) Article 69 places the duty to report suspicions to the national FIU on the obliged entity's own initiative,
with no amount threshold and including attempted transactions and cases where customer due diligence could not be
completed; FIU information requests are generally answered within five working days, shortenable in urgent cases;
AMLA is consulting on implementing technical standards under Article 69(3) for the reporting format and transaction
records. The regulation's general application date is understood to be 10 July 2027 and the tipping-off prohibition to
be Article 73 — both from general knowledge and **to be verified against the official text** at
eur-lex.europa.eu/eli/reg/2024/1624/oj. FATF Recommendation 20 requires prompt reporting of suspicions to the FIU and
Recommendation 21 protects good-faith reporters and prohibits tipping-off. Sources consulted (secondary, 2026-10-22):
[springlex.eu — AMLR Article 69](https://www.springlex.eu/en/packages/aml/amlr-regulation/article-69/),
[AMLA — public hearing on the draft ITS for reporting suspicions (2026-09-09)](https://www.amla.europa.eu/events/public-hearing-draft-its-format-reporting-suspicions-and-providing-transaction-records-2026-09-09_de),
[paytechlaw.com — minimum content requirements (GwGMeldV)](https://paytechlaw.com/en/flood-of-suspicious-reports-introduction-of-minimum-content-requirements-with-the-gwgmeldv/),
[crf.public.lu — guidelines on cooperation with the FIU](https://crf.public.lu/en/actualites/guidelinescooperation.html),
[financialcrimeacademy.org — confidentiality of SARs and tipping-off](https://financialcrimeacademy.org/confidentiality-of-sars-and-tipping-off/),
[thevoiceslu.com — FATF Recommendation 21](https://thevoiceslu.com/?p=67132). The platform's report is therefore
shaped as an abstraction: a jurisdiction code, a format version, the content categories above, and an adapter; the
German minimum-content rules (GwGMeldV) are an example of what an adapter adds, not a platform rule.

### 12.10 Rescreening on list updates (`-022`)

`risk` records a screening list version (sanctions, PEP) as an operator fact under `RISK_POLICY_ADMINISTER`; its
activation enqueues one rescreening permit per `ACTIVE` party; the sweep's rescreening leg requests the screening
through kyc's existing ports (`CounterpartyScreenings` for counterparties; the case-bound screening for parties),
records the clearance as a signal, and raises an alert on a hit. kyc stays the screening owner (Refinement 3).

---

## 13. Testing strategy

### 13.1 Tiers (C5 — the standing criterion-7 deviation)
Hermetic (the engine, rule kinds, the decision model, the dedupe and case machines over fakes — property tests and
two-JVM determinism); database (Testcontainers) suites **written per task and owed a first run**; architecture
(`RiskModuleIsolationTest`, `NoCustomerSurfaceReadsRiskTest`, `NoSingleInstanceAssumptionRulesTest` +2,
`ColumnClassificationTest`, `RoutePermissionRegisterTest`, `NoFloatingPointMoneyRulesTest` over `risk`); fleet
`databaseTest`/`kafkaTest` skipped per task. **The Phase 12 lesson** — three database-only defects found only when the
database tier ran — is answered by a scheduled, owner-authorised database pass before `P13-DOC-001` (the exit review's
agenda), and by every migration test asserting constraint-name uniqueness per table (the V012 class).

### 13.2 Mandatory scenarios (each a named test in its task and in the storm)
1. The same transaction event arrives three times, delayed and reordered — one signal, one alert, one case.
2. Two instances raise the same alert concurrently — one row.
3. A policy version is activated while ten evaluations run — every assessment pins exactly one version, none
   "neither", and the activation never waits on a money row.
4. A risk action times out at the owning domain and is retried — one effect (the domain's key).
5. Two investigators update one case concurrently — both actions kept, one `409`, no lost update.
6. A delayed event arrives after its case is disposed — the disposed case is untouched; a new alert opens a new case
   referencing it.
7. A downstream payment operation fails after a risk decision — the decision stands, the domain's outcome is recorded,
   nothing is retried by risk.
8. Historical journal entries are replayed from an earlier watermark — no new monitored transaction, alert, case or
   action.
9. The risk evaluation is unavailable (no policy; a timeout) — the fail-safe decision is recorded, the alert fires,
   the money movement commits the band's outcome.
10. A held withdrawal is released by an approved override — dispatched once; denied — the hold released once.
11. An account-takeover sequence (credential change → new payee → withdrawal) is held and cased.
12. A structuring sequence is alerted once per window and the case folds every alert.
13. The customer risk profile changes after a credit decision was sealed — the decision replays `IDENTICAL`.
14. A velocity window counts ten concurrent movements of one subject as ten, and a refused movement as zero.
15. An override, a disposition and a policy activation are each refused to their proposer and recorded `FAILED`.

### 13.3 The storm and the battery
`P13-TST-001` — two instances, clocks ±5 s, every scenario above with counts, the named lock waits, **the latency
budget measured** (T-eval p50/p99/max per operation, the monitoring sweep running beside it — M3's split trigger),
every census at every round (assessments = judged commands; windows = monitored sums; alerts born once; cases one open
per subject and kind; no customer route touching `risk`). `P13-TST-002` — ≥ 10,000 generated subjects and movement
sequences replayed through `RISK_ENGINE_V1` in two JVMs, byte-identical; perturbations of an input, a rule parameter,
a policy version flip the verdict and name their row; a fingerprint committed; a shrunk run fails the census.

---

## 14. Failure scenarios

| Situation | Outcome |
|---|---|
| Risk evaluation throws or times out inside a transfer | the fail-safe band's decision, recorded `RISK_UNAVAILABLE`; the transfer commits the band's outcome; alert |
| No `ACTIVE` policy for an operation | `ALLOW(NO_POLICY_ACTIVE)` recorded; alert `RiskPolicyNoneActive` where production is meant to judge (§9 runbook) |
| A policy activated mid-evaluation | the pin reads one version; the successor applies to the next evaluation |
| Duplicate signal / event | `UNIQUE (source_event_id)`; the inbox's dedupe |
| The monitoring sweep crashes mid-claim | the watermark's permit lapses; the next claim re-reads from the committed watermark; born-once rows |
| An owning domain refuses an action (already suspended) | the outcome is recorded `DONE(ALREADY)`; no retry |
| The filing provider is unavailable / unknown | `FILED` is committed before the call with the provider's reference pending; `UNKNOWN` is reconciled by inquiry, never re-filed (the payout precedent) |
| A held withdrawal reaches `held_until` with its case still open | payments' `WithdrawalResolutionSchedule` leg fails it `RISK_REFUSED` (`HOLD_EXPIRED`) and releases the hold under the row lock, once; the case stays open and its age alerts |
| A held withdrawal's customer closes the wallet | the close refuses while a hold is `ACTIVE` (accounts' existing rule) |
| An investigator leaves mid-case | the case's SLA gauge and alert; the queue is oldest-first; a take is one person's act |
| Two requests race | §7.4 |

---

## 15. Observability

Every series is `finapp.risk.*`; tags come from `MetricNames.ALLOWED_TAG_KEYS` plus `rule` (C9, added by `-004`, bounded by the
policy's rule codes); no tag ever carries an id, an amount or a name (`INV-RSK-08`). The table is the register criterion 6 of
`PHASE_GATES.md` §2 is assessed against at the exit (`PlannedMetersExistTest` reads it once Phase 13 is `COMPLETE`);
`P13-TSK-024` registers the series, the builder of each flow emits it, and a `RiskMetersTest` counts this table's rows
against the `app/.../risk/*Metrics` classes (the BNPL precedent). Alert rules live in `infra/prometheus/rules/risk.yml`;
the two the plan names are `RiskEvaluationUnavailable` and `RiskPolicyNoneActive`; `-024` names the rest.

| Series | Type | Tags | Alert |
|---|---|---|---|
| `finapp.risk.evaluation` | counter | operation, verdict | — |
| `finapp.risk.evaluation.latency` | timer (p50 / p99 / max) | operation | p99 over the 50 ms budget for five minutes (M3's split trigger watched) |
| `finapp.risk.evaluation.unavailable` | counter | operation, reason_code (`RISK_UNAVAILABLE`, `NO_POLICY_ACTIVE`) | `RiskEvaluationUnavailable`: any increase where a policy is active; `RiskPolicyNoneActive`: `NO_POLICY_ACTIVE` where production is meant to judge (runbook §9.1) |
| `finapp.risk.rule.hit` | counter | operation, rule | — |
| `finapp.risk.challenge` | counter | operation, outcome (`satisfied`, `refused`) | — |
| `finapp.risk.override` | counter | outcome | — |
| `finapp.risk.alert` | counter | kind, severity | a sustained rate far above the policy's baseline (flood) |
| `finapp.risk.alert.suppressed` | counter | reason_code | — |
| `finapp.risk.case` | counter | kind, status | — |
| `finapp.risk.case.backlog` | gauge (open cases) | kind | above the queue's staffed capacity |
| `finapp.risk.case.age` | gauge (seconds, oldest open) | kind | older than the kind's SLA |
| `finapp.risk.case.resolution` | timer (open → disposed) | kind | — |
| `finapp.risk.action` | counter | kind, outcome | — |
| `finapp.risk.action.failed` | counter | kind | any increase |
| `finapp.risk.report` | counter | status | a filing `UNKNOWN` for longer than the inquiry interval (runbook §9.4) |
| `finapp.risk.policy.active` | gauge (1 per operation with an `ACTIVE` version) | operation, policy_version | 0 for an operation production judges |
| `finapp.risk.monitoring.lag` | gauge (journal head − watermark, entries) | source | lag growing for an hour |
| `finapp.risk.monitoring.unclassified` | gauge (`UNCLASSIFIED` monitored transactions) | — | above 0 |
| `finapp.risk.velocity.proof` | counter | verdict | any `DIVERGED` |
| `finapp.risk.profile.rating` | gauge (parties per rating) | bucket | — |
| `finapp.risk.sweeper.enabled` | gauge (1 / 0) per schedule | kind (`monitoring`, `action`) | 0 in production |
| `finapp.risk.sweeper.claim.failed` | counter (N5 applied forward: every claim failure metered from the first task) | kind | any increase |

Spans: `RiskFlowScope` per step, no attributes. Reports (`GET /v1/operator/reports/risk/...`): decisions, alerts, cases,
velocity — audited reads, never a customer surface.

---

## 16. Milestones

| Milestone | Acceptance | Tasks |
|---|---|---|
| **M13.1 Foundations** | the module, floors, roles and vocabularies; policy versions as data under four eyes; the deterministic engine; the sealed assessment and its replay | `-001`…`-004` |
| **M13.2 Signals and profile** | signals born once from events; durable velocity windows proven against the journal; the customer risk profile and credit's seam version 2 | `-005`…`-007` |
| **M13.3 Synchronous decisioning** | the seams implemented on transfers, withdrawals (with `HOLD`), checkout payments and cross-border; the override under four eyes | `-008`…`-012` |
| **M13.4 Fraud detection** | account-takeover and abuse rules; alerts born once | `-013`…`-015` |
| **M13.5 Case management** | the case machine with concurrent updates, escalation and four-eyes dispositions; downstream actions through the owning domains; queues and the tipping-off control | `-016`…`-018` |
| **M13.6 AML monitoring** | the journal intake and reprocessing; monitoring rules and born-once alerts; the AML case and the regulatory report; rescreening on list updates | `-019`…`-022` |
| **M13.7 Proof and operations** | the proofs, the explanation door, meters, alerts, reports, runbook §9 | `-023`, `-024` |
| **M13.8 Collections (optional, C3)** | a collections case opened on default, facts only | `-025` |
| **M13.9 Exit** | the storm, the battery, the review | `P13-TST-001`, `P13-TST-002`, `P13-DOC-001` |

**Cut order:** `-025` (C3), then `-010`'s slice 2 (card `HOLD`), then `-022` (rescreening), then `-014`'s merchant-abuse
rules. **Never cut:** the sealed assessment and replay, the fail-safe as a recorded decision, the in-transaction
velocity windows, the withdrawal `HOLD`, born-once alerts, four-eyes dispositions, the tipping-off control, the journal
intake, the storm, the battery.

---

## 17. Backlog (small vertical slices)

Every item is elaborated with the gate's fields in [`BACKLOG.md`](BACKLOG.md) §Phase 13. Migration numbers are assigned in the order the waves land them (§8; Flyway runs with out-of-order off — the Phase 12
wave-D lesson), so a task's number is below every number of a later wave; the wave's board confirms them at its start.

| ID | Title | Milestone | Depends on | Migrations | DoD profiles |
|---|---|---|---|---|---|
| `P13-TSK-001` | The `risk` module boundary, floors, vocabularies and roles | M13.1 | Phase 12 `COMPLETE` | `risk V001`, identity `V023` | `DOD-ARCH`, `DOD-SEC` |
| `P13-TSK-002` | Risk policy versions as data: rule kinds, bands, fail-safe, four-eyes activation | M13.1 | `-001` | `risk V002` | `DOD-DOMAIN`, `DOD-API`, `DOD-SEC` |
| `P13-TSK-003` | The risk engine v1 (pure, deterministic, two-JVM) | M13.1 | `-002` | none | `DOD-DOMAIN`, `DOD-TEST` |
| `P13-TSK-004` | The sealed assessment, the decision, reason codes and replay | M13.1 | `-003`, `-006` | `risk V007` | `DOD-DOMAIN`, `DOD-API`, `DOD-SEC` |
| `P13-TSK-005` | Signal ingestion from the platform's events | M13.2 | `-001` | `risk V003` | `DOD-EVENT`, `DOD-SEC` |
| `P13-TSK-006` | Velocity windows in the caller's transaction, and their proof | M13.2 | `-001` | `risk V005` | `DOD-FIN`, `DOD-DOMAIN` |
| `P13-TSK-007` | The customer risk profile and credit's `CreditRiskSignal` version 2 | M13.2 | `-005` | `risk V006` | `DOD-DOMAIN`, `DOD-SEC` |
| `P13-TSK-008` | The transfer seams implemented, with the door's step-up advice | M13.3 | `-004`, `-007` | none | `DOD-FIN`, `DOD-API`, `DOD-SEC` |
| `P13-TSK-009` | The withdrawal risk seam and the `HELD` withdrawal (with its expiry) | M13.3 | `-004`, `-007` | payments `V033` | `DOD-FIN`, `DOD-API`, `DOD-SEC` |
| `P13-TSK-010` | The checkout payment risk seam (card and wallet); slice 2: authorise-and-hold | M13.3 | `-004`, `-007` | payments `V034` | `DOD-FIN`, `DOD-API`, `DOD-SEC` |
| `P13-TSK-011` | The cross-border seams implemented | M13.3 | `-004`, `-007` | none | `DOD-FIN`, `DOD-API` |
| `P13-TSK-012` | Decision override under four eyes, and the held withdrawal's release | M13.3 | `-009` | none (`V007`'s table; the release runs in T-act's shape from the approval door, keyed `risk.action:<override id>`, until `-017` folds it into the action machine) | `DOD-SEC`, `DOD-API`, `DOD-FIN` |
| `P13-TSK-013` | Account-takeover detection | M13.4 | `-005`, `-009`, `-015` | none | `DOD-DOMAIN`, `DOD-SEC` |
| `P13-TSK-014` | Payment, merchant and BNPL abuse rules | M13.4 | `-004`, `-010`, `-015` | none | `DOD-DOMAIN` |
| `P13-TSK-015` | Alerts born once: the alert table, raise, dedupe and suppression | M13.4 | `-001` | `risk V008` | `DOD-DOMAIN`, `DOD-EVENT` |
| `P13-TSK-016` | The case: lifecycle, the folding of alerts, investigator actions, concurrent updates, escalation, four-eyes dispositions | M13.5 | `-015` | `risk V009` | `DOD-DOMAIN`, `DOD-API`, `DOD-SEC` |
| `P13-TSK-017` | Downstream actions through the owning domains | M13.5 | `-016` | `risk V010` | `DOD-SEC`, `DOD-EVENT`, `DOD-API` |
| `P13-TSK-018` | Case and alert queues, reads, and the tipping-off control | M13.5 | `-016` | none | `DOD-API`, `DOD-SEC` |
| `P13-TSK-019` | Monitored transactions: the journal intake, the watermark, reprocessing | M13.6 | `-001` | `risk V004` | `DOD-DOMAIN`, `DOD-FIN` |
| `P13-TSK-020` | Monitoring rules and AML alerts | M13.6 | `-019`, `-015` | none | `DOD-DOMAIN` |
| `P13-TSK-021` | The AML case and the regulatory report artefact | M13.6 | `-016`, `-020` | `risk V011` | `DOD-DOMAIN`, `DOD-API`, `DOD-SEC` |
| `P13-TSK-022` | Rescreening on screening-list updates | M13.6 | `-005`, `-016` | `risk V012` | `DOD-DOMAIN`, `DOD-SEC` |
| `P13-TSK-023` | The risk proofs, the explanation door and replay | M13.7 | `-006`, `-019`, `-016` | none | `DOD-FIN`, `DOD-API` |
| `P13-TSK-024` | Operating risk: meters, alerts, reports, runbook §9, the activation gate | M13.7 | `-023` | none | `DOD-OBS`, `DOD-DOC` |
| `P13-TSK-025` | A collections case opened on default (C3, cut candidate) | M13.8 | `-016` | none | `DOD-DOMAIN` |
| `P13-TST-001` | The risk storm | M13.9 | `-001`…`-024` | none | `DOD-TEST` |
| `P13-TST-002` | The policy battery | M13.9 | `-003`, `-004` | none | `DOD-TEST` |
| `P13-DOC-001` | The Phase 13 exit review | M13.9 | `P13-TST-001`, `-002` | none | `DOD-DOC` |

**Parallel waves** (each task in its own worktree; a wave's board reserves its migration numbers): A `-001`; B `-002`,
`-005`, `-019`; C `-003`, `-006`, `-007`; D `-004`, `-015`; E `-008`, `-009`, `-010`, `-011`, `-016`, `-020`; F `-012`,
`-013`, `-014`, `-017`, `-018`, `-021`, `-022`; G `-023`, `-025`; H `-024`; I `P13-TST-002` then `P13-TST-001`, then
`P13-DOC-001`.

---

## 18. Exit criteria (summary — the gate is `PHASE_GATES.md` §5 Phase 13)

The six original bullets kept and made measurable, and per-area blocks for risk evaluation, policy versioning,
decision explainability, fraud workflows, AML monitoring, alert deduplication, case management, domain integrations,
multi-instance correctness, idempotency and recovery, security and privacy, auditability, observability, testing and
documentation — each naming its test and its task.

---

## 19. ADRs (next free number ADR-0107; all `Proposed` at the transition)

| ADR | Decision |
|---|---|
| ADR-0107 | The `risk` bounded context and module: one module, three capabilities, no money, every domain through ports |
| ADR-0108 | Synchronous risk evaluation in the caller's transaction: the decision model, the seam mapping, the fail-safe as a recorded decision, the latency budget, the override |
| ADR-0109 | Risk policy as versioned data: rule kinds in code, parameters as data, score bands, four-eyes activation, the deterministic engine and replay |
| ADR-0110 | The case model shared by fraud, ATO, merchant abuse, AML (and collections, C3): born-once alerts, one open case per subject and kind, concurrent updates by revision, four-eyes dispositions |
| ADR-0111 | AML transaction monitoring from the ledger journal, and the regulatory report as an artefact distinct from the investigation, behind a filing port |
| ADR-0112 | Downstream actions as keyed commands through the owning domains; risk never mutates authoritative state |

---

## 20. What Phase 13 must NOT implement

Machine-learned models; a real FIU, list or device-intelligence provider; a credential reset or any identity
mutation beyond the existing suspend/reinstate/revoke commands; a reversal of any transaction; collections operations
beyond the case opening (C3); write-off or provisioning; a customer-facing "risk status"; a risk service outside the
monolith; Redis or any cache as an authority; a spending limit product; a customer-facing dispute of a risk decision
(the existing support path and the override stand).

---

## 21. Risks

| Risk | Mitigation |
|---|---|
| The in-lock evaluation slows every money movement | the budget, the timeout behind a savepoint, the fail-safe as a decision, the storm's measurement; M3's split trigger watched |
| A fail-open default lets fraud through; a fail-closed one causes an outage | C1 is a policy field changed by activation, measured by `evaluation.unavailable`; production starts closed to judging (no policy) and opens by the activation gate |
| Alert floods | born-once alerts, folding into one open case, suppression under four eyes, the backlog gauge |
| Tipping-off through a side channel | the route guard, the event and log rules, the test that no customer response differs with a case open |
| Policy drift between fixtures and code | the policy door validates every parameter; the battery replays every version |

---

## 22. The first task

**`P13-TSK-001` — The `risk` module boundary, floors, vocabularies and roles** — `READY`, marked by this transition.
Mirrors `P12-TSK-002`: the module with its `V001` floor (`REVOKE ALL FROM PUBLIC`, no table), `RiskModuleIsolationTest`
(the edge to `platform` and `sharedkernel` only; every business module and `app` refused), the closed vocabularies
(`RiskOperation`, `RiskDecision`, `RuleKind`, `AlertKind`, `CaseKind`, `CaseStatus`, `Disposition`, `ActionKind`,
`SubjectKind`, `WindowKind`), `RiskAuditAction` and `RiskErrorCode` with their register rows, identity `V023` admitting
`RISK_ANALYST`, `RISK_OFFICER` and `AML_OFFICER` with the six permissions, `NoCustomerSurfaceReadsRiskTest`, the
`RiskFlowScope` and the sweeper property names, and the ports' interfaces declared empty of implementation. It is first
because every other task writes into this schema and composes through these ports, and the role split is the trust
decision the whole phase rests on. `P13-TSK-002` (policy versions) follows. *(Built 2026-10-22, `COMPLETE`: as written, with the roles a partition (§11's erratum), `RiskAuditAction` and `RiskErrorCode` grown per task rather than declared whole, and the ports `RiskActingParty`, `SessionAssurance`, `MonitoredLedger`, `RegulatoryFilingProvider`, `ScreeningClearances`, `PartyRescreening` and the six action ports; `RiskInputs` and `CaseOpener` are `-004`'s.)*

**`P13-TSK-002` — Risk policy versions as data** *(built 2026-10-23, `COMPLETE`, in wave B beside `-005` and `-019`: as
§12.2 and §4 describe it, with these readings settled by the build — several `PROPOSED` per operation numbered without
a gap and activation monotonic in version number (the entry's "ten proposals, ten versions"; no one-`PROPOSED` unique);
the content in six tables along the money line (the machine row money-free, `risk_policy`, `risk_rule`,
`risk_rule_parameter` with one typed row per declared parameter, `risk_policy_band` for the score, rating and fail-safe
bands, the history), every amount `RESTRICTED-FINANCIAL` on INSERT-only rows; `RuleParameters` held to each kind's
schema as declared once on `RuleKind` rather than restated per kind; `CREDIT_SIGNAL` carries no policy (refused
`422 risk.PolicyInvalid`); `risk.PolicyNotActive` and `risk.PolicyStale` `409`, `risk.PolicyInvalid` and
`risk.ReasonRequired` `422`; the gauge `finapp.risk.policy.active{operation}` with the version number as its value
(§15's `policy_version` tag answered by the value — a number per activation is not a bounded tag); the pin's A3-I1
re-read composed in `RiskPolicies.active` over the store's two primitives so its shape is hermetic
(`RiskPoliciesPinTest`) as well as live (`RiskPolicyDatabaseTest#anEvaluationBehindAnActivationPinsTheSuccessor`,
written and owed under C5 with the rest of the database tier); the C8 fixtures as risk's published test fixtures
(`RiskPolicyFixtures`), every one of the twenty-two kinds used by some fixture.)*

**`P13-TSK-003` — The risk engine v1** *(built 2026-10-23, `COMPLETE`, in wave C beside `-006` and `-007`: as §12.2
and §12.3 describe it, with the readings their as-built notes record — the decision the strictest of the band's and
every fired hard decision; the band at 0 deciding when nothing fires; velocity readings the current figures before the
movement, compared at-or-above; an absent reading never firing and saying so. The entry's names kept: `EvaluationInputs`
(not `RiskInputs`, which is `-004`'s port), `Verdict` (not `RiskEvaluation`, which is `-004`'s service), `RuleHit`,
`Score`, `ReasonCode`, `EvaluationFailed`, `failSafe(policy, amount)` and `failSafe(operation)`; `RuleEvaluation` a
package-private enum, one member per kind. Added beyond the entry: `Reading`, `FirstSeen`, `LastSeen`, `VelocityKey`,
`VelocityReading`, `Flow`, `Ratio`, `Amounts`, `MonitoringReadings` (the readings' value types) and
`CanonicalEvaluation` format 1 with a strict parser of the inputs form, so a sealed text can be replayed from the text
alone — the two-JVM digest parses the parent's rendered corpus rather than regenerating it. No row, no migration, no
route, no event, no meter: the engine has nothing of its own to persist or count. Found on the way, for the
coordinator: the `SubjectKind`/`VelocityDimension` gap between `-002` and `-006` (§12.4's note); `ROUND_AMOUNT` with
`modulus_minor = 0` passing the policy door (§12.2's note); a judged amount in a currency other than the policy's is
refused (`EvaluationFailed.CURRENCY_MISMATCH`) rather than compared — `-011`'s cross-border judged amount must be the
EUR figure, or the policy's currency must widen.)*

---

## 23. The transition's agenda carried in (from `PHASE_12_TO_13_TRANSITION.md` §9's owner list)

Items the Phase 12 → 13 gate left the owner that touch this phase: N1 (criterion 7, answered as C5); N5 (schedules'
unmetered claim failures — every Phase 13 schedule meters its claim failures from the first task); N13 (bucket labels,
not risk's); PI-002/PI-003 (Phase 6 pins and reasons, not risk's); the Phase 11 and 12 items the gate ratified stand.
`CreditRiskSignal`'s `NOT_ASSESSED` answer is retired by `-007`. *(Retired by `P13-TSK-007`, 2026-10-23: the composition
answers through `RiskCreditRiskSignal` at seam version 2; `NOT_ASSESSED` remains the answer only for a party risk has
not assessed, and every decision sealed under version 1 replays it.)*

---

## 24. The open questions, settled by default (C1–C10, 2026-10-22; "owner accepted default, review later")

OQ-1 what a velocity window counts when a movement is refused — nothing (§12.4). OQ-2 whether `REVIEW` opens a case
in the money transaction — yes, the case row is risk's and takes no money lock. OQ-3 whether the monitoring intake
reads events or the journal — the journal (§12.7). OQ-4 whether a device description is stored — only its hash, by
the handler. OQ-5 whether an `ALLOW` can be overridden — never. OQ-6 whether a case re-opens — never; a new case
references it. OQ-7 whether the report is the case — no (§12.9). OQ-8 whether kyc or risk owns rescreening — risk asks,
kyc screens. OQ-9 whether a merchant sees a risk outcome — the operation's generic refusal only. OQ-10 whether
collections is in — a thin case slice, cut candidate (C3).
