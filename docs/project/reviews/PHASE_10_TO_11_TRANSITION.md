# Phase 10 → Phase 11 Transition

**Conducted:** 2026-10-10
**Parts:** Phase 10 completion audit · credit decision correctness · mandatory multi-instance audit ·
atomicity, consistency and idempotency · security and privacy · testing · architecture and debt ·
blocking issues and repair · Phase 10 completion · Phase 11 initialisation
**Constraint:** CRITICAL and IMPORTANT Phase 10 defects are resolved before the transition, never deferred
as debt, and a task is not trusted because it was marked complete. Like the transitions before it, this
one wrote application code — the repairs in §8, each tested, each broken on purpose to prove its test,
each restored byte-identical (sha256-verified) and recorded in `MUTATION_TESTING.md` §2. It wrote no
Phase 11 code — Phase 11 exists below only as documents.

**Method.** Six parallel adversarial read-only audits over master `8927a1e7` (Phase 10 recorded
`COMPLETE` by `P10-DOC-001`): decision correctness and explainability; multi-instance, atomicity and
idempotency; security and privacy; test adequacy; architecture, documentation and the gate's criteria;
provider adapters and data collection. Each finding had to be proven from the code with a file, a line and
a failure scenario. Three repair agents fixed them on separate branches (`repair-collection` `76147ebe`,
`repair-decisioning` `f3e9ddc3`, `repair-tests` `8b95fb03`), merged into `phase-10-to-11-transition`
and re-verified together.

---

## Verdict

| Part | Outcome |
|---|---|
| 1. Phase 10 completion audit | **`PASS` after repair.** Every capability area present; the gate's criteria re-graded against the code (§1). Universal criterion 7 is `PARTIAL` by a recorded deviation the owner re-affirmed (§7). |
| 2. Credit decision correctness | **`PARTIAL` as found, `PASS` after repair.** No wrong, lost or contradictory decision could be proven; but a decision's link to its own request's snapshot was not enforced, a person could decide on data past its maximum age, and an absent bureau balance counted as zero in a person's exposure check (§2). |
| 3. *"Would Phase 10 remain correct if 10 service instances executed the relevant operations concurrently?"* | **`PASS`** for Phase 10 as built, with one latent defect that would have broken `INV-CRD-09` the moment Phase 11 consumed a decision (exposure blind to outstanding credit) — repaired (§3). |
| 4. Atomicity, consistency, idempotency | **`PASS`** — every workflow's owner, transaction boundary, concurrency control, idempotency and version pin identified (§4); no distributed atomicity is claimed. A policy change cannot yield an ambiguous result: pins are written once and every later read is by pinned id `FOR SHARE`. |
| 5. Security and privacy | **`FAIL` as found** (an underwriter could decide their own credit application; credit's free-text reasons were never screened for card or account numbers), **`PASS` after repair** (§5). |
| 6. Testing | **`PARTIAL` as found** (INV-CRD-10's claimed scale check did not exist; the pin's `FOR SHARE` had no test that failed without it; one race assertion could not fail), **`PASS` after repair** (§6). |
| 7. Architecture and debt | **`PARTIAL` as found** (the MFA gate clause reworded to match the code; the consumption write without an owner; Phase 11 planning documents thin), resolved or scheduled (§7). |
| 8. Blocking issues | **No CRITICAL. Fifteen IMPORTANT (deduplicated), every Phase 10 one repaired, tested and probed** (§8); one — the consumption write's owner — belongs to Phase 11's first task by design. |
| 9. Phase 10 completion | **`COMPLETE`, confirmed after repair.** |
| 10. Phase 11 initialisation | **`READY`** — see §10. |

---

## 1. Phase 10 completion audit

Re-graded against the code, not the exit review's word. Capability areas: credit profile, credit data,
credit assessment, affordability, exposure, bureau integration (simulated; production fail-safe until
unresolved questions #13 and #14 are answered), the risk seam (`NotAssessedUntilPhase13`, version 1, frozen
as `RISK_SIGNAL`), underwriting, credit policy and its versioning, decisioning, reason codes, model
(scorecard) versioning, explainability, decision audit, provider adapters, persistence, transaction
boundaries, consistency, idempotency, concurrency, failure handling, observability — **`PASS`** (exposure and
model versioning `PASS` for Phase 10 with the repairs of §8). Security and privacy, testing and
documentation — **`PARTIAL` as found, `PASS` after repair**. Universal criteria 1–6 and 8–12 `PASS`;
criterion 7 `PARTIAL` by the recorded deviation of §7. F1–F8 met (F1, F2, F4, F5 vacuous: credit moves no
money; F8 not applicable). The Phase 11 boundary holds: no loan, offer, disbursement or repayment code; no
`ledger` edge from `credit`; `credit_decision_consumption` written only by tests.

## 2. Credit decision correctness

| Check | Result |
|---|---|
| Input data and source references preserved | `PASS` — the sealed snapshot (canonical text, SHA-256 also checked by the database), provenance per attribute, encrypted evidence with its id as associated data. |
| Policy version identifiable | `PASS` — pinned once on the request (trigger), carried on the decision. |
| Model version identifiable | `PASS` — the scorecard version pinned likewise; the engine version on every evaluation. |
| Reason codes recorded | `PASS` — stored by ordinal; replay compares them in order. |
| Manual overrides authorized and audited | `PASS` after repair — `CREDIT_UNDERWRITE`, four eyes by domain and `CHECK`, hard declines and the exposure limit binding; **an underwriter acting on their own party's case is now refused** (§8 R1). |
| A later policy change cannot rewrite history | `PASS` — rules and bands immutable from insert; pins written once; replay reads pinned rows only. |
| Duplicate requests | `PASS` — keyed doors, one open request per party and product, `UNIQUE` one decision per request, born-once assessment and evaluation. |
| Stale, incomplete, inconsistent external data | `PASS` after repair — freshness on the database clock (`LEAST(retrieved_at, recorded_at)`); ABSENT with markers; never converted; **now also re-judged at the decision** (R10), **re-collection bounded** (R5), **strict parsing** (R4), **a poisoned answer ends `UNAVAILABLE`** (R3). |
| Failure and manual-review paths | `PASS` — explicit states, expiry, abandonment reasons; an evaluation error looping to expiry is recorded debt. |
| Separate from loan servicing | `PASS`. |

**Decisions that could not be reproduced or explained:** none found in the data. Two paths could have
produced one: a decision on another request's snapshot (now refused by composite keys and a trigger, and
reported `HASH` by replay — R9) and a person's decision on records past their maximum age (now refused —
R10).

