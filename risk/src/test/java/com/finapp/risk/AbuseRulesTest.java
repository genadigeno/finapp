package com.finapp.risk;

import static com.finapp.risk.EvaluationWorld.IDS;
import static com.finapp.risk.EvaluationWorld.UOW;
import static com.finapp.risk.EvaluationWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.risk.testing.RiskPolicyFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payment, merchant and BNPL abuse rule kinds (`P13-TSK-014`; {@code PHASE_13_PLAN.md} sections 12.2, 12.6;
 * {@code INV-MON-01}, {@code INV-RSK-04}): each fires exactly at its boundary and not one below, by integer
 * cross-multiplication - never a quotient; a ratio over fewer than its count floor never fires; an absent reading - no
 * window, a zero denominator - never fires and says so; and through the real T-eval, a payment's ratio is its
 * merchant's, read from the merchant's signals in the window's bucket over its {@code MERCHANT} window.
 */
@DisplayName("the abuse rule kinds: boundaries, the count floor, ABSENT, the merchant's reading (P13-TSK-014)")
class AbuseRulesTest {

    private static final Instant NOW = EvaluationWorld.CLOCK.instant();

    @Test
    @DisplayName("the ratios are written for the payments and the sweep with a window, a share and a count floor; BNPL"
            + " for the sweep alone; a floor of 0 and a ratio in a transfer version are refused at the door")
    void theScopeAndTheDoor() {
        for (RuleKind ratio : PaymentAbuse.RATIOS) {
            assertThat(ratio.operations()).containsExactlyInAnyOrder(RiskOperation.CARD_PAYMENT,
                    RiskOperation.WALLET_PAYMENT, RiskOperation.MONITORING);
            assertThat(ratio.parameters()).extracting(RuleKind.Parameter::type).containsExactly(
                    RuleKind.ParameterType.WINDOW, RuleKind.ParameterType.BASIS_POINTS, RuleKind.ParameterType.COUNT);
        }
        assertThat(RuleKind.BNPL_MULTI_APPLICATION.operations()).containsExactly(RiskOperation.MONITORING);
        for (RuleKind kind : PaymentAbuse.KINDS) {
            Map<String, ParameterValue> floorZero = kind == RuleKind.BNPL_MULTI_APPLICATION
                    ? Map.of("window", RiskPolicyFixtures.window(WindowKind.D7), "threshold_count",
                            RiskPolicyFixtures.count(0))
                    : RiskPolicyFixtures.ratio(WindowKind.D30, 100, 0);
            assertThatThrownBy(() -> RuleKindCoverageTest.oneRule(RiskOperation.MONITORING, kind, floorZero))
                    .as(kind.name()).isInstanceOf(RiskPolicy.PolicyInvalid.class)
                    .hasMessageContaining("threshold count of at least one");
        }
        assertThatThrownBy(() -> RuleKindCoverageTest.oneRule(RiskOperation.TRANSFER, RuleKind.REFUND_RATIO,
                RiskPolicyFixtures.ratio(WindowKind.D30, 100, 1))).isInstanceOf(RiskPolicy.PolicyInvalid.class)
                .hasMessageContaining("REFUND_RATIO is not written for TRANSFER");
        assertThat(RuleKindCoverageTest.oneRule(RiskOperation.CARD_PAYMENT, RuleKind.CHARGEBACK_RATIO,
                RiskPolicyFixtures.ratio(WindowKind.D30, 100, 1)).rules()).hasSize(1);
    }

