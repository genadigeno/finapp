package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.payments.JdbcPaymentParticipants;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AsOf;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.merchant.FeeAssessment;
import com.finapp.merchant.FeeCalculation;
import com.finapp.merchant.FeeRate;
import com.finapp.merchant.FeeSchedule;
import com.finapp.merchant.FeeScheduleVersion;
import com.finapp.merchant.FeeScheduleVersionId;
import com.finapp.merchant.FeeSchedules;
import com.finapp.merchant.JdbcFeeScheduleStore;
import com.finapp.merchant.JdbcMerchantStore;
import com.finapp.merchant.JdbcPaymentFeePinStore;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantSettlement;
import com.finapp.merchant.MerchantSettlementException;
import com.finapp.merchant.PaymentFeePin;
import com.finapp.merchant.RefundFeePolicy;
import com.finapp.party.JdbcPartyStore;
import com.finapp.paymentmethods.JdbcPaymentMethodStore;
import com.finapp.payments.CaptureComposition;
import com.finapp.payments.EvidenceCipher;
import com.finapp.payments.InstrumentToken;
import com.finapp.payments.JdbcPaymentAttemptStore;
import com.finapp.payments.JdbcPaymentIntentStore;
import com.finapp.payments.JdbcProviderEvidenceStore;
import com.finapp.payments.PaymentAttemptId;
import com.finapp.payments.PaymentAttemptStatus;
import com.finapp.payments.PaymentCapture;
import com.finapp.payments.PaymentConfirmation;
import com.finapp.payments.PaymentCreation;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentIntentStatus;
import com.finapp.payments.PaymentOutcomes;
import com.finapp.payments.PaymentParticipants;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.TransactionRunner;
import com.finapp.payments.WalletTopUpComposition;
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
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <strong>ADR-0050 §3's entry, end to end</strong> (`P6-TSK-005`) — the phase's financial
 * heart, against the real ledger, the real merchant chain and a real HTTP provider.
 *
 * <pre>
 *   DR SETTLEMENT_CLEARING   gross
 *   CR MERCHANT_PAYABLE      gross
 *   DR MERCHANT_PAYABLE      fee
 *   CR FEE_REVENUE           fee
 * </pre>
 *
 * <p><strong>The intent is test-composed, deliberately and with the backlog's sanction.</strong>
 * A checkout surface that creates merchant-bound intents is {@code P6-TSK-007}'s; what exists
 * today is the seam, and the honest way to exercise a seam before its production caller is to
 * be that caller. Everything downstream of intent creation — confirmation, authorization, the
 * provider, the capture, the posting — is the real path production runs.
 *
 * <p>Asserted by {@code DIRECTION:PURPOSE} rather than by line count (the `P5-TST-002` lesson):
 * a count of four says nothing about <em>which</em> four.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the merchant-bound capture (P6-TSK-005)")
class MerchantCaptureDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    /** 100.00 — chosen so 2.9% + 0.30 is 3.20 exactly, and the net 96.80. */
    private static final Money AMOUNT = Money.ofMinorUnits(100_00L, EUR);
    private static final byte[] PSP_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EVIDENCE_KEY =
            "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);
    private static final String APPROVED_BODY =
            "{\"status\":\"approved\",\"reference\":\"psp_%s\"}";

    private static SimulatedProvider psp;

    private final TransactionRunner runner = new AppRoleRunner();
    private final JdbcPaymentIntentStore intents = new JdbcPaymentIntentStore();
    private final JdbcPaymentAttemptStore attempts = new JdbcPaymentAttemptStore();
    private final JdbcProviderEvidenceStore evidence =
            new JdbcProviderEvidenceStore(
                    new EvidenceCipher(EVIDENCE_KEY, 1, new SecureRandom()), IDS);
    private final JdbcLedgerAccountStore ledgerAccounts = new JdbcLedgerAccountStore();
    private final JdbcFeeScheduleStore schedules = new JdbcFeeScheduleStore();
    private final JdbcPaymentFeePinStore pins = new JdbcPaymentFeePinStore();
    private final JdbcPaymentParticipants realParticipants =
            new JdbcPaymentParticipants(
                    new JdbcPartyStore(),
                    new com.finapp.accounts.JdbcCustomerAccountStore(),
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
                        Correlation.startingWith(CorrelationId.of("mcap-" + UUID.randomUUID())));
    }

    @org.junit.jupiter.api.AfterEach
    void leaveFlow() throws Exception {
        testFlow.close();
    }

    // ----------------------------------------------------------------- the entry

    @Test
    @DisplayName("ADR-0050 §3: ONE entry, FOUR lines, on the accounts the ADR names - and the"
            + " payable's position is the NET")
    void theMerchantBoundCapturePostsTheFourLines() throws Exception {
        Merchant merchant = onboardedMerchant("0.029", 30L);
        Payment payment = merchantBoundPayment(merchant, AMOUNT);
        PaymentAttemptId attempt = capturedAttempt(payment);

        try (Connection app = DatabaseRoles.application()) {
            // ONE entry, and its lines named by DIRECTION:PURPOSE rather than counted - a
            // count of four says nothing about WHICH four (the P5-TST-002 lesson).
            assertThat(entriesReferencing(app, attempt)).isEqualTo(1);
            assertThat(linePurposes(app, attempt))
                    .containsExactlyInAnyOrder(
                            "DEBIT:SETTLEMENT_CLEARING",
                            "CREDIT:MERCHANT_PAYABLE",
                            "DEBIT:MERCHANT_PAYABLE",
                            "CREDIT:FEE_REVENUE");

            // 100.00 x 2.9% = 2.90, + 0.30 = 3.20; net 96.80.
            assertThat(amountOn(app, attempt, "CREDIT", AccountPurpose.MERCHANT_PAYABLE))
                    .isEqualTo(100_00L);
            assertThat(amountOn(app, attempt, "DEBIT", AccountPurpose.MERCHANT_PAYABLE))
                    .isEqualTo(3_20L);
            assertThat(amountOn(app, attempt, "CREDIT", AccountPurpose.FEE_REVENUE))
                    .isEqualTo(3_20L);
            assertThat(amountOn(app, attempt, "DEBIT", AccountPurpose.SETTLEMENT_CLEARING))
                    .isEqualTo(100_00L);

            // INV-MER-04 at the ledger: fee + net = captured, to the minor unit.
            assertThat(3_20L + 96_80L).isEqualTo(AMOUNT.minorUnits());

            // INV-MER-02: what the platform owes is the POSITION, derived - gross in, fee out.
            assertThat(payablePosition(app, merchant).minorUnits()).isEqualTo(96_80L);
        }
    }

    @Test
    @DisplayName("merchant.FeeAssessed commits WITH the entry, naming the pinned version and"
            + " the three amounts (INV-HIST-04 on the wire)")
    void theAssessmentIsAnnouncedWithTheEntry() throws Exception {
        Merchant merchant = onboardedMerchant("0.029", 30L);
        Payment payment = merchantBoundPayment(merchant, AMOUNT);
        capturedAttempt(payment);

        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT payload FROM platform.outbox_event WHERE event_type ="
                                        + " 'merchant.FeeAssessed' AND aggregate_id = ?")) {
            read.setObject(1, merchant.id().value());
            try (ResultSet rows = read.executeQuery()) {
                assertThat(rows.next()).as("exactly one announcement").isTrue();
                String payload = new String(rows.getBytes(1), StandardCharsets.UTF_8);
                assertThat(payload)
                        .contains(payment.intent().value().toString())
                        .contains(merchant.versionId().value().toString())
                        .contains("\"grossMinor\":\"10000\"")
                        .contains("\"feeMinor\":\"320\"")
                        .contains("\"netMinor\":\"9680\"");
                assertThat(rows.next()).as("and only one").isFalse();
            }
        }
    }

    @Test
    @DisplayName("INV-MER-03: a version created mid-flight prices NOTHING - the capture uses"
            + " the version pinned when the price was agreed")
    void aMidFlightVersionChangeDoesNotReprice() throws Exception {
        Merchant merchant = onboardedMerchant("0.029", 30L);
        Payment payment = merchantBoundPayment(merchant, AMOUNT);

        // The platform reprices, effective immediately - AFTER this payment was agreed and
        // BEFORE its capture arrives. This is the restatement question 8 carried, one level
        // down: without the pin, a customer who agreed to one price pays another.
        addVersion(merchant, "0.099", 500L);

        PaymentAttemptId attempt = capturedAttempt(payment);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(amountOn(app, attempt, "CREDIT", AccountPurpose.FEE_REVENUE))
                    .as("2.9%% + 0.30, not 9.9%% + 5.00")
                    .isEqualTo(3_20L);
            assertThat(payablePosition(app, merchant).minorUnits()).isEqualTo(96_80L);
        }
    }

    @Test
    @DisplayName("INV-MER-03: recomputing under the PIN reproduces the posted fee exactly")
    void theLedgerAgreesWithTheRecomputation() throws Exception {
        Merchant merchant = onboardedMerchant("0.0175", 25L);
        Payment payment = merchantBoundPayment(merchant, Money.ofMinorUnits(333L, EUR));
        PaymentAttemptId attempt = capturedAttempt(payment);

        FeeAssessment recomputed =
                runner.inTransaction(
                        uow -> {
                            FeeScheduleVersion version =
                                    schedules.findVersion(uow, merchant.versionId()).orElseThrow();
                            return FeeCalculation.assess(Money.ofMinorUnits(333L, EUR), version);
                        });
        try (Connection app = DatabaseRoles.application()) {
            assertThat(amountOn(app, attempt, "CREDIT", AccountPurpose.FEE_REVENUE))
                    .as("the books and the pinned arithmetic are the same number")
                    .isEqualTo(recomputed.fee().minorUnits());
        }
        // 333 x 0.0175 = 5.8275 -> HALF_EVEN -> 6, + 25 = 31; net 302.
        assertThat(recomputed.fee().minorUnits()).isEqualTo(31L);
        assertThat(recomputed.net().minorUnits()).isEqualTo(302L);
    }

    @Test
    @DisplayName("TEN INSTANCES applying one capture outcome post ONE entry with FOUR lines -"
            + " the posting key's guarantee, inherited whole")
    void theDuplicateOutcomeRacePostsOnce() throws Exception {
        Merchant merchant = onboardedMerchant("0.029", 30L);
        Payment payment = merchantBoundPayment(merchant, AMOUNT);
        PaymentAttemptId attempt = authorizedAttempt(payment);
        psp.succeedsWith(
                SimulatedCardPspAdapter.CAPTURES_PATH, 200, APPROVED_BODY.formatted("race"));

        int racers = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            for (int i = 0; i < racers; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try (SecurityContext.Scope platform =
                                                    SecurityContext.enterSystem();
                                            CorrelationContext.Scope flow =
                                                    CorrelationContext.enter(
                                                            Correlation.startingWith(
                                                                    CorrelationId.of(
                                                                            "race-"
                                                                                    + UUID
                                                                                            .randomUUID())))) {
                                        return capture().capture(attempt).converged();
                                    }
                                }));
            }
            start.countDown();
            int converged = 0;
            for (Future<Boolean> result : results) {
                if (result.get(90, TimeUnit.SECONDS)) {
                    converged++;
                }
            }
            assertThat(converged)
                    .as("nine converged on the one that acted")
                    .isEqualTo(racers - 1);
        } finally {
            pool.shutdownNow();
        }

        try (Connection app = DatabaseRoles.application()) {
            assertThat(entriesReferencing(app, attempt)).isEqualTo(1);
            assertThat(linePurposes(app, attempt)).hasSize(4);
            assertThat(payablePosition(app, merchant).minorUnits())
                    .as("credited once, charged once")
                    .isEqualTo(96_80L);
            assertThat(
                            count(
                                    app,
                                    "SELECT count(*) FROM platform.outbox_event WHERE event_type"
                                            + " = 'merchant.FeeAssessed' AND aggregate_id = ?",
                                    merchant.id().value()))
                    .as("announced once, for the same reason it posted once")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a FREE schedule posts TWO lines, not two more of zero - and the merchant"
            + " keeps everything")
    void aFreeScheduleChargesNothing() throws Exception {
        Merchant merchant = onboardedMerchant("0", 0L);
        Payment payment = merchantBoundPayment(merchant, AMOUNT);
        PaymentAttemptId attempt = capturedAttempt(payment);

        try (Connection app = DatabaseRoles.application()) {
            assertThat(linePurposes(app, attempt))
                    .containsExactlyInAnyOrder(
                            "DEBIT:SETTLEMENT_CLEARING", "CREDIT:MERCHANT_PAYABLE");
            assertThat(payablePosition(app, merchant).minorUnits()).isEqualTo(100_00L);
        }
    }

    // ----------------------------------------------------------------- the regression pin

    @Test
    @DisplayName("THE REGRESSION PIN: a payment with NO fee pin still posts Phase 5's exact"
            + " two lines, through the production seam")
    void aWalletTopUpIsUnchanged() throws Exception {
        // The seam's fallback, proven where production runs it rather than by constructing
        // WalletTopUpComposition directly - which would prove only that the class compiles.
        Merchant merchant = onboardedMerchant("0.029", 30L);
        Payment unpinned = payment(merchant.payable(), AMOUNT, Optional.empty());
        PaymentAttemptId attempt = capturedAttempt(unpinned);

        try (Connection app = DatabaseRoles.application()) {
            assertThat(linePurposes(app, attempt))
                    .as("no pin, no fee, no change - ADR-0048's two lines")
                    .containsExactlyInAnyOrder(
                            "DEBIT:SETTLEMENT_CLEARING", "CREDIT:MERCHANT_PAYABLE");
            assertThat(
                            count(
                                    app,
                                    "SELECT count(*) FROM platform.outbox_event WHERE event_type"
                                            + " = 'merchant.FeeAssessed' AND aggregate_id = ?",
                                    merchant.id().value()))
                    .as("and nothing was assessed")
                    .isZero();
        }
    }

    // ----------------------------------------------------------------- the three assumptions

    @Test
    @DisplayName("a capture whose amount is not the pinned gross is REFUSED, not priced")
    void aMismatchedGrossIsRefused() throws Exception {
        Merchant merchant = onboardedMerchant("0.029", 30L);
        assertThatThrownBy(
                        () ->
                                runner.inTransaction(
                                        uow ->
                                                settlement()
                                                        .settle(
                                                                uow,
                                                                pinFor(merchant, AMOUNT),
                                                                clearing(),
                                                                merchant.payable(),
                                                                // Not the pinned gross.
                                                                Money.ofMinorUnits(50_00L, EUR),
                                                                correlation(),
                                                                Instant.now(CLOCK))))
                .isInstanceOf(MerchantSettlementException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    @DisplayName("a capture crediting an account that is NOT the pinned merchant's payable is"
            + " REFUSED - the intent and the pin must agree about whose payment this is")
    void aCreditToTheWrongAccountIsRefused() throws Exception {
        Merchant one = onboardedMerchant("0.029", 30L);
        Merchant two = onboardedMerchant("0.029", 30L);
        assertThatThrownBy(
                        () ->
                                runner.inTransaction(
                                        uow ->
                                                settlement()
                                                        .settle(
                                                                uow,
                                                                pinFor(one, AMOUNT),
                                                                clearing(),
                                                                // Another merchant's payable.
                                                                two.payable(),
                                                                AMOUNT,
                                                                correlation(),
                                                                Instant.now(CLOCK))))
                .isInstanceOf(MerchantSettlementException.class)
                .hasMessageContaining("disagree about whose payment this is");
    }

    @Test
    @DisplayName("a merchant with no payable in the captured currency is REFUSED, not posted"
            + " two lines and called done")
    void aMissingPayableIsRefused() throws Exception {
        // ADDED BY THE COMPLETION GATE. The other two assumptions had tests; this one did not,
        // and it is the least unreachable of the three: a merchant settles in ONE currency, so
        // a checkout session priced in another produces exactly this. P6-TSK-007 must refuse
        // it at the session; until then this throw is the backstop, and now it is a checked
        // one.
        Merchant merchant = onboardedMerchant("0.029", 30L);
        CurrencyCode usd = CurrencyCode.of("USD");
        Money inUsd = Money.ofMinorUnits(100_00L, usd);
        UUID intentRef = IDS.next();
        runner.inTransaction(uow -> pin(uow, merchant, intentRef, inUsd));

        assertThatThrownBy(
                        () ->
                                runner.inTransaction(
                                        uow ->
                                                settlement()
                                                        .settle(
                                                                uow,
                                                                intentRef,
                                                                clearingIn(usd),
                                                                merchant.payable(),
                                                                inUsd,
                                                                correlation(),
                                                                Instant.now(CLOCK))))
                .isInstanceOf(MerchantSettlementException.class)
                .hasMessageContaining("has no USD payable account");
    }

    @Test
    @DisplayName("A GATE FINDING, PINNED: a refund of a merchant-bound capture returns the"
            + " GROSS and applies NO fee treatment - the pinned refundFeePolicy has no"
            + " consumer yet")
    void aMerchantRefundAppliesNoFeeTreatmentYet() throws Exception {
        // FOUND BY THIS TASK'S COMPLETION GATE, and recorded here rather than only in a
        // document, because a gap that lives only in prose is a gap nobody trips over.
        //
        // The CAPTURE now composes its lines through a seam; the REFUND still writes its own
        // two inline (PaymentOutcomes.applyRefund: DR the credit account / CR clearing). For a
        // merchant-bound payment that returns the gross out of the payable - the right
        // DIRECTION - but ADR-0050's consequences say the fee follows the schedule's
        // refundFeePolicy, RETAINED or RETURNED, and NOTHING READS THAT ATTRIBUTE. A RETURNED
        // policy therefore owes the merchant its fee back and no code returns it.
        //
        // Unreachable in production today: only a test can create a merchant-bound intent
        // (P6-TSK-007 brings the first real one). Owned by P6-TSK-014, which this gate added
        // to the backlog - without it, P6-TST-002's "payables equal captured - fees - refunds
        // - payouts" is an identity nothing produces the terms for.
        //
        // THIS TEST PINS THE BEHAVIOUR THAT EXISTS so the suite tells the truth, and so that
        // the task which fixes it must CHANGE this test rather than discover the question.
        assertThat(feeTreatmentOnRefundExists())
                .as("when this becomes true, P6-TSK-014 has landed and this test is its"
                        + " assertion to rewrite")
                .isFalse();
    }

    /**
     * Whether any production code reads a schedule's refund-fee policy. Asserted structurally
     * rather than behaviourally: the gap is an ABSENCE, and the honest way to pin an absence
     * is to look for the thing that is not there.
     */
    private static boolean feeTreatmentOnRefundExists() {
        return java.util.Arrays.stream(MerchantSettlement.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().toLowerCase().contains("refund"));
    }

    @Test
    @DisplayName("a second pin of the SAME decision converges and writes nothing - one price"
            + " per payment, and a retry is not a repricing (P6-TSK-007)")
    void anIdenticalSecondPinConverges() throws Exception {
        // FOUND BY THE P6-TSK-007 FLOW SUITE, and it is the sharper half of this pair. Ten
        // instances confirming one checkout all converge on ONE payment intent (the claim is
        // keyed on the session), and then all ten arrive HERE with the same merchant, the same
        // version and the same gross. Treating the primary key's refusal as a storage failure
        // made nine of them a 500 on a purchase that worked.
        Merchant merchant = onboardedMerchant("0.029", 30L);
        UUID intentRef = IDS.next();
        runner.inTransaction(uow -> pin(uow, merchant, intentRef, AMOUNT));

        runner.inTransaction(uow -> pin(uow, merchant, intentRef, AMOUNT));

        try (Connection app = DatabaseRoles.application()) {
            assertThat(
                            count(
                                    app,
                                    "SELECT count(*) FROM merchant.payment_fee_pin"
                                            + " WHERE payment_intent_ref = ?",
                                    intentRef))
                    .as("converged, not written twice - the key still admits exactly one row")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("INV-MER-03: a second pin at a DIFFERENT price is REFUSED, and the pinned row"
            + " is the first one - a payment has one agreed price")
    void aSecondPinAtADifferentPriceIsRefused() throws Exception {
        Merchant merchant = onboardedMerchant("0.029", 30L);
        UUID intentRef = IDS.next();
        runner.inTransaction(uow -> pin(uow, merchant, intentRef, AMOUNT));

        // The convergence above must NOT extend to this: converging here would silently keep
        // whichever price was written first and report success to a caller that asked for a
        // different one, which is a repricing that leaves no trace. It throws instead, failing
        // the caller's whole transaction.
        Money different = AMOUNT.plus(Money.ofMinorUnits(1L, AMOUNT.currency()));
        assertThatThrownBy(
                        () ->
                                runner.inTransaction(
                                        uow -> pin(uow, merchant, intentRef, different)))
                .isInstanceOf(MerchantSettlementException.class)
                .hasMessageContaining("one agreed price");

        try (Connection app = DatabaseRoles.application()) {
            assertThat(
                            count(
                                    app,
                                    "SELECT gross_amount_minor FROM merchant.payment_fee_pin"
                                            + " WHERE payment_intent_ref = ?",
                                    intentRef))
                    .as("the standing price is untouched - a refused pin writes nothing")
                    .isEqualTo(AMOUNT.minorUnits());
        }
    }

    @Test
    @DisplayName("a pin is IMMUTABLE at the database - for the app role by a withheld grant,"
            + " and for the MIGRATOR by the trigger")
    void aPinCannotBeChanged() throws Exception {
        Merchant merchant = onboardedMerchant("0.029", 30L);
        UUID intentRef = IDS.next();
        runner.inTransaction(uow -> pin(uow, merchant, intentRef, AMOUNT));

        try (Connection app = DatabaseRoles.application()) {
            assertThatThrownBy(
                            () ->
                                    execute(
                                            app,
                                            "UPDATE merchant.payment_fee_pin SET"
                                                    + " gross_amount_minor = 1 WHERE"
                                                    + " payment_intent_ref = ?",
                                            intentRef))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
        // The migrator, deliberately: the only writer the grants cannot bind.
        try (Connection migrator = DatabaseRoles.migrator()) {
            assertThatThrownBy(
                            () ->
                                    execute(
                                            migrator,
                                            "UPDATE merchant.payment_fee_pin SET"
                                                    + " fee_schedule_version_id = ? WHERE"
                                                    + " payment_intent_ref = ?",
                                            merchant.versionId().value(),
                                            intentRef))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("immutable");
            assertThatThrownBy(
                            () ->
                                    execute(
                                            migrator,
                                            "DELETE FROM merchant.payment_fee_pin WHERE"
                                                    + " payment_intent_ref = ?",
                                            intentRef))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("immutable");
        }
    }

    // ----------------------------------------------------------------- fixtures

    private record Merchant(
            MerchantId id, LedgerAccountId payable, FeeScheduleVersionId versionId) {}

    private record Payment(PaymentIntentId intent, UUID party, UUID method, Actor payer) {}

    private Merchant onboardedMerchant(String rate, long fixedMinor) throws Exception {
        UUID party = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES"
                            + " (?, 'ORGANISATION', 'Acme Shop', now() - interval '2 hour')",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    IDS.next(),
                    party);
        }
        Actor operator = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
        try (SecurityContext.Scope acting = SecurityContext.enter(operator)) {
            com.finapp.merchant.MerchantOnboarding.OnboardingResult onboarded =
                    runner.inTransaction(
                            uow ->
                                    onboarding()
                                            .onboard(
                                                    uow,
                                                    new com.finapp.merchant.MerchantOnboarding
                                                            .OnboardMerchantCommand(
                                                            "onb-" + UUID.randomUUID(),
                                                            party,
                                                            "Acme GmbH",
                                                            "Acme",
                                                            EUR)));
            MerchantId merchantId = onboarded.merchantId();
            LedgerAccountId payable =
                    runner.inTransaction(
                            uow ->
                                    ledgerAccounts
                                            .findOwned(
                                                    uow,
                                                    merchantId.value(),
                                                    AccountPurpose.MERCHANT_PAYABLE,
                                                    EUR)
                                            .orElseThrow()
                                            .id());

            FeeSchedule schedule =
                    runner.inTransaction(
                            uow ->
                                    feeSchedules()
                                            .create(uow, "Std " + UUID.randomUUID(), EUR));
            FeeScheduleVersion version =
                    runner.inTransaction(
                            uow ->
                                    feeSchedules()
                                            .addVersion(
                                                    uow,
                                                    schedule.id(),
                                                    new FeeSchedules.NewVersion(
                                                            FeeRate.of(new BigDecimal(rate)),
                                                            Money.ofMinorUnits(fixedMinor, EUR),
                                                            RoundingPolicy.HALF_EVEN,
                                                            RefundFeePolicy.RETAINED,
                                                            Optional.empty(),
                                                            "initial pricing")));
            runner.inTransaction(
                    uow ->
                            feeSchedules()
                                    .assign(uow, merchantId, schedule.id(), "standard terms"));
            return new Merchant(merchantId, payable, version.id());
        }
    }

    /** A newer, dearer version of the merchant's schedule — effective immediately. */
    private void addVersion(Merchant merchant, String rate, long fixedMinor) {
        Actor operator = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
        try (SecurityContext.Scope acting = SecurityContext.enter(operator)) {
            com.finapp.merchant.FeeScheduleId scheduleId =
                    runner.inTransaction(
                            uow -> feeSchedules().assignmentOf(uow, merchant.id()).orElseThrow());
            runner.inTransaction(
                    uow ->
                            feeSchedules()
                                    .addVersion(
                                            uow,
                                            scheduleId,
                                            new FeeSchedules.NewVersion(
                                                    FeeRate.of(new BigDecimal(rate)),
                                                    Money.ofMinorUnits(fixedMinor, EUR),
                                                    RoundingPolicy.HALF_EVEN,
                                                    RefundFeePolicy.RETAINED,
                                                    Optional.empty(),
                                                    "repricing mid-flight")));
        }
    }

    private Payment merchantBoundPayment(Merchant merchant, Money amount) throws Exception {
        return payment(merchant.payable(), amount, Optional.of(merchant));
    }

    /**
     * A payment whose capture will credit {@code creditAccount}, with a fee pin when
     * {@code merchant} is present.
     *
     * <p>The participants port is substituted so the intent records the merchant's payable as
     * the account its capture credits — which is what a checkout-created intent will record
     * for real at {@code P6-TSK-007}. Everything else is the production path.
     */
    private Payment payment(
            LedgerAccountId creditAccount, Money amount, Optional<Merchant> merchant)
            throws Exception {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        UUID method = IDS.next();
        Actor payer = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES"
                            + " (?, 'PERSON', 'Paying Customer', now() - interval '2 hour')",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    customer,
                    party);
            execute(
                    app,
                    "INSERT INTO paymentmethods.payment_method (id, party_id, token_reference,"
                            + " brand, display_suffix, expiry_month, expiry_year, status,"
                            + " created_at) VALUES (?, ?, ?, 'Visa', '4242', 12, 2030, 'ACTIVE',"
                            + " now())",
                    method,
                    party,
                    "tok-merch-" + UUID.randomUUID());
        }

        try (SecurityContext.Scope acting = SecurityContext.enter(payer)) {
            PaymentCreation.CreationResult created =
                    runner.inTransaction(
                            uow ->
                                    new PaymentCreation(
                                                    executor(),
                                                    creditingInstead(creditAccount, customer),
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
                                                            amount,
                                                            "mp-" + UUID.randomUUID())));
            merchant.ifPresent(
                    m ->
                            runner.inTransaction(
                                    uow -> pin(uow, m, created.intent().value(), amount)));
            return new Payment(created.intent(), party, method, payer);
        }
    }

    private Void pin(Connection uow, Merchant merchant, UUID intentRef, Money gross) {
        settlement()
                .pin(
                        uow,
                        new PaymentFeePin(
                                intentRef,
                                merchant.id(),
                                merchant.versionId(),
                                gross,
                                Instant.now(CLOCK),
                                "test"));
        return null;
    }

    private UUID pinFor(Merchant merchant, Money gross) {
        UUID intentRef = IDS.next();
        runner.inTransaction(uow -> pin(uow, merchant, intentRef, gross));
        return intentRef;
    }

    private PaymentAttemptId authorizedAttempt(Payment payment) throws Exception {
        psp.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH,
                200,
                APPROVED_BODY.formatted("auth-" + UUID.randomUUID()));
        try (SecurityContext.Scope acting = SecurityContext.enter(payment.payer())) {
            PaymentConfirmation.ConfirmationResult confirmed =
                    new PaymentConfirmation(
                                    runner,
                                    intents,
                                    attempts,
                                    evidence,
                                    realParticipants,
                                    adapter(),
                                    outcomes(),
                                    new JdbcAuditWriter(),
                                    IDS,
                                    CLOCK)
                            .confirm(payment.party(), payment.intent());
            assertThat(confirmed.attempt()).contains(PaymentAttemptStatus.AUTHORIZED);
        }
        psp.reset();
        try (Connection app = DatabaseRoles.application()) {
            return attempts.findForIntent(app, payment.intent()).orElseThrow().id();
        }
    }

    private PaymentAttemptId capturedAttempt(Payment payment) throws Exception {
        PaymentAttemptId attempt = authorizedAttempt(payment);
        psp.succeedsWith(
                SimulatedCardPspAdapter.CAPTURES_PATH,
                200,
                APPROVED_BODY.formatted("cap-" + UUID.randomUUID()));
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            PaymentCapture.CaptureResult result = capture().capture(attempt);
            assertThat(result.attempt()).isEqualTo(PaymentAttemptStatus.CAPTURED);
            assertThat(result.intent()).isEqualTo(PaymentIntentStatus.SUCCEEDED);
        }
        return attempt;
    }

    // ----------------------------------------------------------------- wiring

    /**
     * The participants port with one answer substituted: the wallet this payment credits is
     * {@code creditAccount}. Instrument resolution stays real, because the token's path is not
     * this task's subject and a double there would weaken a shipped guarantee.
     */
    private PaymentParticipants<Connection> creditingInstead(
            LedgerAccountId creditAccount, UUID customer) {
        return new PaymentParticipants<>() {
            @Override
            public Optional<Wallet> walletOwnedBy(Connection uow, UUID callerPartyId) {
                return Optional.of(new Wallet(customer, creditAccount, EUR));
            }

            @Override
            public Optional<InstrumentToken> instrumentOwnedBy(
                    Connection uow, UUID callerPartyId, UUID paymentMethodId) {
                return realParticipants.instrumentOwnedBy(uow, callerPartyId, paymentMethodId);
            }
        };
    }

    private MerchantSettlement settlement() {
        return new MerchantSettlement(
                pins,
                schedules,
                ledgerAccounts,
                new ChartOfAccounts<>(ledgerAccounts),
                new JdbcOutboxWriter(),
                IDS);
    }

    /** The production seam — the same construction {@code MerchantBeans} performs. */
    private CaptureComposition<Connection> composition() {
        // No completion: this suite's payments belong to no checkout session, and the
        // production consumer is wired in CheckoutBeans (P6-TSK-007's seam).
        return new MerchantBoundCaptureComposition(
                settlement(), new WalletTopUpComposition(), landed -> {});
    }

    private com.finapp.merchant.MerchantOnboarding onboarding() {
        return new com.finapp.merchant.MerchantOnboarding(
                new JdbcMerchantStore(),
                ledgerAccounts,
                new VerifiedMerchantOrganisation(new JdbcPartyStore()),
                executor(),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK);
    }

    private FeeSchedules feeSchedules() {
        return new FeeSchedules(
                schedules, new JdbcMerchantStore(), new JdbcAuditWriter(), IDS, CLOCK);
    }

    private PaymentOutcomes outcomes() {
        return new PaymentOutcomes(
                intents,
                attempts,
                new com.finapp.payments.JdbcRefundStore(),
                new com.finapp.ledger.HoldService(
                        ledgerAccounts,
                        new JdbcBalanceDerivation(),
                        new com.finapp.ledger.JdbcHoldStore(),
                        new JdbcBalanceProjection(),
                        new JdbcAuditWriter(),
                        new JdbcOutboxWriter(),
                        IDS,
                        CLOCK),
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
                composition(),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK);
    }

    private PaymentCapture capture() {
        return new PaymentCapture(
                runner, intents, attempts, evidence, adapter(), outcomes(),
                new JdbcAuditWriter(), IDS, CLOCK);
    }

    private SimulatedCardPspAdapter adapter() {
        return new SimulatedCardPspAdapter(
                URI.create(psp.baseUrl()), Duration.ofSeconds(5), PSP_KEY);
    }

    private static IdempotentExecutor executor() {
        return new IdempotentExecutor(
                new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1),
                Duration.ofMinutes(5));
    }

    private LedgerAccountId clearing() {
        return clearingIn(EUR);
    }

    private LedgerAccountId clearingIn(CurrencyCode currency) {
        return runner.inTransaction(
                uow ->
                        new ChartOfAccounts<>(ledgerAccounts)
                                .resolve(uow, AccountPurpose.SETTLEMENT_CLEARING, currency)
                                .id());
    }

    private static Correlation correlation() {
        return CorrelationContext.current().orElseThrow();
    }

    // ----------------------------------------------------------------- reads

    /** Each line of the attempt's entry as {@code DIRECTION:PURPOSE} — never an amount. */
    private static List<String> linePurposes(Connection app, PaymentAttemptId attempt)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT line.direction, account.purpose FROM ledger.journal_line line"
                                + " JOIN ledger.journal_entry entry ON entry.id = line.entry_id"
                                + " JOIN ledger.ledger_account account ON account.id ="
                                + " line.ledger_account_id WHERE entry.reference = ?")) {
            read.setString(1, attempt.value().toString());
            try (ResultSet rows = read.executeQuery()) {
                List<String> lines = new ArrayList<>();
                while (rows.next()) {
                    lines.add(rows.getString(1) + ":" + rows.getString(2));
                }
                return lines;
            }
        }
    }

    private static long amountOn(
            Connection app, PaymentAttemptId attempt, String direction, AccountPurpose purpose)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT line.amount_minor FROM ledger.journal_line line"
                                + " JOIN ledger.journal_entry entry ON entry.id = line.entry_id"
                                + " JOIN ledger.ledger_account account ON account.id ="
                                + " line.ledger_account_id WHERE entry.reference = ?"
                                + " AND line.direction = ? AND account.purpose = ?")) {
            read.setString(1, attempt.value().toString());
            read.setString(2, direction);
            read.setString(3, purpose.name());
            try (ResultSet rows = read.executeQuery()) {
                assertThat(rows.next())
                        .as("a %s line on %s", direction, purpose)
                        .isTrue();
                return rows.getLong(1);
            }
        }
    }

    /** The payable position, derived from postings — never read from a column. */
    private Money payablePosition(Connection app, Merchant merchant) {
        LedgerAccount payable =
                ledgerAccounts
                        .findOwned(app, merchant.id().value(), AccountPurpose.MERCHANT_PAYABLE, EUR)
                        .orElseThrow();
        return new JdbcBalanceDerivation()
                .derive(app, payable.id(), AsOf.latest())
                .settled();
    }

    private static int entriesReferencing(Connection app, PaymentAttemptId attempt)
            throws SQLException {
        return (int)
                count(
                        app,
                        "SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                        attempt.value().toString());
    }

    private static long count(Connection app, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet rows = read.executeQuery()) {
                rows.next();
                return rows.getLong(1);
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

    /** One transaction on one application-role connection — the suites' established runner. */
    private static final class AppRoleRunner implements TransactionRunner {
        @Override
        public <R> R inTransaction(java.util.function.Function<Connection, R> work) {
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
