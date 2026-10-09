# Phase 10 Exit Review — Credit Decisioning

**Conducted:** 2026-10-09 (`P10-DOC-001`)
**Prescribed by:** [`PHASE_GATES.md`](../PHASE_GATES.md) §4 (review areas), §3 (universal exit
criteria and the financial supplement), §5 (Phase 10-specific criteria, read from the gate at
review time, as extended by the Phase 9 → 10 transition)
**Phase objective under review:** the platform decides credit and moves no money — a keyed,
asynchronous decision request is decided once, on the database's clock, from a sealed snapshot of
data collected through provider-neutral ports under consent re-read at every step; affordability
and exposure are exact single-currency arithmetic, exposure judged under the party's profile lock;
a versioned scorecard and a versioned policy of rules as data are evaluated by a deterministic
engine with its own version; the decision is immutable and carries ordered reason codes; a
referral meets a person under four-eyes whose power is bounded; and every decision replays
`IDENTICAL`.

| | Outcome |
|---|---|
| Review areas (8) | **8 `PASS`**, five of them only after this review's corrections — see *What the review found* |
| Universal criteria (12) | **12 `PASS`**, one (7) with a recorded deviation, one (10) **met by this review's own act** |
| Financial supplement (F1–F8) | **8 `Met`** in the reading the phase's backlog header fixed: F1, F2, F4 and F5 vacuous for want of any posting or external financial event (asserted by `CreditModuleIsolationTest`, never assumed); F3, F6 and F7 binding on the exposure reservation and the credit arithmetic — F6 only after this review closed the policy that could approve past its own limit; F8 `N/A — credit moves no money`, re-confirmed |
| Phase 10-specific criteria | **24 `PASS`** — 5 original + 19 added by the Phase 9 → 10 transition, counted from the gate; the Testing bullet's full-battery clause as criterion 7's deviation; three clauses met in a form their words did not anticipate, recorded under the gate (`PHASE_GATES.md`, the note after Phase 10's criteria) |
| *"Correct with 10 concurrent instances?"* | **`PASS`** — every contention of `PHASE_10_PLAN.md` §7 with its PostgreSQL arbiter and its counted race; every born-once arbiter refusing a copy with every trigger off; the storm's two application contexts five seconds either side of the database; the expiry-against-decision boundary raced over 240 requests; the lock order made true in code where it was not |
| **Verdict** | **Phase 10 `COMPLETE` (2026-10-09)** |

Conducted in the `P2-DOC-001` order: assess → land the corrections → **flip the status, which
is the guarded act** → re-run the battery → finalise with counted numbers.

**The assessment was six read-only audits, then four document passes, and it found what no
task's gate had.** The audits read ADR-0084…0089 against the code (two audits), read
`PHASE_10_PLAN.md`, `CREDIT_DECISIONING_LIFECYCLES.md`, `CREDIT_MODEL.md` and the owner's
transition decisions G1–G11 against it, enumerated the twenty-two credit routes with their
permissions and negatives and the twenty audited acts with their positive assertions, the sixteen
error codes and the 265 credit columns, read every architecture document and the Phase 11
boundary, and re-checked every task's multi-instance answer, the §7 contentions, the §14 failure
scenarios and every gate clause against the counted tests. They found **a credit policy that could
approve past its own maximum exposure — the evaluator judges exposure only through rules, and the
proposal door demanded none (`INV-CRD-09`); the retry sweep still asking providers for requests
already closed; the evaluating step taking the pinned versions before the data requests, against
the one lock order every ADR states; a record's age running from the provider's own date
(`INV-CRD-08`); four audited acts never asserted; the expiry-against-decision boundary raced over
one request where the gate says two hundred; six born-once arbiters without a lock-bypass probe;
the underwriting case's domain refusals untested and raw-SQL gaps in all three machines; four keyed
acts and the rejection never replayed; and drift in all six ADRs and every Phase 10 document** —
among it a consent refusal the code does not make (`403 credit.ConsentRequired`; it is `409
consent.ConsentRequired`), a party standing read "through kyc" that reads party, a "forward-only
permit trigger" that does not exist, an approval bound "by the evaluation's approved amount" that
is the referral's ceiling, an operations runbook entry two ADRs promised and nobody wrote, and the
"planned, nothing built" markers of the transition still standing in eight documents. None was
waived. Each was corrected here, in code or in text, and every correction in code was broken on
purpose: **seven probes, seven caught - the exposure bound skipped at the proposal door, the retry claim's open-request filter neutralised, the evaluating step's early lock removed, freshness on the provider's date again, a rejection's audit dropped, a born-once unique replaced by a CHECK, the case's holder precondition weakened - every restore byte-identical (sha256-verified)**. One probe's first attempt was void and recorded as
no verdict: the unique it dropped was production's own `ON CONFLICT` target, so the suite failed before the assertion
under test; it was re-performed on a unique no `ON CONFLICT` names, and caught.

---

## What the review found, and what it did about it

| Severity | Finding | Done |
|---|---|---|
| **IMPORTANT** | **A credit policy could approve past its own maximum exposure** (`INV-CRD-09`). `PolicyEvaluatorV1` judges exposure only through a rule that reads `EXPOSURE` or `EXPOSURE_HEADROOM`, and the proposal door required a fallback rule per source kind but no exposure rule — so a four-eyes policy without one would have let every system approval through whatever the party's exposure. Latent: the seeded v1 and every policy any test proposed carry `EXPOSURE_LIMIT` | The proposal door refuses it `422 credit.PolicyIncomplete`: `CreditPolicy.boundsExposure()` over `PolicyRule.refusesExposurePast(limit)` — exactly the shapes guaranteed to trigger past the limit (`EXPOSURE_HEADROOM LT`/`LE` x ≥ 0, `EXPOSURE GT`/`GE` x ≤ the limit; `HARD_DECLINE`, `DECLINE` or `REFER`, never `CAP_AMOUNT`). Judged at proposal, not at construction, so a stored version always reads back; the engine is unchanged, so no `engine_version` moves. `CreditPolicyValidationTest#onlyAGuaranteedRuleBoundsTheExposure` (ten shapes), `CreditPolicyEndpointDatabaseTest#theRefusals`. Probed |
| **IMPORTANT** | **The retry sweep kept asking for closed requests.** `JdbcCreditDataRequestStore.claimDue` read the data request alone, so a cancelled, expired or abandoned request's data requests were re-asked — paid pulls of the applicant's data for nothing — until answered or past their deadline. `CREDIT_DECISIONING_LIFECYCLES.md` §3.1 said the sweep stops | The claim admits only data requests whose decision request is open, by a plain read (the request's lock is never taken after a data request's). `BureauCollectionDatabaseTest#theRetrySweepStopsAskingForAClosedRequest` (and its re-run on `bureau-sim-b`): the closed request's reference never asked again, the open one beside it re-asked once. Probed |
| **IMPORTANT** | **The evaluating step took the lock order backwards**: `DecisionProgress.evaluate` shared the pinned versions (5) before the freeze locked the data requests (4), where ADR-0086 §7, ADR-0087 §5 and `DISTRIBUTED_EXECUTION.md` §3 state profile → request → case → data requests → versions as the only order. No deadlock was possible today (no holder of (4) waits on (5)), but the next writer that trusted the register would have built one | `SnapshotFreezer.lockDataRequests` called first: the order now holds everywhere. `DecisionOrchestrationDatabaseTest#theEvaluatingStepLocksItsDataRequestsBeforeThePinnedVersions` — held at the policy row by another session, the step already holds its data requests (`FOR UPDATE NOWAIT` refused `55P03`). Probed |
| **IMPORTANT** | **A record's age ran from the provider's own date** (`INV-CRD-08`): freshness was `retrieved_at ≥ transaction_timestamp() − max age`, and `retrieved_at` is what the provider states — a provider clock ahead of ours, or a re-stamped file, kept stale data fresh. Latent: no real provider is connected in Phase 10 | Judged on `LEAST(retrieved_at, recorded_at)` — the earlier of the provider's date and our own database-stamped recording. `DecisionSnapshotDatabaseTest#aRecordDatedInTheFutureIsAgedFromItsRecording`. Probed |
| **IMPORTANT** | **Four audited acts were never asserted positively** — `credit.PolicyVersionProposed`, `credit.PolicyVersionRejected`, `credit.ScorecardVersionProposed`, `credit.ScorecardVersionRejected` — so the Versioning criterion's "each act audited" was half-proven | `CreditPolicyVersionDatabaseTest#aProposalAndARejectionAreEachAudited` and its scorecard twin: one row each by operation and target with the reason; a refused self-approval and a stale rejection record nothing. Probed (the rejection's audit dropped) |
| **IMPORTANT** | **The Decisioning criterion's "expiry and a decision racing at the boundary over ≥ 200 requests" had no proof** — one request in `-016`'s suite, six in the storm | `CreditDecisionDatabaseTest#expiryAndDecisionAtTheBoundaryOverTwoHundredRequests`: 240 evaluated requests, two racers each on clocks five seconds fast and slow, fired 0–300 ms before each request's own expiry; every request exactly one edge out of `EVALUATED`, `DECIDED` or `EXPIRED`, a decision exactly where `DECIDED`, and the boundary met from both sides (asserted) — on the final code 51 `DECIDED` and 189 `EXPIRED`, sixteen deciding transactions refused by the trigger on a later statement's clock and settled by the next step (the recorded design), and the run before it 69 and 171. Its first version fired every racer 10 ms early and saw 240 `EXPIRED`, 0 `DECIDED`: a race that tests one side only, caught before it counted |
| **IMPORTANT** | **Six of the eight born-once arbiters had no lock-bypass probe** (the Multi-instance criterion: "each survive a lock-bypass probe") — only `credit_record`'s | `UnderwritingCaseDatabaseTest#everyBornOnceArbiterRefusesACopyWithEveryTriggerOff`: with every user trigger of the table disabled, a copy of the row under a fresh id is refused `23505` by the unique alone — the profile, the data request's reference, the snapshot's `(request, sequence)`, the assessment, the evaluation, the decision and the case. Probed (a unique dropped) |
| **IMPORTANT** | **"Every invalid transition refused by the domain and by raw SQL" was unproven** for the underwriting case at the domain (`UnderwritingCaseStatus.canTransitionTo` is called by nothing; the domain's rank is each act's precondition), and raw SQL left the case's terminal states, the decision request's `EVALUATED`, `DECIDED` and `EXPIRED`, and three data-request edges untried | `UnderwritingCaseDatabaseTest#everyInvalidTransitionIsRefusedByTheDomainAndByRawSql` (all twenty act-from-state refusals `credit.CaseTaken`, nothing changed; every edge out of `DECIDED` and `CLOSED` refused by the trigger); `CreditDecisionDatabaseTest#everyEdgeOutOfEvaluatedDecidedAndExpiredIsRefused`; `BureauCollectionDatabaseTest#everyMachineEdge` (`CONSENT_WITHDRAWN → UNAVAILABLE`, `UNAVAILABLE → RECEIVED`, `RECEIVED → REQUESTED`). The domain's rank recorded as built in the lifecycle document. Probed (the case's precondition weakened) |
| **IMPORTANT** | **"`UPDATE` and `DELETE` on the decision tables refused for every role, each proven alone"** held for `credit_decision` but not for its reasons (the application's `DELETE`, the owner's `UPDATE`/`DELETE`) or the consumption fact (neither rank) | `CreditDecisionDatabaseTest#aDecisionRowIsNeverUpdatedOrDeletedByAnyRole` extended — the owner's refusals against a decline's real reason rows (the approval it first used has none, which would have made them vacuous: the first run said so) |
| **IMPORTANT** | **The Idempotency criterion's keyed operator acts were half-replayed** — release, a person's decision, a second approval, its refusal and the rejection never replayed under their key | `UnderwritingCaseDatabaseTest#everyKeyedActReplaysItsResponse` (each replayed byte-identical, each audited once); `ScorecardAdministrationEndpointDatabaseTest#theRefusals` (the rejection) |
| **IMPORTANT** | **The Versioning criterion's "over a generated history"** was two and three hand-built versions | `CreditPolicyVersionDatabaseTest#theVersionActiveAtAnyInstantOverAGeneratedHistory` — twelve seeded activations, every switch instant and the microsecond before it |
| **IMPORTANT** | **Drift in all six ADRs and every Phase 10 document** — among it: the consent refusal (`403 credit.ConsentRequired` in the gate, the plan, the lifecycle document, `MODULE_ARCHITECTURE.md`, ADR-0085, ADR-0087 and `INV-CRD-03`; built as `409 consent.ConsentRequired`); "MFA-assured" (a step-up only where a factor is enrolled); the standing "through kyc's customer-standing port" (party's store, `INV-KYC-05`'s projection); party facts that are always `ABSENT` (unresolved question #13); "snapshot, assessment, evaluation and decision commit together" (two transactions); the "forward-only permit trigger" and "`→ RECEIVED` without its record refused by the trigger" (neither exists); the retry's `UNAVAILABLE → CONSENT_WITHDRAWN` edge (never taken); rejection "by a different holder" (any holder, a withdrawal); a person's bound "the evaluation's approved amount" (the referral's ceiling, `approvable_minor`); a person's exposure check silent on an absent bureau balance; `occurred-at` "from the database" (the instance's); `CreditDecisionRecorded` version 2 under the rule "a breaking change is a new type"; "generated CHECKs"; advisory namespace `10` on approvals (proposals only); the runbook entry ADR-0086 and ADR-0089 promised and nobody wrote; and the transition's "planned, nothing built" markers in eight documents | Every statement made true — area 7 |
| MINOR | Three underwriting doors (release, decision, second approval) refused without a session but never tested so; `CREDIT_UNDERWRITE` missing from `RoutePermissionRegisterTest`'s non-vacuity list | Asserted; listed |
| MINOR | `party_id` on `decision_request`, `credit_decision` and `underwriting_case` classified `INTERNAL`, its siblings on `credit_profile`, `data_request` and `credit_record` `CONFIDENTIAL` | Aligned to `CONFIDENTIAL` |
| MINOR | Stale code text: the activation's period in two administrations' javadoc (before `credit V014`), `CreditDataSubjectResolver`'s "arrives with `P10-TSK-006`", `CreditErrorCode` and two exception messages naming "the evaluation's approved amount", `CreditPolicyMetrics`' description and the `CreditPolicyMissing` alert saying "two officers" activate v1 (one does: the migration proposed it), and a Phase 9 javadoc naming `platform.IdempotencyInProgress` | Corrected (comments, a metric description, an alert description, two messages — no name, tag or behaviour) |
| MINOR | The plan's claim that credit records' `toString` names identifiers only: several hold `Money` in a default `toString` | Never logged — every credit log line names only exception classes, provider codes or ids (audited) — so the claim is corrected to what holds, not the records |
| MINOR, recorded | `finapp.credit.policy.active{product}` tags the product upper-case where the decision, reason and latency series lower-case it | Left: the alert and dashboard read it as built; a rename is a series change for its own task |
| MINOR, recorded | `DecisionSnapshotDatabaseTest#aSkewedInstanceNeitherAcceptsStaleNorRefusesFresh` injects no skewed clock — `SnapshotFreezer` takes none, so it holds by structure | The gate's "an instance skewed ±5 s" rests on the storm, which freezes on both skewed contexts; the name stays, the finding recorded |
| MINOR, recorded | No gauge or alert names a missing `ACTIVE` scorecard: requests are admitted and wait at `SUBMITTED` until they expire (the policy's absence is alerted, `CreditPolicyMissing`) | A debt row owned by Phase 15, and a judgement call for the owner, below |

## The flip

Recording Phase 10 `COMPLETE` is the guarded act: the register guard begins demanding a §2 row for
every `Phase: 10` invariant and a §4 row for every `P10-TST` item, and `PlannedMetersExistTest`
reads `PHASE_10_PLAN.md` through the generic union (its §15 already checked by `P10-TSK-020`'s own
case, ahead of the flip). Against the real status `MutationDemonstrationTest` (9) and
`PlannedMetersExistTest` (12) passed. **And it was proven non-vacuous against the real status**: with
`INV-CRD-11`'s two rows withheld, the guard failed reporting `(currently 10)` and naming exactly
`["INV-CRD-11"]`. The register was restored byte-identical (sha256 `fec35ec6…`, verified). Unlike
Phase 9's, no `Phase: 10` invariant was found unrowed: every task filed its own, and this review's
probes added seven more.

---

## Area 1 — Scope: what the phase set out to build, and what it built

Twenty-four items across eight milestones (M10.1–M10.8), every one `COMPLETE`, plus `X-TSK-017`
(the version periods on a database clock that steps back), run beside the phase. The cut candidate
(`P10-TSK-021`, the second bureau and source selection) was built, so the gate's conditional clause
reads as met by the task. Migrations: credit `V001`–`V015`, consent `V003` and identity `V020` —
seventeen. **`PASS`.**

## Area 2 — Walk a decision end to end

Credit moves no money, so the walk is a decision's, hop by hop: a keyed submission under consent
and standing, `202`; the progress sweep's claim, the pin of the active policy and scorecard at
`SUBMITTED → COLLECTING`, the data requests born on unique references and asked after the commit;
the answers recorded under the row lock with the gate re-read, evidence encrypted; `READY`, the
freeze judging freshness on the database clock, the snapshot sealed by its SHA-256; the assessment
and the evaluation born once in the same transaction; the deciding transaction profile-first,
re-sharing the pinned versions, re-reading consent, standing and the reserved exposure, freezing a
successor only if the reservation moved, recording the immutable decision with its ordered reasons
and its event; a referral's case taken, decided by a person, second-approved above the threshold;
the customer's explanation, the operator's explanation, evidence read and replay. All of it driven
together in the storm (`CreditDecisionStormDatabaseTest`). **Found:** the lock order backwards in
the evaluating step and the policy that could approve past its limit — fixed above. **`PASS`.**

## Area 3 — Multi-instance correctness

Every task's ten-instance answer re-checked against its counted tests: seventeen answer `PASS` on
named races (`P10-TSK-010`'s reservation race carried, as its record said, by `-016`'s), and the stateless or read-only ones (`P10-TSK-001`, `-003`, `-009`, `-017`, `-020`,
`P10-TST-002`) on the tests that pin what they hold — the closed vocabularies
(`CreditVocabularyTest`), the grants (`RoleNameTest`), the pure arithmetic
(`AffordabilityPropertiesTest`), the audited reads (`DecisionExplanationDatabaseTest#everyServingIsAudited`),
the reports' one snapshot (`Phase10ReportsDatabaseTest#eachReportReadsOneSnapshot`) and the battery's
two JVMs. Every contention of `PHASE_10_PLAN.md` §7 matched to its arbiter and its counted race;
the eight born-once arbiters each refusing a copy with every trigger off (seven for the first time
here); the storm's two application contexts five seconds either side of the database racing one
submission, step, decision, activation and case with one effect each; both schedules registered
(`NoSingleInstanceAssumptionRulesTest`, twenty → twenty-two) with their gauges; advisory namespace
`10` registered (proposals only, as built); every window judged in SQL on the database clock — the
instance clock stamps only audit and event times, latencies and gauge refresh floors (every use
read). **Found:** the lock order (above). **`PASS`.**

## Area 4 — Failure behaviour

Each of the thirty-one failure scenarios in `PHASE_10_PLAN.md` §14 has a test — the plan's table
names tasks, and the review matched every row to a test that exists — and the storm seeds the
catalogue fault by fault: every simulator fault (timeout, slow, malformed, partial, unknown status,
unavailable, lost response, a foreign-currency balance), an answer delivered twice, consent
withdrawn mid-pull and after the answer, standing lost before the freeze and at the decision, a
source past its deadline, a stale record re-collected, a policy and a scorecard activated
mid-decision, requests raced at their expiry, and nine crash points, two of them killed backends.
**`PASS`.**

## Area 5 — Security and audit

Twenty-two credit routes, every operator route in `RoutePermissionRegisterTest` and every route
with a negative of its own (three `401`s only since this review); three permissions under two
roles pinned by `RoleNameTest`; another party's request and profile `404`; submission behind the
conditional step-up and the party's standing, in-transaction; four-eyes refused at the domain and
at the `CHECK`, each alone, for versions and for a person's second approval; the evidence
encrypted, unreadable to the application role but through the reasoned, audited definer door; the
`INV-RAIL-03` needle walked through credit's doors in the storm and absent from every table but the
evidence ciphertext, every log line and every response; no attribute, score, threshold or reason
text in a log line, metric tag, span attribute, event or exception message (audited). Twenty
audited acts catalogued with `requiresReason` where a person judges, each asserted positively —
four only since this review. **`PASS`** after the audit assertions.

## Area 6 — Invariants, the register, and the demonstrations

Fourteen invariants carry Phase 10 in their catalogue line, read with the guard's own token regex:
`INV-CRD-01`…`12` (eight new at the transition) and `INV-HIST-04` and `INV-AUD-04`, already
demanded by earlier phases. Every one has `MUTATION_TESTING.md` §2 rows; `P10-TST-001` and
`P10-TST-002` have their §4 rows; this review's probes add seven §2 rows. **`PASS`.**

## Area 7 — Documentation accuracy

All six ADRs read against the code, corrected with dated notes where a decision changed or the
built shape differs, and accepted. `PHASE_10_PLAN.md` (inline corrections and a new §20 *Errata —
as built*), `CREDIT_DECISIONING_LIFECYCLES.md` (every machine, the born-once facts, the three
ranks as they exist, and its §5 points), `CREDIT_MODEL.md`, `MODULE_ARCHITECTURE.md` (the credit
entry as built, the risk score `risk`'s), `BOUNDED_CONTEXTS.md` (the standing party's, identity
upstream), the glossary (six terms added — Credit Data Request, Credit Evidence, Decision
Consumption, Decision Replay, Engine Version, Policy Evaluation; "Credit Bureau Record" renamed
Credit Record; the thirty-six planned markers removed) and `DOMAIN_MODEL.md`,
`DISTRIBUTED_EXECUTION.md` §3 (every planned and "not yet wired" row as built, two rows added),
`DATA_CLASSIFICATION.md`, `AUDITABLE_ACTIONS.md`, `ERROR_CONTRACT.md`, `FINANCIAL_INVARIANTS.md`
(`INV-CRD-03`, `-08`, `-09`), the `DELIVERY_PLAN.md` addendum, `OPERATIONS_RUNBOOK.md` (§6, new:
bringing v1 into force, the review queue, the credit alerts), `CAPABILITY_MAP.md`, `ROADMAP.md`,
`DECISIONS.md` (four Phase 10 deferrals added) and the ADR index made true, and eight code
comments and texts. The most consequential: the consent refusal the gate itself named wrongly, a
lock order the code did not keep, a sweep the documents said stopped and did not, and an approval
bound named after an amount a referral does not have. **`PASS`.**

## Area 8 — Debt, and what is deliberately deferred

The Phase 15 evidence-purge row present and accurate (`CURRENT_STATE.md` §Known Architectural
Debt), its scope widened by this review to name crypto-shredding and the normalised attributes and
snapshot text kept for replay. Carried with owners: real bureau and financial-data connectivity
and the party's date of birth and residence (unresolved questions #13 and #14); the risk score
(Phase 13, behind the `NOT_ASSESSED` seam); the loan application, offer and consumption writer
(Phase 11). **The Phase 11 boundary holds**: no loan application, offer, counter-offer,
acceptance, disbursement, schedule, interest, servicing, delinquency, collection or BNPL code
anywhere in a main source set (every hit of the search read: the `NoLoansUntilPhase11` seam, the
`PERSONAL_LOAN` product, the stressed repayment as a decision input, a bureau attribute, Phase 9's
cross-border offers); nothing writes `credit_decision_consumption`; `credit` depends on `platform`
and `sharedkernel` alone (`CreditModuleIsolationTest` refuses every sibling, `ledger` first); no
risk score or fraud rule, no real bureau, no machine-learned model, no purge. **`PASS`.**

## The twelve universal exit criteria

| # | Criterion | Verdict |
|---|---|---|
| 1 | Required functionality exists | `PASS` — keyed decision requests for two products; bureau and financial-data collection behind provider-neutral ports with two simulated bureaus and source selection; the sealed snapshot; exact affordability and exposure; the versioned scorecard and policy, four-eyes; the deterministic evaluator; immutable decisions with reason codes; underwriting under four-eyes; explanation, evidence read, replay, reports, meters and alerts |
| 2 | Architectural boundaries respected | `PASS` — `credit` → `platform`, `sharedkernel` only, every other reach a port `app` implements; nothing depends on `credit` but `app` (`CreditModuleIsolationTest` and the fifteen sibling isolation tests) |
| 3 | Required invariants tested | `PASS` — fourteen `Phase: 10` invariants, every one with register rows, demanded by the guard after the flip |
| 4 | Failure cases handled | `PASS` — all thirty-one of §14 |
| 5 | Security requirements implemented | `PASS` — area 5 |
| 6 | Observability exists | `PASS` — the §15 series from a freshly started instance (`PlannedMetersExistTest`, armed by the flip and already by `P10-TSK-020`'s own case), nine alert rules and the dashboard row resolved against a live scrape (`AlertRulesResolveTest`), five spans (`Phase10SpansTest`) |
| 7 | Integration tests pass | `PASS` **with deviation recorded** — below |
| 8 | Documentation reflects reality | `PASS` — area 7 |
| 9 | `CURRENT_STATE.md` updated | `PASS` — this review's finalisation |
| 10 | Relevant ADRs exist and are `Accepted` | `PASS` — **by this review's act**: ADR-0084…0089, each read against the code and corrected first |
| 11 | No unresolved critical issues | `PASS` — none critical; every important finding corrected, and every correction in code probed |
| 12 | Formal phase review conducted | `PASS` — this document |

**Criterion 7 — full suite against real infrastructure.** The owner's standing instruction skips
the fleet-wide `databaseTest` and `kafkaTest`, and every Phase 10 item was verified by targeted
tiers and recorded in those words. This review does not pretend otherwise:

- The **hermetic** tier was run **fleet-wide after the flip** — **2765 tests across 460 suites in 19 modules (credit's and app's executed fresh, 955 across 181; the other seventeen modules' tasks up to date - inputs unchanged since their last execution, not re-executed), 0 failures** — with the
  **architecture** tier, **213 across 51 (174 across 33 executed fresh), 0 failures**; the document guards among them,
  and re-run after the last record edits - app's architecture tier 171 across 32 and its nine document-reading
  hermetic suites, 83 tests - 0 failures.
- The **database** tier was run over every credit suite, the corrections' and their neighbours': credit's own
  database tier **73 tests across 8 suites**, app's credit suites **48 across 6** in the shared JVM and **103 across 11**
  in their own containers — the storm among them, green — **all 0 failures**. The suites a correction touched were
  also each run alone, before and after its probe.

**No fleet-wide database or kafka count is claimed for Phase 10.** Its cost is stated from the
phase before: Phase 9's column-classification guard stood red for a dozen tasks unseen with the
database tier skipped. This review re-ran the classification by script over every migration of
every module (0 unclassified, 0 stale) in lieu of the database-tier guard.

## The financial supplement F1–F8 — re-assessed at the gate

| # | Verdict |
|---|---|
| F1 | `Met` — vacuous: credit posts nothing (no build edge to `ledger`, asserted by `CreditModuleIsolationTest`) |
| F2 | `Met` — vacuous for balances; the one money-adjacent figure, the reserved exposure, is derived from decision rows alone (`APPROVED`, `valid_until` after the database's now, no consumption row) and re-derived by the storm's order-free exposure census every round |
| F3 | `Met` — every command that reserves exposure has a domain arbiter independent of `platform.idempotency_record`: `UNIQUE (credit_decision.decision_request_id)`, the underwriting case's, the conditional edges; each refused a copy with every trigger off |
| F4 | `Met` — vacuous for money; a decision is never corrected in place — a change of mind is a new request, and the decision tables refuse `UPDATE` and `DELETE` for every role, each rank alone |
| F5 | `Met` — vacuous for financial effects; duplicate provider answers make one record and a flagged duplicate evidence (`anAnswerDeliveredTwiceLeavesOneRecord`, the storm's census) |
| F6 | `Met` — two products for one party at the limit raced on the profile lock (a hundred rounds, a person beside the system, the storm's twelve parties on two skewed instances); **after this review**, no policy can be proposed that does not bound its own limit |
| F7 | `Met` — `NoFloatingPointMoneyRulesTest` covers `credit`, a planted violation refused; the annuity at scale 10 `HALF_EVEN`, rounded once `HALF_UP` |
| F8 | `Met` — `N/A — credit moves no money`, re-confirmed: nothing settles, nothing reconciles |

## The Phase 10-specific criteria — twenty-four

Five original and nineteen added by the Phase 9 → 10 transition, counted from the gate. All
**`PASS`**, read against the code: the five originals (replay reproduces outcome and reasons;
every decline carries adverse-action reasons; bureau access without consent rejected; policy
changes four-eyes and audited, the active version answerable at any instant; decisions immutable),
each made measurable by its added criterion; *Credit data* (its consent refusal as built, `409`,
recorded under the gate); *Credit profile*; *Affordability*; *Exposure* (the policy's own bound
since this review; "ten ways" recorded under the gate); *Underwriting* (the domain's refusals since
this review); *The policy engine*; *Decisioning* (≥ 200 boundary requests and the raw-SQL edges
since this review); *Explainability*; *Versioning* (the proposal's and rejection's audit and the
generated history since this review); *Provider abstraction* (with `P10-TSK-021` landed);
*Multi-instance correctness* (the lock-bypass probes and the lock order since this review);
*Concurrency*; *Idempotency* (five keyed replays since this review); *Failure recovery*;
*Security*; *Audit*; *Observability*; *Testing* (the full-battery clause as criterion 7's
deviation); *Documentation and invariants*.

## The owner's transition decisions, read against the code

| # | Verdict |
|---|---|
| G1 — consumption a born-once fact, never a column | **Holds** — `credit_decision_consumption`, `UNIQUE (decision_id)`, append-only, written by nothing in Phase 10; the decision row never updated by any role |
| G2 — versions pinned at `SUBMITTED → COLLECTING`, re-read `FOR SHARE` | **Holds** — the pin written once by trigger; shared at the evaluation and the decision |
| G3 — deadline and cadence stamped at birth | **Holds** — frozen by trigger |
| G4 — advisory namespace `10` | **Holds** — the scorecard's first, the policy's reusing it; proposals only, as built |
| G5 — the boundary race and the crash between evaluation and decision proven by `-016` | **Holds** — and the boundary now over 240 requests |
| G6 — request validity 7 days, decision validity 30 | **Holds** — `CreditProduct` |
| G7 — a person's approval bounded, beyond it `422 credit.ExposureLimitExceeded`, nothing recorded | **Holds in substance** — "the evaluation's approved amount" is built as the referral's ceiling `approvable_minor` (a referral approves no amount); documents corrected |
| G8 — `CLOSED` with its request's reason, never stranded | **Holds** — an `OPEN` case's request is abandoned by the sweep only on lost standing; a consent withdrawal surfaces at the person's deciding transaction or the request expires (recorded) |
| G9 — the evidence-read and release routes | **Holds** |
| G10 — foreign keys arrive with their tables | **Holds** |
| G11 — `RECEIVED` terminal; the gate re-read at the freeze and the decision; a retry gated like the first ask | **Holds** — the retry's edge as built: the claim takes `UNAVAILABLE → REQUESTED`, a closed gate then `REQUESTED → CONSENT_WITHDRAWN`; documents corrected |
| A second approver who disagrees refuses (`AWAITING_SECOND → ASSIGNED`, reasoned, audited) | **Holds** |
| Assignment, release and refusal lock the request, then the case | **Holds** — and the expiry takes the same order |

## ADR-0084 … ADR-0089: accepted

Each read against the code, every drifted passage corrected — dated notes where the built shape
differs — and only then accepted: `Accepted (2026-10-09, P10-DOC-001 — read against the code and
corrected first)`. Where the code had drifted from an ADR that was right, the code was corrected
instead: ADR-0086 §7's and ADR-0087 §5's lock order (the evaluating step), ADR-0085's freshness on
the database clock (aged from our own recording), and the lifecycle document's sweep that stops
for a closed request. Where the ADR was silent and the invariant was not — `INV-CRD-09` against a
policy without an exposure rule — the proposal door now holds it and ADR-0086 §6 says so.

## Judgement calls for the owner

1. **The exposure-bound finding is rated IMPORTANT, not CRITICAL**: it could break `INV-CRD-09`,
   but only through a four-eyes administrative act no production deployment ever took, and every
   policy that exists bounds its limit. Corrected and probed before the flip either way.
2. **No alert names a missing `ACTIVE` scorecard** — the policy's absence is alerted; the
   scorecard's leaves requests waiting at `SUBMITTED` until they expire. Recorded, not built: a new
   series is a task's, not a review's - a `CURRENT_STATE.md` debt row owned by Phase 15.
3. **Production decides on fail-safe sources only** until unresolved questions #13 and #14 are
   answered: every pull is unavailable, so every request falls back (refers) and
   `CreditDataUnavailable` fires on any traffic. By design in Phase 10 (no real bureau, §17);
   recorded in the runbook.
4. **Three gate clauses recorded, not re-worded** (the note under the gate's Phase 10 criteria):
   the consent refusal's code, "raced ten ways", and the ≥ 200 requests this review added.

## What the phase produced

One module (`credit`), seventeen migrations across three schemas, two simulated bureaus and a
simulated financial-data provider behind provider-neutral ports, twenty-two routes, twenty audited
acts, sixteen error codes, three permissions under two roles, the §15 series, nine alert rules, a
dashboard row, three audited reports, two leaderless schedules, the storm that proves every
decision correct on two instances whose clocks disagree, and the battery that replays ten thousand
generated applicants `IDENTICAL` in two JVMs.

## What happens next

The Phase 10 → 11 transition is `READY`. Phase 11 (Lending) inherits a decision it will reference
and a reservation it will consume, through `CreditDecisions` and `credit_decision_consumption`, and
the debt rows owned by Phases 13, 15 and 16.
