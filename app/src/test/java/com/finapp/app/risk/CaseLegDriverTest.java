package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.AlertEvidence;
import com.finapp.risk.AlertKind;
import com.finapp.risk.AlertRaiser;
import com.finapp.risk.AlertSeverity;
import com.finapp.risk.AlertStatus;
import com.finapp.risk.AlertWindow;
import com.finapp.risk.CaseKind;
import com.finapp.risk.CaseObserver;
import com.finapp.risk.Cases;
import com.finapp.risk.NewAlert;
import com.finapp.risk.ProfileInputs;
import com.finapp.risk.ProfileRecompute;
import com.finapp.risk.RiskAlert;
import com.finapp.risk.RiskAlertId;
import com.finapp.risk.RiskAssessmentId;
import com.finapp.risk.RiskCase;
import com.finapp.risk.RiskPartyIdentities;
import com.finapp.risk.RiskPolicies;
import com.finapp.risk.RiskPolicyVersionId;
import com.finapp.risk.RiskRating;
import com.finapp.risk.RuleKind;
import com.finapp.risk.SignalReadings;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import com.finapp.risk.VelocityWindows;
import com.finapp.risk.WindowKind;
import com.finapp.risk.testing.InMemoryCustomerRiskProfileStore;
import com.finapp.risk.testing.InMemoryRiskAlertStore;
import com.finapp.risk.testing.InMemoryRiskCaseStore;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The case leg, hermetic (`P13-TSK-016`; {@code DESIGN-016} D7): the alerts born {@code OPEN} before the folding are
 * folded once each, in a transaction of their own, bounded per tick; a second tick finds nothing; an approved
 * prohibition the door left unapplied is applied by the leg, once.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the case leg: the drain of the queued alerts and the prohibitions' re-drive (P13-TSK-016)")
class CaseLegDriverTest {

    private final InMemoryRiskCaseStore caseStore = new InMemoryRiskCaseStore();
    private final InMemoryRiskAlertStore alertStore = new InMemoryRiskAlertStore();
    private final InMemoryCustomerRiskProfileStore profileStore = new InMemoryCustomerRiskProfileStore();
    private final RiskEvaluationWorld world = new RiskEvaluationWorld();
    private final Map<String, UUID> partyOf = new HashMap<>();
    private int transactions;

