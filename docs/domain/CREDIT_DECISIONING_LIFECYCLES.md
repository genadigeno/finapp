# Credit Decisioning Lifecycles

*Planned by the Phase 9 → 10 transition (2026-10-07); built by Phase 10's tasks and read against the
code, statement by statement, by the Phase 10 exit review (`P10-DOC-001`, 2026-10-09).* Every machine,
edge, table, trigger, event and audit act below is now a description of the `credit` module as built
(`credit/src/main/java/com/finapp/credit`, migrations `credit V001`…`V015`, the doors in `app`);
where the review found the code and the design apart it corrected the document, or — where the
code was wrong — the code, each noted where it stands. *(This read "nothing built yet … the decided design, not a
description of code: no `credit` module exists" until the exit review.)*

Written on the `FX_AND_CROSS_BORDER_LIFECYCLES.md` precedent: the document that names a phase's
model is written before the phase's first task, from the decisions in ADR-0084…0089. The engineering
plan is [`PHASE_10_PLAN.md`](../project/PHASE_10_PLAN.md); the invariants are `INV-CRD-01`…`12` in
[`FINANCIAL_INVARIANTS.md`](FINANCIAL_INVARIANTS.md); the founding statement is
[`CREDIT_MODEL.md`](CREDIT_MODEL.md).

Related: ADR-0084 (the credit bounded context; the risk score is `risk`'s) · ADR-0085 (credit data
collection) · ADR-0086 (policy and model as versioned data) · ADR-0087 (the decision, the snapshot,
explanation and replay) · ADR-0088 (affordability and exposure) · ADR-0089 (underwriting) — all
written `Proposed` at the transition and accepted by the exit review (`P10-DOC-001`, 2026-10-09),
indexed in [`docs/adr/README.md`](../adr/README.md).

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

Every stored machine below is held at three ranks (`INV-LIFE-01/-02`, the ADR-0044 doctrine:
**states are earned by producers**), as built: **the domain** — each act's own precondition, a
conditional `UPDATE … WHERE status IN (the act's from-states)` in the store or a check under the
row lock (`UnderwritingCases`' `held()` and `awaitingSecond()`), so a loser finds the row moved;
**the database** — a status `CHECK` that is a membership list of the machine's states, and a
hand-written every-writer trigger (`*_permits_only_machine_edges`) admitting exactly the edges
below, with the frozen columns and the cross-table preconditions; and **an append-only history**
(§3.6 names each table and what it records). *(The plan's "generated `CHECK` and trigger, both from
the aggregate's `permittedTransitions()`" was not built: `permittedTransitions()` exists only on
`DecisionRequestStatus` and `UnderwritingCaseStatus` — checked by tests only
(`DecisionRequestTest` sweeps every pair against this document; `UnderwritingCaseDatabaseTest`
checks each act's edge is in the table) — and no production code calls `canTransitionTo`; `CreditDataRequestStatus`,
`CreditPolicyStatus` and `ScorecardStatus` have no transition table — `P10-DOC-001`.)* Every window —
the request's validity, a source's deadline, a record's maximum age, a decision's validity, every
sweep permit, a version's effective period — is judged **on the database clock**
(`statement_timestamp()` in SQL; `transaction_timestamp()` — one instant for the whole step — for the
freeze's freshness and deadline judgements, the policy and scorecard periods and their histories),
never on an instance clock. The application role holds no `DELETE` on any table of the `credit`
schema, and no `UPDATE` on any born-once fact but the one column-level `UPDATE (party_id)` on
`credit_profile` that exists only so `FOR UPDATE` is legal (§3.5).

**The lock order** (`PHASE_10_PLAN.md` §7; one `DISTRIBUTED_EXECUTION.md` §3 row). Every credit
writer takes an order-respecting subsequence of:

| Position | Row | Mode | Taken by |
|---|---|---|---|
| **L1** | the party's `credit_profile` | `FOR UPDATE` | deciding transactions only, and then first |
| **L2** | the `decision_request` | `FOR UPDATE` | every request step, the cancellation, the expiry and abandonment, every act of the case (assignment, release and the refusal of a second approval included — never the case alone) |
| **L3** | the `underwriting_case` | `FOR UPDATE` | the case's acts; the expiry or abandonment of an `IN_REVIEW` request |
| **L4** | the request's `data_request` rows, by id | `FOR UPDATE` (every taker; `JdbcDecisionSnapshotStore.lockDataRequestsOf`, ordered by id, and the store's `lock`) | the `COLLECTING → READY` step; the evaluating step, before L5, and the freeze within it; the recorder; the retry (alone, a suffix of the order) |
| **L5** | the pinned policy and model versions | `FOR SHARE` | the pin at `SUBMITTED → COLLECTING`; the evaluating step; the deciding transaction |

*(L4 read "`FOR UPDATE` (recording) / `FOR SHARE` (freeze)" until the exit review: every taker locks
`FOR UPDATE`. And the evaluating step took L5 before L4 — the freeze locked the data requests after
the versions were shared — until `P10-DOC-001` corrected the code: `DecisionProgress.evaluate` now
locks its data requests first (`SnapshotFreezer.lockDataRequests`), so the order above holds for
every writer.)* After L5 only inserts (snapshot, assessment, evaluation, decision, case, events,
audit) — and the re-collection's new data requests, inserted, never locked. The policy
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
   SUBMITTED ──data requests opened──> COLLECTING ──every source received (fresh or not),
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

**The progress step's order, as built** (`DecisionProgress`): under L2, an `IN_REVIEW` request goes to
its case (below); for every other open state the party's standing is read **first** — lost, the
request is `ABANDONED` (`STANDING_LOST`) — and only then the expiry, so a lapsed request whose party
has also lost standing is `ABANDONED`, never `EXPIRED`; then the state's own step.

| Edge | Trigger and actor | Transaction (lock order) | Effect | Event | Audit act |
|---|---|---|---|---|---|
| (birth) → `SUBMITTED` | The customer, `POST /v1/me/credit/decision-requests`, keyed; an identity with an active TOTP factor must act from a `MULTI_FACTOR` session (`403 identity.AssuranceRequired` before any write — the `P4-TSK-007` conditional step-up, not an unconditional MFA requirement) | The submission, under the `IdempotentExecutor` claim `credit.decision:CUSTOMER:<id>`; no row lock, in this order: the party's standing (its live party customer `ACTIVE` — `INV-KYC-05`'s projection of KYC's approval, read from `party`'s store by `app`'s `PartyCreditStanding`; no `kyc` read), the product offered (an `ACTIVE` policy), the bounds, and the consent gate for each source kind that active policy reads, each an in-transaction read; the profile ensured `ON CONFLICT DO NOTHING`; the request inserted under the partial `UNIQUE (party_id, product)` over the open states | The request born with `expires_at = statement_timestamp() +` the product's request validity and its first progress permit. Refusals, each writing nothing: `409 credit.ApplicantNotEligible`, `422 credit.ProductNotOffered` (no `ACTIVE` policy, or an unknown product), `422 credit.AmountOutOfRange`, `409 consent.ConsentRequired` (the platform's one consent refusal, naming the purpose; nothing asked), `409 credit.DecisionRequestOpen`; a malformed amount `422 api.ValidationFailed`, a missing key `422 api.IdempotencyKeyRequired` | `CreditDecisionRequested` | — (the customer's own keyed command: the idempotency record, the history row and the event are its trail) |
| `SUBMITTED → COLLECTING` | `CreditDecisionProgressSchedule` (every instance, leaderless) | The step: **L2**, conditional on `SUBMITTED` and `expires_at > statement_timestamp()`; standing re-read (above); **L5** the `ACTIVE` policy and scorecard versions `FOR SHARE` — **with either absent the step waits**, nothing written, and a later permit reads again; the consent gate re-read for every source kind the policy reads — a withdrawal abandons the request (`CONSENT_WITHDRAWN`), nothing pinned or opened; the versions pinned on the request (written once); one `data_request` inserted per source kind the pinned policy reads (ADR-0085's Tx1: reference minted and stored, gate read, the stamps of §3.2's birth) | Data requests `REQUESTED` (§3.2); the providers asked after commit, no connection held. A product whose policy reads no external source passes through `COLLECTING` to `READY` in one transaction, both history rows written | — | `credit.BureauDataRequested` / `credit.FinancialDataRequested`, one per data request opened (`INV-AUD-01`: the access is the act) |
| `COLLECTING → READY` | The progress step | **L2**, **L4** `FOR UPDATE`; conditional on `COLLECTING`, unexpired; per source kind the pinned policy reads, the **latest** data request (by `requested_at`, which `credit V015` keeps ordered) | Every required source's latest data request is `RECEIVED`, or `UNAVAILABLE` past its deadline (`deadline_at <= transaction_timestamp()`) — its attributes then frozen `ABSENT` with a recorded `SOURCE_UNAVAILABLE`, for the policy's fallback to decide (`INV-CRD-10`). A latest data request `CONSENT_WITHDRAWN` abandons the request instead (§14 scenario 7). **Freshness is not judged here** — a `RECEIVED` record of any age admits `READY`; the freeze judges it (`READY → COLLECTING`) | — (`CreditDataUnavailable` is emitted for the data request once its deadline passed, §3.2) | — |
| `READY → COLLECTING` | The progress step, at the freeze — the one backward edge (`PHASE_10_PLAN.md` §5, §14 scenario 9) | **L2**, **L4**, **L5**; a `RECEIVED` record found past the pinned policy's maximum age on the database clock (`INV-CRD-08`: `LEAST(retrieved_at, recorded_at) < transaction_timestamp() −` the maximum age — *corrected by `P10-DOC-001`, which made the age run from the earlier of the provider's stated retrieval and our recording*) — *and, since the Phase 10 → 11 transition (ADR-0085 §11), fresh when recorded (`retrieved_at >= recorded_at −` the maximum age) and the kind's only data request on the request*. Otherwise — a record already stale when recorded, or a second staleness — the edge is not taken: the kind is frozen unavailable (`ABSENT`, `SOURCE_UNAVAILABLE`) and the request goes on to `EVALUATED` under the policy's fallback | A new data request for that source (new reference), before any snapshot exists; the stale one stays `RECEIVED`, its record belonging to no snapshot. At most once per source kind per request (before the transition: bounded only by the request's validity) | — | `credit.BureauDataRequested` / `credit.FinancialDataRequested` (a new access) |
| `READY → EVALUATED` | The progress step | **L2**, **L4** `FOR UPDATE`, **L5** the request's pinned policy and model versions `FOR SHARE` (even if since retired) — L4 before L5 since `P10-DOC-001`; one transaction: the consent gate re-read for every source kind (a withdrawal abandons the request instead, nothing frozen — §14 scenario 31), freshness judged (above), snapshot frozen (sequence 1; canonical JSON, SHA-256; reserved exposure read; the risk seam's `NOT_ASSESSED`, the platform-exposure seam's zero and the party facts — always `ABSENT` in Phase 10 — recorded with their versions), assessment computed, evaluation run | Snapshot, assessment and evaluation born once (§3.5); the evaluation's outcome, ordered reason codes and every rule's result stored | `CreditAssessmentCreated` | — |
| `EVALUATED → DECIDED` | The deciding transaction, on an `APPROVE` or `DECLINE` (a `HARD_DECLINE` included) | **L1** the profile, **L2** the request (`EVALUATED`, `expires_at > statement_timestamp()`), standing re-read, **L5** the pinned versions `FOR SHARE`, the consent gate for every source kind re-read — either lost abandons the request instead; the reserved exposure re-read under L1 and, **only if it changed**, a successor snapshot (the next sequence: the same records, the new exposure), its assessment and its evaluation under the same pinned versions (`PHASE_10_PLAN.md` §12.7) | The `credit_decision` born naming the snapshot it was made from, with its reason codes in order, `decided_by` the platform (`decided_by_type` `SYSTEM`), `valid_until = statement_timestamp() +` the product's decision validity and the pinned versions; an `APPROVED` decision's amount now reserved (`INV-CRD-09`) | `CreditDecisionRecorded` (event version 2) | `credit.DecisionRecorded` |
| `EVALUATED → IN_REVIEW` | The deciding transaction, on a `REFER` | The same: **L1**, **L2**, standing re-read, **L5**, consent re-read, the exposure re-read (a changed exposure re-evaluates against a successor snapshot, and may turn the referral into a decision) | The `underwriting_case` born `OPEN` with its basis evaluation, the referral's ceiling (`approvable_minor`) and the product's four-eyes threshold (§3.3); the referral's reason codes stay on the basis evaluation and travel in `ManualReviewRequired` | `ManualReviewRequired` | — (a platform workflow step; the case's history and the event are its trail) |
| `IN_REVIEW → DECIDED` | The underwriter's decision (case `ASSIGNED → DECIDED`) or the second approval (case `AWAITING_SECOND → DECIDED`) | **L1**, **L2**, **L3**, standing re-read, **L5**, consent re-read, the exposure re-read; the transaction checks the case, **not** the request's expiry — a taken case is decided by its person whatever the request's validity | The decision born with `decided_by` the person (`decided_by_type` `EMPLOYEE`), the case's reason codes, the system evaluation kept as the case's basis. An approval is bounded by **the referral's ceiling** — the lesser of the case's `approvable_minor` and the deciding (successor) evaluation's ceiling — and by the exposure limit judged on the deciding snapshot with the person's amount, under L1: beyond either `422 credit.ExposureLimitExceeded`, nothing recorded, the case unchanged, and the person decides again | `CreditDecisionRecorded` | `credit.ReviewDecided` (reason required) or `credit.ReviewSecondApproval`, with `credit.DecisionRecorded` |
| `SUBMITTED \| COLLECTING \| READY → CANCELLED` | The customer, `POST …/{id}/cancellation`, keyed under its own scope `credit.decision-cancellation:CUSTOMER:<id>`, the same conditional step-up as the submission | **L2**, conditional on one of the three | Closed. Open data requests finish on their own (§3.2); their records belong to no snapshot, and the retry sweep stops asking for a closed request — *as built since `P10-DOC-001`, which found the sweep still re-asking a closed request's sources and made its claim skip them* (`JdbcCreditDataRequestStore.claimDue`). Refusals: `409 credit.RequestNotCancellable`; another party's or an unknown request `404 credit.NotFound` | `CreditDecisionRequestClosed` (status `CANCELLED`) | `credit.DecisionRequestCancelled` (the customer's act, the `fx.QuoteCancelled` precedent; no reason required) |
| any open state → `EXPIRED` (from `IN_REVIEW` only while its case is `OPEN`) | The progress step, on `expires_at <= statement_timestamp()`, the party's standing intact | **L2**; from `IN_REVIEW` also **L3**, conditional on the case `OPEN` | Closed: no decision came within the validity; an `OPEN` case moves `CLOSED` in the same transaction (§3.3). The expiry and the system decision use complementary conditionals on one clock, so exactly one of `DECIDED`, `EXPIRED` | `CreditDecisionRequestClosed` (status `EXPIRED`) | — |
| any open state → `ABANDONED` | The platform: the progress step finding a latest data request `CONSENT_WITHDRAWN` (§14 scenario 7); the `SUBMITTED → COLLECTING` step, the freeze or a deciding transaction — the system's or a person's — re-reading the consent gate and finding a withdrawal (§14 scenario 31); any step or deciding transaction finding the party's standing lost (§14 scenario 27) | **L2** (with **L3** from `IN_REVIEW`); inside a deciding transaction under **L1**, **L2** (and **L3**) | Closed with its reason, nothing frozen or decided; never `EXPIRED`. From `IN_REVIEW` the case moves `CLOSED` in the same transaction — while `OPEN` by the sweep (standing lost only), while taken only by its person's own deciding transaction | `CreditDecisionRequestClosed` (status `ABANDONED`, reason `STANDING_LOST` or `CONSENT_WITHDRAWN`) | — |

- **Terminal:** `DECIDED`, `CANCELLED`, `EXPIRED`, `ABANDONED`. `DECIDED` carries exactly one
  `CreditDecision`.
- **The open states** (the partial unique's predicate, the expiry sweep's scope): `SUBMITTED`,
  `COLLECTING`, `READY`, `EVALUATED`, `IN_REVIEW`.

| Invalid edge | Why | Refused at the database, for every writer, by |
|---|---|---|
| any edge out of `DECIDED`, `CANCELLED`, `EXPIRED`, `ABANDONED` | terminal | the edge trigger `decision_request_permits_only_machine_edges` (`credit V010`; re-created by `V011` and `V013`), which also freezes the terms, validity and correlation from birth and writes the pins once; the status `CHECK` is a membership list |
| `SUBMITTED → READY \| EVALUATED \| IN_REVIEW \| DECIDED` | collection is never skipped (a source-less policy passes through `COLLECTING`) | the edge trigger |
| `COLLECTING → EVALUATED \| IN_REVIEW \| DECIDED` | no snapshot, no evaluation | the edge trigger; `→ EVALUATED` refused without its `policy_evaluation` |
| `READY → IN_REVIEW \| DECIDED` | no evaluation | the edge trigger; `→ IN_REVIEW` refused without an `OPEN` `underwriting_case` beside it (`V013`), `→ DECIDED` without its `credit_decision` (`V011`) |
| a request leaving `IN_REVIEW` with its case not terminal beside it | the request and its case move together | the deferred constraint trigger `decision_request_leaves_review_with_its_case` (`V013`): at commit, `DECIDED` only beside a `DECIDED` case, a closure only beside a `CLOSED` one |
| `EVALUATED → COLLECTING \| READY` | a frozen snapshot is never re-collected; a stale one makes a new request | the edge trigger |
| `EVALUATED \| IN_REVIEW → CANCELLED` | the evaluation is a fact; withdrawing then is a decision the applicant receives | the edge trigger |
| `IN_REVIEW → EVALUATED` | a referral is decided by a person, never re-run by the system | the edge trigger |
| `IN_REVIEW → EXPIRED` once the case is `ASSIGNED` or `AWAITING_SECOND` | a taken case is decided by its person | the edge trigger, reading the case's status under the request's lock |
| `→ EXPIRED` while `expires_at > statement_timestamp()`; `EVALUATED → DECIDED \| IN_REVIEW` while `expires_at <= statement_timestamp()` | the complementary clock conditionals — the system neither decides nor refers an expired request (`V013` added `IN_REVIEW`) | the edge trigger, on `statement_timestamp()` |
| `→ ABANDONED` without a reason, or with one outside {`STANDING_LOST`, `CONSENT_WITHDRAWN`} | a platform closure always says why | the `CHECK` pairing status and closure reason |
| a second open request for one (party, product) | one open request per product | the partial `UNIQUE (party_id, product)` over the open states |

### 3.2 Credit data request (`credit.data_request`)

```
 (opened by the request's collection step: reference minted, gate read)
        │
        v
   REQUESTED ──answer recorded──> RECEIVED
     │  │  ^
     │  │  └──the retry claim, same reference, before the deadline──┐
     │  └──timeout · refusal · malformed · unknown status──> UNAVAILABLE ──past the deadline: final
     │
     └──gate closed at recording, or at the retry (after its claim)──> CONSENT_WITHDRAWN

  (UNAVAILABLE ──> CONSENT_WITHDRAWN is admitted by the edge trigger; no writer takes it.)
```

| Edge | Trigger and actor | Transaction (lock order) | Effect | Event | Audit act |
|---|---|---|---|---|---|
| (birth) → `REQUESTED` | The decision request's `SUBMITTED → COLLECTING` or `READY → COLLECTING` step | Inside that step's **L2** (an insert): `request_reference UNIQUE` minted and stored before any call; the provider selected by the kind's configured order (`P10-TSK-021`) and stamped; the gate for the source kind's purpose (`CREDIT_BUREAU_ACCESS` / `FINANCIAL_DATA_ACCESS`) read; the trigger stamps `requested_at = GREATEST(statement_timestamp(),` the decision request's latest data request `+ 1 µs)` (`credit V015`, so a re-collection born on a clock that stepped back still reads as the latest), and from it `deadline_at` (the kind's collection window) and the first permit (its retry cadence) — the window and the cadence arrive with the insert from the source kind's configuration and are frozen, so a configuration change never moves an open request's deadline | The provider asked after commit, holding no connection, under a bounded timeout | — | `credit.BureauDataRequested` / `credit.FinancialDataRequested` |
| `REQUESTED → RECEIVED` | The recorder (ADR-0085's Tx2), on the asking thread or the retry sweep's | **L4** alone (a suffix of the order); the gate re-read (`INV-CRD-03`) | The `credit_record` born once (`UNIQUE (data_request_id)`), its attributes normalised (a money value in another currency is normalised as partial data — the attribute `ABSENT` with the recorded `CURRENCY_NOT_SUPPORTED` marker, never a conversion and never an error); the raw answer kept encrypted in `credit_evidence` with `retain_until`; an attempt row | `CreditDataCollected` | — (the access was audited when opened) |
| `REQUESTED → UNAVAILABLE` | The recorder; or, *since the Phase 10 → 11 transition (ADR-0085 §11)*, the recorder's own fallback when Tx2 could not record an answer; or the overdue report past the deadline | **L4**; a timeout, refusal, 5xx, a malformed answer (evidence kept, never parsed) or an unknown status (`INV-LIFE-03`). An answer Tx2 refused (another provider's, a value a `CHECK` refuses, an instant past the database's range) is recorded in a transaction of its own — the gate re-read, only from `REQUESTED` — as an `UNRECORDED` attempt (credit `V020`), its bytes kept. A `REQUESTED` row past `deadline_at` is moved by `claimOverdue` (one `UPDATE … FOR UPDATE SKIP LOCKED`), which also sets `unavailable_reported` | An attempt row (none at the report); nothing parsed into attributes; the permit re-stamped `statement_timestamp() +` the row's stamped cadence | `CreditDataUnavailable`, once, when the report moves it | — |
| `REQUESTED → CONSENT_WITHDRAWN` | The recorder, finding the gate closed; or the retry, finding it closed under the row lock after its claim (below) | **L4** | An attempt row `CONSENT_WITHDRAWN`. At the recorder the payload is discarded unread and the evidence row records only that a response arrived; at the retry nothing is asked. The decision request is then `ABANDONED` (`CONSENT_WITHDRAWN`) by its next progress step | — | — |
| `UNAVAILABLE → REQUESTED` | `CreditDataRetrySchedule` (every instance, leaderless): **the claim statement itself** | One `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED)` over due rows — `REQUESTED` or `UNAVAILABLE`, with `deadline_at > statement_timestamp()` and not yet reported (the deadline bound on a `REQUESTED` row since the Phase 10 → 11 transition) — **of an open decision request** (since `P10-DOC-001`; the ask re-reads it before the pull since the transition), stamping the permit; the trigger refuses the edge at or past `deadline_at`. The claim commits; then the retry takes **L4**, re-reads the gate (closed: `REQUESTED → CONSENT_WITHDRAWN`, nothing asked) and asks under the **same** reference — the provider dedupes, so one pull is counted | No attempt row at the claim; the answer's attempt row (`PRIMARY KEY (data_request_id, attempt)`) is written by the recorder | — | — (the same access under the same reference) |

*(As designed, a retry re-read the gate on an `UNAVAILABLE` row and took `UNAVAILABLE → CONSENT_WITHDRAWN`
itself; as built the claim has already moved the row `REQUESTED`, so the closed gate is met there. The
trigger still admits `UNAVAILABLE → CONSENT_WITHDRAWN`, and no writer takes it — `P10-DOC-001`.)*

- **Not an edge:** a `REQUESTED` data request past its permit (the answer lost) is claimed and re-asked
  by the retry sweep under the same reference, the permit re-stamped `statement_timestamp() +` its
  cadence (a database-stamped permit that binds nothing — no trigger orders it); its status does not
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
| any edge out of `RECEIVED` or `CONSENT_WITHDRAWN` | terminal | the edge trigger `data_request_permits_only_machine_edges` (`credit V004`; re-created by `V015`); the status `CHECK` is a membership list |
| `→ RECEIVED` without its record; a second record | one record per answered request | the domain — the status and the record are written in one recording transaction under L4, and the freezer fails closed on a `RECEIVED` request without its record (`SnapshotFreezer`: an `IllegalStateException`, nothing frozen); the second record ★ `UNIQUE (credit_record.data_request_id)`. *(Read "the edge trigger" until the exit review: no trigger reads the record.)* |
| `UNAVAILABLE → RECEIVED` | an answer is recorded only against an open attempt | the edge trigger |
| `UNAVAILABLE → REQUESTED` at or past `deadline_at` | the source's deadline is final | the edge trigger, on `statement_timestamp()` |
| a changed identity, party, product, reference, source kind, provider, cadence, window, `requested_at` or deadline; attempts falling; a reported request unreported | the reference is what the provider dedupes on; the deadline and cadence are stamped once, at birth | the same edge trigger's frozen-column checks; `request_reference UNIQUE`. *(The "forward-only permit trigger" was not built: the permit is re-stamped from the database clock by its writers and binds nothing — `V004`'s header.)* |

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
| (birth) → `OPEN` | The deciding transaction, on a `REFER` (§3.1) | Under its **L1**, **L2**; `UNIQUE (decision_request_id)`; the birth trigger admits it only for an `EVALUATED` request with its terms, beside that request's `REFER` evaluation with no triggered `HARD_DECLINE` rule | The case with its basis (`basis_evaluation_id`), **the referral's ceiling** `approvable_minor` — the request capped by every `CAP_AMOUNT` rule the basis evaluation triggered (`UnderwritingCases.ceiling`; the auto-approval ceiling is not applied, it bounds only an automated approval) — and the product's four-eyes threshold copied onto it. The referral's reason codes are not stored on the case: they are the basis evaluation's, and `ManualReviewRequired` carries them | `ManualReviewRequired` | — |
| `OPEN → ASSIGNED` | An underwriter (`CREDIT_UNDERWRITE`), `POST /v1/operator/credit/review-cases/{id}/assignment`, keyed `credit.review:EMPLOYEE:<id>` | **L2** then **L3**, conditional on the case `OPEN` and the request `IN_REVIEW` — so the assignment and the expiry serialise on the request row | The assignee recorded; from here the request no longer expires. Loser: `409 credit.CaseTaken` | — (`UnderwritingStarted` refused, `PHASE_10_PLAN.md` §10) | `credit.ReviewCaseAssigned` |
| `ASSIGNED → OPEN` | The assignee, `POST /v1/operator/credit/review-cases/{id}/release`, keyed | **L2**, **L3**, conditional on `ASSIGNED` and the actor the assignee | Back in the queue; the request's validity governs again (an already-lapsed request is expired by the next sweep, the case `CLOSED`) | — | `credit.ReviewCaseReleased` |
| `ASSIGNED → DECIDED` | The assignee, `POST …/{id}/decision`: `DECLINED`, or `APPROVED` at or below the threshold, with ≥ 1 reason code and a reason | The deciding transaction: **L1**, **L2**, **L3**, standing re-read, **L5**, consent re-read, the exposure re-read; no expiry check | The request `IN_REVIEW → DECIDED` and the decision born (§3.1). Refusals: `422 credit.ReasonRequired` (no code, no reason, a code twice, or a code that is not an adverse catalogue code — `CRD-AUTO-APPROVAL-CEILING` among them), `422 api.ValidationFailed` (a judgement not well formed: an approval without a positive amount in the product's currency, or below the product's minimum amount; a decline with an amount), `422 credit.HardDeclineNotOverridable`, `422 credit.ExposureLimitExceeded` (an approval above the referral's ceiling — the lesser of `approvable_minor` and the deciding evaluation's — or beyond the exposure limit judged with the person's amount — nothing recorded, the case stays `ASSIGNED`, the person decides again), `409 credit.CaseTaken` (not the holder, or the case moved) | `CreditDecisionRecorded` | `credit.ReviewDecided` (reason required), `credit.DecisionRecorded` |
| `ASSIGNED → AWAITING_SECOND` | The assignee, `APPROVED` above the threshold, with ≥ 1 reason code and a reason | **L2**, **L3** (no decision is recorded, so no L1, and no standing, consent or exposure read) | The first decision (outcome, amount, reason codes, reason, `first_decided_by`) recorded on the case; no `credit_decision` yet. The amount is bounded by the case's `approvable_minor` (refused `422 credit.ExposureLimitExceeded` above it); the same reason and judgement refusals as above | — | `credit.ReviewDecided` (reason required) |
| `AWAITING_SECOND → DECIDED` | A **different** underwriter, `POST …/{id}/second-approval` (approve) | The deciding transaction: **L1**, **L2**, **L3**, standing re-read, **L5**, consent re-read, the exposure re-read; no expiry check | The decision born with the first decision's content (`INV-CRD-11`, `INV-AUD-04`); the deciding transaction's bounds judged again for it. Refusals: `403 credit.SelfApprovalRefused`; `422 credit.ExposureLimitExceeded` (the case stays `AWAITING_SECOND`; the second approver may refuse it back) | `CreditDecisionRecorded` | `credit.ReviewSecondApproval`, `credit.DecisionRecorded` |
| `AWAITING_SECOND → ASSIGNED` | A **different** underwriter who disagrees, `POST …/{id}/second-approval` (refuse), with a reason | **L2**, **L3**, conditional on `AWAITING_SECOND`; the first decider refused `403 credit.SelfApprovalRefused` by the domain, a missing reason `422 credit.ReasonRequired` | The first decision cleared from the case (kept in its history); the case back with its first underwriter, who decides again | — | `credit.ReviewSecondApprovalRefused` (reason required) |
| `OPEN → CLOSED` | The request's expiry, or the progress sweep abandoning it (standing lost) | Inside the request's closing transaction: **L2**, **L3**, conditional on the case `OPEN` | Closed with the request's reason (`EXPIRED`, `STANDING_LOST`); nothing decided | — (the request's `CreditDecisionRequestClosed`) | — |
| `ASSIGNED \| AWAITING_SECOND → CLOSED` | Its person's own deciding transaction (the holder's decision, or the second approval) finding the party's standing lost or the consent withdrawn | Inside that transaction: **L1**, **L2**, **L3** | The request `ABANDONED` with its reason and the case `CLOSED` with it; nothing decided | — (the request's `CreditDecisionRequestClosed`) | The person's act — `credit.ReviewDecided` or `credit.ReviewSecondApproval` — recorded with outcome `FAILED`, naming the abandonment |

