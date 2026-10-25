package com.finapp.risk;

import static com.finapp.risk.EvaluationWorld.IDS;
import static com.finapp.risk.EvaluationWorld.UOW;
import static com.finapp.risk.EvaluationWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PaymentAbuse} - the readings and the alerts both paths share (`P13-TSK-014`; {@code INV-RSK-06},
 * {@code INV-RSK-08}): the profile leg's party readings (its ratios over its own signals and its {@code PARTY} window,
 * its BNPL applications within the window, the latest evidence read only where counted); the alerts' identity - kind,
 * subject, rule, the window's bucket - their constant severity, their evidence by reference and nothing else; one alert
 * per key; none for another operation; a fired rule with no evidence refused, never an alert that names nothing.
 */
@DisplayName("PaymentAbuse: the party's readings, the alerts' identity and evidence (P13-TSK-014)")
class PaymentAbuseTest {

    private static final Instant NOW = EvaluationWorld.CLOCK.instant();
    private static final RiskPolicy MONITORING = RiskPolicyFixtures.v1(RiskOperation.MONITORING);
    private static final RiskPolicy CARD = RiskPolicyFixtures.v1(RiskOperation.CARD_PAYMENT);
    private static final RiskPolicyVersionId VERSION = RiskPolicyVersionId.next(IDS);
    private static final CorrelationId FLOW = CorrelationId.of("flow-14");

    private final SignalReadingsTest.InMemoryStore signalStore = new SignalReadingsTest.InMemoryStore();
    private final FakeVelocityWindowStore windowStore = new FakeVelocityWindowStore(NOW);
    private final SignalReadings signals = new SignalReadings(signalStore);
    private final VelocityWindows windows = new VelocityWindows(windowStore);

    @Test
    @DisplayName("the party's ratios: its refunds and chargebacks in the D30 bucket over its PARTY window's committed count"
            + " for that bucket; another party's and last bucket's are not its own; no window is no ratio")
    void thePartysRatios() {
        UUID party = IDS.next();
        Instant bucket = WindowStarts.start(WindowKind.D30, NOW);
        planted(VelocityDimension.PARTY, party, WindowKind.D30, 10);
        for (int i = 0; i < 4; i++) {
            signalStore.add(AbuseRulesTest.abuse(SignalKind.REFUND_COMPLETED, party, IDS.next(), IDS.next(),
                    bucket.plus(Duration.ofHours(i))));
        }
        signalStore.add(AbuseRulesTest.abuse(SignalKind.REFUND_COMPLETED, party, IDS.next(), IDS.next(),
                bucket.minus(Duration.ofMinutes(1))));
        signalStore.add(AbuseRulesTest.abuse(SignalKind.REFUND_COMPLETED, IDS.next(), IDS.next(), IDS.next(),
                bucket.plus(Duration.ofHours(2))));
        signalStore.add(AbuseRulesTest.abuse(SignalKind.CHARGEBACK_RECEIVED, party, IDS.next(), IDS.next(),
                bucket.plus(Duration.ofHours(3))));

        PaymentAbuse.PartyRead read = PaymentAbuse.party(UOW, signals, windows, MONITORING, party, NOW);
        assertThat(read.readings().refunds()).containsEntry(WindowKind.D30, new Ratio(4, 10));
        assertThat(read.readings().chargebacks()).containsEntry(WindowKind.D30, new Ratio(1, 10));
        assertThat(read.latest(SignalKind.REFUND_COMPLETED).orElseThrow().observedAt())
                .isEqualTo(bucket.plus(Duration.ofHours(3)));

        UUID uncounted = IDS.next();
        signalStore.add(AbuseRulesTest.abuse(SignalKind.REFUND_COMPLETED, uncounted, IDS.next(), IDS.next(), NOW));
        PaymentAbuse.PartyRead none = PaymentAbuse.party(UOW, signals, windows, MONITORING, uncounted, NOW);
        assertThat(none.readings().refunds()).as("no counted movement this bucket: no share").isEmpty();
    }

