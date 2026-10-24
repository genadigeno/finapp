package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.platform.audit.AuditOutcome;
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
import com.finapp.risk.ActionOutcome;
import com.finapp.risk.CommandRef;
import com.finapp.risk.DecisionOverride;
import com.finapp.risk.DecisionOverrideId;
import com.finapp.risk.DecisionOverrides;
import com.finapp.risk.HeldPaymentActions;
import com.finapp.risk.HeldWithdrawalActions;
import com.finapp.risk.OverrideActions;
import com.finapp.risk.RiskAssessment;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskEvaluation;
import com.finapp.risk.RiskEvaluationRequest;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicies;
import com.finapp.risk.RiskPolicy;
import com.finapp.risk.ScoreBand;
import com.finapp.risk.testing.InMemoryDecisionOverrideStore;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
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
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The override doors, hermetic (`P13-TSK-012`; {@code PHASE_13_PLAN.md} sections 9, 11): every route
 * {@code RISK_OVERRIDE}, the approval {@code MULTI_FACTOR}, every act keyed; the approval's action dispatched through
 * payments' door <strong>after</strong> its transaction commits - with no transaction open - and re-driven by the key's
 * replay when the first dispatch threw, one effect; the act audited; the refusals on the error contract - the
 * proposer's approval {@code 403 risk.SelfApprovalRefused} and the officer's own party {@code 403
 * risk.SelfDealingRefused}, each recorded {@code FAILED} in a transaction of its own, an {@code ALLOW} {@code 409
 * risk.ActionNotProposable}, a decided override {@code 409 risk.OverrideStale}, a blank reason {@code 422
 * risk.ReasonRequired}, an unknown or malformed id {@code 404 risk.NotFound}. The fake transaction rolls a failed act
 * back (the override store, the claims and the audit trail); over HTTP the doors are {@code RiskOverrideApiTest}'s
 * (written, owed - C5).
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the override doors: RISK_OVERRIDE, MULTI_FACTOR approval, keyed acts, the action after the commit"
        + " (P13-TSK-012)")
class RiskOverrideDeskTest {

    private static final long THRESHOLD = RiskPolicyFixtures.OVERRIDE_THRESHOLD_MINOR;

    private final RiskEvaluationWorld world = new RiskEvaluationWorld();
    private final InMemoryDecisionOverrideStore store = new InMemoryDecisionOverrideStore();
    private final List<AuditRecord> audit = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final Map<String, UUID> partyOf = new HashMap<>();
    private final Map<IdempotencyKey, IdempotencyRecord> claims = new LinkedHashMap<>();
    private final Payments payments = new Payments();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private int depth;