    @Test
    @DisplayName("REFUND_RATIO fires at exactly its share and not one basis point below - on figures near the engine's"
            + " bound, exact by cross-multiplication")
    void theRefundShareBoundary() {
        RiskPolicy twentyPercent = RuleKindCoverageTest.oneRule(RiskOperation.MONITORING, RuleKind.REFUND_RATIO,
                RiskPolicyFixtures.ratio(WindowKind.D30, 2_000, 1));
        assertThat(judge(twentyPercent, refunds(WindowKind.D30, new Ratio(2_000, 10_000))).outcome())
                .isEqualTo(RuleOutcome.FIRED);
        assertThat(judge(twentyPercent, refunds(WindowKind.D30, new Ratio(1_999, 10_000))).outcome())
                .isEqualTo(RuleOutcome.NOT_FIRED);
        long k = EvaluationInputs.MAX_MINOR / 10_000;
        assertThat(judge(twentyPercent, refunds(WindowKind.D30, new Ratio(2_000 * k, 10_000 * k))).outcome())
                .as("20 % of 10^14, exactly").isEqualTo(RuleOutcome.FIRED);
        assertThat(judge(twentyPercent, refunds(WindowKind.D30, new Ratio(2_000 * k - 1, 10_000 * k))).outcome())
                .as("one refund short of 20 % of 10^14").isEqualTo(RuleOutcome.NOT_FIRED);
        RiskPolicy whole = RuleKindCoverageTest.oneRule(RiskOperation.MONITORING, RuleKind.REFUND_RATIO,
                RiskPolicyFixtures.ratio(WindowKind.D30, 10_000, 1));
        assertThat(judge(whole, refunds(WindowKind.D30,
                new Ratio(EvaluationInputs.MAX_MINOR, EvaluationInputs.MAX_MINOR))).outcome()).isEqualTo(RuleOutcome.FIRED);
        assertThat(judge(whole, refunds(WindowKind.D30, new Ratio(3, 2))).outcome())
                .as("refunds of earlier buckets' payments: a share above 100 % is a share").isEqualTo(RuleOutcome.FIRED);
        RuleHit hit = judge(twentyPercent, refunds(WindowKind.D30, new Ratio(5, 20)));
        assertThat(hit.judged()).isEqualTo("refunds:D30:num=5,den=20");
        assertThat(hit.contribution()).isEqualTo(100);
    }

    @Test
    @DisplayName("the count floor: a share over fewer refunds than threshold_count never fires, at the floor it does")
    void theCountFloor() {
        RiskPolicy policy = RuleKindCoverageTest.oneRule(RiskOperation.CARD_PAYMENT, RuleKind.REFUND_RATIO,
                RiskPolicyFixtures.ratio(WindowKind.D30, 2_000, 3));
        assertThat(judge(policy, refunds(WindowKind.D30, new Ratio(2, 2))).outcome())
                .as("100 % of a bucket's first two payments is noise").isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(judge(policy, refunds(WindowKind.D30, new Ratio(3, 15))).outcome())
                .as("3 refunds, exactly 20 %").isEqualTo(RuleOutcome.FIRED);
        assertThat(judge(policy, refunds(WindowKind.D30, new Ratio(3, 16))).outcome())
                .as("3 refunds, 18.75 %").isEqualTo(RuleOutcome.NOT_FIRED);
    }

    @Test
    @DisplayName("CHARGEBACK_RATIO is the same judgement over the chargebacks; it never reads the refunds")
    void theChargebackShare() {
        RiskPolicy onePercent = RuleKindCoverageTest.oneRule(RiskOperation.WALLET_PAYMENT, RuleKind.CHARGEBACK_RATIO,
                RiskPolicyFixtures.ratio(WindowKind.D30, 100, 2));
        assertThat(judge(onePercent, chargebacks(WindowKind.D30, new Ratio(2, 200))).outcome())
                .isEqualTo(RuleOutcome.FIRED);
        assertThat(judge(onePercent, chargebacks(WindowKind.D30, new Ratio(2, 201))).outcome())
                .isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(judge(onePercent, refunds(WindowKind.D30, new Ratio(50, 100))).outcome())
                .as("refunds are not chargebacks").isEqualTo(RuleOutcome.ABSENT);
    }

