package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.payments.PayInResolution;
import com.finapp.payments.PaymentConfirmation;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PushRail;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.payments.TransactionRunner;
import com.finapp.paymentmethods.SimulatedTokenisationAdapter;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.id.IdGenerator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Pay-by-bank pay-ins, end to end and at every resolver (`P7-TSK-009`, ADR-0062 §5): the
 * whole deployed chain over HTTP — the bank account registered through the real grant
 * exchange, the initiation dispatched through the REAL
 * {@link SimulatedInstantSchemeAdapter}, the payer's execution landing through the SIGNED
 * callback door — plus the batteries only the database can prove: ten fresh-id duplicate
 * callbacks crediting once, the unattributable confirmation parked in
 * {@code SUSPENSE_UNMATCHED} exactly once ({@code INV-REC-05}), the lost initiate answer
 * recovered by the sweep's convergent re-initiate, the lost callback resolved by the
 * inquiry to one entry, the refused connection failing ONLY the handle-less row, and the
 * foreign scheme-reference claim refused with the first record standing.
 *
 * <p>The checkout leg — the fee split and {@code COMPLETED_LATE} ({@code INV-MER-06}'s
 * second rail) — lives in {@code CheckoutFlowDatabaseTest}, whose merchant fixture it
 * reuses whole.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("pay-by-bank pay-ins over the instant rail (P7-TSK-009)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class PayByBankDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final byte[] WEBHOOK_KEY =
            "an-instant-webhook-key-32-bytes!".getBytes(StandardCharsets.UTF_8);

    private static SimulatedProvider provider;

    @LocalServerPort private int port;

    @Autowired private com.finapp.payments.PaymentOutcomes outcomes;
    @Autowired private com.finapp.payments.PaymentAttemptStore<Connection> attempts;
    @Autowired private com.finapp.payments.PaymentIntentStore<Connection> intents;
    @Autowired private TransactionRunner transactions;
    @Autowired private IdGenerator ids;

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        // One stub server, every provider: the tokeniser and card PSP exist for the
        // registration surface's siblings, the instant scheme takes the initiations.
        registry.add("finapp.paymentmethods.tokenisation.url", () -> provider.baseUrl());
        registry.add("finapp.paymentmethods.tokenisation.timeout", () -> "PT0.7S");
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add("finapp.payments.provider.timeout", () -> "PT0.7S");
        registry.add("finapp.payments.instant.url", () -> provider.baseUrl());
        registry.add("finapp.payments.instant.timeout", () -> "PT0.7S");
        registry.add(
                "finapp.payments.instant.webhook.key",
                () -> Base64.getEncoder().encodeToString(WEBHOOK_KEY));
        // Tiny candidacy bound so a stranded initiation is swept NOW; the sweep's safety
        // never rests on this clock (the handle predicate does that work).
        registry.add("finapp.payments.payin.sweeper.initiation-age", () -> "PT0.05S");
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    // -----------------------------------------------------------------
    // The wallet chain and the callback door
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the wallet chain holds: registered, initiated with the handle rendered to"
            + " its owner, executed by the signed callback - EXACTLY ONE entry"
            + " DR INSTANT_CLEARING / CR wallet, the balance explained, the decision"
            + " pinned to version 3")
    void theWalletChainHolds() throws Exception {
        Fixture f = bankFixture();
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());

        HttpResponse<String> confirmed = confirmedPayment(f, "5.00");
        assertThat(field(confirmed.body(), "status")).isEqualTo("PROCESSING");
        // The handle reaches its OWNER, once rendered on the awaiting view - the one
        // greppable expose site's product (InitiationAnswer's promise landing).
        assertThat(field(confirmed.body(), "authorizationHandle"))
                .startsWith("https://payer-psp.example/authorize/");
        String paymentId = field(confirmed.body(), "id");
        String attemptId = attemptIdOf(paymentId);
        assertThat(oneString(
                        "SELECT status || '|' || interaction_model || '|' || rail"
                                + " FROM payments.payment_attempt WHERE id = ?",
                        UUID.fromString(attemptId)))
                .isEqualTo("AWAITING_PAYER|PUSH|instant");

        // The payer executes; the scheme confirms through the signed door.
        String scheme = "sch-wallet-" + suffix();
        assertThat(executedCallback(referenceOf(attemptId), scheme, "C7", "5.00", "USD"))
                .isEqualTo(204);

        // The machine, the scheme's pair (Phase 8's keys), the intent - one commit.
        assertThat(oneString(
                        "SELECT status || '|' || scheme_reference || '|' || settlement_cycle"
                                + " FROM payments.payment_attempt WHERE id = ?",
                        UUID.fromString(attemptId)))
                .isEqualTo("EXECUTED|" + scheme + "|C7");
        assertThat(field(get("/v1/payments/" + paymentId, f.token()).body(), "status"))
                .isEqualTo("SUCCEEDED");

        // EXACTLY ONE posting, the execution's own key, on the instant rail's declared
        // position (INV-RAIL-04) - and the wallet explains it to the cent.
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-execution:" + attemptId + "'"))
                .isEqualTo(1);
        assertThat(oneString(
                        "SELECT string_agg(l.direction || ':' || a.purpose, ',' ORDER BY"
                                + " l.direction) FROM ledger.journal_line l"
                                + " JOIN ledger.journal_entry e ON e.id = l.entry_id"
                                + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                                + " WHERE e.idempotency_scope ="
                                + " 'ledger.post:payment-execution:" + attemptId + "'"))
                .isEqualTo("CREDIT:CUSTOMER_WALLET,DEBIT:INSTANT_CLEARING");
        assertThat(get("/v1/me/accounts/" + f.product() + "/balance", f.token()).body())
                .contains("\"settled\":\"5.00\"");

        // The pinned decision: version 3's bank pay-in rule chose the instant rail.
        assertThat(oneString(
                        "SELECT chosen_rail || '|' || v.version::text"
                                + " FROM payments.routing_decision d"
                                + " JOIN payments.routing_policy_version v"
                                + " ON v.id = d.policy_version_id WHERE d.intent_id = ?"
                                + " AND d.chosen_rail IS NOT NULL",
                        UUID.fromString(paymentId)))
                .isEqualTo("instant|3");

        // One executed fact; the outcome applied as the platform; and the handle reaches
        // NO event and NO audit record (INV-AUD-02: a capability URL is a credential).
        assertThat(outboxCount("payments.PaymentExecuted", paymentId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE"
                        + " convert_from(payload, 'UTF8') LIKE '%payer-psp.example%'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE change_summary"
                        + " LIKE '%payer-psp.example%'"))
                .isZero();
        // The backlog's audit line, asserted: the initiation audited as the PERSON,
        // the outcome applied as the platform (the withdrawal's split, inbound).
        assertThat(oneString("SELECT actor_type FROM platform.audit_record WHERE"
                        + " operation = 'payments.PaymentConfirmed' AND target_id = ?",
                        paymentId))
                .isEqualTo("CUSTOMER");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                        + " 'payments.PaymentOutcomeApplied' AND target_id = '" + paymentId
                        + "' AND actor_type = 'SYSTEM' AND change_summary LIKE"
                        + " '%EXECUTED%'"))
                .isGreaterThanOrEqualTo(1);
        // A concluded payment renders NO handle: the invitation died with the wait.
        assertThat(get("/v1/payments/" + paymentId, f.token()).body())
                .contains("\"authorizationHandle\":null");
    }

    @Test
    @DisplayName("ten duplicate callbacks under ten fresh event ids credit ONCE: one entry,"
            + " one executed fact, the rest converged on the row (INV-IDEM-04)")
    void tenDuplicateCallbacksCreditOnce() throws Exception {
        Fixture f = bankFixture();
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String paymentId = field(confirmedPayment(f, "4.00").body(), "id");
        String attemptId = attemptIdOf(paymentId);
        String reference = referenceOf(attemptId);
        String scheme = "sch-dup-" + suffix();

        for (int delivery = 0; delivery < 10; delivery++) {
            assertThat(executedCallback(reference, scheme, "C1", "4.00", "USD"))
                    .isEqualTo(204);
        }

        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-execution:" + attemptId + "'"))
                .isEqualTo(1);
        assertThat(outboxCount("payments.PaymentExecuted", paymentId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payments.payment_attempt_event WHERE"
                        + " attempt_id = '" + attemptId + "'::uuid AND to_status = 'EXECUTED'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the payer's refusal and the payer PSP's expiry both land FAILED(DECLINED)"
            + " with NOTHING posted - the scheme's word rests in the evidence (INV-PAY-03)")
    void aRejectedPayerFailsWithNothingPosted() throws Exception {
        for (String word : new String[] {"rejected", "expired"}) {
            Fixture f = bankFixture();
            schemeInitiates("https://payer-psp.example/authorize/" + suffix());
            String paymentId = field(confirmedPayment(f, "3.00").body(), "id");
            String attemptId = attemptIdOf(paymentId);

            assertThat(callback(
                            "{\"eventId\":\"evt_" + suffix() + "\",\"reference\":\""
                                    + referenceOf(attemptId) + "\",\"status\":\"" + word
                                    + "\"}"))
                    .isEqualTo(204);

            assertThat(oneString(
                            "SELECT status || '|' || failure_reason"
                                    + " FROM payments.payment_attempt WHERE id = ?",
                            UUID.fromString(attemptId)))
                    .as("the '%s' word is the rejection's (the adapter keeps the raw word"
                            + " in the evidence)", word)
                    .isEqualTo("FAILED|DECLINED");
            assertThat(field(get("/v1/payments/" + paymentId, f.token()).body(), "status"))
                    .isEqualTo("FAILED");
            assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE"
                            + " idempotency_scope = 'ledger.post:payment-execution:"
                            + attemptId + "'"))
                    .isZero();
        }
    }

    // -----------------------------------------------------------------
    // The unattributable confirmation (INV-REC-05)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an unattributable confirmation carrying money parks ONCE under ten"
            + " fresh-id deliveries: DR INSTANT_CLEARING / CR SUSPENSE_UNMATCHED, audited,"
            + " never credited by guesswork - and a moneyless one is evidence alone")
    void anUnattributableConfirmationParksOnce() throws Exception {
        String scheme = "sch-orphan-" + suffix();
        String foreign = UUID.randomUUID().toString().replace("-", "");
        for (int delivery = 0; delivery < 10; delivery++) {
            assertThat(callback(
                            "{\"eventId\":\"evt_" + suffix() + "\",\"reference\":\"" + foreign
                                    + "\",\"status\":\"executed\",\"schemeReference\":\""
                                    + scheme + "\",\"amount\":\"7.50\","
                                    + "\"currency\":\"USD\"}"))
                    .isEqualTo(204);
        }

        assertThat(count("SELECT count(*) FROM payments.unmatched_confirmation WHERE"
                        + " scheme_reference = '" + scheme + "'"))
                .isEqualTo(1);
        String scope = "ledger.post:unmatched-confirmation:instant:" + scheme;
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = '" + scope + "'"))
                .isEqualTo(1);
        assertThat(oneString(
                        "SELECT string_agg(l.direction || ':' || a.purpose, ',' ORDER BY"
                                + " l.direction) FROM ledger.journal_line l"
                                + " JOIN ledger.journal_entry e ON e.id = l.entry_id"
                                + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                                + " WHERE e.idempotency_scope = '" + scope + "'"))
                .isEqualTo("CREDIT:SUSPENSE_UNMATCHED,DEBIT:INSTANT_CLEARING");
        // The row points at its entry, and the acting parking is audited ONCE.
        assertThat(count("SELECT count(*) FROM payments.unmatched_confirmation u"
                        + " JOIN ledger.journal_entry e ON e.id = u.entry_ref"
                        + " WHERE u.scheme_reference = '" + scheme + "'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                        + " 'payments.UnmatchedConfirmationParked' AND target_id = '"
                        + scheme + "'"))
                .isEqualTo(1);

        // Moneyless, or unusable money: retained and acknowledged, nothing parked.
        String moneyless = "sch-moneyless-" + suffix();
        assertThat(callback(
                        "{\"eventId\":\"evt_" + suffix() + "\",\"reference\":\""
                                + UUID.randomUUID().toString().replace("-", "")
                                + "\",\"status\":\"executed\",\"schemeReference\":\""
                                + moneyless + "\"}"))
                .isEqualTo(204);
        assertThat(count("SELECT count(*) FROM payments.unmatched_confirmation WHERE"
                        + " scheme_reference = '" + moneyless + "'"))
                .isZero();
    }

    @Test
    @DisplayName("a confirmation whose stated amount differs from the initiation's ask moves"
            + " NOTHING - retained loud, the integration break reconciliation must see")
    void aMismatchedAmountMovesNothing() throws Exception {
        Fixture f = bankFixture();
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String paymentId = field(confirmedPayment(f, "5.00").body(), "id");
        String attemptId = attemptIdOf(paymentId);

        assertThat(executedCallback(
                        referenceOf(attemptId), "sch-mismatch-" + suffix(), "C1", "9.99",
                        "USD"))
                .isEqualTo(204);

        assertThat(oneString("SELECT status FROM payments.payment_attempt WHERE id = ?",
                        UUID.fromString(attemptId)))
                .isEqualTo("AWAITING_PAYER");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-execution:" + attemptId + "'"))
                .isZero();
    }

    @Test
    @DisplayName("a scheme reference another attempt already stored, or one already PARKED,"
            + " never credits twice: the first record stands and the statement rests as"
            + " evidence")
    void aForeignSchemeReferenceClaimNeverCreditsTwice() throws Exception {
        // First: a completed payment holds the reference.
        Fixture f = bankFixture();
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String firstPayment = field(confirmedPayment(f, "2.00").body(), "id");
        String firstAttempt = attemptIdOf(firstPayment);
        String claimed = "sch-claimed-" + suffix();
        assertThat(executedCallback(referenceOf(firstAttempt), claimed, "C1", "2.00", "USD"))
                .isEqualTo(204);

        // A second awaiting payment; its confirmation claims the SAME scheme reference.
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String secondPayment = field(confirmedPayment(f, "2.00").body(), "id");
        String secondAttempt = attemptIdOf(secondPayment);
        assertThat(executedCallback(referenceOf(secondAttempt), claimed, "C1", "2.00", "USD"))
                .isEqualTo(204);
        assertThat(oneString("SELECT status FROM payments.payment_attempt WHERE id = ?",
                        UUID.fromString(secondAttempt)))
                .as("the foreign claim moves nothing; the first record stands")
                .isEqualTo("AWAITING_PAYER");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-execution:" + secondAttempt + "'"))
                .isZero();

        // And a reference already PARKED in suspense refuses the credit the same way.
        String parked = "sch-parked-" + suffix();
        assertThat(callback(
                        "{\"eventId\":\"evt_" + suffix() + "\",\"reference\":\""
                                + UUID.randomUUID().toString().replace("-", "")
                                + "\",\"status\":\"executed\",\"schemeReference\":\"" + parked
                                + "\",\"amount\":\"2.00\",\"currency\":\"USD\"}"))
                .isEqualTo(204);
        assertThat(executedCallback(referenceOf(secondAttempt), parked, "C1", "2.00", "USD"))
                .isEqualTo(204);
        assertThat(oneString("SELECT status FROM payments.payment_attempt WHERE id = ?",
                        UUID.fromString(secondAttempt)))
                .as("a parked reference stays the operator's to resolve (INV-REC-05)")
                .isEqualTo("AWAITING_PAYER");
    }

    // -----------------------------------------------------------------
    // The sweep's two legs, and the refused connection
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a lost initiate answer rests AWAITING_PAYER handle-less; the sweep"
            + " re-initiates with the SAME reference, recovers the handle, and the payer"
            + " then completes")
    void aLostInitiateAnswerIsRecoveredByTheSweep() throws Exception {
        Fixture f = bankFixture();
        provider.receivesTheRequestThenLosesTheResponse(
                SimulatedInstantSchemeAdapter.INITIATIONS_PATH);

        HttpResponse<String> confirmed = confirmedPayment(f, "6.00");
        assertThat(field(confirmed.body(), "status")).isEqualTo("PROCESSING");
        assertThat(confirmed.body())
                .as("no handle exists yet - the honest modelled unknown (INV-LIFE-03)")
                .contains("\"authorizationHandle\":null");
        String paymentId = field(confirmed.body(), "id");
        String attemptId = attemptIdOf(paymentId);
        assertThat(oneString(
                        "SELECT status || '|' || COALESCE(authorization_handle, 'none')"
                                + " FROM payments.payment_attempt WHERE id = ?",
                        UUID.fromString(attemptId)))
                .isEqualTo("AWAITING_PAYER|none");

        // The scheme heals; the sweep re-initiates by the SAME reference (the dedupe
        // premise) and stores the handle the scheme answers. A WIDE-batch engine,
        // deliberately: this shared database accumulates other suites' waiting rows, and
        // the bean's bounded batch reads oldest-first - the shared-database citizenship
        // lesson (P7-TSK-004), met at the sweep this time. The row's own state is the
        // assertion of record.
        schemeInitiates("https://payer-psp.example/authorize/recovered-" + suffix());
        Thread.sleep(80); // past the tiny candidacy bound
        PayInResolution.SweepResult swept = wideSweep();
        assertThat(swept.contacted()).isGreaterThanOrEqualTo(1);
        assertThat(oneString(
                        "SELECT COALESCE(authorization_handle, 'none')"
                                + " FROM payments.payment_attempt WHERE id = ?",
                        UUID.fromString(attemptId)))
                .startsWith("https://payer-psp.example/authorize/recovered-");
        assertThat(provider.headerValues(
                                SimulatedInstantSchemeAdapter.INITIATIONS_PATH,
                                SimulatedInstantSchemeAdapter.IDEMPOTENCY_KEY_HEADER)
                        .stream()
                        .distinct())
                .as("both initiate calls carried OUR one reference (INV-PAY-04)")
                .containsExactly(referenceOf(attemptId));

        // The payer completes; the handle-bearing row executes to one entry.
        assertThat(executedCallback(
                        referenceOf(attemptId), "sch-recovered-" + suffix(), "C1", "6.00",
                        "USD"))
                .isEqualTo(204);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-execution:" + attemptId + "'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a lost callback is resolved by the initiation inquiry on the current row:"
            + " EXECUTED once, one entry, and a second sweep converges quietly")
    void aLostCallbackIsResolvedByInquiry() throws Exception {
        Fixture f = bankFixture();
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String paymentId = field(confirmedPayment(f, "8.00").body(), "id");
        String attemptId = attemptIdOf(paymentId);
        String reference = referenceOf(attemptId);

        // The payer PSP executed but the callback never arrives; the inquiry answers.
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.INITIATION_STATUS_PATH + reference,
                200,
                "{\"status\":\"accepted\",\"reference\":\"sch-inq-" + suffix()
                        + "\",\"cycle\":\"C4\"}");
        Thread.sleep(80);
        // The wide-batch engine, for the recovery test's shared-database reason.
        PayInResolution.SweepResult swept = wideSweep();
        assertThat(swept.resolved()).isGreaterThanOrEqualTo(1);

        assertThat(oneString("SELECT status FROM payments.payment_attempt WHERE id = ?",
                        UUID.fromString(attemptId)))
                .isEqualTo("EXECUTED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-execution:" + attemptId + "'"))
                .isEqualTo(1);

        // A second sweep finds nothing to move: the entry count is the proof.
        Thread.sleep(80);
        wideSweep();
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-execution:" + attemptId + "'"))
                .isEqualTo(1);
        assertThat(outboxCount("payments.PaymentExecuted", paymentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a refused connection fails ONLY the handle-less row"
            + " (FAILED(PROVIDER_UNAVAILABLE), retryable under a new key) - and the same"
            + " verdict against a row HOLDING a handle moves nothing: the payer can still"
            + " complete")
    void theRefusedConnectionFailsOnlyTheHandlelessRow() throws Exception {
        // First half: the dead port, driven through a second confirmation engine - the
        // WithdrawalDatabaseTest idiom, at the pay-in door.
        Fixture f = bankFixture();
        HttpResponse<String> created =
                post("/v1/payments", body(f.methodId(), "5.00", "USD"), f.token(), true);
        assertThat(created.statusCode()).isEqualTo(201);
        String paymentId = field(created.body(), "id");
        UUID party = oneUuid("SELECT party_id FROM payments.payment_intent WHERE id = ?",
                UUID.fromString(paymentId));

        PaymentConfirmation.ConfirmationResult refused;
        try (var actor =
                        com.finapp.platform.security.SecurityContext.enter(
                                new com.finapp.platform.security.Actor(
                                        UUID.randomUUID().toString(),
                                        com.finapp.platform.security.ActorType.CUSTOMER));
                var scope =
                        com.finapp.platform.correlation.CorrelationContext.enter(
                                com.finapp.sharedkernel.correlation.Correlation.startingWith(
                                        com.finapp.sharedkernel.correlation.CorrelationId
                                                .generate(ids)))) {
            refused =
                    offlineConfirmation()
                            .confirm(party, PaymentIntentId.of(UUID.fromString(paymentId)));
        }
        assertThat(refused.attempt())
                .contains(com.finapp.payments.PaymentAttemptStatus.FAILED);
        String attemptId = attemptIdOf(paymentId);
        assertThat(oneString(
                        "SELECT status || '|' || failure_reason || '|' ||"
                                + " COALESCE(authorization_handle, 'none')"
                                + " FROM payments.payment_attempt WHERE id = ?",
                        UUID.fromString(attemptId)))
                .isEqualTo("FAILED|PROVIDER_UNAVAILABLE|none");

        // Second half - the falsifiable twin: a row HOLDING a handle meets the same
        // verdict and MOVES NOTHING, because an initiation the payer can complete must
        // never be failed by our unavailability (ADR-0062 section 3 adapted).
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String secondPayment = field(confirmedPayment(f, "5.00").body(), "id");
        String secondAttempt = attemptIdOf(secondPayment);
        try (var platform = com.finapp.platform.security.SecurityContext.enterSystem();
                var scope =
                        com.finapp.platform.correlation.CorrelationContext.enter(
                                com.finapp.sharedkernel.correlation.Correlation.startingWith(
                                        com.finapp.sharedkernel.correlation.CorrelationId
                                                .generate(ids)))) {
            com.finapp.payments.PaymentOutcomes.Applied applied =
                    transactions.inTransaction(
                            uow ->
                                    outcomes.applyInitiation(
                                            uow,
                                            PaymentIntentId.of(UUID.fromString(secondPayment)),
                                            com.finapp.payments.PaymentAttemptId.of(
                                                    UUID.fromString(secondAttempt)),
                                            com.finapp.payments.InitiationAnswer.Outcome
                                                    .NOTHING_SENT,
                                            java.util.Optional.empty(),
                                            com.finapp.platform.correlation.CorrelationContext
                                                    .current()
                                                    .orElseThrow()));
            assertThat(applied.acting()).isFalse();
        }
        assertThat(oneString("SELECT status FROM payments.payment_attempt WHERE id = ?",
                        UUID.fromString(secondAttempt)))
                .isEqualTo("AWAITING_PAYER");
    }

    // -----------------------------------------------------------------
    // Security negatives, and the absent reversal surface
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the door authenticates before anything else: a wrong signature, a missing"
            + " header and a stale timestamp are ONE uniform 401 with nothing written")
    void anUnsignedCallbackDiesAtTheDoor() throws Exception {
        long evidenceBefore = count("SELECT count(*) FROM payments.provider_evidence");
        String body = "{\"eventId\":\"evt_" + suffix() + "\",\"status\":\"executed\"}";

        assertThat(provider.deliverTimestampSignedCallback(
                        door(), body, "not-the-key-material-32-bytes!!!".getBytes(
                                StandardCharsets.UTF_8),
                        Instant.now(CLOCK).getEpochSecond(), 1))
                .isEqualTo(401);
        assertThat(provider.deliverCallback(door(), body)).isEqualTo(401);
        assertThat(provider.deliverTimestampSignedCallback(
                        door(), body, WEBHOOK_KEY,
                        Instant.now(CLOCK).getEpochSecond() - 3600, 1))
                .isEqualTo(401);

        assertThat(count("SELECT count(*) FROM payments.provider_evidence"))
                .as("an unauthenticated stranger grows no table (INV-PAY-01)")
                .isEqualTo(evidenceBefore);
    }

    @Test
    @DisplayName("no cancel and no reversal exists for an in-flight pay-by-bank: the DELETE"
            + " answers the capability's refusal with zero wire calls (INV-REV-03)")
    void noCancelNoReversalRouteExists() throws Exception {
        Fixture f = bankFixture();
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String paymentId = field(confirmedPayment(f, "5.00").body(), "id");
        long wired = provider.requestCount(SimulatedInstantSchemeAdapter.INITIATIONS_PATH);

        HttpRequest cancel =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/payments/"
                                + paymentId))
                        .header("Authorization", "Bearer " + f.token())
                        .DELETE()
                        .build();
        assertThat(send(cancel).statusCode()).isEqualTo(409);
        assertThat(provider.requestCount(SimulatedInstantSchemeAdapter.INITIATIONS_PATH))
                .isEqualTo(wired);
        assertThat(oneString("SELECT status FROM payments.payment_attempt WHERE intent_id"
                        + " = ?", UUID.fromString(paymentId)))
                .isEqualTo("AWAITING_PAYER");
    }

    @Test
    @DisplayName("the permit's conditional renewal arbitrates the wire among instances: one"
            + " expected value, one winner")
    void thePermitRenewalArbitratesContacts() throws Exception {
        Fixture f = bankFixture();
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String paymentId = field(confirmedPayment(f, "5.00").body(), "id");
        String attemptId = attemptIdOf(paymentId);
        com.finapp.payments.PaymentAttemptId id =
                com.finapp.payments.PaymentAttemptId.of(UUID.fromString(attemptId));

        Instant expected =
                transactions.inTransaction(
                        uow -> attempts.findById(uow, id).orElseThrow().lastDispatchedAt());
        Instant renewed = Instant.now(CLOCK).plusSeconds(1)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        boolean first =
                transactions.inTransaction(
                        uow -> attempts.renewInitiationPermit(uow, id, expected, renewed));
        boolean second =
                transactions.inTransaction(
                        uow -> attempts.renewInitiationPermit(uow, id, expected, renewed));
        assertThat(first).isTrue();
        assertThat(second)
                .as("the loser of the expected-value conditional skips the wire this tick")
                .isFalse();
    }

    // -----------------------------------------------------------------
    // Fixtures and helpers (the WithdrawalDatabaseTest idiom)
    // -----------------------------------------------------------------

    private record Fixture(String token, String methodId, String product) {}

    /** A verified customer with a USD wallet and a registered bank account - no card. */
    private Fixture bankFixture() throws Exception {
        String token = verifiedCustomer(someLogin());
        String product = openAccount(token);
        String methodId = registerBankAccount(token);
        return new Fixture(token, methodId, product);
    }

    /** Creates and confirms a pay-by-bank payment; answers the confirmation response. */
    private HttpResponse<String> confirmedPayment(Fixture f, String amount) throws Exception {
        HttpResponse<String> created =
                post("/v1/payments", body(f.methodId(), amount, "USD"), f.token(), true);
        assertThat(created.statusCode()).isEqualTo(201);
        String paymentId = field(created.body(), "id");
        HttpResponse<String> confirmed =
                post("/v1/payments/" + paymentId + "/confirmation", null, f.token(), false);
        assertThat(confirmed.statusCode()).isEqualTo(200);
        return confirmed;
    }

    /** The initiation opens with a handle minted per request (the stub's dedupe shape). */
    private void schemeInitiates(String handle) {
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.INITIATIONS_PATH,
                200,
                "{\"status\":\"initiated\",\"handle\":\"" + handle + "\"}");
    }

    private int executedCallback(
            String reference, String scheme, String cycle, String amount, String currency) {
        return callback(
                "{\"eventId\":\"evt_" + suffix() + "\",\"reference\":\"" + reference
                        + "\",\"status\":\"executed\",\"schemeReference\":\"" + scheme
                        + "\",\"settlementCycle\":\"" + cycle + "\",\"amount\":\"" + amount
                        + "\",\"currency\":\"" + currency + "\"}");
    }

    private int callback(String body) {
        return provider.deliverTimestampSignedCallback(
                door(), body, WEBHOOK_KEY, Instant.now(CLOCK).getEpochSecond(), 1);
    }

    private URI door() {
        return URI.create(
                "http://localhost:" + port + "/v1/providers/payments/instant/webhooks");
    }

    private String attemptIdOf(String paymentId) throws SQLException {
        return oneString("SELECT id::text FROM payments.payment_attempt WHERE intent_id = ?",
                UUID.fromString(paymentId));
    }

    private String referenceOf(String attemptId) throws SQLException {
        return oneString(
                "SELECT end_to_end_reference FROM payments.payment_attempt WHERE id = ?",
                UUID.fromString(attemptId));
    }

    /**
     * The wired sweep with a batch wide enough to reach THIS test's row however many
     * waiting rows other suites left behind (the shared-database citizenship lesson): the
     * same real components as the bean, the same tiny candidacy bound.
     */
    private PayInResolution.SweepResult wideSweep() {
        return new PayInResolution(
                        attempts,
                        intents,
                        outcomes,
                        instantRailBean,
                        evidenceBean,
                        new PayInResolution.Config(Duration.ofMillis(50), 2_000),
                        ids,
                        CLOCK,
                        transactions)
                .sweep();
    }

    @Autowired private PushRail instantRailBean;

    /** The confirmation engine against a port nothing listens on: the refused connection, real. */
    private PaymentConfirmation offlineConfirmation() {
        PushRail dead =
                new SimulatedInstantSchemeAdapter(
                        URI.create("http://127.0.0.1:1"),
                        Duration.ofMillis(300),
                        "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
        return new PaymentConfirmation(
                transactions,
                intents,
                attempts,
                evidenceBean,
                participantsBean,
                providerBean,
                outcomes,
                routingStoreBean,
                railsBean,
                auditWriterBean,
                outboxWriterBean,
                ids,
                CLOCK,
                com.finapp.payments.RoutingTelemetry.NONE,
                java.util.Optional.of(dead));
    }

    @Autowired private com.finapp.payments.ProviderEvidenceStore<Connection> evidenceBean;
    @Autowired private com.finapp.payments.PaymentParticipants<Connection> participantsBean;
    @Autowired private com.finapp.payments.PaymentProvider providerBean;
    @Autowired private com.finapp.payments.RoutingStore<Connection> routingStoreBean;
    @Autowired private com.finapp.payments.PaymentRails railsBean;
    @Autowired private com.finapp.platform.audit.AuditWriter<Connection> auditWriterBean;
    @Autowired private com.finapp.platform.outbox.OutboxWriter<Connection> outboxWriterBean;

    // -----------------------------------------------------------------
    // Registration and account fixtures (the WithdrawalDatabaseTest idiom, verbatim)
    // -----------------------------------------------------------------

    private String verifiedCustomer(String login) throws Exception {
        assertThat(register(login).statusCode()).isEqualTo(201);
        execute(
                "UPDATE party.customer SET status = 'ACTIVE',"
                        + " status_changed_at = GREATEST(now(), opened_at)"
                        + " WHERE party_id = (SELECT party_id FROM identity.identity"
                        + " WHERE login_identifier = '" + login + "')");
        return tokenFrom(authenticate(login).body());
    }

    private String openAccount(String token) throws Exception {
        HttpResponse<String> opened =
                post("/v1/me/accounts", "{\"productType\":\"WALLET\",\"currency\":\"USD\"}",
                        token, true);
        assertThat(opened.statusCode()).isEqualTo(201);
        return field(opened.body(), "id");
    }

    /** Registers a bank account through the real exchange; returns the method id. */
    private String registerBankAccount(String token) throws Exception {
        String dest = "dest-pbb-" + suffix();
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.EXCHANGES_PATH,
                200,
                "{\"status\":\"exchanged\",\"destination\":\"" + dest
                        + "\",\"suffix\":\"6819\",\"payee\":\"match\"}");
        HttpResponse<String> registered =
                post(
                        "/v1/me/payment-methods/bank-accounts",
                        "{\"grant\":\"blg-" + suffix() + "\",\"acknowledgeNoMatch\":false}",
                        token,
                        true);
        assertThat(registered.statusCode()).isEqualTo(201);
        return field(registered.body(), "id");
    }

    private static String body(String methodId, String amount, String currency) {
        return "{\"paymentMethodId\":\"" + methodId + "\",\"amount\":\"" + amount
                + "\",\"currency\":\"" + currency + "\"}";
    }

    private HttpResponse<String> register(String login) throws Exception {
        return post(
                "/v1/registrations",
                "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                        + "\"password\":\"" + PASSWORD + "\"}",
                null,
                true);
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post(
                "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}",
                null,
                false);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
        return send(request);
    }

    private HttpResponse<String> post(String path, String body, String token, boolean keyed)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (keyed) {
            request.header(IdempotencyKeyHeader.NAME, someKey());
        }
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String field(String body, String name) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile(
                                "\"" + java.util.regex.Pattern.quote(name) + "\":\"([^\"]+)\"")
                        .matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    private static String tokenFrom(String body) {
        return field(body, "sessionToken");
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String someLogin() {
        return "pbb." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static void execute(String sql) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql);
                ResultSet row = statement.executeQuery()) {
            assertThat(row.next()).isTrue();
            return row.getLong(1);
        }
    }

    private static String oneString(String sql) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql);
                ResultSet row = statement.executeQuery()) {
            assertThat(row.next()).as("expected one row: %s", sql).isTrue();
            return row.getString(1);
        }
    }

    private static String oneString(String sql, Object argument) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            statement.setObject(1, argument);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("expected one row: %s", sql).isTrue();
                return row.getString(1);
            }
        }
    }

    private static UUID oneUuid(String sql, Object argument) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            statement.setObject(1, argument);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("expected one row: %s", sql).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private static long outboxCount(String eventType, String aggregateId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.outbox_event"
                                        + " WHERE event_type = ? AND aggregate_id = ?")) {
            statement.setString(1, eventType);
            statement.setObject(2, UUID.fromString(aggregateId));
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }
}