    private final RiskTransactions transactions = new RiskTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            Map<DecisionOverrideId, DecisionOverride> overrides = store.snapshot();
            Map<IdempotencyKey, IdempotencyRecord> keys = new LinkedHashMap<>(claims);
            int audited = audit.size();
            depth++;
            try {
                return work.apply(RiskEvaluationWorld.UNTOUCHABLE);
            } catch (RuntimeException rolledBack) {
                store.restore(overrides);
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

    private final OutboxWriter<Connection> outbox =
            (unitOfWork, envelope, payload, mediaType) -> events.add(envelope.eventType());
    private final DecisionOverrides machine = new DecisionOverrides(store, world.assessmentStore,
            new RiskPolicies(world.policyStore), (unitOfWork, actor) -> Optional.ofNullable(partyOf.get(actor.id())),
            (unitOfWork, record) -> audit.add(record), outbox, RiskEvaluationWorld.IDS, RiskEvaluationWorld.CLOCK);
    private final RiskOverrideMetrics metrics = new RiskOverrideMetrics(registry);
    private final OverrideActionDriver driver = new OverrideActionDriver(new OverrideActions(store, payments, payments,
            (unitOfWork, record) -> audit.add(record), outbox, RiskEvaluationWorld.IDS, RiskEvaluationWorld.CLOCK),
            transactions, metrics, RiskFlowScope.NONE, Duration.ofMinutes(2), 5);
    private final RiskOverrideDesk desk = new RiskOverrideDesk(machine, driver,
            new IdempotentExecutor(new Claims(), RiskEvaluationWorld.CLOCK, Duration.ofDays(1), Duration.ofMinutes(1)),
            transactions, RiskFlowScope.NONE, metrics);

    @Test
    @DisplayName("every route carries exactly RISK_OVERRIDE; the approval asks MULTI_FACTOR; every act is keyed and the"
            + " reads are not")
    void everyRouteIsTheOfficers() throws NoSuchMethodException {
        Method read = RiskOverrideController.class.getMethod("readRiskOverrides", String.class);
        Method readOne = RiskOverrideController.class.getMethod("readRiskOverride", String.class, String.class);
        Method propose = RiskOverrideController.class.getMethod("proposeRiskOverride", String.class, String.class,
                RiskOverrideController.RiskOverrideProposalRequest.class);
        Method approve = RiskOverrideController.class.getMethod("approveRiskOverride", String.class, String.class,
                String.class, RiskOverrideController.RiskOverrideDecisionRequest.class);
        Method reject = RiskOverrideController.class.getMethod("rejectRiskOverride", String.class, String.class,
                String.class, RiskOverrideController.RiskOverrideDecisionRequest.class);
        for (Method route : List.of(read, readOne, propose, approve, reject)) {
            assertThat(route.getAnnotation(RequiresPermission.class).value()).as(route.getName())
                    .isEqualTo(PermissionName.RISK_OVERRIDE);
        }
        assertThat(approve.getAnnotation(RequiresAssurance.class).value()).isEqualTo(AssuranceLevel.MULTI_FACTOR);
        for (Method act : List.of(propose, approve, reject)) {
            assertThat(act.getAnnotation(RequiresIdempotencyKey.class)).as(act.getName()).isNotNull();
        }
        for (Method notStepped : List.of(read, readOne, propose, reject)) {
            assertThat(notStepped.getAnnotation(RequiresAssurance.class)).as(notStepped.getName()).isNull();
        }
        assertThat(read.getAnnotation(RequiresIdempotencyKey.class)).isNull();
    }

    @Test
    @DisplayName("proposed, then approved by a second officer: the release is dispatched once through payments' door"
            + " AFTER the approval committed - no transaction open - under risk.action:<id>, its DONE in the answer;"
            + " the acts audited and counted")
    void theReleaseIsDispatchedAfterTheCommit() {
        RiskAssessment held = sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, THRESHOLD);
        RiskOverrideDesk.RiskOverrideView proposed = as(officer(), () -> desk.propose("key-1", id(held),
                new RiskOverrideController.RiskOverrideProposalRequest("FALSE_POSITIVE", "verified by phone", null)));
        assertThat(proposed.status()).isEqualTo("PROPOSED");
        assertThat(proposed.effect()).isEqualTo("RELEASE_HELD_WITHDRAWAL");
        assertThat(proposed.targetKind()).isEqualTo("WITHDRAWAL");
        assertThat(proposed.targetId()).isEqualTo(held.commandRef().value());
        assertThat(proposed.requiresSecondPerson()).isTrue();
        assertThat(proposed.reason()).isEqualTo("verified by phone");

        RiskOverrideDesk.RiskOverrideView approved = as(officer(), () -> desk.approve("key-2", id(held),
                proposed.overrideId(), "second pair of eyes"));
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.action()).isEqualTo("RELEASE_HELD_WITHDRAWAL");
        assertThat(approved.actionOutcome()).isEqualTo("DONE");
        assertThat(approved.actionDueAt()).isNull();
        assertThat(payments.calls).containsExactly("release|" + held.commandRef().value() + "|risk.action:"
                + proposed.overrideId() + "|FALSE_POSITIVE|depth=0");
        assertThat(audit).extracting(record -> record.operation().code()).containsExactly("risk.OverrideProposed",
                "risk.OverrideApproved", "risk.ActionConcluded");
        assertThat(events).containsExactly("risk.RiskActionRequested", "risk.RiskActionCompleted");
        assertThat(count(RiskOverrideMetrics.OVERRIDE, "outcome", "proposed")).isEqualTo(1);
        assertThat(count(RiskOverrideMetrics.OVERRIDE, "outcome", "approved")).isEqualTo(1);
        assertThat(registry.get(RiskOverrideMetrics.ACTION).tag("kind", "RELEASE_HELD_WITHDRAWAL")
                .tag("outcome", "done").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the first dispatch throws: the approval stands with no outcome, action.failed counted; the approver's"
            + " retry under the same key replays the approval - no second risk.OverrideApproved - and re-drives the"
            + " same key to DONE: one effect")
    void aRetryReplaysAndReDrives() {
        RiskAssessment held = sealed(RiskOperation.CARD_PAYMENT, RiskDecision.HOLD, 100_00);
        Actor proposer = officer();
        String overrideId = as(proposer, () -> desk.propose("p", id(held),
                new RiskOverrideController.RiskOverrideProposalRequest("CUSTOMER_VERIFIED", "verified", "PROCEED")))
                .overrideId();
        payments.failures.push(new IllegalStateException("an authorisation still in flight"));
        Actor approver = officer();
        RiskOverrideDesk.RiskOverrideView first = as(approver, () -> desk.approve("a", id(held), overrideId, "ok"));
        assertThat(first.status()).isEqualTo("APPROVED");
        assertThat(first.actionOutcome()).isNull();
        assertThat(first.actionDueAt()).isNotNull();
        assertThat(registry.get(RiskOverrideMetrics.ACTION_FAILED).tag("kind", "CAPTURE_HELD_PAYMENT").counter()
                .count()).isEqualTo(1);

        RiskOverrideDesk.RiskOverrideView again = as(approver, () -> desk.approve("a", id(held), overrideId, "ok"));
        assertThat(again.actionOutcome()).isEqualTo("DONE");
        assertThat(payments.calls).hasSize(2).allSatisfy(call -> assertThat(call)
                .startsWith("capture|" + held.commandRef().value() + "|risk.action:" + overrideId + "|").endsWith(
                        "|depth=0"));
        assertThat(payments.effects).isEqualTo(1);
        assertThat(audit).filteredOn(record -> record.operation().code().equals("risk.OverrideApproved")).hasSize(1);
        assertThat(count(RiskOverrideMetrics.OVERRIDE, "outcome", "approved")).as("the replay counts nothing").isEqualTo(1);
    }

