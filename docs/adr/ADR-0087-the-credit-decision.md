# ADR-0087 — The credit decision: a keyed request, a sealed snapshot, born-once assessment, evaluation and decision, recorded immutably with ordered reason codes, explained and replayed

Status: Proposed
Date: 2026-10-07
Phase: 10
Context: Credit · Platform · Identity · Events
Supersedes: nothing. Resolves, with ADR-0086, `docs/adr/README.md`'s anticipated Phase 10
decision "Credit policy versioning and decision reproducibility". Applies ADR-0004 and the
`IdempotentExecutor` shape to submission, ADR-0005 (the outbox) to credit's events, ADR-0039
(`READ COMMITTED` plus row locks, conditionals and uniques) to every credit writer, and the
database-clock discipline of `X-TSK-013` and the Phase 9 exit review to every window. Rests on
`PHASE_10_PLAN.md` §4, §5, §7, §9, §10, §12.2, §12.7, §12.8, §14 and `INV-CRD-01`, `-02`, `-06`,
`-07`, `-09`.

## Context

A credit decision must be defensible years later, to a regulator or to the declined applicant.
That sets the bar:

1. **Reproducible** (`INV-CRD-01`): re-running the decision must give the identical outcome,
   approved amount and ordered reasons — which requires the exact inputs, not a re-fetch of
   inputs that have since changed.
2. **Immutable** (`INV-CRD-02`): a decision that can be edited is not evidence. A change of mind
   must be a new fact.
3. **Explained**: every adverse decision carries the reasons an adverse-action notice needs, and
   the applicant sees those reasons' customer texts — never "412".
4. **Asynchronous**: collection takes seconds to hours; the applicant submits, and the decision
   arrives later.
5. **Concurrent**: ten instances progress one request; a client retries a submission; two
   requests for one party race; a request's validity expires at the moment it is decided; the
   process crashes between any two steps.

## Decision

1. **The `DecisionRequest` is the input envelope, keyed at submission, with an explicit machine.**
   - `POST /v1/me/credit/decision-requests` (MFA-assured session; the party's standing `ACTIVE`
     and KYC `VERIFIED` checked in-transaction; consent present or `403 credit.ConsentRequired`)
     takes product, requested amount, term and declared income and expenditure, under the
     idempotency claim `credit.decision:<actorType>:<actorId>`; ten same-key submissions make one
     request and replay one response (`409 platform.IdempotencyInProgress` while in flight). It
     answers `202` with the request id; `CreditDecisionRequested` is emitted (never declared
     income). The request's `expires_at` is submission plus the product's declared request
     validity (7 days for both products, ADR-0084 §6), on the database clock. Submission is not
     an audit record — the request's row, history and event are its trail.
   - **One open request per (party, product)**, by partial unique over the open states; a second
     key is `409 credit.DecisionRequestOpen` naming the open request.
   - **The machine** (full in `CREDIT_DECISIONING_LIFECYCLES.md`): `SUBMITTED → COLLECTING →
     READY → EVALUATED → DECIDED`, `EVALUATED → IN_REVIEW → DECIDED` (a referral, ADR-0089);
     `CANCELLED` by the applicant before evaluation (`POST …/{id}/cancellation`, keyed, audited
     `credit.DecisionRequestCancelled` on the `fx.QuoteCancelled` precedent; after evaluation
     `409 credit.RequestNotCancellable` — the evaluation is a fact, and withdrawing then is a
     decision the applicant receives); `EXPIRED` when no decision lands within the
     request's validity — and only that; `ABANDONED` when the platform closes the request before
     a decision, with a recorded reason: `STANDING_LOST` (the party's standing lost before the
     decision; the step or the deciding transaction refuses, failure scenario 27) or
     `CONSENT_WITHDRAWN` (a data request ended `CONSENT_WITHDRAWN`, scenario 7; or, after a
     source answered, the gate re-read at the freeze or in the deciding transaction found the
     withdrawal, scenario 31 — ADR-0085 §4). The policy and scorecard versions are pinned on the
     request at `SUBMITTED → COLLECTING` (ADR-0086 §7). **`READY → COLLECTING`** is the
     one backward edge: a record found stale at the freeze, on the database clock, is re-collected
     before any snapshot exists (scenario 9). Terminal: `DECIDED`, `CANCELLED`, `EXPIRED`,
     `ABANDONED`. Refused: any edge out of a terminal state; `COLLECTING → EVALUATED`;
     `EVALUATED → COLLECTING` (a frozen snapshot is never re-collected — a stale one makes a new
     request); `IN_REVIEW → EXPIRED` once a person has taken the case (ADR-0089 §7). Held by a
     generated `CHECK`, an every-writer edge trigger and the domain; history in
     `decision_request_event`, append-only. `CreditDecisionRequestClosed` carries the reason on
     cancellation, expiry or abandonment.