    @Test
    @DisplayName("the party's BNPL applications within the window's length before now - across merchants, a sliding"
            + " count from the signals' own instants; the latest kept for the alert, none read where none counted")
    void thePartysApplications() {
        UUID party = IDS.next();
        for (Duration ago : List.of(Duration.ofDays(8), Duration.ofDays(6), Duration.ofDays(2), Duration.ofHours(1))) {
            signalStore.add(AbuseRulesTest.abuse(SignalKind.BNPL_APPLICATION_SUBMITTED, party, null, null,
                    NOW.minus(ago)));
        }
        PaymentAbuse.PartyRead read = PaymentAbuse.party(UOW, signals, windows, MONITORING, party, NOW);
        assertThat(read.readings().bnplApplications()).containsEntry(WindowKind.D7, 3L);
        assertThat(read.latest(SignalKind.BNPL_APPLICATION_SUBMITTED).orElseThrow().observedAt())
                .isEqualTo(NOW.minus(Duration.ofHours(1)));
        assertThat(read.latest(SignalKind.REFUND_COMPLETED)).isEmpty();
        assertThat(PaymentAbuse.party(UOW, signals, windows, CARD, party, NOW).readings().bnplApplications())
                .as("a payment version is never the party's reading").isEmpty();
    }

    @Test
    @DisplayName("the party's alerts: FRAUD on the PARTY, the rule's code, the ratio's bucket or the latest application's,"
            + " the latest evidence signal and its source event as the cause; constant severities")
    void thePartysAlerts() {
        UUID party = IDS.next();
        planted(VelocityDimension.PARTY, party, WindowKind.D30, 4);
        for (int i = 0; i < 3; i++) {
            signalStore.add(AbuseRulesTest.abuse(SignalKind.CHARGEBACK_RECEIVED, party, IDS.next(), IDS.next(),
                    NOW.minus(Duration.ofMinutes(10 + i))));
            signalStore.add(AbuseRulesTest.abuse(SignalKind.BNPL_APPLICATION_SUBMITTED, party, null, null,
                    NOW.minus(Duration.ofDays(1 + i))));
        }
        PaymentAbuse.PartyRead read = PaymentAbuse.party(UOW, signals, windows, MONITORING, party, NOW);
        Verdict verdict = RiskEngineV1.evaluate(MONITORING, EvaluationInputs.builder(RiskOperation.MONITORING)
                .subject(SignalSubject.of(SubjectKind.PARTY, party)).monitoring(read.readings()).build());
        List<NewAlert> alerts = PaymentAbuse.party(verdict, MONITORING, read, party, VERSION, FLOW);

        assertThat(alerts).extracting(alert -> alert.rule().code())
                .containsExactly("CHARGEBACK_RATIO", "BNPL_MULTI_APPLICATION");
        NewAlert chargebacks = alerts.get(0);
        RiskSignal latestChargeback = read.latest(SignalKind.CHARGEBACK_RECEIVED).orElseThrow();
        assertThat(chargebacks.kind()).isEqualTo(AlertKind.FRAUD);
        assertThat(chargebacks.subject()).isEqualTo(SignalSubject.of(SubjectKind.PARTY, party));
        assertThat(chargebacks.severity()).isEqualTo(AlertSeverity.HIGH);
        assertThat(chargebacks.window()).isEqualTo(AlertWindow.containing(WindowKind.D30, NOW));
        assertThat(chargebacks.evidence().signals()).containsExactly(latestChargeback.id());
        assertThat(chargebacks.evidence().assessments()).isEmpty();
        assertThat(chargebacks.causation().value()).isEqualTo(latestChargeback.source().id().value().toString());
        assertThat(chargebacks.policyVersion()).contains(VERSION);
        NewAlert applications = alerts.get(1);
        assertThat(applications.severity()).isEqualTo(AlertSeverity.MEDIUM);
        assertThat(applications.window()).isEqualTo(AlertWindow.containing(WindowKind.D7, NOW.minus(Duration.ofDays(1))));
        assertThat(alerts).allSatisfy(alert -> assertThat(alert.toString()).doesNotContain(party.toString()));

        assertThat(PaymentAbuse.party(verdict, MONITORING, read, party, VERSION, FLOW))
                .as("judged twice, the same keys").extracting(NewAlert::key)
                .containsExactlyElementsOf(alerts.stream().map(NewAlert::key).toList());
    }

    @Test
    @DisplayName("an abuse rule fired over no evidence is refused - never an alert naming nothing")
    void aFiredRuleOverNoEvidenceIsRefused() {
        UUID party = IDS.next();
        Verdict fired = RiskEngineV1.evaluate(MONITORING, EvaluationInputs.builder(RiskOperation.MONITORING)
                .monitoring(AbuseRulesTest.bnpl(WindowKind.D7, 5)).build());
        assertThatThrownBy(() -> PaymentAbuse.party(fired, MONITORING, PaymentAbuse.PartyRead.none(), party, VERSION,
                FLOW)).isInstanceOf(IllegalStateException.class).hasMessageContaining("fired over no signal");
    }