- **Terminal:** `DECIDED`, `CLOSED`.
- **What a person may not decide** (ADR-0089): approve a request whose evaluation included a
  `HARD_DECLINE` (the evaluator never refers one, and a case for one is unstorable); decide with no
  reason code or no reason, or with a code that is not an adverse catalogue code (a person's
  judgement is about the applicant: `CRD-AUTO-APPROVAL-CEILING` speaks of an automated approval,
  refused `422 credit.ReasonRequired`); approve an amount that is not positive, not in the
  product's currency or below the product's minimum (`422 api.ValidationFailed`); second-approve
  their own first decision; approve more than **the referral's ceiling** — the case's
  `approvable_minor` (a `REFER` evaluation approves no amount, `credit V009`, so the ceiling is the
  request capped by the basis evaluation's triggered caps, stamped at the case's birth), and at the
  deciding transaction the lesser of it and the deciding evaluation's ceiling — or beyond the
  exposure limit judged under the profile lock with the person's amount (`INV-CRD-09`) — refused
  `422 credit.ExposureLimitExceeded`, nothing recorded, and the person decides again (a decline, or a
  smaller approval). *(The ceiling read "the evaluation's approved amount" until the exit review.)*
- **A taken case is never stuck:** once `ASSIGNED` or `AWAITING_SECOND`, it is decided by its
  person whatever the request's validity — the person's deciding transaction checks the case, not
  the request's expiry. **Only an unassigned `OPEN` case expires, and it does so through its
  request:** the request's `IN_REVIEW → EXPIRED` takes L2 then L3 and is admitted only while the
  case is `OPEN`, closing the case `CLOSED`; the assignment takes the same two locks in the same
  order and is admitted only while the request is `IN_REVIEW`. Exactly one wins.