2. **The snapshot is sealed: canonical JSON, SHA-256, sequenced per request** (`INV-CRD-07`).
   `decision_snapshot` is keyed `UNIQUE (decision_request_id, sequence)`, one snapshot per
   evaluation: sequence 1 is frozen in the step that leaves `READY`, and a successor exists only
   when the deciding transaction finds reserved exposure changed (point 5). The first is frozen
   by reading the data requests' records under the request's row lock (data requests by id, plan
   §7) and judging their freshness on the database clock (ADR-0085 §8). Its body is the canonical
   JSON of every attribute, sorted by `CreditAttributeCode`, each value in a fixed textual form
   (money as minor units plus currency; no floating point; no locale), with its provenance; plus
   the requested product, amount and term and the pinned policy, model, engine and seam versions
   (the policy and model versions those pinned on the request, re-read `FOR SHARE`). Before
   freezing, the step re-reads the consent gate for every source kind; a withdrawal abandons the
   request with nothing frozen.
   Its SHA-256 over that canonical form is stored beside it and **re-verified on every replay**.
   Every attribute any rule, the scorecard or the arithmetic reads is in it; reading one it lacks
   is an evaluation error, never a default. A record arriving after the freeze belongs to no
   snapshot. `INSERT` only.

3. **Assessment, evaluation and decision are each born once** (`INV-CRD-06`: a request has at
   most one decision; each of its snapshots at most one assessment and one evaluation; the
   decision names the snapshot it was made from). The assessment (`credit_assessment`:
   affordability, exposure and score, each with its inputs' references — ADR-0088, ADR-0086 §3)
   is born once per snapshot (`UNIQUE (snapshot_id)`) and emits `CreditAssessmentCreated`
   (request, snapshot hash, model version; never the figures). The evaluation (ADR-0086 §2) is
   born once per assessment (`UNIQUE (assessment_id)`). The decision is born once per request
   (`UNIQUE (decision_request_id)`) and references its snapshot by id. A retry, a duplicate event
   or a second instance finds the row already present and converges on it.

4. **The decision is immutable and carries ordered reason codes** (`INV-CRD-02`).
   `credit_decision` (+ `credit_decision_reason`) holds the outcome, the approved amount and term,
   the reason codes in the evaluator's order, `decided_by` (the system, or the person who decided
   a referral), `valid_until`, the pinned versions, and the snapshot it was made from with that
   snapshot's hash. **No role may `UPDATE`
   or `DELETE` it** — privilege and trigger, the migrator included (failure scenario 25). An
   adverse outcome with no reason code is refused by `CHECK` and by the domain. A later change of
   mind is a new request, never an amended decision. The decision has **no consumption column**:
   the consumption Phase 11's loan will record is a separate born-once fact,
   `credit_decision_consumption` (`UNIQUE (decision_id)`), whose table this phase creates empty
   (ADR-0088 §4). Credit publishes the **`CreditDecisions`** port — the decision read Phase 11's
   `lending` will use; nothing consumes it in Phase 10.