    @Test
    @DisplayName("the re-drive leg: an approval whose dispatch threw is due once its lease passes; the leg claims it,"
            + " dispatches the same key with no transaction open and records DONE as the platform under the override's"
            + " correlation - once; the next tick finds nothing due")
    void theLegReDrivesAsThePlatform() {
        RiskAssessment held = sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, 100_00);
        String overrideId = as(officer(), () -> desk.propose("p", id(held),
                new RiskOverrideController.RiskOverrideProposalRequest("FALSE_POSITIVE", "verified", "PROCEED")))
                .overrideId();
        payments.failures.push(new IllegalStateException("the push rail timed out"));
        assertThat(as(officer(), () -> desk.approve("a", id(held), overrideId, "ok")).actionOutcome()).isNull();
        Actor platform = new Actor("risk-monitoring", ActorType.SYSTEM);
        assertThat(as(platform, driver::driveDue)).as("within the lease the permit is not due").isZero();

        store.now = store.now.plus(Duration.ofMinutes(3));
        assertThat(as(platform, driver::driveDue)).isEqualTo(1);
        assertThat(payments.calls).hasSize(2).last().asString()
                .isEqualTo("release|" + held.commandRef().value() + "|risk.action:" + overrideId
                        + "|FALSE_POSITIVE|depth=0");
        assertThat(audited("risk.ActionConcluded")).singleElement().satisfies(record -> {
            assertThat(record.actor().type()).isEqualTo(ActorType.SYSTEM);
            assertThat(record.changeSummary().orElseThrow()).contains("outcome=DONE", "attempts=2");
        });
        store.now = store.now.plus(Duration.ofMinutes(10));
        assertThat(as(platform, driver::driveDue)).as("concluded: nothing due again").isZero();
        assertThat(payments.effects).isEqualTo(1);
    }

    @Test
    @DisplayName("the refusals on the error contract: the proposer's approval 403 SelfApprovalRefused and the own"
            + " party 403 SelfDealingRefused, each recorded FAILED and surviving the act's rollback; an ALLOW 409"
            + " ActionNotProposable; a decided override 409 OverrideStale; a blank reason 422; unknown and malformed"
            + " ids 404")
    void theRefusalsOnTheErrorContract() {
        RiskAssessment held = sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, THRESHOLD);
        Actor proposer = officer();
        String overrideId = as(proposer, () -> desk.propose("p", id(held),
                new RiskOverrideController.RiskOverrideProposalRequest("FALSE_POSITIVE", "verified", null)))
                .overrideId();

        ApiException self = refusal(proposer, () -> desk.approve("s", id(held), overrideId, "mine"));
        assertThat(self.errorCode()).isEqualTo(RiskErrorCode.SELF_APPROVAL_REFUSED);
        assertThat(audited("risk.SelfApprovalRefused")).singleElement()
                .satisfies(record -> assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED));
        assertThat(store.all()).singleElement().satisfies(row -> assertThat(row.status().name()).isEqualTo("PROPOSED"));
        assertThat(count(RiskOverrideMetrics.OVERRIDE, "outcome", "refused")).isEqualTo(1);

        Actor insider = officer();
        UUID own = UUID.randomUUID();
        partyOf.put(insider.id(), own);
        RiskAssessment ownHeld = sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, 100_00, own);
        ApiException dealing = refusal(insider, () -> desk.read(id(ownHeld)));
        assertThat(dealing.errorCode()).isEqualTo(RiskErrorCode.SELF_DEALING_REFUSED);
        assertThat(audited("risk.SelfDealingRefused")).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.changeSummary().orElseThrow()).doesNotContain(own.toString());
        });

        RiskAssessment allowed = sealed(RiskOperation.TRANSFER, RiskDecision.ALLOW, 100_00);
        assertThat(refusal(officer(), () -> desk.propose("x", id(allowed),
                new RiskOverrideController.RiskOverrideProposalRequest("FALSE_POSITIVE", "x", null))).errorCode())
                .isEqualTo(RiskErrorCode.ACTION_NOT_PROPOSABLE);

        as(officer(), () -> desk.approve("y", id(held), overrideId, "approved"));
        assertThat(refusal(officer(), () -> desk.reject("z", id(held), overrideId, "too late")).errorCode())
                .isEqualTo(RiskErrorCode.OVERRIDE_STALE);
        RiskAssessment review = sealed(RiskOperation.TRANSFER, RiskDecision.REVIEW, 100_00);
        assertThat(refusal(officer(), () -> desk.propose("b", id(review),
                new RiskOverrideController.RiskOverrideProposalRequest("FALSE_POSITIVE", " ", null))).errorCode())
                .isEqualTo(RiskErrorCode.REASON_REQUIRED);
        for (Supplier<?> unknown : List.<Supplier<?>>of(() -> desk.read("not-an-id"),
                () -> desk.read(RiskEvaluationWorld.IDS.next().toString()),
                () -> desk.readOne(id(held), RiskEvaluationWorld.IDS.next().toString()),
                () -> desk.approve("c", id(review), overrideId, "the override is another assessment's"))) {
            assertThat(refusal(officer(), unknown).errorCode()).isEqualTo(RiskErrorCode.NOT_FOUND);
        }
    }

    @Test
    @DisplayName("the reads serve the overridden assessment whole under RISK_OVERRIDE with its overrides - never a"
            + " person - each recorded risk.AssessmentRead; a rejection issues nothing")
    void theReadsAndARejection() {
        RiskAssessment review = sealed(RiskOperation.TRANSFER, RiskDecision.REVIEW, 100_00);
        Actor officer = officer();
        String overrideId = as(officer, () -> desk.propose("p", id(review),
                new RiskOverrideController.RiskOverrideProposalRequest("ACTIVITY_EXPLAINED", "explained", null)))
                .overrideId();
        RiskOverrideDesk.RiskOverrideView rejected = as(officer, () -> desk.reject("r", id(review), overrideId,
                "withdrawn"));
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.action()).isNull();
        assertThat(payments.calls).isEmpty();

        RiskOverrideDesk.RiskOverriddenView served = as(officer(), () -> desk.read(id(review)));
        assertThat(served.assessment().assessmentId()).isEqualTo(id(review));
        assertThat(served.assessment().decision()).isEqualTo("REVIEW");
        assertThat(served.overrides()).singleElement().satisfies(view -> {
            assertThat(view.effect()).isEqualTo("RESUBMISSION");
            assertThat(view.decisionReason()).isEqualTo("withdrawn");
        });
        assertThat(RiskOverrideDesk.RiskOverrideView.class.getRecordComponents())
                .noneMatch(component -> component.getName().contains("By"));
        assertThat(as(officer(), () -> desk.readOne(id(review), overrideId)).overrides()).hasSize(1);
        assertThat(audited("risk.AssessmentRead")).hasSize(2).allSatisfy(record -> assertThat(
                record.changeSummary().orElseThrow()).contains("door=override"));
    }

    // ------------------------------------------------------------------ fixtures

    private RiskAssessment sealed(RiskOperation operation, RiskDecision decision, long minor) {
        return sealed(operation, decision, minor, UUID.randomUUID());
    }

    /** A {@code decision} of {@code operation} sealed by T-eval under a version whose one score band decides it. */
    private RiskAssessment sealed(RiskOperation operation, RiskDecision decision, long minor, UUID party) {
        RiskPolicy v1 = RiskPolicyFixtures.v1(operation);
        List<ScoreBand> bands = List.of(new ScoreBand(0, 1000, decision));
        world.activate(new RiskPolicy(operation, v1.engine(), v1.currency(), v1.rules(), bands, v1.ratingBands(),
                v1.failSafeBands(), v1.challengeAssurance(),
                Optional.of(Money.ofMinorUnits(THRESHOLD, RiskPolicyFixtures.EUR)),
                RiskPolicy.namesHoldValidity(operation, v1.rules(), bands)
                        ? Optional.of(RiskPolicy.DEFAULT_HOLD_VALIDITY) : Optional.empty()));
        RiskEvaluation.Assessed assessed = world.evaluation.evaluate(RiskEvaluationWorld.UNTOUCHABLE,
                RiskEvaluationRequest.builder(operation, CommandRef.of(RiskEvaluationWorld.IDS.next()),
                        RiskEvaluationWorld.eur(minor), party, CorrelationId.of("flow"), CausationId.of("cause"))
                        .sourceAccount(RiskEvaluationWorld.IDS.next()).build());
        RiskAssessment sealed = world.assessmentStore.read(RiskEvaluationWorld.UNTOUCHABLE, assessed.assessmentId())
                .orElseThrow();
        assertThat(sealed.decision()).isEqualTo(decision);
        return sealed;
    }

    private static String id(RiskAssessment assessment) {
        return assessment.id().value().toString();
    }

    private Actor officer() {
        Actor actor = new Actor("officer-" + UUID.randomUUID(), ActorType.EMPLOYEE);
        partyOf.put(actor.id(), UUID.randomUUID());
        return actor;
    }

    private List<AuditRecord> audited(String code) {
        return audit.stream().filter(record -> record.operation().code().equals(code)).toList();
    }

    private double count(String name, String tag, String value) {
        return registry.get(name).tag(tag, value).counter().count();
    }

    private static ApiException refusal(Actor actor, Supplier<?> work) {
        ApiException refused = catchThrowableOfType(ApiException.class, () -> as(actor, work));
        assertThat(refused).as("refused on the error contract").isNotNull();
        return refused;
    }

    private static <T> T as(Actor actor, Supplier<T> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("flow-" + UUID.randomUUID())))) {
            return work.get();
        }
    }

    /** Payments' two keyed doors as payments applies them, each call recording the risk transactions open around it. */
    private final class Payments implements HeldWithdrawalActions, HeldPaymentActions {
        final List<String> calls = new ArrayList<>();
        final Deque<RuntimeException> failures = new ArrayDeque<>();
        final Map<String, ActionOutcome> answers = new HashMap<>();
        int effects;

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
            calls.add(door + "|" + target + "|" + key + "|" + reason + "|depth=" + depth);
            if (!failures.isEmpty()) {
                throw failures.pop();
            }
            return answers.computeIfAbsent(key, k -> {
                effects++;
                return ActionOutcome.DONE;
            });
        }
    }

    /** The platform's claim store, in memory - a claim held, completed once, reclaimed past its lease. */
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
