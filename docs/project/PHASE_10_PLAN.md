# Phase 10 — Credit Decisioning

Written by the Phase 9 → 10 transition (2026-10-07). Decisions in ADR-0084 (the credit bounded
context: data, assessment, policy and decision kept apart; credit moves no money and stops where
lending begins; the risk score's owner settled), ADR-0085 (credit data collection: provider-neutral
ports, normalised attributes, consent-gated access, encrypted evidence with declared retention,
freshness on the database clock), ADR-0086 (policy and model as versioned data: rules as rows over a
closed operator set, a deterministic evaluator with its own engine version, four-eyes activation,
effective periods, pinning), ADR-0087 (the decision: the request lifecycle, the frozen input
snapshot and its hash, the immutable decision with ordered reason codes, explanation and replay),
ADR-0088 (affordability and exposure: the arithmetic, the reserved exposure of current approvals,
per-party serialisation), ADR-0089 (underwriting: the manual review case, what a person may and may
not decide, four-eyes above a threshold). The machines are in
[`CREDIT_DECISIONING_LIFECYCLES.md`](../domain/CREDIT_DECISIONING_LIFECYCLES.md); the invariants are
`INV-CRD-01`…`12` in [`FINANCIAL_INVARIANTS.md`](../domain/FINANCIAL_INVARIANTS.md); the exit
criteria are `PHASE_GATES.md` §5 Phase 10, extended by this transition; the tasks are
`P10-TSK-001`…`021`, `P10-TST-001`…`002` and `P10-DOC-001` in [`BACKLOG.md`](BACKLOG.md).

Nothing in this document is built yet. Each task that builds a part corrects this plan where the
code teaches otherwise, and the Phase 10 exit review (`P10-DOC-001`) reads the whole of it against
the code. The owner's transition decisions G1–G11 (recorded in `BACKLOG.md` §Phase 10, and the
settled open points of `CREDIT_DECISIONING_LIFECYCLES.md`) are folded into the sections below.

---

## 1. Objective

Make credit decisions the platform can defend years later, to a regulator or to a declined
applicant: **a decision is a recorded, immutable fact, reproducible from a frozen snapshot of the
data it used, evaluated by a versioned policy and a versioned model through a deterministic engine,
carrying the ordered reason codes that explain it.** The phase builds the credit profile, the
collection of credit data from provider-neutral bureau and financial-data adapters under recorded
consent, the affordability and exposure assessments, a versioned scorecard, a versioned policy
engine, the decision and its explanation, manual underwriting, and the replay proof that re-derives
every past decision.

**Phase 10 moves no money.** It posts nothing to the ledger, holds nothing, disburses nothing. Its
output is a decision a later phase may rely on — Phase 11's loan application references a credit
decision, Phase 12's BNPL eligibility another — and the exposure an approval reserves until it
lapses or is consumed.

## 2. Why this phase is shaped by decisions already taken

- **`CREDIT_MODEL.md`** (Phase 0): credit data, credit profile, risk assessment, underwriting,
  decisioning and loan servicing are separate; a decision is reproducible from its inputs, policy
  version, model version, reason codes, outcome, timestamp and context; policy is never opaque,
  unversioned conditionals. This plan is that document made concrete.
- **`INV-CRD-01`…`04`** (catalogued at Phase 0, `Phase: 10`): reproducibility, immutability with
  reason codes, consent before bureau access, score ≠ decision. The transition adds eight (§6).