5. **The deciding transaction is profile-first, and re-reads exposure under the lock**
   (`INV-CRD-09`, plan §12.7).
   - Lock the party's `credit_profile` row `FOR UPDATE`; lock the request, requiring `EVALUATED`
     and **`expires_at > statement_timestamp()`** — or, for a person's decision, `IN_REVIEW` with
     the case locked and taken by that person, **the case judged rather than the request's
     expiry** (ADR-0089 §6: a taken case is never stuck); the party's standing and the consent
     gate for every source kind read in the same transaction (lost standing ⇒ `ABANDONED`,
     `STANDING_LOST`; a withdrawal ⇒ `ABANDONED`, `CONSENT_WITHDRAWN`; nothing decided); the
     pinned versions read `FOR SHARE`; re-read the reserved exposure of the party's current
     approvals (ADR-0088 §2).
   - **If it differs from the snapshot's `PLATFORM_RESERVED_EXPOSURE`** — grown or shrunk, so
     the snapshot always equals the value read under the lock — **freeze a successor snapshot**
     (the next `sequence`: the same records, the new exposure), re-assess and re-evaluate under
     **the same pinned versions**; the request keeps its first snapshot in history (a snapshot is
     per evaluation). An unchanged exposure freezes nothing. The decision names the snapshot it
     was actually made from.
   - Insert the decision with its reason codes; move the request `DECIDED`; emit
     `CreditDecisionRecorded` (request, decision, outcome, approved amount and term, valid-until,
     pinned versions, snapshot hash, reason codes; never attributes or scores); audit
     `credit.DecisionRecorded` — all in one transaction.
   - **A `REFER`** opens the underwriting case instead and moves the request `IN_REVIEW`
     (ADR-0089); the person's decision later runs this same transaction with `decided_by` the
     person and the case's reason codes, the system evaluation kept as the case's basis. A
     person's approval is bounded by the evaluation's approved amount and by the exposure limit
     re-read under the profile lock: beyond it `422 credit.ExposureLimitExceeded`, nothing
     recorded, and the person decides again (ADR-0089 §6).
   - The progress sweep, which starts from a request, **releases its claim transaction and opens
     the deciding one profile-first**, so no writer ever takes profile after request: profile →
     request → case → data requests → versions `FOR SHARE` is the only order (plan §7).

6. **Progress is a leaderless sweep over database-stamped permits.**
   `CreditDecisionProgressSchedule` (every instance, off in test contexts, its
   `finapp.credit.progress.sweeper.enabled` gauge) takes requests due a step — open the data
   requests, judge `COLLECTING → READY`, freeze, assess, evaluate, decide, expire — oldest permit
   first in one `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED)` stamping a new permit
   from `statement_timestamp()`; each step's conditional edge is re-judged under the row lock, so
   ten sweepers take one step per state (failure scenario 30). A step that cannot act re-stamps
   rather than holding the page (the P9-TST-001 starvation lesson). A crash after any step leaves
   a state the sweep re-drives (scenarios 10–12); every step is idempotent by its born-once unique
   and its conditional.

7. **Every window is on the database clock.** Request expiry and decision are complementary
   conditionals — `expires_at > statement_timestamp()` to decide, `<=` to expire — so exactly
   one of `DECIDED` and `EXPIRED` lands at the boundary (scenario 18); the one exception is a
   case a person has taken, which its person decides whatever the request's validity
   (ADR-0089 §7). The decision's
   `valid_until` (decided-at plus the product's decision validity — 30 days for both products,
   ADR-0084 §6) is computed from
   the database's time in the deciding transaction; its lapse ends the exposure reservation
   (ADR-0088 §2). No instance's clock judges any credit window.

8. **Explanation and replay are first-class doors** (`INV-CRD-01`, plan §12.8).
   - **Explanation** (`GET /v1/operator/credit/decisions/{id}/explanation`, `CREDIT_INVESTIGATE`,
     audited `credit.ExplanationRead`) reads the decision, its snapshot, the pinned versions'
     rules and the triggered list: which data (attributes, sources, retrieval times), which
     policy, model and engine versions, which rules fired, which reasons, what outcome, when, by
     whom — from the rows alone. It returns normalised attributes; raw evidence stays behind the
     definer function, reached only through the evidence-read door
     (`POST /v1/operator/credit/records/{id}/evidence-read`, a reason required, audited
     `credit.EvidenceRead`; ADR-0085 §5).
   - **Replay** (`POST …/{id}/replay`, audited `credit.DecisionReplayed`, reason required, no
     state) re-runs the pinned engine over the stored snapshot with the pinned policy and model
     versions, compares outcome, approved amount and ordered reason codes, and re-verifies the
     hash: `IDENTICAL` or `DIVERGED` with what differs. `CreditReplayProof` replays every
     decision per reading, each reading in one `REPEATABLE READ` read-only snapshot, rolled back;
     `finapp.credit.replay{verdict}` alerts on any `DIVERGED`. A tampered snapshot or a forced rule
     edit flips the verdict (scenarios 23–24).
   - **The customer** (`GET /v1/me/credit/decision-requests/{id}`, owner-scoped — another party's
     id is `404`, never `403`) sees the status and, when decided, the outcome, approved amount and
     term, the validity and the **customer texts of the adverse reasons in order**. Never a
     bureau's raw data, an internal score, a rule's threshold or a risk signal; a needle per
     figure proves it.

