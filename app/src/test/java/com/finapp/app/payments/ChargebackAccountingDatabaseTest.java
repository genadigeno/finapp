package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounts.AccountClosing;
import com.finapp.accounts.AccountNotEmptyException;
import com.finapp.accounts.AccountOpening;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcNegativePositions;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentRefund;
import com.finapp.payments.RefundExceedsCaptureException;
import com.finapp.payments.RefundStatus;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.WebhookSignature;
import com.finapp.platform.correlation.CorrelationContext;
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
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Chargeback accounting over the real chain (`P7-TSK-013`, ADR-0061 §3–§5): the signed card
 * door, the production dispute notifications and accounting, the production refund command, and
 * the ledger — {@code INV-DSP-01}'s combined bound and {@code INV-DSP-02}'s once-per-stage,
 * each asserted in the journal's own lines by {@code DIRECTION:PURPOSE:minor}.
 *
 * <p>Each disputed payment is a captured card payment crediting a REAL counterparty account,
 * seeded raw with its capture's posting ({@code payment-capture:<attempt>}: DR the card rail's
 * clearing / CR the counterparty) — so the counterparty holds exactly what the capture credited
 * it, and every assertion about "never debited more than it was credited" is about real
 * balances. The Phase 7 gate's two dispute criteria are {@link #aChargebackOnAFullyRefundedPaymentNeverDebitsTheCounterpartyTwice}
 * and {@link #tenFreshIdChargebacksPostOnce}.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("chargeback accounting and the combined bound (P7-TSK-013)")
class ChargebackAccountingDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final byte[] WEBHOOK_KEY =
            "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final String WEBHOOK_METER = "finapp.payments.webhook";

    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private PostingService postingService;
    @Autowired private PaymentRefund paymentRefund;
    @Autowired private AccountOpening accountOpening;
    @Autowired private AccountClosing accountClosing;
    @Autowired private MeterRegistry registry;

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
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add(
                "finapp.payments.webhook.key",
                () -> Base64.getEncoder().encodeToString(WEBHOOK_KEY));
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    // -----------------------------------------------------------------
    // The chargeback's split (INV-DSP-01)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a chargeback on an unrefunded payment: the clearing credited by exactly what the"
            + " network took, the counterparty charged all of it out of the recoverable, the"
            + " recoverable left at zero - two entries, the external fact first")
    void aChargebackOnAnUnrefundedPaymentChargesTheCounterparty() throws Exception {
        Payment payment = captured(wallet());
        String reference = someDisputeReference();

        assertThat(deliver(chargeback(payment, reference, "needs_response", 1000, null))
                        .statusCode())
                .isEqualTo(204);

        UUID dispute = disputeId(reference);
        assertThat(split(dispute)).as("share, parked, excess").isEqualTo(new long[] {1000, 0, 0});
        assertThat(lines(dispute))
                .containsExactly(
                        "dispute-chargeback|DEBIT:CHARGEBACK_RECOVERABLE:1000",
                        "dispute-chargeback|CREDIT:SETTLEMENT_CLEARING:1000",
                        "dispute-attribution|DEBIT:CUSTOMER_WALLET:1000",
                        "dispute-attribution|CREDIT:CHARGEBACK_RECOVERABLE:1000");
        assertThat(balance(payment.credit()))
                .as("credited 10.00 by the capture, charged 10.00 back: exactly zero")
                .isZero();
        assertThat(eventPayload(dispute, "payments.ChargebackReceived"))
                .as("the stage fact names the split's accounts - never an amount")
                .contains("\"counterpartyAccountId\":\"" + payment.credit().value() + "\"")
                .doesNotContain("recoverableAccountId")
                .doesNotContain("amount")
                .doesNotContain("10.00");
    }

    @Test
    @DisplayName("THE GATE'S CRITERION: a chargeback on an already FULLY refunded payment does not"
            + " double-debit - the counterparty is not touched, the whole chargeback rests in"
            + " CHARGEBACK_RECOVERABLE (ADR-0061's risk 2, closed by arithmetic)")
    void aChargebackOnAFullyRefundedPaymentNeverDebitsTheCounterpartyTwice() throws Exception {
        Payment payment = captured(wallet());
        assertThat(refund(payment, 1000, "psp_rf-" + IDS.next()).status())
                .isEqualTo(RefundStatus.COMPLETED);
        assertThat(balance(payment.credit())).as("refunded in full").isZero();
        String reference = someDisputeReference();

        assertThat(deliver(chargeback(payment, reference, "needs_response", 1000, null))
                        .statusCode())
                .isEqualTo(204);

        UUID dispute = disputeId(reference);
        assertThat(split(dispute)).as("share, parked, excess").isEqualTo(new long[] {0, 0, 1000});
        assertThat(lines(dispute))
                .as("the external fact alone: no attribution entry, because nothing remains")
                .containsExactly(
                        "dispute-chargeback|DEBIT:CHARGEBACK_RECOVERABLE:1000",
                        "dispute-chargeback|CREDIT:SETTLEMENT_CLEARING:1000");
        assertThat(balance(payment.credit()))
                .as("the merchant or customer who refunded properly pays ONCE")
                .isZero();
        assertThat(eventPayload(dispute, "payments.ChargebackReceived"))
                .doesNotContain("counterpartyAccountId")
                .contains("recoverableAccountId");
    }

    @Test
    @DisplayName("a chargeback on a PARTIALLY refunded payment charges the counterparty only what"
            + " the capture left it - never below zero on this payment's account")
    void aChargebackOnAPartiallyRefundedPaymentChargesOnlyWhatRemains() throws Exception {
        Payment payment = captured(wallet());
        refund(payment, 300, "psp_rf-" + IDS.next());
        String reference = someDisputeReference();

        deliver(chargeback(payment, reference, "needs_response", 1000, null));

        UUID dispute = disputeId(reference);
        assertThat(split(dispute)).isEqualTo(new long[] {700, 0, 300});
        assertThat(lines(dispute))
                .containsExactly(
                        "dispute-chargeback|DEBIT:CHARGEBACK_RECOVERABLE:1000",
                        "dispute-chargeback|CREDIT:SETTLEMENT_CLEARING:1000",
                        "dispute-attribution|DEBIT:CUSTOMER_WALLET:700",
                        "dispute-attribution|CREDIT:CHARGEBACK_RECOVERABLE:700");
        assertThat(balance(payment.credit()))
                .as("10.00 credited, 3.00 refunded, 7.00 charged back: zero, not -3.00")
                .isZero();
    }

    @Test
    @DisplayName("ADR-0061's risk 3: a refund after a chargeback has taken the value back is"
            + " refused past what remains - the honest 422 with nothing written - and allowed up"
            + " to it")
    void aRefundAfterAChargebackIsRefusedPastWhatRemains() throws Exception {
        Payment payment = captured(wallet());
        deliver(chargeback(payment, someDisputeReference(), "needs_response", 600, null));
        long refundsBefore = count("SELECT count(*) FROM payments.refund WHERE attempt_id = ?",
                payment.attempt());
        long holdsBefore = count("SELECT count(*) FROM ledger.hold WHERE ledger_account_id = ?",
                payment.credit().value());

        assertThatThrownBy(() -> refund(payment, 401, "psp_rf-" + IDS.next()))
                .isInstanceOf(RefundExceedsCaptureException.class);
        assertThat(count("SELECT count(*) FROM payments.refund WHERE attempt_id = ?",
                        payment.attempt()))
                .as("nothing written").isEqualTo(refundsBefore);
        assertThat(count("SELECT count(*) FROM ledger.hold WHERE ledger_account_id = ?",
                        payment.credit().value()))
                .as("nothing reserved").isEqualTo(holdsBefore);

        assertThat(refund(payment, 400, "psp_rf-" + IDS.next()).status())
                .as("exactly what the capture left the counterparty")
                .isEqualTo(RefundStatus.COMPLETED);
        assertThat(balance(payment.credit())).isZero();
    }

    @Test
    @DisplayName("refunds RACING a chargeback on one payment serialise on the attempt's lock: the"
            + " counted refunds and the attributed share sum to exactly the capture, the"
            + " counterparty ends at zero, never below - counted in the tables")
    void refundsRacingAChargebackKeepTheBound() throws Exception {
        Payment payment = captured(wallet());
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.REFUNDS_PATH, "psp_rr");
        String reference = someDisputeReference();
        CountDownLatch open = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(9);
        try {
            List<Future<?>> racers = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                racers.add(pool.submit((Callable<Void>) () -> {
                    open.await();
                    try {
                        refund(payment, 200, null);
                    } catch (RuntimeException refused) {
                        // The bound or the funding refused this one - the tables decide below.
                    }
                    return null;
                }));
            }
            racers.add(pool.submit((Callable<Void>) () -> {
                open.await();
                assertThat(deliver(chargeback(payment, reference, "needs_response", 1000, null))
                                .statusCode())
                        .isEqualTo(204);
                return null;
            }));
            open.countDown();
            for (Future<?> racer : racers) {
                racer.get();
            }
        } finally {
            pool.shutdownNow();
        }

        long refunded =
                count("SELECT coalesce(sum(amount_minor), 0) FROM payments.refund"
                        + " WHERE attempt_id = ? AND status <> 'FAILED'", payment.attempt());
        long[] split = split(disputeId(reference));
        assertThat(refunded + split[0] + split[1])
                .as("INV-DSP-01: counted refunds + attributed share == captured, exactly")
                .isEqualTo(1000);
        assertThat(split[2]).as("the excess is exactly what the refunds had already returned")
                .isEqualTo(refunded);
        assertThat(balance(payment.credit())).as("never below what it was credited").isZero();
    }

    /**
     * `P7-TST-001`'s find, made deterministic enough to fail without its fix. A win posts its
     * external fact first — the clearing's and the recoverable's balance rows — and only then
     * restores the counterparty's share; a refund of ANOTHER payment to the same counterparty
     * holds that account {@code FOR UPDATE} (its hold's release) and then posts to the clearing.
     * Opposite orders over the same two rows: the multi-rail storm met it as a 40P01 and a 500
     * at the card door. The win now share-locks the counterparty before its first posting.
     */
    @Test
    @DisplayName("a WIN restoring its share races refunds of the same counterparty's other payments"
            + " and every round completes - the counterparty share-locked before the win's first"
            + " posting, the order every hold keeps (a 40P01 in the multi-rail storm)")
    void aWinRacingRefundsOfTheSameCounterpartyNeverDeadlocks() throws Exception {
        LedgerAccountId wallet = wallet();
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.REFUNDS_PATH, "psp_wr");
        int rounds = 8;
        for (int round = 0; round < rounds; round++) {
            int thisRound = round;
            Payment disputed = captured(wallet);
            String reference = someDisputeReference();
            assertThat(deliver(chargeback(disputed, reference, "needs_response", 1000, null))
                            .statusCode())
                    .isEqualTo(204);
            List<Payment> refundable =
                    List.of(captured(wallet), captured(wallet), captured(wallet));
            CountDownLatch open = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> racers = new ArrayList<>();
                racers.add(pool.submit((Callable<Void>) () -> {
                    open.await();
                    HttpResponse<String> won =
                            deliver(chargeback(disputed, reference, "won", 1000, null));
                    assertThat(won.statusCode())
                            .as("round %s: the win applies - a deadlock is a 500 (%s)",
                                    thisRound, won.body())
                            .isEqualTo(204);
                    return null;
                }));
                for (Payment payment : refundable) {
                    racers.add(pool.submit((Callable<Void>) () -> {
                        open.await();
                        assertThat(refund(payment, 1000, null).status())
                                .as("round %s: the refund completes", thisRound)
                                .isEqualTo(RefundStatus.COMPLETED);
                        return null;
                    }));
                }
                open.countDown();
                for (Future<?> racer : racers) {
                    racer.get();
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(split(disputeId(reference))[0])
                    .as("round %s: the share was the whole chargeback, and the win restored it",
                            thisRound)
                    .isEqualTo(1000);
        }
        // Every round explained: the disputed payment kept (charged back and won back), each
        // refunded payment returned in full.
        assertThat(balance(wallet)).isEqualTo(rounds * 1000L);
    }

    // -----------------------------------------------------------------
    // Re-attribution (ADR-0061 section 3's last rule)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a counted refund that later FAILS gives its share of the excess back to the"
            + " counterparty, under the attempt lock, in the failure's own transaction - one"
            + " re-attribution entry, one record")
    void aCountedRefundThatFailsGivesItsShareBack() throws Exception {
        Payment payment = captured(wallet());
        provider.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.REFUNDS_PATH);
        PaymentRefund.RefundResult inFlight = refund(payment, 400, null);
        assertThat(inFlight.status()).isEqualTo(RefundStatus.UNKNOWN);
        String reference = someDisputeReference();
        deliver(chargeback(payment, reference, "needs_response", 1000, null));
        UUID dispute = disputeId(reference);
        assertThat(split(dispute))
                .as("the in-flight refund counted: 4.00 of the chargeback is excess")
                .isEqualTo(new long[] {600, 0, 400});

        assertThat(deliver(refundStatement(inFlight, "declined")).statusCode()).isEqualTo(204);

        assertThat(refundStatus(inFlight)).isEqualTo("FAILED");
        assertThat(split(dispute))
                .as("the money the refund was counted as returning never went back")
                .isEqualTo(new long[] {1000, 0, 0});
        assertThat(lines(dispute))
                .containsExactly(
                        "dispute-chargeback|DEBIT:CHARGEBACK_RECOVERABLE:1000",
                        "dispute-chargeback|CREDIT:SETTLEMENT_CLEARING:1000",
                        "dispute-attribution|DEBIT:CUSTOMER_WALLET:600",
                        "dispute-attribution|CREDIT:CHARGEBACK_RECOVERABLE:600",
                        "dispute-reattribution|DEBIT:CUSTOMER_WALLET:400",
                        "dispute-reattribution|CREDIT:CHARGEBACK_RECOVERABLE:400");
        assertThat(balance(payment.credit()))
                .as("the refund's hold released, the full chargeback the counterparty's: zero")
                .isZero();
        assertThat(auditSummaries(dispute, "payments.ChargebackReattributed"))
                .singleElement()
                .asString()
                .contains("cause=refund:" + inFlight.refund().value())
                .contains("to=COUNTERPARTY");

        // A win then reverses exactly what stands - the re-attribution included (INV-DSP-02).
        deliver(chargeback(payment, reference, "under_review", 1000, null));
        deliver(chargeback(payment, reference, "won", 1000, null));
        assertThat(nets(dispute).values()).as("every account nets to zero").containsOnly(0L);
        assertThat(balance(payment.credit())).as("the capture's 10.00, whole again")
                .isEqualTo(1000);
    }

    @Test
    @DisplayName("a counted refund failing AFTER a loss wrote the excess off recovers the cost:"
            + " DR the counterparty / CR DISPUTE_COSTS")
    void aCountedRefundFailingAfterALossRecoversTheCost() throws Exception {
        Payment payment = captured(wallet());
        provider.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.REFUNDS_PATH);
        PaymentRefund.RefundResult inFlight = refund(payment, 400, null);
        String reference = someDisputeReference();
        deliver(chargeback(payment, reference, "needs_response", 1000, null));
        deliver(chargeback(payment, reference, "lost", 1000, null));
        UUID dispute = disputeId(reference);

        deliver(refundStatement(inFlight, "declined"));

        assertThat(lines(dispute))
                .containsExactly(
                        "dispute-chargeback|DEBIT:CHARGEBACK_RECOVERABLE:1000",
                        "dispute-chargeback|CREDIT:SETTLEMENT_CLEARING:1000",
                        "dispute-attribution|DEBIT:CUSTOMER_WALLET:600",
                        "dispute-attribution|CREDIT:CHARGEBACK_RECOVERABLE:600",
                        "dispute-loss|DEBIT:DISPUTE_COSTS:400",
                        "dispute-loss|CREDIT:CHARGEBACK_RECOVERABLE:400",
                        "dispute-reattribution|DEBIT:CUSTOMER_WALLET:400",
                        "dispute-reattribution|CREDIT:DISPUTE_COSTS:400");
        assertThat(nets(dispute))
                .as("the recoverable empty, the cost recovered, the counterparty bearing it all")
                .containsEntry("CHARGEBACK_RECOVERABLE", 0L)
                .containsEntry("DISPUTE_COSTS", 0L)
                .containsEntry("CUSTOMER_WALLET", 1000L);
    }

    @Test
    @DisplayName("a WIN frees what its chargeback attributed: a sibling chargeback whose split"
            + " counted it - a second cycle's statement delivered before the first's win - gets"
            + " its excess back on the counterparty, the won dispute named as the cause")
    void aWinGivesASiblingsExcessBack() throws Exception {
        Payment payment = captured(wallet());
        String first = someDisputeReference();
        deliver(chargeback(payment, first, "needs_response", 1000, null));
        String second = someDisputeReference();
        deliver(chargeback(payment, second, "needs_response", 500, null));
        UUID secondCycle = disputeId(second);
        assertThat(split(secondCycle))
                .as("the first chargeback took all the capture credited: all excess")
                .isEqualTo(new long[] {0, 0, 500});

        deliver(chargeback(payment, first, "under_review", 1000, null));
        deliver(chargeback(payment, first, "won", 1000, null));

        assertThat(split(secondCycle)).isEqualTo(new long[] {500, 0, 0});
        assertThat(lines(secondCycle))
                .containsExactly(
                        "dispute-chargeback|DEBIT:CHARGEBACK_RECOVERABLE:500",
                        "dispute-chargeback|CREDIT:SETTLEMENT_CLEARING:500",
                        "dispute-reattribution|DEBIT:CUSTOMER_WALLET:500",
                        "dispute-reattribution|CREDIT:CHARGEBACK_RECOVERABLE:500");
        assertThat(balance(payment.credit()))
                .as("10.00 credited, the won chargeback returned, the standing 5.00 borne")
                .isEqualTo(500);
        assertThat(auditSummaries(secondCycle, "payments.ChargebackReattributed"))
                .singleElement()
                .asString()
                .contains("cause=dispute:" + disputeId(first));
    }

    // -----------------------------------------------------------------
    // Resolution (INV-DSP-02)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("INV-DSP-02: a win is the chargeback's EXACT inverse - every account the dispute"
            + " touched nets to zero, the clearing restored, the counterparty's share returned")
    void aWinNetsTheChargebackToZeroPerAccount() throws Exception {
        Payment payment = captured(wallet());
        refund(payment, 300, "psp_rf-" + IDS.next());
        String reference = someDisputeReference();
        deliver(chargeback(payment, reference, "needs_response", 1000, null));
        deliver(chargeback(payment, reference, "under_review", 1000, null));
        deliver(chargeback(payment, reference, "won", 1000, null));

        UUID dispute = disputeId(reference);
        assertThat(lines(dispute))
                .containsExactly(
                        "dispute-chargeback|DEBIT:CHARGEBACK_RECOVERABLE:1000",
                        "dispute-chargeback|CREDIT:SETTLEMENT_CLEARING:1000",
                        "dispute-attribution|DEBIT:CUSTOMER_WALLET:700",
                        "dispute-attribution|CREDIT:CHARGEBACK_RECOVERABLE:700",
                        "dispute-won|DEBIT:SETTLEMENT_CLEARING:1000",
                        "dispute-won|CREDIT:CHARGEBACK_RECOVERABLE:1000",
                        "dispute-restoration|DEBIT:CHARGEBACK_RECOVERABLE:700",
                        "dispute-restoration|CREDIT:CUSTOMER_WALLET:700");
        assertThat(nets(dispute).values()).as("zero on every account").containsOnly(0L);
        assertThat(balance(payment.credit())).as("10.00 - 3.00 refunded").isEqualTo(700);
    }

    @Test
    @DisplayName("a loss writes off ONLY the excess to DISPUTE_COSTS: the counterparty's share"
            + " stands as its debt, the recoverable is emptied")
    void aLossWritesOffOnlyTheExcess() throws Exception {
        Payment payment = captured(wallet());
        refund(payment, 300, "psp_rf-" + IDS.next());
        String reference = someDisputeReference();
        deliver(chargeback(payment, reference, "needs_response", 1000, null));
        deliver(chargeback(payment, reference, "lost", 1000, null));

        UUID dispute = disputeId(reference);
        assertThat(lines(dispute))
                .endsWith(
                        "dispute-loss|DEBIT:DISPUTE_COSTS:300",
                        "dispute-loss|CREDIT:CHARGEBACK_RECOVERABLE:300");
        assertThat(nets(dispute))
                .containsEntry("CHARGEBACK_RECOVERABLE", 0L)
                .containsEntry("DISPUTE_COSTS", 300L)
                .containsEntry("CUSTOMER_WALLET", 700L)
                .containsEntry("SETTLEMENT_CLEARING", -1000L);
    }

    @Test
    @DisplayName("the PSP's dispute fee posts ONCE as a platform cost against the clearing; the"
            + " same fee again is nothing, another fee is a loud contradiction, and a fee first"
            + " reported late is recorded then")
    void theDisputeFeePostsOnceAndIsGuarded() throws Exception {
        Payment payment = captured(wallet());
        String reference = someDisputeReference();
        deliver(chargeback(payment, reference, "needs_response", 1000, 1500L));
        UUID dispute = disputeId(reference);
        deliver(chargeback(payment, reference, "under_review", 1000, 1500L));
        double unmappable = webhooks("unmappable");
        deliver(chargeback(payment, reference, "won", 1000, 1600L));

        assertThat(webhooks("unmappable")).as("a second story about money")
                .isEqualTo(unmappable + 1);
        assertThat(stage(dispute)).isEqualTo("REPRESENTED");
        assertThat(lines(dispute))
                .containsExactly(
                        "dispute-chargeback|DEBIT:CHARGEBACK_RECOVERABLE:1000",
                        "dispute-chargeback|CREDIT:SETTLEMENT_CLEARING:1000",
                        "dispute-attribution|DEBIT:CUSTOMER_WALLET:1000",
                        "dispute-attribution|CREDIT:CHARGEBACK_RECOVERABLE:1000",
                        "dispute-fee|DEBIT:DISPUTE_COSTS:1500",
                        "dispute-fee|CREDIT:SETTLEMENT_CLEARING:1500");
        assertThat(audits(dispute, "payments.DisputeFeeRecorded")).isEqualTo(1);

        // A dispute charged back WITHOUT a fee, and the fee reported on a later statement.
        Payment late = captured(wallet());
        String later = someDisputeReference();
        deliver(chargeback(late, later, "needs_response", 1000, null));
        UUID lateDispute = disputeId(later);
        assertThat(deliver(chargeback(late, later, "needs_response", 1000, 1500L)).statusCode())
                .isEqualTo(204);
        assertThat(stage(lateDispute)).isEqualTo("CHARGED_BACK");
        assertThat(lines(lateDispute))
                .endsWith("dispute-fee|DEBIT:DISPUTE_COSTS:1500",
                        "dispute-fee|CREDIT:SETTLEMENT_CLEARING:1500");
        assertThat(audits(lateDispute, "payments.DisputeFeeRecorded")).isEqualTo(1);
    }

    @Test
    @DisplayName("THE GATE'S CRITERION: ten concurrent deliveries of one chargeback under fresh"
            + " event ids produce a SINGLE financial effect - one entry per stage key, the fee"
            + " once, the balances moved once")
    void tenFreshIdChargebacksPostOnce() throws Exception {
        Payment payment = captured(wallet());
        String reference = someDisputeReference();
        List<Integer> answers =
                race(10, i -> chargeback(payment, reference, "won", 1000, 1500L));
        assertThat(answers).containsOnly(204);

        UUID dispute = disputeId(reference);
        assertThat(operations(dispute))
                .containsExactly("dispute-chargeback", "dispute-attribution", "dispute-fee",
                        "dispute-won", "dispute-restoration");
        assertThat(balance(payment.credit())).as("charged once, restored once").isEqualTo(1000);
    }

    // -----------------------------------------------------------------
    // The counterparty that can take no posting (ADR-0061 section 5)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a chargeback on a payment whose wallet is CLOSED is parked in the recoverable,"
            + " never refused - and a win returns it from there")
    void aClosedWalletsShareIsParked() throws Exception {
        Payment payment = captured(wallet());
        // The customer spent nothing and the wallet is closed: V007 refuses it any new line.
        spend(payment.credit(), 1000);
        closeLedgerAccount(payment.credit());
        String reference = someDisputeReference();

        assertThat(deliver(chargeback(payment, reference, "needs_response", 1000, null))
                        .statusCode())
                .as("the network has already taken the money: recorded, never refused")
                .isEqualTo(204);

        UUID dispute = disputeId(reference);
        assertThat(split(dispute)).as("share, parked, excess").isEqualTo(new long[] {0, 1000, 0});
        assertThat(lines(dispute))
                .containsExactly(
                        "dispute-chargeback|DEBIT:CHARGEBACK_RECOVERABLE:1000",
                        "dispute-chargeback|CREDIT:SETTLEMENT_CLEARING:1000");

        deliver(chargeback(payment, reference, "under_review", 1000, null));
        deliver(chargeback(payment, reference, "won", 1000, null));
        assertThat(nets(dispute).values()).containsOnly(0L);
    }

    @Test
    @DisplayName("a wallet cannot close while a chargeback charged to it may still be WON - the"
            + " win would credit it back - and closes once the dispute is lost")
    void aRestorableChargebackKeepsTheWalletOpen() throws Exception {
        Customer customer = customerWithWallet();
        Payment payment = captured(customer.wallet());
        String reference = someDisputeReference();
        deliver(chargeback(payment, reference, "needs_response", 1000, null));
        assertThat(balance(customer.wallet())).as("empty after the chargeback").isZero();

        assertThatThrownBy(() -> close(customer))
                .as("empty means nothing on its way either: a win would credit it")
                .isInstanceOf(AccountNotEmptyException.class);

        deliver(chargeback(payment, reference, "lost", 1000, null));
        assertThat(close(customer)).as("no win can follow a loss").isTrue();
    }

    // -----------------------------------------------------------------
    // The capture that lands after its chargeback (the gate's find)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a chargeback stated while the capture is still being RESOLVED is recorded AT"
            + " ONCE against what has been captured - nothing, so all excess - and when the"
            + " capture lands, in the capture's own transaction, the counterparty's share comes"
            + " back to it: the split a chargeback arriving then would have taken")
    void aChargebackBeforeItsCaptureIsAttributedWhenTheCaptureLands() throws Exception {
        LedgerAccountId wallet = wallet();
        Payment payment = unresolvedCapture(wallet);
        String reference = someDisputeReference();

        assertThat(deliver(chargeback(payment, reference, "needs_response", 1000, null))
                        .statusCode())
                .as("the external fact first: recorded, never deferred on a retry window")
                .isEqualTo(204);
        UUID dispute = disputeId(reference);
        assertThat(split(dispute)).as("nothing captured credited nobody")
                .isEqualTo(new long[] {0, 0, 1000});

        // The capture's own outcome, through the production door and PaymentOutcomes.
        assertThat(deliver("{\"eventId\":\"evt_cap_" + UUID.randomUUID() + "\",\"operation\":\""
                                + payment.operation() + "\",\"status\":\"approved\","
                                + "\"reference\":\"psp_cap-" + IDS.next() + "\"}")
                        .statusCode())
                .isEqualTo(204);

        assertThat(oneString("SELECT status FROM payments.payment_attempt WHERE id = ?",
                        payment.attempt()))
                .isEqualTo("CAPTURED");
        assertThat(split(dispute)).isEqualTo(new long[] {1000, 0, 0});
        assertThat(lines(dispute))
                .containsExactly(
                        "dispute-chargeback|DEBIT:CHARGEBACK_RECOVERABLE:1000",
                        "dispute-chargeback|CREDIT:SETTLEMENT_CLEARING:1000",
                        "dispute-reattribution|DEBIT:CUSTOMER_WALLET:1000",
                        "dispute-reattribution|CREDIT:CHARGEBACK_RECOVERABLE:1000");
        assertThat(balance(wallet))
                .as("credited by the capture, charged back in the same breath: zero")
                .isZero();
        assertThat(auditSummaries(dispute, "payments.ChargebackReattributed"))
                .singleElement()
                .asString()
                .contains("cause=capture:" + payment.attempt());
    }

    // -----------------------------------------------------------------
    // The surfaces
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the negative-position gauge counts a wallet a spent top-up's chargeback drove"
            + " below zero - a receivable from the customer, visible, never absorbed")
    void theNegativePositionGaugeCountsTheReceivable() throws Exception {
        Payment payment = captured(wallet());
        spend(payment.credit(), 1000);
        long before = negativeWallets();

        deliver(chargeback(payment, someDisputeReference(), "needs_response", 1000, null));

        assertThat(balance(payment.credit())).isEqualTo(-1000);
        assertThat(negativeWallets()).isEqualTo(before + 1);
        assertThat(registry.find("finapp.ledger.negative.positions")
                        .tag("purpose", "CUSTOMER_WALLET")
                        .gauge())
                .as("published eagerly by a running instance")
                .isNotNull();
    }

    @Test
    @DisplayName("the merchant's payable drill-down NAMES its chargebacks - never a refund, never"
            + " 'other' - and a win's restoration as chargebacks reversed; the terms still sum to"
            + " the position (INV-MER-02 as a surface)")
    void theMerchantDrillDownNamesChargebacks() throws Exception {
        UUID merchant = IDS.next();
        LedgerAccountId payable =
                asActor(uow -> ledgerAccountStore
                        .createOrConverge(
                                uow,
                                LedgerAccount.owned(
                                        IDS, CLOCK, AccountType.LIABILITY,
                                        AccountPurpose.MERCHANT_PAYABLE, EUR, merchant))
                        .account()
                        .id());
        Payment sale = captured(payable);
        String reference = someDisputeReference();
        deliver(chargeback(sale, reference, "needs_response", 1000, null));

        com.finapp.merchant.MerchantPayable.Payable charged = payableOf(merchant);
        assertThat(charged.captured()).isEqualTo(Money.ofMinorUnits(1000, EUR));
        assertThat(charged.chargedBack()).isEqualTo(Money.ofMinorUnits(1000, EUR));
        assertThat(charged.refunded()).as("a chargeback is never a refund")
                .isEqualTo(Money.ofMinorUnits(0, EUR));
        assertThat(charged.other()).isEqualTo(Money.ofMinorUnits(0, EUR));
        assertThat(charged.position()).isEqualTo(Money.ofMinorUnits(0, EUR));
        assertThat(charged.terms()).isEqualTo(charged.position());

        deliver(chargeback(sale, reference, "under_review", 1000, null));
        deliver(chargeback(sale, reference, "won", 1000, null));
        com.finapp.merchant.MerchantPayable.Payable won = payableOf(merchant);
        assertThat(won.chargebacksReversed()).isEqualTo(Money.ofMinorUnits(1000, EUR));
        assertThat(won.captured()).as("a restoration is never a second sale")
                .isEqualTo(Money.ofMinorUnits(1000, EUR));
        assertThat(won.position()).isEqualTo(Money.ofMinorUnits(1000, EUR));
        assertThat(won.terms()).isEqualTo(won.position());
    }

    private com.finapp.merchant.MerchantPayable.Payable payableOf(UUID merchant)
            throws Exception {
        List<com.finapp.merchant.MerchantPayable.Payable> payables =
                asActor(uow -> new com.finapp.merchant.MerchantPayable(
                                ledgerAccountStore, new com.finapp.ledger.JdbcPositionBreakdown())
                        .payablesOf(uow, com.finapp.merchant.MerchantId.of(merchant)));
        assertThat(payables).hasSize(1);
        return payables.get(0);
    }

    // -----------------------------------------------------------------
    // Seeds
    // -----------------------------------------------------------------

    /** A card payment and the account its capture credited. */
    private record Payment(UUID intent, UUID attempt, String operation, LedgerAccountId credit) {}

    private record Customer(UUID customer, UUID account, LedgerAccountId wallet) {}

    /** A real customer wallet ledger account, owned by a fresh customer reference. */
    private LedgerAccountId wallet() throws Exception {
        return asActor(
                uow ->
                        ledgerAccountStore
                                .createOrConverge(
                                        uow,
                                        LedgerAccount.owned(
                                                IDS, CLOCK, AccountType.LIABILITY,
                                                AccountPurpose.CUSTOMER_WALLET, EUR, IDS.next()))
                                .account()
                                .id());
    }

    /**
     * A captured 10.00 card payment crediting {@code credit}, seeded raw WITH its capture's
     * posting (the production key and lines: DR the card clearing / CR the counterparty), so the
     * counterparty holds exactly what the capture credited it.
     */
    private Payment captured(LedgerAccountId credit) throws Exception {
        Payment payment = seededPayment(credit, "CAPTURED");
        posted("payment-capture:" + payment.attempt(), payment.attempt().toString(),
                List.of(
                        new JournalLine(clearing(), Direction.DEBIT, Money.ofMinorUnits(1000, EUR)),
                        new JournalLine(credit, Direction.CREDIT, Money.ofMinorUnits(1000, EUR))));
        return payment;
    }

    /** A card payment whose capture was dispatched and whose outcome is still unknown. */
    private Payment unresolvedCapture(LedgerAccountId credit) throws Exception {
        return seededPayment(credit, "CAPTURE_UNKNOWN");
    }

    private static Payment seededPayment(LedgerAccountId credit, String state)
            throws SQLException {
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        String capture = "cap-" + IDS.next();
        boolean captured = state.equals("CAPTURED");
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO payments.payment_intent (id, party_id, customer_id,"
                            + " payment_method_id, credit_account_id, amount_minor, currency,"
                            + " scale, status, created_at, capture_mode)"
                            + " VALUES (?, ?, ?, ?, ?, 1000, 'EUR', 2, ?, now(), 'AUTOMATIC')",
                    intent, IDS.next(), IDS.next(), IDS.next(), credit.value(),
                    captured ? "SUCCEEDED" : "PROCESSING");
            execute(app,
                    "INSERT INTO payments.payment_attempt (id, intent_id, auth_reference,"
                            + " capture_reference, auth_provider_reference,"
                            + " capture_provider_reference, authorized_amount_minor,"
                            + " authorized_currency, authorized_scale, captured_amount_minor,"
                            + " captured_currency, captured_scale, status, created_at, rail,"
                            + " interaction_model)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 1000, 'EUR', 2, ?, ?, ?, ?, now(),"
                            + " 'card', 'TWO_STEP')",
                    attempt, intent, "auth-" + IDS.next(), capture, "psp-auth-" + IDS.next(),
                    captured ? "psp-cap-" + IDS.next() : null,
                    captured ? 1000L : null,
                    captured ? "EUR" : null,
                    captured ? (short) 2 : null,
                    state);
        }
        return new Payment(intent, attempt, capture, credit);
    }

    /** The customer spends {@code minor} out of the wallet (to the fee revenue: any operational
     * counterpart will do - what matters is the wallet's position). */
    private void spend(LedgerAccountId wallet, long minor) throws Exception {
        LedgerAccountId elsewhere =
                asActor(uow -> new ChartOfAccounts<>(ledgerAccountStore)
                        .resolve(uow, AccountPurpose.FEE_REVENUE, EUR)
                        .id());
        posted("test-spend:" + IDS.next(), "spend-" + IDS.next(),
                List.of(
                        new JournalLine(wallet, Direction.DEBIT, Money.ofMinorUnits(minor, EUR)),
                        new JournalLine(elsewhere, Direction.CREDIT,
                                Money.ofMinorUnits(minor, EUR))));
    }

    private void closeLedgerAccount(LedgerAccountId account) throws Exception {
        asActor(uow -> ledgerAccountStore.moveStatus(
                uow, account, LedgerAccountStatus.ACTIVE, LedgerAccountStatus.CLOSED,
                Instant.now(CLOCK)));
    }

    /** A verified customer with an OPEN wallet product - the real opening, its ledger account. */
    private Customer customerWithWallet() throws Exception {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Dispute Holder', now() - interval '2 hour')",
                    party);
            execute(app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    customer, party);
        }
        CustomerAccountStore.Creation opened =
                asActor(uow -> accountOpening.open(uow, party, ProductType.WALLET, EUR));
        UUID account = opened.account().id().value();
        LedgerAccountId wallet =
                asActor(uow -> ledgerAccountStore
                        .findOwned(uow, account, AccountPurpose.CUSTOMER_WALLET, EUR)
                        .orElseThrow()
                        .id());
        return new Customer(customer, account, wallet);
    }

    /** The real close, as its owner - true when this call ended the agreement. */
    private boolean close(Customer customer) throws Exception {
        return asActor(uow -> accountClosing
                        .close(uow, customer.customer(),
                                com.finapp.accounts.CustomerAccountId.of(customer.account()))
                        .orElseThrow()
                        .closed());
    }

    private LedgerAccountId clearing() throws Exception {
        return asActor(uow -> new ChartOfAccounts<>(ledgerAccountStore)
                .resolve(uow, AccountPurpose.SETTLEMENT_CLEARING, EUR)
                .id());
    }

    /** One posting as the platform, through the production posting service. */
    private void posted(String key, String reference, List<JournalLine> lines) throws Exception {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope platform = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LocalDate today = LocalDate.now(CLOCK);
            postingService.post(app, new PostingCommand(key, today, today, reference, lines));
            app.commit();
        }
    }

    /** The production refund command, as an operator; {@code pspReference} approves it. */
    private PaymentRefund.RefundResult refund(Payment payment, long minor, String pspReference)
            throws Exception {
        if (pspReference != null) {
            provider.succeedsWith(
                    SimulatedCardPspAdapter.REFUNDS_PATH,
                    200,
                    "{\"status\":\"approved\",\"reference\":\"" + pspReference + "\"}");
        }
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope operator =
                        SecurityContext.enter(
                                new Actor(IDS.next().toString(), ActorType.EMPLOYEE))) {
            return paymentRefund.refund(
                    PaymentIntentId.of(payment.intent()),
                    Money.ofMinorUnits(minor, EUR),
                    "chargeback-suite refund",
                    "cbr-" + UUID.randomUUID());
        }
    }

    private <R> R asActor(Function<Connection, R> work) throws Exception {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope acting =
                        SecurityContext.enter(
                                new Actor(IDS.next().toString(), ActorType.CUSTOMER));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            R result = work.apply(app);
            app.commit();
            return result;
        }
    }

    // -----------------------------------------------------------------
    // The wire
    // -----------------------------------------------------------------

    /** A dispute statement in the simulated PSP's wire shape, a fresh event id each time. */
    private static String chargeback(
            Payment payment, String dispute, String stage, long amountMinor, Long feeMinor) {
        return "{\"eventId\":\"evt_" + UUID.randomUUID() + "\",\"operation\":\""
                + payment.operation() + "\",\"status\":\"disputed\",\"dispute\":\"" + dispute
                + "\",\"stage\":\"" + stage + "\",\"reasonCode\":\"fraudulent\","
                + "\"amountMinor\":\"" + amountMinor + "\",\"currency\":\"EUR\",\"scale\":2"
                + (feeMinor == null ? "" : ",\"feeMinor\":\"" + feeMinor + "\"")
                + "}";
    }

    /** The refund's own statement on the same door, by the refund's reference. */
    private static String refundStatement(PaymentRefund.RefundResult refund, String status)
            throws SQLException {
        return "{\"eventId\":\"rfd-evt-" + UUID.randomUUID() + "\",\"operation\":\""
                + oneString("SELECT provider_idempotency_reference FROM payments.refund"
                        + " WHERE id = ?", refund.refund().value())
                + "\",\"status\":\"" + status + "\",\"reference\":\"psp_no\"}";
    }

    private HttpResponse<String> deliver(String body) throws Exception {
        String timestamp = Long.toString(Instant.now(CLOCK).getEpochSecond());
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://localhost:" + port
                                                    + "/v1/providers/payments/webhooks"))
                            .header("Content-Type", "application/json")
                            .header(WebhookSignature.TIMESTAMP_HEADER, timestamp)
                            .header(WebhookSignature.SIGNATURE_HEADER,
                                    hmacHex(timestamp + "." + body))
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private List<Integer> race(int racers, java.util.function.IntFunction<String> bodies)
            throws Exception {
        CountDownLatch open = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            List<Future<Integer>> answers = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                String body = bodies.apply(i);
                answers.add(pool.submit(() -> {
                    open.await();
                    return deliver(body).statusCode();
                }));
            }
            open.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> answer : answers) {
                statuses.add(answer.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String hmacHex(String signedPayload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(WEBHOOK_KEY, "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is required by every JVM", impossible);
        }
    }

    // -----------------------------------------------------------------
    // Readers
    // -----------------------------------------------------------------

    private static UUID disputeId(String reference) throws SQLException {
        return UUID.fromString(oneString(
                "SELECT id FROM payments.dispute WHERE provider = ? AND"
                        + " provider_dispute_reference = ?",
                SimulatedCardPspAdapter.NAME, reference));
    }

    private static String stage(UUID dispute) throws SQLException {
        return oneString("SELECT stage FROM payments.dispute WHERE id = ?", dispute);
    }

    /** {counterparty share, parked share, excess}, in minor units. */
    private static long[] split(UUID dispute) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(
                        "SELECT counterparty_share_amount_minor, parked_share_amount_minor,"
                                + " chargeback_amount_minor FROM payments.dispute WHERE id = ?")) {
            read.setObject(1, dispute);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                long share = row.getLong(1);
                long parked = row.getLong(2);
                return new long[] {share, parked, row.getLong(3) - share - parked};
            }
        }
    }

    /**
     * Every line of every entry referencing the dispute, as {@code
     * operation|DIRECTION:PURPOSE:minor}, in posting order (the store-minted UUIDv7 entry ids,
     * then each entry's own line order).
     */
    private static List<String> lines(UUID dispute) throws SQLException {
        return strings(
                "SELECT split_part(substring(entry.idempotency_scope FROM"
                        + " length('ledger.post:') + 1), ':', 1) || '|' || line.direction"
                        + " || ':' || account.purpose || ':' || line.amount_minor"
                        + " FROM ledger.journal_entry entry"
                        + " JOIN ledger.journal_line line ON line.entry_id = entry.id"
                        + " JOIN ledger.ledger_account account"
                        + "   ON account.id = line.ledger_account_id"
                        + " WHERE entry.reference = ? ORDER BY entry.id, line.seq",
                dispute.toString());
    }

    private static List<String> operations(UUID dispute) throws SQLException {
        return strings(
                "SELECT split_part(substring(idempotency_scope FROM length('ledger.post:') + 1),"
                        + " ':', 1) FROM ledger.journal_entry WHERE reference = ? ORDER BY id",
                dispute.toString());
    }

    /** Per purpose, debits minus credits over every entry referencing the dispute. */
    private static Map<String, Long> nets(UUID dispute) throws SQLException {
        Map<String, Long> nets = new LinkedHashMap<>();
        for (String line : lines(dispute)) {
            String[] parts = line.split("\\|")[1].split(":");
            long signed = Long.parseLong(parts[2]) * (parts[0].equals("DEBIT") ? 1 : -1);
            nets.merge(parts[1], signed, Long::sum);
        }
        return nets;
    }

    /** A liability-normal account's settled position from its lines: credits minus debits. */
    private static long balance(LedgerAccountId account) throws SQLException {
        return count(
                "SELECT coalesce(sum(CASE direction WHEN 'CREDIT' THEN amount_minor"
                        + " ELSE -amount_minor END), 0) FROM ledger.journal_line"
                        + " WHERE ledger_account_id = ?",
                account.value());
    }

    private static long negativeWallets() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return new JdbcNegativePositions()
                    .countBelowZero(app, Set.of(AccountPurpose.CUSTOMER_WALLET))
                    .get(AccountPurpose.CUSTOMER_WALLET);
        }
    }

    private static String refundStatus(PaymentRefund.RefundResult refund) throws SQLException {
        return oneString("SELECT status FROM payments.refund WHERE id = ?", refund.refund().value());
    }

    private static long audits(UUID dispute, String operation) throws SQLException {
        return count(
                "SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND operation = ?",
                dispute.toString(), operation);
    }

    private static List<String> auditSummaries(UUID dispute, String operation)
            throws SQLException {
        return strings(
                "SELECT change_summary FROM platform.audit_record WHERE target_id = ?"
                        + " AND operation = ? ORDER BY audit_id",
                dispute.toString(), operation);
    }

    private static String eventPayload(UUID dispute, String type) throws SQLException {
        return oneString(
                "SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event"
                        + " WHERE aggregate_id = ? AND event_type = ?",
                dispute, type);
    }

    private static long evidenceCount(UUID attempt) throws SQLException {
        return count("SELECT count(*) FROM payments.provider_evidence WHERE attempt_id = ?",
                attempt);
    }

    private double webhooks(String outcome) {
        return registry.find(WEBHOOK_METER).tag("outcome", outcome).counter().count();
    }

    private static String someDisputeReference() {
        return "dp_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String oneString(String sql, Object... arguments) throws SQLException {
        List<String> values = strings(sql, arguments);
        assertThat(values).as(sql).hasSize(1);
        return values.get(0);
    }

    private static List<String> strings(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            List<String> values = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    values.add(row.getString(1));
                }
            }
            return values;
        }
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
}