- **A withdrawn consent does not close an `OPEN` case** (G8, as built): the progress step closes an
  `OPEN` case's request only for lost standing or expiry — it does not re-read the consent gate. A
  withdrawal under an `OPEN` case surfaces at the person's deciding transaction (once the case is
  taken and decided: the request `ABANDONED` with `CONSENT_WITHDRAWN`, the case `CLOSED`), or, while
  the case stays `OPEN`, the request expires with it.

| Invalid edge | Why | Refused at the database, for every writer, by |
|---|---|---|
| any edge out of `DECIDED` or `CLOSED` | terminal; a change of mind is a new request | the edge trigger (`underwriting_case_permits_only_machine_edges`, `credit V013`), which also freezes the request, basis, ceiling and threshold from birth |
| `→ DECIDED` without the request's decision | the case and its request move together | the edge trigger: `DECIDED` only beside a `DECIDED` request and its `credit_decision` |
| `OPEN → DECIDED \| AWAITING_SECOND` | a decision needs an assignee | the edge trigger |
| `AWAITING_SECOND → OPEN` | a recorded first decision is not released to the queue; a disagreeing second approver refuses it back to its first underwriter (`AWAITING_SECOND → ASSIGNED`) | the edge trigger |
| `AWAITING_SECOND → ASSIGNED` by the first decider, or without a reason | the refusal is the second person's, reasoned | the event row's `CHECK underwriting_case_event_second_person` (`V013`: a second person's act — refusal or approval — names the first decider, its actor is not that decider, and a refusal carries a reason), the history row written in the edge's transaction; the domain refuses first (`403 credit.SelfApprovalRefused`, `422 credit.ReasonRequired`). The case trigger itself does not read the actor |
| `→ CLOSED` while the request is still open | a case closes only with its request, in the request's closing transaction | the edge trigger, reading the request's status under L2 |
| `OPEN → ASSIGNED` under a request not `IN_REVIEW` | the request was closed first | the edge trigger, reading the request's status under L2 |
| `ASSIGNED → DECIDED` approving above the case's threshold | four-eyes above the threshold | the state-shape `CHECK`: `DECIDED` carries a second approver exactly when the approval is above the copied threshold |
| `AWAITING_SECOND → DECIDED` by the first decider | four-eyes | the `CHECK (second_decided_by <> first_decided_by)` |
| `→ DECIDED \| AWAITING_SECOND` with no reason code | `INV-CRD-11` | the first-decision `CHECK` (codes held on the case row, at least one; each the catalogue's by the edge trigger) |
| a first approval above the referral's ceiling | G7 | the `CHECK underwriting_case_first_amount_bounded` (`first_approved_minor BETWEEN 1 AND approvable_minor`) |
| a case whose basis evaluation carries a `HARD_DECLINE` | ADR-0089 §2 | the case's `BEFORE INSERT` trigger reading the evaluation |
| a second case for one request | one review per referral | ★ `UNIQUE (decision_request_id)` |

### 3.4 Credit policy version and scorecard model version

One machine, two tables — `credit_policy_version` (+ `credit_policy_rule`) per product,
`P10-TSK-012`; `scorecard_model_version` (+ `scorecard_band`) per model family, `P10-TSK-011` — on
the Phase 9 pricing and corridor policy shape (`FX_AND_CROSS_BORDER_LIFECYCLES.md` §3.9), with
**no seed exemption**: version 1 of each is inserted as a proposal and activated by two persons.

```
PROPOSED ──a different approver──> ACTIVE ──only beside its successor's activation──> RETIRED
    └──any holder, the proposer included (a withdrawal), reasoned──> REJECTED
```

| Edge | Trigger and actor | Transaction (lock order) | Effect | Event | Audit act |
|---|---|---|---|---|---|
| (birth) → `PROPOSED` | `CREDIT_POLICY_ADMINISTER`, `POST /v1/operator/credit/policies` (or `/scorecards`), the full rule set or points table as the body, keyed | **A0** (namespace `10`, blocking), then inserts: the version and its rule or band rows, born together in one transaction; one `PROPOSED` per product (family) | The rules and bands immutable for every writer from insert; a correction is a rejection and a new proposal. Refusals: `409 credit.ProposalPending`; `422 credit.PolicyIncomplete` (a policy not well formed — a rule ill typed or with a non-adverse or uncatalogued code, an amount in another currency — or lacking a rule guaranteed to fall back, with `CRD-SOURCE-UNAVAILABLE`, for a source kind it reads; **or, since `P10-DOC-001`, lacking a rule guaranteed to stop an approval past its maximum exposure** (below)); `422 credit.ScorecardInvalid` (a points table with overlapping or gapped bands, a missing absent band, or a code outside the vocabulary); `422 credit.ReasonRequired` (no proposal reason) | — | `credit.PolicyVersionProposed` / `credit.ScorecardVersionProposed` (reason required) |
| `PROPOSED → ACTIVE` | A **different** holder, `POST …/{versionId}/approval`, reasoned, keyed | **A1** the proposal `FOR UPDATE`, conditional on `PROPOSED`; **A2** the active row `FOR UPDATE` | `effective_from = GREATEST(transaction_timestamp(), the predecessor's effective_to)` (`X-TSK-017`); the predecessor retired first, in the same transaction. Losers: `409 credit.PolicyStale`; the proposer: `403 credit.SelfApprovalRefused` | `CreditPolicyVersionActivated` / `ScorecardModelVersionActivated` (with the predecessor) | `credit.PolicyVersionActivated` / `credit.ScorecardVersionActivated` (reason required) |
| `ACTIVE → RETIRED` | Only the successor's activation | Inside it, under **A2** | `effective_to = GREATEST(transaction_timestamp(), effective_from + 1 µs)` (`X-TSK-017`), and the successor starts there, so the periods abut and never overlap, even on a database clock that stepped back | (carried by the successor's activation event) | (carried by the activation's act) |
| `PROPOSED → REJECTED` | Any `CREDIT_POLICY_ADMINISTER` holder — the proposer included, which is a withdrawal — `POST …/{versionId}/rejection`, reasoned, keyed | **A1**, conditional on `PROPOSED` | The proposal closed | — | `credit.PolicyVersionRejected` / `credit.ScorecardVersionRejected` (reason required) |

- **Terminal:** `RETIRED`, `REJECTED`. A retired version is never reactivated: a new proposal is.
- **A policy bounds its own exposure** (`INV-CRD-09`; added by `P10-DOC-001`): the evaluator judges
  exposure only through rules, so the proposal door refuses `422 credit.PolicyIncomplete` a policy
  with no rule guaranteed to stop an approval past its maximum exposure
  (`CreditPolicy.boundsExposure`): `EXPOSURE_HEADROOM LT | LE x` with `x ≥ 0`, or `EXPOSURE GT | GE x`
  with `x ≤` the maximum exposure, with the effect `HARD_DECLINE`, `DECLINE` or `REFER`. Judged at
  proposal, not at construction, so every stored version still reads back.
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
| any edge out of `RETIRED` or `REJECTED` | terminal | the edge trigger (`credit_policy_permits_only_machine_edges`, `credit V008`; `scorecard_model_permits_only_machine_edges`, `V006`; both re-created by `V014`); the status `CHECK` is a membership list |
| `PROPOSED → RETIRED`; `ACTIVE → PROPOSED \| REJECTED` | retirement is only a successor's consequence | the edge trigger |
| an `ACTIVE` retired without a successor activated in the same transaction | at most one, and at every instant after the first activation exactly one, active version | a deferred constraint trigger |
| a second `PROPOSED` or `ACTIVE` per product (family) | one of each | partial uniques (the `PROPOSED` one the backstop behind namespace `10`) |
| activation by the proposer | four-eyes (`INV-AUD-04`) | the `CHECK (status NOT IN ('ACTIVE', 'RETIRED') OR decided_by <> proposed_by)` (`credit_policy_four_eyes`, `scorecard_model_four_eyes`), no seed exemption; a rejection by the proposer is a lawful withdrawal |
| any change to a version's content; a rule or band inserted outside its version's proposing transaction, updated or deleted | `INV-CRD-05`; the approver approves what was proposed | the freeze trigger on the version; the every-writer trigger on its rule and band rows |

### 3.5 The born-once facts

Facts, not machines: each is one row, written once, with no status column to walk, held
append-only (an every-writer trigger refuses `UPDATE` and `DELETE`, and a statement trigger
`TRUNCATE`; the application role is granted `INSERT` and `SELECT` only — with two exceptions, as
built: on `credit_profile` also `UPDATE (party_id)`, granted only because PostgreSQL refuses
`SELECT … FOR UPDATE` without an `UPDATE` privilege on some column, while its trigger refuses every
actual update for every role; and on `credit_evidence` no table `SELECT`, only the column `SELECT`
on `(id, data_request_id, attempt, duplicate, consent_withdrawn)` that `credit V012` grants so the
evidence read can name its row — the ciphertext, nonce, checksum and length stay unreadable). *(This
read "`INSERT` and `SELECT` only, and on `credit_evidence` not even `SELECT`" until the exit review.)*
Each contended one is a ★ born-once arbiter with a lock-bypass probe in its task.

| Fact | Table | Born (transaction, lock order) | Once by |
|---|---|---|---|
| Credit profile | `credit_profile` | the submission (ensure), `P10-TSK-004` | `UNIQUE (party_id)`, `ON CONFLICT DO NOTHING`. Holds no figures; it is the L1 lock target |
| Credit record | `credit_record` | the recorder's `REQUESTED → RECEIVED`, under L4 | ★ `UNIQUE (data_request_id)` |
| Credit evidence | `credit_evidence` | every recorded answer — a duplicate marked, a consent-withdrawn arrival as a bare marker | Append-only; AES-256-GCM under credit's own key, the evidence id as associated data; `retain_until = recorded_at +` the product's evidence retention, stamped by the trigger; read only through the evidence-read door's definer function (`POST /v1/operator/credit/records/{id}/evidence-read`, reasoned, audited `credit.EvidenceRead`) |
| Decision snapshot | `decision_snapshot` | sequence 1 at `READY → EVALUATED`, under L2, L4, L5; a successor (the next sequence) only in a deciding transaction that found the reserved exposure changed, under L1 | ★ `UNIQUE (decision_request_id, sequence)`; one snapshot per evaluation; the canonical JSON and its SHA-256 re-verified by replay (`INV-CRD-07`) |
| Credit assessment | `credit_assessment` | the same transaction as its snapshot | ★ `UNIQUE (snapshot_id)` |
| Policy evaluation | `policy_evaluation` (+ `policy_evaluation_rule`) | the same transaction | ★ `UNIQUE (assessment_id)`; the ordered reason codes on the evaluation, and **every** rule's result in `policy_evaluation_rule` — ordinal, code, effect, `assessed`, `triggered` (a rule that could not read its subject did not trigger, by `CHECK`), born in the evaluating transaction |
| Credit decision | `credit_decision` (+ `credit_decision_reason`) | the deciding transaction, under L1 | ★ `UNIQUE (decision_request_id)`; names the snapshot it was made from (`INV-CRD-06`); **never `UPDATE` or `DELETE` for any role** (privilege and trigger, `INV-CRD-02`); a decline, or an approval below its request, without a reason row refused at commit (the deferred `credit_decision_is_explained`); `decided_by` the platform or a person, with `decided_by_type` `SYSTEM` or `EMPLOYEE`; no consumption column |
| Decision consumption | `credit_decision_consumption` | created empty by `P10-TSK-016`; written only by Phase 11's loan | ★ `UNIQUE (decision_id)`; the fact that ends an approval's reservation (§4) |
| Reason code | `reason_code` | migration-seeded, `P10-TSK-001` | The closed catalogue: code, category, customer text, adverse flag; `SELECT` only |

### 3.6 The ranks, per machine

*(This section's table read "Table (planned task)" and listed "the forward-only permit" twice until
the exit review; neither permit trigger exists.)*

| Machine | Table (built by) | History (what it records) | Database rank beyond the edge trigger |
|---|---|---|---|
| Decision request | `credit.decision_request` (born `P10-TSK-014`, `V010`; progressed and pinned `-015`; decided `-016`, `V011`; reviewed `-018`, `V013`) | `decision_request_event`: from, to, actor id, actor type, reason, `occurred_at` (database) | Partial `UNIQUE (party_id, product)` over the open states; `→ EVALUATED`, `→ DECIDED` each refused without its row, `→ IN_REVIEW` without an `OPEN` case; the complementary expiry and decision (and referral) clock conditionals; `IN_REVIEW → EXPIRED` only beside an `OPEN` case; the deferred `decision_request_leaves_review_with_its_case`; the closure-reason `CHECK`; the terms frozen and the pins written once. The progress permit is re-stamped from the database clock by the claim and binds nothing |
| Credit data request | `credit.data_request` (`P10-TSK-006`, `V004`; the birth stamp `P10-TST-001`, `V015`) | `data_request_attempt`: attempt number, outcome, `answered_at` (database) — **no actor or reason columns**; one row per recorded answer or found withdrawal, none for a claim | `request_reference UNIQUE`; ★ `credit_record`; the retry edge only before `deadline_at`; the frozen columns (deadline and cadence included), attempts only growing and a report never undone, all in the edge trigger; `decision_request_id NOT NULL`, its foreign key `(decision_request_id, party_id)` added by `-014`'s `V010` |
| Underwriting case | `credit.underwriting_case` (`P10-TSK-018`, `V013`) | `underwriting_case_event`: from, to, actor id, actor type, a decision's outcome, amount, reason codes and reason, the first decider a second person's act answers, `occurred_at` | ★ `UNIQUE (decision_request_id)`; the four-eyes `CHECK`; the copied threshold and the referral's ceiling; the reason count; each state's shape; no case for a `HARD_DECLINE`; assignment only while the request is `IN_REVIEW`; `DECIDED` only beside the request's decision; `CLOSED` only with its request and its reason |
| Credit policy version | `credit.credit_policy_version`, `credit_policy_rule` (`P10-TSK-012`, `V008`; periods `X-TSK-017`, `V014`) | `credit_policy_event`: from, to, actor id, reason, `occurred_at` — **no actor type** | One `PROPOSED` / one `ACTIVE` partial uniques; four-eyes `CHECK` on activation; retirement only beside its successor (deferred); rules born in the proposing transaction and immutable from insert; advisory namespace `10` (extended to `hashtext(product)`) |
| Scorecard model version | `credit.scorecard_model_version`, `scorecard_band` (`P10-TSK-011`, `V006`; periods `V014`) | `scorecard_model_event`: as the policy's — no actor type | The same shape, per model family; advisory namespace `10` registered here (`hashtext(family)`) |

Each state arrived with its producer's task. Nothing was cut: `P10-TSK-021` (a second bureau and
source selection, the plan's cut candidate) landed — `bureau-sim-b` and the per-kind provider
order stamped on each data request at its birth — adding a provider and a selection step and no
state.

---

## 4. The exposure reservation (not a machine)

An `APPROVED` decision reserves its approved amount against the party's exposure from the commit of
its deciding transaction until the first of: `valid_until` passing on the database clock, or its
consumption by Phase 11's loan. Reserved exposure is the sum over the party's `APPROVED` decisions
**in the product's currency** (`d.currency = ?` — `JdbcReservedExposure`, version 2) with
`valid_until > statement_timestamp()` and no consumption row, read **under the party's profile
row lock** in every deciding transaction (`INV-CRD-09`), so two decisions for one party serialise
and the second sees the first's reservation. There is no lapse writer: a lapse is a comparison
with the clock, not a state. **The limit itself is a rule's**: the evaluator judges exposure only
through the pinned policy's rules, and since `P10-DOC-001` no policy without a rule bounding it is
accepted at proposal (§3.4). **Consumption is a separate born-once fact**,
`credit_decision_consumption` (`UNIQUE (decision_id)`), created empty by `P10-TSK-016` and written
only by Phase 11's loan — never a column of `credit_decision`, which no role ever updates
(`INV-CRD-02`). Reserved exposure is therefore: `APPROVED`, in the currency asked, `valid_until >
statement_timestamp()`, and no consumption row.

---

## 5. Points settled at the transition

The open points found while writing the machines from `PHASE_10_PLAN.md` were settled by the
owner at the transition (decisions G1–G11 and two more, recorded in `BACKLOG.md` §Phase 10) and
are folded into §§3–4 above, the plan and the ADRs named. None remains open; the task named built
and proved each, and `P10-DOC-001` read each against the code — O5 and O7 as built differ from
their settlement, each noted in its row. *(This read "the task named builds and proves each, and
`P10-DOC-001` reads it" until the exit review.)*

| # | Point | Settled as | Built by; ADR |
|---|---|---|---|
| O1 | `CreditDecisionRequestClosed` named only cancellation and expiry | Emitted on every closure — cancelled, expired or abandoned — carrying the closing status and an abandonment's reason (§3.1) | `-014`, `-015`; ADR-0087 |
| O2 | §14 scenario 31 had a `RECEIVED` data request turn `CONSENT_WITHDRAWN` | The data request stays `RECEIVED`; the gate is re-read for every source kind at the freeze and in the deciding transaction, and a withdrawal abandons the request (`ABANDONED`, `CONSENT_WITHDRAWN`), nothing frozen or decided (G11; §3.1, §3.2) | `-006`, `-015`, `-016`; ADR-0085 |
| O3 | The release `ASSIGNED → OPEN` had no route and no audit act | `POST /v1/operator/credit/review-cases/{id}/release` (`CREDIT_UNDERWRITE`, keyed), audited `credit.ReviewCaseReleased` (G9; §3.3) | `-018`; ADR-0089 |
| O4 | A disagreeing second approver had no edge, and a case whose request closed had no terminal | `AWAITING_SECOND → ASSIGNED` — the second approver refuses, reason required, audited `credit.ReviewSecondApprovalRefused`, back to the first underwriter; terminal `CLOSED` when the request closes undecided (G8; §3.3) | `-018`; ADR-0089 |
| O5 | A person's approval that the re-read exposure no longer admits | Bounded by the referral's ceiling — the case's `approvable_minor`, the request capped by every `CAP_AMOUNT` rule the basis evaluation triggered (a `REFER` evaluation has no approved amount, `V009`), the lesser of it and the deciding evaluation's ceiling at the deciding transaction — and by the exposure limit judged under the profile lock with the person's amount; beyond it `422 credit.ExposureLimitExceeded`, nothing recorded, and the person decides again (G7; §3.1, §3.3). *(Settled as "bounded by the evaluation's approved amount"; corrected at the exit review)* | `-016`, `-018`; ADR-0088, ADR-0089 |
| O6 | Consumption written as a column of the immutable decision | The born-once fact `credit_decision_consumption`, created empty by `-016`, written only by Phase 11 (G1; §3.5, §4) | `-016`; ADR-0087, ADR-0088 |
| O7 | A retry was not gated | The retry re-reads the gate under the row lock; as built the claim has already moved an `UNAVAILABLE` row `REQUESTED`, so a closed gate takes `REQUESTED → CONSENT_WITHDRAWN`, nothing asked — `UNAVAILABLE → CONSENT_WITHDRAWN` is admitted by the trigger and taken by no writer (§3.2). *(Settled as the `UNAVAILABLE → CONSENT_WITHDRAWN` edge; corrected at the exit review)* | `-006`; ADR-0085 |
| O8 | The collected sources came from one policy version and the pinned version from another | The versions are pinned on the request at `SUBMITTED → COLLECTING` and re-read `FOR SHARE` at the freeze and in the deciding transaction (G2; §3.1, §3.4) | `-015`; ADR-0086 |