9. **Events: nine types, two refused.** Through the transactional outbox, in the state change's
   transaction, with the platform envelope (event id, type, aggregate id, event version 1,
   schema version, occurred-at from the database, producer `credit`, correlation, causation):
   `CreditDecisionRequested`, `CreditDataCollected`, `CreditDataUnavailable`,
   `CreditAssessmentCreated`, `ManualReviewRequired`, `CreditDecisionRecorded`,
   `CreditDecisionRequestClosed`, `CreditPolicyVersionActivated` /
   `ScorecardModelVersionActivated`. None carries an attribute, a score or declared income.
   Evolution adds optional fields only; a breaking change is a new type. Credit consumes no event
   in Phase 10; Phase 11 will consume `CreditDecisionRecorded`.
   - **`CreditDecisionUpdated` is refused**: a decision is never updated (`INV-CRD-02`); an
     event announcing an update would advertise a mutation the schema forbids.
   - **`UnderwritingStarted` is refused**: a case's assignment is an internal workflow step,
     audited (`credit.ReviewCaseAssigned`), not an integration fact anyone consumes.

## Alternatives Considered

### Store references to the records instead of a snapshot
Pros:
- No duplication of attribute values; smaller rows.

Cons:
- The decision's inputs become whatever the referenced rows say *now*; purge (Phase 15) or a
  correction would silently change a past decision's replay, and a fresher record arriving
  between evaluation and decision would have no defined relation to the decision.

Refused: the snapshot holds the values, sealed by a hash (point 2).

### Synchronous decisioning in the submission request
Pros:
- One round trip for the applicant.

Cons:
- Holds a request open across provider calls (ADR-0046 forbids a transaction spanning one), and
  makes a slow bureau the applicant's timeout. A crash mid-request leaves nothing to re-drive.

Refused: `202` plus a sweep-driven machine (points 1, 6).

### Decide inside the evaluating transaction, without the profile lock
Pros:
- One fewer transaction; no successor snapshot.

Cons:
- Two products for one party at the exposure limit would each read the other's reservation as
  absent and both approve (`INV-CRD-09`, scenario 15). The evaluating step starts from the
  request, so taking the profile there would invert the lock order.

Refused: a separate deciding transaction, profile-first, re-reading exposure (point 5).

### Allow a decision to be amended (with history)
Pros:
- An underwriter's correction lands on the same decision; one decision per request stays simple.

Cons:
- An amended decision is not evidence; Phase 11 would have relied on a fact that changed. The
  history table would become the real decision and the row a cache of it.

Refused: immutable decisions, `CreditDecisionUpdated` refused, a change of mind is a new request
(points 4, 9).

## Consequences

Positive:
- Every decision is re-derivable from its own rows: sealed snapshot, pinned versions, engine
  version, ordered reasons — and the proof runs continuously on every instance.
- Immutability is held for every role, the migrator included; the applicant receives an
  explanation in words.
- Ten instances, retries, crashes and boundary races each converge on one decision or one
  expiry.

Negative:
- A successor snapshot (when reserved exposure moved) adds a re-assessment inside the deciding
  transaction — bounded, and only for a party with concurrent requests.
- Snapshots duplicate attribute values for the decision's explanation life — deliberate, and
  `RESTRICTED-FINANCIAL`.
- A changed mind costs the applicant a new request.

Operational impact: `finapp.credit.decision{product, outcome, policy_version, decided_by}`,
`finapp.credit.decision.latency{product}`, `finapp.credit.reason{product, reason_code}`,
`finapp.credit.request.open.age{status}`, `finapp.credit.replay{verdict}`, the progress sweeper's
enabled gauge; spans for submission, collection, freeze, evaluation and decision linked by the
request's correlation.
Security impact: customer routes owner-scoped in every query with uniform `404`s; customer
responses carry no internal figure (needle-proven); explanation and replay audited with a
reason; snapshot and decision rows `INSERT` only.
Financial impact: none posted. An approval reserves exposure until it lapses or is consumed
(ADR-0088); it is the fact Phase 11's loan application will reference.

## Invariants / Constraints

