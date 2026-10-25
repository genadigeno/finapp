package com.finapp.app.risk;

import static com.finapp.app.risk.RiskEvaluationWorld.CLOCK;
import static com.finapp.app.risk.RiskEvaluationWorld.IDS;
import static com.finapp.app.risk.RiskEvaluationWorld.UNTOUCHABLE;
import static com.finapp.app.risk.RiskEvaluationWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Session;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.payments.InstrumentKind;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentRiskDecision;
import com.finapp.payments.PaymentVerdict;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.AlertKind;
import com.finapp.risk.AlertRule;
import com.finapp.risk.AlertSeverity;
import com.finapp.risk.AlertStatus;
import com.finapp.risk.Alerts;
import com.finapp.risk.ProfileRecompute;
import com.finapp.risk.RiskAlert;
import com.finapp.risk.RiskAssessment;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskRating;
import com.finapp.risk.RiskSignal;
import com.finapp.risk.RiskSignalId;
import com.finapp.risk.RuleHit;
import com.finapp.risk.RuleKind;
import com.finapp.risk.RuleOutcome;
import com.finapp.risk.SignalAttributes;
import com.finapp.risk.SignalKind;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import com.finapp.risk.VelocityDimension;
import com.finapp.risk.VelocityWindow;
import com.finapp.risk.VelocityWindowKey;
import com.finapp.risk.WindowKind;
import com.finapp.risk.WindowStarts;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Payment, merchant and BNPL abuse through the real seam and the real profile leg (`P13-TSK-014`; {@code PHASE_13_PLAN.md}
 * sections 12.2, 12.6; ADR-0110; {@code INV-RSK-04}, {@code INV-RSK-06}, {@code INV-RSK-08}): over a real
 * {@code RiskEvaluation} and {@code ProfileRecompute} with in-memory stores and the real {@link Alerts}. T-eval: a
 * payment to a merchant over its refund or chargeback ratio raises ONE {@code MERCHANT_ABUSE} alert on the merchant per
 * (merchant, rule, D30 bucket) however many payments meet it - the payment itself permitted (the merchant's ratio refuses
 * no customer); a raise that fails behind its savepoint leaves the evaluation and the verdict intact; a session
 * confirming many checkouts is stepped up. The profile leg: a party's own ratios and its BNPL applications across
 * merchants raise ONE {@code FRAUD} alert each on the party, judged twice raise once, and move the rating credit's
 * signal answers. Every alert {@code OPEN} - no case before `-016`. The database's arbiters and the concurrency are the
 * owed {@code MerchantAbuseDatabaseTest}'s (C5).
 */
