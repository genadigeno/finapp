package com.finapp.app.payments;

import static com.finapp.app.payments.HeldWithdrawalFixtures.CLOCK;
import static com.finapp.app.payments.HeldWithdrawalFixtures.EUR;
import static com.finapp.app.payments.HeldWithdrawalFixtures.IDS;
import static com.finapp.app.payments.HeldWithdrawalFixtures.assertNoRiskWord;
import static com.finapp.app.payments.HeldWithdrawalFixtures.column;
import static com.finapp.app.payments.HeldWithdrawalFixtures.count;
import static com.finapp.app.payments.HeldWithdrawalFixtures.eur;
import static com.finapp.app.payments.HeldWithdrawalFixtures.field;
import static com.finapp.app.payments.HeldWithdrawalFixtures.someKey;
import static com.finapp.app.payments.HeldWithdrawalFixtures.windowCount;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.payments.HeldWithdrawalFixtures.Customer;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.paymentmethods.SimulatedTokenisationAdapter;
import com.finapp.payments.CaptureHoldStore;
import com.finapp.payments.HeldPayments;
import com.finapp.payments.PaymentAttemptStore;
import com.finapp.payments.PaymentConfirmation;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentIntentStore;
import com.finapp.payments.PaymentParticipants;
import com.finapp.payments.PaymentProvider;
import com.finapp.payments.PaymentVerdict;
import com.finapp.payments.ProviderEvidenceStore;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.TransactionRunner;
import com.finapp.payments.Withdrawals;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.risk.JdbcRiskPolicyStore;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicy;
import com.finapp.risk.RiskPolicyAdministration;
import com.finapp.risk.RiskRule;
import com.finapp.risk.RuleKind;
import com.finapp.risk.RuleParameters;
import com.finapp.risk.VelocityDimension;
import com.finapp.risk.VelocityWindows;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import com.finapp.transfers.TransferCommand;
import com.finapp.transfers.TransferExecution;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Checkout payments judged by risk against a live PostgreSQL, through the composition root's own wiring
 * (`P13-TSK-010`; {@code PHASE_13_PLAN.md} sections 2.2 C1/C6, 7.2-7.4, 12.1, 12.3, 12.8, 14, scenarios 7, 9 and 14;
 * ADR-0060 section 3, ADR-0108; {@code INV-RSK-01}…{@code -04}, {@code INV-RSK-08}, {@code INV-CON-03},
 * {@code INV-IDEM-01}, {@code INV-LIFE-04}): the REAL checkout confirmation door, payments' confirmation judged in Tx1
 * under the intent by risk's real adapter. A {@code DENY} ends the intent {@code FAILED(RISK_REFUSED)} with no attempt,
 * no routing row, nothing sent and nothing counted, and ten re-confirmations are each refused; the customer and the
 * merchant read only the generic failure; one assessment per intent; the unavailable path's C1 band (below EUR 250 the
 * payment completes, at or above it is refused, both recorded); the door's {@code 403} writing nothing; ten confirmations
 * of one party counted exactly as their assessments; no lock cycle against a concurrent transfer and withdrawal of the
 * same party; slice 2's held card payment authorised and not captured, and a capture racing the deadline's void leaving
 * exactly one resolution. <strong>Written and owed</strong> (owner decision C5: the database tiers are skipped per task;
 * the owner-authorised database pass before the exit review runs it).
 *
 * <p><strong>Its own container</strong> ({@code own-container}): it activates EUR {@code CARD_PAYMENT} and
 * {@code WALLET_PAYMENT} versions, after which every merchant-bound card and wallet payment in the database is judged by
 * them - a shared container's USD checkouts would be refused by the open-ended fail-safe band, its EUR 600 ones
 * challenged. The first case runs before the activation (the production default); every other activates once and keys
 * its subjects fresh.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("checkout payments judged by risk: the seam in the real confirmation, the terminal refusal, the held card"
        + " payment (P13-TSK-010)")
class PaymentRiskSeamDatabaseTest {

    private static final AtomicBoolean ACTIVATED = new AtomicBoolean();
    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private Withdrawals engine;
    @Autowired private TransferExecution transferExecution;
    @Autowired private HeldPayments heldPayments;