- **The consent gate** (Phase 2, ADR-0037): `ConsentPurpose` is closed and its javadoc reserves
  Phase 10's bureau access as a single new member with its own consent text — the transition
  decided two purposes (§3), so the reserved member becomes two when `P10-TSK-002` lands. The gate
  is read authoritatively inside the transaction that acts (`ConsentGateDatabaseTest`'s lesson).
- **The versioned four-eyes policy** (Phase 9, ADR-0075 §7; ADR-0080 §4): the pricing and corridor
  policies are versions with `PROPOSED → ACTIVE → RETIRED`, one `ACTIVE` per scope by partial
  unique, activation locking the active row to retire it beside its successor in one transaction,
  four-eyes at the domain and the `CHECK`. The credit policy and the scorecard take that shape.
- **Every window on the database clock** (`X-TSK-013`, the Phase 9 exit review): data freshness,
  decision validity, request expiry and every sweep permit are judged on `statement_timestamp()` /
  `DatabaseTime.now`, never an instance's clock.
- **The screening precedent** (Phase 9, ADR-0081): a provider asked with no connection held,
  requests born on a unique reference, a leaderless retry sweep over database-stamped permits, the
  decision applied under the row lock and conditional. Bureau and financial-data collection take
  that shape.
- **The seam with a reserved code** (Phase 9's `CrossBorderLimitCheck`, `CrossBorderRiskDecision`):
  a required parameter consulted in-lock, whose Phase 9 composition answers deterministically and is
  recorded. Phase 10's risk-signal input is such a seam (§3).
- **Phase 11 is lending** (`DELIVERY_PLAN.md` §Phase 11): loan application, offer, acceptance,
  disbursement and servicing are its, with `Loan Application` a distinct aggregate. Phase 10 must not
  build any of it (§17).

## 3. Bounded contexts and modules

**One new module, `credit`** — a bounded context with one consistency boundary: the decision and
the snapshot it was made from commit together, and the exposure an approval reserves is judged in
the same transaction that records it. Splitting data collection into a second module was weighed
and refused (ADR-0084 §2): its only consumer is credit's own snapshot, the evidence's security
boundary is a table-level grant inside one schema, and a second module would put the freshness and
consent judgements on the far side of a port from the decision that depends on them.

Inside `credit` the concepts stay apart, each with its own aggregate, table and lifecycle — the
`CLAUDE.md` distinctions made physical:

| Concept | What it is | Is NOT |
|---|---|---|
| **Credit Data** | Evidence retrieved from a source — a bureau record, a financial-data record — retained as received (encrypted) and normalised to attributes | a profile, a score |
| **Credit Profile** | The party's credit identity in this platform: the one row per party that every decision for the party serialises on, holding no figures of its own | the data, a score, a decision |
| **Credit Assessment** | The derived figures for one request — affordability, exposure, the scorecard's score — computed from a frozen snapshot | a decision |
| **Credit Score** | One derived figure: the scorecard model's points over the snapshot ("how likely to repay") | a bureau's own score (an attribute), a risk score |
| **Risk Score** | "How likely is this fraud or abuse?" — **owned by `risk` (Phase 13)**; credit consumes it through the `CreditRiskSignal` seam and records what it was given | credit's to compute |
| **Underwriting** | The assessment that weighs the figures against policy — the evaluator's run, or a person's review of a referral | the decision it produces |
| **Credit Policy** | A versioned set of rules as data, per credit product | code |
| **Credit Decision** | The recorded, immutable outcome with ordered reason codes, pinned versions and the snapshot hash | a score, an outcome of a loan |
| **Loan Application** | Phase 11's aggregate — it will reference a decision | Phase 10's |
| **Loan Servicing** | Phase 11's | Phase 10's |

**The risk score's owner, settled** (the question `GLOSSARY.md` §10 and `MODULE_ARCHITECTURE.md`
left to "Phase 10 or 13"): a risk score answers a fraud question, from fraud inputs, with a fraud
consequence. It is `risk`'s (Phase 13). Credit needs a risk signal as an input — a hard decline on a
confirmed fraud flag — so it declares the `CreditRiskSignal` port; Phase 10's composition answers
`NOT_ASSESSED` for every party, deterministically, and the snapshot records that answer and the
seam's version, so a decision made before Phase 13 replays identically after it. `MODULE_ARCHITECTURE.md`
moves `Risk Score` from `credit` to `risk`.

**Changed modules:**

| Module | Change |
|---|---|
| `consent` | Two new purposes, `CREDIT_BUREAU_ACCESS` and `FINANCIAL_DATA_ACCESS`, each with its consent text (`V003`) — the `ConsentPurpose` javadoc's single reserved Phase 10 member becomes two when `P10-TSK-002` lands |
| `identity` | Three permissions and two roles: `CREDIT_POLICY_ADMINISTER` and `CREDIT_INVESTIGATE` under `CREDIT_POLICY_OFFICER`; `CREDIT_UNDERWRITE` under `UNDERWRITER` (`V020`) |
| `kyc` | Nothing new: credit reads the party's verification standing through its `CreditPartyStanding` port, which `app` implements over the existing customer-standing port |
| `app` | Composition: the adapters, the progress and retry schedules, the doors, the reports, the meters |

**Build edges** (ADR-0084 §3): `credit` → `platform`, `sharedkernel` only. It reaches consent, party
standing and the risk signal through ports `app` implements — no edge to `consent`, `kyc`, `party`
or `ledger`. No module depends on `credit` in Phase 10; Phase 11's `lending` will, through credit's
published decision-read port. `CreditModuleIsolationTest` pins it.

**The ports**, all declared in `credit`: `CreditConsentGate` (whether a current lawful basis exists
for a source kind's purpose, read in the caller's transaction; `app` over the consent gate, `-002`),
`CreditPartyStanding` (the party's standing — `ACTIVE`, KYC `VERIFIED` — and the party facts a
snapshot records, age and residency country; `app` over the existing customer-standing port, `-008`,
`-014`), `CreditBureau` and `FinancialDataProvider` (the provider-neutral data ports, `-005`, `-007`),
`CreditRiskSignal` (the risk seam, answering `NOT_ASSESSED` in Phase 10, `-008`),
`PlatformCreditExposure` (the platform's outstanding credit, answering zero until Phase 11, `-010`),
and the published `CreditDecisions` (the decision-read port Phase 11's `lending` will use; declared
by `-016`, no consumer in Phase 10).

## 4. Aggregates and commands

| Aggregate | Owner | Commands | Arbiter |
|---|---|---|---|
| `CreditProfile` | `credit` | ensure (born once per party) | `UNIQUE (party_id)`, `ON CONFLICT DO NOTHING` |
| `CreditDataRequest` (bureau / financial data) | `credit` | request; record answer; record unavailable; retry | `request_reference UNIQUE`; the row lock and conditional |
| `CreditRecord` (bureau record / financial-data record) | `credit` | born once per answered request | `UNIQUE (data_request_id)` |
| `DecisionRequest` | `credit` | submit (keyed); progress (the versions pinned at `SUBMITTED → COLLECTING`); cancel (keyed); expire; abandon | the idempotency claim `credit.decision:<actorType>:<actorId>`; one open per (party, product) by partial unique; the row lock and conditional |
| `DecisionSnapshot` | `credit` | freeze (the first per request; a successor only when the deciding transaction finds reserved exposure changed, §12.7) | `UNIQUE (decision_request_id, sequence)`; one snapshot per evaluation |
| `CreditAssessment` | `credit` | compute (born once per snapshot) | `UNIQUE (snapshot_id)` |
| `PolicyEvaluation` | `credit` | evaluate (born once per assessment) | `UNIQUE (assessment_id)` |
| `CreditDecision` | `credit` | record (born once per request) | `UNIQUE (decision_request_id)`; the profile row lock for exposure |
| `CreditDecisionConsumption` | `credit` | none in Phase 10 — the born-once fact that a decision was consumed, Phase 11's loan its only writer (§12.4) | `UNIQUE (decision_id)` |
| `UnderwritingCase` | `credit` | open (born once per referral); assign; release; decide; second approval; refuse the second approval; close (its request expired or was abandoned undecided, §5) | `UNIQUE (decision_request_id)`; four-eyes `CHECK`; the request row then the case row locked |
| `CreditPolicyVersion` | `credit` | propose; approve (activate); reject | one `PROPOSED` and one `ACTIVE` per product by partial unique; the active row locked to retire |
| `ScorecardModelVersion` | `credit` | propose; approve (activate); reject | the same shape per model family |

Every command that changes state is idempotent: the keyed commands through `IdempotentExecutor`
(the request's submission and cancellation, the review acts, the policy and scorecard acts), the internal ones by a born-once unique and a
conditional transition — a retry, a duplicate event or a second instance finds the row already
moved and converges.

## 5. The lifecycles

The full machines, with every valid and invalid edge and the database rank that refuses the invalid
ones, are in [`CREDIT_DECISIONING_LIFECYCLES.md`](../domain/CREDIT_DECISIONING_LIFECYCLES.md). In
outline:

**DecisionRequest** — the application/input envelope (not a loan application):

```
SUBMITTED ──(data requests opened)──▶ COLLECTING ──(every required record present and fresh)──▶ READY
   │                                     │                                                    │
   │                                     └──(a required source unavailable past its deadline)─┤ (the policy's
   │                                                                                           │  declared fallback
   ▼                                                                                           ▼  decides: §12.6)
CANCELLED (by the applicant, before evaluation)        READY ──(snapshot frozen, assessed, evaluated)──▶ EVALUATED
EXPIRED (no decision within the request's validity)    READY ──(a record stale at the freeze)──▶ COLLECTING │
ABANDONED (closed by the platform, with a reason:                                                        │
  STANDING_LOST, CONSENT_WITHDRAWN)                                                                       │
                                              EVALUATED ──(APPROVE or DECLINE)──▶ DECIDED                │
                                              EVALUATED ──(REFER)──▶ IN_REVIEW ──(a person decides)──▶ DECIDED
```

Terminal: `DECIDED`, `CANCELLED`, `EXPIRED`, `ABANDONED`. `DECIDED` carries exactly one `CreditDecision`.
The request's validity is a declaration of its `CreditProduct` (§12.1), stamped as `expires_at` on the database
clock at submission. **The policy and scorecard versions are pinned on the request at `SUBMITTED → COLLECTING`**
(the claim-time precedent: the sources collected, and their maximum age, are the pinned policy's), re-read
`FOR SHARE` at evaluation and in the deciding transaction; an activation mid-request never changes them.
`READY → COLLECTING` is the one backward edge: a record found stale at the freeze (on the database clock) is
re-collected before any snapshot exists (§14 #9). A data request ending `CONSENT_WITHDRAWN` abandons its request
(`ABANDONED`, reason `CONSENT_WITHDRAWN`); a `RECEIVED` data request stays `RECEIVED`, and the consent gate is
re-read for every source kind at the freeze and in the deciding transaction, a withdrawal found there abandoning
the request the same way, nothing frozen or decided (§14 #31); a party whose standing is lost before the decision
abandons it (`STANDING_LOST`) — never `EXPIRED`, which means only that no decision came within the validity. Invalid:
any edge out of a terminal state; `COLLECTING → EVALUATED` (no snapshot, no evaluation);
`EVALUATED → COLLECTING` (a frozen snapshot is never re-collected — a stale one makes a new request);
`IN_REVIEW → EXPIRED` once a person has taken the case (an assigned case is decided by its person whatever the
request's validity — the person's deciding transaction checks the case, not the request's expiry, so a taken case is
never stuck; only an unassigned `OPEN` case expires with its request); `CANCELLED` after `EVALUATED` (the
evaluation is a fact; withdrawing then is a decision the applicant receives, not a cancellation).

**CreditDataRequest**: `REQUESTED → RECEIVED | UNAVAILABLE | CONSENT_WITHDRAWN`; `UNAVAILABLE →
REQUESTED` (a retry, a new attempt under the same reference) until the source's deadline;
`UNAVAILABLE → CONSENT_WITHDRAWN` (a retry is gated like the first ask); `RECEIVED` is terminal and
born-once-backed by its `CreditRecord`. Each source kind's deadline and retry cadence are
configuration, stamped on the data request at its birth, so a configuration change never moves an
open request's deadline.

**UnderwritingCase**: `OPEN → ASSIGNED → DECIDED` or `ASSIGNED → AWAITING_SECOND → DECIDED` (an
approval above the product's four-eyes threshold); `ASSIGNED → OPEN` (released by its assignee);
`AWAITING_SECOND → ASSIGNED` (the second approver disagrees and refuses the second approval, with a
reason — the case goes back to its first underwriter, who decides again); `→ CLOSED` when its request
closes undecided — from `OPEN` when the request expires or the sweep finds the party's standing lost,
and from `ASSIGNED` or `AWAITING_SECOND` only when the person's own deciding transaction abandons the
request (standing lost, consent withdrawn). Assignment locks the request, then the case, so an
assignment and an expiry serialise (exactly one of `ASSIGNED`, `EXPIRED`). A person may decide
`APPROVED` or `DECLINED` with at least one reason code; a person's approval is bounded by the
evaluation's approved amount and by the exposure limit re-read under the profile lock — beyond it
`422 credit.ExposureLimitExceeded`, nothing recorded, and the person decides again; a person may
**not** approve a request whose evaluation included a hard decline (ADR-0089 §2) — the evaluator
never refers such a request.

**Policy and model versions**: `PROPOSED → ACTIVE → RETIRED`, `PROPOSED → REJECTED`; the pricing
policy's shape.

## 6. Financial and decision invariants Phase 10 must preserve

The four catalogued at Phase 0, made measurable, and eight new ones (catalogued by this transition,
each `**Phase:** 10`):

| ID | Statement | Rank |
|---|---|---|
| `INV-CRD-01` | Replaying a decision's snapshot against its pinned policy, model and engine versions reproduces the identical outcome and ordered reason codes | `DOMAIN` + the replay proof |
| `INV-CRD-02` | A recorded decision is never modified; every adverse decision carries reason codes sufficient for an adverse-action explanation | `DB-PRIVILEGE` + `DB-CONSTRAINT` + trigger |
| `INV-CRD-03` | No external credit data is retrieved without a recorded, current lawful basis — checked in the transaction that opens the request, at every retry, and again in the one that records the answer | `DOMAIN` + the gate |
| `INV-CRD-04` | Score, risk signal, assessment, evaluation and decision are separately modelled and recorded | `DOMAIN` + schema |
| `INV-CRD-05` *(new)* | A policy or model version is immutable once proposed (its rules and bands are born with it and no writer changes them — a correction is a rejection and a new proposal); at every instant at most one version is `ACTIVE` per product (per model family), and which one was active at any past instant is answerable from the rows | `DB-CONSTRAINT` + trigger |
| `INV-CRD-06` *(new)* | A decision request has at most one decision; each of its snapshots has at most one assessment and one evaluation, and the decision names the snapshot it was made from | `DB-CONSTRAINT` (born-once uniques) |
| `INV-CRD-07` *(new)* | A decision's snapshot is complete and sealed: every attribute any rule, the scorecard or the arithmetic read is in it with its provenance, and its SHA-256 over the canonical form is stored and re-verified | `DOMAIN` + replay |
| `INV-CRD-08` *(new)* | Stale data never decides: every record a snapshot uses was retrieved within the pinned policy's declared maximum age, judged on the database clock at the freeze | `DOMAIN` (DB clock) |
| `INV-CRD-09` *(new)* | Decisions for one party are serialised: the exposure an approval reserves is judged under the party's profile row lock, so concurrent approvals never together exceed the policy's exposure limit | `DOMAIN` (lock-then-look: the profile row `FOR UPDATE` first in every deciding transaction, the reserved exposure summed under it) |
| `INV-CRD-10` *(new)* | A provider's unavailability or partial answer never becomes an approval: it follows the policy's declared fallback (refer or decline), recorded as such | `DOMAIN` |
| `INV-CRD-11` *(new)* | A person's decision is never their own case's second approval, never overrides a hard decline, and always carries reason codes | `DOMAIN` + `DB-CONSTRAINT` |
| `INV-CRD-12` *(new)* | Credit money arithmetic (income, expenditure, repayment, exposure) is exact decimal in one explicit currency per assessment, rounded once at declared points | `DOMAIN` + `NoFloatingPointMoneyRulesTest` |

**Restated** for Phase 10 (their statements unchanged, their Phase 10 subject named in each task):
`INV-HIST-04` (a pinned version never changes under a decision), `INV-IDEM-01`…`03` (keyed
commands), `INV-CON-02` (racing requests, one effect), `INV-AUD-01`…`04` (every privileged act and
every bureau access audited; four-eyes), `INV-CNS-01` (the gate), `INV-CNS-02` (consent history
append-only) — that a purpose is never removed is ADR-0037's rule, not an invariant —
`INV-MON-01`…`02` (no floating point; explicit currency), `INV-LIFE-03` (an unknown provider answer
is a modelled state). **128 invariants** platform-wide (120 + eight).

## 7. Multi-instance architecture

Every instance runs every door, sweep and consumer. Correctness rests on PostgreSQL alone.

| Contention | Arbiter | Loser's answer | Proven by |
|---|---|---|---|
| Ten submissions under one key | `IdempotentExecutor` claim `credit.decision:<actorType>:<actorId>` | the same response replayed; `409` while in progress | `-014` |
| Two keys, one party and product, both open | partial `UNIQUE (party_id, product) WHERE status IN (open states)` | `409 credit.DecisionRequestOpen` naming the open request | `-014` |
| Ten instances progressing one request | the progress sweep's `FOR UPDATE SKIP LOCKED` claim with a database-stamped permit; each step's conditional edge re-judged under the row lock | the step already taken; nothing written | `-015` |
| A bureau answer arriving twice (retry, duplicate delivery) | `request_reference UNIQUE`; `UNIQUE (data_request_id)` on the record; the conditional `REQUESTED → RECEIVED` | one record; the duplicate's evidence kept as a duplicate, never a second record | `-006` |
| Provider success with the response lost | the provider dedupes by our reference; the retry asks again under the same reference and records the first answer | one pull counted at the provider | `-006` |
| Consent withdrawn while a pull is in flight | the gate re-read in the recording transaction | `CONSENT_WITHDRAWN`; the answer's payload not retained | `-006` |
| Freeze vs a fresher record arriving | the freeze reads the records under the request's row lock and fixes the snapshot by its unique | one snapshot; a later record belongs to no snapshot | `-008` |
| Concurrent decisions for one party (two products, or a decision beside a manual approval) | the party's `credit_profile` row `FOR UPDATE` in the deciding transaction; reserved exposure summed under it | serialised; the second sees the first's reservation | `-016`, `-018` |
| Policy activation racing a decision | the versions are pinned on the request at `SUBMITTED → COLLECTING` and read `FOR SHARE` at evaluation and in the deciding transaction; activation locks the active row `FOR UPDATE` to retire it | the decision keeps the versions it pinned (`INV-HIST-04`); never a retired-but-unpinned mix | `-012`, `-015`, `-016` |
| Ten approvers of one policy (or scorecard) proposal | the version row `FOR UPDATE`, conditional on `PROPOSED`; one `PROPOSED` per product (family) | one activation; nine `PolicyStale` | `-011`, `-012` |
| Two underwriters taking one case | the request row then the case row `FOR UPDATE`, conditional `OPEN → ASSIGNED` under an `IN_REVIEW` request | one assignment; the other `409 credit.CaseTaken` | `-018` |
| An unassigned case's assignment vs its request's expiry | both take the request row then the case row; the assignment admitted only while the request is `IN_REVIEW`, the expiry only while the case is `OPEN` | exactly one of `ASSIGNED`, `EXPIRED` | `-018` |
| Ten second approvers of one case | the deciding transaction's locks (profile, request, case), conditional on `AWAITING_SECOND`; the decision's `UNIQUE (decision_request_id)` | one decision; the others find the case `DECIDED` | `-018` |
| Request expiry vs decision at the boundary | complementary conditionals on the database clock (`expires_at > statement_timestamp()` to decide, `<=` to expire) | exactly one of `DECIDED`, `EXPIRED` | `-016` (the expiry side against every earlier step, `-015`) |
| Replay proof on every instance | one `REPEATABLE READ` read-only snapshot per reading, rolled back | every instance computes the same verdict | `-019` |

**The lock order** (one `DISTRIBUTED_EXECUTION.md` §3 row, followed by every credit writer):
(1) the party's `credit_profile` row `FOR UPDATE` — deciding transactions only; (2) the
`decision_request` row `FOR UPDATE`; (3) the `underwriting_case` row; (4) the data requests of the
request, by id; (5) the pinned policy and model versions `FOR SHARE`. After that only inserts
(snapshot, assessment, evaluation, decision, events, audit). The policy administration takes only
version rows (its own order: the proposal, then the active row). No credit transaction takes a lock
outside `credit`; the consent and standing reads are plain reads under `READ COMMITTED` in the
acting transaction (the gate's authoritative read). **No cycle:** profile → request → case → data
requests is the only order any writer takes, and the progress sweep, which starts from a request,
takes the profile row only in the deciding step, and then first — it releases its claim
transaction and opens the deciding one profile-first. The case's assignment, release and refusal of a
second approval take (2) then (3) — never the case alone — so they serialise with the request's expiry.

**Schedules** (each a `SmartLifecycle` on `scheduleWithFixedDelay`, off in test contexts, leaderless,
its `…sweeper.enabled` gauge, joining `NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS`
twenty → twenty-two): `CreditDecisionProgressSchedule` (requests due a step; expiry) and
`CreditDataRetrySchedule` (data requests `UNAVAILABLE` or `REQUESTED` past their permit). Both take
work oldest-permit-first in one `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED)` statement
that stamps a new permit from `statement_timestamp()`; a step that cannot act re-stamps rather than
holding the page (the P9-TST-001 starvation lesson: due work that will never act must not starve due
work that will). Each source kind's collection deadline and retry cadence are configuration, stamped on
the data request at its birth (`-006`).

**Advisory namespace `10`**: the policy and model administration's writers (`hashtext(product)` /
`hashtext(family)`), blocking, so ten proposers leave one proposal — credit's own lock (the pricing policy
arbitrates with its partial unique alone; credit keeps that unique as the backstop).
Registered in `DISTRIBUTED_EXECUTION.md` §3 by `-011`, its first writer (the scorecard administration);
`-012` extends it to the policy.

**Isolation** is `READ COMMITTED` plus row locks, conditionals and uniques (ADR-0039). The replay
proof and the reports run in one `REPEATABLE READ` snapshot. **Recovery** is from rows: a crash at
any point leaves the request in a state the sweep re-drives, and every step is idempotent.

## 8. Data architecture

Schema `credit`, owned by the module (Flyway `credit/V001…`), the application role granted the
minimum per table:

| Table | Rows | Writable by the application |
|---|---|---|
| `credit_profile` | one per party | `INSERT` only (the lock target; nothing to update) |
| `decision_request` (+ `decision_request_event`) | the envelope; its pinned policy and scorecard versions (set once, at `SUBMITTED → COLLECTING`); its history | `INSERT`; `UPDATE (status, permit, pins, …)` through the machine trigger, the pins written once; history append-only |
| `data_request` (+ `data_request_attempt`) | per source per request; `decision_request_id NOT NULL`, its foreign key added by `-014`'s migration (the table precedes `decision_request`); the deadline and retry cadence stamped at birth | `INSERT`; `UPDATE (status, permit, attempts)` through the machine trigger |
| `credit_record` | the normalised attributes of an answered request | `INSERT` only |
| `credit_evidence` | the raw answer, encrypted (envelope as the settlement file's), `retain_until` | `INSERT` only; `SELECT` revoked from the application, read only through the evidence-read door's definer function (`-017`) |
| `decision_snapshot` | the canonical attributes JSON, its SHA-256, the pinned versions; `sequence` per request (1, then a successor only on an exposure change, §12.7); `decision_request_id NOT NULL`, its foreign key added by `-014`, the pinned-version keys by `-011` and `-012` | `INSERT` only |
| `credit_assessment` | affordability, exposure, score, each with its inputs' references | `INSERT` only |
| `policy_evaluation` (+ `policy_evaluation_rule`) | outcome, the triggered rules in order | `INSERT` only |
| `credit_decision` (+ `credit_decision_reason`) | outcome, approved amount/term, reason codes in order, `decided_by`, `valid_until`, the snapshot hash | `INSERT` only — **never `UPDATE` or `DELETE` for any role** (`INV-CRD-02`); it has no consumption column |
| `credit_decision_consumption` | the born-once fact that an approved decision was consumed — `UNIQUE (decision_id)`; created empty by `-016`, written only by Phase 11's loan | `INSERT` only |
| `underwriting_case` (+ `underwriting_case_event`) | the review | `INSERT`; `UPDATE` through the machine trigger |
| `credit_policy_version` (+ `credit_policy_rule`, `credit_policy_event`) | policy versions; rules as rows | `INSERT`; `UPDATE (status, …)` through the machine trigger; rules born with their version in its proposing transaction and immutable for every writer from insert |
| `scorecard_model_version` (+ `scorecard_band`, event) | model versions; points tables as rows | as the policy |
| `reason_code` | the closed catalogue: code, category, customer text, adverse flag | migration-seeded; `SELECT` only |

**Classification** (`DATA_CLASSIFICATION.md`): `credit_evidence.payload` and every attribute value
are `RESTRICTED-FINANCIAL` (bureau data is financial PII); party references `CONFIDENTIAL`;
reason-code catalogue `INTERNAL`. Every new column classified by the task that adds it, under
`ColumnClassificationTest`.

**Retention** (ADR-0085 §5): raw evidence carries `retain_until` = retrieval + the product's
declared evidence retention (the plan's default: 25 months, a configuration of the product, not of
the code); the normalised attributes inside a decision snapshot are retained for the decision's
explanation life. **No purge runs in Phase 10** — crypto-shredding and purge are Phase 15's
operational-readiness work, recorded as a debt row with that owner; Phase 10 makes the deadline a
stored, queryable fact so the purge has something exact to act on.

## 9. API architecture

All under `/v1`, closed request bodies (`@ClosedBody`), the platform's error contract, the
`Idempotency-Key` header on every keyed command.

| Route | Who | Command | Idempotency | Async |
|---|---|---|---|---|
| `POST /v1/me/credit/decision-requests` | the customer (MFA-assured session) | submit for self: product, requested amount, term, declared income/expenditure | keyed (`credit.decision:CUSTOMER:<id>`) | `202` with the request id; the decision arrives later |
| `GET /v1/me/credit/decision-requests/{id}` | the customer, own requests only | status; when decided, the outcome and the **adverse-action reasons' customer texts** | read | — |
| `POST /v1/me/credit/decision-requests/{id}/cancellation` | the customer | cancel before evaluation | keyed | sync |
| `GET /v1/me/credit/profile` | the customer | the profile's summary: which sources are on file, their retrieval dates, current decisions | read | — |
| `GET /v1/operator/credit/decisions/{id}/explanation` | `CREDIT_INVESTIGATE` | the full explanation: snapshot attributes with provenance, versions, triggered rules, reason codes (audited read) | read | — |
| `POST /v1/operator/credit/decisions/{id}/replay` | `CREDIT_INVESTIGATE` | replay now: `IDENTICAL` or `DIVERGED` with what differs (audited, reason required) | none — no state | sync |
| `POST /v1/operator/credit/records/{id}/evidence-read` | `CREDIT_INVESTIGATE` | the raw evidence of one credit record, through the definer function, a reason required (audited `credit.EvidenceRead`) | none — no state but its audit | sync |
| `GET /v1/operator/credit/review-cases?status=` · `POST …/{id}/assignment` · `POST …/{id}/decision` · `POST …/{id}/second-approval` (approve, or refuse with a reason) | `CREDIT_UNDERWRITE` | the manual review | keyed (`credit.review:EMPLOYEE:<id>`) | sync |
| `POST /v1/operator/credit/review-cases/{id}/release` | `CREDIT_UNDERWRITE` | the assignee releases a taken case back to the queue (audited `credit.ReviewCaseReleased`) | keyed (`credit.review:EMPLOYEE:<id>`) | sync |
| `POST /v1/operator/credit/policies` · `POST …/{versionId}/approval` · `POST …/{versionId}/rejection` | `CREDIT_POLICY_ADMINISTER` | propose (the full rule set as a body), approve (a second person), reject with reason | keyed (`credit.policy:EMPLOYEE:<id>`) | sync |
| the same three for `/v1/operator/credit/scorecards` | `CREDIT_POLICY_ADMINISTER` | the model versions | keyed (`credit.scorecard:EMPLOYEE:<id>`) | sync |
| `GET /v1/operator/credit/policies?product=&at=` | `CREDIT_INVESTIGATE` | the version active at an instant (`INV-CRD-05`) | read | — |
| `GET /v1/operator/reports/credit/{outcomes,reasons,sources}` | `CREDIT_INVESTIGATE` | rates by policy version, reason-code distribution, source availability (audited reads) | read | — |

**Error semantics** (`ERROR_CONTRACT.md`, each code catalogued by its task): `409
credit.DecisionRequestOpen`, `409 credit.RequestNotCancellable`, `409 credit.CaseTaken`, `409
credit.PolicyStale`, `409 credit.ProposalPending`, `422 credit.ProductNotOffered`, `422
credit.AmountOutOfRange`, `403 credit.ConsentRequired` (the purpose named), `403
credit.SelfApprovalRefused`, `422 credit.HardDeclineNotOverridable`, `422 credit.ReasonRequired`,
`422 credit.PolicyIncomplete`, `422 credit.ScorecardInvalid`, `422 credit.ExposureLimitExceeded`,
`404` for another party's request (never `403`: existence is not disclosed). A source in a currency
other than the product's is **not** an error: it is normalised as partial data — the attribute
`ABSENT` with the recorded `CURRENCY_NOT_SUPPORTED` marker — and never converted (§12.3).

**Customer responses never carry** a bureau's raw data, an internal score, a rule's threshold or a
risk signal — only the outcome, the approved amount and term, the decision's validity, and the
customer texts of the adverse reasons in order (`INV-CRD-02`'s explanation, `GLOSSARY.md`'s
"412 is not an explanation").

## 10. Event architecture

Through the transactional outbox, in the state change's transaction, the platform envelope (event
id, type, aggregate id, event version, schema version, occurred-at from the database, producer
`credit`, correlation, causation). No Phase 10 consumer exists outside `credit`; Phase 11 will
consume `CreditDecisionRecorded`. Credit consumes no event in Phase 10.

| Event | Aggregate | Emitted when | Carries | Not |
|---|---|---|---|---|
| `CreditDecisionRequested` | DecisionRequest | submitted | request id, party, product, requested amount/term | declared income |
| `CreditDataCollected` | CreditDataRequest | a record is born | request id, source kind, provider code, retrieved-at | any attribute or payload |
| `CreditDataUnavailable` | CreditDataRequest | a source misses its deadline (once, by the retry sweep, a conditional flag) | source, attempts | — |
| `CreditAssessmentCreated` | CreditAssessment | the assessment is born | request id, snapshot hash, model version | figures |
| `ManualReviewRequired` | UnderwritingCase | a case opens | request id, case id, referral reason codes | attributes |
| `CreditDecisionRecorded` | CreditDecision | the decision is born | request id, decision id, outcome, approved amount/term, valid-until, pinned versions, snapshot hash, reason codes | attributes, scores |
| `CreditDecisionRequestClosed` | DecisionRequest | cancelled, expired or abandoned | the closing status and, when abandoned, its reason (`STANDING_LOST`, `CONSENT_WITHDRAWN`) | — |
| `CreditPolicyVersionActivated` / `ScorecardModelVersionActivated` | the version | activated | version id, product/family, effective-from, predecessor | rules |

**Evaluated and refused:** `UnderwritingStarted` (the case's assignment is an internal workflow
step, audited, not an integration fact anyone consumes — and likewise its release, the refusal of a
second approval and its closure); **`CreditDecisionUpdated`** — a decision is
never updated (`INV-CRD-02`); a person's decision on a referral is *the* decision, recorded once, and
a later change of mind is a new request. Each event carries `eventVersion` 1 and evolves by adding
optional fields only; a breaking change is a new type.

## 11. Security and privacy

**Data.** Bureau and financial-data payloads are encrypted at rest with the platform's envelope
encryption (the settlement file's ADR-0066 scheme, its own key purpose `credit-evidence`); the
application role cannot `SELECT` them; an operator reads normalised attributes through the audited
explanation door, and the raw payload only through the evidence-read door
(`POST /v1/operator/credit/records/{id}/evidence-read`), which calls a definer function with a
reason, audited `credit.EvidenceRead`. No attribute, score, threshold or reason text
appears in a log line, a metric tag, a span attribute, an event or an exception message; records'
`toString` names identifiers only (`lombok.config` opt-in). The `INV-RAIL-03` needle walk extends to
credit's doors.

**Authorization.**

| Permission | Role | Grants |
|---|---|---|
| `CREDIT_POLICY_ADMINISTER` | `CREDIT_POLICY_OFFICER` | propose, approve, reject policy and scorecard versions (never one's own proposal) |
| `CREDIT_INVESTIGATE` | `CREDIT_POLICY_OFFICER` | explanation, evidence read, replay, reports, the policy-at-instant read |
| `CREDIT_UNDERWRITE` | `UNDERWRITER` | the review queue: take, release, decide, second-approve or refuse the second approval (never one's own case's first decision) |

Customer routes are owner-scoped in every query (a request id from another party is `404`), require
an MFA-assured session for submission, and the party's verification standing (`ACTIVE`, KYC
`VERIFIED`) is checked in-transaction. `RoutePermissionRegisterTest` and `RoleNameTest` carry every
route and grant; every route has a negative test.

**Consent** (`INV-CRD-03`, through the `CreditConsentGate` port): the gate checked when the data
request is opened (the purpose for the source kind), at every retry, and again when the answer is
recorded; a withdrawal before the answer is recorded yields `CONSENT_WITHDRAWN`, the payload
discarded unread and the evidence row recording only that a response arrived. A `RECEIVED` data
request stays `RECEIVED`: the gate is re-read for every source kind at the freeze and in the
deciding transaction, and a withdrawal found there abandons the request (`ABANDONED`,
`CONSENT_WITHDRAWN`), nothing frozen or decided. A decision request whose consent is absent is
refused at submission `403 credit.ConsentRequired`, before any provider is asked.

**Audit** (`AUDITABLE_ACTIONS.md`, each act in its own transaction, losers record nothing):
`credit.BureauDataRequested`, `credit.FinancialDataRequested` (a provider asked — the access is the
act, `INV-AUD-01`), `credit.DecisionRequestCancelled` (the customer's cancellation, the
`fx.QuoteCancelled` precedent; no reason required), `credit.DecisionRecorded`,
`credit.ReviewCaseAssigned`, `credit.ReviewCaseReleased`, `credit.ReviewDecided` (reason required),
`credit.ReviewSecondApproval`, `credit.ReviewSecondApprovalRefused` (reason required),
`credit.PolicyVersionProposed` / `credit.PolicyVersionActivated` / `credit.PolicyVersionRejected`
(reason required), `credit.ScorecardVersionProposed` / `credit.ScorecardVersionActivated` /
`credit.ScorecardVersionRejected` (reason required), `credit.ExplanationRead`,
`credit.EvidenceRead` (reason required), `credit.DecisionReplayed` (reason required),
`credit.ReportRead`. Submission is not an audit record: the request's row, history and event are its
trail, and the access it leads to is audited.

## 12. The decision model

### 12.1 Credit products

A closed enumeration `CreditProduct` (ADR-0084 §6), each declaring its currency, its amount and term
bounds, its four-eyes threshold, its request validity, its decision validity and its evidence
retention: `PERSONAL_LOAN` (EUR, 500.00–25,000.00, 6–60 months) and `CREDIT_LINE` (EUR,
250.00–5,000.00, revolving, term absent), each with a request validity of 7 days and a decision
validity of 30 days, both judged on the database clock. Phase 11 builds products on these; a product
is a reviewed code change with its migration, never a string.

### 12.2 Attributes and the snapshot

Every input the engine may read is a **credit attribute** with a code from the closed
`CreditAttributeCode` vocabulary (e.g. `BUREAU_EXTERNAL_SCORE`, `BUREAU_ACTIVE_ACCOUNTS`,
`BUREAU_DELINQUENCIES_24M`, `BUREAU_DEFAULTS_72M`, `BUREAU_INSOLVENCY_FLAG`,
`BUREAU_MONTHLY_OBLIGATIONS`, `BUREAU_TOTAL_BALANCE`, `FINDATA_MONTHLY_INCOME`,
`FINDATA_MONTHLY_COMMITTED_EXPENDITURE`, `DECLARED_MONTHLY_INCOME`,
`DECLARED_MONTHLY_EXPENDITURE`, `PARTY_AGE_YEARS`, `PARTY_RESIDENCY_COUNTRY`,
`PLATFORM_OUTSTANDING_CREDIT` (*added by `P10-TSK-010`*: §12.4's platform term), `PLATFORM_RESERVED_EXPOSURE`, `RISK_SIGNAL`, plus the recorded markers `SOURCE_UNAVAILABLE` and
`CURRENCY_NOT_SUPPORTED`), a typed value (integer, decimal-with-currency, boolean, code) and a
provenance (`credit_record` id and source, or `DECLARED`, or the port and its version).
**The snapshot** is the canonical JSON of the attributes sorted by code, every value in a fixed
textual form (money as minor units + currency), plus the requested product, amount, term and the
pinned versions; its SHA-256 is stored. A rule, the scorecard or the arithmetic reading an attribute
the snapshot lacks is an evaluation error, never a default (`INV-CRD-07`) — a missing optional
attribute is a value (`ABSENT`) the policy reasons about explicitly.

### 12.3 Affordability (ADR-0088 §1)

In the product's currency, exact decimal (`Money`, `ExchangeRate`-free — no conversion: a source
in another currency is normalised as partial data, the attribute `ABSENT` with the recorded
`CURRENCY_NOT_SUPPORTED` marker — an attribute value, not an error code):

```
income      = min(verified income, declared income)          -- verified = financial data where present
expenditure = max(verified committed expenditure, declared expenditure)
obligations = bureau monthly obligations
repayment   = annuity(requested amount, term, policy.assessment_rate)   -- stress rate, not a product price
disposable  = income − expenditure − obligations − repayment
affordable  ⇔ disposable ≥ policy.minimum_disposable
```

The annuity uses `BigDecimal` at scale 10 with `HALF_EVEN`, rounded once to minor units `HALF_UP`
at the end; the formula, the scale and the rounding are the engine's (`engine_version`), the rate
and the minimum the policy's. A revolving `CREDIT_LINE` assesses `repayment = limit ×
policy.minimum_payment_ratio`. Property tests: monotone in income, amount and rate; exact on worked
cases; no floating point (`NoFloatingPointMoneyRulesTest`).

### 12.4 Exposure (ADR-0088 §2)

```
exposure = bureau total balance                                    -- external, from the snapshot
         + platform outstanding credit                             -- PlatformCreditExposure port: Phase 10's
                                                                      composition answers zero, recorded (no loans yet)
         + reserved exposure                                       -- Σ approved amount of this party's decisions
                                                                      APPROVED, valid_until > now (DB clock), with no
                                                                      credit_decision_consumption row
         + requested amount
within   ⇔ exposure ≤ policy.max_exposure
```

`reserved exposure` is read **under the party's profile row lock in the deciding transaction**
(`INV-CRD-09`): two decisions for one party serialise, and the second sees the first's reservation.
It is recorded in the snapshot as `PLATFORM_RESERVED_EXPOSURE` — but because the snapshot is frozen
earlier, the deciding transaction **re-reads** it under the lock and, if it differs, re-evaluates the
exposure rule against a successor snapshot (§12.7). Consumption (Phase 11's loan) and lapse end a
reservation. A lapse is a comparison with the database clock, not a state. **Consumption is a
separate born-once fact**, `credit_decision_consumption` (`UNIQUE (decision_id)`), which `-016`
creates empty and only Phase 11 writes — never a column on `credit_decision`, which no role ever
updates (`INV-CRD-02`).

### 12.5 The scorecard (ADR-0086 §3)

A **model version** is a points table as rows: per attribute, ordered bands (`[lower, upper)` or a
code set) each with integer points, plus a base; score = base + Σ points of the band each attribute
falls in, an `ABSENT` attribute taking its declared absent band. Integer arithmetic only. The model
family `RETAIL_SCORECARD`; version 1 seeded **as a proposal** by the task that builds it and
activated by two persons in the suites (no model is migration-activated — the rule-set precedent).

### 12.6 The policy and the evaluator (ADR-0086 §1–2)

A **policy version** per product is a list of rules as rows: `(ordinal, rule_code, attribute or
derived figure, operator, operand, effect, reason_code)`. Operators are closed: `LT, LE, GT, GE, EQ,
NE, IN, NOT_IN, IS_ABSENT, IS_PRESENT`. Derived figures: `SCORE`, `DISPOSABLE_INCOME`,
`AFFORDABLE`, `EXPOSURE`, `EXPOSURE_HEADROOM`. Effects: `HARD_DECLINE`, `DECLINE`, `REFER`,
`CAP_AMOUNT` (the operand a ceiling). Plus the policy's parameters (assessment rate, minimum
disposable, minimum payment ratio, max exposure, max data age per source kind, the fallback for an
unavailable source — `REFER` or `DECLINE`, never approve — and the auto-approval ceiling).

**The evaluator** (pure, `engine_version` 1): every rule evaluated in ordinal order; the outcome is
the most severe effect triggered (`HARD_DECLINE > DECLINE > REFER > APPROVE`); the approved amount is
`min(requested, every CAP_AMOUNT triggered, the auto-approval ceiling)` — an approval below the
request carries the cap's reason code (the auto-approval ceiling, a policy parameter rather than a rule, carries
the catalogue's `CRD-AUTO-APPROVAL-CEILING`); reason codes are the triggered rules' codes in ordinal order,
deduplicated keeping the first. An adverse outcome with no reason code is unrepresentable (the
`CHECK` and the domain). Changing the evaluator's semantics is a new `engine_version`, and the old
one stays in the code for replay (`INV-CRD-01`).

**A source unavailable past its deadline** (`INV-CRD-10`): the request reaches `READY` with the
source's attributes `ABSENT` and a recorded `SOURCE_UNAVAILABLE` attribute; the policy's fallback
rule fires (`REFER` or `DECLINE`, the policy's declared choice, with reason code
`CRD-SOURCE-UNAVAILABLE`). An approval can never arise from missing data, because every approving
path requires the source's attributes `IS_PRESENT` — asserted at proposal: a policy without the
fallback rule for every source kind it reads is refused (`422 credit.PolicyIncomplete`).

### 12.7 Recording the decision (ADR-0087 §5)

The deciding transaction, profile-first: lock the profile; lock the request (`EVALUATED`, not
expired on the database clock); re-read the party's standing and the consent gate for every source
kind (lost standing → `ABANDONED`, `STANDING_LOST`; a withdrawal → `ABANDONED`, `CONSENT_WITHDRAWN`;
nothing decided); read the pinned versions `FOR SHARE`; re-read reserved exposure; if it differs
from the snapshot's, freeze a successor snapshot (the same records, the new exposure — a snapshot is
per evaluation, the request keeps its first in history), re-assess, re-evaluate under the same pinned
versions; insert the decision with its reason codes; move the request `DECIDED`; emit
`CreditDecisionRecorded`; audit. **A REFER** opens the underwriting case instead and moves the
request `IN_REVIEW`; the person's decision runs the same deciding transaction — conditional on the
case (taken by that person) rather than the request's expiry — with `decided_by` the person and the
case's reason codes, the system evaluation kept as the case's basis. A person's approval is bounded
by the evaluation's approved amount and by the exposure limit re-read under the profile lock: beyond
it the act is refused `422 credit.ExposureLimitExceeded`, nothing recorded and the case unchanged, and
the person decides again (a decline, or a smaller approval). A person who abandons the request this
way closes the case (`CLOSED`).

### 12.8 Explanation and replay

**Explanation** reads the decision, its snapshot, the pinned versions' rules and the triggered list:
which data (attributes and their sources and retrieval times), which policy, model and engine
versions, which rules fired, which reasons, what outcome, when, by whom. **Replay** re-runs the
pinned engine over the stored snapshot with the pinned policy and model versions and compares
outcome, approved amount and ordered reason codes, and re-verifies the hash; the operator door does
one, `CreditReplayProof` does all of them per reading (`finapp.credit.replay{verdict}`, alerting on
any `DIVERGED`).

## 13. Testing strategy

- **Decision-rule tests** (hermetic): every operator, effect and severity combination; ordering and
  deduplication of reason codes; caps; the fallback; an adverse outcome without reasons
  unrepresentable.
- **Provider contract tests**: each simulated adapter against the port's contract suite (normal,
  partial, malformed, timeout, duplicate, unknown status → `UNAVAILABLE`, never data); normalisation
  golden files per adapter.
- **Policy-version tests**: four-eyes at both ranks; one `ACTIVE`; the active-at-instant query over a
  history; activation mid-decision keeps the pinned version.
- **Explainability tests**: every decision explains from the rows alone; customer responses carry no
  internal figure (a needle per figure).
- **Reproducibility battery** (`P10-TST-002`): ≥ 10,000 generated applicants across both products,
  every reason code exercised, every decision replayed `IDENTICAL`; a perturbation of a stored
  snapshot or a rule flips the verdict.
- **Duplicates and concurrency**: ten submissions per key; two keys per party and product; ten
  progress sweepers; two products for one party at the exposure limit; ten approvers per proposal;
  two underwriters per case — each counted.
- **Stale data**: a record one second past the maximum age (database clock) re-collects; an instance
  ±5 s skewed neither accepts stale data nor refuses fresh.
- **Provider timeout / retry / lost response**: the provider counts one pull per reference; retries
  converge.
- **Failure recovery**: a crash after each step (simulated by a forced rollback or a killed
  transaction) re-driven by another instance with nothing doubled.
- **Authorization and security**: every route's negative; owner scoping; consent-absent refusal;
  self-approval refused at domain and `CHECK`; the needle walk.
- **The storm** (`P10-TST-001`): two application instances with clocks ten seconds apart, decision
  requests for shared parties across both products, provider faults, policy and scorecard activations
  mid-flight, underwriters on both instances — at rest every decision replays `IDENTICAL`, no party's
  approved exposure exceeds its limit, each provider counts one pull per reference, and the
  references answered `RECEIVED` equal the records.

## 14. Failure scenarios

| # | Scenario | Behaviour | Test |
|---|---|---|---|
| 1 | Bureau unavailable | `UNAVAILABLE`; retried until the deadline; then the policy's fallback | `-006`, `-015` |
| 2 | Bureau returns partial data | missing attributes `ABSENT`; rules decide explicitly | `-005`, `-013` |
| 3 | Bureau returns malformed data | `UNAVAILABLE` with the evidence kept; never parsed into attributes | `-005` |
| 4 | Bureau answers an unknown status | `UNAVAILABLE` (modelled, `INV-LIFE-03`), never data | `-005` |
| 5 | Bureau success, response lost | the retry asks under the same reference; one pull counted | `-006` |
| 6 | Duplicate bureau answer | one record; duplicate evidence marked | `-006` |
| 7 | Consent withdrawn mid-pull | `CONSENT_WITHDRAWN`; payload discarded; the request `ABANDONED` (`CONSENT_WITHDRAWN`) by its next step | `-006`, `-015` |
| 8 | Consent absent at submission | `403 credit.ConsentRequired`; nothing asked | `-014` |
| 9 | Data stale at freeze | re-collected; never decides stale | `-008` |
| 10 | Crash after submission | the sweep opens the data requests | `-015` |
| 11 | Crash after data received | the sweep freezes | `-015` |
| 12 | Crash between evaluation and decision | the sweep decides once | `-016` |
| 13 | Duplicate submission (same key) | the same response | `-014` |
| 14 | Two submissions, two keys, same product | `409 DecisionRequestOpen` | `-014` |
| 15 | Two products for one party at the exposure limit | serialised; the second sees the reservation | `-016` |
| 16 | Policy activated mid-decision | the pinned version decides | `-012`, `-016` |
| 17 | Scorecard activated mid-decision | the pinned model decides | `-011`, `-016` |
| 18 | Request expires at the decision boundary | exactly one of `DECIDED`, `EXPIRED` | `-016` (`-015` proves expiry against every earlier step) |
| 19 | Underwriter approves own first decision as second | refused at domain and `CHECK` | `-018` |
| 20 | Underwriter tries to approve a hard decline | `422 HardDeclineNotOverridable` | `-018` |
| 21 | Two underwriters take one case | one `ASSIGNED` | `-018` |
| 22 | A person decides with no reason | `422 ReasonRequired` | `-018` |
| 23 | A stored snapshot tampered | replay `DIVERGED` (hash) | `-019` |
| 24 | A rule row tampered with (in any status) | refused by the rule-immutability trigger; replay `DIVERGED` if forced | `-012`, `-019` |
| 25 | A decision row updated by any role | refused (privilege and trigger) | `-016` |
| 26 | Evaluator semantics changed | a new engine version; old decisions replay under theirs | `-013`, `-019` |
| 27 | Applicant's standing suspended mid-request | the step (or the deciding transaction) refuses; the request `ABANDONED` (`STANDING_LOST`) | `-015`, `-016` |
| 28 | A source in a currency other than the product's | partial data: the attribute `ABSENT` with the `CURRENCY_NOT_SUPPORTED` marker; never converted | `-005`, `-008` |
| 29 | Provider slow beyond timeout | the call times out; the data request `UNAVAILABLE`, retried | `-006` |
| 30 | Ten sweepers on one request | one step per state | `-015` |
| 31 | Consent withdrawn after a source answered, before the decision | the data request stays `RECEIVED` (terminal); the gate re-read at the freeze or in the deciding transaction finds the withdrawal and the request is `ABANDONED` (`CONSENT_WITHDRAWN`), nothing frozen or decided | `-006` (the record stays `RECEIVED`), `-015`, `-016` |

## 15. Observability

| Series | Type | Tags | Alert |
|---|---|---|---|
| `finapp.credit.decision` | counter | product, outcome, policy_version, decided_by (system/person) | — |
| `finapp.credit.decision.latency` | timer | product | p99 above the declared objective |
| `finapp.credit.reason` | counter | product, reason_code | — |
| `finapp.credit.data.request` | counter | source_kind, provider, outcome (received/unavailable/consent_withdrawn/duplicate) | unavailability ratio |
| `finapp.credit.data.latency` | timer | source_kind, provider | — |
| `finapp.credit.request.open.age` | gauge (seconds, oldest) | status | above the request validity |
| `finapp.credit.review.age` | gauge (seconds, oldest open case) | — | above the review objective |
| `finapp.credit.replay` | gauge | verdict | any `DIVERGED` > 0 |
| `finapp.credit.policy.active` | gauge (version) | product | none active for an offered product |
| `finapp.credit.progress.sweeper.enabled`, `finapp.credit.data.retry.sweeper.enabled` | gauge | — | 0 in a non-test profile |

`finapp.credit.data.request` per `provider` is also **the bureau-cost proxy**: pulls are counted per
provider (one per reference); Phase 10 publishes no money cost series. No amount, score, attribute
or party in any tag. Spans for submission, collection, freeze,
evaluation and decision, linked by the request's correlation. A dashboard row and the alert rules
resolved against a live scrape (`P10-TSK-020`).

## 16. Milestones

| Milestone | Acceptance | Tasks |
|---|---|---|
| **M10.1 Foundations** | the module, its schema and isolation; consent purposes; permissions and roles | `-001`, `-002`, `-003` |
| **M10.2 Credit data** | a profile per party; bureau and financial data collected under consent, normalised, evidence encrypted, duplicates and outages safe | `-004`, `-005`, `-006`, `-007` |
| **M10.3 Assessment** | the snapshot sealed and fresh; affordability and exposure exact; the scorecard versioned | `-008`, `-009`, `-010`, `-011` |
| **M10.4 Policy** | policy versions as data, four-eyes; the deterministic evaluator | `-012`, `-013` |
| **M10.5 Decisioning** | requests decided end to end across instances; decisions immutable, explained to the customer | `-014`, `-015`, `-016`, `-017` |
| **M10.6 Underwriting** | referrals decided by people under four-eyes | `-018` |
| **M10.7 Proof** | every decision replayed; a second bureau (cut candidate); operated by meters and reports | `-019`, `-020`, `-021` |
| **M10.8 Exit** | the storm, the battery, the review | `P10-TST-001`, `P10-TST-002`, `P10-DOC-001` |

**Cut order** if the phase must shrink: `P10-TSK-021` (a second bureau and source selection) first;
its deferral is recorded with Phase 15 as owner and the provider-neutrality criterion is then met by
the port's contract suite and the single adapter.

## 17. What Phase 10 must NOT implement

Loan applications, offers, acceptance, disbursement, repayment schedules, interest, servicing,
delinquency, collections, BNPL (Phases 11–12); any ledger posting, hold or money movement; the risk
score and fraud rules (Phase 13 — only the seam); counter-offers and pricing of credit (Phase 11's
offer); real bureau connectivity; machine-learned models (the scorecard is a points table; a
statistical model is a later model family under the same versioning); evidence purge and
crypto-shredding (Phase 15).

## 18. Risks

- **Policy drift into code.** Mitigation: rules are rows over a closed vocabulary; a rule the
  vocabulary cannot express is a new operator with a new engine version, reviewed.
- **Non-reproducibility through the clock or the environment.** Mitigation: the evaluator reads only
  the snapshot; no clock, no locale, no hash-map ordering; the battery replays everything.
- **Exposure double-counting or under-counting under concurrency.** Mitigation: the profile lock and
  the re-read in the deciding transaction; the storm's exposure census.
- **Bureau PII over-exposure.** Mitigation: encrypted evidence, no application `SELECT`, the needle
  walk, attributes only in explanations.
- **A referral queue nobody works.** Mitigation: the review-age alert; the request's validity
  expires an unworked case with a recorded reason.

## 19. The first task

**`P10-TSK-001` — The credit module boundary and floors.** The `credit` module and schema
(`V001`: the schema, the roles' grants floor), `CreditModuleIsolationTest` (edges to `platform` and
`sharedkernel` only; nothing depends on `credit`), the closed vocabularies (`CreditProduct` with
every declaration of §12.1 — its request and decision validities included — `CreditAttributeCode`,
`ReasonCode` with the seeded catalogue table (`V002`), `DecisionOutcome`), the
package documentation, and the planned register rows promoted as each lands. No behaviour, no
endpoint. Its entry in `BACKLOG.md` carries the full twenty-three fields.