    @Test
    @DisplayName("BNPL_MULTI_APPLICATION fires at its count across merchants, not one below; absent without a reading")
    void theBnplCount() {
        RiskPolicy three = RuleKindCoverageTest.oneRule(RiskOperation.MONITORING, RuleKind.BNPL_MULTI_APPLICATION,
                Map.of("window", RiskPolicyFixtures.window(WindowKind.D7), "threshold_count", RiskPolicyFixtures.count(3)));
        assertThat(judge(three, bnpl(WindowKind.D7, 3)).outcome()).isEqualTo(RuleOutcome.FIRED);
        assertThat(judge(three, bnpl(WindowKind.D7, 2)).outcome()).isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(judge(three, bnpl(WindowKind.D30, 9)).outcome()).as("another window's count").isEqualTo(
                RuleOutcome.ABSENT);
        assertThat(judge(three, bnpl(WindowKind.D7, 3)).judged()).isEqualTo("bnpl:D7:count=3");
    }

    @Test
    @DisplayName("an absent reading never fires and says so: no ratio for the window; a zero denominator seals none")
    void anAbsentReadingNeverFires() {
        RiskPolicy policy = RuleKindCoverageTest.oneRule(RiskOperation.MONITORING, RuleKind.REFUND_RATIO,
                RiskPolicyFixtures.ratio(WindowKind.D30, 1, 1));
        RuleHit none = judge(policy, MonitoringReadings.none());
        assertThat(none.outcome()).isEqualTo(RuleOutcome.ABSENT);
        assertThat(none.judged()).isEqualTo("ABSENT");
        assertThat(none.contribution()).isZero();
        assertThat(judge(policy, refunds(WindowKind.D7, new Ratio(9, 10))).outcome()).isEqualTo(RuleOutcome.ABSENT);
        assertThat(AbuseRatios.of(7, 0)).as("refunds over nothing is no share").isEmpty();
        assertThat(new Ratio(7, 0).atLeast(0)).as("and a planted zero denominator never fires either").isFalse();
    }

    // ------------------------------------------------------------------ through T-eval

