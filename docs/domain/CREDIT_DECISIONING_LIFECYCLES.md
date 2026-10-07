# Credit Decisioning Lifecycles

*Planned by the Phase 9 → 10 transition (2026-10-07); nothing built yet.* Every machine, edge,
table, trigger, event and audit act below is the decided design, not a description of code: no
`credit` module exists. Each task that builds a part corrects this document where the code teaches
otherwise, and the Phase 10 exit review (`P10-DOC-001`) reads every statement against the code.

Written on the `FX_AND_CROSS_BORDER_LIFECYCLES.md` precedent: the document that names a phase's
model is written before the phase's first task, from the decisions in ADR-0084…0089. The engineering
plan is [`PHASE_10_PLAN.md`](../project/PHASE_10_PLAN.md); the invariants are `INV-CRD-01`…`12` in
[`FINANCIAL_INVARIANTS.md`](FINANCIAL_INVARIANTS.md); the founding statement is
[`CREDIT_MODEL.md`](CREDIT_MODEL.md).

Related: ADR-0084 (the credit bounded context; the risk score is `risk`'s) · ADR-0085 (credit data
collection) · ADR-0086 (policy and model as versioned data) · ADR-0087 (the decision, the snapshot,
explanation and replay) · ADR-0088 (affordability and exposure) · ADR-0089 (underwriting) — all
Proposed at the transition, indexed in [`docs/adr/README.md`](../adr/README.md).

---

## 1. The concepts, kept apart

The canonical terms are in [`GLOSSARY.md`](GLOSSARY.md); `PHASE_10_PLAN.md` §3 tabulates what each
is and is not. The distinctions this document's machines make physical:

| Kept apart | Why |
|---|---|
| **Decision request** vs **Loan application** | The decision request is credit's input envelope; the loan application is Phase 11's aggregate and will *reference* a decision. Phase 10 builds no loan application |
| **Credit data** vs **Credit profile** | Data is evidence from a source, one record per answered data request; the profile is the party's one row that every decision serialises on, holding no figures |
| **Credit score** vs **Risk score** vs **Decision** | The score is the scorecard's points over a snapshot; the risk score is `risk`'s (Phase 13), consumed through the `CreditRiskSignal` seam (Phase 10 answers `NOT_ASSESSED`, recorded); the decision is the recorded outcome (`INV-CRD-04`) |
| **Assessment** vs **Evaluation** vs **Decision** | The assessment holds the figures (affordability, exposure, score); the evaluation is the policy's run over them; the decision is the immutable outcome, which differs from the evaluation only through a person's review of a referral |
| **Underwriting case** vs **Decision** | The case is the workflow of a person's review; its outcome is recorded once, as *the* decision — never as an update of an earlier one |
| **Policy version** vs **Code** | A policy is rows over a closed vocabulary, versioned, activated four-eyes; the evaluator's semantics are code, versioned by `engine_version` |
| **Expired** vs **Abandoned** vs **Cancelled** | `EXPIRED` means only that no decision came within the request's validity; `ABANDONED` is the platform closing a request it may no longer decide (standing lost, consent withdrawn); `CANCELLED` is the applicant's own act before evaluation |

---

## 2. When the decision takes effect

**Phase 10 moves no money.** No transaction below posts to the ledger, takes a hold or disburses.
The pipeline is **Submission → Collection → Freeze → Assessment → Evaluation → Decision (or
Review → Decision)**, never collapsed: each step has its own record.

| Step | Record | Transaction | Effect |
|---|---|---|---|
| Submission | `decision_request SUBMITTED` | the keyed submission | None beyond the request; the profile ensured |
| Collection | the request's pinned versions, `data_request`, `credit_record`, `credit_evidence` | the progress step pins the policy and scorecard versions and opens; the asking thread or the retry sweep records | External data retrieved under consent, audited as an access |
| Freeze, assessment, evaluation | `decision_snapshot` (sequence 1), `credit_assessment`, `policy_evaluation` | one progress step | The pinned versions recorded on the snapshot; figures computed; outcome evaluated — not yet a decision |
| Decision | `credit_decision` (and, if the reserved exposure changed, a successor snapshot with its assessment and evaluation) | the deciding transaction, profile-first | **Here.** An `APPROVED` decision reserves its approved amount as exposure until `valid_until` (database clock) or its consumption fact (§4) |
| Review | `underwriting_case` | the deciding transaction's referral branch; the underwriter's acts | The person's decision is the decision, recorded by the same deciding transaction |

---

## 3. The state machines

Every stored machine below gets the platform's standing three-layer enforcement: the aggregate's
exhaustive transition sweep refusing invalid edges; a generated schema `CHECK` plus an every-writer
transition trigger, both from the aggregate's `permittedTransitions()`; and an append-only history
recording actor id, actor type, occurred at and reason (`INV-LIFE-01/-02`, the ADR-0044 doctrine:
**states are earned by producers**). §3.6 lists the tables. Every window — the request's validity,
a source's deadline, a record's maximum age, a decision's validity, every sweep permit — is judged
**on the database clock** (`statement_timestamp()` in SQL, or `DatabaseTime.now` read in the
deciding transaction), never on an instance clock. The application role holds no `DELETE` on any
table of the `credit` schema, and no `UPDATE` on any born-once fact (§3.5).

**The lock order** (`PHASE_10_PLAN.md` §7; one `DISTRIBUTED_EXECUTION.md` §3 row). Every credit
writer takes an order-respecting subsequence of:

