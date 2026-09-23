package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounts.AccountOpening;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.JdbcCustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.app.accounts.VerifiedAccountHolder;
import com.finapp.ledger.AsOf;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.party.JdbcPartyStore;
import com.finapp.paymentmethods.JdbcPaymentMethodStore;
import com.finapp.payments.EvidenceCipher;
import com.finapp.payments.JdbcPaymentAttemptStore;
import com.finapp.payments.JdbcPaymentIntentStore;
import com.finapp.payments.JdbcProviderEvidenceStore;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.PaymentAttemptId;
import com.finapp.payments.PaymentAttemptStatus;
import com.finapp.payments.PaymentCapture;
import com.finapp.payments.PaymentConfirmation;
import com.finapp.payments.PaymentCreation;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentIntentStatus;
import com.finapp.payments.PaymentFailureReason;
import com.finapp.payments.PaymentProvider;
import com.finapp.payments.ProviderAnswer;
import com.finapp.payments.ProviderIdempotencyReference;
import com.finapp.payments.QueryAnswer;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.TransactionRunner;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The capture against the real ledger, the real chain and a real HTTP provider
 * (`P5-TSK-010`, ADR-0048): the {@code CAPTURED} transition, the posting and the intent's
 * {@code SUCCEEDED} are one transaction — proven by the entry count under a ten-way race, by
 * the rolled-back outcome leaving <strong>no posting and no transition</strong>, and by the
 * wallet balance being <strong>explainable by replay</strong> ({@code INV-BAL-02} extends
 * with no new mechanism).
 *
 * <p><strong>The whole participant chain is real here</strong> — party → `ACTIVE` customer →
 * wallet product → ledger wallet, and a real `paymentmethods` row — which pays `P5-TSK-009`'s
 * recorded deferral: {@code JdbcPaymentParticipants} is exercised over real rows on its first
 * money-moving path.
 */