    @Test
    @DisplayName("T-eval of a card payment reads its merchant's refunds in the D30 bucket over the MERCHANT window, before"
            + " this payment: 25 of 50 fires the fixture's REFUND_RATIO (20 %, at least 20) and raises the merchant's"
            + " MERCHANT_ABUSE alert; another merchant's refunds and last bucket's are not this one's")
    void aPaymentJudgesItsMerchant() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.CARD_PAYMENT);
        UUID merchant = IDS.next();
        Instant bucket = WindowStarts.start(WindowKind.D30, NOW);
        plantMerchantWindow(world, merchant, 50);
        for (int i = 0; i < 25; i++) {
            world.signalStore.add(refund(merchant, IDS.next(), bucket.plus(Duration.ofMinutes(i))));
        }
        world.signalStore.add(refund(IDS.next(), IDS.next(), bucket.plus(Duration.ofHours(1))));
        world.signalStore.add(refund(merchant, IDS.next(), bucket.minus(Duration.ofSeconds(1))));

        RiskEvaluation.Assessed assessed = world.evaluation.evaluate(UOW, cardPayment(merchant));
        RuleHit hit = hit(world, assessed, RuleKind.REFUND_RATIO);
        assertThat(hit.outcome()).isEqualTo(RuleOutcome.FIRED);
        assertThat(hit.judged()).isEqualTo("refunds:D30:num=25,den=50");
        assertThat(hit(world, assessed, RuleKind.CHARGEBACK_RATIO).judged()).isEqualTo("chargebacks:D30:num=0,den=50");
        assertThat(world.raised).hasSize(1);
        NewAlert alert = world.raised.get(0).get(0);
        assertThat(alert.kind()).isEqualTo(AlertKind.MERCHANT_ABUSE);
        assertThat(alert.subject()).isEqualTo(SignalSubject.of(SubjectKind.MERCHANT, merchant));
        assertThat(alert.rule().code()).isEqualTo("REFUND_RATIO");
        assertThat(alert.window().kind()).isEqualTo(WindowKind.D30);
        assertThat(alert.window().start()).isEqualTo(bucket);
        assertThat(alert.evidence().assessments()).containsExactly(assessed.assessmentId());
        assertThat(alert.severity()).isEqualTo(AlertSeverity.MEDIUM);
        assertThat(assessed.permits()).as("100 alone stays ALLOW: the merchant's ratio refuses no customer").isTrue();
    }

    @Test
    @DisplayName("a merchant with no counted payment in the bucket has no ratio (ABSENT), however many refunds; a payment"
            + " carrying no merchant reads none")
    void noDenominatorNoRatio() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.CARD_PAYMENT);
        UUID merchant = IDS.next();
        for (int i = 0; i < 30; i++) {
            world.signalStore.add(refund(merchant, IDS.next(), NOW.minus(Duration.ofMinutes(i))));
        }
        RiskEvaluation.Assessed assessed = world.evaluation.evaluate(UOW, cardPayment(merchant));
        assertThat(hit(world, assessed, RuleKind.REFUND_RATIO).outcome()).isEqualTo(RuleOutcome.ABSENT);
        assertThat(world.raised).as("nothing fired, nothing raised").isEmpty();

        RiskEvaluation.Assessed unmerchanted = world.evaluation.evaluate(UOW, EvaluationWorld.request(
                RiskOperation.CARD_PAYMENT, eur(10_00), IDS.next()).build());
        assertThat(hit(world, unmerchanted, RuleKind.CHARGEBACK_RATIO).outcome()).isEqualTo(RuleOutcome.ABSENT);
    }

    @Test
    @DisplayName("a session confirming five checkouts within the hour is challenged (the fixture's SESSION weight 300);"
            + " the fifth confirmation reads four and is not; no session, ABSENT")
    void aSessionConfirmingManyCheckouts() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.CARD_PAYMENT);
        UUID session = IDS.next();
        RiskEvaluation.Assessed first = world.evaluation.evaluate(UOW, sessioned(session));
        assertThat(sessionHit(world, first).judged()).isEqualTo("velocity:SESSION:H1:count=0");
        for (int i = 1; i < 5; i++) {
            assertThat(world.evaluation.evaluate(UOW, sessioned(session)).decision()).isEqualTo(RiskDecision.ALLOW);
        }
        RiskEvaluation.Assessed sixth = world.evaluation.evaluate(UOW, sessioned(session));
        assertThat(sessionHit(world, sixth).outcome()).isEqualTo(RuleOutcome.FIRED);
        assertThat(sixth.decision()).isEqualTo(RiskDecision.CHALLENGE);
        assertThat(sixth.challengeRequired()).as("no assurance sealed: the step-up is unsatisfied").isTrue();
        RiskAssessment sealed = world.assessmentStore.read(UOW, sixth.assessmentId()).orElseThrow();
        assertThat(sealed.subjects().session()).as("the session is sealed beside the subjects").contains(session);
        assertThat(sealed.movement().subject(VelocityDimension.SESSION)).contains(session.toString());
        RiskEvaluation.Assessed unsessioned = world.evaluation.evaluate(UOW, cardPayment(IDS.next()));
        assertThat(sessionHit(world, unsessioned).outcome()).isEqualTo(RuleOutcome.ABSENT);
    }

    // ------------------------------------------------------------------ the shapes

    private static RuleHit judge(RiskPolicy policy, MonitoringReadings readings) {
        EvaluationInputs.Builder inputs = EvaluationInputs.builder(policy.operation()).monitoring(readings);
        if (policy.operation().judgesAMovement()) {
            inputs.amount(eur(10_00));
        }
        return RiskEngineV1.evaluate(policy, inputs.build()).hits().get(0);
    }

    static MonitoringReadings refunds(WindowKind window, Ratio ratio) {
        Map<WindowKind, Ratio> map = new EnumMap<>(WindowKind.class);
        map.put(window, ratio);
        return new MonitoringReadings(Reading.absent(), Map.of(), Map.of(), map, Map.of(), Map.of());
    }

    static MonitoringReadings chargebacks(WindowKind window, Ratio ratio) {
        Map<WindowKind, Ratio> map = new EnumMap<>(WindowKind.class);
        map.put(window, ratio);
        return new MonitoringReadings(Reading.absent(), Map.of(), Map.of(), Map.of(), map, Map.of());
    }

    static MonitoringReadings bnpl(WindowKind window, long count) {
        Map<WindowKind, Long> map = new EnumMap<>(WindowKind.class);
        map.put(window, count);
        return new MonitoringReadings(Reading.absent(), Map.of(), Map.of(), Map.of(), Map.of(), map);
    }

    /** A refund of a payment to {@code merchant} by a fresh payer - as the handler records it. */
    static RiskSignal refund(UUID merchant, UUID intent, Instant observedAt) {
        return abuse(SignalKind.REFUND_COMPLETED, IDS.next(), merchant, intent, observedAt);
    }

    static RiskSignal abuse(SignalKind kind, UUID party, UUID merchant, UUID intent, Instant observedAt) {
        SignalAttributes attributes = kind == SignalKind.BNPL_APPLICATION_SUBMITTED
                ? SignalAttributes.of(kind, Map.of("product", "BNPL_PAY_IN_N"))
                : SignalAttributes.of(kind, Map.of("merchant_subject_ref", merchant.toString(), "intent_ref", intent.toString()));
        return new RiskSignal(RiskSignalId.next(IDS), kind, SignalSubject.of(SubjectKind.PARTY, party),
                AtoRulesTest.source(kind.sourceEventType()), observedAt, attributes);
    }

    /** {@code count} payments to {@code merchant} counted in the D30 bucket containing now. */
    static void plantMerchantWindow(EvaluationWorld world, UUID merchant, long count) {
        VelocityWindowKey key = new VelocityWindowKey(VelocityDimension.MERCHANT, merchant.toString(), WindowKind.D30,
                WindowStarts.start(WindowKind.D30, NOW));
        world.windowStore.delegate.plant(new VelocityWindow(key, count, eur(count * 10_00), NOW));
    }

    private static RiskEvaluationRequest cardPayment(UUID merchant) {
        return EvaluationWorld.request(RiskOperation.CARD_PAYMENT, eur(10_00), IDS.next()).merchant(merchant).build();
    }

    private static RiskEvaluationRequest sessioned(UUID session) {
        UUID identity = IDS.next();
        return EvaluationWorld.request(RiskOperation.CARD_PAYMENT, eur(10_00), IDS.next()).merchant(IDS.next())
                .identity(identity).actor(new Actor(identity.toString(), ActorType.CUSTOMER)).session(session).build();
    }

    private static RuleHit sessionHit(EvaluationWorld world, RiskEvaluation.Assessed assessed) {
        RiskAssessment sealed = world.assessmentStore.read(UOW, assessed.assessmentId()).orElseThrow();
        return sealed.hits().stream().filter(hit -> hit.ordinal() == sessionOrdinal()).findFirst().orElseThrow();
    }

    private static int sessionOrdinal() {
        return RiskPolicyFixtures.v1(RiskOperation.CARD_PAYMENT).rules().stream()
                .filter(rule -> rule.kind() == RuleKind.VELOCITY_COUNT
                        && rule.parameters().dimension("dimension") == VelocityDimension.SESSION)
                .findFirst().orElseThrow().ordinal();
    }

    private static RuleHit hit(EvaluationWorld world, RiskEvaluation.Assessed assessed, RuleKind kind) {
        RiskAssessment sealed = world.assessmentStore.read(UOW, assessed.assessmentId()).orElseThrow();
        return sealed.hits().stream().filter(hit -> hit.kind() == kind).findFirst().orElseThrow();
    }

}
