package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounts.AccountOpening;
import com.finapp.accounts.JdbcCustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.app.accounts.VerifiedAccountHolder;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.HoldExceedsAvailableBalanceException;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcHoldStore;
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
import com.finapp.payments.JdbcRefundStore;
import com.finapp.payments.PaymentAttemptId;
import com.finapp.payments.PaymentCapture;
import com.finapp.payments.PaymentConfirmation;
import com.finapp.payments.PaymentCreation;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentNotRefundableException;
import com.finapp.payments.PaymentOutcomes;
import com.finapp.payments.PaymentRefund;
import com.finapp.payments.RefundExceedsCaptureException;
import com.finapp.payments.RefundId;
import com.finapp.payments.RefundStatus;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.TransactionRunner;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The refund command against a real PostgreSQL and a real HTTP provider (`P5-TSK-015`,
 * ADR-0048 §4): hold, then post — `P3-TSK-015`'s owed composition meeting its production
 * caller, the bound proven at both ranks, and every accept counted in the tables.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the refund command (P5-TSK-015)")
class PaymentRefundDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Money CAPTURED = Money.ofMinorUnits(12_00, EUR);
    private static final byte[] PSP_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EVIDENCE_KEY =
            "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);

    private static final byte[] WEBHOOK_KEY =
            "refund-webhook-key-0123456789abcdef".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);

    private static SimulatedProvider psp;

    private final CountingRunner runner = new CountingRunner();
    private final JdbcPaymentIntentStore intents = new JdbcPaymentIntentStore();
    private final JdbcPaymentAttemptStore attempts = new JdbcPaymentAttemptStore();
    private final JdbcRefundStore refunds = new JdbcRefundStore();
    private final JdbcProviderEvidenceStore evidence =
            new JdbcProviderEvidenceStore(
                    new EvidenceCipher(EVIDENCE_KEY, 1, new SecureRandom()), IDS);
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
    private SecurityContext.Scope operator;

    @BeforeEach
    void reset() {
        psp.reset();
        testFlow =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("rfd-" + UUID.randomUUID())));
        // The refund is the OPERATOR's act; the boundary check is the endpoint suite's, and
        // here the command receives the actor the way the interceptor would hand it over.
        operator =
                SecurityContext.enter(
                        new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER));
    }

    @AfterEach
    void leaveScopes() {
        operator.close();
        testFlow.close();
    }

    // -----------------------------------------------------------------
    // The bound, at both ranks, under the race
    // -----------------------------------------------------------------

    @Test
    @DisplayName("ten concurrent partials of 4.00 against a captured 12.00 accept EXACTLY three,"
            + " counted - the rest refused cleanly at the bound, never as our 500")
    void tenConcurrentPartialsAcceptExactlyTheBoundedSet() throws Exception {
        Captured captured = capturedPayment();
        // The dispatches race into AMBIGUITY, deliberately: the bound's race lives at the
        // dispatch (the attempt lock, the sibling sum, the hold), and a provider answering
        // one shared stub reference for three distinct operations would trip V004's
        // provider-reference UNIQUE - a harness artifact, not a provider behaviour (each
        // real operation carries its own reference; the per-refund completion is its own
        // test above). Three UNKNOWNs with three standing holds is the sharper count.
        psp.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.REFUNDS_PATH);

        int refunders = 10;
        Money partial = Money.ofMinorUnits(4_00, EUR);
        CountDownLatch open = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(refunders);
        int accepted = 0;
        int bounded = 0;
        try {
            List<Future<Object>> results =
                    java.util.stream.IntStream.range(0, refunders)
                            .<Future<Object>>mapToObj(
                                    i ->
                                            pool.submit(
                                                    () -> {
                                                        open.await();
                                                        try (CorrelationContext.Scope flow =
                                                                        CorrelationContext.enter(
                                                                                Correlation
                                                                                        .startingWith(
                                                                                                CorrelationId
                                                                                                        .generate(
                                                                                                                IDS)));
                                                                SecurityContext.Scope actor =
                                                                        SecurityContext.enter(
                                                                                new Actor(
                                                                                        UUID
                                                                                                .randomUUID()
                                                                                                .toString(),
                                                                                        ActorType
                                                                                                .CUSTOMER))) {
                                                            try {
                                                                return refundCommand()
                                                                        .refund(
                                                                                captured.intent(),
                                                                                partial,
                                                                                "race partial " + i,
                                                                                "race-"
                                                                                        + UUID
                                                                                                .randomUUID());
                                                            } catch (RuntimeException refusal) {
                                                                return refusal;
                                                            }
                                                        }
                                                    }))
                            .toList();
            open.countDown();
            for (Future<Object> result : results) {
                Object outcome = result.get();
                if (outcome instanceof PaymentRefund.RefundResult) {
                    accepted++;
                } else {
                    // The refusal must be the BOUND's clean shape - the command's lock gives
                    // honest 422s, never the trigger's 23514 surfacing as our 500.
                    assertThat(outcome)
                            .isInstanceOfAny(
                                    RefundExceedsCaptureException.class,
                                    HoldExceedsAvailableBalanceException.class);
                    bounded++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(accepted).as("exactly the bounded set (the accept)").isEqualTo(3);
        assertThat(bounded).isEqualTo(7);
        assertThat(refundRowCount(captured.attempt())).isEqualTo(3);
        assertThat(nonFailedRefundSum(captured.attempt())).isEqualTo(12_00L);
        assertThat(activeHoldCount(captured.wallet()))
                .as("three standing holds for three ambiguous refunds")
                .isEqualTo(3);
        assertThat(refundEntryCount(captured.attempt()))
                .as("ambiguity posts NOTHING, however many won the bound")
                .isZero();
        // The whole capture is reserved: one more cent is unspendable (INV-BAL-04).
        assertThatThrownBy(
                        () ->
                                runner.inTransaction(
                                        uow ->
                                                holdService()
                                                        .place(
                                                                uow,
                                                                captured.wallet(),
                                                                Money.ofMinorUnits(1, EUR))))
                .isInstanceOf(HoldExceedsAvailableBalanceException.class);
    }

    @Test
    @DisplayName("ten concurrent partials against a FUNDED wallet still accept exactly three -"
            + " the attempt lock is the arbiter when the account lock cannot be")
    void tenConcurrentPartialsAgainstAFundedWalletStillRespectTheCaptureBound() throws Exception {
        // THE PROBE THE FIRST RACE COULD NOT BE (found by this task's own battery): with the
        // wallet holding exactly the captured amount, dropping the attempt row lock SURVIVED
        // the race above - the hold placements serialize on the ACCOUNT lock and INV-BAL-04
        // refuses the overrun, masking the mutation entirely. A real customer's wallet holds
        // other money too, so here the wallet is funded BEYOND the capture: the account lock
        // can no longer arbitrate, every refusal must be the domain bound's own exception,
        // and a dispatcher that summed without the attempt lock surfaces as the trigger's
        // 23514 - caught by the refusal-type assertion.
        Captured captured = capturedPayment();
        runner.inTransaction(
                uow -> {
                    com.finapp.ledger.LedgerAccount clearing =
                            new ChartOfAccounts<Connection>(ledgerAccounts)
                                    .resolve(
                                            uow,
                                            com.finapp.ledger.AccountPurpose.SETTLEMENT_CLEARING,
                                            EUR);
                    postingService()
                            .post(
                                    uow,
                                    new com.finapp.ledger.PostingCommand(
                                            "fund-" + IDS.next(),
                                            java.time.LocalDate.now(CLOCK),
                                            java.time.LocalDate.now(CLOCK),
                                            "other money",
                                            List.of(
                                                    new com.finapp.ledger.JournalLine(
                                                            captured.wallet(),
                                                            com.finapp.ledger.Direction.CREDIT,
                                                            Money.ofMinorUnits(20_00, EUR)),
                                                    new com.finapp.ledger.JournalLine(
                                                            clearing.id(),
                                                            com.finapp.ledger.Direction.DEBIT,
                                                            Money.ofMinorUnits(20_00, EUR)))));
                    return null;
                });
        psp.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.REFUNDS_PATH);

        int refunders = 10;
        Money partial = Money.ofMinorUnits(4_00, EUR);
        CountDownLatch open = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(refunders);
        int accepted = 0;
        try {
            List<Future<Object>> results =
                    java.util.stream.IntStream.range(0, refunders)
                            .<Future<Object>>mapToObj(
                                    i ->
                                            pool.submit(
                                                    () -> {
                                                        open.await();
                                                        try (CorrelationContext.Scope flow =
                                                                        CorrelationContext.enter(
                                                                                Correlation
                                                                                        .startingWith(
                                                                                                CorrelationId
                                                                                                        .generate(
                                                                                                                IDS)));
                                                                SecurityContext.Scope actor =
                                                                        SecurityContext.enter(
                                                                                new Actor(
                                                                                        UUID
                                                                                                .randomUUID()
                                                                                                .toString(),
                                                                                        ActorType
                                                                                                .CUSTOMER))) {
                                                            try {
                                                                return refundCommand()
                                                                        .refund(
                                                                                captured.intent(),
                                                                                partial,
                                                                                "funded race " + i,
                                                                                "frace-"
                                                                                        + UUID
                                                                                                .randomUUID());
                                                            } catch (RuntimeException refusal) {
                                                                return refusal;
                                                            }
                                                        }
                                                    }))
                            .toList();
            open.countDown();
            for (Future<Object> result : results) {
                Object outcome = result.get();
                if (outcome instanceof PaymentRefund.RefundResult) {
                    accepted++;
                } else {
                    // The funded wallet leaves ONLY the capture bound to refuse - never
                    // INV-BAL-04, never the trigger's 23514 surfacing as our 500.
                    assertThat(outcome).isInstanceOf(RefundExceedsCaptureException.class);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(accepted).as("exactly the bounded set, whatever the wallet holds")
                .isEqualTo(3);
        assertThat(refundRowCount(captured.attempt())).isEqualTo(3);
        assertThat(nonFailedRefundSum(captured.attempt())).isEqualTo(12_00L);
    }

    @Test
    @DisplayName("the sum bound refuses raw SQL - the schema's rank, against a command-created"
            + " capture (23514 payments_refund_is_bounded)")
    void theSumBoundRefusesRawSql() throws Exception {
        Captured captured = capturedPayment();

        try (Connection app = DatabaseRoles.application()) {
            assertThatThrownBy(
                            () ->
                                    execute(
                                            app,
                                            "INSERT INTO payments.refund (id, attempt_id,"
                                                + " amount_minor, currency, scale, reason,"
                                                + " hold_reference,"
                                                + " provider_idempotency_reference, status,"
                                                + " created_at)"
                                                + " VALUES (?, ?, 1300, 'EUR', 2, 'raw sql"
                                                + " probe', ?, ?, 'DISPATCHED', now())",
                                            IDS.next(),
                                            captured.attempt().value(),
                                            IDS.next(),
                                            "raw-" + UUID.randomUUID()))
                    .hasMessageContaining("payments_refund_is_bounded");
        }
    }

    // -----------------------------------------------------------------
    // Hold, then post: the money's whereabouts at every instant
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the held funds are UNSPENDABLE mid-flight (driven): ambiguity commits UNKNOWN"
            + " with the hold standing, and a competing spend is refused by INV-BAL-04")
    void heldFundsAreUnspendableMidFlight() throws Exception {
        Captured captured = capturedPayment();
        psp.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.REFUNDS_PATH);

        PaymentRefund.RefundResult result =
                refundCommand()
                        .refund(
                                captured.intent(),
                                CAPTURED,
                                "full refund into ambiguity",
                                "amb-" + UUID.randomUUID());

        assertThat(result.status().name()).isEqualTo("UNKNOWN");
        assertThat(refundEntryCount(captured.attempt())).as("ambiguity posts NOTHING").isZero();
        // The competing spend, DRIVEN: the wallet's settled money is fully reserved, so a
        // hold for one cent is refused - the customer cannot spend what the provider may be
        // returning (INV-BAL-04 doing refund duty).
        assertThatThrownBy(
                        () ->
                                runner.inTransaction(
                                        uow ->
                                                holdService()
                                                        .place(
                                                                uow,
                                                                captured.wallet(),
                                                                Money.ofMinorUnits(1, EUR))))
                .isInstanceOf(HoldExceedsAvailableBalanceException.class);
    }

    @Test
    @DisplayName("completion releases-and-posts atomically: the wallet drops, the hold is gone,"
            + " and exactly one payment-refund entry exists")
    void completionReleasesAndPostsAtomically() throws Exception {
        Captured captured = capturedPayment();
        providerRefunds("psp_rfd-ok");

        PaymentRefund.RefundResult result =
                refundCommand()
                        .refund(
                                captured.intent(),
                                Money.ofMinorUnits(5_00, EUR),
                                "customer complaint upheld",
                                "ok-" + UUID.randomUUID());

        assertThat(result.status().name()).isEqualTo("COMPLETED");
        assertThat(refundEntryCount(captured.attempt())).isEqualTo(1);
        assertThat(settled(captured.wallet())).isEqualTo("7.00");
        // The hold is GONE, not lingering: the remaining 7.00 is spendable to the cent.
        runner.inTransaction(
                uow -> holdService().place(uow, captured.wallet(), Money.ofMinorUnits(7_00, EUR)));
    }

    @Test
    @DisplayName("declined releases with nothing posted - the customer's money is theirs again,"
            + " and the freed budget refunds in full afterwards")
    void declinedReleasesWithNothingPosted() throws Exception {
        Captured captured = capturedPayment();
        psp.succeedsWith(
                SimulatedCardPspAdapter.REFUNDS_PATH, 200, "{\"status\":\"declined\"}");

        PaymentRefund.RefundResult declined =
                refundCommand()
                        .refund(
                                captured.intent(),
                                Money.ofMinorUnits(5_00, EUR),
                                "declined by provider",
                                "dec-" + UUID.randomUUID());

        assertThat(declined.status().name()).isEqualTo("FAILED");
        assertThat(refundEntryCount(captured.attempt())).isZero();
        assertThat(settled(captured.wallet())).isEqualTo("12.00");

        // The freed budget (a FAILED refund no longer counts) refunds TO THE PENNY.
        providerRefunds("psp_rfd-retry");
        PaymentRefund.RefundResult full =
                refundCommand()
                        .refund(
                                captured.intent(),
                                CAPTURED,
                                "second attempt, full",
                                "full-" + UUID.randomUUID());
        assertThat(full.status().name()).isEqualTo("COMPLETED");
        assertThat(settled(captured.wallet())).isEqualTo("0.00");

        // And one minor unit past the (now exhausted) bound is the clean domain refusal.
        assertThatThrownBy(
                        () ->
                                refundCommand()
                                        .refund(
                                                captured.intent(),
                                                Money.ofMinorUnits(1, EUR),
                                                "over the bound",
                                                "over-" + UUID.randomUUID()))
                .isInstanceOf(RefundExceedsCaptureException.class);
    }

    // -----------------------------------------------------------------
    // Refusals with nothing written
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an uncaptured payment is not refundable, and no hold is placed on the way to"
            + " the refusal")
    void anUncapturedPaymentIsNotRefundable() throws Exception {
        Captured captured = capturedPayment(false);

        assertThatThrownBy(
                        () ->
                                refundCommand()
                                        .refund(
                                                captured.intent(),
                                                Money.ofMinorUnits(1_00, EUR),
                                                "premature",
                                                "pre-" + UUID.randomUUID()))
                .isInstanceOf(PaymentNotRefundableException.class);
        assertThat(refundRowCount(captured.attempt())).isZero();
        assertThat(activeHoldCount(captured.wallet())).isZero();
    }

    @Test
    @DisplayName("a refund the wallet cannot fund is refused with nothing written - the"
            + " customer has spent the money (INV-BAL-04)")
    void anUnfundedRefundIsRefusedWithNothingWritten() throws Exception {
        Captured captured = capturedPayment();
        // The customer spends everything: a real posting out of the wallet.
        runner.inTransaction(
                uow -> {
                    com.finapp.ledger.LedgerAccount clearing =
                            new ChartOfAccounts<Connection>(ledgerAccounts)
                                    .resolve(
                                            uow,
                                            com.finapp.ledger.AccountPurpose.SETTLEMENT_CLEARING,
                                            EUR);
                    postingService()
                            .post(
                                    uow,
                                    new com.finapp.ledger.PostingCommand(
                                            "spend-" + IDS.next(),
                                            java.time.LocalDate.now(CLOCK),
                                            java.time.LocalDate.now(CLOCK),
                                            "spent",
                                            List.of(
                                                    new com.finapp.ledger.JournalLine(
                                                            captured.wallet(),
                                                            com.finapp.ledger.Direction.DEBIT,
                                                            CAPTURED),
                                                    new com.finapp.ledger.JournalLine(
                                                            clearing.id(),
                                                            com.finapp.ledger.Direction.CREDIT,
                                                            CAPTURED))));
                    return null;
                });

        assertThatThrownBy(
                        () ->
                                refundCommand()
                                        .refund(
                                                captured.intent(),
                                                Money.ofMinorUnits(5_00, EUR),
                                                "unfunded",
                                                "unf-" + UUID.randomUUID()))
                .isInstanceOf(HoldExceedsAvailableBalanceException.class);
        assertThat(refundRowCount(captured.attempt())).isZero();
    }

    @Test
    @DisplayName("a retried key replays the dispatch: same refund, no second hold, no second"
            + " wire call")
    void aRetriedKeyReplaysWithoutASecondHold() throws Exception {
        Captured captured = capturedPayment();
        providerRefunds("psp_rfd-replay");
        String key = "rep-" + UUID.randomUUID();

        PaymentRefund.RefundResult first =
                refundCommand()
                        .refund(captured.intent(), Money.ofMinorUnits(3_00, EUR), "once", key);
        PaymentRefund.RefundResult second =
                refundCommand()
                        .refund(captured.intent(), Money.ofMinorUnits(3_00, EUR), "once", key);

        assertThat(second.replayed()).isTrue();
        assertThat(second.refund()).isEqualTo(first.refund());
        assertThat(psp.requestCount(SimulatedCardPspAdapter.REFUNDS_PATH))
                .as("the replay never reaches the wire")
                .isEqualTo(1);
        assertThat(refundRowCount(captured.attempt())).isEqualTo(1);
        assertThat(holdsEverPlaced(captured.wallet()))
                .as("one hold for one refund, however many retries")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // The webhook-completed refund (P5-TSK-016): asynchronous completion, and the facts
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a webhook completes the UNKNOWN refund: released-and-posted, one entry, the"
            + " RefundCompleted fact once - and a duplicate under a fresh event id converges")
    void aWebhookCompletesTheUnknownRefund() throws Exception {
        Captured captured = capturedPayment();
        psp.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.REFUNDS_PATH);
        PaymentRefund.RefundResult result =
                refundCommand()
                        .refund(
                                captured.intent(),
                                Money.ofMinorUnits(5_00, EUR),
                                "async completion",
                                "wh-" + UUID.randomUUID());
        assertThat(result.status()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(activeHoldCount(captured.wallet())).isEqualTo(1);
        assertThat(refundEventCount(result.refund(), "payments.RefundInitiated")).isEqualTo(1);
        assertThat(refundEventCount(result.refund(), "payments.RefundCompleted")).isZero();

        // The provider's unsolicited statement, quoting OUR minted reference (INV-PAY-04).
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        PaymentWebhookService webhooks = webhookService(registry);
        String reference = refundReference(result.refund());

        // A word outside the total vocabulary first: retained, acknowledged, NOTHING moves
        // (INV-PAY-03 - the mapping's default is never success).
        deliverWebhook(
                webhooks,
                "{\"eventId\":\"rfd-evt-" + UUID.randomUUID() + "\",\"operation\":\""
                        + reference
                        + "\",\"status\":\"pending_review\",\"reference\":\"psp_x\"}");
        assertThat(refundStatus(result.refund())).isEqualTo("UNKNOWN");
        assertThat(refundEntryCount(captured.attempt())).isZero();
        // And the METER calls it what it is (`P5-TSK-017`): a provider saying something we
        // do not understand about money we are holding is an INTEGRATION BREAK, not a
        // processed delivery - the plan's §15 note names this series for exactly that rise.
        assertThat(webhookCount(registry, "unmappable")).isEqualTo(1);
        assertThat(webhookCount(registry, "processed")).isZero();

        deliverWebhook(
                webhooks,
                "{\"eventId\":\"rfd-evt-" + UUID.randomUUID() + "\",\"operation\":\""
                        + reference + "\",\"status\":\"approved\",\"reference\":\"psp_rfd-wh\"}");

        assertThat(refundStatus(result.refund())).isEqualTo("COMPLETED");
        assertThat(activeHoldCount(captured.wallet())).as("released with the posting").isZero();
        assertThat(refundEntryCount(captured.attempt())).isEqualTo(1);
        assertThat(settled(captured.wallet())).isEqualTo("7.00");
        // The capture's inverse pair, on the accounts ADR-0048 §4 names (`P5-TST-002`,
        // INV-SET-01): the customer's wallet is debited and the CLEARING position credited
        // - the money goes back to where it came from, never out of settled cash. The
        // capture's own probe found this gap by mutation; the refund gets the same rank.
        assertThat(refundLinePurposes(captured.attempt()))
                .as("DR the customer's wallet / CR settlement clearing")
                .containsExactlyInAnyOrder(
                        "DEBIT:CUSTOMER_WALLET", "CREDIT:SETTLEMENT_CLEARING");
        assertThat(refundEventCount(result.refund(), "payments.RefundCompleted"))
                .as("the terminal fact, once, in the committing transaction")
                .isEqualTo(1);
        // Identifiers and enumerated names only - never an amount (the needle).
        assertThat(refundEventPayloads(result.refund()))
                .doesNotContain("5.00")
                .doesNotContain("500")
                .doesNotContain("psp_rfd-wh");

        assertThat(webhookCount(registry, "processed"))
                .as("understood and handled")
                .isEqualTo(1);

        // THE SAME EVENT ID AGAIN: the inbox absorbs it, and the meter says duplicate -
        // counting a redelivery as throughput would make an at-least-once provider look
        // like traffic (INV-IDEM-04 at the meter).
        String repeated = "rfd-evt-" + UUID.randomUUID();
        String body =
                "{\"eventId\":\"" + repeated + "\",\"operation\":\"" + reference
                        + "\",\"status\":\"approved\",\"reference\":\"psp_rfd-wh\"}";
        deliverWebhook(webhooks, body);
        deliverWebhook(webhooks, body);
        assertThat(webhookCount(registry, "duplicate"))
                .as("the second delivery of one event id is a duplicate, never throughput")
                .isEqualTo(1);
        assertThat(refundEntryCount(captured.attempt()))
                .as("and it moved nothing, as every other rank already proved")
                .isEqualTo(1);

        // An authentic delivery naming an operation NOBODY minted: unmappable too.
        deliverWebhook(
                webhooks,
                "{\"eventId\":\"rfd-evt-" + UUID.randomUUID()
                        + "\",\"operation\":\"rfd-nobody-minted-this\",\"status\":\"approved\","
                        + "\"reference\":\"psp_y\"}");
        assertThat(webhookCount(registry, "unmappable")).isEqualTo(2);
        assertThat(webhookCount(registry, "refused"))
                .as("nothing here failed authentication")
                .isZero();

        // Distinct-id duplicate: past the inbox by design, absorbed by the machine - no
        // second posting, no second fact (INV-IDEM-04 at both ranks).
        deliverWebhook(
                webhooks,
                "{\"eventId\":\"rfd-evt-" + UUID.randomUUID() + "\",\"operation\":\""
                        + reference + "\",\"status\":\"approved\",\"reference\":\"psp_rfd-wh\"}");
        assertThat(refundEntryCount(captured.attempt())).isEqualTo(1);
        assertThat(refundEventCount(result.refund(), "payments.RefundCompleted")).isEqualTo(1);
        assertThat(transitionCount(result.refund(), "COMPLETED")).isEqualTo(1);

        // A contradictory late report on the terminal refund: evidence only (INV-LIFE-04).
        deliverWebhook(
                webhooks,
                "{\"eventId\":\"rfd-evt-" + UUID.randomUUID() + "\",\"operation\":\""
                        + reference + "\",\"status\":\"declined\",\"reference\":\"psp_x\"}");
        assertThat(refundStatus(result.refund())).isEqualTo("COMPLETED");
        assertThat(refundEventCount(result.refund(), "payments.RefundFailed")).isZero();
    }

    @Test
    @DisplayName("a declined webhook fails the UNKNOWN refund: released, nothing posted, the"
            + " RefundFailed fact once")
    void aDeclinedWebhookFailsTheRefund() throws Exception {
        Captured captured = capturedPayment();
        psp.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.REFUNDS_PATH);
        PaymentRefund.RefundResult result =
                refundCommand()
                        .refund(
                                captured.intent(),
                                Money.ofMinorUnits(5_00, EUR),
                                "declined async",
                                "whd-" + UUID.randomUUID());
        assertThat(result.status()).isEqualTo(RefundStatus.UNKNOWN);

        deliverWebhook(
                webhookService(),
                "{\"eventId\":\"rfd-evt-" + UUID.randomUUID() + "\",\"operation\":\""
                        + refundReference(result.refund())
                        + "\",\"status\":\"declined\",\"reference\":\"psp_no\"}");

        assertThat(refundStatus(result.refund())).isEqualTo("FAILED");
        assertThat(activeHoldCount(captured.wallet()))
                .as("the customer's money is theirs again")
                .isZero();
        assertThat(refundEntryCount(captured.attempt())).isZero();
        assertThat(settled(captured.wallet())).isEqualTo("12.00");
        assertThat(refundEventCount(result.refund(), "payments.RefundFailed")).isEqualTo(1);
        assertThat(refundEventCount(result.refund(), "payments.RefundCompleted")).isZero();
    }

    @Test
    @DisplayName("ten concurrent refund webhooks, distinct event ids, one UNKNOWN refund: one"
            + " entry, one COMPLETED transition, one fact - counted in the tables")
    void tenConcurrentRefundWebhooksProduceOneEffect() throws Exception {
        Captured captured = capturedPayment();
        psp.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.REFUNDS_PATH);
        PaymentRefund.RefundResult result =
                refundCommand()
                        .refund(
                                captured.intent(),
                                Money.ofMinorUnits(5_00, EUR),
                                "raced completion",
                                "whr-" + UUID.randomUUID());
        assertThat(result.status()).isEqualTo(RefundStatus.UNKNOWN);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        PaymentWebhookService webhooks = webhookService(registry);
        String reference = refundReference(result.refund());

        int resolvers = 10;
        CountDownLatch open = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(resolvers);
        try {
            List<Future<Object>> results =
                    java.util.stream.IntStream.range(0, resolvers)
                            .<Future<Object>>mapToObj(
                                    i ->
                                            pool.submit(
                                                    () -> {
                                                        open.await();
                                                        try (CorrelationContext.Scope flow =
                                                                CorrelationContext.enter(
                                                                        Correlation.startingWith(
                                                                                CorrelationId
                                                                                        .generate(
                                                                                                IDS)))) {
                                                            deliverWebhook(
                                                                    webhooks,
                                                                    "{\"eventId\":\"rfd-race-" + i
                                                                            + "-"
                                                                            + UUID.randomUUID()
                                                                            + "\",\"operation\":\""
                                                                            + reference
                                                                            + "\",\"status\":\"approved\","
                                                                            + "\"reference\":\"psp_rfd-race\"}");
                                                        }
                                                        return null;
                                                    }))
                            .toList();
            open.countDown();
            for (Future<Object> delivery : results) {
                delivery.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // Counted where winners are counted - the tables.
        assertThat(refundStatus(result.refund())).isEqualTo("COMPLETED");
        assertThat(refundEntryCount(captured.attempt())).isEqualTo(1);
        assertThat(transitionCount(result.refund(), "COMPLETED")).isEqualTo(1);
        assertThat(refundEventCount(result.refund(), "payments.RefundCompleted")).isEqualTo(1);
        assertThat(activeHoldCount(captured.wallet())).isZero();
        assertThat(settled(captured.wallet())).isEqualTo("7.00");

        // AND THE METER COUNTS ONE (`P5-TSK-017`): nine resolvers converged on a judgement
        // they did not make, and throughput that counted them would report ten refunds
        // where one customer got their money back. The acting bit is the conditional
        // transition's own row count, and this is where it earns its place.
        assertThat(
                        registry.find("finapp.payments.refund")
                                .tag("outcome", "completed")
                                .counter()
                                .count())
                .as("one judgement, counted once, however many resolvers raced for it")
                .isEqualTo(1.0);
        assertThat(
                        registry.find("finapp.payments.webhook")
                                .tag("outcome", "processed")
                                .counter()
                                .count())
                .as("every delivery that committed IS a processed delivery - the webhook"
                        + " counter measures the door, not the judgement")
                .isEqualTo(10.0);
    }

    @Test
    @DisplayName("the taken-over retry finishes the crashed flight: converges on the committed"
            + " dispatch, re-drives the wire with the STORED reference, and the response of"
            + " record replays byte-for-byte from then on")
    void theTakenOverRetryFinishesTheCrashedFlight() throws Exception {
        Captured captured = capturedPayment();
        Money amount = Money.ofMinorUnits(5_00, EUR);
        String reason = "crashed flight";
        String key = "crash-" + UUID.randomUUID();
        com.finapp.platform.security.Actor actor = SecurityContext.require();

        // The crashed flight's Tx1, reconstructed as it commits: the hold, the refund row
        // carrying the dispatch key (V008), and the claim IN_PROGRESS whose lease has
        // already expired (the platform suite's negative-lease idiom - the database's own
        // clock judges expiry).
        com.finapp.ledger.Hold hold =
                runner.inTransaction(uow -> holdService().place(uow, captured.wallet(), amount));
        UUID refundId = IDS.next();
        String storedReference = "rfd-crash-" + UUID.randomUUID();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO payments.refund (id, attempt_id, amount_minor, currency,"
                            + " scale, reason, hold_reference,"
                            + " provider_idempotency_reference, status, created_at,"
                            + " dispatch_key)"
                            + " VALUES (?, ?, 500, 'EUR', 2, ?, ?, ?, 'DISPATCHED', now(), ?)",
                    refundId,
                    captured.attempt().value(),
                    reason,
                    hold.id().value(),
                    storedReference,
                    key);
        }
        try (Connection other = DatabaseRoles.application()) {
            other.setAutoCommit(false);
            new JdbcIdempotencyRecordStore()
                    .claim(
                            other,
                            new com.finapp.platform.idempotency.IdempotencyKey(
                                    "payment.refund", key),
                            com.finapp.platform.idempotency.RequestFingerprint.sha256(
                                    ("payment.refund|" + actor.id() + "|"
                                                    + captured.intent().value() + "|500|EUR|2|"
                                                    + reason)
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            CorrelationId.of("crashed-flow"),
                            Instant.now(CLOCK),
                            Instant.now(CLOCK).plus(Duration.ofDays(1)),
                            Duration.ofSeconds(-1));
            other.commit();
        }
        providerRefunds("psp_rfd-heal");

        // The retry: takes the claim over, CONVERGES on the committed dispatch (no second
        // hold, no second row), re-asks the provider with the reference the crashed flight
        // stored (INV-PAY-04's whole point), and completes the claim with the judgement.
        PaymentRefund.RefundResult healed =
                refundCommand().refund(captured.intent(), amount, reason, key);

        assertThat(healed.replayed()).isFalse();
        assertThat(healed.refund().value()).isEqualTo(refundId);
        assertThat(healed.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(refundRowCount(captured.attempt())).isEqualTo(1);
        assertThat(holdsEverPlaced(captured.wallet()))
                .as("the crashed flight's hold, and no other")
                .isEqualTo(1);
        assertThat(psp.requestCount(SimulatedCardPspAdapter.REFUNDS_PATH)).isEqualTo(1);
        assertThat(refundEntryCount(captured.attempt())).isEqualTo(1);
        assertThat(settled(captured.wallet())).isEqualTo("7.00");

        // And from here the key answers the response of record, byte for byte.
        PaymentRefund.RefundResult replay =
                refundCommand().refund(captured.intent(), amount, reason, key);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.refund().value()).isEqualTo(refundId);
        assertThat(replay.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(psp.requestCount(SimulatedCardPspAdapter.REFUNDS_PATH))
                .as("the replay never reaches the wire")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    private record Captured(
            PaymentIntentId intent, PaymentAttemptId attempt, LedgerAccountId wallet) {}

    private Captured capturedPayment() throws Exception {
        return capturedPayment(true);
    }

    /** The full real chain to a CAPTURED (or merely AUTHORIZED) attempt with money moved. */
    private Captured capturedPayment(boolean capture) throws Exception {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        UUID method = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Refund Holder',"
                            + " now() - interval '2 hour')",
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
                    method, party, "tok-rfd-" + UUID.randomUUID());
        }
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
                                                        CAPTURED,
                                                        "rfd-" + UUID.randomUUID())));
        psp.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"psp_ra-" + UUID.randomUUID() + "\"}");
        new PaymentConfirmation(
                        runner, intents, attempts, evidence, participants, adapter(),
                        outcomes(), new JdbcAuditWriter(), IDS, CLOCK)
                .confirm(party, created.intent());
        PaymentAttemptId attemptId =
                runner.inTransaction(
                        uow ->
                                attempts.findForIntent(uow, created.intent())
                                        .orElseThrow()
                                        .id());
        if (capture) {
            psp.succeedsWith(
                    SimulatedCardPspAdapter.CAPTURES_PATH,
                    200,
                    "{\"status\":\"approved\",\"reference\":\"psp_rc-" + UUID.randomUUID()
                            + "\"}");
            new PaymentCapture(
                            runner, intents, attempts, evidence, adapter(), outcomes(),
                            new JdbcAuditWriter(), IDS, CLOCK)
                    .capture(attemptId);
        }
        LedgerAccountId wallet =
                runner.inTransaction(
                        uow ->
                                intents.findById(uow, created.intent())
                                        .orElseThrow()
                                        .walletAccount());
        psp.reset();
        return new Captured(created.intent(), attemptId, wallet);
    }

    private PaymentRefund refundCommand() {
        return new PaymentRefund(
                runner,
                executor(),
                intents,
                attempts,
                refunds,
                evidence,
                holdService(),
                adapter(),
                outcomes(),
                new JdbcAuditWriter(),
                IDS,
                CLOCK);
    }

    private HoldService holdService() {
        return new HoldService(
                ledgerAccounts,
                new JdbcBalanceDerivation(),
                new JdbcHoldStore(),
                new JdbcBalanceProjection(),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK);
    }

    private PostingService postingService() {
        return new PostingService(
                executor(),
                new JdbcJournalEntryStore(IDS),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                new JdbcBalanceProjection(),
                IDS,
                CLOCK,
                PostingObserver.NONE);
    }

    /** A registry of this suite's own: the meters' wiring is the telemetry suites'. */
    private static com.finapp.app.telemetry.PaymentMeters meters() {
        return new com.finapp.app.telemetry.PaymentMeters(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                SimulatedCardPspAdapter.NAME);
    }

    /** The webhook resolver over a registry THIS test can read (`P5-TSK-017`). */
    private PaymentWebhookService webhookService(
            io.micrometer.core.instrument.simple.SimpleMeterRegistry registry) {
        return webhookService(
                new com.finapp.app.telemetry.PaymentMeters(
                        registry, SimulatedCardPspAdapter.NAME));
    }

    private PaymentOutcomes outcomes() {
        return new PaymentOutcomes(
                intents,
                attempts,
                refunds,
                holdService(),
                postingService(),
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
                        new com.finapp.payments.WalletTopUpComposition()),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK);
    }

    private SimulatedCardPspAdapter adapter() {
        return new SimulatedCardPspAdapter(
                URI.create(psp.baseUrl()), Duration.ofSeconds(2), PSP_KEY);
    }

    /** The REAL webhook resolver, composed as the beans compose it (the sweeper-suite idiom). */
    private PaymentWebhookService webhookService() {
        return webhookService(meters());
    }

    private PaymentWebhookService webhookService(
            com.finapp.app.telemetry.PaymentMeters paymentMeters) {
        org.springframework.jdbc.datasource.DriverManagerDataSource dataSource =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        DatabaseRoles.required("finapp.db.url"),
                        DatabaseRoles.required("finapp.db.app.user"),
                        DatabaseRoles.required("finapp.db.app.password"));
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.support.JdbcTransactionManager(dataSource));
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new PaymentWebhookService(
                new com.finapp.payments.WebhookSignature(
                        WEBHOOK_KEY, Duration.ofMinutes(5), CLOCK),
                evidence,
                attempts,
                intents,
                refunds,
                paymentMeters,
                outcomes(),
                new com.finapp.platform.inbox.InboxConsumer<>(
                        new com.finapp.platform.inbox.JdbcInboxRecordStore(),
                        CLOCK,
                        Duration.ofDays(14)),
                new tools.jackson.databind.ObjectMapper(),
                CLOCK,
                template,
                dataSource);
    }

    private static void deliverWebhook(PaymentWebhookService webhooks, String body) {
        String timestamp = Long.toString(Instant.now(CLOCK).getEpochSecond());
        webhooks.deliver(
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                timestamp,
                hmacHex(timestamp + "." + body));
    }

    private static String hmacHex(String signedPayload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(WEBHOOK_KEY, "HmacSHA256"));
            return java.util.HexFormat.of()
                    .formatHex(
                            mac.doFinal(
                                    signedPayload.getBytes(
                                            java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is required by every JVM", impossible);
        }
    }

    private static void providerRefunds(String pspReference) {
        psp.succeedsWith(
                SimulatedCardPspAdapter.REFUNDS_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"" + pspReference + "\"}");
    }

    private static IdempotentExecutor executor() {
        return new IdempotentExecutor(
                new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1),
                Duration.ofMinutes(5));
    }

    // -----------------------------------------------------------------
    // Counters - in the tables, never inferred
    // -----------------------------------------------------------------

    private static double webhookCount(
            io.micrometer.core.instrument.simple.SimpleMeterRegistry registry, String outcome) {
        return registry.find("finapp.payments.webhook").tag("outcome", outcome).counter().count();
    }

    private static String refundReference(RefundId refund) throws SQLException {
        return oneString(
                "SELECT provider_idempotency_reference FROM payments.refund WHERE id = ?",
                refund.value());
    }

    private static String refundStatus(RefundId refund) throws SQLException {
        return oneString("SELECT status FROM payments.refund WHERE id = ?", refund.value());
    }

    private static long transitionCount(RefundId refund, String to) throws SQLException {
        return count(
                "SELECT count(*) FROM payments.refund_event WHERE refund_id = ?"
                        + " AND to_status = '" + to + "'",
                refund.value());
    }

    /** The published facts, counted in the outbox table - never inferred. */
    private static long refundEventCount(RefundId refund, String type) throws SQLException {
        return count(
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?"
                        + " AND event_type = '" + type + "'",
                refund.value());
    }

    private static String refundEventPayloads(RefundId refund) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT string_agg(convert_from(payload, 'UTF8'), '||')"
                                        + " FROM platform.outbox_event WHERE aggregate_id = ?")) {
            read.setObject(1, refund.value());
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getString(1);
            }
        }
    }

    private static String oneString(String sql, Object argument) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            read.setObject(1, argument);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException("no row for: " + sql);
                }
                return row.getString(1);
            }
        }
    }

    private static long nonFailedRefundSum(PaymentAttemptId attempt) throws SQLException {
        return count(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM payments.refund"
                        + " WHERE attempt_id = ? AND status <> 'FAILED'",
                attempt.value());
    }

    private static long refundRowCount(PaymentAttemptId attempt) throws SQLException {
        return count(
                "SELECT count(*) FROM payments.refund WHERE attempt_id = ?", attempt.value());
    }

    private static long refundEntryCount(PaymentAttemptId attempt) throws SQLException {
        return count(
                "SELECT count(*) FROM ledger.journal_entry e"
                        + " WHERE e.reference IN"
                        + " (SELECT r.id::text FROM payments.refund r WHERE r.attempt_id = ?)",
                attempt.value());
    }

    /** Each line of the attempt's refund entries as {@code DIRECTION:PURPOSE}. */
    private static List<String> refundLinePurposes(PaymentAttemptId attempt)
            throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT l.direction, a.purpose FROM ledger.journal_line l"
                                        + " JOIN ledger.journal_entry e ON e.id = l.entry_id"
                                        + " JOIN ledger.ledger_account a"
                                        + "   ON a.id = l.ledger_account_id"
                                        + " WHERE e.reference IN (SELECT r.id::text"
                                        + "   FROM payments.refund r WHERE r.attempt_id = ?)")) {
            read.setObject(1, attempt.value());
            try (ResultSet rows = read.executeQuery()) {
                List<String> lines = new java.util.ArrayList<>();
                while (rows.next()) {
                    lines.add(rows.getString(1) + ":" + rows.getString(2));
                }
                return lines;
            }
        }
    }

    private static long activeHoldCount(LedgerAccountId account) throws SQLException {
        return count(
                "SELECT count(*) FROM ledger.hold WHERE ledger_account_id = ? AND status = 'ACTIVE'",
                account.value());
    }

    private static long holdsEverPlaced(LedgerAccountId account) throws SQLException {
        return count(
                "SELECT count(*) FROM ledger.hold WHERE ledger_account_id = ?", account.value());
    }

    private static String settled(LedgerAccountId account) throws SQLException {
        long minor =
                count(
                        "SELECT COALESCE(posted_minor, 0) FROM ledger.account_balance"
                                + " WHERE ledger_account_id = ?",
                        account.value());
        return Money.ofMinorUnits(minor, EUR).toBigDecimal().toPlainString();
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }

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
}