@Tag("database")
@DisplayName("payment capture command (P5-TSK-010)")
class PaymentCaptureDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Money AMOUNT = Money.ofMinorUnits(10_00, EUR);
    private static final byte[] PSP_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EVIDENCE_KEY =
            "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);
    private static final String APPROVED_BODY =
            "  {\"status\" : \"approved\",  \"reference\":\"psp_%s\"}\n";

    private static SimulatedProvider psp;

    private final CountingRunner runner = new CountingRunner();
    private final JdbcPaymentIntentStore intents = new JdbcPaymentIntentStore();
    private final JdbcPaymentAttemptStore attempts = new JdbcPaymentAttemptStore();
    private final JdbcProviderEvidenceStore evidence =
            new JdbcProviderEvidenceStore(new EvidenceCipher(EVIDENCE_KEY, 1, new SecureRandom()), IDS);
    private final JdbcLedgerAccountStore ledgerAccounts = new JdbcLedgerAccountStore();
    private final JdbcPaymentParticipants participants =
            new JdbcPaymentParticipants(
                    new JdbcPartyStore(),
                    new JdbcCustomerAccountStore(),
                    ledgerAccounts,
                    new JdbcPaymentMethodStore());

    @BeforeAll
    static void startProvider() {
        psp = SimulatedProvider.start();
    }

    @AfterAll
    static void stopProvider() {
        psp.close();
    }

    private CorrelationContext.Scope testFlow;

    @BeforeEach
    void reset() {
        psp.reset();
        testFlow =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("test-" + UUID.randomUUID())));
    }

    @org.junit.jupiter.api.AfterEach
    void leaveFlow() throws Exception {
        testFlow.close();
    }

    @Test
    @DisplayName("capture end to end: one entry, atomic states, and a balance explainable by replay")
    void captureEndToEndPostsAtomically() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attempt = authorizedAttempt(holder);

        psp.succeedsWith(
                SimulatedCardPspAdapter.CAPTURES_PATH, 200, APPROVED_BODY.formatted("cap-e2e"));
        PaymentCapture.CaptureResult result = capture(adapter()).capture(attempt);

        assertThat(result.converged()).isFalse();
        assertThat(result.attempt()).isEqualTo(PaymentAttemptStatus.CAPTURED);
        assertThat(result.intent()).isEqualTo(PaymentIntentStatus.SUCCEEDED);
        try (Connection app = DatabaseRoles.application()) {
            PaymentAttempt captured = attempts.findById(app, attempt).orElseThrow();
            assertThat(captured.status()).isEqualTo(PaymentAttemptStatus.CAPTURED);
            assertThat(captured.capturedAmount()).isEqualTo(AMOUNT);
            assertThat(intents.findById(app, holder.intent).orElseThrow().status())
                    .isEqualTo(PaymentIntentStatus.SUCCEEDED);

            // Exactly one entry, referenced by the attempt (the section-12 chain), with the
            // ADR-0048 lines: DR clearing / CR wallet.
            assertThat(entriesReferencing(app, attempt)).isEqualTo(1);
            assertThat(lineCountFor(app, attempt)).isEqualTo(2);

            // AND THE LINES LAND ON THE ACCOUNTS ADR-0048 NAMES (`P5-TST-002`): the wallet
            // is credited against SETTLEMENT_CLEARING, never against settled cash and never
            // against a suspense account. This is INV-SET-01 made checkable - "internal
            // completion is not settlement" is a claim about WHICH position the money sits
            // in, and counting two lines cannot see it. The audit found the gap by mutation:
            // debiting SUSPENSE_UNMATCHED instead left all 80 payment database tests green,
            // with the customer's wallet funded from the wrong operational account.
            assertThat(linePurposes(app, attempt))
                    .as("DR settlement clearing / CR the customer's wallet (ADR-0048,"
                            + " INV-SET-01: captured is not settled)")
                    .containsExactlyInAnyOrder(
                            "DEBIT:SETTLEMENT_CLEARING", "CREDIT:CUSTOMER_WALLET");

            // INV-BAL-02 extends with no new mechanism: replay-from-zero explains the wallet.
            assertThat(
                            new JdbcBalanceDerivation()
                                    .derive(app, holder.wallet, AsOf.latest())
                                    .settled())
                    .isEqualTo(AMOUNT);

            // The received bytes retained, decrypted and checksum-verified - BOTH answers:
            // the authorization's response and the capture's, one attempt's full wire history.
            List<byte[]> retained = evidence.payloadsFor(app, attempt);
            assertThat(retained).hasSize(2);
            assertThat(new String(retained.get(1), StandardCharsets.UTF_8))
                    .contains("psp_cap-e2e");
        }
    }

    @Test
    @DisplayName("ten concurrent captures produce one entry per attempt, counted in the journal")
    void tenConcurrentCapturesProduceOneEntry() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attempt = authorizedAttempt(holder);
        psp.succeedsWith(
                SimulatedCardPspAdapter.CAPTURES_PATH, 200, APPROVED_BODY.formatted("cap-race"));

        List<Callable<Boolean>> captures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            captures.add(
                    () -> {
                        CorrelationContext.Scope flow =
                                CorrelationContext.enter(
                                        Correlation.startingWith(
                                                CorrelationId.of("race-" + UUID.randomUUID())));
                        try {
                            return capture(adapter()).capture(attempt).converged();
                        } finally {
                            flow.close();
                        }
                    });
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        int convergedCount = 0;
        try {
            for (Future<Boolean> outcome : pool.invokeAll(captures)) {
                if (outcome.get()) {
                    convergedCount++;
                }
            }
        } finally {
            pool.shutdown();
        }

        assertThat(convergedCount).isEqualTo(9);
        assertThat(psp.requestCount(SimulatedCardPspAdapter.CAPTURES_PATH)).isEqualTo(1);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(entriesReferencing(app, attempt)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("ambiguity commits CAPTURE_UNKNOWN with nothing posted (INV-LIFE-03)")
    void ambiguityPostsNothing() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attempt = authorizedAttempt(holder);
        psp.neverResponds(SimulatedCardPspAdapter.CAPTURES_PATH);

        PaymentCapture.CaptureResult result =
                capture(adapter(Duration.ofMillis(400))).capture(attempt);

        assertThat(result.attempt()).isEqualTo(PaymentAttemptStatus.CAPTURE_UNKNOWN);
        assertThat(result.intent()).isEqualTo(PaymentIntentStatus.PROCESSING);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(entriesReferencing(app, attempt)).isZero();
            assertThat(
                            new JdbcBalanceDerivation()
                                    .derive(app, holder.wallet, AsOf.latest())
                                    .settled()
                                    .isZero())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("declined fails both rows with nothing posted; the customer's answer is honest")
    void declinedFailsBothRows() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attempt = authorizedAttempt(holder);
        psp.succeedsWith(
                SimulatedCardPspAdapter.CAPTURES_PATH,
                200,
                "{\"status\":\"declined\",\"reason\":\"limit_exceeded\"}");

        PaymentCapture.CaptureResult result = capture(adapter()).capture(attempt);

        assertThat(result.attempt()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(result.intent()).isEqualTo(PaymentIntentStatus.FAILED);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(attempts.findById(app, attempt).orElseThrow().failureReason())
                    .isEqualTo(PaymentFailureReason.DECLINED);
            // The ROW, not the result object: the mutation that drops the intent's write
            // while still CLAIMING FAILED in the return value is exactly what an in-memory
            // assertion cannot see (found by this gate's own battery).
            assertThat(intents.findById(app, holder.intent()).orElseThrow().status())
                    .isEqualTo(PaymentIntentStatus.FAILED);
            assertThat(entriesReferencing(app, attempt)).isZero();
        }
    }

    @Test
    @DisplayName("a crash mid-capture strands CAPTURE_DISPATCHED and the retry converges")
    void aCrashMidCaptureStrandsTheDispatch() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attempt = authorizedAttempt(holder);

        assertThatThrownBy(() -> capture(new CrashingProvider()).capture(attempt))
                .hasMessage("simulated crash mid-capture");

        try (Connection app = DatabaseRoles.application()) {
            PaymentAttempt stranded = attempts.findById(app, attempt).orElseThrow();
            assertThat(stranded.status()).isEqualTo(PaymentAttemptStatus.CAPTURE_DISPATCHED);
            assertThat(stranded.captureReference()).isNotNull();
            assertThat(entriesReferencing(app, attempt)).isZero();
        }

        // The retry converges - the stranded case is the sweeper's, never a second wire call.
        psp.succeedsWith(
                SimulatedCardPspAdapter.CAPTURES_PATH, 200, APPROVED_BODY.formatted("retry"));
        PaymentCapture.CaptureResult retry = capture(adapter()).capture(attempt);
        assertThat(retry.converged()).isTrue();
        assertThat(retry.attempt()).isEqualTo(PaymentAttemptStatus.CAPTURE_DISPATCHED);
        assertThat(psp.requestCount(SimulatedCardPspAdapter.CAPTURES_PATH)).isZero();
    }

    @Test
    @DisplayName("a rolled-back outcome leaves no posting and no transition - the atomicity probe")
    void aRolledBackOutcomeLeavesNothing() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attempt = authorizedAttempt(holder);
        psp.succeedsWith(
                SimulatedCardPspAdapter.CAPTURES_PATH, 200, APPROVED_BODY.formatted("rollbk"));

        // The outcome transaction is made to fail AFTER the posting call: the event write
        // throws, the transaction rolls back, and the rollback must take the posting and the
        // transitions with it - exactly what the posting-hoisted-out mutation destroys.
        OutboxWriter<Connection> failingOutbox =
                (uow, envelope, payload, mediaType) -> {
                    if (envelope.eventType().equals("payments.PaymentCaptured")) {
                        throw new IllegalStateException("injected outcome failure");
                    }
                    new JdbcOutboxWriter().write(uow, envelope, payload, mediaType);
                };
        assertThatThrownBy(() -> capture(adapter(), failingOutbox).capture(attempt))
                .hasMessage("injected outcome failure");

        try (Connection app = DatabaseRoles.application()) {
            assertThat(entriesReferencing(app, attempt))
                    .as("the rollback takes the posting with it")
                    .isZero();
            assertThat(attempts.findById(app, attempt).orElseThrow().status())
                    .as("and the transition")
                    .isEqualTo(PaymentAttemptStatus.CAPTURE_DISPATCHED);
            assertThat(intents.findById(app, holder.intent).orElseThrow().status())
                    .isEqualTo(PaymentIntentStatus.PROCESSING);
            assertThat(
                            new JdbcBalanceDerivation()
                                    .derive(app, holder.wallet, AsOf.latest())
                                    .settled()
                                    .isZero())
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private record Holder(
            UUID party, Actor person, PaymentIntentId intent, LedgerAccountId wallet) {}

    /** The REAL chain: party → ACTIVE customer → wallet product → ledger wallet → instrument. */
    private Holder holder() throws Exception {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        UUID method = IDS.next();
        Actor person = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Capture Holder', now() - interval '2 hour')",
                    party);
            execute(app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    customer, party);
            execute(app,
                    "INSERT INTO paymentmethods.payment_method (id, party_id, token_reference,"
                            + " brand, display_suffix, expiry_month, expiry_year, status,"
                            + " created_at) VALUES (?, ?, ?, 'Visa', '4242', 12, 2030,"
                            + " 'ACTIVE', now())",
                    method, party, "tok-capture-" + UUID.randomUUID());
        }

        SecurityContext.Scope actor = SecurityContext.enter(person);
        CorrelationContext.Scope flow =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("fix-" + UUID.randomUUID())));
        try {
            runner.inTransaction(
                    uow ->
                            new AccountOpening(
                                            new JdbcCustomerAccountStore(),
                                            ledgerAccounts,
                                            new VerifiedAccountHolder(new JdbcPartyStore()),
                                            new JdbcAuditWriter(),
                                            new JdbcOutboxWriter(),
                                            IDS,
                                            CLOCK)
                                    .open(uow, party, ProductType.WALLET, EUR));

            PaymentCreation.CreationResult created =
                    runner.inTransaction(
                            uow ->
                                    new PaymentCreation(
                                                    executor(),
                                                    participants,
                                                    intents,
                                                    new JdbcAuditWriter(),
                                                    new JdbcOutboxWriter(),
                                                    IDS,
                                                    CLOCK)
                                            .create(
                                                    uow,
                                                    new PaymentCreation.CreatePaymentCommand(
                                                            party,
                                                            method,
                                                            AMOUNT,
                                                            "cap-" + UUID.randomUUID())));
            LedgerAccountId wallet =
                    runner.inTransaction(
                            uow ->
                                    intents.findById(uow, created.intent())
                                            .orElseThrow()
                                            .walletAccount());
            return new Holder(party, person, created.intent(), wallet);
        } finally {
            flow.close();
            actor.close();
        }
    }

    /** Confirms through the real adapter to a real AUTHORIZED attempt. */
    private PaymentAttemptId authorizedAttempt(Holder holder) throws Exception {
        psp.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH,
                200,
                APPROVED_BODY.formatted("auth-" + UUID.randomUUID()));
        SecurityContext.Scope actor = SecurityContext.enter(holder.person());
        CorrelationContext.Scope flow =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("auth-" + UUID.randomUUID())));
        try {
            PaymentConfirmation.ConfirmationResult confirmed =
                    new PaymentConfirmation(
                                    runner, intents, attempts, evidence, participants,
                                    adapter(),
                                    new com.finapp.payments.PaymentOutcomes(
                                            intents, attempts,
                                            new com.finapp.payments.JdbcRefundStore(),
                                            new com.finapp.ledger.HoldService(
                                                    ledgerAccounts,
                                                    new com.finapp.ledger.JdbcBalanceDerivation(),
                                                    new com.finapp.ledger.JdbcHoldStore(),
                                                    new JdbcBalanceProjection(),
                                                    new JdbcAuditWriter(),
                                                    new JdbcOutboxWriter(),
                                                    IDS, CLOCK),
                                            new PostingService(
                                                    executor(),
                                                    new JdbcJournalEntryStore(IDS),
                                                    new JdbcAuditWriter(),
                                                    new JdbcOutboxWriter(),
                                                    new JdbcBalanceProjection(),
                                                    IDS, CLOCK, PostingObserver.NONE),
                                            new ChartOfAccounts<>(ledgerAccounts),
                                            // THE PRODUCTION SEAM (P6-TSK-005): the composition production posts
                                            // through, not the wallet one directly - so "no fee pin, two lines" is
                                            // proven where it matters. A payment with no pin falls back, which is
                                            // this suite's every payment.
                                            new com.finapp.app.merchant.MerchantBoundCaptureComposition(
                                                    new com.finapp.merchant.MerchantSettlement(
                                                            new com.finapp.merchant.JdbcPaymentFeePinStore(),
                                                            new com.finapp.merchant.JdbcFeeScheduleStore(),
                                                            ledgerAccounts,
                                                            new ChartOfAccounts<>(ledgerAccounts),
                                                            new JdbcOutboxWriter(),
                                                            IDS),
                                                    new com.finapp.payments.WalletTopUpComposition(),
                        // No completion: these suites' payments belong to no checkout
                        // session, and the production consumer is wired in CheckoutBeans.
                        landed -> {}),
                                            // THE REFUND'S MIRROR SEAM (P6-TSK-014), production's own: every
                                            // refund here falls back to Phase 5's two lines.
                                            new com.finapp.app.merchant.MerchantBoundRefundComposition(
                                                    new com.finapp.merchant.MerchantSettlement(
                                                            new com.finapp.merchant.JdbcPaymentFeePinStore(),
                                                            new com.finapp.merchant.JdbcFeeScheduleStore(),
                                                            ledgerAccounts,
                                                            new ChartOfAccounts<>(ledgerAccounts),
                                                            new JdbcOutboxWriter(),
                                                            IDS),
                                                    new com.finapp.payments.WalletRefundComposition()),
                                            new JdbcAuditWriter(), new JdbcOutboxWriter(),
                                            IDS, CLOCK),
                                    new JdbcAuditWriter(),
                                    IDS, CLOCK)
                            .confirm(holder.party(), holder.intent());
            assertThat(confirmed.attempt()).contains(PaymentAttemptStatus.AUTHORIZED);
        } finally {
            flow.close();
            actor.close();
        }
        psp.reset();
        try (Connection app = DatabaseRoles.application()) {
            return attempts.findForIntent(app, holder.intent()).orElseThrow().id();
        }
    }

    private PaymentCapture capture(PaymentProvider provider) {
        return capture(provider, new JdbcOutboxWriter());
    }

    private PaymentCapture capture(PaymentProvider provider, OutboxWriter<Connection> outbox) {
        return new PaymentCapture(
                runner,
                intents,
                attempts,
                evidence,
                provider,
                new com.finapp.payments.PaymentOutcomes(
                        intents,
                        attempts,
                        new com.finapp.payments.JdbcRefundStore(),
                        new com.finapp.ledger.HoldService(
                                ledgerAccounts,
                                new com.finapp.ledger.JdbcBalanceDerivation(),
                                new com.finapp.ledger.JdbcHoldStore(),
                                new JdbcBalanceProjection(),
                                new JdbcAuditWriter(),
                                new JdbcOutboxWriter(),
                                IDS, CLOCK),
                        new PostingService(
                                executor(),
                                new JdbcJournalEntryStore(IDS),
                                new JdbcAuditWriter(),
                                new JdbcOutboxWriter(),
                                new JdbcBalanceProjection(),
                                IDS,
                                CLOCK,
                                PostingObserver.NONE),
                        new ChartOfAccounts<>(ledgerAccounts),
                        // THE PRODUCTION SEAM (P6-TSK-005): the composition production posts
                        // through, not the wallet one directly - so "no fee pin, two lines" is
                        // proven where it matters. A payment with no pin falls back, which is
                        // this suite's every payment.
                        new com.finapp.app.merchant.MerchantBoundCaptureComposition(
                                new com.finapp.merchant.MerchantSettlement(
                                        new com.finapp.merchant.JdbcPaymentFeePinStore(),
                                        new com.finapp.merchant.JdbcFeeScheduleStore(),
                                        ledgerAccounts,
                                        new ChartOfAccounts<>(ledgerAccounts),
                                        new JdbcOutboxWriter(),
                                        IDS),
                                new com.finapp.payments.WalletTopUpComposition(),
                        // No completion: these suites' payments belong to no checkout
                        // session, and the production consumer is wired in CheckoutBeans.
                        landed -> {}),
                        // THE REFUND'S MIRROR SEAM (P6-TSK-014), production's own: every
                        // refund here falls back to Phase 5's two lines.
                        new com.finapp.app.merchant.MerchantBoundRefundComposition(
                                new com.finapp.merchant.MerchantSettlement(
                                        new com.finapp.merchant.JdbcPaymentFeePinStore(),
                                        new com.finapp.merchant.JdbcFeeScheduleStore(),
                                        ledgerAccounts,
                                        new ChartOfAccounts<>(ledgerAccounts),
                                        new JdbcOutboxWriter(),
                                        IDS),
                                new com.finapp.payments.WalletRefundComposition()),
                        new JdbcAuditWriter(),
                        outbox,
                        IDS,
                        CLOCK),
                new JdbcAuditWriter(),
                IDS,
                CLOCK);
    }

    private static IdempotentExecutor executor() {
        return new IdempotentExecutor(
                new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1),
                Duration.ofMinutes(5));
    }

    private SimulatedCardPspAdapter adapter() {
        return adapter(Duration.ofSeconds(2));
    }

    private SimulatedCardPspAdapter adapter(Duration timeout) {
        return new SimulatedCardPspAdapter(URI.create(psp.baseUrl()), timeout, PSP_KEY);
    }

    private static int entriesReferencing(Connection app, PaymentAttemptId attempt)
            throws SQLException {
        try (PreparedStatement count = app.prepareStatement(
                "SELECT count(*) FROM ledger.journal_entry WHERE reference = ?")) {
            count.setString(1, attempt.value().toString());
            try (ResultSet result = count.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    /** Each line of the attempt's entry as {@code DIRECTION:PURPOSE} — never an amount. */
    private static List<String> linePurposes(Connection app, PaymentAttemptId attempt)
            throws SQLException {
        try (PreparedStatement read = app.prepareStatement(
                "SELECT line.direction, account.purpose FROM ledger.journal_line line"
                        + " JOIN ledger.journal_entry entry ON entry.id = line.entry_id"
                        + " JOIN ledger.ledger_account account ON account.id = line.ledger_account_id"
                        + " WHERE entry.reference = ?")) {
            read.setString(1, attempt.value().toString());
            try (ResultSet rows = read.executeQuery()) {
                List<String> lines = new java.util.ArrayList<>();
                while (rows.next()) {
                    lines.add(rows.getString(1) + ":" + rows.getString(2));
                }
                return lines;
            }
        }
    }

    private static int lineCountFor(Connection app, PaymentAttemptId attempt)
            throws SQLException {
        try (PreparedStatement count = app.prepareStatement(
                "SELECT count(*) FROM ledger.journal_line line"
                        + " JOIN ledger.journal_entry entry ON entry.id = line.entry_id"
                        + " WHERE entry.reference = ?")) {
            count.setString(1, attempt.value().toString());
            try (ResultSet result = count.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    /** The production runner's shape over the test roles. */
    private static final class CountingRunner implements TransactionRunner {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            try (Connection unitOfWork = DatabaseRoles.application()) {
                try {
                    unitOfWork.setAutoCommit(false);
                    R result = work.apply(unitOfWork);
                    unitOfWork.commit();
                    return result;
                } catch (RuntimeException failure) {
                    unitOfWork.rollback();
                    throw failure;
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("transaction plumbing failed", failure);
            }
        }
    }

    private static final class CrashingProvider implements PaymentProvider {
        @Override
        public String providerName() {
            return "crashing";
        }

        @Override
        public ProviderAnswer authorize(AuthorizationRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderAnswer capture(CaptureRequest request) {
            throw new IllegalStateException("simulated crash mid-capture");
        }

        @Override
        public ProviderAnswer refund(RefundRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public QueryAnswer query(ProviderIdempotencyReference ourReference) {
            throw new UnsupportedOperationException();
        }
    }
}
