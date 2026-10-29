# The Phase 13 → 14 Transition, Part A — the Phase 13 Completion Gate

**Conducted:** 2026-10-29, on branch `P13-GATE` from master `f94f331a`
**Prescribed by:** [`PHASE_GATES.md`](../PHASE_GATES.md) §1 (the status model), §3 (universal exit criteria and the
financial supplement), §5 Phase 13 (the six originals and the per-area blocks); [`EXECUTION_PROTOCOL.md`](../EXECUTION_PROTOCOL.md);
[`DEFINITION_OF_DONE.md`](../DEFINITION_OF_DONE.md); the owner's gate prompt (PART 1–6)
**Precedent:** [`PHASE_12_TO_13_TRANSITION.md`](PHASE_12_TO_13_TRANSITION.md) (Part A, the Phase 12 gate)
**Not done here:** Phase 14's initialisation (Part B) - the coordinator flips the phase pointer after this gate passes.

| | Outcome |
|---|---|
| **Gate result** | **`PASS` - Phase 13 `COMPLETE`, confirmed after three repairs made inside the gate** |
| *"Would Phase 13 remain correct if 10 service instances executed the relevant operations concurrently?"* | **`PASS`** - on arbitration and on the database-tier concurrency suites that ran green; the storm's counted half is the owner's acceptance, not a measurement (below) |
| The owner's ratification (2026-10-29, "Accept recommendations") | **Recorded** per group in `PHASE_13_PLAN.md` §1.2, §2.2, §24, in `PHASE_13_REVIEW.md`'s addendum and in `BACKLOG.md` |
| Findings | **CRITICAL 0. IMPORTANT 3 - every one fixed in the gate** (items 79, 78, 25). **MINOR** - the other "fix before production use" and every "decide before activation" item, as remediation entries `P13-REM-001`…`-013`; three document defects corrected |
| Repairs | risk `V019` (`risk_case_alerts_closed_by_two`), identity `V024` (`suspension_cause`); `CaseEvidence`, `CaseWorklist`, `DispositionRules`, `RiskCase`, `Cases`, `IdentityAdministration`, `IdentityStore`/`JdbcIdentityStore`, `SuspensionCause`, `IdentityRiskActions`; each with a regression test and a mutation probe (`MUTATION_TESTING.md` §2, seven rows) |
| Universal criterion 7 | **`PARTIAL`, the standing C5 deviation** - the gate ran the database suites its repairs touch, not the fleet (Phase 13's exit authorisation covers repairs made before the gate closes) |

The gate reused the exit review's evidence ([`PHASE_13_REVIEW.md`](PHASE_13_REVIEW.md): the owner-authorised database
pass, 103 suites and 897 tests green; 269 gate sub-clauses read with ten independent audits; the storm accepted by the
owner on its partial evidence) rather than re-deriving it, read the code where a check needed it, and fixed what the
owner's ratification put inside the gate.

---

## PART 1 — the capabilities, against the Phase 13 backlog and the documented exit criteria

