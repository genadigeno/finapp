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
                                                + " created_at, last_dispatched_at)"
                                                + " VALUES (?, ?, 1300, 'EUR', 2, 'raw sql"
                                                + " probe', ?, ?, 'DISPATCHED', now(),"
                                                + " now())",
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
        // Identifiers and enumerated names only - never an amount (the needle). The amount
        // needles are quoted-value shaped: a bare "500" matches hex inside a random UUIDv7
        // (the WithdrawalTest flake class, P7-TSK-008 - met here by a 1-in-4096 draw).
        assertThat(refundEventPayloads(result.refund()))
                .doesNotContain("5.00")
                .doesNotContain("\"500\"")
                .doesNotContain(":500")
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
        // where one customer got their money back. What stops the nine is the door's LOCKED
        // read (the Phase 6 -> 7 transition): each finds the refund finished and applies
        // nothing, so this race no longer reaches the applier's acting bit at all - P7-TSK-015's
        // gate saw a probe of that bit survive here, and aConvergedRefundApplicationCountsNothing
        // is where the bit now earns its place.
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

    /**
     * Where a converged refund application is still reachable (`P7-TSK-015`'s gate). Every
     * refund door locks the row and applies from its locked state, so a finished refund never
     * reaches the applier - except an AMBIGUOUS answer on a row another resolver has already
     * moved into {@code UNKNOWN}: a stalled flight's timeout arriving after the sweep heard its
     * own. The UNKNOWN judgement is that resolver's, and the flight's application converges.
     * Deterministic: the provider stands in for the stall, and the resolver's move goes through
     * the one shared applier into the same registry while the flight's call is out.
     */
    @Test
    @DisplayName("a converged refund application counts nothing: a stalled flight's timeout finds"
            + " its refund already moved into UNKNOWN by another resolver - one unknown"
            + " judgement counted and one outcome record, not two")
    void aConvergedRefundApplicationCountsNothing() throws Exception {
        Captured captured = capturedPayment();
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        PaymentOutcomes metered =
                outcomes(
                        new com.finapp.app.telemetry.CommittedRailOutcomes(
                                new com.finapp.app.telemetry.PaymentMeters(
                                        registry, SimulatedCardPspAdapter.NAME)));
        com.finapp.payments.PaymentProvider resolvedWhileStalled =
                new com.finapp.payments.PaymentProvider() {
                    @Override
                    public String providerName() {
                        return "resolved-while-stalled";
                    }

                    @Override
                    public com.finapp.payments.ProviderAnswer authorize(
                            AuthorizationRequest request) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public com.finapp.payments.ProviderAnswer capture(CaptureRequest request) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public com.finapp.payments.ProviderAnswer refund(RefundRequest request) {
                        // Another resolver, while this flight's call is out: an ambiguous
                        // answer of its own moves the row into UNKNOWN - through the applier,
                        // counted, as the platform, in its own flow.
                        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                                CorrelationContext.Scope flow =
                                        CorrelationContext.enter(
                                                Correlation.startingWith(
                                                        CorrelationId.generate(IDS)))) {
                            Correlation resolverFlow = PaymentCreation.resolvedCorrelation();
                            RefundId stalled =
                                    RefundId.of(
                                            UUID.fromString(
                                                    oneString(
                                                            "SELECT id::text FROM payments.refund"
                                                                    + " WHERE"
                                                                    + " provider_idempotency_reference"
                                                                    + " = ?",
                                                            request.reference().value())));
                            runner.inTransaction(
                                    uow -> {
                                        com.finapp.payments.Refund row =
                                                refunds.lockForOutcome(uow, stalled)
                                                        .orElseThrow()
                                                        .refund();
                                        return metered.applyRefund(
                                                uow,
                                                captured.intent(),
                                                row,
                                                row.status(),
                                                com.finapp.payments.ProviderAnswer.Verdict
                                                        .INDETERMINATE,
                                                java.util.Optional.empty(),
                                                captured.wallet(),
                                                resolverFlow);
                                    });
                        } catch (SQLException failure) {
                            throw new IllegalStateException(failure);
                        }
                        return com.finapp.payments.ProviderAnswer.indeterminate();
                    }

                    @Override
                    public com.finapp.payments.ProviderAnswer voidAuthorization(
                            VoidRequest request) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public com.finapp.payments.QueryAnswer query(
                            com.finapp.payments.ProviderIdempotencyReference ourReference) {
                        throw new UnsupportedOperationException();
                    }
                };

        PaymentRefund.RefundResult result =
                refundCommand(resolvedWhileStalled, metered)
                        .refund(
                                captured.intent(),
                                Money.ofMinorUnits(5_00, EUR),
                                "stalled flight",
                                "stalled-" + UUID.randomUUID());

        assertThat(result.status()).as("the row's truth, not a judgement of the flight's own")
                .isEqualTo(RefundStatus.UNKNOWN);
        assertThat(refundStatus(result.refund())).isEqualTo("UNKNOWN");
        assertThat(activeHoldCount(captured.wallet())).as("the hold stands").isEqualTo(1);
        assertThat(refundEntryCount(captured.attempt())).isZero();
        assertThat(com.finapp.app.telemetry.RailOutcomeCounts.refund(registry, "unknown"))
                .as("the resolver's judgement, once: the converged flight made none")
                .isEqualTo(1);
        assertThat(
                        com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(
                                registry, SimulatedCardPspAdapter.RAIL.id(), "refund",
                                "unknown"))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM platform.audit_record"
                                        + " WHERE operation = 'payments.PaymentOutcomeApplied'"
                                        + " AND change_summary LIKE ?",
                                "refund=" + result.refund() + ", %"))
                .as("and one outcome record - the same acting exit guards both")
                .isEqualTo(1);
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
                            + " dispatch_key, last_dispatched_at)"
                            + " VALUES (?, ?, 500, 'EUR', 2, ?, ?, ?, 'DISPATCHED', now(), ?,"
                            + " now())",
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
                            // A minute lapsed, not a second - crashedFlight's reason.
                            Duration.ofMinutes(-1));
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
    // The send permit (V009, the Phase 6 -> 7 transition): what a refused connection proves
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a FIRST send whose connection is refused fails the refund and releases its"
            + " hold - the one refused connection that proves nothing was ever transmitted")
    void aFirstSendsRefusedConnectionFailsTheRefund() throws Exception {
        Captured captured = capturedPayment();

        PaymentRefund.RefundResult result =
                refundCommand(unreachable())
                        .refund(
                                captured.intent(),
                                Money.ofMinorUnits(5_00, EUR),
                                "provider down",
                                "first-" + UUID.randomUUID());

        assertThat(result.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(activeHoldCount(captured.wallet())).as("released: nothing left").isZero();
        assertThat(refundEntryCount(captured.attempt())).isZero();
        assertThat(settled(captured.wallet())).isEqualTo("12.00");
    }

    /**
     * The Phase 6 -> 7 transition's CRITICAL finding, found by three independent audits: the
     * crashed flight's send had reached the provider and been paid, and the takeover's re-send
     * met a refused connection. Before V009 the refund committed FAILED, released its hold and
     * posted nothing - the customer refunded at the provider, the wallet never debited, the bound
     * freed for a second refund of the same money.
     */
    @Test
    @DisplayName("a taken-over re-send whose connection is refused proves NOTHING: the refund"
            + " stays UNKNOWN with its hold standing, and the provider's own answer lands one"
            + " entry")
    void aTakeoversRefusedConnectionProvesNothing() throws Exception {
        Captured captured = capturedPayment();
        Money amount = Money.ofMinorUnits(5_00, EUR);
        String reason = "crashed then refused";
        String key = "crash-refused-" + UUID.randomUUID();
        CrashedFlight crashed = crashedFlight(captured, amount, reason, key);

        PaymentRefund.RefundResult retried =
                refundCommand(unreachable()).refund(captured.intent(), amount, reason, key);

        assertThat(retried.status())
                .as("a refused connection on a re-send is not knowledge that the refund failed")
                .isEqualTo(RefundStatus.UNKNOWN);
        assertThat(refundStatus(crashed.refund())).isEqualTo("UNKNOWN");
        assertThat(activeHoldCount(captured.wallet()))
                .as("the hold stands: the customer may already have the money")
                .isEqualTo(1);
        assertThat(refundEntryCount(captured.attempt())).isZero();
        assertThat(settled(captured.wallet())).isEqualTo("12.00");

        // The provider's own statement about the reference the crashed flight stored: it paid.
        deliverWebhook(
                webhookService(),
                "{\"eventId\":\"rfd-evt-" + UUID.randomUUID() + "\",\"operation\":\""
                        + crashed.storedReference()
                        + "\",\"status\":\"approved\",\"reference\":\"psp_rfd-late\"}");

        assertThat(refundStatus(crashed.refund())).isEqualTo("COMPLETED");
        assertThat(activeHoldCount(captured.wallet())).isZero();
        assertThat(refundEntryCount(captured.attempt())).isEqualTo(1);
        assertThat(settled(captured.wallet())).isEqualTo("7.00");
    }

    /**
     * The first-send rule judged against the ROW, not the request - the payout's latent class
     * (its NOTHING_SENT rule compared no permit), closed for the refund by V009's comparison. A
     * first flight stalls on its send; a takeover renews the permit and re-sends, and may be
     * paid; only then does the first flight's refused connection arrive. That refused
     * connection is still about the first flight's own send - which proves nothing about the
     * takeover's. Deterministic: the provider renews the permit, as a takeover would, on its
     * own connection before answering.
     */
    @Test
    @DisplayName("a first send's refused connection proves nothing once a later permit exists -"
            + " the locked row's permit decides, not the request's")
    void aFirstSendsRefusedConnectionAfterARenewalProvesNothing() throws Exception {
        Captured captured = capturedPayment();
        com.finapp.payments.PaymentProvider renewsThenRefuses =
                new com.finapp.payments.PaymentProvider() {
                    @Override
                    public String providerName() {
                        return "renews-then-refuses";
                    }

                    @Override
                    public com.finapp.payments.ProviderAnswer authorize(
                            AuthorizationRequest request) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public com.finapp.payments.ProviderAnswer capture(CaptureRequest request) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public com.finapp.payments.ProviderAnswer refund(RefundRequest request) {
                        // A takeover's permit, committed while this flight stalled.
                        try (Connection other = DatabaseRoles.application()) {
                            execute(
                                    other,
                                    "UPDATE payments.refund SET last_dispatched_at ="
                                            + " last_dispatched_at + interval '1 second'"
                                            + " WHERE provider_idempotency_reference = ?",
                                    request.reference().value());
                        } catch (SQLException failure) {
                            throw new IllegalStateException(failure);
                        }
                        return com.finapp.payments.ProviderAnswer.nothingSent();
                    }

                    @Override
                    public com.finapp.payments.ProviderAnswer voidAuthorization(
                            VoidRequest request) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public com.finapp.payments.QueryAnswer query(
                            com.finapp.payments.ProviderIdempotencyReference ourReference) {
                        throw new UnsupportedOperationException();
                    }
                };

        PaymentRefund.RefundResult result =
                refundCommand(renewsThenRefuses)
                        .refund(
                                captured.intent(),
                                Money.ofMinorUnits(5_00, EUR),
                                "stalled first flight",
                                "stall-" + UUID.randomUUID());

        assertThat(result.status())
                .as("a later permit exists, so this refused connection proves nothing")
                .isEqualTo(RefundStatus.UNKNOWN);
        assertThat(activeHoldCount(captured.wallet())).isEqualTo(1);
        assertThat(refundEntryCount(captured.attempt())).isZero();
    }

    @Test
    @DisplayName("a takeover that finds its refund already finished sends NOTHING and answers the"
            + " row's truth - no second hold, no second row, and the claim completes")
    void aTakeoverFindingAFinishedRefundSendsNothing() throws Exception {
        Captured captured = capturedPayment();
        Money amount = Money.ofMinorUnits(5_00, EUR);
        String reason = "finished by webhook";
        String key = "finished-" + UUID.randomUUID();
        CrashedFlight crashed = crashedFlight(captured, amount, reason, key);
        // The webhook finished the crashed flight's refund before anybody retried.
        deliverWebhook(
                webhookService(),
                "{\"eventId\":\"rfd-evt-" + UUID.randomUUID() + "\",\"operation\":\""
                        + crashed.storedReference()
                        + "\",\"status\":\"approved\",\"reference\":\"psp_rfd-first\"}");
        assertThat(refundStatus(crashed.refund())).isEqualTo("COMPLETED");

        PaymentRefund.RefundResult retried =
                refundCommand().refund(captured.intent(), amount, reason, key);

        assertThat(retried.refund()).isEqualTo(crashed.refund());
        assertThat(retried.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(psp.requestCount(SimulatedCardPspAdapter.REFUNDS_PATH))
                .as("a finished refund is never sent again")
                .isZero();
        assertThat(refundRowCount(captured.attempt())).isEqualTo(1);
        assertThat(holdsEverPlaced(captured.wallet())).isEqualTo(1);
        assertThat(refundEntryCount(captured.attempt())).isEqualTo(1);

        PaymentRefund.RefundResult replay =
                refundCommand().refund(captured.intent(), amount, reason, key);
        assertThat(replay.replayed()).as("the claim completed with the row's truth").isTrue();
        assertThat(replay.status()).isEqualTo(RefundStatus.COMPLETED);
    }

    @Test
    @DisplayName("past the claim's retention a key still carries its refund: the same facts"
            + " converge on it, different facts are refused with nothing written")
    void aKeyCarriesItsRefundPastTheClaim() throws Exception {
        Captured captured = capturedPayment();
        providerRefunds("psp_rfd-bound");
        Money amount = Money.ofMinorUnits(3_00, EUR);
        String key = "bound-" + UUID.randomUUID();
        PaymentRefund.RefundResult first =
                refundCommand().refund(captured.intent(), amount, "the first", key);
        assertThat(first.status()).isEqualTo(RefundStatus.COMPLETED);
        // The claim's retention sweeps it - Phase 15's job, done by hand.
        try (Connection root = DatabaseRoles.bootstrap()) {
            execute(
                    root,
                    "DELETE FROM platform.idempotency_record"
                            + " WHERE scope = 'payment.refund' AND idempotency_key = ?",
                    key);
        }
        int wire = psp.requestCount(SimulatedCardPspAdapter.REFUNDS_PATH);

        assertThatThrownBy(
                        () ->
                                refundCommand()
                                        .refund(
                                                captured.intent(),
                                                Money.ofMinorUnits(4_00, EUR),
                                                "a different refund",
                                                key))
                .isInstanceOf(com.finapp.payments.RefundKeyReusedException.class);
        assertThat(refundRowCount(captured.attempt())).isEqualTo(1);
        assertThat(holdsEverPlaced(captured.wallet())).isEqualTo(1);
        assertThat(psp.requestCount(SimulatedCardPspAdapter.REFUNDS_PATH)).isEqualTo(wire);

        PaymentRefund.RefundResult again =
                refundCommand().refund(captured.intent(), amount, "the first", key);
        assertThat(again.refund()).isEqualTo(first.refund());
        assertThat(again.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(psp.requestCount(SimulatedCardPspAdapter.REFUNDS_PATH)).isEqualTo(wire);
        assertThat(refundRowCount(captured.attempt())).isEqualTo(1);
        assertThat(refundEntryCount(captured.attempt())).isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // The credited account's standing at confirmation (the Phase 6 -> 7 transition's C2)
    // -----------------------------------------------------------------

    /**
     * The creation race's window, the one interleaving account closing's own refusal cannot see:
     * the close committed between the creation's read of the wallet and its commit. Reconstructed
     * by closing the ledger account as the close does. Before the transition, the confirmation
     * dispatched - the provider authorized and captured the card, the ledger refused the capture's
     * posting, and the payment sat CAPTURE_DISPATCHED with the customer charged and nothing booked.
     */
    @Test
    @DisplayName("a confirmation to a wallet closed since the payment was created is refused with"
            + " nothing dispatched - the customer is never charged for a posting the ledger would"
            + " refuse")
    void aConfirmationToAClosedWalletDispatchesNothing() throws Exception {
        Created created = createdPayment();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    // GREATEST: the database's clock can trail the instance's that stamped
                    // created_at, and the status change may not predate the account's birth.
                    "UPDATE ledger.ledger_account SET status = 'CLOSED',"
                            + " status_changed_at = GREATEST(now(), created_at) WHERE id = ?",
                    created.wallet().value());
        }
        psp.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"psp_ra-closed\"}");

        assertThatThrownBy(
                        () ->
                                new PaymentConfirmation(
                runner,
                intents,
                attempts,
                evidence,
                participants,
                adapter(),
                outcomes(),
                new com.finapp.payments.JdbcRoutingStore(),
                com.finapp.payments.PaymentRails.of(java.util.List.of(SimulatedCardPspAdapter.RAIL)),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK,
                com.finapp.payments.RoutingTelemetry.NONE,
                java.util.Optional.empty())
                                        .confirm(created.party(), created.intent()))
                .isInstanceOf(com.finapp.payments.NoWalletForPaymentException.class);
        assertThat(psp.requestCount(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH))
                .as("nothing was sent to the provider")
                .isZero();
        assertThat(
                        oneString(
                                "SELECT status FROM payments.payment_intent WHERE id = ?",
                                created.intent().value()))
                .as("nothing written: still cancellable")
                .isEqualTo("REQUIRES_CONFIRMATION");
        assertThat(
                        count(
                                "SELECT count(*) FROM payments.payment_attempt WHERE intent_id = ?",
                                created.intent().value()))
                .isZero();
    }

    /**
     * The rank beneath account closing's own refusal, raced deterministically: a close that judged
     * before this payment existed holds its FOR UPDATE, mid-transaction, with its move made. The
     * confirmation's FOR SHARE on the same row must wait for it - and then see CLOSED and send
     * nothing. Unlocked, it read the old ACTIVE row and dispatched into a close about to commit.
     */
    @Test
    @DisplayName("a confirmation racing an account's close waits on the share lock and then refuses"
            + " - the close's FOR UPDATE and the confirmation's FOR SHARE serialise")
    void aConfirmationRacingACloseWaitsAndRefuses() throws Exception {
        Created created = createdPayment();
        psp.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"psp_ra-race\"}");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection closer = DatabaseRoles.application()) {
            closer.setAutoCommit(false);
            try (PreparedStatement lock =
                    closer.prepareStatement(
                            "SELECT id FROM ledger.ledger_account WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, created.wallet().value());
                lock.executeQuery().close();
            }
            execute(
                    closer,
                    "UPDATE ledger.ledger_account SET status = 'CLOSED',"
                            + " status_changed_at = GREATEST(now(), created_at) WHERE id = ?",
                    created.wallet().value());

            Future<?> confirming =
                    pool.submit(
                            () -> {
                                try (SecurityContext.Scope actor =
                                                SecurityContext.enter(
                                                        new Actor(
                                                                created.party().toString(),
                                                                ActorType.CUSTOMER));
                                        CorrelationContext.Scope flow =
                                                CorrelationContext.enter(
                                                        Correlation.startingWith(
                                                                CorrelationId.generate(IDS)))) {
                                    new PaymentConfirmation(
                runner,
                intents,
                attempts,
                evidence,
                participants,
                adapter(),
                outcomes(),
                new com.finapp.payments.JdbcRoutingStore(),
                com.finapp.payments.PaymentRails.of(java.util.List.of(SimulatedCardPspAdapter.RAIL)),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK,
                com.finapp.payments.RoutingTelemetry.NONE,
                java.util.Optional.empty())
                                            .confirm(created.party(), created.intent());
                                }
                                return null;
                            });
            awaitShareLockWait();
            closer.commit();

            assertThatThrownBy(() -> confirming.get(60, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(com.finapp.payments.NoWalletForPaymentException.class);
        } finally {
            pool.shutdownNow();
        }
        assertThat(psp.requestCount(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH))
                .as("the confirmation that lost the race sent nothing")
                .isZero();
    }

    /** Until a backend waits on a lock for the confirmation's share-locked read. */
    private static void awaitShareLockWait() throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        try (Connection observer = DatabaseRoles.application();
                PreparedStatement select =
                        observer.prepareStatement(
                                "SELECT count(*) FROM pg_stat_activity"
                                        + " WHERE wait_event_type = 'Lock'"
                                        + " AND query LIKE '%ledger_account%'"
                                        + " AND query LIKE '%FOR SHARE%'")) {
            while (System.nanoTime() < deadline) {
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    if (row.getLong(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(25);
            }
        }
        throw new AssertionError(
                "the confirmation never waited on the account's share lock - without it the race"
                        + " is decided by a stale snapshot, and the payment dispatches into a close");
    }

    // -----------------------------------------------------------------
    // The sweep's refund leg and stranded-chain leg (the Phase 6 -> 7 transition): Phase 5's
    // recorded deferral, carried through Phase 6, paid
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an UNKNOWN refund whose webhook never came is resolved by the sweep's query:"
            + " completed, released and posted once, and nothing is sent again")
    void theSweepResolvesAnUnknownRefundByQuery() throws Exception {
        Captured captured = capturedPayment();
        psp.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.REFUNDS_PATH);
        PaymentRefund.RefundResult result =
                refundCommand()
                        .refund(
                                captured.intent(),
                                Money.ofMinorUnits(5_00, EUR),
                                "answer lost",
                                "swp-" + UUID.randomUUID());
        assertThat(result.status()).isEqualTo(RefundStatus.UNKNOWN);
        queryAnswers(refundReference(result.refund()), "approved", "psp_rfd-q");

        sweeper().sweep();

        assertThat(refundStatus(result.refund())).isEqualTo("COMPLETED");
        assertThat(activeHoldCount(captured.wallet())).isZero();
        assertThat(refundEntryCount(captured.attempt())).isEqualTo(1);
        assertThat(settled(captured.wallet())).isEqualTo("7.00");
        assertThat(psp.requestCount(SimulatedCardPspAdapter.REFUNDS_PATH))
                .as("a query found the answer; nothing was sent again")
                .isEqualTo(1);

        sweeper().sweep();
        assertThat(refundEntryCount(captured.attempt())).as("a resolved refund is left alone")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a refund the provider never saw is RE-DRIVEN with its stored reference under a"
            + " new permit, and completes - never concluded FAILED on the sweep's say-so")
    void theSweepReDrivesARefundTheProviderNeverSaw() throws Exception {
        Captured captured = capturedPayment();
        Money amount = Money.ofMinorUnits(5_00, EUR);
        CrashedFlight crashed =
                crashedFlight(captured, amount, "never arrived", "never-" + UUID.randomUUID());
        java.sql.Timestamp birthPermit = permitOf(crashed.refund());
        psp.succeedsWith(
                SimulatedCardPspAdapter.OPERATIONS_PATH + crashed.storedReference(),
                200,
                "{\"status\":\"unrecognised\"}");
        providerRefunds("psp_rfd-redrive");

        sweeper().sweep();

        assertThat(refundStatus(crashed.refund())).isEqualTo("COMPLETED");
        assertThat(activeHoldCount(captured.wallet())).isZero();
        assertThat(refundEntryCount(captured.attempt())).isEqualTo(1);
        assertThat(psp.requestCount(SimulatedCardPspAdapter.REFUNDS_PATH)).isEqualTo(1);
        assertThat(
                        psp.headerValues(
                                SimulatedCardPspAdapter.REFUNDS_PATH,
                                SimulatedCardPspAdapter.IDEMPOTENCY_KEY_HEADER))
                .as("the re-drive presents the reference stored at dispatch (INV-PAY-04)")
                .containsExactly(crashed.storedReference());
        assertThat(permitOf(crashed.refund()))
                .as("a new permit was committed before the send (V009)")
                .isAfter(birthPermit);
    }

    @Test
    @DisplayName("a refund the provider DECLINED, learned by query, fails with its hold released"
            + " and nothing posted")
    void theSweepFailsARefundTheProviderDeclined() throws Exception {
        Captured captured = capturedPayment();
        psp.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.REFUNDS_PATH);
        PaymentRefund.RefundResult result =
                refundCommand()
                        .refund(
                                captured.intent(),
                                Money.ofMinorUnits(5_00, EUR),
                                "declined late",
                                "swp-" + UUID.randomUUID());
        psp.succeedsWith(
                SimulatedCardPspAdapter.OPERATIONS_PATH + refundReference(result.refund()),
                200,
                "{\"status\":\"declined\"}");

        sweeper().sweep();

        assertThat(refundStatus(result.refund())).isEqualTo("FAILED");
        assertThat(activeHoldCount(captured.wallet())).isZero();
        assertThat(refundEntryCount(captured.attempt())).isZero();
        assertThat(settled(captured.wallet())).isEqualTo("12.00");
    }

    @Test
    @DisplayName("ten concurrent sweeps over one UNKNOWN refund: one completion, one entry, one"
            + " release - counted in the tables")
    void tenConcurrentSweepsResolveOneRefundOnce() throws Exception {
        Captured captured = capturedPayment();
        psp.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.REFUNDS_PATH);
        PaymentRefund.RefundResult result =
                refundCommand()
                        .refund(
                                captured.intent(),
                                Money.ofMinorUnits(5_00, EUR),
                                "ten sweeps",
                                "swp-" + UUID.randomUUID());
        queryAnswers(refundReference(result.refund()), "approved", "psp_rfd-ten");

        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            CountDownLatch open = new CountDownLatch(1);
            List<Future<?>> racers = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                racers.add(
                        pool.submit(
                                () -> {
                                    open.await();
                                    return sweeper().sweep();
                                }));
            }
            open.countDown();
            for (Future<?> racer : racers) {
                racer.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(refundStatus(result.refund())).isEqualTo("COMPLETED");
        assertThat(transitionCount(result.refund(), "COMPLETED")).isEqualTo(1);
        assertThat(refundEntryCount(captured.attempt())).isEqualTo(1);
        assertThat(activeHoldCount(captured.wallet())).isZero();
        assertThat(refundEventCount(result.refund(), "payments.RefundCompleted")).isEqualTo(1);
        assertThat(settled(captured.wallet())).isEqualTo("7.00");
    }

    @Test
    @DisplayName("an authorization nothing captured is captured by the sweep past its bound: one"
            + " wire capture, one entry, and the payment SUCCEEDED")
    void theSweepChainsAStrandedAuthorization() throws Exception {
        Captured authorized = capturedPayment(false);
        assertThat(attemptStatus(authorized.attempt())).isEqualTo("AUTHORIZED");
        psp.succeedsWith(
                SimulatedCardPspAdapter.CAPTURES_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"psp_cap-stranded\"}");

        sweeper().sweep();
        sweeper().sweep();

        assertThat(attemptStatus(authorized.attempt())).isEqualTo("CAPTURED");
        // Row-scoped: the shared database carries other suites' stranded authorizations, which
        // the sweep rightly chains too - the count of record is THIS attempt's capture reference.
        String captureReference =
                oneString(
                        "SELECT capture_reference FROM payments.payment_attempt WHERE id = ?",
                        authorized.attempt().value());
        assertThat(
                        psp.headerValues(
                                SimulatedCardPspAdapter.CAPTURES_PATH,
                                SimulatedCardPspAdapter.IDEMPOTENCY_KEY_HEADER))
                .as("one capture of this attempt on the wire, however many sweeps")
                .containsOnlyOnce(captureReference);
        assertThat(
                        count(
                                "SELECT count(*) FROM ledger.journal_entry"
                                        + " WHERE reference = ?",
                                authorized.attempt().value().toString()))
                .isEqualTo(1);
        assertThat(settled(authorized.wallet())).isEqualTo("12.00");
    }

    @Test
    @DisplayName("a refund stuck DISPATCHED past the sweep's bound is counted by the stuck gauge;"
            + " inside the bound it is mid-question and is not")
    void aStuckDispatchedRefundIsCountedPastTheBound() throws Exception {
        Captured captured = capturedPayment();
        try (Connection app = DatabaseRoles.application()) {
            com.finapp.payments.PaymentAttemptStore.UnknownReading before =
                    refunds.unknownReading(app, Duration.ZERO);
            com.finapp.payments.PaymentAttemptStore.UnknownReading beforeLong =
                    refunds.unknownReading(app, Duration.ofHours(1));
            crashedFlight(
                    captured, Money.ofMinorUnits(2_00, EUR), "stuck", "stuck-" + UUID.randomUUID());
            assertThat(refunds.unknownReading(app, Duration.ZERO).active() - before.active())
                    .as("a crashed dispatch is stuck once the sweep would have asked")
                    .isEqualTo(1);
            assertThat(
                            refunds.unknownReading(app, Duration.ofHours(1)).active()
                                    - beforeLong.active())
                    .as("and not before - a young dispatch is mid-question")
                    .isZero();
        }
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    /** The sweep over this suite's provider, with the least bounds it accepts. */
    private com.finapp.payments.PaymentSweeper sweeper() {
        Duration dueNow = Duration.ofNanos(1_000);
        return new com.finapp.payments.PaymentSweeper(
                runner,
                attempts,
                intents,
                refunds,
                evidence,
                adapter(),
                outcomes(),
                new PaymentCapture(
                        runner, intents, attempts, evidence, adapter(), outcomes(),
                        voids(), new JdbcAuditWriter(), IDS, CLOCK),
                voids(),
                IDS,
                CLOCK,
                dueNow,
                dueNow,
                50);
    }

    /** The void command over the same stores (P7-TSK-004) - the capture idiom's sibling. */
    private com.finapp.payments.PaymentVoid voids() {
        return new com.finapp.payments.PaymentVoid(
                runner, intents, attempts, evidence, adapter(), outcomes(),
                com.finapp.payments.PaymentRails.of(
                        java.util.List.of(SimulatedCardPspAdapter.RAIL)),
                new JdbcAuditWriter(), IDS, CLOCK);
    }

    private static void queryAnswers(String reference, String status, String pspReference) {
        psp.succeedsWith(
                SimulatedCardPspAdapter.OPERATIONS_PATH + reference,
                200,
                "{\"status\":\"" + status + "\",\"reference\":\"" + pspReference + "\"}");
    }

    private static java.sql.Timestamp permitOf(RefundId refund) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT last_dispatched_at FROM payments.refund WHERE id = ?")) {
            read.setObject(1, refund.value());
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getTimestamp(1);
            }
        }
    }

    private static String attemptStatus(PaymentAttemptId attempt) throws SQLException {
        return oneString(
                "SELECT status FROM payments.payment_attempt WHERE id = ?", attempt.value());
    }

    /** A crashed flight's Tx1, as it committed: its refund, and the reference it stored. */
    private record CrashedFlight(RefundId refund, String storedReference) {}

    /**
     * The crashed flight's Tx1, reconstructed as it commits (the takeover test's idiom): the
     * hold, the refund row carrying its dispatch key and birth permit (V008, V009), and the claim
     * IN_PROGRESS whose lease has already expired (the database's own clock judges expiry).
     */
    private CrashedFlight crashedFlight(Captured captured, Money amount, String reason, String key)
            throws Exception {
        com.finapp.platform.security.Actor actor = SecurityContext.require();
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
                            + " dispatch_key, last_dispatched_at)"
                            // A minute old on the SERVER clock: the sweep's bound is the
                            // JVM's, and a permit born "now()" can hide behind host/VM
                            // skew under the microsecond test bound (ADR-0057 section 4's
                            // premise, at the fixture rank). A stranded flight is old.
                            + " VALUES (?, ?, ?, 'EUR', 2, ?, ?, ?, 'DISPATCHED',"
                            + " now() - interval '1 minute', ?,"
                            + " now() - interval '1 minute')",
                    refundId,
                    captured.attempt().value(),
                    amount.minorUnits(),
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
                                                    + captured.intent().value() + "|"
                                                    + amount.minorUnits() + "|EUR|2|" + reason)
                                            .getBytes(StandardCharsets.UTF_8)),
                            CorrelationId.of("crashed-flow"),
                            Instant.now(CLOCK),
                            Instant.now(CLOCK).plus(Duration.ofDays(1)),
                            // Lapsed a minute ago on the SERVER clock, not one second: the
                            // takeover is judged by the same clock, and the Docker VM's steps back
                            // ~1.6 s every ~27 s (measured at P7-TSK-015's gate, whose battery met
                            // a one-second lease read as still held). A stranded flight is old.
                            Duration.ofMinutes(-1));
            other.commit();
        }
        return new CrashedFlight(RefundId.of(refundId), storedReference);
    }

    /** A provider whose connection is refused: a port bound and released, so nothing listens. */
    private static SimulatedCardPspAdapter unreachable() throws Exception {
        int closed;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        return new SimulatedCardPspAdapter(
                URI.create("http://127.0.0.1:" + closed), Duration.ofSeconds(2), PSP_KEY);
    }

    private record Captured(
            PaymentIntentId intent, PaymentAttemptId attempt, LedgerAccountId wallet) {}

    private Captured capturedPayment() throws Exception {
        return capturedPayment(true);
    }

    /** A payment created and not yet confirmed: its payer, its intent and the wallet it credits. */
    private record Created(UUID party, PaymentIntentId intent, LedgerAccountId wallet) {}

    /** The full real chain to a CAPTURED (or merely AUTHORIZED) attempt with money moved. */
    private Captured capturedPayment(boolean capture) throws Exception {
        Created created = createdPayment();
        UUID party = created.party();
        psp.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"psp_ra-" + UUID.randomUUID() + "\"}");
        new PaymentConfirmation(
                runner,
                intents,
                attempts,
                evidence,
                participants,
                adapter(),
                outcomes(),
                new com.finapp.payments.JdbcRoutingStore(),
                com.finapp.payments.PaymentRails.of(java.util.List.of(SimulatedCardPspAdapter.RAIL)),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK,
                com.finapp.payments.RoutingTelemetry.NONE,
                java.util.Optional.empty())
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
                            voids(), new JdbcAuditWriter(), IDS, CLOCK)
                    .capture(attemptId);
        }
        psp.reset();
        return new Captured(created.intent(), attemptId, created.wallet());
    }

    /** The real chain up to a created payment: a verified payer, an open wallet, an intent. */
    private Created createdPayment() throws Exception {
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
                    "INSERT INTO paymentmethods.payment_method (id, party_id, kind, token_reference,"
                            + " brand, display_suffix, expiry_month, expiry_year, status,"
                            + " created_at) VALUES (?, ?, 'CARD_TOKEN', ?, 'Visa', '4242', 12, 2030,"
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
                                                CLOCK,
                                                PaymentCreation.IDEMPOTENCY_SCOPE)
                                        .create(
                                                uow,
                                                new PaymentCreation.CreatePaymentCommand(
                                                        party,
                                                        method,
                                                        CAPTURED,
                                                        "rfd-" + UUID.randomUUID())));
        LedgerAccountId wallet =
                runner.inTransaction(
                        uow ->
                                intents.findById(uow, created.intent())
                                        .orElseThrow()
                                        .creditAccount());
        return new Created(party, created.intent(), wallet);
    }

    private PaymentRefund refundCommand() {
        return refundCommand(adapter());
    }

    private PaymentRefund refundCommand(com.finapp.payments.PaymentProvider provider) {
        return refundCommand(provider, outcomes());
    }

    private PaymentRefund refundCommand(
            com.finapp.payments.PaymentProvider provider, PaymentOutcomes outcomes) {
        return new PaymentRefund(
                runner,
                executor(),
                intents,
                attempts,
                refunds,
                evidence,
                holdService(),
                provider,
                outcomes,
                new JdbcAuditWriter(),
                IDS,
                CLOCK,
                com.finapp.payments.PaymentRails.of(java.util.List.of(SimulatedCardPspAdapter.RAIL)),
                // A card-only suite: the push rail is absent, as on a card-only deployment.
                java.util.Optional.empty());
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
        return outcomes(com.finapp.payments.RailOutcomeObserver.NONE);
    }

    /** The applier reporting to {@code observer} - the webhook door's counting seam since
     * P7-TSK-015, so a suite reading its own registry reads what the applier committed. */
    private PaymentOutcomes outcomes(com.finapp.payments.RailOutcomeObserver observer) {
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
                        new com.finapp.payments.WalletTopUpComposition(),
                        // No completion: these suites' payments belong to no checkout
                        // session, and the production consumer is wired in CheckoutBeans.
                        landed -> {},
                        new com.finapp.app.telemetry.MerchantMeters(
                                new io.micrometer.core.instrument.simple.SimpleMeterRegistry())),
                // THE REFUND'S MIRROR SEAM (P6-TSK-014), production's own for the same
                // reason: every refund in this suite falls back to Phase 5's two lines, and
                // proving that through the real composition is what makes "byte-identical"
                // a claim about production rather than about a double.
                new com.finapp.app.merchant.MerchantBoundRefundComposition(
                        new com.finapp.merchant.MerchantSettlement(
                                new com.finapp.merchant.JdbcPaymentFeePinStore(),
                                new com.finapp.merchant.JdbcFeeScheduleStore(),
                                new com.finapp.ledger.JdbcLedgerAccountStore(),
                                new com.finapp.ledger.ChartOfAccounts<>(new com.finapp.ledger.JdbcLedgerAccountStore()),
                                new JdbcOutboxWriter(),
                                IDS),
                        new com.finapp.payments.WalletRefundComposition()),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK,
                com.finapp.payments.PaymentRails.of(java.util.List.of(SimulatedCardPspAdapter.RAIL)),
                new com.finapp.payments.JdbcUnmatchedConfirmationStore(),
                new com.finapp.ledger.JdbcLedgerAccountStore(),
                // The dispute money (P7-TSK-013), production-shaped: a failed refund here
                // locks its card attempt first and finds no chargeback.
                com.finapp.app.payments.ChargebackAccountingFixture.over(
                        postingService(),
                        com.finapp.payments.PaymentRails.of(java.util.List.of(SimulatedCardPspAdapter.RAIL)),
                        IDS,
                        CLOCK),
                observer);
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
                // The judgement counted where it is written, into THIS suite's meters,
                // after the delivery's commit (P7-TSK-015) - no longer by the door.
                outcomes(new com.finapp.app.telemetry.CommittedRailOutcomes(paymentMeters)),
                new com.finapp.payments.PaymentClearing(
                        new com.finapp.payments.JdbcClearingRecordStore(),
                        new com.finapp.platform.outbox.JdbcOutboxWriter(),
                        IDS,
                        CLOCK),
                new com.finapp.platform.inbox.InboxConsumer<>(
                        new com.finapp.platform.inbox.JdbcInboxRecordStore(),
                        CLOCK,
                        Duration.ofDays(14)),
                new tools.jackson.databind.ObjectMapper(),
                CLOCK,
                template,
                dataSource,
                new com.finapp.payments.DisputeNotifications(
                        new com.finapp.payments.JdbcDisputeStore(),
                        intents,
                        new JdbcAuditWriter(),
                        new com.finapp.platform.outbox.JdbcOutboxWriter(),
                        IDS,
                        CLOCK,
                        // P7-TSK-013: the attempt lock first, and the dispute money.
                        new com.finapp.payments.JdbcPaymentAttemptStore(),
                        com.finapp.app.payments.ChargebackAccountingFixture.over(
                                postingService(),
                                com.finapp.payments.PaymentRails.of(
                                        java.util.List.of(SimulatedCardPspAdapter.RAIL)),
                                IDS,
                                CLOCK)));
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