| Position | Row | Mode | Taken by |
|---|---|---|---|
| **L1** | the party's `credit_profile` | `FOR UPDATE` | deciding transactions only, and then first |
| **L2** | the `decision_request` | `FOR UPDATE` | every request step, the cancellation, the expiry and abandonment, every act of the case (assignment, release and the refusal of a second approval included — never the case alone) |
| **L3** | the `underwriting_case` | `FOR UPDATE` | the case's acts; the expiry or abandonment of an `IN_REVIEW` request |
| **L4** | the request's `data_request` rows, by id | `FOR UPDATE` (recording) / `FOR SHARE` (freeze) | collection steps, the recorder, the retry sweep (alone) |
| **L5** | the pinned policy and model versions | `FOR SHARE` | the pin at `SUBMITTED → COLLECTING`; the freeze and evaluation; the deciding transaction |

After L5 only inserts (snapshot, assessment, evaluation, decision, case, events, audit). The policy
and model administration takes only version rows, in its own order: **A0** credit's own advisory
lock in namespace `10` (`hashtext(product)` / `hashtext(family)`, blocking — the one-`PROPOSED`
partial unique stays as the backstop; registered by `P10-TSK-011`, its first writer), **A1** the
proposal row, **A2** the active row. No credit
transaction takes a lock outside `credit`; the consent gate and the party's standing are plain
authoritative reads under `READ COMMITTED` in the acting transaction. The progress sweep's claim
transaction (a `FOR UPDATE SKIP LOCKED` page stamping a permit from `statement_timestamp()`)
commits before the step runs; a deciding step opens its own transaction profile-first, so no writer
ever waits for L1 while holding L2.

### 3.1 Decision request (`credit.decision_request`)

```
 (keyed submission)
        │
        v
   SUBMITTED ──data requests opened──> COLLECTING ──every source received and fresh,
                                          ^            or unavailable past its deadline──> READY
                                          └───────────a record stale at the freeze────────┘ │
                                                                                            │ snapshot frozen,
                                                                                            v assessed, evaluated
                                                              EVALUATED ──APPROVE | DECLINE──> DECIDED
                                                                  │                              ^
                                                                  └──REFER──> IN_REVIEW ──a person decides

  SUBMITTED | COLLECTING | READY ──the applicant──> CANCELLED
  any open state (IN_REVIEW only while its case is OPEN) ──no decision within the validity──> EXPIRED
  any open state ──the platform: standing lost, or consent withdrawn──> ABANDONED
```

