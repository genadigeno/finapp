package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxKey;
import com.finapp.platform.inbox.JdbcInboxRecordStore;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.SimulatedInstance;
import com.finapp.risk.CommandRef;
import com.finapp.risk.JdbcRiskPolicyStore;
import com.finapp.risk.ProfileRecompute;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskEvaluation;
import com.finapp.risk.RiskEvaluationRequest;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicyAdministration;
import com.finapp.risk.WindowKind;
import com.finapp.risk.WindowStarts;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Payment, merchant and BNPL abuse against a live PostgreSQL, through the composition root's own wiring (`P13-TSK-014`;
 * {@code PHASE_13_PLAN.md} sections 7.4, 12.6, scenarios 1 and 2; ADR-0110; {@code INV-RSK-06}, {@code INV-RSK-08},
 * {@code INV-EVT-04}): payments' refund and chargeback events and bnpl's application events through risk's real inbox
 * handlers on simulated instances, attributed from the assessments the composition's own {@link RiskEvaluation} sealed
 * for each payment (the checkout seam's own T-eval call - the seam's mapping and the real confirmation are
 * {@code RiskPaymentRiskDecisionTest}'s and {@code PaymentRiskSeamDatabaseTest}'s); the profile leg's real recompute. A
 * merchant over its refund ratio is alerted once, however many payments and instances meet it; a chargeback ratio once
 * per window, a duplicated dispute event counting once; a replayed refund records nothing new; BNPL applications
 * across merchants alert once; a session confirming many checkouts is challenged. <strong>Written and owed</strong>
 * (owner decision C5: the database tiers are skipped per task).
 *
 * <p><strong>Its own container</strong> ({@code own-container}): it activates the C8 EUR {@code CARD_PAYMENT},
 * {@code WALLET_PAYMENT} and {@code MONITORING} versions, after which every checkout payment and every profile in the
 * database is judged by them.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("payment, merchant and BNPL abuse on PostgreSQL: alerted once per subject, rule and window (P13-TSK-014)")
class MerchantAbuseDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    // Every identifier a UUIDv7, as the seams' own are (ADR-0013): a random v4 party or merchant made PartyId.of and
    // MerchantId.of throw inside T-eval's read - every payment a READ_FAILED fail-safe ALLOW judged by no rule, and the
    // profile leg's recompute failed outright (P13-TST-001's first run of this owed suite).
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Duration RETENTION = Duration.ofDays(14);
    private static final AtomicBoolean ACTIVATED = new AtomicBoolean();

    @Autowired private RiskEvaluation evaluation;
    @Autowired private ProfileRecompute profileRecompute;
    @Autowired @Qualifier("riskSignalRefundCompleted") private RiskSignalIntake refundCompleted;
    @Autowired @Qualifier("riskSignalChargebackReceived") private RiskSignalIntake chargebackReceived;
    @Autowired @Qualifier("riskSignalBnplApplicationSubmitted") private RiskSignalIntake applicationSubmitted;

    private final InboxConsumer<Connection> inbox = new InboxConsumer<>(new JdbcInboxRecordStore(), CLOCK, RETENTION);

    @BeforeEach
    void activate() {
        if (!ACTIVATED.compareAndSet(false, true)) {
            return;
        }
        RiskPolicyAdministration administration = new RiskPolicyAdministration(new JdbcRiskPolicyStore(),
                new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);
        for (RiskOperation operation : List.of(RiskOperation.CARD_PAYMENT, RiskOperation.WALLET_PAYMENT,
                RiskOperation.MONITORING)) {
            RiskPolicyAdministration.Proposed proposed = inOneTransaction(uow -> administration.propose(uow,
                    RiskPolicyFixtures.v1(operation), "the abuse suite's version", employee(),
                    CorrelationId.generate(IDS)));
            inOneTransaction(uow -> administration.activate(uow, proposed.id(), employee(), "activated",
                    CorrelationId.generate(IDS)));
        }
    }

    // ------------------------------------------------------------------ the merchant's refund ratio

    @Test
    @DisplayName("a merchant's refund ratio crossing its threshold raises ONE MERCHANT_ABUSE alert: 25 refunds of its 30"
            + " judged payments, attributed from their sealed assessments, then its next payment - the alert ATTACHED to"
            + " the merchant's MERCHANT_ABUSE case (P13-TSK-016), the"
            + " merchant, REFUND_RATIO, the D30 bucket, the payment permitted")
    void aRefundRatioOverTheThresholdRaisesOneMerchantAbuseAlert() throws Exception {
        UUID merchant = IDS.next();
        List<UUID> intents = paidTo(merchant, 30);
        for (UUID intent : intents.subList(0, 25)) {
            assertThat(deliver(refundCompleted, refund(intent))).isEqualTo(InboxConsumer.Outcome.PROCESSED);
        }
        assertThat(count("SELECT count(*) FROM risk.risk_signal WHERE kind = 'REFUND_COMPLETED' AND merchant_subject_ref = ?",
                merchant)).as("each refund attributed to the merchant its payment's assessment named").isEqualTo(25);

        RiskEvaluation.Assessed next = pay(merchant, IDS.next(), IDS.next());
        assertThat(next.permits()).as("the merchant's ratio refuses no customer").isTrue();
        assertThat(column("SELECT kind || ',' || status || ',' || subject_kind || ',' || rule_code || ',' || window_kind"
                + " FROM risk.risk_alert WHERE subject_ref = ?", merchant.toString()))
                .containsExactly("MERCHANT_ABUSE,ATTACHED,MERCHANT,REFUND_RATIO,D30");
        assertThat(column("SELECT kind || ',' || subject_kind || ',' || status FROM risk.risk_case WHERE subject_ref = ?",
                merchant.toString())).as("the merchant's case (P13-TSK-016)")
                .containsExactly("MERCHANT_ABUSE,MERCHANT,OPEN");
        assertThat(count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref = ? AND window_start = ?"
                + " AND ? = ANY (evidence_assessment_ids)", merchant.toString(),
                java.sql.Timestamp.from(WindowStarts.start(WindowKind.D30, Instant.now(CLOCK))),
                next.assessmentId().value())).isEqualTo(1);
        assertThat(alertsRaised(merchant)).isEqualTo(1);
    }

    @Test
    @DisplayName("ten concurrent payments to a merchant over its ratio, on ten instances, raise ONE alert and one"
            + " AlertRaised - serialised on the merchant's R2a lock, the unique beneath")
    void tenConcurrentConfirmationsOverTheRatioRaiseOneAlert() throws Exception {
        UUID merchant = IDS.next();
        List<UUID> intents = paidTo(merchant, 40);
        for (UUID intent : intents.subList(0, 30)) {
            deliver(refundCompleted, refund(intent));
        }
        List<Callable<RiskEvaluation.Assessed>> payments = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            payments.add(() -> pay(merchant, IDS.next(), IDS.next()));
        }
        List<RiskEvaluation.Assessed> answers = race(payments);
        assertThat(answers).allMatch(RiskEvaluation.Assessed::permits);
        assertThat(alerts(merchant, "REFUND_RATIO")).isEqualTo(1);
        assertThat(alertsRaised(merchant)).isEqualTo(1);
        assertThat(count("SELECT count FROM risk.velocity_window WHERE dimension = 'MERCHANT' AND subject_ref = ?"
                + " AND window_kind = 'D30' AND window_start = ?", merchant.toString(),
                java.sql.Timestamp.from(WindowStarts.start(WindowKind.D30, Instant.now(CLOCK)))))
                .as("forty, then ten more, each counted once").isEqualTo(50);
    }

    @Test
    @DisplayName("a replayed refund event - redelivered past the inbox's dedupe - records nothing new: the unique on its"
            + " source event absorbs it, no second signal, no second announcement, the ratio unmoved")
    void aReplayedRefundEventRecordsNothingNew() throws Exception {
        UUID merchant = IDS.next();
        UUID intent = paidTo(merchant, 1).get(0);
        ReceivedEvent refund = refund(intent);
        assertThat(deliver(refundCompleted, refund)).isEqualTo(InboxConsumer.Outcome.PROCESSED);
        assertThat(deliver(refundCompleted, refund)).isEqualTo(InboxConsumer.Outcome.SKIPPED_DUPLICATE);
        forgetDedupe(refund);
        assertThat(deliver(refundCompleted, refund)).isEqualTo(InboxConsumer.Outcome.PROCESSED);
        assertThat(count("SELECT count(*) FROM risk.risk_signal WHERE source_event_id = ?", refund.eventId().value()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'risk.RiskSignalRecorded'"
                + " AND causation_id = ?", refund.eventId().value().toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'risk.RiskSignalRecorded'"
                + " AND convert_from(payload, 'UTF8') LIKE ?", "%" + merchant + "%"))
                .as("INV-RSK-08: the announcement names the payer, never the merchant").isZero();
    }

    // ------------------------------------------------------------------ the chargeback ratio

    @Test
    @DisplayName("a chargeback ratio over its threshold alerts once per window: ten chargebacks of a hundred payments,"
            + " the merchant's next payments meet one CHARGEBACK_RATIO alert (HIGH)")
    void aChargebackRatioOverTheThresholdAlertsOncePerWindow() throws Exception {
        UUID merchant = IDS.next();
        List<UUID> intents = paidTo(merchant, 100);
        for (UUID intent : intents.subList(0, 10)) {
            deliver(chargebackReceived, chargeback(IDS.next(), intent));
        }
        pay(merchant, IDS.next(), IDS.next());
        pay(merchant, IDS.next(), IDS.next());
        assertThat(column("SELECT rule_code || ',' || severity FROM risk.risk_alert WHERE subject_ref = ?",
                merchant.toString()))
                .containsExactly("CHARGEBACK_RATIO,HIGH");
    }

    @Test
    @DisplayName("duplicate dispute events count once: the same ChargebackReceived on two instances at once and again"
            + " later is one signal - nine distinct chargebacks and a duplicated tenth stay below the floor of ten")
    void duplicateDisputeEventsCountOnce() throws Exception {
        UUID merchant = IDS.next();
        List<UUID> intents = paidTo(merchant, 100);
        for (UUID intent : intents.subList(0, 9)) {
            deliver(chargebackReceived, chargeback(IDS.next(), intent));
        }
        ReceivedEvent tenth = chargeback(IDS.next(), intents.get(9));
        List<InboxConsumer.Outcome> outcomes = race(List.of(() -> deliver(chargebackReceived, tenth),
                () -> deliver(chargebackReceived, tenth)));
        assertThat(outcomes).containsOnlyOnce(InboxConsumer.Outcome.PROCESSED);
        deliver(chargebackReceived, tenth);
        assertThat(count("SELECT count(*) FROM risk.risk_signal WHERE kind = 'CHARGEBACK_RECEIVED' AND merchant_subject_ref = ?",
                merchant)).isEqualTo(10);

        UUID other = IDS.next();
        List<UUID> others = paidTo(other, 100);
        for (UUID intent : others.subList(0, 9)) {
            deliver(chargebackReceived, chargeback(IDS.next(), intent));
        }
        ReceivedEvent duplicated = chargeback(IDS.next(), others.get(9));
        deliver(chargebackReceived, duplicated);
        forgetDedupe(duplicated);
        deliver(chargebackReceived, duplicated);
        pay(other, IDS.next(), IDS.next());
        assertThat(alerts(other, "CHARGEBACK_RATIO")).as("ten distinct chargebacks: at the floor").isEqualTo(1);
    }

    // ------------------------------------------------------------------ BNPL and the session

    @Test
    @DisplayName("ten BNPL applications across merchants within the week: ten signals about the applicant, the profile"
            + " leg raises ONE FRAUD alert BNPL_MULTI_APPLICATION, swept again still one - and the rating moves")
    void tenApplicationsAcrossMerchantsAlertOnce() throws Exception {
        UUID party = IDS.next();
        for (int i = 0; i < 10; i++) {
            deliver(applicationSubmitted, application(party, Instant.now(CLOCK).minus(Duration.ofHours(i))));
        }
        assertThat(count("SELECT count(*) FROM risk.risk_signal WHERE kind = 'BNPL_APPLICATION_SUBMITTED'"
                + " AND subject_kind = 'PARTY' AND subject_ref = ?", party.toString())).isEqualTo(10);
        sweep(party);
        sweep(party);
        assertThat(column("SELECT kind || ',' || subject_kind || ',' || rule_code FROM risk.risk_alert"
                + " WHERE subject_ref = ?", party.toString())).containsExactly("FRAUD,PARTY,BNPL_MULTI_APPLICATION");
        assertThat(column("SELECT rating FROM risk.customer_risk_profile WHERE party_id = ?", party))
                .as("the weight moves the rating credit's signal answers").isNotEmpty();
    }

    @Test
    @DisplayName("a session confirming many checkouts is challenged: five confirmations in the hour count five in the"
            + " SESSION window; the sixth is CHALLENGE, unsatisfied, counting nothing; the session sealed on each")
    void aSessionConfirmingManyCheckoutsIsChallenged() throws Exception {
        UUID session = IDS.next();
        for (int i = 0; i < 5; i++) {
            assertThat(pay(IDS.next(), IDS.next(), session).decision()).isEqualTo(RiskDecision.ALLOW);
        }
        RiskEvaluation.Assessed sixth = pay(IDS.next(), IDS.next(), session);
        assertThat(sixth.decision()).isEqualTo(RiskDecision.CHALLENGE);
        assertThat(sixth.permits()).isFalse();
        assertThat(count("SELECT sum(count) FROM risk.velocity_window WHERE dimension = 'SESSION' AND subject_ref = ?"
                + " AND window_kind = 'H1'", session.toString())).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE session_id = ?", session)).isEqualTo(6);
    }

    // ------------------------------------------------------------------ the shapes

    /** {@code payments} card payments to {@code merchant}, each judged and committed; their intents. */
    private List<UUID> paidTo(UUID merchant, int payments) throws SQLException {
        List<UUID> intents = new ArrayList<>();
        for (int i = 0; i < payments; i++) {
            UUID intent = IDS.next();
            evaluate(merchant, intent, null);
            intents.add(intent);
        }
        return intents;
    }

    private RiskEvaluation.Assessed pay(UUID merchant, UUID intent, UUID session) throws SQLException {
        return evaluate(merchant, intent, session);
    }

    /** One checkout payment's T-eval, as the seam calls it inside the confirmation's Tx1 - committed. */
    private RiskEvaluation.Assessed evaluate(UUID merchant, UUID intent, UUID session) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            RiskEvaluationRequest.Builder request = RiskEvaluationRequest.builder(RiskOperation.CARD_PAYMENT,
                    CommandRef.of(intent), Money.ofMinorUnits(25_00, EUR), IDS.next(), CorrelationId.generate(IDS),
                    CausationId.of("p13-tsk-014")).merchant(merchant);
            if (session != null) {
                request.session(session);
            }
            // The seam's caller's principal (P13-TSK-016: the folding opens a case under the acting principal).
            RiskEvaluation.Assessed assessed;
            try (com.finapp.platform.security.SecurityContext.Scope payer =
                    com.finapp.platform.security.SecurityContext.enter(new com.finapp.platform.security.Actor(
                            "customer-" + intent, com.finapp.platform.security.ActorType.CUSTOMER))) {
                assessed = evaluation.evaluate(app, request.build());
            }
            app.commit();
            return assessed;
        }
    }

    private InboxConsumer.Outcome deliver(RiskSignalIntake intake, ReceivedEvent event) throws SQLException {
        try (SimulatedInstance instance = SimulatedInstance.inAgreementWithTheServer();
                CorrelationContext.Scope scope = CorrelationContext.enter(
                        new Correlation(event.correlationId(), CausationId.of(event.eventId().value().toString())))) {
            InboxConsumer.Outcome outcome = inbox.consume(instance.connection(), new InboxKey(intake.consumerName(),
                    event.eventId().value().toString()), event.eventType(), connection -> intake.handle(connection, event));
            if (outcome == InboxConsumer.Outcome.CONTENDED) {
                instance.rollback();
            } else {
                instance.commit();
            }
            return outcome;
        }
    }

    private void sweep(UUID party) {
        // As the schedule runs it: the platform's scope (P13-TSK-016 - the leg's folds open cases under SYSTEM).
        try (com.finapp.platform.security.SecurityContext.Scope system =
                com.finapp.platform.security.SecurityContext.enterSystem()) {
            inOneTransaction(uow -> profileRecompute.recompute(uow, party, CorrelationId.generate(IDS)));
        }
    }

    private static ReceivedEvent refund(UUID intent) {
        UUID refund = IDS.next();
        return new ReceivedEvent(EventId.next(IDS), "payments.RefundCompleted", 1, 1, refund, "refund",
                Instant.now(CLOCK), "payments", CorrelationId.of(IDS.next().toString()),
                CausationId.of("p13-tsk-014"), "application/json", EventPayload.of().with("status", "COMPLETED")
                        .with("intentId", intent.toString()).with("attemptId", IDS.next().toString()).toBytes());
    }

    private static ReceivedEvent chargeback(UUID dispute, UUID intent) {
        return new ReceivedEvent(EventId.next(IDS), "payments.ChargebackReceived", 1, 1, dispute, "dispute",
                Instant.now(CLOCK), "payments", CorrelationId.of(IDS.next().toString()),
                CausationId.of("p13-tsk-014"), "application/json", EventPayload.of().with("stage", "CHARGED_BACK")
                        .with("disputeId", dispute.toString()).with("attemptId", IDS.next().toString())
                        .with("intentId", intent.toString()).toBytes());
    }

    private static ReceivedEvent application(UUID party, Instant occurredAt) {
        UUID application = IDS.next();
        return new ReceivedEvent(EventId.next(IDS), "bnpl.BnplApplicationSubmitted", 1, 1, application,
                "bnpl_application", occurredAt, "bnpl", CorrelationId.of(IDS.next().toString()),
                CausationId.of("p13-tsk-014"), "application/json", EventPayload.of()
                        .with("applicationId", application.toString()).with("partyId", party.toString())
                        .with("product", "BNPL_PAY_IN_N").with("plan", "PAY_IN_3")
                        .with("checkoutSessionId", IDS.next().toString())
                        .with("decisionRequestId", IDS.next().toString())
                        .with("termsVersionId", IDS.next().toString()).with("termsVersion", "1").toBytes());
    }

    private static void forgetDedupe(ReceivedEvent event) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement forget = migrator.prepareStatement(
                        "DELETE FROM platform.inbox_message WHERE consumer = ? AND dedupe_key = ?")) {
            forget.setString(1, RiskSignalIntake.CONSUMER);
            forget.setString(2, event.eventId().value().toString());
            assertThat(forget.executeUpdate()).as("the dedupe record existed").isEqualTo(1);
        }
    }

    private static long alerts(UUID subject, String rule) throws SQLException {
        return count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref = ? AND rule_code = ?", subject.toString(),
                rule);
    }

    private static long alertsRaised(UUID subject) throws SQLException {
        return count("SELECT count(*) FROM platform.outbox_event e WHERE e.event_type = 'risk.AlertRaised'"
                + " AND EXISTS (SELECT 1 FROM risk.risk_alert a WHERE a.id = e.aggregate_id AND a.subject_ref = ?)",
                subject.toString());
    }

    /** A count, each argument bound as itself - a UUID for a uuid column, its text for a text one. */
    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                select.setObject(i + 1, arguments[i]);
            }
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static List<String> column(String sql, Object... arguments) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                select.setObject(i + 1, arguments[i]);
            }
            List<String> values = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
            return values;
        }
    }

    private static <R> R inOneTransaction(Function<Connection, R> work) {
        try (Connection connection = DatabaseRoles.application();
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            connection.setAutoCommit(false);
            R result = work.apply(connection);
            connection.commit();
            return result;
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Actor employee() {
        return new Actor("employee-" + IDS.next(), ActorType.EMPLOYEE);
    }

    private static <T> List<T> race(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> answers = new ArrayList<>();
            for (Future<T> future : futures) {
                answers.add(future.get(90, TimeUnit.SECONDS));
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }
}