    @Test
    @DisplayName("the merchant's alerts: one per fired ratio rule on a payment, keyed by the window's bucket containing the"
            + " cut, the sealed assessment its evidence; nothing for a monitoring verdict or a quiet one")
    void theMerchantsAlerts() {
        UUID merchant = IDS.next();
        RiskAssessmentId assessment = RiskAssessmentId.next(IDS);
        Verdict over = RiskEngineV1.evaluate(CARD, EvaluationInputs.builder(RiskOperation.CARD_PAYMENT).amount(eur(10_00))
                .monitoring(new MonitoringReadings(Reading.absent(), Map.of(), Map.of(),
                        Map.of(WindowKind.D30, new Ratio(30, 100)), Map.of(WindowKind.D30, new Ratio(12, 100)), Map.of()))
                .build());
        List<NewAlert> alerts = PaymentAbuse.merchant(over, CARD, merchant, VERSION, assessment, NOW, FLOW,
                CausationId.of("command"));
        assertThat(alerts).extracting(alert -> alert.rule().code())
                .containsExactlyInAnyOrder("CHARGEBACK_RATIO", "REFUND_RATIO");
        assertThat(alerts).allSatisfy(alert -> {
            assertThat(alert.kind()).isEqualTo(AlertKind.MERCHANT_ABUSE);
            assertThat(alert.subject()).isEqualTo(SignalSubject.of(SubjectKind.MERCHANT, merchant));
            assertThat(alert.window()).isEqualTo(AlertWindow.containing(WindowKind.D30, NOW));
            assertThat(alert.evidence().assessments()).containsExactly(assessment);
            assertThat(alert.evidence().signals()).isEmpty();
        });
        Verdict quiet = RiskEngineV1.evaluate(CARD, EvaluationInputs.builder(RiskOperation.CARD_PAYMENT)
                .amount(eur(10_00)).build());
        assertThat(PaymentAbuse.merchant(quiet, CARD, merchant, VERSION, assessment, NOW, FLOW,
                CausationId.of("command"))).isEmpty();
        Verdict monitoring = RiskEngineV1.evaluate(MONITORING, EvaluationInputs.builder(RiskOperation.MONITORING)
                .monitoring(AbuseRulesTest.refunds(WindowKind.D30, new Ratio(9, 10))).build());
        assertThat(PaymentAbuse.merchant(monitoring, MONITORING, merchant, VERSION, assessment, NOW, FLOW,
                CausationId.of("command"))).as("the sweep's ratio is the party's, never a merchant's").isEmpty();
    }

    @Test
    @DisplayName("each abuse kind counts its own evidence kind; the payment's denominator keys are the MERCHANT windows")
    void theEvidenceAndTheDenominators() {
        assertThat(PaymentAbuse.evidenceOf(RuleKind.REFUND_RATIO)).isEqualTo(SignalKind.REFUND_COMPLETED);
        assertThat(PaymentAbuse.evidenceOf(RuleKind.CHARGEBACK_RATIO)).isEqualTo(SignalKind.CHARGEBACK_RECEIVED);
        assertThat(PaymentAbuse.evidenceOf(RuleKind.BNPL_MULTI_APPLICATION))
                .isEqualTo(SignalKind.BNPL_APPLICATION_SUBMITTED);
        assertThatThrownBy(() -> PaymentAbuse.evidenceOf(RuleKind.AMOUNT_BAND)).isInstanceOf(
                IllegalArgumentException.class);
        assertThat(VelocityWindows.keysRead(CARD)).contains(new VelocityKey(VelocityDimension.MERCHANT, WindowKind.D30),
                new VelocityKey(VelocityDimension.SESSION, WindowKind.H1));
        assertThat(PaymentAbuse.denominatorKeys(MONITORING)).as("the profile leg reads its PARTY window itself").isEmpty();
    }

    private void planted(VelocityDimension dimension, UUID subject, WindowKind window, long count) {
        VelocityWindowKey key = new VelocityWindowKey(dimension, subject.toString(), window,
                WindowStarts.start(window, NOW));
        windowStore.plant(new VelocityWindow(key, count, eur(count * 10_00), NOW));
    }
}
