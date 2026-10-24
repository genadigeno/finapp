package com.finapp.risk;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.risk.testing.InMemoryDecisionOverrideStore;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The override machine over fakes for the hermetic suites (`P13-TSK-012`): T-eval's own world ({@link EvaluationWorld})
 * seals real assessments under policies built for the decision a case needs; the real {@link DecisionOverrides} and
 * {@link OverrideActions} over an {@link InMemoryDecisionOverrideStore}; the acting-party port a map; the audit trail
 * and the outbox recorded; and payments' two keyed doors {@link Payments faked as payments applies them} - a held row
 * moved once, a key's answer replayed, another key on a moved row {@code ALREADY}, a scripted throw.
 */
final class OverrideWorld {

    static final Duration LEASE = Duration.ofMinutes(2);

    final EvaluationWorld evaluation = new EvaluationWorld();
    final InMemoryDecisionOverrideStore overrides = new InMemoryDecisionOverrideStore();
    final List<AuditRecord> audit = new ArrayList<>();
    final List<EventEnvelope> events = new ArrayList<>();
    final List<Map<String, String>> payloads = new ArrayList<>();
    final Map<String, UUID> partyOfActor = new HashMap<>();
    final Payments payments = new Payments();

    final DecisionOverrides machine;
    final OverrideActions actions;

    OverrideWorld() {
        OutboxWriter<Connection> outbox = (unitOfWork, envelope, payload, mediaType) -> {
            events.add(envelope);
            payloads.add(EventPayload.fields(payload));
        };
        machine = new DecisionOverrides(overrides, evaluation.assessmentStore,
                new RiskPolicies(evaluation.policyStore),
                (unitOfWork, actor) -> Optional.ofNullable(partyOfActor.get(actor.id())),
                (unitOfWork, record) -> audit.add(record), outbox, EvaluationWorld.IDS, EvaluationWorld.CLOCK);
        actions = new OverrideActions(overrides, payments, payments, (unitOfWork, record) -> audit.add(record), outbox,
                EvaluationWorld.IDS, EvaluationWorld.CLOCK);
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

    static CorrelationId correlation() {
        return CorrelationId.of("flow-" + UUID.randomUUID());
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

    // ------------------------------------------------------------------ acts

    DecisionOverride propose(RiskAssessment assessment, OverrideResolution resolution, Actor proposer) {
        return machine.propose(EvaluationWorld.UOW, assessment.id(), resolution, "FALSE_POSITIVE",
                "the customer confirmed the movement by phone", proposer, correlation());
    }

    DecisionOverrides.Decided approve(DecisionOverride override, Actor approver) {
        return machine.approve(EvaluationWorld.UOW, override.assessment(), override.id(), "reviewed the evidence",
                approver, correlation(), LEASE);
    }

    /** The audit records of {@code code}. */
    List<AuditRecord> audited(String code) {
        return audit.stream().filter(record -> record.operation().code().equals(code)).toList();
    }

    // ------------------------------------------------------------------ payments, faked as payments applies its doors

    /** Payments' keyed doors on held rows: one move per row, a key's answer replayed, a scripted throw. */
    static final class Payments implements HeldWithdrawalActions, HeldPaymentActions {

        /** Every call: {@code door|target|key|reason}. */
        final List<String> calls = new ArrayList<>();
        /** The rows moved out of their hold, by the door that moved them. */
        final Map<UUID, String> moved = new HashMap<>();
        /** Rows payments no longer holds by another hand (an expiry), answered ALREADY. */
        final Map<UUID, String> elsewhere = new HashMap<>();
        /** Rows whose validity lapsed, answered REFUSED. */
        final java.util.Set<UUID> lapsed = new java.util.HashSet<>();
        /** A key's recorded answer. */
        final Map<String, ActionOutcome> answers = new HashMap<>();
        /** Scripted failures: each pops one for the next call. */
        final Deque<RuntimeException> failures = new ArrayDeque<>();

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

        private ActionOutcome door(String door, UUID target, String key, String reason) {
            calls.add(door + "|" + target + "|" + key + "|" + reason);
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