    // The confirmation's own collaborators, for the scripted-HOLD race (the WithdrawalHoldDatabaseTest shape).
    @Autowired private TransactionRunner paymentTransactionRunner;
    @Autowired private PaymentIntentStore<Connection> paymentIntentStore;
    @Autowired private PaymentAttemptStore<Connection> paymentAttemptStore;
    @Autowired private ProviderEvidenceStore<Connection> providerEvidenceStore;
    @Autowired private PaymentParticipants<Connection> paymentParticipants;
    @Autowired private PaymentProvider paymentProvider;
    @Autowired private com.finapp.payments.PaymentOutcomes paymentOutcomes;
    @Autowired private com.finapp.payments.RoutingStore<Connection> routingStore;
    @Autowired private com.finapp.payments.PaymentRails paymentRails;
    @Autowired private AuditWriter<Connection> auditWriter;
    @Autowired private OutboxWriter<Connection> outboxWriter;
    @Autowired private CaptureHoldStore<Connection> captureHoldStore;

    private HeldWithdrawalFixtures fixtures;

    @BeforeAll
    static void startProvider() {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.paymentmethods.tokenisation.url", () -> provider.baseUrl());
        registry.add("finapp.paymentmethods.tokenisation.timeout", () -> "PT0.7S");
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add("finapp.payments.provider.timeout", () -> "PT0.7S");
        registry.add("finapp.payments.instant.url", () -> provider.baseUrl());
        registry.add("finapp.payments.instant.timeout", () -> "PT0.7S");
    }

    @BeforeEach
    void reset() {
        provider.reset();
        fixtures = new HeldWithdrawalFixtures(port, provider);
        fixtures.schemeAccepts();
        // One reference per CALL, as a PSP mints them: a per-test constant collided on the UNIQUE provider references
        // at a test's second authorisation (P13-TST-001's first run of this owed suite).
        provider.succeedsWith(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, 200,
                "{\"status\":\"approved\",\"reference\":\"psp_auth-{{randomValue type='UUID'}}\"}");
        provider.succeedsWith(SimulatedCardPspAdapter.CAPTURES_PATH, 200,
                "{\"status\":\"approved\",\"reference\":\"psp_cap-{{randomValue type='UUID'}}\"}");
        provider.succeedsWith(SimulatedCardPspAdapter.VOIDS_PATH, 200,
                "{\"status\":\"approved\",\"reference\":\"psp_void-{{randomValue type='UUID'}}\"}");
    }

    // ------------------------------------------------------------------ before any version

    @Test
    @Order(1)
    @DisplayName("scenario 9, no policy: the production default - ALLOW(NO_POLICY_ACTIVE) recorded and counted, the"
            + " card payment routed, authorised and captured")
    void noPolicyRecordsAllowAndThePaymentRoutes() throws Exception {
        assertThat(ACTIVATED).as("this case runs before the suite activates a version").isFalse();
        Merchant merchant = tradingMerchant();
        Payer payer = cardPayer();
        HttpResponse<String> confirmed = confirmByCard(payer, openSession(merchant, 40_00));
        assertThat(field(confirmed.body(), "status")).as(confirmed.body()).isEqualTo("COMPLETED");
        assertThat(column("SELECT decision || ',' || fail_safe_reason || ',' || velocity_outcome FROM"
                + " risk.risk_assessment WHERE operation = 'CARD_PAYMENT' AND party_id = ?", payer.customer().party()))
                .containsExactly("ALLOW,NO_POLICY_ACTIVE,COUNTED");
    }

    // ------------------------------------------------------------------ the refusal