## 3. Multi-instance audit

No `synchronized`, JVM lock, in-memory authoritative state or process-local idempotency anywhere in credit.
Every window is judged on `statement_timestamp()` / `transaction_timestamp()`. No connection is held across
a provider call. Every writer takes the documented lock order — profile, request, case, data requests,
versions — implicit foreign-key `KEY SHARE` locks included; no cycle. Concurrent assessments, duplicate
requests, concurrent exposure changes, duplicate bureau answers, timeout-then-retry, duplicate events,
concurrent manual review and second approvals, policy changes mid-assessment, restarts, lost updates, stale
reads, partial failure and duplicate workflow execution were each traced to an arbiter in PostgreSQL.
**One latent defect** (R12): the deciding transaction re-read only the reserved exposure and a successor
snapshot copied the first freeze's outstanding credit, so once Phase 11 consumes an approval into a loan the
limit would be judged on a stale figure. **Answer: `PASS`.**

## 4. Atomicity, consistency and idempotency

| Workflow | Owner | Transaction | Concurrency control | Idempotency / pin | External; retry; recovery |
|---|---|---|---|---|---|
| Submit | `decision_request` | one | partial unique (party, product) over open states | `IdempotentExecutor` per actor | none |
| Collect / pin | request, `data_request` | one step | request row, then versions `FOR SHARE`; pin written once | conditional edge | asked after commit; the retry sweep covers a crash |
| Data answer | `data_request` | one | data-request row, from `REQUESTED` only | `UNIQUE(data_request_id)`; the reference is the provider's key | a duplicate kept as evidence; **an unrecordable answer `UNAVAILABLE`** |
| Retry sweep | `data_request` | claim, gate, pull, record | `UPDATE … SKIP LOCKED`; **deadline-bound; open requests only** | same reference | ends at the deadline; reported once |
| Freeze / assess / evaluate | request and children | one | request, data requests, versions | born once per (request, sequence) / snapshot / assessment | a stale record re-collects **once**; else the kind is unavailable |
| Decide (system and person) | `credit_decision` | one, after the claim is released | profile, request, (case), versions; **reserved and outstanding re-read; freshness re-judged** | `UNIQUE(decision_request_id)`; **composite key to its own snapshot** | the next tick re-drives |
| Assign / release / second approval | `underwriting_case` | one | request, case; conditional on case state | key per principal | — |
| Policy / scorecard propose, approve | version rows | one | advisory namespace 10; proposal then active row `FOR UPDATE` | conditional on `PROPOSED`; four eyes by `CHECK` | — |
| Replay | read only | `REPEATABLE READ` | snapshot isolation | pinned rows by id | — |