@DisplayName("payment, merchant and BNPL abuse: alerted once per subject, rule and window (P13-TSK-014)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class MerchantAbuseTest {

    private static final Instant NOW = CLOCK.instant();
    private static final Instant BUCKET = WindowStarts.start(WindowKind.D30, NOW);

    private final RiskEvaluationWorld world = new RiskEvaluationWorld();
    private final Map<LedgerAccountId, RiskPaymentRiskDecision.Accounts.Account> ledger = new HashMap<>();
    private final RiskPaymentRiskDecision seam = new RiskPaymentRiskDecision(world.evaluation,
            (unitOfWork, account) -> ledger.get(account));

    private record Merchant(UUID id, LedgerAccountId payable) {}

    // ------------------------------------------------------------------ T-eval: the merchant

    @Test
    @DisplayName("a card payment to a merchant with 25 refunds over 50 counted payments this D30 bucket: REFUND_RATIO fires"
            + " (20 %, at least 20), the payment is PERMITTED, and ONE MERCHANT_ABUSE alert is born OPEN - the merchant,"
            + " REFUND_RATIO, the bucket, MEDIUM, the assessment as evidence")
    void aMerchantOverItsRefundRatioIsAlertedAndThePaymentStands() {
        world.activate(RiskOperation.CARD_PAYMENT);
        Merchant shop = merchant();
        counted(VelocityDimension.MERCHANT, shop.id(), 50);
        refunds(shop.id(), 25);

        UUID payer = IDS.next();
        assertThat(pay(payer, shop, InstrumentKind.CARD_TOKEN)).isEqualTo(PaymentVerdict.permit());
        RiskAssessment sealed = last();
        assertThat(hit(sealed, RuleKind.REFUND_RATIO).outcome()).isEqualTo(RuleOutcome.FIRED);
        assertThat(hit(sealed, RuleKind.REFUND_RATIO).judged()).isEqualTo("refunds:D30:num=25,den=50");
        assertThat(sealed.decision()).isEqualTo(RiskDecision.ALLOW);

        RiskAlert alert = onlyAlert();
        assertThat(alert.kind()).isEqualTo(AlertKind.MERCHANT_ABUSE);
        assertThat(alert.kind().caseKind()).contains(com.finapp.risk.CaseKind.MERCHANT_ABUSE);
        assertThat(alert.subject()).isEqualTo(SignalSubject.of(SubjectKind.MERCHANT, shop.id()));
        assertThat(alert.rule()).isEqualTo(AlertRule.of(RuleKind.REFUND_RATIO));
        assertThat(alert.window().start()).isEqualTo(BUCKET);
        assertThat(alert.severity()).isEqualTo(AlertSeverity.MEDIUM);
        assertThat(alert.status()).isEqualTo(AlertStatus.OPEN);
        assertThat(alert.evidence().assessments()).containsExactly(sealed.id());
        // INV-RSK-08: the announcement carries ids and codes - never the ratio, the payer or an amount.
        Map<String, String> raised = world.payloads.get(indexOf(Alerts.EVENT_TYPE));
        assertThat(raised.keySet()).containsExactlyInAnyOrder("alertId", "kind", "severity", "subjectKind", "subjectRef");
        assertThat(raised.values()).doesNotContain(payer.toString(), "25", "50");
    }

    @Test
    @DisplayName("the same pattern judged twice - a second payment to the merchant, by card and from a wallet - raises once:"
            + " one alert, one AlertRaised; a chargeback ratio over its own floor is a second rule, a second alert (HIGH)")
    void theSamePatternJudgedTwiceRaisesOnce() {
        world.activate(RiskOperation.CARD_PAYMENT);
        world.activate(RiskOperation.WALLET_PAYMENT);
        Merchant shop = merchant();
        counted(VelocityDimension.MERCHANT, shop.id(), 40);
        refunds(shop.id(), 20);

        assertThat(pay(IDS.next(), shop, InstrumentKind.CARD_TOKEN)).isEqualTo(PaymentVerdict.permit());
        assertThat(pay(IDS.next(), shop, InstrumentKind.WALLET)).isEqualTo(PaymentVerdict.permit());
        assertThat(world.alertStore.all()).hasSize(1);
        assertThat(world.alertsAnnounced()).isEqualTo(1);

        for (int i = 0; i < 10; i++) {
            world.signalStore.add(abuse(SignalKind.CHARGEBACK_RECEIVED, IDS.next(), shop.id(), BUCKET.plusSeconds(i)));
        }
        assertThat(pay(IDS.next(), shop, InstrumentKind.CARD_TOKEN)).isEqualTo(PaymentVerdict.permit());
        assertThat(world.alertStore.all()).extracting(RiskAlert::rule)
                .containsExactlyInAnyOrder(AlertRule.of(RuleKind.REFUND_RATIO), AlertRule.of(RuleKind.CHARGEBACK_RATIO));
        assertThat(world.alertStore.all()).filteredOn(a -> a.rule().equals(AlertRule.of(RuleKind.CHARGEBACK_RATIO)))
                .singleElement().extracting(RiskAlert::severity).isEqualTo(AlertSeverity.HIGH);
        assertThat(world.alertsAnnounced()).isEqualTo(2);
    }

    @Test
    @DisplayName("a raise that fails behind its savepoint - a timeout, a failed read - leaves the payment's evaluation, its"
            + " seal and its verdict intact; reported unraised; no alert; the next payment raises it")
    void aFailedRaiseLeavesTheEvaluationIntact() {
        world.activate(RiskOperation.CARD_PAYMENT);
        Merchant shop = merchant();
        counted(VelocityDimension.MERCHANT, shop.id(), 50);
        refunds(shop.id(), 30);
        world.failRaise.push(RiskEvaluationWorld.timeout());

        assertThat(pay(IDS.next(), shop, InstrumentKind.CARD_TOKEN)).isEqualTo(PaymentVerdict.permit());
        assertThat(world.savepoints.subList(world.savepoints.size() - 2, world.savepoints.size()))
                .containsExactly("open 250ms", "undo");
        assertThat(world.unraised).singleElement().asString().endsWith(" TIMEOUT");
        assertThat(world.alertStore.all()).isEmpty();
        assertThat(last().decision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(world.decisionsAnnounced()).isEqualTo(1);

        assertThat(pay(IDS.next(), shop, InstrumentKind.CARD_TOKEN)).isEqualTo(PaymentVerdict.permit());
        assertThat(world.alertStore.all()).hasSize(1);
    }

    @Test
    @DisplayName("below the floor or below the share, or with no counted payment this bucket, nothing fires and nothing is"
            + " raised")
    void belowTheThresholdNothingIsRaised() {
        world.activate(RiskOperation.CARD_PAYMENT);
        Merchant few = merchant();
        counted(VelocityDimension.MERCHANT, few.id(), 20);
        refunds(few.id(), 19);
        Merchant small = merchant();
        counted(VelocityDimension.MERCHANT, small.id(), 1_000);
        refunds(small.id(), 199);
        Merchant fresh = merchant();
        refunds(fresh.id(), 40);
        for (Merchant merchant : new Merchant[] {few, small, fresh}) {
            assertThat(pay(IDS.next(), merchant, InstrumentKind.CARD_TOKEN)).isEqualTo(PaymentVerdict.permit());
        }
        assertThat(hit(last(), RuleKind.REFUND_RATIO).outcome()).as("no denominator: ABSENT").isEqualTo(
                RuleOutcome.ABSENT);
        assertThat(world.alertStore.all()).isEmpty();
    }

    @Test
    @DisplayName("a session confirming five checkouts within the hour: the sixth is stepped up - unsatisfied on a password"
            + " session, refused; counted under the session's own window, the session sealed beside the subjects")
    void aSessionConfirmingManyCheckoutsIsChallenged() throws Exception {
        world.activate(RiskOperation.CARD_PAYMENT);
        UUID party = IDS.next();
        UUID identity = IDS.next();
        Session session = RiskEvaluationWorld.session(identity, AssuranceLevel.PASSWORD, null);
        try (AutoCloseable presented = RiskEvaluationWorld.presenting(session)) {
            for (int i = 0; i < 5; i++) {
                assertThat(payAs(identity, party, merchant(), InstrumentKind.CARD_TOKEN)).isEqualTo(PaymentVerdict.permit());
            }
            assertThat(world.count(VelocityDimension.SESSION, session.id().value(), WindowKind.H1)).isEqualTo(5);
            assertThat(payAs(identity, party, merchant(), InstrumentKind.CARD_TOKEN)).isEqualTo(PaymentVerdict.refuse());
        }
        RiskAssessment sixth = last();
        assertThat(sixth.decision()).isEqualTo(RiskDecision.CHALLENGE);
        assertThat(sixth.subjects().session()).contains(session.id().value());
        assertThat(world.count(VelocityDimension.SESSION, session.id().value(), WindowKind.H1))
                .as("a refused confirmation counts nothing").isEqualTo(5);
        assertThat(world.alertStore.all()).as("a velocity rule raises no alert").isEmpty();
    }

    // ------------------------------------------------------------------ the profile leg: the party

    @Test
    @DisplayName("the profile leg: a party's 3 refunds over its 10 counted movements (30 %) fire the monitoring REFUND_RATIO"
            + " - one FRAUD alert on the PARTY, the bucket, the latest refund as evidence; recomputed again, the same row")
    void aPartysOwnRefundRatioIsAlertedOnce() {
        world.activate(RiskOperation.MONITORING);
        UUID party = IDS.next();
        counted(VelocityDimension.PARTY, party, 10);
        RiskSignal latest = null;
        for (int i = 0; i < 3; i++) {
            latest = abuse(SignalKind.REFUND_COMPLETED, party, IDS.next(), BUCKET.plus(Duration.ofHours(i)));
            world.signalStore.add(latest);
        }
        ProfileRecompute leg = world.profileLeg();
        leg.recompute(UNTOUCHABLE, party, CorrelationId.of("sweep-1"));
        leg.recompute(UNTOUCHABLE, party, CorrelationId.of("sweep-2"));

        RiskAlert alert = onlyAlert();
        assertThat(alert.kind()).isEqualTo(AlertKind.FRAUD);
        assertThat(alert.subject()).isEqualTo(SignalSubject.of(SubjectKind.PARTY, party));
        assertThat(alert.rule()).isEqualTo(AlertRule.of(RuleKind.REFUND_RATIO));
        assertThat(alert.window().start()).isEqualTo(BUCKET);
        assertThat(alert.evidence().signals()).containsExactly(latest.id());
        assertThat(alert.evidence().assessments()).isEmpty();
        assertThat(world.alertsAnnounced()).isEqualTo(1);
        assertThat(world.profileStore.head(party).orElseThrow().rating()).as("200 alone keeps the party LOW")
                .contains(RiskRating.LOW);
    }

    @Test
    @DisplayName("BNPL applications across merchants: three within seven days fire BNPL_MULTI_APPLICATION - one FRAUD alert,"
            + " and with its weight the party's rating moves to MEDIUM - credit's signal, never a seam")
    void bnplApplicationsAcrossMerchantsFeedTheRating() {
        world.activate(RiskOperation.MONITORING);
        UUID party = IDS.next();
        counted(VelocityDimension.PARTY, party, 10);
        for (int i = 0; i < 3; i++) {
            world.signalStore.add(abuse(SignalKind.REFUND_COMPLETED, party, IDS.next(), BUCKET.plusSeconds(i)));
            world.signalStore.add(abuse(SignalKind.BNPL_APPLICATION_SUBMITTED, party, null,
                    NOW.minus(Duration.ofDays(1 + i))));
        }
        ProfileRecompute.Outcome outcome = world.profileLeg().recompute(UNTOUCHABLE, party, CorrelationId.of("sweep"));

        assertThat(world.alertStore.all()).extracting(RiskAlert::rule).containsExactlyInAnyOrder(
                AlertRule.of(RuleKind.REFUND_RATIO), AlertRule.of(RuleKind.BNPL_MULTI_APPLICATION));
        assertThat(world.alertStore.all()).allSatisfy(alert -> {
            assertThat(alert.kind()).isEqualTo(AlertKind.FRAUD);
            assertThat(alert.subject()).isEqualTo(SignalSubject.of(SubjectKind.PARTY, party));
        });
        assertThat(outcome.kind()).isEqualTo(ProfileRecompute.Kind.CHANGED);
        assertThat(outcome.rating()).as("200 + 200 = 400: MEDIUM").contains(RiskRating.MEDIUM);
    }

    @Test
    @DisplayName("a failed raise in the profile leg fails the recompute whole - no version, no alert - for the deferral")
    void aFailedRaiseFailsTheRecomputeWhole() {
        world.activate(RiskOperation.MONITORING);
        UUID party = IDS.next();
        for (int i = 0; i < 4; i++) {
            world.signalStore.add(abuse(SignalKind.BNPL_APPLICATION_SUBMITTED, party, null, NOW.minusSeconds(60 + i)));
        }
        world.failRaise.push(new IllegalStateException("the alert store is gone"));
        assertThatThrownBy(() -> world.profileLeg().recompute(UNTOUCHABLE, party, CorrelationId.of("sweep")))
                .isInstanceOf(ProfileRecompute.RecomputeFailed.class);
        assertThat(world.alertStore.all()).isEmpty();
    }

    // ------------------------------------------------------------------ the shapes

    private Merchant merchant() {
        Merchant merchant = new Merchant(IDS.next(), LedgerAccountId.of(IDS.next()));
        ledger.put(merchant.payable(), new RiskPaymentRiskDecision.Accounts.Account(AccountPurpose.MERCHANT_PAYABLE,
                Optional.of(merchant.id())));
        return merchant;
    }

    private PaymentVerdict pay(UUID party, Merchant merchant, InstrumentKind instrument) {
        return payAs(IDS.next(), party, merchant, instrument);
    }

    private PaymentVerdict payAs(UUID identity, UUID party, Merchant merchant, InstrumentKind instrument) {
        Optional<LedgerAccountId> wallet = Optional.empty();
        if (instrument == InstrumentKind.WALLET) {
            LedgerAccountId ledgerAccount = LedgerAccountId.of(IDS.next());
            ledger.put(ledgerAccount, new RiskPaymentRiskDecision.Accounts.Account(AccountPurpose.CUSTOMER_WALLET,
                    Optional.of(IDS.next())));
            wallet = Optional.of(ledgerAccount);
        }
        PaymentRiskDecision.Judged payment = new PaymentRiskDecision.Judged(PaymentIntentId.next(IDS), party,
                instrument, wallet, merchant.payable(), eur(25_00));
        return as(identity, () -> seam.check(UNTOUCHABLE, payment));
    }

    private static <T> T as(UUID identity, Supplier<T> call) {
        try (SecurityContext.Scope actor = SecurityContext.enter(new Actor(identity.toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return call.get();
        }
    }

    private void counted(VelocityDimension dimension, UUID subject, long count) {
        VelocityWindowKey key = new VelocityWindowKey(dimension, subject.toString(), WindowKind.D30, BUCKET);
        world.windowStore.rows.put(key, new VelocityWindow(key, count, eur(count * 25_00), NOW));
    }

    private void refunds(UUID merchant, int count) {
        for (int i = 0; i < count; i++) {
            world.signalStore.add(abuse(SignalKind.REFUND_COMPLETED, IDS.next(), merchant, BUCKET.plusSeconds(i)));
        }
    }

    /** An abuse signal as the intake records it: about the party, the merchant and the intent by reference. */
    private static RiskSignal abuse(SignalKind kind, UUID party, UUID merchant, Instant observedAt) {
        SignalAttributes attributes = kind == SignalKind.BNPL_APPLICATION_SUBMITTED
                ? SignalAttributes.of(kind, Map.of("product", "BNPL_PAY_IN_N"))
                : SignalAttributes.of(kind, Map.of("merchant_subject_ref", merchant.toString(), "intent_ref",
                        IDS.next().toString()));
        return new RiskSignal(RiskSignalId.next(IDS), kind, SignalSubject.of(SubjectKind.PARTY, party),
                new RiskSignal.SourceEvent(EventId.next(IDS), kind.sourceEventType(), 1,
                        kind.sourceEventType().substring(0, kind.sourceEventType().indexOf('.')),
                        CorrelationId.of("flow"), CausationId.of("cause")),
                observedAt, attributes);
    }

    private RiskAssessment last() {
        return world.assessmentStore.all().get(world.assessmentStore.all().size() - 1);
    }

    private static RuleHit hit(RiskAssessment sealed, RuleKind kind) {
        return sealed.hits().stream().filter(hit -> hit.kind() == kind).findFirst().orElseThrow();
    }

    private RiskAlert onlyAlert() {
        assertThat(world.alertStore.all()).hasSize(1);
        return world.alertStore.all().get(0);
    }

    private int indexOf(String eventType) {
        for (int i = 0; i < world.envelopes.size(); i++) {
            if (world.envelopes.get(i).eventType().equals(eventType)) {
                return i;
            }
        }
        throw new AssertionError("no " + eventType + " announced");
    }
}
