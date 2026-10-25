package com.finapp.app.risk;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecord;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.ActionDispatch;
import com.finapp.risk.ActionOutcome;
import com.finapp.risk.CaseKind;
import com.finapp.risk.CaseObserver;
import com.finapp.risk.CaseOpener;
import com.finapp.risk.Cases;
import com.finapp.risk.CommandRef;
import com.finapp.risk.DecisionOverride;
import com.finapp.risk.DecisionOverrideId;
import com.finapp.risk.DecisionOverrides;
import com.finapp.risk.HeldPaymentActions;
import com.finapp.risk.HeldWithdrawalActions;
import com.finapp.risk.IdentityActions;
import com.finapp.risk.MerchantActions;
import com.finapp.risk.MonitoredParties;
import com.finapp.risk.RegulatoryFilingProvider;
import com.finapp.risk.RegulatoryReport;
import com.finapp.risk.RegulatoryReportId;
import com.finapp.risk.RegulatoryReports;
import com.finapp.risk.RiskAction;
import com.finapp.risk.RiskActionId;
import com.finapp.risk.RiskActions;
import com.finapp.risk.RiskAssessment;
import com.finapp.risk.RiskCase;
import com.finapp.risk.RiskCaseId;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskEvaluation;
import com.finapp.risk.RiskEvaluationRequest;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicies;
import com.finapp.risk.RiskPolicy;
import com.finapp.risk.ScoreBand;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import com.finapp.risk.testing.InMemoryDecisionOverrideStore;
import com.finapp.risk.testing.InMemoryRegulatoryReportStore;
import com.finapp.risk.testing.InMemoryRiskActionStore;
import com.finapp.risk.testing.InMemoryRiskAlertStore;
import com.finapp.risk.testing.InMemoryRiskCaseStore;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The action machine's composition over fakes for {@code app}'s hermetic suites (`P13-TSK-017`): the real
 * {@link RiskActions}, {@link Cases} and {@link DecisionOverrides} over in-memory stores; the real
 * {@link RiskActionDriver} and {@link RiskActionDesk} over a fake unit of work that counts its depth (a door called inside
 * one shows {@code depth=1}) and rolls the stores, the platform's claims and the audit trail back on a throw; and the
 * owners' doors faked as the owners apply them - payments' held doors <strong>refusing any caller payments refuses</strong>
 * (a {@code CUSTOMER}-typed session: `-009`/`-010`'s rule, the routed finding), identity's and merchant's recording the
 * acting principal their own audit would name. The analysts are {@code CUSTOMER}-typed, as the session interceptor builds
 * every identity.
 */
final class RiskActionWorld {

    static final Duration LEASE = Duration.ofMinutes(2);

    final RiskEvaluationWorld evaluation = new RiskEvaluationWorld();
    final InMemoryRiskCaseStore caseStore = new InMemoryRiskCaseStore();
    final InMemoryRiskAlertStore alertStore = new InMemoryRiskAlertStore();
    final InMemoryRiskActionStore actionStore = new InMemoryRiskActionStore();
    final InMemoryDecisionOverrideStore overrideStore = new InMemoryDecisionOverrideStore();
    /** `P13-TSK-021`: the regulatory report's rows, the party's accounts, the authority behind the port. */
    final InMemoryRegulatoryReportStore reportStore = new InMemoryRegulatoryReportStore();
    final Map<UUID, Set<UUID>> accountsOf = new HashMap<>();
    final Authority authority = new Authority();
    final List<AuditRecord> audit = new ArrayList<>();
    final List<String> events = new ArrayList<>();
    final Map<String, UUID> partyOf = new HashMap<>();
    final Map<SignalSubject, UUID> partyOfSubject = new HashMap<>();
    final Map<IdempotencyKey, IdempotencyRecord> claims = new LinkedHashMap<>();
    final Owners owners = new Owners();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    /** Throws scripted for the next unit of work, before its work runs - a failing claim. */
    final Deque<RuntimeException> failTransactions = new ArrayDeque<>();
    int depth;

    final RiskTransactions transactions = new RiskTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            if (depth > 0) {
                return work.apply(RiskEvaluationWorld.UNTOUCHABLE);
            }
            if (!failTransactions.isEmpty()) {
                throw failTransactions.pop();
            }
            var actionRows = actionStore.snapshot();
            var overrideRows = overrideStore.snapshot();
            var reportRows = reportStore.snapshot();
            Map<IdempotencyKey, IdempotencyRecord> keys = new LinkedHashMap<>(claims);
            int audited = audit.size();
            depth++;
            try {
                return work.apply(RiskEvaluationWorld.UNTOUCHABLE);
            } catch (RuntimeException rolledBack) {
                // Every refusal the suites drive is judged before a case step is written; the case store needs none.
                actionStore.restore(actionRows);
                overrideStore.restore(overrideRows);
                reportStore.restore(reportRows);
                claims.clear();
                claims.putAll(keys);
                audit.subList(audited, audit.size()).clear();
                throw rolledBack;
            } finally {
                depth--;
            }
        }

        @Override
        public <R> R inSnapshot(Function<Connection, R> work) {
            return inTransaction(work);
        }
    };

    final OutboxWriter<Connection> outbox =
            (unitOfWork, envelope, payload, mediaType) -> events.add(envelope.eventType());
    final Cases cases;
    final RiskActions actions;
    final DecisionOverrides overrides;
    final RiskActionMetrics metrics = new RiskActionMetrics(registry);
    final RiskActionDriver driver;
    final IdempotentExecutor executor = new IdempotentExecutor(new Claims(), RiskEvaluationWorld.CLOCK,
            Duration.ofDays(1), Duration.ofMinutes(1));
    final RiskActionDesk desk;
    final RegulatoryReportMetrics reportMetrics = new RegulatoryReportMetrics(registry);
    final RegulatoryReports reports;
    final RegulatoryFilingDriver filings;
    final RiskReportDesk reportDesk;

    RiskActionWorld() {
        this(5);
    }

    RiskActionWorld(int perTick) {
        overrideStore.actionOf = actionStore::ofOverride;
        caseStore.actionStands = actionStore::proposalStands;
        cases = new Cases(caseStore, alertStore, (unitOfWork, actor) -> Optional.ofNullable(partyOf.get(actor.id())),
                (unitOfWork, subject) -> Optional.ofNullable(partyOfSubject.get(subject)),
                (unitOfWork, record) -> audit.add(record), outbox, RiskEvaluationWorld.IDS, RiskEvaluationWorld.CLOCK,
                CaseObserver.NONE);
        actions = new RiskActions(actionStore, cases, caseStore, evaluation.assessmentStore,
                new ActionDispatch(owners, owners, owners, owners), (unitOfWork, record) -> audit.add(record), outbox,
                RiskEvaluationWorld.IDS, RiskEvaluationWorld.CLOCK);
        overrides = new DecisionOverrides(overrideStore, evaluation.assessmentStore,
                new RiskPolicies(evaluation.policyStore),
                (unitOfWork, actor) -> Optional.ofNullable(partyOf.get(actor.id())),
                (unitOfWork, record) -> audit.add(record), actions, RiskEvaluationWorld.IDS, RiskEvaluationWorld.CLOCK);
        driver = new RiskActionDriver(actions, transactions, metrics, RiskFlowScope.NONE, LEASE, perTick);
        desk = new RiskActionDesk(actions, cases, driver, executor, transactions, RiskFlowScope.NONE);
        reports = new RegulatoryReports(reportStore, cases, caseStore, alertStore, new MonitoredParties() {
            @Override
            public Optional<UUID> partyOfAccount(Connection unitOfWork, UUID customerAccountId) {
                return Optional.empty();
            }

            @Override
            public Accounts accountsOf(Connection unitOfWork, UUID partyId) {
                List<UUID> held = List.copyOf(accountsOf.getOrDefault(partyId, Set.of()));
                return Accounts.of(held, held.stream().map(account -> Instant.parse("2026-01-01T00:00:00Z")).toList());
            }
        }, (unitOfWork, actor) -> Optional.ofNullable(partyOf.get(actor.id())), authority,
                (unitOfWork, record) -> audit.add(record), outbox, RiskEvaluationWorld.IDS, RiskEvaluationWorld.CLOCK,
                reportMetrics);
        filings = new RegulatoryFilingDriver(reports, transactions, reportMetrics, RiskFlowScope.NONE, LEASE,
                Duration.ofMinutes(15), perTick);
        reportDesk = new RiskReportDesk(reports, filings, executor, transactions, RiskFlowScope.NONE);
    }

    // ------------------------------------------------------------------ regulatory reports (`P13-TSK-021`)

    /**
     * An AML case of a fresh party with two accounts, opened by hand and taken by one analyst, disposed
     * {@code CONFIRMED_SUSPICIOUS} with a second - its id.
     */
    String confirmedSuspicious() {
        Taken taken = partyCase(CaseKind.AML);
        UUID party = UUID.fromString(taken.riskCase().subject().ref());
        accountsOf.put(party, new java.util.LinkedHashSet<>(List.of(RiskEvaluationWorld.IDS.next(),
                RiskEvaluationWorld.IDS.next())));
        Cases.Decided proposed = as(taken.investigator(), () -> transactions.inTransaction(uow ->
                cases.proposeDisposition(uow, taken.riskCase().id(), taken.riskCase().revision(), "CONFIRMED_SUSPICIOUS",
                        false, "the pattern is confirmed", taken.investigator(), correlation())));
        Actor approver = analyst();
        as(approver, () -> transactions.inTransaction(uow -> cases.approveDisposition(uow, taken.riskCase().id(),
                proposed.dispositionId(), proposed.riskCase().revision(), "agreed", approver, correlation())));
        return taken.id();
    }

    /** The report as it stands now. */
    RegulatoryReport reportOf(String reportId) {
        return reportStore.find(RiskEvaluationWorld.UNTOUCHABLE, RegulatoryReportId.of(UUID.fromString(reportId)))
                .orElseThrow();
    }

    /**
     * The authority behind the port: the simulated FIU of {@code app}, its knob settable - and, for a suite, a scripted
     * throw after the call reached it (a lost answer). Each call records the risk transactions open around it and the
     * acting principal, so a suite proves the call is made on no transaction and as the platform.
     */
    final class Authority implements RegulatoryFilingProvider {
        SimulatedFilingProvider.Behaviour behaviour = SimulatedFilingProvider.Behaviour.ACKNOWLEDGE;
        boolean loseNextAnswer;
        final List<String> calls = new ArrayList<>();

        @Override
        public Optional<Declaration> declared(String jurisdiction) {
            return simulated().declared(jurisdiction);
        }

        @Override
        public Receipt file(Request request) {
            calls.add("file|" + request.filingReference() + "|depth=" + depth + "|" + SecurityContext.require().type());
            Receipt answered = simulated().file(request);
            if (loseNextAnswer) {
                loseNextAnswer = false;
                throw new IllegalStateException("the answer was lost");
            }
            return answered;
        }

        @Override
        public Receipt inquire(String filingReference) {
            calls.add("inquire|" + filingReference + "|depth=" + depth + "|" + SecurityContext.require().type());
            return simulated().inquire(filingReference);
        }

        private SimulatedFilingProvider simulated() {
            return new SimulatedFilingProvider(behaviour, "FINAPP-EU-REF-0001");
        }
    }

    // ------------------------------------------------------------------ people

    /** A risk analyst as the session interceptor establishes every identity: {@code CUSTOMER}-typed, an identity id. */
    Actor analyst() {
        Actor actor = new Actor(RiskEvaluationWorld.IDS.next().toString(), ActorType.CUSTOMER);
        partyOf.put(actor.id(), RiskEvaluationWorld.IDS.next());
        return actor;
    }

    /** {@code work} as {@code actor}, inside a correlation - a door's request. */
    @SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
    static <T> T as(Actor actor, Supplier<T> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("flow-" + UUID.randomUUID())))) {
            return work.get();
        }
    }

    // ------------------------------------------------------------------ cases

    /** A case taken, and who took it. */
    record Taken(RiskCase riskCase, Actor investigator) {
        String id() {
            return riskCase.id().value().toString();
        }
    }

    /** A {@code kind} case of a fresh party, opened by hand and taken by a fresh analyst. */
    Taken partyCase(CaseKind kind) {
        Actor investigator = analyst();
        UUID party = RiskEvaluationWorld.IDS.next();
        RiskCase opened = as(investigator, () -> transactions.inTransaction(uow -> cases.open(uow,
                Cases.OpenRequest.of(kind, SignalSubject.of(SubjectKind.PARTY, party)), "a pattern", investigator,
                correlation()).riskCase()));
        return take(opened, investigator);
    }

    /** A {@code FRAUD} case opened by T-eval's sealed {@code HOLD} of a withdrawal, taken by a fresh analyst. */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    Held heldWithdrawalCase() {
        UUID party = RiskEvaluationWorld.IDS.next();
        RiskAssessment held = sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, 2_000_00, party);
        RiskCase opened;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            opened = transactions.inTransaction(uow -> cases.openFor(uow, new CaseOpener.Decided(held.id(),
                    RiskOperation.WITHDRAWAL, held.commandRef(), party, RiskDecision.HOLD, CaseKind.FRAUD,
                    CorrelationId.of("flow-held")))).riskCase();
        }
        return new Held(take(opened, analyst()), held);
    }

    /** A held movement's case and its sealed HOLD. */
    record Held(Taken taken, RiskAssessment assessment) {
        UUID movement() {
            return UUID.fromString(assessment.commandRef().value());
        }
    }

    private Taken take(RiskCase opened, Actor investigator) {
        RiskCase taken = as(investigator, () -> transactions.inTransaction(uow -> cases.take(uow, opened.id(),
                opened.revision(), investigator, correlation()).riskCase()));
        return new Taken(taken, investigator);
    }

    /** The case as it stands now. */
    RiskCase caseOf(String caseId) {
        return caseStore.find(RiskEvaluationWorld.UNTOUCHABLE, RiskCaseId.of(UUID.fromString(caseId))).orElseThrow();
    }

    /** The action as it stands now. */
    RiskAction actionOf(String actionId) {
        return actionStore.find(RiskEvaluationWorld.UNTOUCHABLE, RiskActionId.of(UUID.fromString(actionId)))
                .orElseThrow();
    }

    /** {@code kind} proposed through the desk by the case's investigator. */
    RiskActionDesk.RiskActionView propose(Taken taken, String kind, String target) {
        return as(taken.investigator(), () -> desk.propose("propose-" + UUID.randomUUID(), taken.id(),
                new RiskActionController.RiskActionProposalRequest(caseOf(taken.id()).revision(), kind, target,
                        "the account was taken over")));
    }

    /** {@code proposed} approved through the desk by {@code approver}. */
    RiskActionDesk.RiskActionView approve(RiskActionDesk.RiskActionView proposed, Actor approver, String key) {
        return as(approver, () -> desk.approve(key, proposed.caseId(), proposed.actionId(),
                new RiskCaseController.RiskCaseDecisionRequest(caseOf(proposed.caseId()).revision(), "confirmed")));
    }

    // ------------------------------------------------------------------ overrides

    /** A HOLD of a withdrawal below the threshold, overridden PROCEED by one officer and approved by another. */
    DecisionOverride approvedOverride() {
        RiskAssessment held = sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, 250_00, UUID.randomUUID());
        Actor proposer = analyst();
        DecisionOverride proposed = as(proposer, () -> transactions.inTransaction(uow -> overrides.propose(uow,
                held.id(), com.finapp.risk.OverrideResolution.PROCEED, "FALSE_POSITIVE", "verified", proposer,
                correlation())));
        Actor approver = analyst();
        return as(approver, () -> transactions.inTransaction(uow -> overrides.approve(uow, held.id(), proposed.id(),
                "four eyes", approver, correlation()).override()));
    }

    /** A {@code decision} of {@code operation} sealed by T-eval under a version whose one score band decides it. */
    RiskAssessment sealed(RiskOperation operation, RiskDecision decision, long minor, UUID party) {
        RiskPolicy v1 = RiskPolicyFixtures.v1(operation);
        List<ScoreBand> bands = List.of(new ScoreBand(0, 1000, decision));
        evaluation.activate(new RiskPolicy(operation, v1.engine(), v1.currency(), v1.rules(), bands, v1.ratingBands(),
                v1.failSafeBands(), v1.challengeAssurance(),
                Optional.of(Money.ofMinorUnits(RiskPolicyFixtures.OVERRIDE_THRESHOLD_MINOR, RiskPolicyFixtures.EUR)),
                RiskPolicy.namesHoldValidity(operation, v1.rules(), bands)
                        ? Optional.of(RiskPolicy.DEFAULT_HOLD_VALIDITY) : Optional.empty()));
        RiskEvaluation.Assessed assessed = evaluation.evaluation.evaluate(RiskEvaluationWorld.UNTOUCHABLE,
                RiskEvaluationRequest.builder(operation, CommandRef.of(RiskEvaluationWorld.IDS.next()),
                        RiskEvaluationWorld.eur(minor), party, CorrelationId.of("flow"), CausationId.of("cause"))
                        .sourceAccount(RiskEvaluationWorld.IDS.next()).build());
        return evaluation.assessmentStore.read(RiskEvaluationWorld.UNTOUCHABLE, assessed.assessmentId()).orElseThrow();
    }

    static CorrelationId correlation() {
        return CorrelationId.of("flow-" + UUID.randomUUID());
    }

    /** The audit records of {@code code}. */
    List<AuditRecord> audited(String code) {
        return audit.stream().filter(record -> record.operation().code().equals(code)).toList();
    }

    /** A counter's count. */
    double count(String name, String tag, String value) {
        return registry.get(name).tag(tag, value).counter().count();
    }

    static RiskActionId actionId(DecisionOverrideId override) {
        return RiskActionId.ofOverride(override);
    }

    // ------------------------------------------------------------------ the owners

    /**
     * The owners' doors as their owners apply them, each call recording the risk transactions open around it and the
     * acting principal: payments' four held doors refuse a caller payments refuses (anyone but the platform or an
     * employee); a key's answer replayed; one move per target; a scripted throw.
     */
    final class Owners implements HeldWithdrawalActions, HeldPaymentActions, IdentityActions, MerchantActions {
        final List<String> calls = new ArrayList<>();
        final Deque<RuntimeException> failures = new ArrayDeque<>();
        final Map<String, ActionOutcome> answers = new HashMap<>();
        final Map<UUID, ActionOutcome> scripted = new HashMap<>();
        final List<String> deciders = new ArrayList<>();
        int effects;
        /** Run as an owner answers - a suite scripting what happens to the transaction after it. */
        Runnable afterAnswer = () -> {};

        @Override
        public ActionOutcome release(UUID withdrawalId, String idempotencyKey, String reason) {
            return payments("release", withdrawalId, idempotencyKey, reason);
        }

        @Override
        public ActionOutcome deny(UUID withdrawalId, String idempotencyKey, String reason) {
            return payments("deny", withdrawalId, idempotencyKey, reason);
        }

        @Override
        public ActionOutcome capture(UUID intentId, String idempotencyKey, String reason) {
            return payments("capture", intentId, idempotencyKey, reason);
        }

        @Override
        public ActionOutcome voidHold(UUID intentId, String idempotencyKey, String reason) {
            return payments("void", intentId, idempotencyKey, reason);
        }

        @Override
        public ActionOutcome suspend(UUID partyId, String decidedBy, String idempotencyKey, String reason) {
            deciders.add(decidedBy);
            return door("suspend-identity", partyId, idempotencyKey, reason);
        }

        @Override
        public ActionOutcome reinstate(UUID partyId, String decidedBy, String idempotencyKey, String reason) {
            deciders.add(decidedBy);
            return door("reinstate-identity", partyId, idempotencyKey, reason);
        }

        @Override
        public ActionOutcome revokeSessions(UUID partyId, String idempotencyKey, String reason) {
            return door("revoke-sessions", partyId, idempotencyKey, reason);
        }

        @Override
        public ActionOutcome suspend(UUID merchantId, String idempotencyKey, String reason) {
            return door("suspend-merchant", merchantId, idempotencyKey, reason);
        }

        private ActionOutcome payments(String door, UUID target, String key, String reason) {
            ActorType acting = SecurityContext.require().type();
            if (acting != ActorType.SYSTEM && acting != ActorType.EMPLOYEE) {
                calls.add(door + "|" + target + "|" + key + "|" + reason + "|depth=" + depth + "|" + acting);
                // Payments' held doors admit the platform or an employee - never a CUSTOMER-typed session.
                throw new IllegalStateException("payments refuses a " + acting + " caller at a held door");
            }
            return door(door, target, key, reason);
        }

        private ActionOutcome door(String door, UUID target, String key, String reason) {
            calls.add(door + "|" + target + "|" + key + "|" + reason + "|depth=" + depth + "|"
                    + SecurityContext.require().type());
            if (!failures.isEmpty()) {
                throw failures.pop();
            }
            afterAnswer.run();
            return answers.computeIfAbsent(key, k -> {
                ActionOutcome outcome = scripted.getOrDefault(target, ActionOutcome.DONE);
                if (outcome == ActionOutcome.DONE) {
                    effects++;
                }
                return outcome;
            });
        }
    }

    /** The platform's claim store, in memory - a claim held, completed once. */
    private final class Claims implements IdempotencyRecordStore<Connection> {

        @Override
        public ClaimOutcome claim(Connection unitOfWork, IdempotencyKey key, RequestFingerprint fingerprint,
                CorrelationId correlationId, Instant now, Instant expiresAt, Duration lease) {
            if (claims.containsKey(key)) {
                return ClaimOutcome.ALREADY_CLAIMED;
            }
            claims.put(key, new IdempotencyRecord(key, fingerprint, IdempotencyState.IN_PROGRESS,
                    StoredResponse.empty(), correlationId, now, expiresAt));
            return ClaimOutcome.CLAIMED;
        }

        @Override
        public Optional<IdempotencyRecord> find(Connection unitOfWork, IdempotencyKey key) {
            return Optional.ofNullable(claims.get(key));
        }

        @Override
        public boolean complete(Connection unitOfWork, IdempotencyKey key, IdempotencyState terminalState,
                StoredResponse response, Instant completedAt) {
            IdempotencyRecord held = claims.get(key);
            if (held == null || held.state() != IdempotencyState.IN_PROGRESS) {
                return false;
            }
            claims.put(key, new IdempotencyRecord(key, held.fingerprint(), terminalState, response,
                    held.correlationId(), held.createdAt(), held.expiresAt()));
            return true;
        }

        @Override
        public boolean reclaimIfLeaseExpired(Connection unitOfWork, IdempotencyKey key, CorrelationId correlationId,
                Instant now, Instant expiresAt, Duration lease) {
            return false;
        }
    }
}
