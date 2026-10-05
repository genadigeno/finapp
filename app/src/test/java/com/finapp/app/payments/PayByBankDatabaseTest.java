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
import com.finapp.app.reconciliation.ClearingLineCopies;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.KeyKind;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
@ExtendWith(OutputCaptureExtension.class)
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
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;
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

        // ITS EXPECTATION (P8-TSK-005, ADR-0067): the execution's clearing line's copy, INBOUND,
        // keyed by the scheme's reference and our end-to-end reference - and the cycle the
        // confirmation announced kept as the ATTRIBUTE it is, never a key.
        ClearingLineCopies.Opened payIn =
                ClearingLineCopies.assertOpensItsClearingLinesCopy(
                        ExpectationKind.PUSH_PAY_IN, attemptId,
                        "payment-execution:" + attemptId, ExpectationDirection.INBOUND);
        assertThat(payIn.amountMinor()).isEqualTo(500);
        assertThat(payIn.settlementCycle()).contains("C7");
        ClearingLineCopies.assertKeyed(payIn, KeyKind.SCHEME_REF, scheme);
        ClearingLineCopies.assertKeyed(payIn, KeyKind.END_TO_END_REF, referenceOf(attemptId));

        // The pinned decision: version 3's bank pay-in rule chose the instant rail.
        assertThat(oneString(
                        "SELECT chosen_rail || '|' || v.version::text"
                                + " FROM payments.routing_decision d"
                                + " JOIN payments.routing_policy_version v"
                                + " ON v.id = d.policy_version_id WHERE d.intent_id = ?"
                                + " AND d.chosen_rail IS NOT NULL",
                        UUID.fromString(paymentId)))
                .isEqualTo("instant|4");

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
    @DisplayName("ten CONCURRENT duplicate callbacks - five under one event id, five fresh -"
            + " credit ONCE: one inbox record for the shared id, one entry, one EXECUTED edge,"
            + " one executed fact and one acting audit record (INV-IDEM-04, P7-DOC-001)")
    void tenDuplicateCallbacksCreditOnce() throws Exception {
        Fixture f = bankFixture();
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String paymentId = field(confirmedPayment(f, "4.00").body(), "id");
        String attemptId = attemptIdOf(paymentId);
        String reference = referenceOf(attemptId);
        String scheme = "sch-dup-" + suffix();
        String shared = "evt_shared_" + suffix();

        // The gate's third scenario as written (PHASE_7_PLAN section 13.3): ten deliveries AT
        // ONCE under identical AND distinct event ids - the inbox's rank and the conditional's.
        // The review found them delivered one after another, under fresh ids only.
        java.util.concurrent.CountDownLatch open = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(10);
        try {
            java.util.List<java.util.concurrent.Future<Integer>> answers =
                    new java.util.ArrayList<>();
            for (int delivery = 0; delivery < 10; delivery++) {
                String eventId = delivery < 5 ? shared : "evt_" + suffix();
                String body =
                        "{\"eventId\":\"" + eventId + "\",\"reference\":\"" + reference
                                + "\",\"status\":\"executed\",\"schemeReference\":\"" + scheme
                                + "\",\"settlementCycle\":\"C1\",\"amount\":\"4.00\","
                                + "\"currency\":\"USD\"}";
                answers.add(pool.submit(() -> {
                    open.await();
                    // The door's one 409 is the inbox's contended record: redelivered, as the
                    // scheme would, until acknowledged.
                    for (int attempt = 0; attempt < 200; attempt++) {
                        int status = callback(body);
                        if (status != 409) {
                            return status;
                        }
                        Thread.sleep(20);
                    }
                    return 409;
                }));
            }
            open.countDown();
            for (java.util.concurrent.Future<Integer> answer : answers) {
                assertThat(answer.get()).isEqualTo(204);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("SELECT count(*) FROM platform.inbox_message WHERE consumer ="
                        + " 'payments.instant-webhook' AND dedupe_key = '"
                        + SimulatedInstantSchemeAdapter.NAME + ":" + shared + "'"))
                .as("the five identical deliveries are one inbox record")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-execution:" + attemptId + "'"))
                .isEqualTo(1);
        assertThat(outboxCount("payments.PaymentExecuted", paymentId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payments.payment_attempt_event WHERE"
                        + " attempt_id = '" + attemptId + "'::uuid AND to_status = 'EXECUTED'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                        + " 'payments.PaymentOutcomeApplied' AND target_id = '" + paymentId
                        + "' AND change_summary LIKE '%EXECUTED%'"))
                .as("resolvers record acting transitions only: one record for ten deliveries")
                .isEqualTo(1);
        assertThat(ClearingLineCopies.expectationsOf(ExpectationKind.PUSH_PAY_IN, attemptId))
                .as("ten deliveries, one expectation - the acting exit decides (P8-TSK-005)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("INV-RAIL-03 through the money paths: a bank account's destination and its"
            + " grant reach no response, no audit record, no event and no log line across the"
            + " registration, a pay-in funding the wallet and a withdrawal out of it (P7-DOC-001)")
    void theDestinationReachesNoSink(CapturedOutput output) throws Exception {
        String token = verifiedCustomer(someLogin());
        String product = openAccount(token);
        String needle = "dest-NEEDLE-" + suffix();
        String grant = "blg-GRANT-" + suffix();
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.EXCHANGES_PATH,
                200,
                "{\"status\":\"exchanged\",\"destination\":\"" + needle
                        + "\",\"suffix\":\"6819\",\"payee\":\"match\"}");
        HttpResponse<String> registered =
                post("/v1/me/payment-methods/bank-accounts",
                        "{\"grant\":\"" + grant + "\",\"acknowledgeNoMatch\":false}", token, true);
        assertThat(registered.statusCode()).isEqualTo(201);
        Fixture f = new Fixture(token, field(registered.body(), "id"), product);

        // A pay-in funds the wallet; its confirmation delivered twice under one event id.
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String paymentId = field(confirmedPayment(f, "5.00").body(), "id");
        String executed =
                "{\"eventId\":\"evt_" + suffix() + "\",\"reference\":\""
                        + referenceOf(attemptIdOf(paymentId))
                        + "\",\"status\":\"executed\",\"schemeReference\":\"sch-needle-"
                        + suffix() + "\",\"settlementCycle\":\"C1\",\"amount\":\"5.00\","
                        + "\"currency\":\"USD\"}";
        assertThat(callback(executed)).isEqualTo(204);
        assertThat(callback(executed)).isEqualTo(204);

        // A withdrawal to the registered destination: the one path that SENDS it.
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.TRANSFERS_PATH,
                200,
                "{\"status\":\"accepted\",\"reference\":\"sch-w-{{request.headers.Idempotency-Key}}"
                        + "\",\"cycle\":\"C2\"}");
        HttpResponse<String> withdrawn =
                post("/v1/me/withdrawals", body(f.methodId(), "2.00", "USD"), token, true);
        assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(201);
        String read = get("/v1/me/withdrawals/" + field(withdrawn.body(), "id"), token).body();

        assertThat(output.getAll())
                .as("precondition: the flow's own log lines were captured, or nothing is read")
                .contains("A duplicate instant confirmation delivery was absorbed");
        for (String secret : java.util.List.of(needle, grant)) {
            assertThat(output.getAll()).as("no log line carries %s", secret)
                    .doesNotContain(secret);
            assertThat(registered.body() + withdrawn.body() + read)
                    .as("no response carries %s", secret)
                    .doesNotContain(secret);
            assertThat(count("SELECT count(*) FROM platform.audit_record WHERE"
                            + " coalesce(change_summary, '') || coalesce(reason, '') LIKE '%"
                            + secret + "%'"))
                    .as("no audit record carries %s", secret)
                    .isZero();
            assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE"
                            + " convert_from(payload, 'UTF8') LIKE '%" + secret + "%'"))
                    .as("no event carries %s", secret)
                    .isZero();
        }
        assertThat(count("SELECT count(*) FROM paymentmethods.payment_method"
                        + " WHERE destination_reference = '" + needle + "'"))
                .as("the one place the opaque destination rests: its instrument's own column")
                .isEqualTo(1);

        // AND THE WALLET'S STATEMENT RECONCILES LINE BY LINE TO THE JOURNAL (PHASE_GATES
        // section 5's wallet bullet; the Phase 7 review found it proven only over Phase 3's
        // synthetic postings): every journal line on this wallet - the pay-in's credit under
        // payment-execution:, the withdrawal's debit under wallet-withdrawal: - is exactly one
        // statement line naming its entry, direction and amount, and the statement has no other.
        String wallet = oneString(
                "SELECT l.ledger_account_id::text FROM ledger.journal_line l"
                        + " JOIN ledger.journal_entry e ON e.id = l.entry_id"
                        + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                        + " WHERE e.idempotency_scope = 'ledger.post:payment-execution:"
                        + attemptIdOf(paymentId) + "' AND a.purpose = 'CUSTOMER_WALLET'");
        String period = "?from=" + java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1)
                + "&to=" + java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1);
        String statement = get("/v1/me/accounts/" + product + "/statement" + period, token).body();
        java.util.List<String> journal = new java.util.ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement lines = app.prepareStatement(
                        "SELECT e.id::text, e.idempotency_scope, l.direction, l.amount_minor,"
                                + " l.scale FROM ledger.journal_line l"
                                + " JOIN ledger.journal_entry e ON e.id = l.entry_id"
                                + " WHERE l.ledger_account_id = ?::uuid ORDER BY e.id")) {
            lines.setString(1, wallet);
            try (ResultSet row = lines.executeQuery()) {
                while (row.next()) {
                    String entry = row.getString(1);
                    journal.add(row.getString(2).split(":")[1]);
                    int at = statement.indexOf("\"" + entry + "\"");
                    assertThat(at).as("the statement names entry %s", entry).isNotNegative();
                    assertThat(statement.indexOf("\"" + entry + "\"", at + 1))
                            .as("entry %s is one statement line", entry)
                            .isNegative();
                    String line = statement.substring(
                            statement.lastIndexOf('{', at), statement.indexOf('}', at) + 1);
                    assertThat(line)
                            .contains("\"direction\":\"" + row.getString(3) + "\"")
                            .contains("\"amount\":\"" + java.math.BigDecimal.valueOf(
                                    row.getLong(4), row.getInt(5)).toPlainString() + "\"");
                }
            }
        }
        assertThat(journal)
                .as("the wallet's journal: the pay-in's credit and the withdrawal's debit")
                .containsExactly("payment-execution", "wallet-withdrawal");
        assertThat(statement.split("\"entryId\"", -1).length - 1)
                .as("the statement carries no line the journal does not")
                .isEqualTo(journal.size());
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
        // What Phase 8 resolves it with (the Phase 7 -> 8 transition): why it parked and the
        // reference it named - and all ten deliveries' bytes addressed to the parking.
        assertThat(oneString("SELECT cause || '|' || coalesce(attempt_id::text, '-') || '|'"
                        + " || named_reference FROM payments.unmatched_confirmation WHERE"
                        + " scheme_reference = ?", scheme))
                .isEqualTo("UNATTRIBUTED|-|" + foreign);
        assertThat(count("SELECT count(*) FROM payments.provider_evidence e"
                        + " JOIN payments.unmatched_confirmation u"
                        + "   ON u.id = e.unmatched_confirmation_id"
                        + " WHERE u.scheme_reference = '" + scheme + "'"))
                .isEqualTo(10);

        // ITS EXPECTATION (P8-TSK-005, ADR-0067 §2): the parking's clearing line's copy, opened
        // once under ten deliveries, keyed by the scheme's reference ALONE - the named reference
        // is no key (it travels to -020's suspense item).
        ClearingLineCopies.Opened parked =
                ClearingLineCopies.assertOpensItsClearingLinesCopy(
                        ExpectationKind.UNMATCHED_CONFIRMATION, "instant:" + scheme,
                        "unmatched-confirmation:instant:" + scheme, ExpectationDirection.INBOUND);
        assertThat(parked.amountMinor()).isEqualTo(750);
        ClearingLineCopies.assertKeyed(parked, KeyKind.SCHEME_REF, scheme);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_key WHERE"
                        + " expectation_id = '" + parked.id() + "'"))
                .as("one key: the scheme's reference")
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
    @DisplayName("a confirmation whose stated amount differs from the initiation's ask credits"
            + " NOTHING: the value the scheme executed parks (AMOUNT_MISMATCH, attributed) and"
            + " the pay-in fails DECLINED - booked, alerted, never asked forever (the Phase 7"
            + " -> 8 transition)")
    void aMismatchedAmountParksTheExecutedValueAndFailsThePayIn() throws Exception {
        Fixture f = bankFixture();
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String paymentId = field(confirmedPayment(f, "5.00").body(), "id");
        String attemptId = attemptIdOf(paymentId);
        String scheme = "sch-mismatch-" + suffix();

        assertThat(executedCallback(referenceOf(attemptId), scheme, "C1", "4.99", "USD"))
                .isEqualTo(204);

        assertThat(oneString("SELECT status || '|' || failure_reason FROM"
                        + " payments.payment_attempt WHERE id = ?", UUID.fromString(attemptId)))
                .isEqualTo("FAILED|DECLINED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-execution:" + attemptId + "'"))
                .as("the initiation's ask is never credited")
                .isZero();
        // The value that DID move rests in suspense, attributed, at the EXECUTED amount.
        assertThat(oneString("SELECT cause || '|' || attempt_id::text || '|' || named_reference"
                        + " || '|' || settlement_cycle || '|' || amount_minor::text"
                        + " FROM payments.unmatched_confirmation WHERE scheme_reference = ?",
                        scheme))
                .isEqualTo("AMOUNT_MISMATCH|" + attemptId + "|" + referenceOf(attemptId)
                        + "|C1|499");
        assertThat(oneString("SELECT subject_kind FROM payments.scheme_execution_claim"
                        + " WHERE rail = 'instant' AND scheme_reference = ?", scheme))
                .isEqualTo("UNMATCHED");
        // THE EXPECTATIONS (P8-TSK-005): the parked value opens UNMATCHED_CONFIRMATION at the
        // EXECUTED amount, its stored cycle the attribute; the failed pay-in opens nothing.
        ClearingLineCopies.Opened parked =
                ClearingLineCopies.assertOpensItsClearingLinesCopy(
                        ExpectationKind.UNMATCHED_CONFIRMATION, "instant:" + scheme,
                        "unmatched-confirmation:instant:" + scheme, ExpectationDirection.INBOUND);
        assertThat(parked.amountMinor()).isEqualTo(499);
        assertThat(parked.settlementCycle()).contains("C1");
        assertThat(ClearingLineCopies.expectationsOf(ExpectationKind.PUSH_PAY_IN, attemptId))
                .as("the mismatched pay-in posted no execution and opened no PUSH_PAY_IN")
                .isZero();
    }

    @Test
    @DisplayName("the inquiry sweep judges the executed amount too (C2 of the Phase 7 -> 8"
            + " transition): a mismatched acceptance parks and fails, never crediting the ask;"
            + " an acceptance without a usable amount moves nothing")
    void theInquirySweepJudgesTheExecutedAmount() throws Exception {
        // An acceptance with NO amount: unactionable - the row waits, nothing posts.
        Fixture f = bankFixture();
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String waiting = attemptIdOf(field(confirmedPayment(f, "7.00").body(), "id"));
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.INITIATION_STATUS_PATH + referenceOf(waiting),
                200,
                "{\"status\":\"accepted\",\"reference\":\"sch-noamt-" + suffix() + "\"}");

        // A MISMATCHED acceptance: the value parks, the pay-in fails, the ask never credits.
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String paymentId = field(confirmedPayment(f, "9.00").body(), "id");
        String mismatched = attemptIdOf(paymentId);
        String scheme = "sch-sweep-mm-" + suffix();
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.INITIATION_STATUS_PATH + referenceOf(mismatched),
                200,
                "{\"status\":\"accepted\",\"reference\":\"" + scheme + "\",\"cycle\":\"C5\","
                        + "\"amount\":\"8.00\",\"currency\":\"USD\"}");
        Thread.sleep(80); // past the tiny candidacy bound

        // And at the door: an attributed 'executed' statement carrying NO amount reaches the
        // applier (the door's own shape check marks it unmappable) and moves nothing either -
        // the applier's no-amount rule, which the adapter's INDETERMINATE shields on the sweep.
        assertThat(callback(
                        "{\"eventId\":\"evt_" + suffix() + "\",\"reference\":\""
                                + referenceOf(waiting) + "\",\"status\":\"executed\","
                                + "\"schemeReference\":\"sch-nomoney-" + suffix() + "\"}"))
                .isEqualTo(204);

        wideSweep();

        assertThat(oneString("SELECT status FROM payments.payment_attempt WHERE id = ?",
                        UUID.fromString(waiting)))
                .as("an acceptance the applier cannot judge moves nothing, at the door or"
                        + " the sweep")
                .isEqualTo("AWAITING_PAYER");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-execution:" + waiting + "'"))
                .isZero();

        assertThat(oneString("SELECT status || '|' || failure_reason FROM"
                        + " payments.payment_attempt WHERE id = ?", UUID.fromString(mismatched)))
                .isEqualTo("FAILED|DECLINED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-execution:" + mismatched + "'"))
                .as("the sweep never credits an ask the scheme did not execute")
                .isZero();
        assertThat(oneString("SELECT cause || '|' || amount_minor::text FROM"
                        + " payments.unmatched_confirmation WHERE scheme_reference = ?", scheme))
                .isEqualTo("AMOUNT_MISMATCH|800");
        assertThat(field(get("/v1/payments/" + paymentId, f.token()).body(), "status"))
                .isEqualTo("FAILED");
    }

    @Test
    @DisplayName("an initiation the payer PSP reports EXPIRED ends through the sweep (the"
            + " Phase 7 -> 8 transition): FAILED(DECLINED), nothing posted - never asked"
            + " forever with its intent PROCESSING")
    void anExpiredInitiationEndsThroughTheSweep() throws Exception {
        Fixture f = bankFixture();
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String paymentId = field(confirmedPayment(f, "4.00").body(), "id");
        String attemptId = attemptIdOf(paymentId);
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.INITIATION_STATUS_PATH + referenceOf(attemptId),
                200,
                "{\"status\":\"expired\"}");
        Thread.sleep(80);

        wideSweep();

        assertThat(oneString("SELECT status || '|' || failure_reason FROM"
                        + " payments.payment_attempt WHERE id = ?", UUID.fromString(attemptId)))
                .isEqualTo("FAILED|DECLINED");
        assertThat(field(get("/v1/payments/" + paymentId, f.token()).body(), "status"))
                .isEqualTo("FAILED");
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
    // One scheme execution, one money fact (the Phase 7 -> 8 transition, V023)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a WITHDRAWAL's own confirmation echoed to the pay-in door never parks - by"
            + " our reference, or restated without it: the value went OUT (C1 of the Phase 7"
            + " -> 8 transition)")
    void aWithdrawalsOwnConfirmationNeverParks(CapturedOutput output) throws Exception {
        Fixture f = bankFixture();
        // Fund the wallet over the pay-in itself.
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String funding = attemptIdOf(field(confirmedPayment(f, "20.00").body(), "id"));
        assertThat(executedCallback(
                        referenceOf(funding), "sch-fund-" + suffix(), "C1", "20.00", "USD"))
                .isEqualTo(204);
        // Withdraw 5.00; the scheme accepts, minting its reference from our key.
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.TRANSFERS_PATH,
                200,
                "{\"status\":\"accepted\",\"reference\":\"sch-wd-"
                        + "{{request.headers.Idempotency-Key}}\",\"cycle\":\"C9\"}");
        HttpResponse<String> withdrawn =
                post("/v1/me/withdrawals", body(f.methodId(), "5.00", "USD"), f.token(), true);
        assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(201);
        assertThat(field(withdrawn.body(), "status")).isEqualTo("COMPLETED");
        UUID withdrawal = UUID.fromString(field(withdrawn.body(), "id"));
        String ours =
                oneString("SELECT end_to_end_reference FROM payments.withdrawal WHERE id = ?",
                        withdrawal);
        String theirs =
                oneString("SELECT scheme_reference FROM payments.withdrawal WHERE id = ?",
                        withdrawal);

        // The echo, by OUR reference - recognised as the withdrawal's own at the door, before
        // any parking is attempted (the claim below is the second rank, not the first) - and
        // restated under a reference we never minted.
        assertThat(executedCallback(ours, theirs, "C9", "5.00", "USD")).isEqualTo(204);
        assertThat(output.getOut())
                .as("the door names the withdrawal's echo as what it is")
                .contains("named a withdrawal's own reference");
        assertThat(callback(
                        "{\"eventId\":\"evt_" + suffix() + "\",\"reference\":\""
                                + UUID.randomUUID().toString().replace("-", "")
                                + "\",\"status\":\"executed\",\"schemeReference\":\"" + theirs
                                + "\",\"amount\":\"5.00\",\"currency\":\"USD\"}"))
                .isEqualTo(204);

        assertThat(count("SELECT count(*) FROM payments.unmatched_confirmation WHERE"
                        + " scheme_reference = '" + theirs + "'"))
                .as("value that went OUT is never booked as value that came in")
                .isZero();
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:unmatched-confirmation:instant:" + theirs + "'"))
                .isZero();
        assertThat(oneString("SELECT subject_kind || '|' || subject_id::text FROM"
                        + " payments.scheme_execution_claim WHERE rail = 'instant' AND"
                        + " scheme_reference = ?", theirs))
                .isEqualTo("WITHDRAWAL|" + withdrawal);
        assertThat(count("SELECT count(*) FROM payments.provider_evidence WHERE"
                        + " withdrawal_id = '" + withdrawal + "' AND kind = 'WEBHOOK'"))
                .as("the echo's bytes rest addressed to the withdrawal it echoes")
                .isEqualTo(1);
        // The wallet explains itself: 20 in, 5 out.
        assertThat(get("/v1/me/accounts/" + f.product() + "/balance", f.token()).body())
                .contains("\"settled\":\"15.00\"");
    }

    @Test
    @DisplayName("a credited execution restated WITHOUT our reference never parks beside its"
            + " credit (the gate's sequential find): one arrival, one money fact")
    void aCreditedExecutionRestatedUnattributedNeverParks() throws Exception {
        Fixture f = bankFixture();
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String attemptId = attemptIdOf(field(confirmedPayment(f, "3.00").body(), "id"));
        String scheme = "sch-credited-" + suffix();
        assertThat(executedCallback(referenceOf(attemptId), scheme, "C1", "3.00", "USD"))
                .isEqualTo(204);

        assertThat(callback(
                        "{\"eventId\":\"evt_" + suffix() + "\",\"status\":\"executed\","
                                + "\"schemeReference\":\"" + scheme
                                + "\",\"amount\":\"3.00\",\"currency\":\"USD\"}"))
                .isEqualTo(204);

        assertThat(count("SELECT count(*) FROM payments.unmatched_confirmation WHERE"
                        + " scheme_reference = '" + scheme + "'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-execution:" + attemptId + "'"))
                .isEqualTo(1);
        assertThat(oneString("SELECT subject_kind FROM payments.scheme_execution_claim WHERE"
                        + " rail = 'instant' AND scheme_reference = ?", scheme))
                .isEqualTo("PAY_IN");
    }

    @Test
    @DisplayName("ten racing deliveries of ONE execution - five by our reference, five without"
            + " it, all fresh event ids - make exactly ONE money fact: a credit or a parking,"
            + " never both (the gate's concurrent find, V023's arbiter)")
    void tenRacingDeliveriesOfOneExecutionMakeOneMoneyFact() throws Exception {
        for (int round = 0; round < 3; round++) {
            Fixture f = bankFixture();
            schemeInitiates("https://payer-psp.example/authorize/" + suffix());
            String attemptId = attemptIdOf(field(confirmedPayment(f, "6.00").body(), "id"));
            String reference = referenceOf(attemptId);
            String scheme = "sch-race-mix-" + suffix();
            String foreign = UUID.randomUUID().toString().replace("-", "");

            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(10);
            java.util.List<java.util.concurrent.Future<Integer>> answers =
                    new java.util.ArrayList<>();
            for (int racer = 0; racer < 10; racer++) {
                String named = racer % 2 == 0 ? reference : foreign;
                String body =
                        "{\"eventId\":\"evt_" + suffix() + "\",\"reference\":\"" + named
                                + "\",\"status\":\"executed\",\"schemeReference\":\""
                                + scheme + "\",\"settlementCycle\":\"C2\","
                                + "\"amount\":\"6.00\",\"currency\":\"USD\"}";
                answers.add(pool.submit(() -> {
                    start.await();
                    return callback(body);
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<Integer> answer : answers) {
                assertThat(answer.get(60, java.util.concurrent.TimeUnit.SECONDS))
                        .as("every racer is acknowledged - never a 5xx")
                        .isEqualTo(204);
            }
            pool.shutdown();

            long credited = count("SELECT count(*) FROM ledger.journal_entry WHERE"
                    + " idempotency_scope = 'ledger.post:payment-execution:" + attemptId + "'");
            long parked = count("SELECT count(*) FROM ledger.journal_entry WHERE"
                    + " idempotency_scope = 'ledger.post:unmatched-confirmation:instant:"
                    + scheme + "'");
            assertThat(credited + parked)
                    .as("round %d: one execution, one money fact (credited %d, parked %d)",
                            round, credited, parked)
                    .isEqualTo(1);
            assertThat(count("SELECT count(*) FROM payments.scheme_execution_claim WHERE"
                            + " rail = 'instant' AND scheme_reference = '" + scheme + "'"))
                    .isEqualTo(1);
            assertThat(oneString("SELECT status FROM payments.payment_attempt WHERE id = ?",
                            UUID.fromString(attemptId)))
                    .as("the row agrees with the one fact")
                    .isEqualTo(credited == 1 ? "EXECUTED" : "AWAITING_PAYER");

            // ONE MONEY FACT, ONE EXPECTATION (P8-TSK-005, the gate's find): the credit and the
            // parking are the two openers that could both claim this scheme reference, and
            // under ten racers exactly the winner's opens - the pay-in's or the parking's -
            // with the scheme reference keyed once and no collision recorded.
            long payIns = ClearingLineCopies.expectationsOf(ExpectationKind.PUSH_PAY_IN, attemptId);
            long parkings = ClearingLineCopies.expectationsOf(
                    ExpectationKind.UNMATCHED_CONFIRMATION, "instant:" + scheme);
            assertThat(new long[] {payIns, parkings})
                    .as("round %d: the one money fact's expectation, and no other", round)
                    .containsExactly(credited, parked);
            if (credited == 1) {
                ClearingLineCopies.assertOpensItsClearingLinesCopy(
                        ExpectationKind.PUSH_PAY_IN, attemptId,
                        "payment-execution:" + attemptId, ExpectationDirection.INBOUND);
            } else {
                ClearingLineCopies.assertOpensItsClearingLinesCopy(
                        ExpectationKind.UNMATCHED_CONFIRMATION, "instant:" + scheme,
                        "unmatched-confirmation:instant:" + scheme,
                        ExpectationDirection.INBOUND);
            }
            assertThat(count("SELECT count(*) FROM reconciliation.expectation_key WHERE"
                            + " key_kind = 'SCHEME_REF' AND key_value = '" + scheme + "'"))
                    .as("round %d: the scheme reference keyed once", round)
                    .isEqualTo(1);
            assertThat(count("SELECT count(*) FROM reconciliation.expectation_event ev"
                            + " JOIN reconciliation.expectation x ON x.id = ev.expectation_id"
                            + " WHERE ev.event_type = 'KEY_COLLISION' AND x.operation_ref IN ('"
                            + attemptId + "', 'instant:" + scheme + "')"))
                    .as("round %d: no collision - V023's claim arbitrated before either opened",
                            round)
                    .isZero();
        }
    }

    @Test
    @DisplayName("value stated on an attempt that cannot move PARKS (the Phase 7 -> 8"
            + " transition): an execution on a FAILED attempt, and a SECOND execution under"
            + " another scheme reference on an EXECUTED one - attributed, audited, its evidence"
            + " addressed to the parking; the same execution repeated is evidence alone")
    void valueOnAConcludedAttemptParks() throws Exception {
        // FAILED: the payer PSP said expired, then the payer's execution arrives.
        Fixture f = bankFixture();
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String failed = attemptIdOf(field(confirmedPayment(f, "8.00").body(), "id"));
        assertThat(callback(
                        "{\"eventId\":\"evt_" + suffix() + "\",\"reference\":\""
                                + referenceOf(failed) + "\",\"status\":\"expired\"}"))
                .isEqualTo(204);
        String late = "sch-late-" + suffix();
        assertThat(executedCallback(referenceOf(failed), late, "C3", "8.00", "USD"))
                .isEqualTo(204);
        assertThat(oneString("SELECT status FROM payments.payment_attempt WHERE id = ?",
                        UUID.fromString(failed)))
                .isEqualTo("FAILED");
        assertThat(oneString("SELECT cause || '|' || attempt_id::text || '|' || named_reference"
                        + " || '|' || settlement_cycle FROM payments.unmatched_confirmation"
                        + " WHERE scheme_reference = ?", late))
                .isEqualTo("ATTEMPT_CONCLUDED|" + failed + "|" + referenceOf(failed) + "|C3");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                        + " 'payments.UnmatchedConfirmationParked' AND target_id = '" + late
                        + "' AND change_summary LIKE '%cause=ATTEMPT_CONCLUDED%'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payments.provider_evidence e"
                        + " JOIN payments.unmatched_confirmation u"
                        + "   ON u.id = e.unmatched_confirmation_id"
                        + " WHERE u.scheme_reference = '" + late + "'"))
                .as("the parking's raw statement is found by stored identifier")
                .isEqualTo(1);

        // EXECUTED under S1; a second execution under S2 parks, S1 repeated does not.
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String executed = attemptIdOf(field(confirmedPayment(f, "2.50").body(), "id"));
        String first = "sch-first-" + suffix();
        String second = "sch-second-" + suffix();
        assertThat(executedCallback(referenceOf(executed), first, "C4", "2.50", "USD"))
                .isEqualTo(204);
        assertThat(executedCallback(referenceOf(executed), first, "C4", "2.50", "USD"))
                .isEqualTo(204);
        assertThat(executedCallback(referenceOf(executed), second, "C4", "2.50", "USD"))
                .isEqualTo(204);
        assertThat(count("SELECT count(*) FROM payments.unmatched_confirmation WHERE"
                        + " scheme_reference = '" + first + "'"))
                .as("the rail repeating itself is evidence")
                .isZero();
        assertThat(oneString("SELECT cause || '|' || attempt_id::text FROM"
                        + " payments.unmatched_confirmation WHERE scheme_reference = ?", second))
                .isEqualTo("ATTEMPT_CONCLUDED|" + executed);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-execution:" + executed + "'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a fresh-id duplicate of a parked execution on a LATER DAY, or stating another"
            + " amount, converges on the parking without posting (the gate's poison-delivery"
            + " find): one entry, one row, the delivery never refused")
    void aLaterDaysDuplicateConvergesWithoutPosting() throws Exception {
        String scheme = "sch-dayline-" + suffix();
        java.time.Instant today = java.time.Instant.parse("2026-09-28T23:59:30Z");
        com.finapp.payments.UnmatchedConfirmations.Parked first =
                parkAt(Clock.fixed(today, ZoneOffset.UTC), scheme, "7.50");
        com.finapp.payments.UnmatchedConfirmations.Parked tomorrow =
                parkAt(Clock.fixed(today.plus(Duration.ofMinutes(2)), ZoneOffset.UTC), scheme,
                        "7.50");
        com.finapp.payments.UnmatchedConfirmations.Parked contradicting =
                parkAt(Clock.fixed(today.plus(Duration.ofDays(3)), ZoneOffset.UTC), scheme,
                        "9.99");

        assertThat(first.acting()).isTrue();
        assertThat(tomorrow.acting()).isFalse();
        assertThat(contradicting.acting()).isFalse();
        assertThat(tomorrow.parking()).isEqualTo(first.parking());
        assertThat(contradicting.parking()).isEqualTo(first.parking());
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:unmatched-confirmation:instant:" + scheme + "'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payments.unmatched_confirmation WHERE"
                        + " scheme_reference = '" + scheme + "'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a resolver whose read was STALE - the attempt concluded since - still parks the"
            + " value the statement carries, on the locked row, never acknowledging it away"
            + " (the Phase 7 -> 8 transition, the doors' unlocked-read race)")
    void aStaleReadOfAConcludedAttemptStillParks() throws Exception {
        Fixture f = bankFixture();
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String paymentId = field(confirmedPayment(f, "3.50").body(), "id");
        String attemptId = attemptIdOf(paymentId);
        // Another resolver concludes it FAILED ...
        assertThat(callback(
                        "{\"eventId\":\"evt_" + suffix() + "\",\"reference\":\""
                                + referenceOf(attemptId) + "\",\"status\":\"rejected\"}"))
                .isEqualTo(204);
        // ... while this one still believed it waiting: the applier locks, sees FAILED, parks.
        String scheme = "sch-stale-" + suffix();
        try (com.finapp.platform.security.SecurityContext.Scope platform =
                        com.finapp.platform.security.SecurityContext.enterSystem();
                com.finapp.platform.correlation.CorrelationContext.Scope correlation =
                        com.finapp.platform.correlation.CorrelationContext.enter(
                                com.finapp.sharedkernel.correlation.Correlation.startingWith(
                                        com.finapp.sharedkernel.correlation.CorrelationId
                                                .generate(ids)))) {
            transactions.inTransaction(
                    uow -> {
                        com.finapp.payments.PaymentIntent intent =
                                intents.findById(uow, PaymentIntentId.of(UUID.fromString(paymentId)))
                                        .orElseThrow();
                        return outcomes.applyExecution(
                                uow,
                                intent.id(),
                                com.finapp.payments.PaymentAttemptId.of(UUID.fromString(attemptId)),
                                com.finapp.payments.PaymentAttemptStatus.AWAITING_PAYER,
                                com.finapp.payments.PushInquiryAnswer.Verdict.ACCEPTED,
                                java.util.Optional.of(new com.finapp.payments.ProviderReference(scheme)),
                                java.util.Optional.of("C6"),
                                intent.creditAccount(),
                                intent.amount(),
                                java.util.Optional.of(intent.amount()),
                                com.finapp.payments.PaymentCreation.resolvedCorrelation());
                    });
        }
        assertThat(oneString("SELECT status FROM payments.payment_attempt WHERE id = ?",
                        UUID.fromString(attemptId)))
                .isEqualTo("FAILED");
        assertThat(oneString("SELECT cause || '|' || attempt_id::text FROM"
                        + " payments.unmatched_confirmation WHERE scheme_reference = ?", scheme))
                .isEqualTo("ATTEMPT_CONCLUDED|" + attemptId);
    }

    @Test
    @DisplayName("the door answers every authenticated statement totally: a zero amount, or a"
            + " currency this rail cannot park, is unmappable - acknowledged, its evidence kept,"
            + " nothing parked - never an exception that rolls the evidence back (the Phase 7"
            + " -> 8 transition)")
    void anUnparkableStatementIsUnmappableNotAStall() throws Exception {
        String zero = "sch-zero-" + suffix();
        String foreignCurrency = "sch-chf-" + suffix();
        long evidenceBefore = count("SELECT count(*) FROM payments.provider_evidence");
        assertThat(callback(
                        "{\"eventId\":\"evt_" + suffix() + "\",\"reference\":\"nobody"
                                + suffix() + "\",\"status\":\"executed\",\"schemeReference\":\""
                                + zero + "\",\"amount\":\"0.00\",\"currency\":\"USD\"}"))
                .isEqualTo(204);
        assertThat(callback(
                        "{\"eventId\":\"evt_" + suffix() + "\",\"reference\":\"nobody"
                                + suffix() + "\",\"status\":\"executed\",\"schemeReference\":\""
                                + foreignCurrency + "\",\"amount\":\"5.00\",\"currency\":\"CHF\"}"))
                .isEqualTo(204);
        assertThat(count("SELECT count(*) FROM payments.unmatched_confirmation WHERE"
                        + " scheme_reference IN ('" + zero + "', '" + foreignCurrency + "')"))
                .isZero();
        assertThat(count("SELECT count(*) FROM payments.provider_evidence") - evidenceBefore)
                .as("both deliveries' bytes retained, neither rolled back")
                .isGreaterThanOrEqualTo(2);
    }

    /** Parks through a parking engine on {@code clock} - the wired parts, another day. */
    private com.finapp.payments.UnmatchedConfirmations.Parked parkAt(
            Clock clock, String scheme, String amount) {
        com.finapp.payments.UnmatchedConfirmations engine =
                new com.finapp.payments.UnmatchedConfirmations(
                        unmatchedStoreBean,
                        railsBean,
                        new com.finapp.ledger.ChartOfAccounts<>(ledgerAccountStore),
                        new com.finapp.ledger.PostingService(
                                new com.finapp.platform.idempotency.IdempotentExecutor(
                                        new com.finapp.platform.idempotency
                                                .JdbcIdempotencyRecordStore(),
                                        clock, Duration.ofDays(1), Duration.ofMinutes(5)),
                                new com.finapp.ledger.JdbcJournalEntryStore(ids),
                                auditWriterBean,
                                new com.finapp.platform.outbox.JdbcOutboxWriter(),
                                new com.finapp.ledger.JdbcBalanceProjection(),
                                ids,
                                clock,
                                com.finapp.ledger.PostingObserver.NONE),
                        auditWriterBean,
                        ids,
                        clock,
                        new com.finapp.payments.JdbcSchemeExecutionClaimStore(),
                        com.finapp.payments.RailOutcomeObserver.NONE,
                        // The wired recorder (P8-TSK-005): a parking made here opens its
                        // expectation exactly as the door's does.
                        settlementExpectationsBean);
        try (com.finapp.platform.security.SecurityContext.Scope platform =
                        com.finapp.platform.security.SecurityContext.enterSystem();
                com.finapp.platform.correlation.CorrelationContext.Scope correlation =
                        com.finapp.platform.correlation.CorrelationContext.enter(
                                com.finapp.sharedkernel.correlation.Correlation.startingWith(
                                        com.finapp.sharedkernel.correlation.CorrelationId
                                                .generate(ids)))) {
            return transactions.inTransaction(
                    uow ->
                            engine.park(
                                    uow,
                                    new com.finapp.payments.UnmatchedConfirmations.Parking(
                                            SimulatedInstantSchemeAdapter.RAIL.id(),
                                            new com.finapp.payments.ProviderReference(scheme),
                                            com.finapp.sharedkernel.money.Money.of(
                                                    new java.math.BigDecimal(amount),
                                                    com.finapp.sharedkernel.money.CurrencyCode
                                                            .of("USD")),
                                            com.finapp.payments.UnmatchedConfirmation.Attribution
                                                    .unattributed(
                                                            java.util.Optional.empty(),
                                                            java.util.Optional.empty()),
                                            com.finapp.payments.PaymentCreation
                                                    .resolvedCorrelation())));
        }
    }

    @Autowired
    private com.finapp.payments.UnmatchedConfirmationStore<Connection> unmatchedStoreBean;

    @Autowired
    private com.finapp.payments.SettlementExpectations settlementExpectationsBean;

    @Autowired private com.finapp.ledger.LedgerAccountStore<Connection> ledgerAccountStore;

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
                        + "\",\"cycle\":\"C4\",\"amount\":\"8.00\",\"currency\":\"USD\"}");
        Thread.sleep(80);
        double executedBefore =
                com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(meterRegistry, com.finapp.payments.SimulatedInstantSchemeAdapter.RAIL.id(), "payment", "executed");
        double legacyBefore = com.finapp.app.telemetry.RailOutcomeCounts.attempt(meterRegistry, "executed");
        // The wide-batch engine, for the recovery test's shared-database reason.
        PayInResolution.SweepResult swept = wideSweep();
        assertThat(swept.resolved()).isGreaterThanOrEqualTo(1);

        assertThat(oneString("SELECT status FROM payments.payment_attempt WHERE id = ?",
                        UUID.fromString(attemptId)))
                .isEqualTo("EXECUTED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-execution:" + attemptId + "'"))
                .isEqualTo(1);

        // COUNTED where it was written (P7-TSK-015): the sweep's execution - counted NOWHERE
        // until the appliers reported their judgements - once, on the instant rail, in the
        // rail series and the legacy series together.
        assertThat(com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(meterRegistry, com.finapp.payments.SimulatedInstantSchemeAdapter.RAIL.id(), "payment", "executed")
                        - executedBefore)
                .isEqualTo(1);
        assertThat(com.finapp.app.telemetry.RailOutcomeCounts.attempt(meterRegistry, "executed") - legacyBefore).isEqualTo(1);

        // A second sweep finds nothing to move: the entry count is the proof.
        Thread.sleep(80);
        wideSweep();
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-execution:" + attemptId + "'"))
                .isEqualTo(1);
        assertThat(outboxCount("payments.PaymentExecuted", paymentId)).isEqualTo(1);
        assertThat(com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(meterRegistry, com.finapp.payments.SimulatedInstantSchemeAdapter.RAIL.id(), "payment", "executed")
                        - executedBefore)
                .as("the converging second sweep judged nothing, so counted nothing")
                .isEqualTo(1);
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
        // Nothing written, counted (P7-DOC-001, section 14.6): the withdrawal reversal's
        // counters - no audit record, no fact, no entry, no history row on either machine.
        long audits = count("SELECT count(*) FROM platform.audit_record");
        long events = count("SELECT count(*) FROM platform.outbox_event");
        long entries = count("SELECT count(*) FROM ledger.journal_entry");
        long history = count("SELECT count(*) FROM payments.payment_intent_event")
                + count("SELECT count(*) FROM payments.payment_attempt_event");

        HttpRequest cancel =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/payments/"
                                + paymentId))
                        .header("Authorization", "Bearer " + f.token())
                        .DELETE()
                        .build();
        HttpResponse<String> refused = send(cancel);
        assertThat(refused.statusCode()).isEqualTo(409);
        // THE CAPABILITY's refusal, by its own code (P7-DOC-001: with the declaration's gate
        // removed the push machine still refused - no void edge leaves AWAITING_PAYER - as
        // payments.NotCancellable, and a bare 409 could not tell the two ranks apart).
        assertThat(refused.body()).contains("payments.ReversalNotSupported");
        assertThat(provider.requestCount(SimulatedInstantSchemeAdapter.INITIATIONS_PATH))
                .isEqualTo(wired);
        assertThat(oneString("SELECT status FROM payments.payment_attempt WHERE intent_id"
                        + " = ?", UUID.fromString(paymentId)))
                .isEqualTo("AWAITING_PAYER");
        assertThat(count("SELECT count(*) FROM platform.audit_record")).isEqualTo(audits);
        assertThat(count("SELECT count(*) FROM platform.outbox_event")).isEqualTo(events);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry")).isEqualTo(entries);
        assertThat(count("SELECT count(*) FROM payments.payment_intent_event")
                        + count("SELECT count(*) FROM payments.payment_attempt_event"))
                .as("no machine moved")
                .isEqualTo(history);
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
    // The return payment (P7-TSK-010, ADR-0059 §3)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the return chain holds: an EXECUTED pay-in refunds as a NEW push citing"
            + " the original's scheme reference - EXACTLY ONE entry DR wallet / CR"
            + " INSTANT_CLEARING, the key replayed byte for byte, and the return's own"
            + " confirmation echo never parks (P7-TSK-010)")
    void theReturnChainHolds() throws Exception {
        String scheme = "sch-ret-orig-" + suffix();
        // Unique per run since P8-TSK-005: the return's scheme reference is now a per-source
        // expectation key, so a constant would collide with an earlier run's return.
        String returnRef = "sch-ret-tx-" + suffix();
        Executed paid = executedPayIn("5.00", scheme);
        String operator = operatorToken();

        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.RETURNS_PATH, 200,
                "{\"status\":\"accepted\",\"reference\":\"" + returnRef + "\","
                        + "\"cycle\":\"C9\"}");
        String key = someKey();
        HttpResponse<String> returned =
                refund(operator, paid.paymentId(), "5.00", "USD", "goods returned", key);
        assertThat(returned.statusCode()).isEqualTo(201);
        assertThat(field(returned.body(), "status")).isEqualTo("COMPLETED");
        String refundId = field(returned.body(), "id");

        // The row: COMPLETED with the RETURN's own scheme reference - never the original's.
        assertThat(oneString(
                        "SELECT status || '|' || provider_reference"
                                + " FROM payments.refund WHERE id = ?",
                        UUID.fromString(refundId)))
                .isEqualTo("COMPLETED|" + returnRef);

        // THE WIRE: our minted reference as the Idempotency-Key, the ORIGINAL's scheme
        // reference as the destination-by-reference in the body (INV-RAIL-03: the
        // platform never learns the payer's account), inside the e2e bound.
        String ourReference =
                oneString(
                        "SELECT provider_idempotency_reference FROM payments.refund"
                                + " WHERE id = ?",
                        UUID.fromString(refundId));
        assertThat(ourReference).matches("[a-f0-9]{32}");
        assertThat(provider.headerValues(
                        SimulatedInstantSchemeAdapter.RETURNS_PATH,
                        SimulatedInstantSchemeAdapter.IDEMPOTENCY_KEY_HEADER))
                .containsExactly(ourReference);
        assertThat(provider.bodyValues(SimulatedInstantSchemeAdapter.RETURNS_PATH).get(0))
                .contains("\"originalReference\":\"" + scheme + "\"")
                .contains("\"endToEndReference\":\"" + ourReference + "\"");

        // EXACTLY ONE posting - the refund's own key, the capture-inverse pair on the
        // INSTANT rail's declared position (INV-RAIL-04) - and the wallet explains it.
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-refund:" + refundId + "'"))
                .isEqualTo(1);
        assertThat(oneString(
                        "SELECT string_agg(l.direction || ':' || a.purpose, ',' ORDER BY"
                                + " l.direction) FROM ledger.journal_line l"
                                + " JOIN ledger.journal_entry e ON e.id = l.entry_id"
                                + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                                + " WHERE e.idempotency_scope ="
                                + " 'ledger.post:payment-refund:" + refundId + "'"))
                .isEqualTo("CREDIT:INSTANT_CLEARING,DEBIT:CUSTOMER_WALLET");
        assertThat(get("/v1/me/accounts/" + paid.fixture().product() + "/balance",
                        paid.fixture().token()).body())
                .contains("\"settled\":\"0.00\"");

        // ITS EXPECTATION (P8-TSK-005, ADR-0067): the RETURN_PAYMENT mode opens PUSH_RETURN -
        // OUTBOUND, keyed by the return's OWN scheme reference and our reference, and NO cycle:
        // a return's cycle is learned from the scheme's report (-017), never announced here.
        ClearingLineCopies.Opened returnOpened =
                ClearingLineCopies.assertOpensItsClearingLinesCopy(
                        ExpectationKind.PUSH_RETURN, refundId,
                        "payment-refund:" + refundId, ExpectationDirection.OUTBOUND);
        assertThat(returnOpened.amountMinor()).isEqualTo(500);
        assertThat(returnOpened.settlementCycle()).isEmpty();
        ClearingLineCopies.assertKeyed(returnOpened, KeyKind.SCHEME_REF, returnRef);
        ClearingLineCopies.assertKeyed(returnOpened, KeyKind.OUR_REF, ourReference);
        assertThat(ClearingLineCopies.expectationsOf(ExpectationKind.CARD_REFUND, refundId))
                .as("a return is never a card refund: the kind is the DECLARED refund mode")
                .isZero();

        // The replay: the recorded judgement byte for byte, no second wire call, no
        // second entry (INV-IDEM-03).
        HttpResponse<String> replay =
                refund(operator, paid.paymentId(), "5.00", "USD", "goods returned", key);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(field(replay.body(), "id")).isEqualTo(refundId);
        assertThat(provider.requestCount(SimulatedInstantSchemeAdapter.RETURNS_PATH))
                .isEqualTo(1);

        // THE RETURN'S OWN ECHO: the scheme confirming OUR return's reference attributes
        // to the refund, parks nothing and credits nothing (the park guard's half).
        assertThat(executedCallback(ourReference, returnRef, "C9", "5.00", "USD"))
                .isEqualTo(204);
        assertThat(count("SELECT count(*) FROM payments.unmatched_confirmation WHERE"
                        + " scheme_reference = '" + returnRef + "'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " LIKE 'ledger.post:unmatched-confirmation:%" + returnRef + "'"))
                .isZero();
    }

    @Test
    @DisplayName("a partial return is bounded by the EXECUTED amount for every writer"
            + " (V018): to the cent legal, one cent past refused 422 with nothing"
            + " reserved, and a waiting pay-in has nothing to return (409)")
    void aPartialReturnIsBoundedByTheExecution() throws Exception {
        Executed paid = executedPayIn("6.00", "sch-ret-bound-" + suffix());
        String operator = operatorToken();
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.RETURNS_PATH, 200,
                "{\"status\":\"accepted\",\"reference\":\"sch-ret-b-" + suffix()
                        + "\",\"cycle\":\"C9\"}");

        assertThat(field(refund(operator, paid.paymentId(), "4.00", "USD", "partial one",
                                someKey()).body(),
                        "status"))
                .isEqualTo("COMPLETED");
        HttpResponse<String> past =
                refund(operator, paid.paymentId(), "2.50", "USD", "one too far", someKey());
        assertThat(past.statusCode()).isEqualTo(422);
        assertThat(past.body()).contains("payments.RefundExceedsCaptured");

        // The freed remainder to the cent - the bound is <= against the EXECUTED amount.
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.RETURNS_PATH, 200,
                "{\"status\":\"accepted\",\"reference\":\"sch-ret-b2-" + suffix()
                        + "\",\"cycle\":\"C9\"}");
        assertThat(field(refund(operator, paid.paymentId(), "2.00", "USD", "the rest",
                                someKey()).body(),
                        "status"))
                .isEqualTo("COMPLETED");
        assertThat(get("/v1/me/accounts/" + paid.fixture().product() + "/balance",
                        paid.fixture().token()).body())
                .contains("\"settled\":\"0.00\"");

        // A pay-in the payer never executed has nothing to return: 409, nothing written.
        Fixture waiting = bankFixture();
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String waitingId = field(confirmedPayment(waiting, "3.00").body(), "id");
        HttpResponse<String> premature =
                refund(operator, waitingId, "3.00", "USD", "nothing arrived", someKey());
        assertThat(premature.statusCode()).isEqualTo(409);
        assertThat(premature.body()).contains("payments.NotRefundable");
        assertThat(count("SELECT count(*) FROM payments.refund WHERE attempt_id = '"
                        + attemptIdOf(waitingId) + "'::uuid"))
                .isZero();
    }

    @Test
    @DisplayName("the lost return answer is honestly UNKNOWN with the hold standing and"
            + " nothing posted; its echo never parks; the return sweep's inquiry completes"
            + " it to EXACTLY ONE entry and a second sweep converges quietly (INV-LIFE-03)")
    void aLostReturnAnswerResolvesBySweep() throws Exception {
        Executed paid = executedPayIn("5.00", "sch-ret-lost-" + suffix());
        String operator = operatorToken();

        provider.neverResponds(SimulatedInstantSchemeAdapter.RETURNS_PATH);
        double unknownBefore =
                com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(meterRegistry, com.finapp.payments.SimulatedInstantSchemeAdapter.RAIL.id(), "refund", "unknown");
        double completedBefore =
                com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(meterRegistry, com.finapp.payments.SimulatedInstantSchemeAdapter.RAIL.id(), "refund", "completed");
        HttpResponse<String> returned =
                refund(operator, paid.paymentId(), "5.00", "USD", "lost answer", someKey());
        assertThat(returned.statusCode()).isEqualTo(201);
        assertThat(field(returned.body(), "status")).isEqualTo("UNKNOWN");
        // The honest unknown IS a judgement, counted on the return's own rail (P7-TSK-015).
        assertThat(com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(meterRegistry, com.finapp.payments.SimulatedInstantSchemeAdapter.RAIL.id(), "refund", "unknown")
                        - unknownBefore)
                .isEqualTo(1);
        String refundId = field(returned.body(), "id");
        String ourReference =
                oneString(
                        "SELECT provider_idempotency_reference FROM payments.refund"
                                + " WHERE id = ?",
                        UUID.fromString(refundId));

        // Nothing posted, the hold STANDING: the wallet still holds the money but cannot
        // spend what the return may yet take (INV-BAL-04 with INV-LIFE-03).
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-refund:" + refundId + "'"))
                .isZero();
        String balances =
                get("/v1/me/accounts/" + paid.fixture().product() + "/balance",
                        paid.fixture().token()).body();
        assertThat(balances).contains("\"settled\":\"5.00\"");
        assertThat(balances).contains("\"available\":\"0.00\"");

        // The echo window: a confirmation naming the IN-FLIGHT return's reference parks
        // nothing and moves nothing - the refund is the sweep's to conclude.
        assertThat(executedCallback(ourReference, "sch-ret-echo-" + suffix(), "C9", "5.00",
                        "USD"))
                .isEqualTo(204);
        assertThat(count("SELECT count(*) FROM payments.unmatched_confirmation WHERE"
                        + " rail = 'instant' AND scheme_reference LIKE 'sch-ret-echo-%'"))
                .isZero();
        assertThat(oneString("SELECT status FROM payments.refund WHERE id = ?",
                        UUID.fromString(refundId)))
                .isEqualTo("UNKNOWN");

        // The scheme answers the inquiry; the sweep applies it on the LOCKED row.
        provider.reset();
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.RETURN_STATUS_PATH + ourReference, 200,
                "{\"status\":\"accepted\",\"reference\":\"sch-ret-l2-" + suffix()
                        + "\",\"cycle\":\"C9\"}");
        assertThat(wideReturnSweep().applied()).isGreaterThanOrEqualTo(1);
        // The return sweep's completion - counted nowhere before P7-TSK-015 - once.
        assertThat(com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(meterRegistry, com.finapp.payments.SimulatedInstantSchemeAdapter.RAIL.id(), "refund", "completed")
                        - completedBefore)
                .isEqualTo(1);
        assertThat(oneString("SELECT status FROM payments.refund WHERE id = ?",
                        UUID.fromString(refundId)))
                .isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-refund:" + refundId + "'"))
                .isEqualTo(1);
        assertThat(get("/v1/me/accounts/" + paid.fixture().product() + "/balance",
                        paid.fixture().token()).body())
                .contains("\"settled\":\"0.00\"");

        // The second sweep converges quietly: the row is terminal, nothing re-applies.
        wideReturnSweep();
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-refund:" + refundId + "'"))
                .isEqualTo(1);
    }

    /**
     * Section 7's last row, for the two instant sweeps no test raced (`P7-DOC-001`; A3's find):
     * ten instances sweeping at once over one resolvable row - the registered leaderless
     * pattern, counted rather than asserted.
     */
    @Test
    @DisplayName("ten concurrent initiation sweeps over one lost callback execute it ONCE: one"
            + " EXECUTED edge, one entry, one executed fact, one acting record")
    void tenConcurrentInitiationSweepsExecuteOnce() throws Exception {
        Fixture f = bankFixture();
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String paymentId = field(confirmedPayment(f, "6.00").body(), "id");
        String attemptId = attemptIdOf(paymentId);
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.INITIATION_STATUS_PATH + referenceOf(attemptId),
                200,
                "{\"status\":\"accepted\",\"reference\":\"sch-race-" + suffix()
                        + "\",\"cycle\":\"C4\",\"amount\":\"6.00\",\"currency\":\"USD\"}");
        Thread.sleep(80); // past the tiny candidacy bound

        tenAtOnce(this::wideSweep);

        assertThat(count("SELECT count(*) FROM payments.payment_attempt_event WHERE"
                        + " attempt_id = '" + attemptId + "'::uuid AND to_status = 'EXECUTED'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-execution:" + attemptId + "'"))
                .isEqualTo(1);
        assertThat(outboxCount("payments.PaymentExecuted", paymentId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                        + " 'payments.PaymentOutcomeApplied' AND target_id = '" + paymentId
                        + "' AND change_summary LIKE '%EXECUTED%'"))
                .isEqualTo(1);
        // The inquiry sweep is another arrival of the one applier (P8-TSK-005): ten sweeps,
        // one expectation, its cycle the one the inquiry announced.
        ClearingLineCopies.Opened swept =
                ClearingLineCopies.assertOpensItsClearingLinesCopy(
                        ExpectationKind.PUSH_PAY_IN, attemptId,
                        "payment-execution:" + attemptId, ExpectationDirection.INBOUND);
        assertThat(swept.settlementCycle()).contains("C4");
    }

    @Test
    @DisplayName("ten concurrent return sweeps over one lost return answer complete it ONCE:"
            + " one COMPLETED edge, one entry, one completed fact")
    void tenConcurrentReturnSweepsCompleteOnce() throws Exception {
        Executed paid = executedPayIn("5.00", "sch-ret-race-" + suffix());
        String operator = operatorToken();
        provider.neverResponds(SimulatedInstantSchemeAdapter.RETURNS_PATH);
        HttpResponse<String> returned =
                refund(operator, paid.paymentId(), "5.00", "USD", "raced sweeps", someKey());
        assertThat(field(returned.body(), "status")).isEqualTo("UNKNOWN");
        String refundId = field(returned.body(), "id");
        String ourReference =
                oneString("SELECT provider_idempotency_reference FROM payments.refund"
                        + " WHERE id = ?", UUID.fromString(refundId));
        provider.reset();
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.RETURN_STATUS_PATH + ourReference, 200,
                "{\"status\":\"accepted\",\"reference\":\"sch-ret-race2-" + suffix()
                        + "\",\"cycle\":\"C9\"}");

        tenAtOnce(this::wideReturnSweep);

        assertThat(count("SELECT count(*) FROM payments.refund_event WHERE refund_id = '"
                        + refundId + "'::uuid AND to_status = 'COMPLETED'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-refund:" + refundId + "'"))
                .isEqualTo(1);
        assertThat(outboxCount("payments.RefundCompleted", refundId)).isEqualTo(1);
        // Ten sweeps, one PUSH_RETURN expectation (P8-TSK-005): the acting exit decides.
        ClearingLineCopies.assertOpensItsClearingLinesCopy(
                ExpectationKind.PUSH_RETURN, refundId,
                "payment-refund:" + refundId, ExpectationDirection.OUTBOUND);
    }

    /**
     * THE CYCLE IS AN ATTRIBUTE, NEVER A KEY (`P8-TSK-005`, ADR-0067 §5, the transition's A5):
     * ten pay-ins all announced in ONE settlement cycle open ten expectations each carrying that
     * cycle - and raise no collision, because one cycle names many executions and a cycle key
     * under the per-source unique would have made every pay-in after the first a
     * {@code KEY_COLLISION}.
     */
    @Test
    @DisplayName("ten pay-ins of ONE cycle open ten expectations carrying it and raise no"
            + " collision - the cycle is an attribute, never a key (P8-TSK-005)")
    void tenPayInsOfOneCycleRaiseNoCollision() throws Exception {
        Fixture f = bankFixture();
        String cycle = "CY" + suffix();
        List<String> attemptIds = new java.util.ArrayList<>();
        for (int payIn = 0; payIn < 10; payIn++) {
            schemeInitiates("https://payer-psp.example/authorize/" + suffix());
            String attemptId = attemptIdOf(field(confirmedPayment(f, "1.00").body(), "id"));
            assertThat(executedCallback(
                            referenceOf(attemptId), "sch-cycle-" + suffix(), cycle, "1.00",
                            "USD"))
                    .isEqualTo(204);
            attemptIds.add(attemptId);
        }
        for (String attemptId : attemptIds) {
            ClearingLineCopies.Opened opened =
                    ClearingLineCopies.assertOpensItsClearingLinesCopy(
                            ExpectationKind.PUSH_PAY_IN, attemptId,
                            "payment-execution:" + attemptId, ExpectationDirection.INBOUND);
            assertThat(opened.settlementCycle()).contains(cycle);
        }
        assertThat(count("SELECT count(*) FROM reconciliation.expectation"
                        + " WHERE settlement_cycle = '" + cycle + "'"))
                .isEqualTo(10);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_event ev"
                        + " JOIN reconciliation.expectation x ON x.id = ev.expectation_id"
                        + " WHERE x.settlement_cycle = '" + cycle + "'"
                        + " AND ev.event_type = 'KEY_COLLISION'"))
                .as("no pay-in of the cycle collided with another")
                .isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_key WHERE"
                        + " key_value = '" + cycle + "'"))
                .as("the cycle is registered as no key at all")
                .isZero();
    }

    /**
     * THE PULL'S CYCLE WORKLIST READS THE REAL SCHEMA (`P8-TSK-021`, the gate's find): payments'
     * {@code SettlementCycleReads} folds the three stored {@code settlement_cycle} columns to each
     * token's earliest record, one keyset page at a time - every UNION branch executes, the page
     * after a token excludes it, and the {@code since} bound excludes what is older.
     */
    @Test
    @DisplayName("the pull's cycle reads: every cycle a pay-in names, first seen at its earliest"
            + " record, one keyset page at a time, bounded by since (P8-TSK-021)")
    void theCycleReadsWalkTheRealSchema() throws Exception {
        Fixture f = bankFixture();
        String stem = "CY" + suffix();
        String first = stem + "A";
        String second = stem + "B";
        java.time.Instant before = java.time.Instant.now().minus(java.time.Duration.ofMinutes(5));
        for (String cycle : List.of(first, second, second)) {
            schemeInitiates("https://payer-psp.example/authorize/" + suffix());
            String attemptId = attemptIdOf(field(confirmedPayment(f, "1.00").body(), "id"));
            assertThat(executedCallback(
                            referenceOf(attemptId), "sch-pull-" + suffix(), cycle, "1.00", "USD"))
                    .isEqualTo(204);
        }
        com.finapp.payments.JdbcSettlementCycleReads reads =
                new com.finapp.payments.JdbcSettlementCycleReads();
        try (Connection app = DatabaseRoles.application()) {
            List<com.finapp.payments.SettlementCycleReads.SeenCycle> fromTheStem =
                    reads.cyclesSince(app, before, java.util.Optional.of(stem), 2);
            assertThat(fromTheStem)
                    .extracting(com.finapp.payments.SettlementCycleReads.SeenCycle::cycle)
                    .as("the page after the stem, in cycle order, each cycle once")
                    .containsExactly(first, second);
            assertThat(fromTheStem)
                    .allSatisfy(seen -> assertThat(seen.firstSeen()).isAfter(before));
            assertThat(reads.cyclesSince(app, before, java.util.Optional.of(first), 1))
                    .extracting(com.finapp.payments.SettlementCycleReads.SeenCycle::cycle)
                    .as("the page after a cycle excludes it")
                    .containsExactly(second);
            assertThat(reads.cyclesSince(
                            app,
                            java.time.Instant.now().plus(java.time.Duration.ofHours(1)),
                            java.util.Optional.of(stem),
                            10))
                    .as("records older than since name no cycle")
                    .isEmpty();
        }
    }

    /** Ten calls released behind one gate, every one awaited. */
    private static void tenAtOnce(java.util.concurrent.Callable<?> sweep) throws Exception {
        java.util.concurrent.CountDownLatch open = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(10);
        try {
            java.util.List<java.util.concurrent.Future<?>> running = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                running.add(pool.submit(() -> {
                    open.await();
                    return sweep.call();
                }));
            }
            open.countDown();
            for (java.util.concurrent.Future<?> call : running) {
                call.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("the sweep partition and the re-drive: the card leg never sees a return,"
            + " and an UNRECOGNISED return re-drives with the SAME reference under a"
            + " renewed permit - the scheme's dedupe converging (INV-PAY-04, ADR-0057)")
    void anUnrecognisedReturnRedrivesWithTheSameReference() throws Exception {
        Executed paid = executedPayIn("5.00", "sch-ret-redrive-" + suffix());
        String operator = operatorToken();

        provider.neverResponds(SimulatedInstantSchemeAdapter.RETURNS_PATH);
        String refundId =
                field(refund(operator, paid.paymentId(), "5.00", "USD", "redrive",
                                someKey()).body(),
                        "id");
        com.finapp.payments.RefundId id =
                com.finapp.payments.RefundId.of(UUID.fromString(refundId));
        String ourReference =
                oneString(
                        "SELECT provider_idempotency_reference FROM payments.refund"
                                + " WHERE id = ?",
                        UUID.fromString(refundId));

        // THE PARTITION, asserted at the query: the card sweeper's candidate read
        // excludes the push refund it could only mis-resolve, the return sweep's
        // includes it (P7-TSK-010's one schema-visible decision).
        Instant wide = Instant.now(CLOCK).plusSeconds(60);
        assertThat(transactions.inTransaction(
                                uow -> refundsBean.findSweepable(uow, wide, wide, 2_000))
                        .stream()
                        .map(com.finapp.payments.Refund::id))
                .doesNotContain(id);
        assertThat(transactions.inTransaction(
                                uow ->
                                        refundsBean.findSweepableReturns(
                                                uow, wide, wide, 2_000))
                        .stream()
                        .map(com.finapp.payments.Refund::id))
                .contains(id);

        // The scheme explicitly never saw our reference; the re-drive rides the SAME one.
        provider.reset();
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.RETURN_STATUS_PATH + ourReference, 200,
                "{\"status\":\"unrecognised\"}");
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.RETURNS_PATH, 200,
                "{\"status\":\"accepted\",\"reference\":\"sch-ret-r2-" + suffix()
                        + "\",\"cycle\":\"C9\"}");
        assertThat(wideReturnSweep().applied()).isGreaterThanOrEqualTo(1);

        assertThat(oneString("SELECT status FROM payments.refund WHERE id = ?",
                        UUID.fromString(refundId)))
                .isEqualTo("COMPLETED");
        assertThat(provider.headerValues(
                        SimulatedInstantSchemeAdapter.RETURNS_PATH,
                        SimulatedInstantSchemeAdapter.IDEMPOTENCY_KEY_HEADER))
                .as("the re-drive presents the SAME end-to-end reference the first send"
                        + " carried - the dedupe premise, read back from the wire")
                .containsExactly(ourReference);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-refund:" + refundId + "'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a re-drive's refused connection is NEVER knowledge: the return stays"
            + " UNKNOWN with its hold standing and nothing posted - concluding FAILED"
            + " would release money an earlier send may have moved (ADR-0057 §3)")
    void aRedrivesRefusedConnectionConcludesNothing() throws Exception {
        Executed paid = executedPayIn("5.00", "sch-ret-dead-" + suffix());
        String operator = operatorToken();

        provider.neverResponds(SimulatedInstantSchemeAdapter.RETURNS_PATH);
        String refundId =
                field(refund(operator, paid.paymentId(), "5.00", "USD", "dead re-drive",
                                someKey()).body(),
                        "id");
        String ourReference =
                oneString(
                        "SELECT provider_idempotency_reference FROM payments.refund"
                                + " WHERE id = ?",
                        UUID.fromString(refundId));

        // The scheme's inquiry says UNRECOGNISED - but the RE-SEND meets a refused
        // connection (a second engine whose send port is dead, the withdrawal idiom).
        provider.reset();
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.RETURN_STATUS_PATH + ourReference, 200,
                "{\"status\":\"unrecognised\"}");
        int deadPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        PushRail deadSends =
                new SplitRail(
                        instantRailBean,
                        new SimulatedInstantSchemeAdapter(
                                URI.create("http://127.0.0.1:" + deadPort),
                                Duration.ofMillis(300),
                                "0123456789abcdef0123456789abcdef"
                                        .getBytes(StandardCharsets.US_ASCII)));
        new com.finapp.payments.ReturnResolution(
                        refundsBean,
                        attempts,
                        intents,
                        outcomes,
                        deadSends,
                        evidenceBean,
                        new com.finapp.payments.ReturnResolution.Config(
                                Duration.ofMillis(50), Duration.ofMillis(50), 2_000),
                        ids,
                        Clock.offset(CLOCK, Duration.ofSeconds(5)),
                        transactions)
                .sweep();

        assertThat(oneString("SELECT status FROM payments.refund WHERE id = ?",
                        UUID.fromString(refundId)))
                .as("a re-drive is never the first send: its refused connection proves"
                        + " nothing about the sends before it")
                .isEqualTo("UNKNOWN");
        assertThat(oneString(
                        "SELECT h.status FROM ledger.hold h JOIN payments.refund r"
                                + " ON r.hold_reference = h.id WHERE r.id = ?",
                        UUID.fromString(refundId)))
                .isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                        + " = 'ledger.post:payment-refund:" + refundId + "'"))
                .isZero();
    }

    /** Inquiries answered by the live stub, sends refused at a dead port (ADR-0057's split). */
    private record SplitRail(PushRail inquiries, PushRail sends) implements PushRail {
        @Override
        public String schemeName() {
            return inquiries.schemeName();
        }

        @Override
        public com.finapp.payments.ExchangeAnswer exchange(GrantExchange request) {
            return inquiries.exchange(request);
        }

        @Override
        public com.finapp.payments.PushAnswer send(CreditTransfer request) {
            return sends.send(request);
        }

        @Override
        public com.finapp.payments.PushInquiryAnswer inquire(
                com.finapp.payments.EndToEndReference ourReference) {
            return inquiries.inquire(ourReference);
        }

        @Override
        public com.finapp.payments.InitiationAnswer initiate(PayInInitiation request) {
            return sends.initiate(request);
        }

        @Override
        public com.finapp.payments.PushInquiryAnswer inquireInitiation(
                com.finapp.payments.EndToEndReference ourReference) {
            return inquiries.inquireInitiation(ourReference);
        }

        @Override
        public com.finapp.payments.PushAnswer sendReturn(ReturnPayment request) {
            return sends.sendReturn(request);
        }

        @Override
        public com.finapp.payments.PushInquiryAnswer inquireReturn(
                com.finapp.payments.EndToEndReference ourReference) {
            return inquiries.inquireReturn(ourReference);
        }
    }

    /** An executed pay-in: the fixture, its payment and the attempt's stored facts. */
    private record Executed(Fixture fixture, String paymentId, String attemptId) {}

    /** Registers, confirms and executes a pay-in of {@code amount} under {@code scheme}. */
    private Executed executedPayIn(String amount, String scheme) throws Exception {
        Fixture f = bankFixture();
        schemeInitiates("https://payer-psp.example/authorize/" + suffix());
        String paymentId = field(confirmedPayment(f, amount).body(), "id");
        String attemptId = attemptIdOf(paymentId);
        assertThat(executedCallback(referenceOf(attemptId), scheme, "C7", amount, "USD"))
                .isEqualTo(204);
        assertThat(oneString("SELECT status FROM payments.payment_attempt WHERE id = ?",
                        UUID.fromString(attemptId)))
                .isEqualTo("EXECUTED");
        return new Executed(f, paymentId, attemptId);
    }

    /** An operator session holding {@code PAYMENT_REFUND} (the refund endpoint's fixture). */
    private String operatorToken() throws Exception {
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        UUID identity =
                oneUuid("SELECT id FROM identity.identity WHERE login_identifier = ?", login);
        try (com.finapp.platform.correlation.CorrelationContext.Scope flow =
                        com.finapp.platform.correlation.CorrelationContext.enter(
                                com.finapp.sharedkernel.correlation.Correlation.startingWith(
                                        com.finapp.sharedkernel.correlation.CorrelationId
                                                .generate(ids)));
                com.finapp.platform.security.SecurityContext.Scope actor =
                        com.finapp.platform.security.SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorizationBean.assign(
                    app,
                    com.finapp.identity.IdentityId.of(identity),
                    com.finapp.identity.RoleName.LEDGER_OPERATOR,
                    com.finapp.identity.IdentityId.of(identity),
                    "test fixture");
            app.commit();
        }
        return tokenFrom(authenticate(login).body());
    }

    @Autowired private com.finapp.identity.Authorization authorizationBean;
    @Autowired private com.finapp.payments.RefundStore<Connection> refundsBean;

    /** The refund endpoint's own helper, verbatim (the operator surface's URL). */
    private HttpResponse<String> refund(
            String token,
            String paymentId,
            String amount,
            String currency,
            String reason,
            String idempotencyKey)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/payments/"
                                + paymentId + "/refund"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"amount\":\"" + amount + "\",\"currency\":\"" + currency
                                        + "\",\"reason\":\"" + reason + "\"}"));
        if (idempotencyKey != null) {
            request.header(IdempotencyKeyHeader.NAME, idempotencyKey);
        }
        return send(request.build());
    }

    /**
     * The wired return sweep with a batch wide enough to reach THIS test's row however
     * many rows other suites left behind (the shared-database citizenship lesson) — on a
     * clock five seconds AHEAD, so a row that moved milliseconds ago is deterministically
     * inside the candidacy bounds (the expiry sweeper's offset idiom; the bounds pace
     * production, they are not this test's subject).
     */
    private com.finapp.payments.ReturnResolution.SweepResult wideReturnSweep() {
        return new com.finapp.payments.ReturnResolution(
                        refundsBean,
                        attempts,
                        intents,
                        outcomes,
                        instantRailBean,
                        evidenceBean,
                        new com.finapp.payments.ReturnResolution.Config(
                                Duration.ofMillis(50), Duration.ofMillis(50), 2_000),
                        ids,
                        Clock.offset(CLOCK, Duration.ofSeconds(5)),
                        transactions)
                .sweep();
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
                com.finapp.payments.RailOperations.ofPush(com.finapp.payments.RailId.of("instant"), dead));
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