## 5. Security and privacy

`PASS` as found: data access (the `SECURITY DEFINER` evidence read with a reason, column grants), logging
hygiene (class names and SQLStates only, redacting `toString`s, enum-only metric tags, attribute-free spans
and payloads), credentials and the evidence key, route authorization with negatives, ownership with a uniform
404, consent at every step, input validation. Repaired: **self-dealing** (R1) and **unscreened reasons**
(R2). Recorded MINOR debt: the policy-at-an-instant read unaudited; the review queue's audit not naming the
cases served; not-found reads unrecorded; the evidence key ring holding one version; a reason of one
character accepted for evidence reads.

## 6. Testing

Fresh baseline on master before repair — hermetic 2765 across 460 suites and 19 modules, architecture 213
across 51, database 126 across 15, own-container 103 across 11, **0 failures** — and yet the test audit
found real gaps, as Part 6 anticipated: INV-CRD-10's claimed scale check absent (R6), the pin's `FOR SHARE`
untested (R7), a race assertion that could not fail (R8). After repair (§8), on the merged tree: **hermetic
2768 across 461 suites and 19 modules, architecture 213 across 51, database 140 across 16, own-container 118
across 11 (the storm and the ten-thousand-decision battery included), 0 failures.** The fleet-wide
`databaseTest` and `kafkaTest` tiers were not run (§7).

## 7. Architecture, documentation and recorded deviations

- **The MFA clause** — the gate requires an MFA-assured session for credit submission; the code stepped up
  only when a factor was enrolled and the exit review had reworded the plan to match. **Owner decision
  (2026-10-10): enforce MFA** (R14).
- **Universal criterion 7** — the fleet-wide `databaseTest` and `kafkaTest` tiers were not run in Phase 10
  under an instruction written for Phase 9. **Owner decision (2026-10-10): keep skipping**, re-affirmed for
  this gate and Phase 11 — a standing, recorded deviation; asked again at the Phase 11 → 12 transition.
- **The consumption write had no owner** — `BOUNDED_CONTEXTS.md` had lending writing credit's
  `credit_decision_consumption` directly, the table's trigger guards nothing, and the application role holds
  `INSERT`. Nothing in Phase 10 writes it; the fix is a credit-owned command port taken under the party's
  profile lock with a database guard — **`P11-TSK-001`, Phase 11's first task** (§10), an entry condition
  for any loan.
- MINOR, corrected: stale "the evaluation's approved amount" text (the bound is the referral's ceiling);
  ADR-0087's event-evolution contradiction (v2 is a version bump; Phase 11's consumer refuses or handles
  v1); caps below the product minimum now refused. MINOR, recorded: replay not comparing figures or rule
  states; the assessment arithmetic not dispatched by engine version; `Phase10Reports` in `app` against its
  charter; the unresolved-questions register's stale dates (#12, #13).

## 8. Blocking issues and repair

