package com.finapp.risk;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.testing.InMemoryDecisionOverrideStore;
import com.finapp.risk.testing.InMemoryRiskActionStore;
import com.finapp.risk.testing.InMemoryRiskAlertStore;
import com.finapp.risk.testing.InMemoryRiskCaseStore;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The override machine and the action machine over fakes for the hermetic suites (`P13-TSK-012`; `P13-TSK-017`):
 * T-eval's own world ({@link EvaluationWorld}) seals real assessments under policies built for the decision a case
 * needs; the real {@link DecisionOverrides} over an {@link InMemoryDecisionOverrideStore}, issuing into the real
 * {@link RiskActions} over an {@link InMemoryRiskActionStore}, whose cases are the real {@link Cases} over an
 * {@link InMemoryRiskCaseStore}; the acting-party and subject-party ports maps; the audit trail and the outbox recorded;
 * and the owners' doors {@link Owners faked as their owners apply them} - a moved row moved once, a key's answer replayed,
 * another key on a moved row {@code ALREADY}, a scripted throw. The database clock is one instant across the stores
 * ({@link #now}, {@link #advance}).
 */
final class OverrideWorld {

    static final Duration LEASE = Duration.ofMinutes(2);

    final EvaluationWorld evaluation = new EvaluationWorld();
    final InMemoryDecisionOverrideStore overrides = new InMemoryDecisionOverrideStore();
    final InMemoryRiskActionStore actionStore = new InMemoryRiskActionStore();
    final InMemoryRiskCaseStore caseStore = new InMemoryRiskCaseStore();
    final InMemoryRiskAlertStore alertStore = new InMemoryRiskAlertStore();
    final List<AuditRecord> audit = new ArrayList<>();
    final List<EventEnvelope> events = new ArrayList<>();
    final List<Map<String, String>> payloads = new ArrayList<>();
    final Map<String, UUID> partyOfActor = new HashMap<>();
    final Map<SignalSubject, UUID> partyOfSubject = new HashMap<>();
    final Owners payments = new Owners();

    final Cases cases;
    final RiskActions actions;
    final DecisionOverrides machine;

    OverrideWorld() {
        OutboxWriter<Connection> outbox = (unitOfWork, envelope, payload, mediaType) -> {
            events.add(envelope);
            payloads.add(EventPayload.fields(payload));
        };
        RiskActingParty acting = (unitOfWork, actor) -> Optional.ofNullable(partyOfActor.get(actor.id()));
        RiskSubjectParties subjects = (unitOfWork, subject) -> Optional.ofNullable(partyOfSubject.get(subject));
        overrides.actionOf = actionStore::ofOverride;
        caseStore.actionStands = actionStore::proposalStands;
        Instant start = Instant.parse("2026-10-25T12:00:00Z");
        overrides.now = start;
        actionStore.now = start;
        caseStore.now = start;
        cases = new Cases(caseStore, alertStore, acting, subjects, (unitOfWork, record) -> audit.add(record), outbox,
                EvaluationWorld.IDS, EvaluationWorld.CLOCK, CaseObserver.NONE);
        actions = new RiskActions(actionStore, cases, caseStore, evaluation.assessmentStore,
                new ActionDispatch(payments, payments, payments, payments), (unitOfWork, record) -> audit.add(record),
                outbox, EvaluationWorld.IDS, EvaluationWorld.CLOCK);
        machine = new DecisionOverrides(overrides, evaluation.assessmentStore,
                new RiskPolicies(evaluation.policyStore), acting, (unitOfWork, record) -> audit.add(record), actions,
                EvaluationWorld.IDS, EvaluationWorld.CLOCK);
    }

    /** The database's clock, one across the stores. */
    Instant now() {
        return actionStore.now;
    }

    /** The database's clock moved {@code by}. */
    void advance(Duration by) {
        Instant moved = actionStore.now.plus(by);
        actionStore.now = moved;
        overrides.now = moved;
        caseStore.now = moved;
    }

    // ------------------------------------------------------------------ people

    /** A risk officer of a fresh party of their own. */
    Actor officer() {
        Actor actor = new Actor("officer-" + UUID.randomUUID(), ActorType.EMPLOYEE);
        partyOfActor.put(actor.id(), UUID.randomUUID());
        return actor;
    }

    /** A risk officer whose party is {@code party}. */
    Actor officerOf(UUID party) {
        Actor actor = new Actor("officer-" + UUID.randomUUID(), ActorType.EMPLOYEE);
        partyOfActor.put(actor.id(), party);
        return actor;
    }

    /**
     * A risk analyst as the platform's session interceptor establishes every identity - {@code CUSTOMER}-typed, its id
     * an identity id - of a fresh party of their own.
     */
    Actor analyst() {
        Actor actor = new Actor(EvaluationWorld.IDS.next().toString(), ActorType.CUSTOMER);
        partyOfActor.put(actor.id(), EvaluationWorld.IDS.next());
        return actor;
    }

    static CorrelationId correlation() {
        return CorrelationId.of("flow-" + UUID.randomUUID());
    }

    /** {@code work} as the platform - T-act's principal, a leg's scope. */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    static <T> T asSystem(Supplier<T> work) {
        try (SecurityContext.Scope ignored = SecurityContext.enterSystem()) {
            return work.get();
        }
    }

    // ------------------------------------------------------------------ sealed assessments

    /**
     * A {@code decision} of {@code operation} sealed for {@code minor} EUR cents of a fresh party, judged by a version
     * whose one score band decides it and whose override threshold is {@code thresholdMinor}.
     */
    RiskAssessment sealed(RiskOperation operation, RiskDecision decision, long minor, long thresholdMinor) {
        return sealed(operation, decision, minor, thresholdMinor, UUID.randomUUID());
    }

    RiskAssessment sealed(RiskOperation operation, RiskDecision decision, long minor, long thresholdMinor, UUID party) {
        evaluation.activate(policy(operation, decision, thresholdMinor));
        RiskEvaluationRequest request = EvaluationWorld.request(operation, EvaluationWorld.eur(minor), party)
                .sourceAccount(UUID.randomUUID()).build();
        RiskEvaluation.Assessed assessed = evaluation.evaluation.evaluate(EvaluationWorld.UOW, request);
        RiskAssessment sealed = evaluation.assessmentStore.read(EvaluationWorld.UOW, assessed.assessmentId())
                .orElseThrow();
        if (sealed.decision() != decision) {
            throw new IllegalStateException("the world sealed " + sealed.decision() + ", not " + decision);
        }
        return sealed;
    }

    /** A fail-safe {@code DENY} of a withdrawal of {@code minor} - the inputs' read failing (C1: DENY at every band). */
    RiskAssessment failSafeDeny(long minor) {
        evaluation.activate(policy(RiskOperation.WITHDRAWAL, RiskDecision.ALLOW, RiskPolicyFixtures.OVERRIDE_THRESHOLD_MINOR));
        evaluation.failInputs.push(EvaluationWorld.broken());
        RiskEvaluation.Assessed assessed = evaluation.evaluation.evaluate(EvaluationWorld.UOW,
                EvaluationWorld.request(RiskOperation.WITHDRAWAL, EvaluationWorld.eur(minor), UUID.randomUUID())
                        .sourceAccount(UUID.randomUUID()).build());
        return evaluation.assessmentStore.read(EvaluationWorld.UOW, assessed.assessmentId()).orElseThrow();
    }

    /** {@code operation}'s v1 fixture with one score band - 0 to 1000 - deciding {@code decision}, and a threshold. */
    static RiskPolicy policy(RiskOperation operation, RiskDecision decision, long thresholdMinor) {
        RiskPolicy v1 = RiskPolicyFixtures.v1(operation);
        List<ScoreBand> bands = List.of(new ScoreBand(0, 1000, decision));
        Optional<Duration> holdValidity = RiskPolicy.namesHoldValidity(operation, v1.rules(), bands)
                ? Optional.of(v1.holdValidity().orElse(RiskPolicy.DEFAULT_HOLD_VALIDITY))
                : Optional.empty();
        return new RiskPolicy(operation, v1.engine(), v1.currency(), v1.rules(), bands, v1.ratingBands(),
                v1.failSafeBands(), v1.challengeAssurance(),
                Optional.of(Money.ofMinorUnits(thresholdMinor, RiskPolicyFixtures.EUR)), holdValidity);
    }

    // ------------------------------------------------------------------ override acts

    DecisionOverride propose(RiskAssessment assessment, OverrideResolution resolution, Actor proposer) {
        return machine.propose(EvaluationWorld.UOW, assessment.id(), resolution, "FALSE_POSITIVE",
                "the customer confirmed the movement by phone", proposer, correlation());
    }

    DecisionOverrides.Decided approve(DecisionOverride override, Actor approver) {
        return machine.approve(EvaluationWorld.UOW, override.assessment(), override.id(), "reviewed the evidence",
                approver, correlation());
    }

    /** An approved override's action, as issued. */
    RiskAction issuedFor(DecisionOverride override) {
        return actionStore.ofOverride(override.id()).orElseThrow();
    }

    // ------------------------------------------------------------------ cases

    /** A {@code kind} case of a fresh party, opened by hand and taken by a fresh analyst; the analyst and the case. */
    Taken partyCase(CaseKind kind) {
        UUID party = EvaluationWorld.IDS.next();
        Actor investigator = analyst();
        RiskCase opened = cases.open(EvaluationWorld.UOW, Cases.OpenRequest.of(kind, SignalSubject.of(SubjectKind.PARTY,
                party)), "a pattern worth a look", investigator, correlation()).riskCase();
        return take(opened, investigator);
    }

    /** A merchant-abuse case of a fresh merchant (its organisation party a fresh one), opened by hand and taken. */
    Taken merchantCase() {
        UUID merchant = EvaluationWorld.IDS.next();
        SignalSubject subject = SignalSubject.of(SubjectKind.MERCHANT, merchant);
        partyOfSubject.put(subject, EvaluationWorld.IDS.next());
        Actor investigator = analyst();
        RiskCase opened = cases.open(EvaluationWorld.UOW, Cases.OpenRequest.of(CaseKind.MERCHANT_ABUSE, subject),
                "refund ratio", investigator, correlation()).riskCase();
        return take(opened, investigator);
    }

    /**
     * A {@code FRAUD} case opened by T-eval's {@code HOLD} of a {@code operation} ({@code WITHDRAWAL} or
     * {@code CARD_PAYMENT}) - the case holding the movement - taken by a fresh analyst; the held assessment too.
     */
    Held heldCase(RiskOperation operation) {
        UUID party = EvaluationWorld.IDS.next();
        RiskAssessment held = sealed(operation, RiskDecision.HOLD, 2_000_00, RiskPolicyFixtures.OVERRIDE_THRESHOLD_MINOR,
                party);
        RiskCase opened = asSystem(() -> cases.openFor(EvaluationWorld.UOW, new CaseOpener.Decided(held.id(),
                operation, held.commandRef(), party, RiskDecision.HOLD, CaseKind.FRAUD, correlation()))).riskCase();
        Actor investigator = analyst();
        return new Held(take(opened, investigator), held);
    }

    /** A case taken, and who took it. */
    record Taken(RiskCase riskCase, Actor investigator) {}

    /** A held movement's case, and its sealed HOLD. */
    record Held(Taken taken, RiskAssessment assessment) {
        UUID movement() {
            return UUID.fromString(assessment.commandRef().value());
        }
    }

    private Taken take(RiskCase opened, Actor investigator) {
        RiskCase taken = cases.take(EvaluationWorld.UOW, opened.id(), opened.revision(), investigator, correlation())
                .riskCase();
        return new Taken(taken, investigator);
    }

    /** The case as it stands now. */
    RiskCase caseOf(RiskCaseId id) {
        return caseStore.find(EvaluationWorld.UOW, id).orElseThrow();
    }

    /** {@code kind} proposed on {@code taken}'s case by its investigator. */
    RiskActions.Proposed propose(Taken taken, ActionKind kind, Optional<UUID> target) {
        RiskCase current = caseOf(taken.riskCase().id());
        return actions.propose(EvaluationWorld.UOW, current.id(), current.revision(), kind.name(), target,
                "the customer's sessions were used from an unknown device", taken.investigator(), correlation());
    }

    /** {@code proposed} approved by {@code approver}. */
    RiskActions.Decided approve(RiskAction proposed, Actor approver) {
        RiskCase current = caseOf(proposed.caseId().orElseThrow());
        return actions.approve(EvaluationWorld.UOW, current.id(), proposed.id(), current.revision(),
                "confirmed with the investigator", approver, correlation());
    }

    // ------------------------------------------------------------------ T-act, as the driver runs it

    /** T-act's three steps for {@code id} as the platform - the claim, the owner's door, the record; empty if unclaimed. */
    Optional<RiskAction> drive(RiskActionId id) {
        return asSystem(() -> actions.claim(EvaluationWorld.UOW, id, LEASE).map(this::driveClaimed));
    }

    /** The schedule's: the next due action's three steps; empty when none is due. */
    Optional<RiskAction> driveNextDue() {
        return asSystem(() -> actions.claimNextDue(EvaluationWorld.UOW, LEASE).map(this::driveClaimed));
    }

    private RiskAction driveClaimed(RiskAction claimed) {
        ActionOutcome outcome = actions.dispatch(claimed);
        return actions.conclude(EvaluationWorld.UOW, claimed, outcome, correlation())
                .orElseGet(() -> actionStore.find(EvaluationWorld.UOW, claimed.id()).orElseThrow());
    }

    /** The audit records of {@code code}. */
    List<AuditRecord> audited(String code) {
        return audit.stream().filter(record -> record.operation().code().equals(code)).toList();
    }

    /** The payloads of the events of {@code type}. */
    List<Map<String, String>> announced(String type) {
        List<Map<String, String>> found = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).eventType().equals(type)) {
                found.add(payloads.get(i));
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ the owners, faked as they apply their doors

    /**
     * The owning domains' keyed doors - payments' four, identity's three, merchant's one: one move per target and door
     * family, a key's answer replayed, another key on a moved target {@code ALREADY}, a scripted throw.
     */
    static final class Owners implements HeldWithdrawalActions, HeldPaymentActions, IdentityActions, MerchantActions {

        /** Every call: {@code door|target|key|reason}. */
        final List<String> calls = new ArrayList<>();
        /** The targets moved, by the door that moved them. */
        final Map<UUID, String> moved = new HashMap<>();
        /** Targets moved by another hand (an expiry), answered ALREADY. */
        final Map<UUID, String> elsewhere = new HashMap<>();
        /** Targets the owner refuses (a lapsed hold, a closed identity), answered REFUSED. */
        final java.util.Set<UUID> lapsed = new java.util.HashSet<>();
        /** A key's recorded answer. */
        final Map<String, ActionOutcome> answers = new HashMap<>();
        /** Scripted failures: each pops one for the next call. */
        final Deque<RuntimeException> failures = new ArrayDeque<>();
        /** The acting principal at each call - the owner's audit actor. */
        final List<Actor> actors = new ArrayList<>();
        /** The decider identity's own doors were given. */
        final List<String> deciders = new ArrayList<>();

        int effects() {
            return moved.size();
        }

        @Override
        public ActionOutcome release(UUID withdrawalId, String idempotencyKey, String reason) {
            return door("release", withdrawalId, idempotencyKey, reason);
        }

        @Override
        public ActionOutcome deny(UUID withdrawalId, String idempotencyKey, String reason) {
            return door("deny", withdrawalId, idempotencyKey, reason);
        }

        @Override
        public ActionOutcome capture(UUID intentId, String idempotencyKey, String reason) {
            return door("capture", intentId, idempotencyKey, reason);
        }

        @Override
        public ActionOutcome voidHold(UUID intentId, String idempotencyKey, String reason) {
            return door("void", intentId, idempotencyKey, reason);
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

        private ActionOutcome door(String door, UUID target, String key, String reason) {
            calls.add(door + "|" + target + "|" + key + "|" + reason);
            actors.add(SecurityContext.current().orElse(null));
            if (!failures.isEmpty()) {
                throw failures.pop();
            }
            ActionOutcome recorded = answers.get(key);
            if (recorded != null) {
                return recorded; // the key's answer replayed - nothing moves twice (INV-IDEM-01)
            }
            ActionOutcome outcome;
            if (lapsed.contains(target)) {
                outcome = ActionOutcome.REFUSED;
            } else if (moved.containsKey(target) || elsewhere.containsKey(target)) {
                outcome = ActionOutcome.ALREADY;
            } else {
                moved.put(target, door);
                outcome = ActionOutcome.DONE;
            }
            answers.put(key, outcome);
            return outcome;
        }
    }
}