    private final RiskTransactions inTransactions = new RiskTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            transactions++;
            return work.apply(RiskEvaluationWorld.UNTOUCHABLE);
        }

        @Override
        public <R> R inSnapshot(Function<Connection, R> work) {
            return inTransaction(work);
        }
    };

    private final Cases cases = new Cases(caseStore, alertStore,
            (unitOfWork, actor) -> Optional.ofNullable(partyOf.get(actor.id())), (unitOfWork, subject) ->
                    Optional.empty(), (unitOfWork, record) -> { }, (unitOfWork, envelope, payload, type) -> { },
            RiskEvaluationWorld.IDS, RiskEvaluationWorld.CLOCK, CaseObserver.NONE);
    private final ProfileRecompute recompute = new ProfileRecompute(new RiskPolicies(world.policyStore), profileStore,
            new ProfileInputs((unitOfWork, party) -> Optional.empty(), new SignalReadings(world.signalStore),
                    RiskPartyIdentities.NONE, new VelocityWindows(world.windowStore)),
            AlertRaiser.NONE, (unitOfWork, envelope, payload, type) -> { }, RiskEvaluationWorld.IDS,
            RiskEvaluationWorld.CLOCK, Duration.ofDays(1));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    @DisplayName("the queued backlog folds once, bounded per tick, each alert in its own transaction; a second tick finds"
            + " nothing")
    void theDrainFoldsTheBacklogOnce() {
        SignalSubject subject = SignalSubject.of(SubjectKind.PARTY, RiskEvaluationWorld.IDS.next());
        RiskAlert first = planted(subject, RuleKind.REFUND_RATIO, Instant.parse("2026-10-24T08:00:00Z"));
        RiskAlert second = planted(subject, RuleKind.CHARGEBACK_RATIO, Instant.parse("2026-10-24T09:00:00Z"));
        RiskAlert third = planted(SignalSubject.of(SubjectKind.PARTY, RiskEvaluationWorld.IDS.next()),
                RuleKind.REFUND_RATIO, Instant.parse("2026-10-24T10:00:00Z"));
        CaseLegDriver leg = new CaseLegDriver(cases, new CaseProhibitionDriver(cases, recompute, inTransactions,
                RiskEvaluationWorld.IDS, 5), inTransactions, metrics(), RiskFlowScope.NONE, 2);

        int done = asSystem(leg::driveDue);
        assertThat(done).isEqualTo(2);
        assertThat(alertStore.find(RiskEvaluationWorld.UNTOUCHABLE, first.id()).orElseThrow().status())
                .isEqualTo(AlertStatus.ATTACHED);
        assertThat(alertStore.find(RiskEvaluationWorld.UNTOUCHABLE, second.id()).orElseThrow().status())
                .isEqualTo(AlertStatus.ATTACHED);
        assertThat(alertStore.find(RiskEvaluationWorld.UNTOUCHABLE, third.id()).orElseThrow().status())
                .as("bounded: the third waits a tick").isEqualTo(AlertStatus.OPEN);
        assertThat(asSystem(leg::driveDue)).isEqualTo(1);
        assertThat(asSystem(leg::driveDue)).isZero();
        assertThat(caseStore.all()).hasSize(2).allSatisfy(riskCase ->
                assertThat(riskCase.kind()).isEqualTo(CaseKind.FRAUD));
    }

    @Test
    @DisplayName("an approved prohibition the door never applied is applied by the leg - once, a second tick writing"
            + " nothing")
    void theLegReDrivesAProhibition() {
        UUID party = RiskEvaluationWorld.IDS.next();
        Actor alice = analyst();
        Actor bob = analyst();
        RiskCase taken = as(alice, () -> {
            RiskCase opened = cases.open(RiskEvaluationWorld.UNTOUCHABLE, Cases.OpenRequest.of(CaseKind.FRAUD,
                    SignalSubject.of(SubjectKind.PARTY, party)), "a mule", alice, flow()).riskCase();
            return cases.take(RiskEvaluationWorld.UNTOUCHABLE, opened.id(), 0, alice, flow()).riskCase();
        });
        Cases.Decided proposed = cases.proposeDisposition(RiskEvaluationWorld.UNTOUCHABLE, taken.id(),
                taken.revision(), "CONFIRMED_FRAUD", true, "a mule account", alice, flow());
        cases.approveDisposition(RiskEvaluationWorld.UNTOUCHABLE, taken.id(), proposed.dispositionId(),
                proposed.riskCase().revision(), "confirmed", bob, flow());
        caseStore.applied = disposition -> profileStore.versionOfDisposition(RiskEvaluationWorld.UNTOUCHABLE,
                disposition).isPresent();
        CaseLegDriver leg = new CaseLegDriver(cases, new CaseProhibitionDriver(cases, recompute, inTransactions,
                RiskEvaluationWorld.IDS, 5), inTransactions, metrics(), RiskFlowScope.NONE, 10);

        assertThat(asSystem(leg::driveDue)).isEqualTo(1);
        assertThat(profileStore.head(party).orElseThrow().rating()).contains(RiskRating.PROHIBITED);
        assertThat(asSystem(leg::driveDue)).isZero();
        assertThat(profileStore.versions(RiskEvaluationWorld.UNTOUCHABLE, party)).hasSize(1);
    }

    // -----------------------------------------------------------------

    private RiskMonitoringMetrics metrics() {
        return new RiskMonitoringMetrics(unused(com.finapp.risk.MonitoringWatermarkStore.class),
                unused(com.finapp.risk.MonitoredTransactionStore.class), unused(com.finapp.risk.MonitoredLedger.class),
                () -> {
                    throw new java.sql.SQLException("no database in this test");
                }, java.time.Clock.systemUTC(), registry);
    }

    @SuppressWarnings("unchecked")
    private static <T> T unused(Class<T> port) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(CaseLegDriverTest.class.getClassLoader(),
                new Class<?>[] {port}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private RiskAlert planted(SignalSubject subject, RuleKind rule, Instant raisedAt) {
        alertStore.now = raisedAt;
        return alertStore.insert(RiskEvaluationWorld.UNTOUCHABLE, RiskAlertId.next(RiskEvaluationWorld.IDS),
                NewAlert.ofRule(AlertKind.FRAUD, AlertSeverity.HIGH, subject, rule,
                        AlertWindow.of(new com.finapp.risk.VelocityWindowKey(com.finapp.risk.VelocityDimension.PARTY,
                                subject.ref(), WindowKind.D1, Instant.parse("2026-10-24T00:00:00Z"))), RiskPolicyVersionId.of(RiskEvaluationWorld.IDS
                                .next()), AlertEvidence.assessment(RiskAssessmentId.of(RiskEvaluationWorld.IDS.next())),
                        flow(), CausationId.of("leg-suite"))).orElseThrow();
    }

    private Actor analyst() {
        Actor actor = new Actor("analyst-" + UUID.randomUUID(), ActorType.EMPLOYEE);
        partyOf.put(actor.id(), RiskEvaluationWorld.IDS.next());
        return actor;
    }

    private static CorrelationId flow() {
        return CorrelationId.of("leg-" + UUID.randomUUID());
    }

    private static <T> T asSystem(java.util.function.Supplier<T> work) {
        try (SecurityContext.Scope scope = SecurityContext.enterSystem()) {
            return work.get();
        }
    }

    private static <T> T as(Actor actor, java.util.function.Supplier<T> work) {
        try (SecurityContext.Scope scope = SecurityContext.enter(actor)) {
            return work.get();
        }
    }
}