    @Test
    @Order(2)
    @DisplayName("#aDeniedConfirmationCommitsRiskRefused: EUR 10,000 by card is a DENY - the intent FAILED with its"
            + " own RISK_REFUSED, no attempt, no routing row, no authorisation asked, nothing counted, sealed in Tx1")
    void aDeniedConfirmationCommitsRiskRefused() throws Exception {
        activatePolicies();
        Merchant merchant = tradingMerchant();
        Payer payer = cardPayer();
        HttpResponse<String> answered = confirmByCard(payer, openSession(merchant, 10_000_00));
        assertThat(answered.statusCode()).as(answered.body()).isEqualTo(200);
        UUID intent = intentOf(payer);
        assertThat(column("SELECT status || ',' || failure_reason FROM payments.payment_intent WHERE id = ?", intent))
                .containsExactly("FAILED,RISK_REFUSED");
        assertThat(count("SELECT count(*) FROM payments.payment_attempt WHERE intent_id = ?", intent)).isZero();
        assertThat(count("SELECT count(*) FROM payments.routing_decision WHERE intent_id = ?", intent)).isZero();
        assertThat(provider.requestCount(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH)).isZero();
        assertThat(column("SELECT decision || ',' || velocity_outcome FROM risk.risk_assessment WHERE command_ref = ?",
                intent.toString())).containsExactly("DENY,NOT_PERMITTED");
        assertThat(windowCount("PARTY", payer.customer().party(), "D1")).isZero();
        assertThat(count("SELECT count(*) FROM payments.payment_intent_event WHERE intent_id = ? AND from_status ="
                + " 'REQUIRES_CONFIRMATION' AND to_status = 'FAILED'", intent)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'payments.PaymentRiskRefused'"
                + " AND target_id = ? AND outcome = 'FAILED'", intent.toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'payments.PaymentFailed'"
                + " AND aggregate_id = ?", intent)).isEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("#aRiskRefusedIntentCannotBeReconfirmed: ten concurrent re-confirmations of a refused intent - every"
            + " one refused 409 payments.NotConfirmable with no risk word, nothing routed, one assessment")
    void aRiskRefusedIntentCannotBeReconfirmed() throws Exception {
        activatePolicies();
        Merchant merchant = tradingMerchant();
        Payer payer = cardPayer();
        String session = openSession(merchant, 10_000_00);
        confirmByCard(payer, session);
        UUID intent = intentOf(payer);
        List<HttpResponse<String>> retries = race(java.util.stream.IntStream.range(0, 10)
                .<Callable<HttpResponse<String>>>mapToObj(i -> () -> confirmByCard(payer, session)).toList());
        assertThat(retries).allSatisfy(retry -> {
            assertThat(retry.statusCode()).as(retry.body()).isEqualTo(409);
            assertThat(retry.body()).contains("payments.NotConfirmable");
            assertNoRiskWord(retry.body());
        });
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE command_ref = ?", intent.toString()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payments.payment_attempt WHERE intent_id = ?", intent)).isZero();
        assertThat(count("SELECT count(*) FROM payments.routing_decision WHERE intent_id = ?", intent)).isZero();
        assertThat(provider.requestCount(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH)).isZero();
    }

    @Test
    @Order(4)
    @DisplayName("#aMerchantSeesOnlyTheGenericRefusal: the customer's payment reads FAILED with no failureReason, the"
            + " checkout session PAYMENT_PENDING - a declined card's answer - and the merchant's read the same; no risk"
            + " word anywhere")
    void aMerchantSeesOnlyTheGenericRefusal() throws Exception {
        activatePolicies();
        Merchant merchant = tradingMerchant();
        Payer payer = cardPayer();
        String sessionToken = openSession(merchant, 10_000_00);
        HttpResponse<String> answered = confirmByCard(payer, sessionToken);
        assertNoRiskWord(answered.body());
        UUID intent = intentOf(payer);
        HttpResponse<String> payment = fixtures.get("/v1/payments/" + intent, payer.customer().token());
        assertThat(field(payment.body(), "status")).isEqualTo("FAILED");
        assertThat(payment.body()).doesNotContain("failureReason\":\"");
        assertNoRiskWord(payment.body());
        HttpResponse<String> merchantRead = fixtures.get("/v1/checkout/sessions/" + field(answered.body(),
                "checkoutId"), merchant.apiKey());
        assertThat(merchantRead.statusCode()).as(merchantRead.body()).isEqualTo(200);
        assertThat(field(merchantRead.body(), "status")).isEqualTo("PAYMENT_PENDING");
        assertNoRiskWord(merchantRead.body());
    }

    // ------------------------------------------------------------------ the fail-safe and the retry

    @Test
    @Order(5)
    @DisplayName("#riskUnavailableCommitsTheBandsOutcome (C1): the party's subject lock held past the budget - EUR"
            + " 249.99 completes under ALLOW(RISK_UNAVAILABLE), EUR 250.00 is refused under DENY(RISK_UNAVAILABLE)")
    void riskUnavailableCommitsTheBandsOutcome() throws Exception {
        activatePolicies();
        Merchant merchant = tradingMerchant();
        Payer payer = cardPayer();
        String small = openSession(merchant, 249_99);
        String large = openSession(merchant, 250_00);
        List<HttpResponse<String>> answers = new ArrayList<>();
        try (Connection blocker = DatabaseRoles.application()) {
            blocker.setAutoCommit(false);
            try (Statement lock = blocker.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(131, " + VelocityWindows.lockKey(VelocityDimension.PARTY,
                        payer.customer().party().toString()) + ")");
            }
            answers.add(confirmByCard(payer, small));
            answers.add(confirmByCard(payer, large));
            blocker.rollback();
        }
        assertThat(field(answers.get(0).body(), "status")).isEqualTo("COMPLETED");
        assertThat(column("SELECT decision || ',' || fail_safe_reason || ',' || unavailable_cause FROM"
                + " risk.risk_assessment WHERE party_id = ? ORDER BY amount_minor", payer.customer().party()))
                .containsExactly("ALLOW,RISK_UNAVAILABLE,TIMEOUT", "DENY,RISK_UNAVAILABLE,TIMEOUT");
        assertThat(count("SELECT count(*) FROM payments.payment_intent WHERE party_id = ? AND status = 'FAILED'"
                + " AND failure_reason = 'RISK_REFUSED'", payer.customer().party())).isEqualTo(1);
    }

    @Test
    @Order(6)
    @DisplayName("#oneAssessmentPerConfirmation: a permitted confirmation retried converges on its paid session - one"
            + " assessment, one count, one capture")
    void oneAssessmentPerConfirmation() throws Exception {
        activatePolicies();
        Merchant merchant = tradingMerchant();
        Payer payer = cardPayer();
        String session = openSession(merchant, 30_00);
        assertThat(field(confirmByCard(payer, session).body(), "status")).isEqualTo("COMPLETED");
        assertThat(field(confirmByCard(payer, session).body(), "status")).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ?", payer.customer().party()))
                .isEqualTo(1);
        assertThat(windowCount("PARTY", payer.customer().party(), "D1")).isEqualTo(1);
        assertThat(provider.requestCount(SimulatedCardPspAdapter.CAPTURES_PATH)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the door

    @Test
    @Order(7)
    @DisplayName("#theDoorsStepUpWritesNothing: EUR 600 by card from a password session is 403"
            + " identity.AssuranceRequired before any row - the session still OPEN, no intent, no assessment, no window")
    void theDoorsStepUpWritesNothing() throws Exception {
        activatePolicies();
        Merchant merchant = tradingMerchant();
        Payer payer = cardPayer();
        String session = openSession(merchant, 600_00);
        HttpResponse<String> refused = confirmByCard(payer, session);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).contains("identity.AssuranceRequired");
        assertNoRiskWord(refused.body());
        assertThat(count("SELECT count(*) FROM payments.payment_intent WHERE party_id = ?", payer.customer().party()))
                .isZero();
        assertThat(count("SELECT count(*) FROM checkout.checkout_session WHERE merchant_ref = ? AND status = 'OPEN'",
                UUID.fromString(merchant.id()))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ?", payer.customer().party()))
                .isZero();
        assertThat(windowCount("PARTY", payer.customer().party(), "D1")).isZero();
    }

    // ------------------------------------------------------------------ concurrency and the lock order

    @Test
    @Order(8)
    @DisplayName("scenario 14 through the seam: ten concurrent card confirmations of one party - the party's and the"
            + " merchant's windows equal to the counted assessments, ten unless a budget breach was recorded")
    void tenConfirmationsOfOnePartyCountTen() throws Exception {
        activatePolicies();
        Merchant merchant = tradingMerchant();
        Payer payer = cardPayer();
        List<String> sessions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sessions.add(openSession(merchant, 10_00));
        }
        List<HttpResponse<String>> answers = race(sessions.stream()
                .<Callable<HttpResponse<String>>>map(session -> () -> confirmByCard(payer, session)).toList());
        assertThat(answers).allSatisfy(answer -> assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200));
        long counted = count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ? AND velocity_outcome ="
                + " 'COUNTED'", payer.customer().party());
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ?", payer.customer().party()))
                .isEqualTo(10);
        assertThat(windowCount("PARTY", payer.customer().party(), "D1")).isEqualTo(counted);
        assertThat(windowCount("MERCHANT", UUID.fromString(merchant.id()), "D1")).isEqualTo(counted);
    }

    @Test
    @Order(9)
    @DisplayName("#noLockCycleAgainstATransferAndAWithdrawalOfTheSameParty (the wallet pair locked before the"
            + " evaluation): ten wallet payments, ten transfers and ten withdrawals of"
            + " one party over one wallet at once - the account rows first, (R1)-(R3) last in every one; no 40P01, every"
            + " answer a domain outcome")
    void noLockCycleAgainstATransferAndAWithdrawalOfTheSameParty() throws Exception {
        activatePolicies();
        Merchant merchant = tradingMerchant();
        Customer customer = fixtures.funded(5_000_00);
        Customer payee = fixtures.customer();
        List<String> sessions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sessions.add(openSession(merchant, 1_00));
        }
        List<Callable<Object>> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String session = sessions.get(i);
            all.add(() -> confirmFromWallet(customer, session));
            all.add(() -> transfer(customer, payee.product(), eur(1_00)));
            all.add(() -> withdraw(customer, 1_00));
        }
        List<Object> answers = race(all);
        assertThat(answers).hasSize(30).allSatisfy(answer -> assertThat(answer).isNotNull());
        // Every confirmation a domain answer - judged (200, the session's state) or stepped up at the door (403, once
        // the shared SOURCE_ACCOUNT window crossed the C8 count's CHALLENGE weight) - never a 40P01's 500.
        List<HttpResponse<?>> confirmations = answers.stream().filter(answer -> answer instanceof HttpResponse<?>)
                .<HttpResponse<?>>map(answer -> (HttpResponse<?>) answer).toList();
        assertThat(confirmations).hasSize(10).allSatisfy(answer -> assertThat(answer.statusCode())
                .as(String.valueOf(answer.body())).isIn(200, 403));
        long judged = confirmations.stream().filter(answer -> answer.statusCode() == 200).count();
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ? AND operation ="
                + " 'WALLET_PAYMENT'", customer.party())).as("every confirmation past the door judged once")
                .isEqualTo(judged);
    }

    // ------------------------------------------------------------------ slice 2: the held card payment

    @Test
    @Order(10)
    @DisplayName("#aHeldCardPaymentAuthorisesAndDefersCapture: EUR 3,000 by card is a HOLD - authorised, never"
            + " captured, the capture hold open with held_until = held_at + 72 h on the database clock, nothing counted;"
            + " the customer reads PROCESSING and the session PAYMENT_PENDING, no risk word")
    void aHeldCardPaymentAuthorisesAndDefersCapture() throws Exception {
        activatePolicies();
        Merchant merchant = tradingMerchant();
        Payer payer = cardPayer();
        // EUR 3,000 fires the EUR 500 CHALLENGE too; HOLD is the stricter (DENY > HOLD > REVIEW > CHALLENGE), so the
        // door advises no step-up and the confirmation's T-eval decides HOLD.
        HttpResponse<String> answered = confirmByCard(payer, openSession(merchant, 3_000_00));
        assertThat(answered.statusCode()).as(answered.body()).isEqualTo(200);
        assertThat(field(answered.body(), "status")).isEqualTo("PAYMENT_PENDING");
        assertNoRiskWord(answered.body());
        UUID intent = intentOf(payer);
        assertThat(column("SELECT status FROM payments.payment_attempt WHERE intent_id = ?", intent))
                .containsExactly("AUTHORIZED");
        assertThat(provider.requestCount(SimulatedCardPspAdapter.CAPTURES_PATH)).as("never captured").isZero();
        // A timestamptz difference renders "3 days", so the window is read in seconds (P13-TST-001's first run of this owed suite).
        assertThat(column("SELECT extract(epoch FROM held_until - held_at)::bigint || ',' || coalesce(resolution, 'OPEN')"
                + " FROM payments.payment_capture_hold WHERE intent_id = ?", intent)).containsExactly("259200,OPEN");
        assertThat(column("SELECT decision || ',' || velocity_outcome FROM risk.risk_assessment WHERE command_ref = ?",
                intent.toString())).containsExactly("HOLD,NOT_PERMITTED");
        assertThat(windowCount("PARTY", payer.customer().party(), "D1")).isZero();
        HttpResponse<String> payment = fixtures.get("/v1/payments/" + intent, payer.customer().token());
        assertThat(field(payment.body(), "status")).isEqualTo("PROCESSING");
        assertNoRiskWord(payment.body());
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'payments.PaymentCaptureDeferred' AND aggregate_id = ?", intent)).isEqualTo(1);

        // The capture door releases it, once: the capture dispatched and posted.
        HeldPayments.Answer captured = asPlatform(() -> heldPayments.captureHeld("risk.action:" + UUID.randomUUID(),
                PaymentIntentId.of(intent), "OVERRIDE_APPROVED"));
        assertThat(captured.outcome()).isEqualTo(HeldPayments.HeldOutcome.DONE);
        assertThat(column("SELECT status FROM payments.payment_attempt WHERE intent_id = ?", intent))
                .containsExactly("CAPTURED");
        assertThat(provider.requestCount(SimulatedCardPspAdapter.CAPTURES_PATH)).isEqualTo(1);
    }

    @Test
    @Order(11)
    @DisplayName("#captureAndVoidLeaveExactlyOne: a capture racing the deadline's expiry at held_until - one resolution"
            + " on the hold row, and the money moved or released exactly once (captured, or voided)")
    void captureAndVoidLeaveExactlyOne() throws Exception {
        for (int round = 0; round < 5; round++) {
            UUID intent = heldTopUp(Duration.ofSeconds(1));
            Thread.sleep(950 + round * 20L);
            int capturesBefore = provider.requestCount(SimulatedCardPspAdapter.CAPTURES_PATH);
            int voidsBefore = provider.requestCount(SimulatedCardPspAdapter.VOIDS_PATH);
            List<Object> answers = race(List.<Callable<Object>>of(
                    () -> asPlatform(() -> heldPayments.captureHeld("risk.action:" + UUID.randomUUID(),
                            PaymentIntentId.of(intent), "OVERRIDE_APPROVED")),
                    () -> asPlatform(heldPayments::expireHolds)));
            HeldPayments.Answer capture = (HeldPayments.Answer) answers.get(0);
            Thread.sleep(200);
            asPlatform(heldPayments::expireHolds);
            assertThat(column("SELECT coalesce(resolution, 'OPEN') FROM payments.payment_capture_hold WHERE"
                    + " intent_id = ?", intent)).as("round %d: one resolution", round).hasSize(1)
                    .first().isIn("CAPTURE", "EXPIRED");
            String attempt = column("SELECT status FROM payments.payment_attempt WHERE intent_id = ?", intent).get(0);
            if (capture.outcome() == HeldPayments.HeldOutcome.DONE) {
                assertThat(attempt).isEqualTo("CAPTURED");
                assertThat(provider.requestCount(SimulatedCardPspAdapter.CAPTURES_PATH)).isEqualTo(capturesBefore + 1);
                assertThat(provider.requestCount(SimulatedCardPspAdapter.VOIDS_PATH)).isEqualTo(voidsBefore);
            } else {
                assertThat(capture.outcome()).isIn(HeldPayments.HeldOutcome.ALREADY, HeldPayments.HeldOutcome.REFUSED);
                assertThat(attempt).isEqualTo("VOIDED");
                assertThat(provider.requestCount(SimulatedCardPspAdapter.CAPTURES_PATH)).isEqualTo(capturesBefore);
            }
        }
    }

    // ------------------------------------------------------------------ scenario 7's payment half

    @Test
    @Order(12)
    @DisplayName("#aProviderFailureAfterAPermitLeavesTheDecision (scenario 7): the issuer unreachable after a PERMIT -"
            + " payments' own path (the attempt AUTH_UNKNOWN or FAILED, nothing captured), the sealed ALLOW and its count"
            + " standing, the intent never risk-refused")
    void aProviderFailureAfterAPermitLeavesTheDecision() throws Exception {
        activatePolicies();
        Merchant merchant = tradingMerchant();
        Payer payer = cardPayer();
        provider.isUnavailable(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH);
        HttpResponse<String> answered = confirmByCard(payer, openSession(merchant, 30_00));
        assertNoRiskWord(answered.body());
        UUID intent = intentOf(payer);
        assertThat(column("SELECT decision || ',' || velocity_outcome FROM risk.risk_assessment WHERE command_ref = ?",
                intent.toString())).as("the decision stands: the provider's failure is payments' own")
                .containsExactly("ALLOW,COUNTED");
        assertThat(windowCount("PARTY", payer.customer().party(), "D1")).isEqualTo(1);
        assertThat(column("SELECT status FROM payments.payment_attempt WHERE intent_id = ?", intent)).hasSize(1)
                .first().isIn("AUTH_UNKNOWN", "FAILED");
        assertThat(count("SELECT count(*) FROM payments.payment_intent WHERE id = ? AND failure_reason IS NOT NULL",
                intent)).as("never RISK_REFUSED").isZero();
        assertThat(provider.requestCount(SimulatedCardPspAdapter.CAPTURES_PATH)).isZero();
    }

    // ------------------------------------------------------------------ the checkout, over HTTP

    /** A merchant: its id and its API key. */
    private record Merchant(String id, String apiKey) {}

    /** A payer with a card instrument. */
    private record Payer(Customer customer, String methodId) {}

    private Merchant tradingMerchant() throws Exception {
        String operator = operatorSession(RoleName.MERCHANT_ADMINISTRATOR);
        UUID party = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'ORGANISATION',"
                    + " 'Acme Holdings', now())", party);
            execute(app, "INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at) VALUES"
                    + " (?, ?, 'ACTIVE', now() - interval '1 hour', now())", IDS.next(), party);
        }
        HttpResponse<String> created = fixtures.post("/v1/operator/merchants", "{\"partyId\":\"" + party
                + "\",\"legalName\":\"Acme GmbH\",\"displayName\":\"Acme\",\"settlementCurrency\":\"EUR\"}",
                operator, someKey());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String merchantId = field(created.body(), "merchantId");
        String schedule = field(fixtures.post("/v1/operator/fee-schedules", "{\"name\":\"Std " + UUID.randomUUID()
                + "\",\"currency\":\"EUR\"}", operator, null).body(), "feeScheduleId");
        assertThat(fixtures.post("/v1/operator/fee-schedules/" + schedule + "/versions", "{\"rate\":0.029,"
                + "\"fixedAmountMinor\":30,\"roundingPolicy\":\"HALF_EVEN\",\"refundFeePolicy\":\"RETAINED\","
                + "\"reason\":\"initial pricing\"}", operator, null).statusCode()).isEqualTo(201);
        assertThat(put("/v1/operator/merchants/" + merchantId + "/fee-schedule", "{\"feeScheduleId\":\"" + schedule
                + "\",\"reason\":\"standard terms\"}", operator).statusCode()).isEqualTo(200);
        HttpResponse<String> issued = fixtures.post("/v1/operator/merchants/" + merchantId + "/api-keys", null,
                operator, someKey());
        assertThat(issued.statusCode()).isEqualTo(201);
        return new Merchant(merchantId, field(issued.body(), "keyId") + "." + field(issued.body(), "secret"));
    }

    private Payer cardPayer() throws Exception {
        Customer customer = fixtures.customer();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        provider.succeedsWith(SimulatedTokenisationAdapter.TOKENISATIONS_PATH, 200, "{\"status\":\"tokenised\","
                + "\"token\":\"tok_" + suffix + "\",\"brand\":\"Visa\",\"last4\":\"4242\",\"expiryMonth\":12,"
                + "\"expiryYear\":2030}");
        HttpResponse<String> attached = fixtures.post("/v1/me/payment-methods", "{\"clientToken\":\"ctok_" + suffix
                + "\"}", customer.token(), null);
        assertThat(attached.statusCode()).as(attached.body()).isEqualTo(201);
        return new Payer(customer, field(attached.body(), "id"));
    }

    private String openSession(Merchant merchant, long amountMinor) throws Exception {
        HttpResponse<String> created = fixtures.post("/v1/checkout/sessions", "{\"amountMinor\":" + amountMinor
                + ",\"currency\":\"EUR\",\"lineSummary\":\"Two coffees\"}", merchant.apiKey(), someKey());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return field(created.body(), "sessionToken");
    }

    private HttpResponse<String> confirmByCard(Payer payer, String sessionToken) throws Exception {
        return fixtures.post("/v1/checkout/sessions/confirmation", "{\"sessionToken\":\"" + sessionToken
                + "\",\"paymentMethodId\":\"" + payer.methodId() + "\"}", payer.customer().token(), null);
    }

    private HttpResponse<String> confirmFromWallet(Customer customer, String sessionToken) throws Exception {
        return fixtures.post("/v1/checkout/sessions/confirmation", "{\"sessionToken\":\"" + sessionToken
                + "\",\"instrument\":\"WALLET\"}", customer.token(), null);
    }

    private static UUID intentOf(Payer payer) throws SQLException {
        return HeldWithdrawalFixtures.uuid("SELECT id FROM payments.payment_intent WHERE party_id = ? ORDER BY"
                + " created_at DESC LIMIT 1", payer.customer().party());
    }

    // ------------------------------------------------------------------ the scripted HOLD (the race)

    /**
     * A card top-up held by a scripted seam with {@code validity}: the real confirmation, outcomes and capture hold
     * store of the composition root, answered HOLD - the doors' race needs a deadline seconds away, which no policy's
     * whole-hour validity gives.
     */
    private UUID heldTopUp(Duration validity) throws Exception {
        Customer customer = fixtures.customer();
        Payer payer = withCard(customer);
        String intent = field(fixtures.post("/v1/payments", "{\"paymentMethodId\":\"" + payer.methodId()
                + "\",\"amount\":\"20.00\",\"currency\":\"EUR\"}", customer.token(), someKey()).body(), "id");
        PaymentConfirmation held = new PaymentConfirmation(paymentTransactionRunner, paymentIntentStore,
                paymentAttemptStore, providerEvidenceStore, paymentParticipants, paymentProvider, paymentOutcomes,
                routingStore, paymentRails, auditWriter, outboxWriter, IDS, CLOCK,
                com.finapp.payments.RoutingTelemetry.NONE, com.finapp.payments.RailOperations.none(),
                (unitOfWork, judged) -> PaymentVerdict.hold(validity), captureHoldStore);
        try (SecurityContext.Scope scope = SecurityContext.enter(
                        new Actor(customer.identity().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            assertThat(held.confirm(customer.party(), PaymentIntentId.of(UUID.fromString(intent))).attempt())
                    .contains(com.finapp.payments.PaymentAttemptStatus.AUTHORIZED);
        }
        return UUID.fromString(intent);
    }

    private Payer withCard(Customer customer) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        provider.succeedsWith(SimulatedTokenisationAdapter.TOKENISATIONS_PATH, 200, "{\"status\":\"tokenised\","
                + "\"token\":\"tok_" + suffix + "\",\"brand\":\"Visa\",\"last4\":\"4242\",\"expiryMonth\":12,"
                + "\"expiryYear\":2030}");
        HttpResponse<String> attached = fixtures.post("/v1/me/payment-methods", "{\"clientToken\":\"ctok_" + suffix
                + "\"}", customer.token(), null);
        assertThat(attached.statusCode()).as(attached.body()).isEqualTo(201);
        return new Payer(customer, field(attached.body(), "id"));
    }

    // ------------------------------------------------------------------ the money commands of the same party

    private Object transfer(Customer payer, UUID destination, Money amount) throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope scope = SecurityContext.enter(
                        new Actor(payer.identity().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            Object result = transferExecution.execute(app, new TransferCommand(someKey(), payer.party(),
                    payer.product(), destination, amount, "rent"));
            app.commit();
            return result;
        }
    }

    private Withdrawals.Initiated withdraw(Customer customer, long minor) throws Exception {
        Withdrawals.Resolved resolved = HeldWithdrawalFixtures.resolved(customer, EUR);
        try (SecurityContext.Scope scope = SecurityContext.enter(
                        new Actor(customer.identity().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return engine.withdraw(someKey(), Money.ofMinorUnits(minor, EUR), uow -> resolved);
        }
    }

    // ------------------------------------------------------------------ the policies

    /**
     * The C8 {@code WALLET_PAYMENT} fixture, and the C8 {@code CARD_PAYMENT} fixture with slice 2's hard {@code HOLD}
     * at EUR 2,500 and a 72 h hold validity - activated once through the domain by two test persons.
     */
    private static void activatePolicies() {
        if (!ACTIVATED.compareAndSet(false, true)) {
            return;
        }
        RiskPolicyAdministration administration = new RiskPolicyAdministration(new JdbcRiskPolicyStore(),
                new com.finapp.platform.audit.JdbcAuditWriter(), new com.finapp.platform.outbox.JdbcOutboxWriter(),
                IDS, CLOCK);
        RiskPolicy base = RiskPolicyFixtures.v1(RiskOperation.CARD_PAYMENT);
        List<RiskRule> rules = new ArrayList<>(base.rules());
        rules.add(RiskRule.deciding(rules.size() + 1, RuleKind.AMOUNT_BAND, RuleParameters.of(RuleKind.AMOUNT_BAND,
                Map.of("threshold_minor", RiskPolicyFixtures.minor(2_500_00))), RiskDecision.HOLD));
        RiskPolicy holding = new RiskPolicy(RiskOperation.CARD_PAYMENT, base.engine(), base.currency(), rules,
                base.scoreBands(), List.of(), base.failSafeBands(), base.challengeAssurance(), base.overrideThreshold(),
                Optional.of(RiskPolicy.DEFAULT_HOLD_VALIDITY));
        for (RiskPolicy policy : List.of(holding, RiskPolicyFixtures.v1(RiskOperation.WALLET_PAYMENT))) {
            RiskPolicyAdministration.Proposed proposed = inOneTransaction(uow -> administration.propose(uow, policy,
                    "the payment seam suite's version", employee(), CorrelationId.generate(IDS)));
            inOneTransaction(uow -> administration.activate(uow, proposed.id(), employee(), "activated",
                    CorrelationId.generate(IDS)));
        }
    }

    // ------------------------------------------------------------------ plumbing

    private String operatorSession(RoleName role) throws Exception {
        String login = "ops." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(fixtures.post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada"
                + " Lovelace\",\"password\":\"" + HeldWithdrawalFixtures.PASSWORD + "\"}", null, someKey())
                .statusCode()).isEqualTo(201);
        UUID identity = HeldWithdrawalFixtures.uuid("SELECT id FROM identity.identity WHERE login_identifier = ?",
                login);
        try (CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        return HeldWithdrawalFixtures.tokenFrom(fixtures.post("/v1/authentications", "{\"loginIdentifier\":\""
                + login + "\",\"password\":\"" + HeldWithdrawalFixtures.PASSWORD + "\"}", null, null).body());
    }

    private HttpResponse<String> put(String path, String body, String bearer) throws Exception {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + bearer)
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        try (java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    private static <T> T asPlatform(java.util.function.Supplier<T> call) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            return call.get();
        }
    }

    private static <R> R inOneTransaction(java.util.function.Function<Connection, R> work) {
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
        return new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE);
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