`INV-CRD-01` (replay identical, hash re-verified), `INV-CRD-02` (immutable; adverse ⇒ reasons),
`INV-CRD-06` (one decision per request; one assessment and one evaluation per snapshot; the
decision names its snapshot), `INV-CRD-07` (complete, sealed snapshot), `INV-CRD-08`
(freshness at the freeze), `INV-CRD-09` (profile-first deciding transaction), `INV-HIST-04`
(pinned versions), `INV-IDEM-01`…`03` (keyed submission and cancellation), `INV-CON-02` (racing
requests, one effect), `INV-AUD-01`…`04`, ADR-0004, ADR-0005, ADR-0039, ADR-0046.

## Follow-up

- `P10-TSK-008`: the snapshot, canonical form and hash, the freeze. `-014`: submission, the
  customer doors, the open-request unique. `-015`: the progress schedule, the pin, expiry against
  every earlier step, crash recovery. `-016`: the deciding transaction, the immutable decision,
  the successor snapshot, the empty `credit_decision_consumption` table, the `CreditDecisions`
  port, the expiry-against-decision boundary race and the crash between evaluation and
  decision. `-017`: the customer explanation, the operator explanation and the evidence read.
  `-019`: replay and `CreditReplayProof`.
- `P10-TST-001` (the storm) and `P10-TST-002` (the reproducibility battery, ≥ 10,000 applicants).
- *As built by `P10-TSK-014` (2026-10-08), the submission and the customer doors.* `credit V010`:
  `decision_request` (the terms frozen, `submitted_at`/`expires_at`/the first permit stamped by the database, one
  open per party and product by partial unique, the pins nullable until `SUBMITTED -> COLLECTING` and written once,
  an every-writer machine trigger - `-> EVALUATED` only beside its `policy_evaluation`; `IN_REVIEW`'s and `DECIDED`'s
  preconditions join with their tables in `-016`/`-018`) and `decision_request_event`; the foreign keys from
  `data_request` (of the same party, by a composite reference) and `decision_snapshot`. Four decisions taken in the
  building, recorded here. (1) **Consent absent is the platform's one consent refusal**, `409 consent.ConsentRequired`
  naming the purpose - the backlog's `403 credit.ConsentRequired` corrected to the error contract, which keeps that
  refusal deliberately not a `403` (the person can fix it), as KYC's door already does. (2) **An unverified applicant
  is `409 credit.ApplicantNotEligible`**, cause-blind - the `accounts.AccountOpeningRefused` shape; the standing is the
  party's live customer `ACTIVE` (`INV-KYC-05`'s projection of the KYC decision), read authoritatively by
  `app`'s `PartyCreditStanding` (the facts adapter renamed and extended). (3) **Step-up is the platform's conditional**:
  an identity with an active factor submits and cancels from a `MULTI_FACTOR` session (the cross-border precedent);
  one without a factor is not locked out of credit. (4) **A refusal writes nothing**: the door runs in one transaction
  under the `IdempotentExecutor` claim, and a refusal rolls the claim back with it, so a corrected retry under the
  same key is judged afresh, while a success is replayed byte for byte. The decision request suite runs in a database
  of its own (`own-container`), because bringing each product's seeded policy into force would change what the policy
  suites find in the shared one.
- *As built by `P10-TSK-015` (2026-10-08), the orchestration to the evaluation.* credit's `DecisionProgress` and `app`'s
  `CreditDecisionProgressSchedule`: the claim in one statement re-stamping the page's permits; each step a transaction
  of its own under the request's row lock. Four decisions taken in the building, recorded here. (1) **The standing is
  read before the expiry**, so a party whose standing is lost is `ABANDONED` (`STANDING_LOST`) and never `EXPIRED`,
  whatever the clock says. (2) **The consent gate is read for every source kind at the pin as well as before the
  freeze**: a basis gone between submission and the first step abandons the request (`CONSENT_WITHDRAWN`) before any
  data request is opened, so no access is ever opened on a withdrawn basis. (3) **The providers are asked after the
  step's commit**, holding no connection; an ask that fails is the retry sweep's (`P10-TSK-006`), and the step reads
  the rows the next time - so a crash at any point leaves rows another instance carries on from. (4) **Nothing is
  reserved before decisions exist**: the snapshot's reserved-exposure seam is `NothingReservedBeforeDecisions` (zero,
  version 1) until `P10-TSK-016` records decisions and replaces it with a new version; an `EVALUATED` request waits for
  that deciding step, and an `IN_REVIEW` request's expiry for its case (`-018`).
- **Acceptance.** The Phase 10 review (`P10-DOC-001`) reads this ADR against the code before
  accepting it.