The request's validity is a declaration of its `CreditProduct` — 7 days for both `PERSONAL_LOAN` and
`CREDIT_LINE` — stamped as `expires_at` from `statement_timestamp()` at submission. The policy and
scorecard versions are **pinned on the request at `SUBMITTED → COLLECTING`** (the claim-time
precedent: the sources collected, and their maximum age, are the pinned policy's) and re-read
`FOR SHARE` (L5) at the freeze and in the deciding transaction; an activation mid-request never
changes them.

| Edge | Trigger and actor | Transaction (lock order) | Effect | Event | Audit act |
|---|---|---|---|---|---|
| (birth) → `SUBMITTED` | The customer, `POST /v1/me/credit/decision-requests`, MFA-assured session, keyed | The submission, under the `IdempotentExecutor` claim `credit.decision:CUSTOMER:<id>`; no row lock: the consent gate (each source kind the product's active policy reads) and the party's standing (`ACTIVE`, KYC `VERIFIED`) read in-transaction; the profile ensured `ON CONFLICT DO NOTHING`; the request inserted under the partial `UNIQUE (party_id, product)` over the open states | The request born with `expires_at = statement_timestamp() +` the product's request validity and its first progress permit. Refusals: `403 credit.ConsentRequired` (nothing asked), `422 credit.ProductNotOffered`, `422 credit.AmountOutOfRange`, `409 credit.DecisionRequestOpen` | `CreditDecisionRequested` | — (the customer's own keyed command: the idempotency record, the history row and the event are its trail) |
| `SUBMITTED → COLLECTING` | `CreditDecisionProgressSchedule` (every instance, leaderless) | The step: **L2**, conditional on `SUBMITTED` and `expires_at > statement_timestamp()`; standing re-read; **L5** the `ACTIVE` policy and scorecard versions `FOR SHARE`, pinned on the request (written once); one `data_request` inserted per source kind the pinned policy reads (ADR-0085's Tx1: reference minted and stored, gate read, permit stamped, deadline and retry cadence stamped from the source kind's configuration) | Data requests `REQUESTED` (§3.2); the providers asked after commit, no connection held. A product whose policy reads no external source passes through `COLLECTING` to `READY` in one transaction, both history rows written | — | `credit.BureauDataRequested` / `credit.FinancialDataRequested`, one per data request opened (`INV-AUD-01`: the access is the act) |
| `COLLECTING → READY` | The progress step | **L2**, **L4** `FOR SHARE`; conditional on `COLLECTING`, unexpired | Every required source is either `RECEIVED` with a record inside the pinned policy's maximum age, or `UNAVAILABLE` past its deadline — its attributes then `ABSENT` with a recorded `SOURCE_UNAVAILABLE`, for the policy's fallback to decide (`INV-CRD-10`) | — (`CreditDataUnavailable` was emitted by the data request when its deadline passed, §3.2) | — |
| `READY → COLLECTING` | The progress step, at the freeze — the one backward edge (`PHASE_10_PLAN.md` §5, §14 scenario 9) | **L2**, **L4**; a `RECEIVED` record found past the pinned policy's maximum age on the database clock (`INV-CRD-08`) | A new data request for that source (new reference), before any snapshot exists; the stale one stays `RECEIVED`, its record belonging to no snapshot. Bounded by the request's validity | — | `credit.BureauDataRequested` / `credit.FinancialDataRequested` (a new access) |
| `READY → EVALUATED` | The progress step | **L2**, **L4** `FOR SHARE`, **L5** the request's pinned policy and model versions `FOR SHARE` (even if since retired); one transaction: the consent gate re-read for every source kind (a withdrawal abandons the request instead, nothing frozen — §14 scenario 31), freshness re-judged, snapshot frozen (sequence 1; canonical JSON, SHA-256; reserved exposure read; the risk seam's `NOT_ASSESSED` and the platform-exposure seam's zero recorded with their versions), assessment computed, evaluation run | Snapshot, assessment and evaluation born once (§3.5); the evaluation's outcome and ordered triggered rules stored | `CreditAssessmentCreated` | — |
| `EVALUATED → DECIDED` | The deciding transaction, on an `APPROVE` or `DECLINE` (a `HARD_DECLINE` included) | **L1** the profile, **L2** the request (`EVALUATED`, `expires_at > statement_timestamp()`, standing and the consent gate for every source kind re-read — either lost abandons the request instead), **L5** the pinned versions `FOR SHARE`; the reserved exposure re-read under L1 and, **only if it changed**, a successor snapshot (the next sequence: the same records, the new exposure), its assessment and its evaluation under the same pinned versions (`PHASE_10_PLAN.md` §12.7) | The `credit_decision` born naming the snapshot it was made from, with its reason codes in order, `decided_by` the platform, `valid_until = statement_timestamp() +` the product's decision validity and the pinned versions; an `APPROVED` decision's amount now reserved (`INV-CRD-09`) | `CreditDecisionRecorded` | `credit.DecisionRecorded` |
| `EVALUATED → IN_REVIEW` | The deciding transaction, on a `REFER` | The same: **L1**, **L2**, standing and consent re-read, **L5**, the exposure re-read (a changed exposure re-evaluates against a successor snapshot, and may turn the referral into a decision) | The `underwriting_case` born `OPEN` with the referral's reason codes (§3.3) | `ManualReviewRequired` | — (a platform workflow step; the case's history and the event are its trail) |
| `IN_REVIEW → DECIDED` | The underwriter's decision (case `ASSIGNED → DECIDED`) or the second approval (case `AWAITING_SECOND → DECIDED`) | **L1**, **L2**, **L3**, standing and consent re-read, **L5**, the exposure re-read; the transaction checks the case, **not** the request's expiry — a taken case is decided by its person whatever the request's validity | The decision born with `decided_by` the person, the case's reason codes, the system evaluation kept as the case's basis. An approval is bounded by the evaluation's approved amount and by the exposure limit re-read under L1: beyond it `422 credit.ExposureLimitExceeded`, nothing recorded, the case unchanged, and the person decides again | `CreditDecisionRecorded` | `credit.ReviewDecided` (reason required) or `credit.ReviewSecondApproval`, with `credit.DecisionRecorded` |
| `SUBMITTED \| COLLECTING \| READY → CANCELLED` | The customer, `POST …/{id}/cancellation`, keyed | **L2**, conditional on one of the three | Closed. Open data requests finish on their own (§3.2); their records belong to no snapshot, and the retry sweep stops asking for a closed request. Refusal: `409 credit.RequestNotCancellable` | `CreditDecisionRequestClosed` (status `CANCELLED`) | `credit.DecisionRequestCancelled` (the customer's act, the `fx.QuoteCancelled` precedent; no reason required) |
| any open state → `EXPIRED` (from `IN_REVIEW` only while its case is `OPEN`) | The progress step, on `expires_at <= statement_timestamp()` | **L2**; from `IN_REVIEW` also **L3**, conditional on the case `OPEN` | Closed: no decision came within the validity; an `OPEN` case moves `CLOSED` in the same transaction (§3.3). The expiry and the system decision use complementary conditionals on one clock, so exactly one of `DECIDED`, `EXPIRED` | `CreditDecisionRequestClosed` (status `EXPIRED`) | — |
| any open state → `ABANDONED` | The platform: the progress step finding a data request `CONSENT_WITHDRAWN` (§14 scenario 7); the freeze or a deciding transaction — the system's or a person's — re-reading the consent gate and finding a withdrawal after a source answered (§14 scenario 31); any step or deciding transaction finding the party's standing lost (§14 scenario 27) | **L2** (with **L3** from `IN_REVIEW`); inside a deciding transaction under **L1**, **L2** (and **L3**) | Closed with its reason, nothing frozen or decided; never `EXPIRED`. From `IN_REVIEW` the case moves `CLOSED` in the same transaction — while `OPEN` by the sweep (standing lost), while taken only by its person's own deciding transaction | `CreditDecisionRequestClosed` (status `ABANDONED`, reason `STANDING_LOST` or `CONSENT_WITHDRAWN`) | — |

- **Terminal:** `DECIDED`, `CANCELLED`, `EXPIRED`, `ABANDONED`. `DECIDED` carries exactly one
  `CreditDecision`.
- **The open states** (the partial unique's predicate, the expiry sweep's scope): `SUBMITTED`,
  `COLLECTING`, `READY`, `EVALUATED`, `IN_REVIEW`.

| Invalid edge | Why | Refused at the database, for every writer, by |
|---|---|---|
| any edge out of `DECIDED`, `CANCELLED`, `EXPIRED`, `ABANDONED` | terminal | the generated `CHECK` and the edge trigger (`decision_request_edge_is_legal`, planned) |
| `SUBMITTED → READY \| EVALUATED \| IN_REVIEW \| DECIDED` | collection is never skipped (a source-less policy passes through `COLLECTING`) | the edge trigger |
| `COLLECTING → EVALUATED \| IN_REVIEW \| DECIDED` | no snapshot, no evaluation | the edge trigger; `→ EVALUATED` refused without its `policy_evaluation` |
| `READY → IN_REVIEW \| DECIDED` | no evaluation | the edge trigger; `→ IN_REVIEW` refused without its `underwriting_case`, `→ DECIDED` without its `credit_decision` |
| `EVALUATED → COLLECTING \| READY` | a frozen snapshot is never re-collected; a stale one makes a new request | the edge trigger |
| `EVALUATED \| IN_REVIEW → CANCELLED` | the evaluation is a fact; withdrawing then is a decision the applicant receives | the edge trigger |
| `IN_REVIEW → EVALUATED` | a referral is decided by a person, never re-run by the system | the edge trigger |
| `IN_REVIEW → EXPIRED` once the case is `ASSIGNED` or `AWAITING_SECOND` | a taken case is decided by its person | the edge trigger, reading the case's status under the request's lock |
| `→ EXPIRED` while `expires_at > statement_timestamp()`; `EVALUATED → DECIDED` while `expires_at <= statement_timestamp()` | the complementary clock conditionals | the edge trigger, on `statement_timestamp()` |
| `→ ABANDONED` without a reason, or with one outside {`STANDING_LOST`, `CONSENT_WITHDRAWN`} | a platform closure always says why | the `CHECK` pairing status and closure reason |
| a second open request for one (party, product) | one open request per product | the partial `UNIQUE (party_id, product)` over the open states |

### 3.2 Credit data request (`credit.data_request`)

```
 (opened by the request's collection step: reference minted, gate read)
        │
        v
   REQUESTED ──answer recorded──> RECEIVED
     │  │  ^
     │  │  └──retry, same reference, before the deadline──┐
     │  └──timeout · refusal · malformed · unknown status──> UNAVAILABLE ──past the deadline: final
     │                                                         │
     └──gate closed at recording──> CONSENT_WITHDRAWN <──gate closed at retry──┘
```

| Edge | Trigger and actor | Transaction (lock order) | Effect | Event | Audit act |
|---|---|---|---|---|---|
| (birth) → `REQUESTED` | The decision request's `SUBMITTED → COLLECTING` or `READY → COLLECTING` step | Inside that step's **L2** (an insert): `request_reference UNIQUE` minted and stored before any call; the gate for the source kind's purpose (`CREDIT_BUREAU_ACCESS` / `FINANCIAL_DATA_ACCESS`) read; the permit stamped from `statement_timestamp()`, and `deadline_at` and the retry cadence stamped from the source kind's configuration (so a configuration change never moves an open request's deadline) | The provider asked after commit, holding no connection, under a bounded timeout | — | `credit.BureauDataRequested` / `credit.FinancialDataRequested` |
| `REQUESTED → RECEIVED` | The recorder (ADR-0085's Tx2), on the asking thread or the retry sweep's | **L4** alone (a suffix of the order); the gate re-read (`INV-CRD-03`) | The `credit_record` born once (`UNIQUE (data_request_id)`), its attributes normalised (a money value in another currency is normalised as partial data — the attribute `ABSENT` with the recorded `CURRENCY_NOT_SUPPORTED` marker, never a conversion and never an error); the raw answer kept encrypted in `credit_evidence` with `retain_until`; an attempt row | `CreditDataCollected` | — (the access was audited when opened) |
| `REQUESTED → UNAVAILABLE` | The recorder | **L4**; a timeout, refusal, 5xx, a malformed answer (evidence kept, never parsed) or an unknown status (`INV-LIFE-03`) | An attempt row; nothing parsed into attributes; the permit re-stamped at the row's stamped cadence | — | — |
| `REQUESTED → CONSENT_WITHDRAWN` | The recorder, finding the gate closed; or the retry sweep, finding it closed before re-asking a request past its permit | **L4** | The payload discarded unread; the evidence row records only that a response arrived; the decision request is then `ABANDONED` (`CONSENT_WITHDRAWN`) by its next progress step | — | — |
| `UNAVAILABLE → REQUESTED` | `CreditDataRetrySchedule` (every instance, leaderless) | The claim page (`FOR UPDATE SKIP LOCKED`, permit stamped), then **L4**, conditional on `deadline_at > statement_timestamp()`; the gate re-read | A new attempt row (`UNIQUE (data_request_id, attempt)`) under the **same** reference — the provider dedupes, so one pull is counted | — | — (the same access under the same reference) |
| `UNAVAILABLE → CONSENT_WITHDRAWN` | The retry sweep, finding the gate closed | **L4** | Nothing asked — a retry is gated like the first ask; the decision request is then `ABANDONED` (`CONSENT_WITHDRAWN`) by its next progress step | — | — |

- **Not an edge:** a `REQUESTED` data request past its permit (the answer lost) is re-asked by the
  retry sweep under the same reference, the permit renewed strictly forward; its status does not
  move until an answer is recorded. A duplicate answer finds the request `RECEIVED`: its evidence
  is kept marked as a duplicate, never a second record.
- **Not an edge:** an `UNAVAILABLE` data request reaching `deadline_at` stays `UNAVAILABLE`; the
  retry sweep emits `CreditDataUnavailable` (source, attempts) for it exactly once, by a conditional
  flag on the row, and re-stamps rather than holding the page.
- **Not an edge:** a consent withdrawal after the answer was recorded. `RECEIVED` is terminal and
  stays `RECEIVED`; the withdrawal is acted on by the decision request — the gate re-read at the
  freeze and in the deciding transaction abandons it (§3.1, §14 scenario 31).
- **Terminal:** `RECEIVED`, `CONSENT_WITHDRAWN`; `UNAVAILABLE` becomes final at `deadline_at`,
  when the retry edge is refused.

| Invalid edge | Why | Refused at the database, for every writer, by |
|---|---|---|
| any edge out of `RECEIVED` or `CONSENT_WITHDRAWN` | terminal | the generated `CHECK` and the edge trigger (`data_request_edge_is_legal`, planned) |
| `→ RECEIVED` without its record; a second record | one record per answered request | the edge trigger; ★ `UNIQUE (credit_record.data_request_id)` |
| `UNAVAILABLE → RECEIVED` | an answer is recorded only against an open attempt | the edge trigger |
| `UNAVAILABLE → REQUESTED` at or past `deadline_at` | the source's deadline is final | the edge trigger, on `statement_timestamp()` |
| a changed reference, source kind, provider, deadline or retry cadence | the reference is what the provider dedupes on; the deadline and cadence are stamped once, at birth | the frozen-columns trigger; `request_reference UNIQUE`; the forward-only permit trigger |

### 3.3 Underwriting case (`credit.underwriting_case`)

```
 (born OPEN by the deciding transaction's REFER branch)
        │
        v
      OPEN ──an underwriter takes it──> ASSIGNED ──declined, or approved at or below the threshold──> DECIDED
        ^                                │  │                                                         ^
        └────────────released────────────┘  └──approved above the threshold──> AWAITING_SECOND ──a different underwriter approves──┘

  AWAITING_SECOND ──the second approver refuses, reasoned──> ASSIGNED (back to the first underwriter)
  OPEN ──its request expires, or the sweep abandons it (standing lost)──> CLOSED
  ASSIGNED | AWAITING_SECOND ──its person's own deciding transaction abandons the request──> CLOSED
```

| Edge | Trigger and actor | Transaction (lock order) | Effect | Event | Audit act |
|---|---|---|---|---|---|
| (birth) → `OPEN` | The deciding transaction, on a `REFER` (§3.1) | Under its **L1**, **L2**; `UNIQUE (decision_request_id)` | The case with its basis (the evaluation) and referral reason codes; the product's four-eyes threshold copied onto it | `ManualReviewRequired` | — |
| `OPEN → ASSIGNED` | An underwriter (`CREDIT_UNDERWRITE`), `POST /v1/operator/credit/review-cases/{id}/assignment`, keyed `credit.review:EMPLOYEE:<id>` | **L2** then **L3**, conditional on the case `OPEN` and the request `IN_REVIEW` — so the assignment and the expiry serialise on the request row | The assignee recorded; from here the request no longer expires. Loser: `409 credit.CaseTaken` | — (`UnderwritingStarted` refused, `PHASE_10_PLAN.md` §10) | `credit.ReviewCaseAssigned` |
| `ASSIGNED → OPEN` | The assignee, `POST /v1/operator/credit/review-cases/{id}/release`, keyed | **L2**, **L3**, conditional on `ASSIGNED` and the actor the assignee | Back in the queue; the request's validity governs again (an already-lapsed request is expired by the next sweep, the case `CLOSED`) | — | `credit.ReviewCaseReleased` |
| `ASSIGNED → DECIDED` | The assignee, `POST …/{id}/decision`: `DECLINED`, or `APPROVED` at or below the threshold, with ≥ 1 reason code | The deciding transaction: **L1**, **L2**, **L3**, standing and consent re-read, **L5**, the exposure re-read; no expiry check | The request `IN_REVIEW → DECIDED` and the decision born (§3.1). Refusals: `422 credit.ReasonRequired`, `422 credit.HardDeclineNotOverridable`, `422 credit.ExposureLimitExceeded` (the approval beyond the evaluation's approved amount or the re-read exposure limit — nothing recorded, the case stays `ASSIGNED`, the person decides again) | `CreditDecisionRecorded` | `credit.ReviewDecided` (reason required), `credit.DecisionRecorded` |
| `ASSIGNED → AWAITING_SECOND` | The assignee, `APPROVED` above the threshold, with ≥ 1 reason code | **L2**, **L3** (no decision is recorded, so no L1) | The first decision (outcome, amount, term, reasons, `first_decided_by`) recorded on the case; no `credit_decision` yet. The amount is bounded by the evaluation's approved amount | — | `credit.ReviewDecided` (reason required) |
| `AWAITING_SECOND → DECIDED` | A **different** underwriter, `POST …/{id}/second-approval` (approve) | The deciding transaction: **L1**, **L2**, **L3**, standing and consent re-read, **L5**, the exposure re-read; no expiry check | The decision born with the first decision's content (`INV-CRD-11`, `INV-AUD-04`). Refusals: `403 credit.SelfApprovalRefused`; `422 credit.ExposureLimitExceeded` (the case stays `AWAITING_SECOND`; the second approver may refuse it back) | `CreditDecisionRecorded` | `credit.ReviewSecondApproval`, `credit.DecisionRecorded` |
| `AWAITING_SECOND → ASSIGNED` | A **different** underwriter who disagrees, `POST …/{id}/second-approval` (refuse), with a reason | **L2**, **L3**, conditional on `AWAITING_SECOND` and the actor not the first decider | The first decision cleared from the case (kept in its history); the case back with its first underwriter, who decides again | — | `credit.ReviewSecondApprovalRefused` (reason required) |
| `OPEN → CLOSED` | The request's expiry, or the progress sweep abandoning it (standing lost) | Inside the request's closing transaction: **L2**, **L3**, conditional on the case `OPEN` | Closed with the request's reason (`EXPIRED`, `STANDING_LOST`); nothing decided | — (the request's `CreditDecisionRequestClosed`) | — |
| `ASSIGNED \| AWAITING_SECOND → CLOSED` | Its person's own deciding transaction finding the party's standing lost or the consent withdrawn | Inside that transaction: **L1**, **L2**, **L3** | The request `ABANDONED` with its reason and the case `CLOSED` with it; nothing decided | — (the request's `CreditDecisionRequestClosed`) | — |

- **Terminal:** `DECIDED`, `CLOSED`.
- **What a person may not decide** (ADR-0089): approve a request whose evaluation included a
  `HARD_DECLINE` (the evaluator never refers one, and a case for one is unstorable); decide with no
  reason code; second-approve their own first decision; approve more than the evaluation's approved
  amount, or beyond the exposure limit re-read under the profile lock (`INV-CRD-09`) — refused
  `422 credit.ExposureLimitExceeded`, nothing recorded, and the person decides again (a decline, or a
  smaller approval).
- **A taken case is never stuck:** once `ASSIGNED` or `AWAITING_SECOND`, it is decided by its
  person whatever the request's validity — the person's deciding transaction checks the case, not
  the request's expiry. **Only an unassigned `OPEN` case expires, and it does so through its
  request:** the request's `IN_REVIEW → EXPIRED` takes L2 then L3 and is admitted only while the
  case is `OPEN`, closing the case `CLOSED`; the assignment takes the same two locks in the same
  order and is admitted only while the request is `IN_REVIEW`. Exactly one wins.

| Invalid edge | Why | Refused at the database, for every writer, by |
|---|---|---|
| any edge out of `DECIDED` or `CLOSED` | terminal; a change of mind is a new request | the generated `CHECK` and the edge trigger (`underwriting_case_edge_is_legal`, planned) |
| `OPEN → DECIDED \| AWAITING_SECOND` | a decision needs an assignee | the edge trigger |
| `AWAITING_SECOND → OPEN` | a recorded first decision is not released to the queue; a disagreeing second approver refuses it back to its first underwriter (`AWAITING_SECOND → ASSIGNED`) | the edge trigger |
| `AWAITING_SECOND → ASSIGNED` by the first decider, or without a reason | the refusal is the second person's, reasoned | the edge trigger; the reason `CHECK` on the event row |
| `→ CLOSED` while the request is still open | a case closes only with its request, in the request's closing transaction | the edge trigger, reading the request's status under L2 |
| `OPEN → ASSIGNED` under a request not `IN_REVIEW` | the request was closed first | the edge trigger, reading the request's status under L2 |
| `ASSIGNED → DECIDED` approving above the case's threshold | four-eyes above the threshold | the edge trigger, against the copied threshold |
| `AWAITING_SECOND → DECIDED` by the first decider | four-eyes | the `CHECK (second_decided_by <> first_decided_by)` |
| `→ DECIDED \| AWAITING_SECOND` with no reason code | `INV-CRD-11` | a deferred constraint trigger counting the case's reason rows |
| a case whose basis evaluation carries a `HARD_DECLINE` | ADR-0089 §2 | the case's `BEFORE INSERT` trigger reading the evaluation |
| a second case for one request | one review per referral | ★ `UNIQUE (decision_request_id)` |

### 3.4 Credit policy version and scorecard model version

One machine, two tables — `credit_policy_version` (+ `credit_policy_rule`) per product,
`P10-TSK-012`; `scorecard_model_version` (+ `scorecard_band`) per model family, `P10-TSK-011` — on
the Phase 9 pricing and corridor policy shape (`FX_AND_CROSS_BORDER_LIFECYCLES.md` §3.9), with
**no seed exemption**: version 1 of each is inserted as a proposal and activated by two persons.

```
PROPOSED ──a different approver──> ACTIVE ──only beside its successor's activation──> RETIRED
    └──a different rejecter, reasoned──> REJECTED
```

| Edge | Trigger and actor | Transaction (lock order) | Effect | Event | Audit act |
|---|---|---|---|---|---|
| (birth) → `PROPOSED` | `CREDIT_POLICY_ADMINISTER`, `POST /v1/operator/credit/policies` (or `/scorecards`), the full rule set or points table as the body, keyed | **A0** (namespace `10`, blocking), then inserts: the version and its rule or band rows, born together in one transaction; one `PROPOSED` per product (family) | The rules and bands immutable for every writer from insert; a correction is a rejection and a new proposal. Refusals: `409 credit.ProposalPending`; `422 credit.PolicyIncomplete` (a policy lacking the fallback rule for a source kind it reads); `422 credit.ScorecardInvalid` (a points table with overlapping or gapped bands, a missing absent band, or a code outside the vocabulary) | — | `credit.PolicyVersionProposed` / `credit.ScorecardVersionProposed` |
| `PROPOSED → ACTIVE` | A **different** holder, `POST …/{versionId}/approval`, keyed | **A1** the proposal `FOR UPDATE`, conditional on `PROPOSED`; **A2** the active row `FOR UPDATE` | `effective_from = statement_timestamp()`; the predecessor retired in the same transaction. Losers: `409 credit.PolicyStale`; the proposer: `403 credit.SelfApprovalRefused` | `CreditPolicyVersionActivated` / `ScorecardModelVersionActivated` (with the predecessor) | `credit.PolicyVersionActivated` / `credit.ScorecardVersionActivated` |
| `ACTIVE → RETIRED` | Only the successor's activation | Inside it, under **A2** | `effective_to` = the successor's `effective_from` (one `statement_timestamp()`), so the periods abut and never overlap | (carried by the successor's activation event) | (carried by the activation's act) |
| `PROPOSED → REJECTED` | A **different** holder, `POST …/{versionId}/rejection`, reasoned, keyed | **A1**, conditional on `PROPOSED` | The proposal closed | — | `credit.PolicyVersionRejected` / `credit.ScorecardVersionRejected` (reason required) |

- **Terminal:** `RETIRED`, `REJECTED`. A retired version is never reactivated: a new proposal is.
- **Pinning:** a request pins the `ACTIVE` versions, read `FOR SHARE` (L5), at
  `SUBMITTED → COLLECTING`; the freeze, the evaluation and the deciding transaction read exactly
  those rows `FOR SHARE` again, even after their retirement. An activation waits for, or is waited
  on by, that share lock, so a decision never mixes a retired-but-unpinned version
  (`INV-HIST-04`); a version activated after the pin changes nothing for that request — and the
  sources it collected are always the ones its pinned policy reads.
- **The version active at any instant** is answerable from the rows (`INV-CRD-05`): the version
  whose `[effective_from, effective_to)` contains it — `GET /v1/operator/credit/policies?product=&at=`.

| Invalid edge | Why | Refused at the database, for every writer, by |
|---|---|---|
| any edge out of `RETIRED` or `REJECTED` | terminal | the generated `CHECK` and the edge trigger |
| `PROPOSED → RETIRED`; `ACTIVE → PROPOSED \| REJECTED` | retirement is only a successor's consequence | the edge trigger |
| an `ACTIVE` retired without a successor activated in the same transaction | at most one, and at every instant after the first activation exactly one, active version | a deferred constraint trigger |
| a second `PROPOSED` or `ACTIVE` per product (family) | one of each | partial uniques (the `PROPOSED` one the backstop behind namespace `10`) |
| activation or rejection by the proposer | four-eyes (`INV-AUD-04`) | the `CHECK (decided_by <> proposed_by)`, no seed exemption |
| any change to a version's content; a rule or band inserted outside its version's proposing transaction, updated or deleted | `INV-CRD-05`; the approver approves what was proposed | the freeze trigger on the version; the every-writer trigger on its rule and band rows |

### 3.5 The born-once facts

Facts, not machines: each is one row, written once, with no status column to walk, held
append-only (an every-writer trigger refuses `UPDATE` and `DELETE`; the application role is granted
`INSERT` and `SELECT` only, and on `credit_evidence` not even `SELECT`). Each contended one is a ★
born-once arbiter with a lock-bypass probe in its task.

| Fact | Table | Born (transaction, lock order) | Once by |
|---|---|---|---|
| Credit profile | `credit_profile` | the submission (ensure), `P10-TSK-004` | `UNIQUE (party_id)`, `ON CONFLICT DO NOTHING`. Holds no figures; it is the L1 lock target |
| Credit record | `credit_record` | the recorder's `REQUESTED → RECEIVED`, under L4 | ★ `UNIQUE (data_request_id)` |
| Credit evidence | `credit_evidence` | every recorded answer — a duplicate marked, a consent-withdrawn arrival as a bare marker | Append-only; encrypted under key purpose `credit-evidence`; `retain_until` stored; read only through the evidence-read door's definer function (`POST /v1/operator/credit/records/{id}/evidence-read`, reasoned, audited `credit.EvidenceRead`) |
| Decision snapshot | `decision_snapshot` | sequence 1 at `READY → EVALUATED`, under L2, L4, L5; a successor (the next sequence) only in a deciding transaction that found the reserved exposure changed, under L1 | ★ `UNIQUE (decision_request_id, sequence)`; one snapshot per evaluation; the canonical JSON and its SHA-256 re-verified by replay (`INV-CRD-07`) |
| Credit assessment | `credit_assessment` | the same transaction as its snapshot | ★ `UNIQUE (snapshot_id)` |
| Policy evaluation | `policy_evaluation` (+ `policy_evaluation_rule`) | the same transaction | ★ `UNIQUE (assessment_id)`; the triggered rules in ordinal order |
| Credit decision | `credit_decision` (+ `credit_decision_reason`) | the deciding transaction, under L1 | ★ `UNIQUE (decision_request_id)`; names the snapshot it was made from (`INV-CRD-06`); **never `UPDATE` or `DELETE` for any role** (privilege and trigger, `INV-CRD-02`); an adverse outcome without a reason row unstorable; `decided_by` the platform or a person; no consumption column |
| Decision consumption | `credit_decision_consumption` | created empty by `P10-TSK-016`; written only by Phase 11's loan | ★ `UNIQUE (decision_id)`; the fact that ends an approval's reservation (§4) |
| Reason code | `reason_code` | migration-seeded, `P10-TSK-001` | The closed catalogue: code, category, customer text, adverse flag; `SELECT` only |

### 3.6 The three layers, per machine

| Machine | Table (planned task) | History | Database rank beyond the edge trigger |
|---|---|---|---|
| Decision request | `credit.decision_request` (born `P10-TSK-014`; progressed and pinned `-015`; decided `-016`) | `decision_request_event` | Partial `UNIQUE (party_id, product)` over the open states; `→ EVALUATED`, `→ IN_REVIEW`, `→ DECIDED` each refused without its row; the complementary expiry and decision clock conditionals; the closure-reason `CHECK`; the pins written once; the forward-only permit |
| Credit data request | `credit.data_request` (`P10-TSK-006`) | `data_request_attempt` | `request_reference UNIQUE`; ★ `credit_record`; the retry edge only before `deadline_at`; the frozen-columns trigger (deadline and cadence included); the forward-only permit; `decision_request_id NOT NULL`, its foreign key added by `-014` |
| Underwriting case | `credit.underwriting_case` (`P10-TSK-018`) | `underwriting_case_event` | ★ `UNIQUE (decision_request_id)`; the four-eyes `CHECK`; the threshold; the reason count; no case for a `HARD_DECLINE`; assignment only while the request is `IN_REVIEW`; `CLOSED` only with its request |
| Credit policy version | `credit.credit_policy_version`, `credit_policy_rule` (`P10-TSK-012`) | `credit_policy_event` | One `PROPOSED` / one `ACTIVE` partial uniques; four-eyes `CHECK`; retirement only beside its successor (deferred); rules immutable from insert; advisory namespace `10` (extended to `hashtext(product)`) |
| Scorecard model version | `credit.scorecard_model_version`, `scorecard_band` (`P10-TSK-011`) | `scorecard_model_event` | The same shape, per model family; advisory namespace `10` registered here (`hashtext(family)`) |

Each state arrives with its producer's task, so a deferral removes states rather than stranding
them. Cutting `P10-TSK-021` (a second bureau and source selection, the plan's cut candidate)
removes a provider and a selection step and no state.

---

## 4. The exposure reservation (not a machine)

An `APPROVED` decision reserves its approved amount against the party's exposure from the commit of
its deciding transaction until the first of: `valid_until` passing on the database clock, or its
consumption by Phase 11's loan. Reserved exposure is the sum over the party's `APPROVED` decisions
with `valid_until > statement_timestamp()` and no consumption row, read **under the party's profile
row lock** in every deciding transaction (`INV-CRD-09`), so two decisions for one party serialise
and the second sees the first's reservation. There is no lapse writer: a lapse is a comparison
with the clock, not a state. **Consumption is a separate born-once fact**,
`credit_decision_consumption` (`UNIQUE (decision_id)`), created empty by `P10-TSK-016` and written
only by Phase 11's loan — never a column of `credit_decision`, which no role ever updates
(`INV-CRD-02`). Reserved exposure is therefore: `APPROVED`, `valid_until > statement_timestamp()`,
and no consumption row.

---

## 5. Points settled at the transition

The open points found while writing the machines from `PHASE_10_PLAN.md` were settled by the
owner at the transition (decisions G1–G11 and two more, recorded in `BACKLOG.md` §Phase 10) and
are folded into §§3–4 above, the plan and the ADRs named. None remains open; the task named builds
and proves each, and `P10-DOC-001` reads it against the code.

| # | Point | Settled as | Built by; ADR |
|---|---|---|---|
| O1 | `CreditDecisionRequestClosed` named only cancellation and expiry | Emitted on every closure — cancelled, expired or abandoned — carrying the closing status and an abandonment's reason (§3.1) | `-014`, `-015`; ADR-0087 |
| O2 | §14 scenario 31 had a `RECEIVED` data request turn `CONSENT_WITHDRAWN` | The data request stays `RECEIVED`; the gate is re-read for every source kind at the freeze and in the deciding transaction, and a withdrawal abandons the request (`ABANDONED`, `CONSENT_WITHDRAWN`), nothing frozen or decided (G11; §3.1, §3.2) | `-006`, `-015`, `-016`; ADR-0085 |
| O3 | The release `ASSIGNED → OPEN` had no route and no audit act | `POST /v1/operator/credit/review-cases/{id}/release` (`CREDIT_UNDERWRITE`, keyed), audited `credit.ReviewCaseReleased` (G9; §3.3) | `-018`; ADR-0089 |
| O4 | A disagreeing second approver had no edge, and a case whose request closed had no terminal | `AWAITING_SECOND → ASSIGNED` — the second approver refuses, reason required, audited `credit.ReviewSecondApprovalRefused`, back to the first underwriter; terminal `CLOSED` when the request closes undecided (G8; §3.3) | `-018`; ADR-0089 |
| O5 | A person's approval that the re-read exposure no longer admits | Bounded by the evaluation's approved amount and the exposure limit re-read under the profile lock; beyond it `422 credit.ExposureLimitExceeded`, nothing recorded, and the person decides again (G7; §3.1, §3.3) | `-016`, `-018`; ADR-0088, ADR-0089 |
| O6 | Consumption written as a column of the immutable decision | The born-once fact `credit_decision_consumption`, created empty by `-016`, written only by Phase 11 (G1; §3.5, §4) | `-016`; ADR-0087, ADR-0088 |
| O7 | A retry was not gated | The retry re-reads the gate; `UNAVAILABLE → CONSENT_WITHDRAWN` (§3.2) | `-006`; ADR-0085 |
| O8 | The collected sources came from one policy version and the pinned version from another | The versions are pinned on the request at `SUBMITTED → COLLECTING` and re-read `FOR SHARE` at the freeze and in the deciding transaction (G2; §3.1, §3.4) | `-015`; ADR-0086 |
