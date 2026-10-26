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
 * <strong>The velocity windows</strong> (`P13-TSK-006`, wave C, {@code V005}): the dimension is its own vocabulary,
 * {@link com.finapp.risk.VelocityDimension} ({@code PARTY}, {@code SOURCE_ACCOUNT}, {@code DESTINATION}, {@code DEVICE},
 * {@code MERCHANT} - the role a subject plays, so a transfer's source and destination are counted apart), retyped through
 * the policy's parameter type ({@code VELOCITY_DIMENSION}; {@code SUBJECT_KIND} dropped), its store and the engine's
 * {@link com.finapp.risk.VelocityKey} (canonical format still 1); {@link com.finapp.risk.VelocityWindows} - T-eval's
 * three steps in the caller's transaction: {@code cut} (R2a, one advisory lock per (dimension, subject), namespace
 * 131, sorted, then {@code clock_timestamp()} - a {@link com.finapp.risk.WindowCut} no caller can build from another
 * clock), {@code read} (the figures before the movement) and {@code increment} (R2, one
 * {@code INSERT … ON CONFLICT DO UPDATE … RETURNING} per window in sorted key order, only for {@code ALLOW} or a
 * satisfied {@code CHALLENGE}) over {@link com.finapp.risk.VelocityWindowStore}; the windows tumbling buckets from
 * {@link com.finapp.risk.WindowStarts}, counted forward only and only on the database clock for every role by
 * {@code V005}'s trigger; and {@link com.finapp.risk.VelocityProof}, which recomputes the provable live windows from
 * {@link com.finapp.risk.VelocityEvidence} (the monitored transfers, {@link com.finapp.risk.JdbcVelocityEvidence}) and
 * reports a difference, never correcting it. Nothing calls them until `-004` wires T-eval and `-023` the proof.
 * <strong>The customer risk profile</strong> (`P13-TSK-007`, wave C, {@code V006}): {@link com.finapp.risk.CustomerRiskProfile}
 * - the head per party, born unassessed and moved only beside a new version - and
 * {@link com.finapp.risk.CustomerRiskProfileVersion}, born once in sequence ({@code PRIMARY KEY (party_id, version)},
 * {@code INSERT}-only), a {@link com.finapp.risk.ProfileCause#RECOMPUTE} version carrying its whole evaluation (the
 * pinned {@code MONITORING} version, the engine, the signal score, the reasons, the sealed inputs and their digest), a
 * {@link com.finapp.risk.ProfileCause#DISPOSITION} version its disposition (`-016`'s act, the only one that sets or lifts
 * {@code PROHIBITED}); {@link com.finapp.risk.CustomerRiskProfileStore} over the schema;
 * {@link com.finapp.risk.ProfileRecompute} (T-mon's profile leg: the pin (R1), the head (R8) claimed
 * {@code SKIP LOCKED} or locked waiting, {@link com.finapp.risk.ProfileInputs} - the party and its screening clearance,
 * the rest {@code ABSENT} until `-020` reads the windows and the movements - evaluated by the engine over the rating bands, a version only when the
 * rating changes, announced {@code risk.CustomerRiskRatingChanged}; a {@code PROHIBITED} party never re-rated; no
 * {@code MONITORING} version, nothing judged); {@link com.finapp.risk.CustomerRiskProfiles} ({@code current} - what
 * credit's seam, version 2 through {@code app}'s {@code RiskCreditRiskSignal}, and `-004`'s {@code PROFILE_RATING} read;
 * an unassessed party is no rating at all, never a default - and {@code history}, the analyst's audited read behind the
 * self-dealing judgement); {@link com.finapp.risk.ProfileHints} (T-sig's permit pull, inside {@code RiskSignals.record})
 * through the party join {@link com.finapp.risk.RiskSubjectParties} ({@code app}'s {@code IdentitySubjectParties}: a party
 * and an identity placed today). {@link com.finapp.risk.RiskActingParty} has its first implementation ({@code app}'s
 * {@code IdentityActingParty}). <strong>The sealed assessment</strong> (`P13-TSK-004`, wave D, {@code V007}):
 * {@link com.finapp.risk.RiskEvaluation} - T-eval, {@code evaluate(uow, RiskEvaluationRequest)}, in the caller's
 * transaction after its locks: a lookup of (operation, {@link com.finapp.risk.CommandRef}) answering a retry with its
 * seal; the pin (R1) behind a savepoint with no statement timeout; behind a second savepoint under 250 ms
 * ({@link com.finapp.risk.EvaluationBudget}, {@link com.finapp.risk.JdbcEvaluationBudget}) the cut (R2a), the reads -
 * the windows the policy reads, the profile, the signals, {@link com.finapp.risk.RiskInputs} - the engine and the count
 * (R2) when the decision permits; a failure undone to its savepoint and answered by the recorded fail-safe with its
 * {@link com.finapp.risk.UnavailableCause} ({@code RiskEngineV1.failSafe}, {@code failSafeUnpinned}, {@code
 * failSafeUnplaced}); the seal (R3) - {@link com.finapp.risk.RiskAssessment} over
 * {@link com.finapp.risk.RiskAssessmentStore} ({@code JdbcRiskAssessmentStore}: the head, the canonical inputs, every
 * rule's hit, the ordered reasons; {@code UNIQUE (operation, command_ref)}, sealed for every writer, a lost race
 * {@code RaceLost}); {@code risk.RiskDecisionMade} and {@code risk.LimitBreached}; {@link com.finapp.risk.CaseOpener}
 * for {@code REVIEW} and {@code HOLD} ({@code NONE} until `-016`); {@link com.finapp.risk.RiskEvaluationObserver} for
 * the meters - and T-advise, {@code advise}, the same judgement peeked, nothing counted or written.
 * {@link com.finapp.risk.RiskAssessments} (the analyst's audited read and replay, self-dealing fail closed) over
 * {@link com.finapp.risk.AssessmentReplay} (the pinned engine over the parsed seal: {@code IDENTICAL} or {@code DIVERGED}
 * with the kinds); {@link com.finapp.risk.VelocityOutcome} on every assessment and
 * {@link com.finapp.risk.AssessmentVelocityEvidence} - the counted assessments, the velocity proof's evidence of record,
 * every dimension provable; {@code decision_override}'s table (its machine `-012`'s, below); a {@code ROUND_AMOUNT} modulus
 * below one minor unit refused by {@code RiskPolicy}. <strong>The first consumer</strong> (`P13-TSK-008`, wave E):
 * transfers' two seams, {@code app}'s {@code RiskTransferLimitCheck} and {@code RiskTransferRiskDecision}, each calling
 * {@code evaluate} for the transfer's id in its execution's transaction after both account locks - the first call
 * seals, the second is answered from the seal - mapped through {@code Assessed.permits()} and the new
 * {@code Assessed.limitDecided()} (a fired amount or velocity rule's own hard decision is the refusing decision); the
 * transfer door's step-up over {@code advise}; {@link com.finapp.risk.SessionAssurance} implemented over identity's
 * session, read live ({@code app}'s {@code IdentitySessionAssurance}), so a {@code CHALLENGE} is satisfiable. The
 * withdrawal, checkout and cross-border seams stay on their permit-all defaults until `-009`…`-011` (credit's seam is
 * risk's since `-007`); production evaluates every transfer and records {@code ALLOW(NO_POLICY_ACTIVE)} until a
 * {@code TRANSFER} version is activated under four eyes (section 1.1) - the profile leg judges nothing until a
 * {@code MONITORING} version is; the monitoring sweep is off in test contexts and ingests, in production, a journal no
 * rule yet reads (`-020`).
 *
 * <p><strong>The decision override</strong> (`P13-TSK-012`, {@code risk V009}): {@link com.finapp.risk.DecisionOverrides}
 * (T-ovr - propose, approve, reject, the officer's reads; C7's four eyes fixed at the proposal by
 * {@code requiresSecondPerson}, failing closed; self-dealing fail closed on every act and read; the sealed assessment
 * never written) over {@link com.finapp.risk.DecisionOverrideStore} ({@code JdbcDecisionOverrideStore}); the
 * {@link com.finapp.risk.OverrideEffect} an approval issues - a held withdrawal released or denied, a held card payment
 * captured or voided, a refusal's {@code RESUBMISSION} that dispatches nothing - issued since `P13-TSK-017` into the
 * action machine through {@link com.finapp.risk.ActionIssuer} (the {@code risk_action} row under the override's own id,
 * so its key stays {@code risk.action:<override id>}); `-012`'s {@code OverrideActions} and its leg are folded away.
 *
 * <p><strong>The born-once alert</strong> (`P13-TSK-015`, {@code risk V010}): {@link com.finapp.risk.Alerts} - the
 * raise ({@code raise} / {@code raiseAll}, S, on the caller's unit of work in T-eval or T-mon): one
 * {@code INSERT … ON CONFLICT ON CONSTRAINT risk_alert_born_once DO NOTHING} per alert, so a replayed event, a
 * reprocessed history or ten instances raise one row ({@code INV-RSK-06}); {@code risk.AlertRaised} only when born; a
 * transaction's alerts born in {@link com.finapp.risk.AlertKey} order and every birth before any fold - the
 * {@link com.finapp.risk.AlertFolding} port (`-016` implements it over its case tables; {@code NONE} wired, every alert
 * {@code OPEN} and queued) and the alert's own attach edge; the four-eyes suppression machine (propose, approve by a
 * second holder, reject - P only, reasons required, self-approval and self-dealing refused and recorded {@code FAILED},
 * the subject's party failing closed) for `-018`'s door. {@link com.finapp.risk.RiskAlert} as stored,
 * {@link com.finapp.risk.NewAlert} (a rule's alert or an operational one), {@link com.finapp.risk.AlertWindow} (a
 * bucket of its kind from a database instant that is the evidence's own), {@link com.finapp.risk.AlertEvidence}
 * (references only, bounded), {@link com.finapp.risk.AlertRule}, {@link com.finapp.risk.AlertStatus},
 * {@link com.finapp.risk.SuppressionReason}, {@link com.finapp.risk.RiskAlertStore} ({@code JdbcRiskAlertStore}) and
 * {@link com.finapp.risk.AlertObserver} (the meters, {@code app}'s {@code RiskAlertMetrics}). The operational kinds are
 * raised by no writer (their alerts are the Prometheus rules on their gauges); the rules raise from `-013` on.
 *
 * <p><strong>Account-takeover detection</strong> (`P13-TSK-013`, no migration): {@link com.finapp.risk.AtoSequence} - the
 * sequence judged from a verdict's sealed hits (an anchor, {@code RECENT_CREDENTIAL_CHANGE} or {@code RECENT_RECOVERY},
 * fired; no judged continuation, {@code NEW_PAYEE} or {@code NEW_DEVICE}, refuting it; the movement a transfer, withdrawal
 * or cross-border payment - the anchor alone for {@code MONITORING}); {@link com.finapp.risk.AccountTakeover} - the
 * anchors' reading over a set of identities and the {@code ACCOUNT_TAKEOVER} alert, keyed (the party, the anchor rule, the
 * anchor signal's own day) so the synchronous and the retrospective raise of one sequence are one row; the port
 * {@link com.finapp.risk.AlertRaiser} ({@code alerts::raiseAll}) through which T-eval raises after the seal behind a
 * savepoint of its own (a failure undone alone, reported to {@link com.finapp.risk.RiskEvaluationObserver#alertsUnraised})
 * and {@link com.finapp.risk.ProfileRecompute} raises under the head's lock; the port
 * {@link com.finapp.risk.RiskPartyIdentities} (the party's identities, {@code app}'s {@code IdentitySubjectParties}) the
 * profile leg reads the anchors through; the anchors admitted for {@code MONITORING}; the payee's and the device's
 * novelty read from their earliest counted window ({@link com.finapp.risk.VelocityWindows#firstCounted}). No case and no
 * action yet - `-016` folds, `-017` proposes.
 *
 * <p><strong>Payment, merchant and BNPL abuse</strong> (`P13-TSK-014`, {@code risk V011}): three signal kinds from the
 * events that exist - {@code REFUND_COMPLETED} and {@code CHARGEBACK_RECEIVED} (payments' events name the refund or the
 * dispute and the intent; the payer and the merchant are attributed from risk's own sealed assessment of that payment,
 * {@link com.finapp.risk.RiskAssessmentStore#judgedPayment}, the merchant riding as {@code merchant_subject_ref}; a payment risk
 * never judged records none) and {@code BNPL_APPLICATION_SUBMITTED} (the applicant); {@code REFUND_RATIO} and
 * {@code CHARGEBACK_RATIO} widened to the payments (scope only) with a count floor ({@code threshold_count}) - the
 * merchant's in a payment version, judged in T-eval over its signals in the window's bucket and its {@code MERCHANT}
 * window, the party's in the {@code MONITORING} version, judged in the profile leg; {@code BNPL_MULTI_APPLICATION} the
 * sweep's alone, feeding the rating and so credit's signal; {@link com.finapp.risk.AbuseRatios} (the arithmetic, pure)
 * and {@link com.finapp.risk.PaymentAbuse} (the readings and the alerts: {@code MERCHANT_ABUSE} on the merchant from
 * T-eval, {@code FRAUD} on the party from the profile leg, each born once per (subject, rule, the window's bucket) in the
 * existing {@code raiseAll}); {@link com.finapp.risk.VelocityDimension#SESSION} - the confirming identity session,
 * sealed on the assessment ({@code session_id}) - for "a session confirming many checkouts". Nothing of it reaches a
 * customer or merchant surface, an event payload beyond ids and codes, a log or a metric tag.
 *
 * <p><strong>The case</strong> (`P13-TSK-016`, {@code risk V012}): {@link com.finapp.risk.Cases} - T-case over
 * {@link com.finapp.risk.RiskCaseStore} ({@code JdbcRiskCaseStore}): {@link com.finapp.risk.RiskCase} (one open per
 * (subject, kind) by the partial unique {@code risk_case_one_open}; {@code OPEN → IN_INVESTIGATION → ESCALATED →
 * DISPOSED}, a one-person close from {@code OPEN} too; never re-opened - a new case names its predecessor; a
 * {@code revision} every step moves by one, each with its {@link com.finapp.risk.CaseEvent} row; the disposition slot),
 * the append-only {@link com.finapp.risk.CaseAction} ({@link com.finapp.risk.CaseActionKind}), the
 * {@link com.finapp.risk.CaseLevel} and {@link com.finapp.risk.CaseQueue} of an escalation, the
 * {@link com.finapp.risk.SlaDeadlines} per kind stamped on the database clock, and {@link com.finapp.risk.DispositionRules}
 * (four eyes for {@code CONFIRMED_*}, {@code REFERRED} and a prohibiting disposition, each on a taken case). Every
 * person's act takes the case row {@code FOR UPDATE} (R4), judges self-dealing on the subject's accountable party
 * ({@link com.finapp.risk.RiskSubjectParties#accountablePartyOf} - a merchant's organisation placed since this task,
 * fail closed) and compares the revision the analyst's screen read; the proposer's approval is refused and
 * {@code risk_case_disposition_four_eyes} holds every writer alone. The two seams it implements: `-015`'s
 * {@link com.finapp.risk.AlertFolding} ({@code Cases::fold} - (R4) the subject's open case of the kind or a case opened
 * for the alert, then (R5) the alert's attach through the store) and `-004`'s {@link com.finapp.risk.CaseOpener}
 * ({@code Cases::openFor} - T-eval's {@code REVIEW} or {@code HOLD} opens or joins the party's case of the kind its sealed
 * hits call for, {@code ATO} when the account-takeover anchors show, so an anchor rule's hold and its alert are one
 * case); the drain of the alerts born {@code OPEN} before it ({@link com.finapp.risk.Cases#drain}, `app`'s case leg). A
 * prohibiting disposition's {@code PROHIBITED} is never written by {@code Cases}:
 * {@link com.finapp.risk.ProfileRecompute#applyDisposition} applies it after the case commits, in a transaction of its
 * own (R8 alone - never R8 under R4), one version per disposition. {@link com.finapp.risk.CaseObserver} is the meters' port. The downstream actions are
 * `-017`'s; the queues and the case read `-018`'s.
 *
 * <p><strong>The downstream action</strong> (`P13-TSK-017`, {@code risk V013}; ADR-0112): {@link com.finapp.risk.RiskActions}
 * over {@link com.finapp.risk.RiskActionStore} ({@code JdbcRiskActionStore}) - the record in risk of an effect the owning
 * domain performs ({@code INV-RSK-02}). {@link com.finapp.risk.RiskAction} runs {@link com.finapp.risk.ActionStatus}'s
 * machine ({@code PROPOSED → APPROVED → DISPATCHED → DONE | FAILED}, {@code REJECTED} beside; {@code FAILED} re-proposable,
 * never re-dispatched by itself), its id the proposing {@code ACTION_PROPOSED} investigator action's or the issuing
 * override's, so the key {@code risk.action:<id>} is fixed at birth. T-case: propose, approve, reject under the case row
 * (R4) then the action row (R6), {@link com.finapp.risk.ActionRules} judging the kind against the case's kind and subject
 * and the target against the case's own party, merchant or a held movement it holds; four eyes in the domain and in
 * {@code risk_action_four_eyes}; a disposition waits while an action stands {@code PROPOSED}. T-ovr:
 * {@link com.finapp.risk.ActionIssuer} - an approved override's action born {@code APPROVED}. T-act: the claim ONE
 * statement {@code FOR UPDATE SKIP LOCKED} on a due permit, committed; {@link com.finapp.risk.ActionDispatch} - the kind
 * to its port ({@link com.finapp.risk.IdentityActions}, {@link com.finapp.risk.MerchantActions},
 * {@link com.finapp.risk.HeldWithdrawalActions}, {@link com.finapp.risk.HeldPaymentActions}) on no transaction, the
 * caller entering the platform's actor; the outcome recorded once by a conditional edge. {@code SUSPEND_ACCOUNT},
 * {@code REINSTATE_ACCOUNT} and {@code BLOCK_BENEFICIARY} have no owning command and are unavailable
 * ({@link com.finapp.risk.ActionKind#available()}); {@code RESCREEN_PARTY} is the rescreening sweep's (`-022`).
 *
 * <p><strong>The rescreening</strong> (`P13-TSK-022`, {@code risk V017}; section 12.10 of the plan): T-list -
 * {@link com.finapp.risk.ScreeningListVersions} over {@link com.finapp.risk.ScreeningListStore}
 * ({@code JdbcScreeningListStore}): a {@link com.finapp.risk.ScreeningListVersion} recorded by a person, activated by a
 * second ({@code RECORDED → ACTIVE → RETIRED}, the predecessor retired beside it), its enqueue left due; the leg -
 * {@link com.finapp.risk.RescreeningRun}: the enqueue in bounded batches over {@link com.finapp.risk.ActiveParties} (one
 * {@link com.finapp.risk.RescreeningPermit} per party and version, {@link com.finapp.risk.RescreeningPermitStore}), then
 * each permit's three steps - the claim committed, kyc asked through {@link com.finapp.risk.PartyRescreening} on no risk
 * transaction, the conclusion at the claimed attempt: kyc's screening recorded as {@code SCREENING_CLEARED} or
 * {@code SCREENING_HIT} only when decided at or after the activation (risk's own signal kinds,
 * {@link com.finapp.risk.SignalKind.Origin#RISK}), a hit alerted once and folded; otherwise deferred and failed at the
 * bound. {@link com.finapp.risk.RescreeningObserver} is the meters' port.
 */
package com.finapp.risk;