| Capability | Result | Evidence (named tests; the exit review's database pass unless marked "gate") |
|---|---|---|
| Risk signal ingestion | `PASS` | `RiskSignalsTest`, `RiskSignalMigrationTest`, `SignalReadingsTest`, `SignalIntakeDatabaseTest` (born once per source event, replay harmless) |
| Assessments and decisions | `PASS` | `RiskEngineV1Test` (deterministic, two-JVM), `RiskEvaluationDatabaseTest#tenRetriesOneAssessment`, `#anUnserialisedDuplicateLosesAtTheSeal`, `#aTimeoutSealsTheFailSafe`, `#theVersionIsNamedUnlessNoneCouldBePinned`; the seams `TransferRiskSeamDatabaseTest`, `WithdrawalRiskSeamDatabaseTest`, `PaymentRiskSeamDatabaseTest`, `CrossBorderRiskSeamDatabaseTest`, `CreditRiskSeamDatabaseTest` |
| Policy and rule versioning | `PASS` | `RiskPolicyDatabaseTest#theProposerCannotActivate` (four eyes by name), `RiskPolicyEndpointDatabaseTest`, `P13-TST-002`'s battery fingerprint |
| Explainability | `PASS` | `AssessmentReplayTest`; the explanation walk in-suite (`WithdrawalRiskSeamDatabaseTest#aHoldCommitsHeldWithTheHoldKept`, `TransferRiskSeamDatabaseTest#aRefusedTransferCountsNothingAndCommitsRiskRefused`, `RiskEvaluationDatabaseTest#aReviewDecisionOpensItsCaseInTheMoneyTransaction`); the walk's first step a query (recorded, item 88's neighbour) |
| Fraud workflows | `PASS` | `AccountTakeoverTest`, `MerchantAbuseDatabaseTest`, `PaymentAbuse`'s suites; deferred with owners: items 7, 13, 15, 18 |
| Transaction monitoring | `PASS` | `MonitoringIntakeDatabaseTest`, `MonitoringRulesDatabaseTest`, `RiskMigrationTest#theMonitoredTransactionIsBornOnceAndAppendOnly`, `RiskCensusesTest` |
| AML alerts | `PASS` | `AlertDatabaseTest` (9, gate re-run), `AlertSuppressionApiTest` (gate re-run), `RegulatoryReportDatabaseTest#theApproverIsNotTheDrafter` |
| Alert deduplication | `PASS` | `RiskBornOnceArbitersDatabaseTest` (21 uniques: 18 refused alone, 3 key-bound; gate re-run), `CaseDatabaseTest#aDuplicateAlertAttachesAndIsNeverSuppressed` |
| Case management and investigation | `PASS` (after repair) | `CaseDatabaseTest` (14, gate re-run), `CasesTest`, `RiskCaseApiTest`; **gate: `CaseEvidenceTest#aReferenceOfTheReadersOwnPartyIsRefused`, `#aReferenceThatCannotBePlacedIsRefused`, `RiskReportsApiTest#theEvidenceDoor` (item 79)** |
| Manual review and escalation | `PASS` | `CasesTest` (take, escalate, revision), `QueueApiTest`, `DecisionOverrideDatabaseTest#theProposerCannotApprove`, `RiskOverrideApiTest` |
| Disposition and audit history | `PASS` (after repair) | `CaseDatabaseTest#theProposerCannotApprove`, `#tenApproversOneDisposition`; **gate: `CaseDatabaseTest#aCaseHoldingAnAlertIsClosedByTwo`, `CasesTest#aCaseHoldingAnAlertIsClosedByTwo`, `DispositionRulesTest#aCaseHoldingAnAlertIsClosedByTwo` (item 78)**; `risk_case_event` history by trigger; `AuditableActionRegistryTest` |
| Integrations - payments, transfers, cross-border | `PASS` | the seams above; `PaymentRiskMigrationDatabaseTest#aHoldIsBornOpenAndResolvesOnce` |
| Integrations - identity | `PASS` (after repair) | `RiskActionApiTest` (gate re-run); **gate: `IdentityRiskActionsTest#riskLiftsOnlyItsOwnSuspension`, `SuspensionConcurrencyDatabaseTest#riskLiftsOnlyItsOwnSuspension` (item 25)** |
| Integrations - KYC/KYB | `PASS` | `RescreeningDatabaseTest#theRanksBeneathTheDomain` (kyc's rescreen door, O1) |
| Integrations - credit, lending, BNPL | `PASS` | `CreditRiskSignalTest`, `CreditRiskSeamDatabaseTest` (seam version 2, sealed version-1 decisions replay `IDENTICAL`), `CollectionsCaseDatabaseTest` (gate re-run) |
| Integrations - reconciliation | `PASS` | risk reads the ledger's journal and posts nothing; `MonitoringCensus`, `AssessmentCensus` and their gauges (`RiskCensusesTest`); `RiskCensusDiverged` |
| Persistence and transaction boundaries | `PASS` | the lock order read from the code for every transaction (`DISTRIBUTED_EXECUTION.md` §3), acyclic; the gate's two additions take no new lock (V019 reads `risk_alert` under the case's existing R4; V024 is one conditional write in identity's own transaction) |
| Idempotency and concurrency | `PASS` | `RiskActionDatabaseTest` (one approval, one claim, one effect per key), `CaseDatabaseTest#tenApproversOneDisposition`, `SuspensionConcurrencyDatabaseTest` (ten instances, one transition - gate) |
| Failure recovery | `PASS` | Area 4: the pin's read failing (item 75 - an activation precondition now), the lost filing response (`UNKNOWN` inquired, never re-filed) |
| Security and privacy | `PASS` (after repair) | `RiskStepUpRoutesTest`, `RoutePermissionRegisterTest`, `NoCustomerSurfaceReadsRiskTest`, every four-eyes `CHECK` refused alone by name; the three gate repairs |
| Observability | `PASS` | `PlannedMetersExistTest`, `RiskAlertRulesTest`, `RiskProofDesksTest`; operational gaps 68, 85 are `P13-REM-005` |
| Testing and documentation | `PASS` | `MutationDemonstrationTest` (every `Phase: 13` invariant demonstrated), `TestTaxonomyTest`, `AdrRegistersAreReconciledTest`; three stale documents corrected by the gate (below) |

## PART 2 — the risk-and-decision checks

| # | Check | Result | Basis |
|---|---|---|---|
| 1 | Inputs and sources preserved | `PASS` | the sealed assessment's canonical inputs and digests (`V007`, insert-only for every writer); signals keep their source event (`risk_signal_id_and_source`) |
| 2 | Policy version identifiable | `PASS` | every assessment pins its version (`risk_assessment_pinned_unless_unpinnable`, proven alone); `PIN_UNREADABLE` the only exception, recorded |
| 3 | Outcomes and reason codes recorded | `PASS` | the verdict, fired hits and ordered reasons sealed; the fail-safe a recorded decision (`INV-RSK-04`) |
| 4 | Overrides authorised and audited | `PASS` | four eyes by `CHECK` (`DecisionOverrideDatabaseTest#theProposerCannotApprove`), C7's band, `MULTI_FACTOR`, refusals audited `FAILED` |
| 5 | Repeated evaluation creates no duplicate actions | `PASS` | `UNIQUE (operation, command_ref)` decides (`#anUnserialisedDuplicateLosesAtTheSeal`); actions keyed `risk.action:<id>` and replayed (`IdentityRiskActionsTest`, `RiskActionDatabaseTest`) |
| 6 | Late and out-of-order events handled explicitly | `PASS` | a late alert never re-opens a disposed case - a new case names it (`CaseDatabaseTest#aLateEventAfterDispositionOpensANewCase`, OQ-6); the monitoring watermark and settle horizon (item 92, ratified as the recorded bound, asked again at activation) |
| 7 | History not rewritten by a later policy | `PASS` | assessments sealed; replay `IDENTICAL` against the pinned version; profile versions append-only (`INV-RSK-10`) |
| 8 | Risk is not an alternative owner of payment, account, identity or ledger state | **`PASS` after repair** | risk posts nothing and writes no other domain's row (`INV-RSK-02`, `RiskModuleIsolationTest`); **item 25 was the one breach of the spirit**: risk's reinstatement lifted suspensions identity's administrators made. Fixed - identity records the cause and lifts only a `RISK` one for risk (identity `V024`) |

## PART 3 — the AML and fraud workflow checks

| Check | Result | Basis |
|---|---|---|
| Duplicate events create no duplicate alerts or cases | `PASS` | `risk_alert_born_once` and `risk_case_one_open`, each refused alone (`RiskBornOnceArbitersDatabaseTest`); a duplicate alert attaches, never suppressed |
| Evidence and rationale retained | `PASS` | evidence by reference, resolved through audited reads; reasons bounded and screened; the case's history row per revision |
| Case actions auditable | `PASS` | every act audited (forty acts, `AuditableActionRegistryTest`); refusals `FAILED` in their own transaction |
| A resolved case is not silently reopened by a stale event | `PASS` | `DISPOSED` is terminal (`risk_case_permits_only_machine_edges`); a late alert opens a new case naming its predecessor |
| Investigation data protected | **`PASS` after repair** | the tipping-off guards (`NoCustomerSurfaceReadsRiskTest`, `INV-RSK-08`); self-dealing fails closed at every door - **and now at the evidence door's references too (item 79)** |
| Replay or reprocessing creates no duplicate downstream actions | `PASS` | actions born once and keyed; the owning domain's keyed door replays the first answer |
| Regulatory reporting distinguishable from internal alerts and investigations | `PASS` | a distinct artefact (`V016`), `AML_REPORT_APPROVE`, four eyes by `CHECK`; the analyst's file never carries a report (`REPORT_DRAFTED` withheld); item 88's act answers are `P13-REM-002` |

## PART 4 — the multi-instance audit

| Concern | Result | How it is decided (and where it is proven) |
|---|---|---|
| Concurrent evaluations | `PASS` | (R2a) per-subject locks, the seal's unique; item 77's band-only `TIMEOUT` under a burst is an activation precondition (`P13-REM-011`) - no policy is active, so nothing is judged today |
| Duplicate transaction events | `PASS` | `monitored_transaction` born once by `UNIQUE (journal_entry_id)` |
| Duplicate alerts | `PASS` | `risk_alert_born_once` (`ON CONFLICT DO NOTHING`), raced ten ways (`CaseDatabaseTest`) |
| Concurrent case creation | `PASS` | `risk_case_one_open`; ten alerts fold into one open case |
| Concurrent investigator actions | `PASS` | the case row `FOR UPDATE` and the `revision` compare-and-set (`#concurrentUpdatesKeepEveryAction`) |
| Concurrent overrides and approvals | `PASS` | conditional edges; ten approvers, one disposition (`#tenApproversOneDisposition`); one approval per action |
| Item 78's closure racing an alert's attach | `PASS` (gate) | the closure reads the case's alerts under the case's `FOR UPDATE`; an attach moves the case row, so it either commits first and is seen, or waits and finds the case `DISPOSED` and opens a new one; V019 re-checks every writer |
| Item 25's lift racing a suspension | `PASS` (gate) | one conditional `UPDATE ... AND suspension_cause = ?`; ten instances lifting risk's own produce one transition (`SuspensionConcurrencyDatabaseTest#riskLiftsOnlyItsOwnSuspension`) |
| Policy change during evaluation | `PASS` | the pin read once, sealed; activation under four eyes takes the version row |
| Delayed or out-of-order events | `PASS` | as PART 2 check 6 |
| Downstream retries | `PASS` | keyed `risk.action:<id>` doors; `DISPATCHED` re-driven by a leaderless schedule (`SKIP LOCKED`) |
| Restart | `PASS` | every claim and schedule is a database row; nothing is held in memory across a request |
| Races and lost updates | `PASS` | every arbiter in the database: uniques, conditional edges, `CHECK`s proven alone |
| Partial failures | `PASS` | a refusal strands nothing; the action outside T-case; item 84's driver starvation is `P13-REM-004` |
| Replay | `PASS` | replay `IDENTICAL`; born-once keys absorb reprocessing |

**"Would Phase 13 remain correct if 10 service instances executed the relevant operations concurrently?" → `PASS`.**
Basis, stated honestly: every deciding instant and every arbiter is the database's - unique keys, conditional edges,
`SKIP LOCKED` claims, four-eyes `CHECK`s each refused alone by name; the coded lock order is acyclic for every
transaction; every §7.4 contention row's named test exists and races (12 of 16 with real concurrency), and the database
suites ran green in the exit review's owner-authorised pass and, for every suite this gate's repairs touch, again here.
**Not measured:** the storm (`P13-TST-001`) was accepted by the owner on its partial evidence (the ninth full run green on
all 57 census checks at every round) - its latency table, probes, final run and gate were waived, so the T-eval latency
budget, the named lock waits for (R1), (R4), (R7), (R8) and "no `40P01` in any storm run" are the owner's acceptance, not a
measurement (PI-004). That is why item 77 - a hot subject's concurrent burst - is an activation precondition: it is the one
ten-instance behaviour whose correctness depends on a timing the storm never measured.

## PART 5 — security, architecture, testing; the findings

| Area | Result |
|---|---|
| Authorization for sensitive actions | `PASS` - forty routes registered; `MULTI_FACTOR` on every four-eyes approval; seven four-eyes `CHECK`s, **eight with V019's trigger** |
| PII and investigation evidence | `PASS` after item 79 - classified (`DATA_CLASSIFICATION.md`, now with `identity.suspension_cause`); served only through audited, self-dealing-judged reads |
| Auditability of policy changes and overrides | `PASS` |
| Tenant and customer isolation | `PASS` after item 79 - a reader never reads a reference of their own party; unplaceable references fail closed |
| Safe logging | `PASS` - no subject, note or amount in logs or tags (`CredentialReachesNoEmittedSinkTest`, C9) |
| Durable dedup and idempotency | `PASS` |
| Transaction and consistency boundaries | `PASS` |
| Failure recovery | `PASS` (items 75, 84 recorded as remediation) |
| Meaningful tests | `PASS` - every gate repair probed red and restored byte-identical |

### Findings

**CRITICAL - none.**

**IMPORTANT - three, each fixed in the gate** (the owner's "fix before production use", ratified 2026-10-29):

- **G-1 / item 79 - the evidence door served references of the reader's own party** (security). `CaseEvidence#read`
  judged the case's subject only. *Repair:* each served reference's parties judged before it is answered - a signal's
  subject (an account through `MonitoredParties`, every other kind through the accountable join), a journal entry's
  subject (customer account or merchant) and its counterparty when that is a party's (customer account, merchant, loan,
  BNPL obligation - through the credit agreement's party); the reader's own refused `OWN_PARTY`, an unplaceable one
  `SUBJECT_UNPLACEABLE` (fail closed), recorded `FAILED`, nothing served. Operational, suspense and external
  counterparties name no party. *Consequence, recorded:* a signal whose subject is a device, a session or a beneficiary is
  never served (no party can be placed). Files: `CaseEvidence`, `CaseWorklist` (`readerParty`, `judgeReferenceParty`),
  `RiskProofBeans`. Tests: `CaseEvidenceTest#aReferenceOfTheReadersOwnPartyIsRefused`, `#aReferenceThatCannotBePlacedIsRefused`
  (hermetic), `RiskReportsApiTest#theEvidenceDoor` (database, HTTP - its fixture now places real wallet agreements).
  Probe: the judgement loop emptied → both hermetic tests red; restored, sha256 `4b562c47…67e59c7a`.
- **G-2 / item 78 - suppression's four eyes reached by one person's case closure.** *Repair:* a `CLOSED_*` of a case
  holding an alert is two persons' act on a taken case - every kind but `COLLECTIONS` (C3, ratified: a default is a fact).
  Domain: `DispositionRules#needsSecondPerson(disposition, prohibits, kind, holdsAlerts)` and `needsTaken(..)`, read by
  `Cases#proposeDisposition` under the case's lock; `RiskCase.DispositionSlot#needsSecondPerson` now true for a standing
  or a two-person decision. Every writer: **risk `V019`**, trigger `risk_case_alerts_closed_by_two` (23514 by name).
  Tests: `CasesTest#aCaseHoldingAnAlertIsClosedByTwo`, `DispositionRulesTest#aCaseHoldingAnAlertIsClosedByTwo`,
  `CaseDatabaseTest#aCaseHoldingAnAlertIsClosedByTwo` (the raw one-person close refused alone for the application and
  the migrator); three tests that closed alert-holding cases alone now close them by two (`CaseFoldingTest` ×2,
  `RescreeningRunTest`, `CaseDatabaseTest#aLateEventAfterDispositionOpensANewCase`); `RiskMigrationTest`'s function
  register gained the trigger function. Probes: the alert term dropped → three hermetic tests red; V019's `RAISE` → `NULL`
  → the database test red; restored, sha256 `2fb621b6…af1848ed` and `9ad6b120…c8b74a25`.
- **G-3 / item 25 - `REINSTATE_IDENTITY` lifted every suspension of the party's identities** (PART 2 check 8).
  *Repair:* **identity `V024`** - `identity.identity.suspension_cause` (`ADMINISTRATOR` | `RISK`, NULL when not suspended
  or suspended before V024; two `CHECK`s); `SuspensionCause`; `IdentityStore#moveStatus(.., Optional<SuspensionCause>)`
  records the cause on the way in, clears it on the way out and, when a cause is named, makes it a condition of the lift;
  `IdentityAdministration#suspend/#reinstate(.., SuspensionCause)` (the administrator's overloads unchanged in
  behaviour - an administrator lifts any); `Reinstatement.OTHER_CAUSE`; `IdentityRiskActions` suspends and lifts as
  `RISK` (`OTHER_CAUSE` → `REFUSED`). Legacy suspensions carry no cause and are lifted by an administrator only - fail
  closed, nothing back-filled. Tests: `IdentityRiskActionsTest#riskLiftsOnlyItsOwnSuspension` (hermetic),
  `SuspensionConcurrencyDatabaseTest#riskLiftsOnlyItsOwnSuspension` (ten instances, the `CHECK` alone). Probes: risk's
  reinstatement calling the administrator's → red; the store's cause condition dropped → red; restored, sha256
  `09f6c9f1…9400d559` and `bf2b936a…561bb8fb`.

**MINOR** (remediation entries - none blocks Phase 14; `BACKLOG.md` §Phase 13 remediation):

- Every other "fix before production use" item - 87, 88, 89/90/91, 84, 85/68, 21, 45/41, 27 - read under the gate: none
  is reachable as a correctness or security defect of the running system (no device producer exists; the report acts'
  answers go to their own actor; no policy is active, so scale, corridors and `PROHIBITED` refuse nothing yet; the rest
  operational). `P13-REM-001`…`-007`.
- **G-4 - item 25's residue**: an administrator suspending an identity already suspended by risk answers `NOT_ACTIVE` and
  the cause stays `RISK`, so risk's later reinstatement could lift it. `P13-REM-008`.
- Every "decide before the first policy activation" item - 1/10/12, 75, 77, 83/72 - adopted as decisions and made
  activation preconditions (`P13-REM-009`…`-012`); 86, 36/66, 92 decided at the activation signature (`P13-REM-013`).
- **G-5 - three stale documents**: `ROADMAP.md`'s current position still read "closing on its exit review … its verdict
  not yet issued"; `BACKLOG.md`'s Phase 13 header read "27 of 28 … `P13-TST-001` `POSTPONED`" after the owner closed it by
  acceptance; `DATA_CLASSIFICATION.md` lacked the gate's new column. Corrected.

## The owner's ratification, as recorded

The owner adopted the exit review's recommendations (2026-10-29, "Accept recommendations") for `PHASE_13_PLAN.md` §1.2
items 1–95, §2.2 C1–C10 and §24 OQ-1…10:

- **Ratified as built:** C1's bands, C2, C3, C4, C6, C7, C8, C9, C10; OQ-1…3, OQ-5…10 (OQ-4 moot); items 5, 11, 14, 16, 17,
  19, 22, 23, 26, 28–31, 33, 37, 39, 40, 42–44, 47, 48, 49, 54, 57, 58, 60–65, 67, 69, 71, 74, 80, 81, 82, 93, 94.
- **Decided before the first policy activation** (activation preconditions, runbook §9.1 step 3): 1/10/12 → a pinned
  EUR conversion; 75 → a compiled per-operation answer and decode-before-release; 77 → a fail-safe `DENY` at (R2a), or
  (R2a) outside the budget with a retryable refusal; 83/72 → `PROHIBITED` refused whatever the version, dead dimensions
  refused at the door; 86, 36/66, 92 → decided at the activation signature.
- **Fixed before production use, in this gate:** 79, 78, 25. **As remediation entries:** 87, 88, 89–91, 84, 85, 68, 21,
  45/41, 27.
- **Deferred, with the owner named:** 3, 4, 6, 7, 8, 9, 13, 15, 18, 24, 32, 34, 35, 38, 46, 50–53, 55, 56, 59, 70, and 76/95
  under PI-004.

## The remediation entries created (`PLANNED`; none blocks Phase 14)

| ID | Horizon | Subject |
|---|---|---|
| `P13-REM-001` | before production use | the device hash keyed before any device producer (87) |
| `P13-REM-002` | before production use | the report acts answer a receipt; the analyst desk counts no filing (88) |
| `P13-REM-003` | before production use | scale checked, the corridor set bounded, the replay body closed (89, 90, 91) |
| `P13-REM-004` | before production use | the prohibition driver's per-item failure handling (84) |
| `P13-REM-005` | before production use | payments' stuck gauge and the held-withdrawal expiry meter (85, 68) |
| `P13-REM-006` | before the first policy activation | a four-eyes lift of `PROHIBITED` (21) |
| `P13-REM-007` | before production use (a real FIU at the latest) | the draft's `WITHDRAWN` exit; the AML approver's evidence door (45/41, 27) |
| `P13-REM-008` | before production use | an administrator's suspension takes over a risk one (G-4) |
| `P13-REM-009` | before the first policy activation | a pinned EUR conversion (1, 10, 12) |
| `P13-REM-010` | before the first policy activation | an unreadable pin answers per operation; decode before release (75) |
| `P13-REM-011` | before the first policy activation | a hot subject's burst cannot outrun velocity (77) |
| `P13-REM-012` | before the first policy activation | `PROHIBITED` refused by the engine; dead dimensions refused (83, 72) |
| `P13-REM-013` | before the first policy activation | the owner's activation decisions: 86, 36/66, 92 |

## Universal criteria and the financial supplement

As the exit review recorded them, re-read for the repairs: criteria 1–6 and 8–12 `PASS`; **criterion 7 `PARTIAL`** - the
standing C5 deviation (the owner's C5, 2026-10-22, for Phase 13;
asked again at this transition). F1–F8 as recorded (Phase 13 posts nothing); the repairs move no money and change no posting.

## Tests, every count from a fresh run

Every count read from the run's own JUnit XML, after the last change to code (2026-10-29):

| Run | Suites | Tests | Failed |
|---|---|---|---|
| `:risk:test --rerun` (hermetic, whole module) | 84 | 570 | 0 |
| `:identity:test --rerun` (hermetic, whole module) | 16 | 139 | 0 |
| `:app:test --rerun`, filtered: `com.finapp.app.risk.*`, `com.finapp.app.administration.*`, `DomainGlossaryTest`, `CredentialReachesNoEmittedSinkTest`, `RoutePermissionRegisterTest`, `OpenApiContractTest`, `PlannedMetersExistTest`, `RiskAlertRulesTest` | 59 | 316 | 0 |
| Database suites the repairs touch (fresh containers): platform `ColumnClassificationTest`, `ApplicationRoleGrantsTest` | 2 | 11 | 0 |
| risk `CaseDatabaseTest`, `RiskActionDatabaseTest`, `AlertDatabaseTest`, `RiskEvaluationDatabaseTest`, `RiskMigrationTest`, `RiskBornOnceArbitersDatabaseTest` | 6 | 68 | 0 |
| app `RiskCaseApiTest`, `RiskReportsApiTest`, `RiskActionApiTest`, `CollectionsCaseDatabaseTest`, `QueueApiTest`, `AlertSuppressionApiTest`, `SuspensionConcurrencyDatabaseTest`, `IdentityAdministrationDatabaseTest`, `SessionRevocationDatabaseTest` | 9 | 55 | 0 |
| `:app:architectureTest --rerun`, once, at the end (`MutationDemonstrationTest` 9, `TestTaxonomyTest` 17, `AdrRegistersAreReconciledTest`, `ArchitectureRulesAreDocumentedTest`, `AuditableActionRegistryTest`, `ErrorCodeRegistryTest`, `OwnershipIsScopedTest`, `NoCustomerSurfaceReadsRiskTest`, `SystemActorCallSitesAreEnumeratedTest` among them) | 35 | 192 | 0 |

**Before green, the database suites found four test sites the repairs had to carry** (no production defect): scenario 6
in `CaseDatabaseTest` closed an alert-holding case alone (now by two); `RiskMigrationTest`'s function register lacked
V019's function; `RiskReportsApiTest`'s fixture used a party id as an account and its own-account helper collided with
a registered party's live customer (now real wallet agreements, the live customer reused). **The probes**: the hermetic
run 5 of 26 red, the database run 2 of 18 red, every one aimed; every file restored byte-identical (sha256 above and in
`MUTATION_TESTING.md` §2). **Not run** (C5): the fleet tiers beyond these suites, previous phases' other suites, the storm.

## What happens next

The coordinator initialises Phase 14 (Part B) and flips the phase pointer; this gate marks nothing of Phase 14 `READY`.
The remediation entries wait for the owner's scheduling. PI-004's reminder: the storms of every later phase are owed after
the project's phases.