| # | Finding (severity) | Repair | Test · probe |
|---|---|---|---|
| R1 | An underwriter could decide their own credit application (IMPORTANT) | `CreditActingParty` port; assign, decide and both second-approval acts refuse a case of the actor's own party, `403 credit.SelfDealingRefused`; the attempt audited `FAILED` (`credit.ReviewOwnCaseRefused`) | `UnderwritingCaseDatabaseTest#anUnderwriterNeverActsOnTheirOwnPartysCase`, over HTTP too · caught |
| R2 | Free-text reasons never screened for card or account numbers (IMPORTANT, `INV-AUD-02`) | `CreditReasons` at the domain; `credit V016` CHECKs on every person-written reason column and in `credit.read_evidence` | `CreditReasonScreenDatabaseTest`, `#aPersonsReasonsHoldNoInstrumentShape` · caught |
| R3 | A `REQUESTED` data request had no deadline — a poisoned answer retried forever (IMPORTANT, `INV-CRD-10`) | claims deadline-bound; a past-deadline `REQUESTED` reported `UNAVAILABLE` once; an unrecordable answer `UNAVAILABLE` with an `UNRECORDED` attempt and its evidence kept (`credit V019`, `V020`) | `BureauCollectionDatabaseTest` (both bureaus) · three probes caught |
| R4 | "Malformed" was a regex, not a JSON parse (IMPORTANT) | `CreditProviderJson`: strict parse, duplicate keys and trailing tokens refused, top-level fields only; normalisers versioned up; fifteen golden files | the three golden suites · three probes caught |
| R5 | Re-collection unbounded (IMPORTANT, `INV-CRD-08`) | a record stale when recorded, or a second staleness, makes the kind unavailable — the fallback decides (ADR-0085 §11) | `DecisionSnapshotDatabaseTest`, `DecisionOrchestrationDatabaseTest` · two probes caught |
| R6 | INV-CRD-10's scale check did not exist (IMPORTANT) | `MissingDataCensus` in every storm round and over the battery, recomputed from the rows | storm, `#missingDataNeverApproves` · two probes caught (replay alone could not) |
| R7 | The pin's `FOR SHARE` had no failing test (IMPORTANT, `INV-CRD-05`) | four lock-wait tests on the production steps, asserting the waiting statement | `CreditDecisionDatabaseTest` · four probes caught |
| R8 | A race assertion could not fail; the person path's profile lock unprobed (IMPORTANT, `INV-CRD-09`) | both orders asserted; a lock-wait test on the person's deciding transactions | `UnderwritingCaseDatabaseTest` · caught |
| R9 | A decision's link to its own request's snapshot not enforced (IMPORTANT, `INV-CRD-06`) | `credit V017` composite keys and triggers (decision, assessment, evaluation, case); replay reports `HASH` | `#aDecisionRestsOnItsOwnRequestsSnapshot`, `DecisionReplayDatabaseTest` · caught |
| R10 | A person could decide on records past their maximum age; a successor copied them unjudged (IMPORTANT, `INV-CRD-08`) | freshness re-judged in the deciding transaction and the successor; `422 credit.DataStale` | `#nothingIsDecidedOnStaleData` · caught |
| R11 | An absent bureau balance counted as zero in a person's exposure check (IMPORTANT, `INV-CRD-09`) | **owner decision: refused** — `422 credit.ExposureUnassessable`; a decline still allowed. Consequence recorded: production approves nothing until a real bureau is connected | `#aPersonCannotApproveAnUnassessableExposure` · caught |
| R12 | Exposure blind to outstanding credit — latent, blocking Phase 11 (IMPORTANT, `INV-CRD-09`) | reserved and outstanding re-read together under the profile lock; the successor replaces both; ADR-0088 corrected: **Phase 11's loan / consumption writer must hold the party's `credit_profile` row `FOR UPDATE`** | `#theOutstandingCreditIsReReadAtTheDecision` · caught |
| R13 | The consumption write without an owner (IMPORTANT) | scheduled — `P11-TSK-001`, Phase 11's first task (§7) | — |
| R14 | Credit submission without MFA when no factor enrolled (IMPORTANT, gate clause) | **owner decision: enforce** — every submission requires a `MULTI_FACTOR` session | `DecisionRequestApiTest` · caught |
| R15 | Universal criterion 7 graded `PASS` (IMPORTANT, gate honesty) | re-graded `PARTIAL`; **owner decision: keep skipping**, recorded | — |

The rest, MINOR, are corrected or recorded in §§5 and 7 and in ADR-0085's and ADR-0089's Follow-up.

## 9. Phase 10 completion

**Phase 10 — Credit Decisioning — `COMPLETE`, confirmed after repair (2026-10-10).** Delivered: the credit
profile; consented bureau and financial-data collection behind provider-neutral ports with simulated
providers and a fail-safe production composition; the sealed decision snapshot; affordability, exposure and
the versioned scorecard; the four-eyes credit policy and the deterministic, versioned evaluator; the decision
request, orchestration, the immutable decision with ordered reason codes, the customer's adverse-action view
and the investigator's explanation; four-eyes manual underwriting; replay and the reproducibility battery;
credit's meters, reports and alerts; the storm. Non-blocking debt carried forward: the evidence purge and
crypto-shredding (Phase 15); real bureau and financial-data connectivity (unresolved #13, #14); the risk score
(Phase 13); the items recorded in §§5 and 7.

## 10. Phase 11 initialisation

Recorded in `docs/project/PHASE_11_PLAN.md`, ADR-0090 onward, the `INV-LND-*` invariants, the Phase 11
backlog and `CURRENT_STATE.md`. **Owner decisions (2026-10-10):** loans are funded from a platform
lending-capital account, so the safeguarding proof stays exact; disbursement to the borrower's wallet **and**
to an external bank account; Phase 11 services the **personal loan and the revolving credit line**; the
servicing conventions recorded in the plan are accepted as the product assumptions (a neutral EUR reference
jurisdiction without consumer-credit-law features; ACT/365F simple daily interest rounded once per period;
allocation oldest-due first, fees then interest then principal, any excess held as a credit; default at 90
days past due; no penalty interest, prepayment fee or APR display) — each configurable and versioned.
