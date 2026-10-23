/**
 * Risk, fraud, AML and case management: the judgement, the evidence and the case - and none of the money
 * (`P13-TSK-001`; ADR-0107...ADR-0112, {@code PHASE_13_PLAN.md}).
 *
 * <p><strong>What belongs here.</strong> One module, three capabilities kept distinct by timing and obligation
 * (MODULE_ARCHITECTURE.md M3): <em>fraud decisioning</em>, synchronous, inside the caller's money transaction and
 * latency-bounded - the risk policy version with its rules as data ({@link com.finapp.risk.RuleKind}), the deterministic
 * engine ({@link com.finapp.risk.RiskEngine}), the sealed assessment with its score and its decision
 * ({@link com.finapp.risk.RiskDecision}), the override under four eyes, the durable velocity windows
 * ({@link com.finapp.risk.WindowKind}) and the customer risk profile ({@link com.finapp.risk.RiskRating});
 * <em>AML monitoring</em>, asynchronous and retrospective - the born-once signal, the monitored transaction read from the
 * ledger's journal through a port, the monitoring rules and the born-once alert ({@link com.finapp.risk.AlertKind});
 * and <em>case management</em>, which serves both and never decides money - the case ({@link com.finapp.risk.CaseKind},
 * {@link com.finapp.risk.CaseStatus}), the investigator's acts, the disposition ({@link com.finapp.risk.Disposition}),
 * the downstream action's record ({@link com.finapp.risk.ActionKind}), the screening list version and the regulatory
 * report. This module owns every one of those and nothing co-owned.
 *
 * <p><strong>What never belongs here.</strong> Money. Risk never posts, never holds, never releases a hold of its own and
 * never writes another module's row ({@code INV-RSK-02}): a decision changes a money movement only because the owning
 * domain consulted a seam in its own transaction and committed its own outcome, and an action is a keyed command
 * through the owning domain's existing door, recorded on both sides. No table here holds a balance. The Screening
 * Result is {@code kyc}'s; the transfer, withdrawal, payment, cross-border payment, BNPL application and loan keep their
 * lifecycles in their modules; the targets of actions are identity's, accounts', merchant's and crossborder's;
 * collections operations are a later context's (owner decision C3), write-off and provisioning Phase 14's. Nothing of
 * an alert, a case, a report or an investigation reaches a customer or merchant surface, a log, an event payload or a
 * metric tag ({@code INV-RSK-08}, {@code NoCustomerSurfaceReadsRiskTest}).
 *
 * <p><strong>Edges.</strong> Exactly {@code platform} and {@code sharedkernel} ({@code RiskModuleIsolationTest}) - no
 * ledger edge, no business module - and no module depends on this one. The seams the consumers declare are implemented
 * in {@code app} as adapters over this module's evaluation API; every other domain is a port this module declares and
 * {@code app} implements: {@link com.finapp.risk.RiskActingParty}, {@link com.finapp.risk.SessionAssurance},
 * {@link com.finapp.risk.MonitoredLedger}, {@link com.finapp.risk.RegulatoryFilingProvider},
 * {@link com.finapp.risk.ScreeningClearances}, {@link com.finapp.risk.PartyRescreening} and the action ports
 * ({@link com.finapp.risk.IdentityActions}, {@link com.finapp.risk.AccountActions},
 * {@link com.finapp.risk.MerchantActions}, {@link com.finapp.risk.BeneficiaryActions},
 * {@link com.finapp.risk.HeldWithdrawalActions}) - declared with the boundary, each implemented by the task that first
 * consumes it, each free to be reshaped by it while it has no implementation. No port returns a row of another module.
 *
 * <p><strong>Trust.</strong> Three disjoint desks, one live role per identity ({@code INV-AUD-03}, {@code RoleNameTest}):
 * the risk analyst investigates and approves another analyst's disposition, action or suppression; the risk officer
 * administers policy versions and overrides; the AML officer approves and files regulatory reports. Four eyes is two
 * holders of one permission, never two roles, and never one's own act ({@code risk.SelfApprovalRefused}); no holder acts
 * on a subject of their own party ({@code risk.SelfDealingRefused}, fail closed).
 *
 * <p><strong>What exists so far.</strong> The boundary, the migrator-owned schema floor ({@code V001}, no table), the
 * closed vocabularies ({@link com.finapp.risk.RiskOperation} with the decisions each admits, {@code RuleKind} with each
 * kind's operations and parameter schema, the alert, case, disposition, action, subject, window, rating and assurance
 * vocabularies, the engine identity), the aggregates' typed identifiers, the error codes and audit actions declared so
 * far, the span names and the ports above (`P13-TSK-001`); the risk policy version as data (`P13-TSK-002`,
 * {@code V002}): {@link com.finapp.risk.RiskPolicy} - the immutable content judged whole at construction (rules of
 * closed kinds with {@link com.finapp.risk.RuleParameters} held to each kind's schema, score, rating and fail-safe
 * bands, the challenge assurance, the override threshold and the hold validity), {@link com.finapp.risk.RiskPolicyVersion}
 * with its machine ({@link com.finapp.risk.RiskPolicyVersionStatus}), {@link com.finapp.risk.RiskPolicyAdministration}
 * (propose, activate under four eyes with the predecessor retired in the same transaction, reject - advisory namespace
 * 13 first, T-pol) and {@link com.finapp.risk.RiskPolicies} (the reads and <em>the pin</em> at (R1): the ACTIVE row
 * {@code FOR SHARE}, re-read under the shared namespace when the first read is empty, so it never answers "neither"),
 * the event {@code risk.RiskPolicyActivated}, and the test-only policy v1 fixtures (C8); and <strong>the born-once
 * signal</strong> (`P13-TSK-005`, {@code V003}): {@link com.finapp.risk.SignalKind} - thirteen kinds, one per consumed
 * event that exists, each with its subject and its typed, bounded attribute schema - {@link com.finapp.risk.RiskSignal},
 * {@link com.finapp.risk.SignalSubject} (a device only ever by {@link com.finapp.risk.DeviceHash}, OQ-4),
 * {@link com.finapp.risk.RiskSignalStore} ({@code INSERT ... ON CONFLICT (source_event_id) DO NOTHING}, four bounded
 * reads), {@link com.finapp.risk.RiskSignals} (T-sig: record and announce {@code risk.RiskSignalRecorded} when born),
 * {@link com.finapp.risk.SignalReadings} (the signal half of `-004`'s inputs) and {@link com.finapp.risk.MonitoringHints}
 * (the completions as hints; `NONE` until `-019`); {@code SubjectKind} gained {@code IDENTITY} and
 * {@code CREDIT_AGREEMENT}, the aggregates the events actually name; the handlers live in {@code app}
 * ({@code RiskSignalIntake}, consumer {@code risk.signals}), as does {@code ScreeningClearances}' implementation over
 * kyc's case checks. <strong>The monitoring intake</strong> (`P13-TSK-019`, {@code V004}): the journal has no sequence
 * column, so {@link com.finapp.risk.MonitoredLedger} (implemented in {@code app} over the ledger's journal and chart
 * stores, {@code SELECT} only) reads by the entry's UUIDv7 id as a keyset under a settle horizon on the database clock,
 * the id's millisecond prefix the "sequence"; {@link com.finapp.risk.MonitoredKind} is the posting-key namespace
 * register (forty-four namespaces into twenty-three kinds, {@code REVERSAL} and {@code ADJUSTMENT} by entry type,
 * {@code UNCLASSIFIED} recorded and gauged, never skipped); {@link com.finapp.risk.MonitoringClassification} the pure
 * classification into {@link com.finapp.risk.MonitoredTransaction} ({@code INSERT}-only, born once by
 * {@code UNIQUE (journal_entry_id)}); {@link com.finapp.risk.MonitoringWatermark} the claim, not a machine, over
 * {@link com.finapp.risk.MonitoringWatermarkStore} (the claim {@code FOR UPDATE SKIP LOCKED} on the database clock -
 * the Phase 13 lock order's R7 - the waiting lock, the move, the hint) and
 * {@link com.finapp.risk.MonitoredTransactionStore} ({@code ON CONFLICT DO NOTHING});
 * {@link com.finapp.risk.MonitoringIntake} (T-mon's intake leg: one batch = one transaction, a partial batch never
 * advances), {@link com.finapp.risk.MonitoringReprocessing} (the audited reset {@code risk.ReprocessingOrdered}; the
 * reason required and screened; a point ahead of the watermark refused {@code risk.ReprocessingNotBehind}) and
 * {@link com.finapp.risk.WatermarkMonitoringHints} (`-005`'s hint seam closed: a completion pulls the journal's permit
 * to now in the handler's own transaction); the schedule {@code RiskMonitoringSchedule}, the officer's door
 * {@code POST /v1/operator/risk/monitoring/reprocessing} and the gauges live in {@code app}. <strong>The engine</strong>
 * (`P13-TSK-003`, wave C; no row): {@link com.finapp.risk.RiskEngineV1} - a pure, deterministic function from a
 * {@code RiskPolicy} and the sealed {@link com.finapp.risk.EvaluationInputs} (every reading a {@link com.finapp.risk.Reading}
 * {@code PRESENT} or {@code ABSENT}: the amount, the subjects by reference, the velocity figures per
 * {@link com.finapp.risk.VelocityKey}, the payee's and device's {@link com.finapp.risk.FirstSeen}, the session assurance,
 * the authentication failures per window, the credential change's and recovery's {@link com.finapp.risk.LastSeen}, the
 * account age, the KYC standing, the screening hit, the profile rating as a code, the merchant's suspension, the
 * corridor, and the sweep's {@link com.finapp.risk.MonitoringReadings}) to a {@link com.finapp.risk.Verdict}: every rule's
 * {@link com.finapp.risk.RuleHit} in declared order with its {@link com.finapp.risk.RuleOutcome} - an absent reading never
 * fires and says so - the {@link com.finapp.risk.Score} (the fired weights summed, clamped to [0, 1000]), the decision as
 * the strictest of the score band's and every fired hard decision (a band never softens a hard rule; with nothing fired
 * the band at 0 decides), the {@link com.finapp.risk.ReasonCode}s (closed, one per fired rule, in order; a member per
 * {@code RuleKind}, {@code NO_POLICY_ACTIVE}, {@code RISK_UNAVAILABLE}, {@code CHALLENGE_REQUIRED}), and for the
 * {@code MONITORING} version the rating from its rating bands instead of a decision; one
 * {@link com.finapp.risk.RuleEvaluation} per kind, the two enums held equal as the class loads;
 * {@code RiskEngineV1.failSafe} - the policy's band for the amount under {@code RISK_UNAVAILABLE}, the compiled
 * {@code ALLOW} under {@code NO_POLICY_ACTIVE} with none; {@link com.finapp.risk.EvaluationFailed} for what refuses
 * (another operation, another engine, another currency, a rule that throws) - never a partial verdict; and
 * {@link com.finapp.risk.CanonicalEvaluation}, format 1 - the byte-exact text of the inputs and of a verdict, its SHA-256
 * what `-004` seals and replays against, the inputs form parsed back strictly; proven byte-identical in a second JVM
 * ({@code RiskEngineDigestTest}). The engine reads nothing: `-004`'s T-eval fills the inputs and seals them.
 * <strong>The customer risk profile</strong> (`P13-TSK-007`, wave C, {@code V006}): {@link com.finapp.risk.CustomerRiskProfile}
 * - the head per party, born unassessed and moved only beside a new version - and
 * {@link com.finapp.risk.CustomerRiskProfileVersion}, born once in sequence ({@code PRIMARY KEY (party_id, version)},
 * {@code INSERT}-only), a {@link com.finapp.risk.ProfileCause#RECOMPUTE} version carrying its whole evaluation (the
 * pinned {@code MONITORING} version, the engine, the signal score, the reasons, the sealed inputs and their digest), a
 * {@link com.finapp.risk.ProfileCause#DISPOSITION} version its disposition (`-016`'s act, the only one that sets or lifts
 * {@code PROHIBITED}); {@link com.finapp.risk.CustomerRiskProfileStore} over the schema;
 * {@link com.finapp.risk.ProfileRecompute} (T-mon's profile leg: the pin (R1), the head (R8) claimed
 * {@code SKIP LOCKED} or locked waiting, {@link com.finapp.risk.ProfileInputs} - the party and its screening clearance,
 * the rest {@code ABSENT} until `-006`/`-020` - evaluated by the engine over the rating bands, a version only when the
 * rating changes, announced {@code risk.CustomerRiskRatingChanged}; a {@code PROHIBITED} party never re-rated; no
 * {@code MONITORING} version, nothing judged); {@link com.finapp.risk.CustomerRiskProfiles} ({@code current} - what
 * credit's seam, version 2 through {@code app}'s {@code RiskCreditRiskSignal}, and `-004`'s {@code PROFILE_RATING} read;
 * an unassessed party is no rating at all, never a default - and {@code history}, the analyst's audited read behind the
 * self-dealing judgement); {@link com.finapp.risk.ProfileHints} (T-sig's permit pull, inside {@code RiskSignals.record})
 * through the party join {@link com.finapp.risk.RiskSubjectParties} ({@code app}'s {@code IdentitySubjectParties}: a party
 * and an identity placed today). {@link com.finapp.risk.RiskActingParty} has its first implementation ({@code app}'s
 * {@code IdentityActingParty}). No evaluation yet in production: nothing calls the engine for a movement until `-004`
 * lands, production evaluates nothing until a policy version is activated under four eyes (section 1.1) - the profile
 * leg judges nothing until a {@code MONITORING} version is - and until `-008`…`-011` land, the movement seams stay wired
 * to their permit-all defaults (credit's seam is risk's since `-007`); the monitoring sweep is off in test contexts and
 * ingests, in production, a journal no rule yet reads (`-020`).
 */
package com.finapp.risk;
