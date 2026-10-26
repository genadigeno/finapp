package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.CanonicalEvaluation;
import com.finapp.risk.CommandRef;
import com.finapp.risk.EvaluationInputs;
import com.finapp.risk.RiskAssessment;
import com.finapp.risk.RiskAssessmentId;
import com.finapp.risk.RiskAssessments;
import com.finapp.risk.RiskEngineV1;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskEvaluationRequest;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicies;
import com.finapp.risk.RiskPolicy;
import com.finapp.risk.RiskPolicyStore;
import com.finapp.risk.RiskPolicyVersion;
import com.finapp.risk.RiskPolicyVersionId;
import com.finapp.risk.RiskPolicyVersionStatus;
import com.finapp.risk.VelocityOutcome;
import com.finapp.risk.Verdict;
import com.finapp.risk.testing.InMemoryRiskAssessmentStore;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The analyst's assessment doors, hermetic (`P13-TSK-004`; {@code PHASE_13_PLAN.md} sections 9, 11): the read serves
 * the sealed assessment whole and records {@code risk.AssessmentRead} in its own transaction; the replay is reasoned,
 * answers {@code IDENTICAL} and records {@code risk.Replayed} before answering; the analyst's own party and an analyst
 * placed in no party are {@code 403 risk.SelfDealingRefused}, recorded {@code FAILED} and never naming the party; an
 * unknown or malformed id the uniform {@code 404}; both routes {@code RISK_INVESTIGATE}. Over HTTP - the session, the
 * permission refusal, the error contract - the doors are {@code RiskAssessmentApiTest}'s (written, owed - C5).
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the risk assessment doors: audited reads and replays, self-dealing fails closed (P13-TSK-004)")
class RiskEvaluationDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(
            RiskEvaluationDeskTest.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });
    private static final RiskPolicy TRANSFER = RiskPolicyFixtures.v1(RiskOperation.TRANSFER);

    private final InMemoryRiskAssessmentStore store = new InMemoryRiskAssessmentStore();
    private final Map<RiskPolicyVersionId, RiskPolicy> versions = new HashMap<>();
    private final Map<String, UUID> partyOfActor = new HashMap<>();
    private final List<AuditRecord> audit = new ArrayList<>();
    private final List<String> transactions = new ArrayList<>();

    private final RiskAssessmentDesk desk = new RiskAssessmentDesk(
            new RiskAssessments(store, new RiskPolicies(policyStore()),
                    (unitOfWork, actor) -> Optional.ofNullable(partyOfActor.get(actor.id())), recording(), IDS, CLOCK),
            // The day's replay is P13-TSK-023's, judged by DailyReplayTest and RiskProofDesksTest; unused here.
            new com.finapp.risk.DailyReplay((com.finapp.risk.RiskProofReads) Proxy.newProxyInstance(
                    RiskEvaluationDeskTest.class.getClassLoader(), new Class<?>[] {com.finapp.risk.RiskProofReads.class},
                    (proxy, method, args) -> {
                        throw new UnsupportedOperationException("the day's replay is not this suite's");
                    }), new RiskPolicies(policyStore()),
                    (unitOfWork, actor) -> Optional.ofNullable(partyOfActor.get(actor.id())), recording(), IDS, CLOCK),
            new RiskTransactions() {
                @Override
                public <R> R inTransaction(Function<Connection, R> work) {
                    transactions.add("transaction");
                    return work.apply(UOW);
                }

                @Override
                public <R> R inSnapshot(Function<Connection, R> work) {
                    transactions.add("snapshot");
                    return work.apply(UOW);
                }
            },
            RiskFlowScope.NONE);

    @Test
    @DisplayName("the read serves the sealed assessment whole - subjects, amount, pin, verdict, hits, reasons, the inputs"
            + " and whether the seal holds - and records risk.AssessmentRead in the read's own transaction, the summary"
            + " codes only")
    void theReadIsServedAndRecorded() {
        Actor analyst = analyst(IDS.next());
        RiskAssessment sealed = seal(IDS.next(), Money.ofMinorUnits(1_200_00, CurrencyCode.of("EUR")));

        RiskAssessmentDesk.RiskAssessmentView view = as(analyst, () -> desk.read(sealed.id().value().toString()));

        assertThat(view.assessmentId()).isEqualTo(sealed.id().value().toString());
        assertThat(view.decision()).isEqualTo("CHALLENGE");
        assertThat(view.amount()).isEqualTo("1200.00");
        assertThat(view.currency()).isEqualTo("EUR");
        assertThat(view.reasonCodes()).containsExactly("AMOUNT_BAND", "AMOUNT_BAND");
        assertThat(view.hits()).hasSize(TRANSFER.rules().size());
        assertThat(view.sealHolds()).isTrue();
        assertThat(view.inputsCanonical()).isEqualTo(sealed.inputsCanonical());
        assertThat(view.policyVersion()).isEqualTo(1);
        assertThat(transactions).containsExactly("transaction");
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.operation().code()).isEqualTo("risk.AssessmentRead");
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.targetType()).isEqualTo("risk_assessment");
            assertThat(record.targetId()).isEqualTo(sealed.id().value().toString());
            assertThat(record.changeSummary()).contains("operation=TRANSFER, decision=CHALLENGE, seal=HOLDS");
        });
    }

    @Test
    @DisplayName("the analyst's own party, and an analyst placed in no party, are 403 risk.SelfDealingRefused - nothing"
            + " served, the attempt recorded FAILED in a transaction of its own, the summary naming no party")
    void selfDealingFailsClosed() {
        UUID party = IDS.next();
        RiskAssessment sealed = seal(party, Money.ofMinorUnits(100_00, CurrencyCode.of("EUR")));

        ApiException own = catchThrowableOfType(ApiException.class,
                () -> as(analyst(party), () -> desk.read(sealed.id().value().toString())));
        assertThat(own.errorCode()).isEqualTo(RiskErrorCode.SELF_DEALING_REFUSED);
        ApiException placeless = catchThrowableOfType(ApiException.class,
                () -> as(new Actor("nobody", ActorType.EMPLOYEE), () -> desk.replay(sealed.id().value().toString(),
                        "checking the seal")));
        assertThat(placeless.errorCode()).isEqualTo(RiskErrorCode.SELF_DEALING_REFUSED);

        assertThat(audit).hasSize(2).allSatisfy(record -> {
            assertThat(record.operation().code()).isEqualTo("risk.SelfDealingRefused");
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.changeSummary().orElseThrow()).doesNotContain(party.toString());
        });
        assertThat(audit.get(0).changeSummary()).contains("act=assessment-read, cause=OWN_PARTY refused");
        assertThat(audit.get(1).changeSummary()).contains("act=assessment-replay, cause=UNPLACEABLE refused");
    }

    @Test
    @DisplayName("an unknown assessment and a malformed id are the uniform 404 risk.NotFound, nothing recorded")
    void theUnknownIsNotFound() {
        Actor analyst = analyst(IDS.next());
        for (String id : List.of(IDS.next().toString(), "not-an-id")) {
            ApiException missing = catchThrowableOfType(ApiException.class, () -> as(analyst, () -> desk.read(id)));
            assertThat(missing.errorCode()).as(id).isEqualTo(RiskErrorCode.NOT_FOUND);
        }
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("the replay: a blank reason is 422 risk.ReasonRequired before anything is read; a reasoned one replays"
            + " IDENTICAL on a snapshot and records risk.Replayed with the reason in its own transaction")
    void theReplayIsReasonedAndRecorded() {
        Actor analyst = analyst(IDS.next());
        RiskAssessment sealed = seal(IDS.next(), Money.ofMinorUnits(600_00, CurrencyCode.of("EUR")));

        ApiException blank = catchThrowableOfType(ApiException.class,
                () -> as(analyst, () -> desk.replay(sealed.id().value().toString(), " ")));
        assertThat(blank.errorCode()).isEqualTo(RiskErrorCode.REASON_REQUIRED);
        assertThat(store.log).isEmpty();

        RiskAssessmentDesk.RiskReplayView replay = as(analyst,
                () -> desk.replay(sealed.id().value().toString(), "a customer disputes the challenge"));
        assertThat(replay.verdict()).isEqualTo("IDENTICAL");
        assertThat(replay.diverged()).isEmpty();
        assertThat(replay.firstDivergedRule()).isNull();
        assertThat(transactions).containsExactly("snapshot", "transaction");
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.operation().code()).isEqualTo("risk.Replayed");
            assertThat(record.reason()).contains("a customer disputes the challenge");
            assertThat(record.changeSummary()).contains("verdict=IDENTICAL, diverged=none");
        });
    }

    @Test
    @DisplayName("both routes carry exactly RISK_INVESTIGATE")
    void bothRoutesAreTheAnalysts() throws NoSuchMethodException {
        for (Method method : List.of(
                RiskAssessmentController.class.getMethod("readRiskAssessment", String.class),
                RiskAssessmentController.class.getMethod("replayRiskAssessment", String.class,
                        RiskAssessmentController.RiskReplayRequest.class))) {
            assertThat(method.getAnnotation(RequiresPermission.class).value()).as(method.getName())
                    .isEqualTo(PermissionName.RISK_INVESTIGATE);
        }
    }

    // ------------------------------------------------------------------

    /** A transfer of {@code amount} for {@code party}, judged by the v1 fixture under version 1 and sealed. */
    private RiskAssessment seal(UUID party, Money amount) {
        RiskPolicyVersionId pinned = RiskPolicyVersionId.next(IDS);
        versions.put(pinned, TRANSFER);
        RiskEvaluationRequest request = RiskEvaluationRequest.builder(RiskOperation.TRANSFER,
                CommandRef.of(IDS.next()), amount, party, CorrelationId.of("flow"), CausationId.of("cause"))
                .sourceAccount(IDS.next()).destinationAccount(IDS.next()).build();
        EvaluationInputs inputs = request.judged().build();
        Verdict verdict = RiskEngineV1.evaluate(TRANSFER, inputs);
        String inputsText = CanonicalEvaluation.render(inputs);
        boolean challenge = verdict.decision().orElseThrow() == com.finapp.risk.RiskDecision.CHALLENGE;
        RiskAssessment sealed = new RiskAssessment(RiskAssessmentId.next(IDS), RiskOperation.TRANSFER,
                request.commandRef(), RiskAssessment.Subjects.of(request), amount,
                Optional.of(new RiskAssessment.Pin(pinned, 1)), verdict.engine(), verdict.score(),
                verdict.decision().orElseThrow(), Optional.empty(),
                challenge ? Optional.of(false) : Optional.empty(),
                challenge || verdict.decision().orElseThrow() != com.finapp.risk.RiskDecision.ALLOW
                        ? VelocityOutcome.NOT_PERMITTED : VelocityOutcome.COUNTED,
                Optional.of(CLOCK.instant()), inputsText, CanonicalEvaluation.sha256(inputsText),
                CanonicalEvaluation.sha256(CanonicalEvaluation.render(verdict)), verdict.hits(), verdict.reasons(), 120,
                "flow", "cause", Optional.empty());
        store.plant(sealed);
        return sealed;
    }

    private Actor analyst(UUID party) {
        Actor actor = new Actor("analyst-" + IDS.next(), ActorType.EMPLOYEE);
        partyOfActor.put(actor.id(), party);
        return actor;
    }

    private AuditWriter<Connection> recording() {
        return (unitOfWork, record) -> audit.add(record);
    }

    /** A {@link RiskPolicyStore} answering only {@code version} - the replay's one read of it. */
    private RiskPolicyStore policyStore() {
        return (RiskPolicyStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {RiskPolicyStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("version")) {
                        RiskPolicyVersionId id = (RiskPolicyVersionId) args[1];
                        RiskPolicy policy = versions.get(id);
                        return Optional.ofNullable(policy).map(content -> new RiskPolicyVersion(
                                new RiskPolicyStore.VersionRow(id, content.operation(), 1, content.engine().name(),
                                        RiskPolicyVersionStatus.ACTIVE, "alice", CLOCK.instant(), Optional.of("bob"),
                                        Optional.of(CLOCK.instant()), Optional.of(CLOCK.instant()), Optional.empty()),
                                content));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static <T> T as(Actor actor, java.util.function.Supplier<T> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("flow-" + UUID.randomUUID())))) {
            return work.get();
        }
    }
}
