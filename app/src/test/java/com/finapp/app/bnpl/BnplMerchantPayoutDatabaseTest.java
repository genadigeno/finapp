package com.finapp.app.bnpl;

import static com.finapp.app.bnpl.BnplPurchaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.database.DateShifted;
import com.finapp.bnpl.BnplOrigination;
import com.finapp.bnpl.BnplTermsAdministration;
import com.finapp.identity.Authorization;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LoanWallets;
import com.finapp.merchant.BnplEnablements;
import com.finapp.merchant.BnplPricing;
import com.finapp.merchant.FeeSchedules;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantPayable;
import com.finapp.merchant.MerchantPayoutId;
import com.finapp.merchant.MerchantPayoutOutcomes;
import com.finapp.merchant.MerchantPayoutResolution;
import com.finapp.merchant.MerchantPayoutStatus;
import com.finapp.merchant.MerchantPayoutStore;
import com.finapp.merchant.MerchantPayoutUnfundedException;
import com.finapp.merchant.MerchantPayouts;
import com.finapp.merchant.MerchantTransactionRunner;
import com.finapp.merchant.PayoutAnswer;
import com.finapp.merchant.PayoutDestinationStore;
import com.finapp.merchant.PayoutEvidenceStore;
import com.finapp.merchant.PayoutProvider;
import com.finapp.merchant.PayoutQueryAnswer;
import com.finapp.merchant.PayoutReference;
import com.finapp.merchant.SimulatedPayoutProvider;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.id.IdGenerator;
import java.net.URI;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payouts of BNPL-financed money against a real database (`P12-TSK-016`; mandatory scenario 5; {@code INV-MER-02}
 * amended, {@code INV-MER-05}, {@code INV-BAL-04}, {@code INV-PAY-04}, {@code INV-IDEM-01}, {@code INV-BNPL-02},
 * {@code INV-BNPL-04}): payables funded by REAL acceptances (credit's decision, the origination sweep, T-pur's F1),
 * paid out through the existing on-demand payout only - the payable locked alone, the hold placed, {@code DISPATCHED}
 * committed, then the wire - and read back through the merchant's own key. The hermetic twin is
 * {@code BnplMerchantPayoutTest}; this suite owns what only a database shows: the locks, the races and the rows.
 *
 * <p><strong>The serialisation, as built</strong>: a payout and a BNPL acceptance at the same merchant meet FIRST on the
 * merchant row - the payout's dispatch takes it {@code FOR UPDATE} (a suspension serialises with it), the acceptance's
 * standing {@code FOR SHARE} - and only then on the payable's {@code ledger_account} row (the payout's hold, T-pur's
 * (P)). Both take the merchant row before the payable, so one waits for the other and none closes a cycle; the payout
 * takes no wallet, no capital row (L6) and no bnpl row.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): it plants lending capital and activates terms and
 * credit policies, as the purchase suites do. Written by `P12-TSK-016` and <strong>owed a first run</strong> (the owner's
 * no-{@code databaseTest} instruction for Phase 12).
 */
@DateShifted
@Tag("database")
@Tag("own-container")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("payouts of BNPL-financed money against the real schema (P12-TSK-016)")
class BnplMerchantPayoutDatabaseTest {

    private static final long GROSS = 600_00L;
    private static final long FEE = 24_00L; // the world's 4% schedule, no fixed part
    private static final String PATH = SimulatedPayoutProvider.PAYOUTS_PATH;
    private static final String PAID = "{\"status\":\"paid\",\"reference\":\"po-{{request.headers.Idempotency-Key}}\"}";
    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private BnplEnablements enablements;
    @Autowired private BnplPricing pricing;
    @Autowired private FeeSchedules feeSchedules;
    @Autowired private BnplTermsAdministration termsAdministration;
    @Autowired private BnplTransactions transactions;
    @Autowired private BnplOrigination origination;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private LendingCapitalHeadroom headroom;
    @Autowired private LoanWallets loanWallets;
    @Autowired private MerchantPayouts payouts;
    @Autowired private MerchantPayoutStore<Connection> payoutStore;
    @Autowired private MerchantPayoutOutcomes outcomes;
    @Autowired private PayoutEvidenceStore<Connection> evidence;
    @Autowired private PayoutDestinationStore<Connection> destinations;
    @Autowired private HoldService holds;
    @Autowired private IdempotentExecutor idempotentExecutor;
    @Autowired private AuditWriter<Connection> auditWriter;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @Autowired private Clock clock;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private BnplPurchaseWorld world;

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
        registry.add("finapp.merchant.payout.provider.url", () -> provider.baseUrl());
        registry.add("finapp.merchant.payout.provider.timeout", () -> "PT0.7S");
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add("finapp.payments.provider.timeout", () -> "PT0.7S");
    }

    @BeforeEach
    void world() throws Exception {
        provider.reset();
        world = new BnplPurchaseWorld(port, authorization, enablements, pricing, feeSchedules, termsAdministration,
                transactions, origination, postingService, ledgerAccountStore, headroom, loanWallets).ready();
        world.headroomAtLeast(eur(1_000_000_00L));
    }

    // ------------------------------------------------------------------ scenario 5

    @Test
    @DisplayName("scenario 5: the scheme executes the send and the answer is lost - merchant_payout UNKNOWN, its hold"
            + " standing; the merchant's retry under the same merchant.payout:<merchantId> key replays the recorded"
            + " answer and dispatches nothing; the resolution's query concludes COMPLETED and posts DR MERCHANT_PAYABLE /"
            + " CR PAYOUT_CLEARING once; one payout at the provider, the dispatch counted equal on both sides; the"
            + " agreement, obligation and financing untouched")
    void aLostPayoutResponseIsHeldAndTheRetryReplays() throws Exception {
        Shop shop = shop();
        String purchase = financed(shop);
        String untouched = bnplFootprint(purchase);
        provider.receivesTheRequestThenLosesTheResponse(PATH);
        Counting wire = counting();
        MerchantPayouts counted = payoutsSendingTo(wire);
        String key = BnplApplicationClient.key();

        MerchantPayouts.Initiated lost = initiate(counted, shop, 500_00L, key);
        assertThat(lost.status()).isEqualTo(MerchantPayoutStatus.UNKNOWN);
        assertThat(holdStatuses(shop)).containsExactly("ACTIVE");
        assertThat(positionMinor(shop)).as("a hold is not a posting").isEqualTo(GROSS - FEE);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.idempotency_record WHERE scope = ?"
                + " AND idempotency_key = ?", MerchantPayouts.IDEMPOTENCY_SCOPE_PREFIX + shop.id().value(), key)).isEqualTo(1);

        for (int retry = 0; retry < 3; retry++) {
            MerchantPayouts.Initiated replayed = initiate(counted, shop, 500_00L, key);
            assertThat(replayed.replayed()).isTrue();
            assertThat(replayed.status()).isEqualTo(MerchantPayoutStatus.UNKNOWN);
            assertThat(replayed.payout()).isEqualTo(lost.payout());
        }
        assertCountedOnBothSides(shop, wire, 1);

        queryAnswers(lost.payout(), "paid");
        assertThat(resolver(wire).sweep().actingJudgements()).containsExactly(MerchantPayoutStatus.COMPLETED);
        assertThat(resolver(wire).sweep().resolved()).isZero();
        assertThat(payoutStatus(lost.payout())).isEqualTo("COMPLETED");
        assertThat(holdStatuses(shop)).containsExactly("RELEASED");
        assertThat(entryLines(MerchantPayoutOutcomes.POSTING_KEY_PREFIX + lost.payout().value()))
                .containsExactlyInAnyOrder("DEBIT:MERCHANT_PAYABLE:50000", "CREDIT:PAYOUT_CLEARING:50000");
        assertThat(positionMinor(shop)).isEqualTo(GROSS - FEE - 500_00L);
        assertCountedOnBothSides(shop, wire, 1);
        assertThat(bnplFootprint(purchase)).as("the agreement, obligation and financing untouched").isEqualTo(untouched);
    }

    // ------------------------------------------------------------------ the bound

    @Test
    @DisplayName("a payout of financed money is bounded by the payable: the payable to the minor unit and not one"
            + " more, a refusal writes nothing; then two financings and the bound moves with each")
    void aPayoutOfFinancedMoneyIsBoundedByThePayable() throws Exception {
        Shop shop = shop();
        financed(shop);
        provider.succeedsWith(PATH, 200, PAID);
        assertThatThrownBy(() -> initiate(payouts, shop, GROSS - FEE + 1, BnplApplicationClient.key()))
                .isInstanceOf(MerchantPayoutUnfundedException.class);
        assertThat(payoutRows(shop)).isZero();
        assertThat(holdStatuses(shop)).isEmpty();
        assertThat(initiate(payouts, shop, GROSS - FEE, BnplApplicationClient.key()).status())
                .isEqualTo(MerchantPayoutStatus.COMPLETED);
        assertThat(positionMinor(shop)).isZero();
        financed(shop);
        financed(shop);
        assertThatThrownBy(() -> initiate(payouts, shop, 2 * (GROSS - FEE) + 1, BnplApplicationClient.key()))
                .isInstanceOf(MerchantPayoutUnfundedException.class);
        assertThat(initiate(payouts, shop, 2 * (GROSS - FEE), BnplApplicationClient.key()).status())
                .isEqualTo(MerchantPayoutStatus.COMPLETED);
        assertThat(positionMinor(shop)).isZero();
        assertThat(provider.requestCount(PATH)).isEqualTo(2);
    }

    @Test
    @DisplayName("a payout racing a BNPL acceptance on one payable is serialised, both orders: the acceptance first -"
            + " the payout waits on the merchant row behind it and then may take the financing; the payout first - the"
            + " acceptance waits on the merchant row behind the dispatch, and the payout is bounded by what it found;"
            + " no deadlock either way, counted")
    void aPayoutRacingAFinancingIsSerialised() throws Exception {
        provider.succeedsWith(PATH, 200, PAID);

        // The acceptance first: held inside (P) - its payable row is held here - having taken the merchant row FOR SHARE.
        Shop first = shop();
        Offered offer = offerAt(first);
        Connection payable = BnplPurchaseWorld.held();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            lock(payable, "SELECT 1 FROM ledger.ledger_account WHERE id = '" + first.payable().value() + "' FOR UPDATE");
            Future<HttpResponse<String>> accepting = pool.submit(() -> world.accept(offer.customer().token(),
                    offer.offer(), BnplApplicationClient.key()));
            int acceptance = awaitPidBlockedBy(pid(payable));
            Future<MerchantPayouts.Initiated> paying = pool.submit(() ->
                    initiate(payouts, first, GROSS - FEE, BnplApplicationClient.key()));
            assertThat(awaitStatementBlockedBy(acceptance)).as("the payout waits on the merchant row the acceptance"
                    + " shares").contains("merchant");
            payable.rollback();
            assertThat(accepting.get(60, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
            assertThat(paying.get(60, TimeUnit.SECONDS).status()).as("it found the financing").isEqualTo(
                    MerchantPayoutStatus.COMPLETED);
        } finally {
            BnplPurchaseWorld.closeQuietly(payable);
        }

        // The payout first: held at its hold - the payable row is held here - having taken the merchant row FOR UPDATE.
        Shop second = shop();
        Offered later = offerAt(second);
        Connection hold = BnplPurchaseWorld.held();
        try {
            lock(hold, "SELECT 1 FROM ledger.ledger_account WHERE id = '" + second.payable().value() + "' FOR UPDATE");
            Future<Object> paying = pool.submit(() -> {
                try {
                    return initiate(payouts, second, GROSS - FEE, BnplApplicationClient.key());
                } catch (MerchantPayoutUnfundedException refused) {
                    return refused;
                }
            });
            int payout = awaitPidBlockedBy(pid(hold));
            Future<HttpResponse<String>> accepting = pool.submit(() -> world.accept(later.customer().token(),
                    later.offer(), BnplApplicationClient.key()));
            assertThat(awaitStatementBlockedBy(payout)).as("the acceptance waits on the merchant row the dispatch holds")
                    .contains("merchant");
            hold.rollback();
            assertThat(paying.get(60, TimeUnit.SECONDS)).as("bounded by what it found: nothing yet")
                    .isInstanceOf(MerchantPayoutUnfundedException.class);
            assertThat(accepting.get(60, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        } finally {
            pool.shutdownNow();
            BnplPurchaseWorld.closeQuietly(hold);
        }
        assertThat(positionMinor(first)).isZero();
        assertThat(positionMinor(second)).isEqualTo(GROSS - FEE);
        assertThat(payoutRows(first)).isEqualTo(1);
        assertThat(payoutRows(second)).isZero();
        assertThat(provider.requestCount(PATH)).isEqualTo(1);
    }

    @Test
    @DisplayName("a payout racing a BNPL refund on one payable is serialised, both orders, counted: the refund first -"
            + " the payout queues behind it on the payable and is bounded by what it found (the net less the refund's"
            + " debit), refused; the payout first - the refund queues behind it, the payout takes the whole net and the"
            + " refund then leaves the payable below zero by its debit (B7, within the net), counted by"
            + " finapp.ledger.negative.positions; the position INV-MER-02's terms either way, INV-MER-05 held")
    void aPayoutRacingABnplRefundIsSerialised() throws Exception {
        provider.succeedsWith(PATH, 200, PAID);
        String body = "{\"amount\":\"200.00\",\"currency\":\"EUR\",\"reason\":\"goods returned\"}";
        long refundDebit = 200_00L - 8_00L; // the refund less the fee returned pro rata (4% of 200.00)
        int[] counted = new int[2]; // [refund first, payout first]

        // The refund first: held at (P) - the payable row held here - the payout queued behind it.
        Shop first = shop();
        String firstPurchase = financed(first);
        String firstKey = refundScopedKey(first);
        Connection payable = BnplPurchaseWorld.held();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            lock(payable, "SELECT 1 FROM ledger.ledger_account WHERE id = '" + first.payable().value() + "' FOR UPDATE");
            Future<HttpResponse<String>> refunding = pool.submit(() -> world.client.post("/v1/merchant/bnpl-purchases/"
                    + firstPurchase + "/refunds", body, firstKey, BnplApplicationClient.key()));
            int refund = awaitPidBlockedBy(pid(payable));
            Future<Object> paying = pool.submit(() -> {
                try {
                    return initiate(payouts, first, GROSS - FEE, BnplApplicationClient.key());
                } catch (MerchantPayoutUnfundedException refused) {
                    return refused;
                }
            });
            assertThat(awaitStatementBlockedBy(refund)).as("the payout queues behind the refund on the payable")
                    .contains("ledger_account");
            payable.rollback();
            HttpResponse<String> refunded = refunding.get(60, TimeUnit.SECONDS);
            assertThat(refunded.statusCode()).as(refunded.body()).isEqualTo(201);
            assertThat(paying.get(60, TimeUnit.SECONDS)).as("bounded by what it found: the net less the refund")
                    .isInstanceOf(MerchantPayoutUnfundedException.class);
            counted[0]++;
        } finally {
            BnplPurchaseWorld.closeQuietly(payable);
        }
        assertThat(positionMinor(first)).isEqualTo(GROSS - FEE - refundDebit);
        assertThat(BnplPurchaseWorld.creditBalanceOf(first.payable().value())).isEqualTo(GROSS - FEE - refundDebit);
        assertThat(payoutRows(first)).isZero();
        assertThat(initiate(payouts, first, GROSS - FEE - refundDebit, BnplApplicationClient.key()).status())
                .as("what it found is paid out exactly").isEqualTo(MerchantPayoutStatus.COMPLETED);
        assertThat(positionMinor(first)).isZero();

        // The payout first: held at its hold - the payable row held here - the refund queued behind it.
        Shop second = shop();
        String secondPurchase = financed(second);
        String secondKey = refundScopedKey(second);
        long negativeBefore = negativePayables();
        assertThat(negativePayablesGauge(negativeBefore)).as("the gauge agrees with the SQL before").isEqualTo(
                (double) negativeBefore);
        Connection hold = BnplPurchaseWorld.held();
        try {
            lock(hold, "SELECT 1 FROM ledger.ledger_account WHERE id = '" + second.payable().value() + "' FOR UPDATE");
            Future<Object> paying = pool.submit(() -> initiate(payouts, second, GROSS - FEE,
                    BnplApplicationClient.key()));
            int payout = awaitPidBlockedBy(pid(hold));
            Future<HttpResponse<String>> refunding = pool.submit(() -> world.client.post("/v1/merchant/bnpl-purchases/"
                    + secondPurchase + "/refunds", body, secondKey, BnplApplicationClient.key()));
            assertThat(awaitStatementBlockedBy(payout)).as("the refund queues behind the payout on the payable")
                    .contains("ledger_account");
            hold.rollback();
            assertThat(((MerchantPayouts.Initiated) paying.get(60, TimeUnit.SECONDS)).status())
                    .as("it found the whole net").isEqualTo(MerchantPayoutStatus.COMPLETED);
            HttpResponse<String> refunded = refunding.get(60, TimeUnit.SECONDS);
            assertThat(refunded.statusCode()).as(refunded.body()).isEqualTo(201);
            counted[1]++;
        } finally {
            pool.shutdownNow();
            BnplPurchaseWorld.closeQuietly(hold);
        }
        assertThat(positionMinor(second)).as("INV-MER-02: financed - fee - payout - refund + fee returned")
                .isEqualTo(GROSS - FEE - (GROSS - FEE) - refundDebit);
        assertThat(BnplPurchaseWorld.creditBalanceOf(second.payable().value())).isEqualTo(-refundDebit);
        assertThat(BnplApplicationClient.count("SELECT coalesce(sum(amount_minor), 0) FROM merchant.merchant_payout WHERE"
                + " merchant_id = ?::uuid AND status = 'COMPLETED'", second.id().value().toString()))
                .as("INV-MER-05: paid out no more than the payable it found").isEqualTo(GROSS - FEE);
        assertThatThrownBy(() -> initiate(payouts, second, 1L, BnplApplicationClient.key()))
                .as("a payable below zero refuses every payout").isInstanceOf(MerchantPayoutUnfundedException.class);
        assertThat(negativePayables()).as("one more payable below zero, by independent SQL").isEqualTo(negativeBefore + 1);
        assertThat(negativePayablesGauge(negativeBefore + 1)).as("the gauge counts the payable the refund drove below"
                + " zero").isEqualTo((double) (negativeBefore + 1));
        assertThat(counted).as("both orders, counted").containsExactly(1, 1);
        assertThat(provider.requestCount(PATH)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ once

    @Test
    @DisplayName("ten retries of one key on ten instances: one dispatch - one merchant_payout row, one send by the"
            + " adapter, one request at the scheme, one posting; every answer that payout's")
    void tenRetriesOneDispatch() throws Exception {
        Shop shop = shop();
        financed(shop);
        provider.succeedsWith(PATH, 200, PAID);
        Counting wire = counting();
        MerchantPayouts counted = payoutsSendingTo(wire);
        String key = BnplApplicationClient.key();
        List<Callable<Object>> retries = new ArrayList<>();
        for (int instance = 0; instance < 10; instance++) {
            retries.add(() -> {
                try {
                    return initiate(counted, shop, 300_00L, key);
                } catch (RuntimeException inProgress) {
                    return inProgress; // a sibling's claim still open: the honest 409, never a second dispatch
                }
            });
        }
        List<Object> answers = atOnce(retries);
        assertThat(answers).filteredOn(MerchantPayouts.Initiated.class::isInstance).isNotEmpty();
        assertThat(answers).filteredOn(MerchantPayouts.Initiated.class::isInstance)
                .extracting(answer -> ((MerchantPayouts.Initiated) answer).payout()).containsOnly(
                        answers.stream().filter(MerchantPayouts.Initiated.class::isInstance)
                                .map(answer -> ((MerchantPayouts.Initiated) answer).payout()).findFirst().orElseThrow());
        assertCountedOnBothSides(shop, wire, 1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope LIKE"
                + " 'ledger.post:merchant-payout:%' AND reference IN (SELECT id::text FROM merchant.merchant_payout"
                + " WHERE merchant_id = ?::uuid)", shop.id().value().toString())).isEqualTo(1);
    }

    @Test
    @DisplayName("two resolvers conclude once: an UNKNOWN payout swept by two instances at once - one acting"
            + " COMPLETED, one posting, one completion event")
    void twoResolversConcludeOnce() throws Exception {
        Shop shop = shop();
        financed(shop);
        provider.receivesTheRequestThenLosesTheResponse(PATH);
        Counting wire = counting();
        MerchantPayouts.Initiated lost = initiate(payoutsSendingTo(wire), shop, 400_00L, BnplApplicationClient.key());
        queryAnswers(lost.payout(), "paid");
        List<Object> swept = atOnce(List.of(() -> resolver(wire).sweep(), () -> resolver(wire).sweep()));
        assertThat(swept.stream().map(MerchantPayoutResolution.SweepResult.class::cast)
                .flatMap(result -> result.actingJudgements().stream())).containsExactly(MerchantPayoutStatus.COMPLETED);
        assertThat(entryLines(MerchantPayoutOutcomes.POSTING_KEY_PREFIX + lost.payout().value())).hasSize(2);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'merchant.MerchantPayoutCompleted' AND aggregate_id = ?::uuid", lost.payout().value().toString()))
                .isEqualTo(1);
        assertCountedOnBothSides(shop, wire, 1);
    }

    @Test
    @DisplayName("a failed payout releases its hold: declined - FAILED, the hold released, nothing posted, the"
            + " financed payable whole and paid out in full next")
    void aFailedPayoutReleasesItsHold() throws Exception {
        Shop shop = shop();
        financed(shop);
        provider.succeedsWith(PATH, 200, "{\"status\":\"declined\"}");
        MerchantPayouts.Initiated declined = initiate(payouts, shop, 500_00L, BnplApplicationClient.key());
        assertThat(declined.status()).isEqualTo(MerchantPayoutStatus.FAILED);
        assertThat(holdStatuses(shop)).containsExactly("RELEASED");
        assertThat(entryLines(MerchantPayoutOutcomes.POSTING_KEY_PREFIX + declined.payout().value())).isEmpty();
        provider.reset();
        provider.succeedsWith(PATH, 200, PAID);
        assertThat(initiate(payouts, shop, GROSS - FEE, BnplApplicationClient.key()).status())
                .isEqualTo(MerchantPayoutStatus.COMPLETED);
        assertThat(positionMinor(shop)).isZero();
    }

    // ------------------------------------------------------------------ the merchant's reads

    @Test
    @DisplayName("the merchant's own key reads the BNPL terms: the payable's bnplFinanced/bnplFees and the"
            + " transactions' BNPL_FINANCING with the purchase; the owner-role fixture under a bnpl.refund: key whose"
            + " excess credits a wallet reads BNPL_REFUND and bnplRefunded, never REFUND; another merchant's key sees"
            + " none of it and no key is 401")
    void bnplFinancingAndRefundKindsAreListed() throws Exception {
        Shop shop = shop();
        String purchase = financed(shop);
        UUID refund = refundFixture(shop, purchase, 200_00L, 50_00L, 8_00L);
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));

        HttpResponse<String> payable = world.client.get("/v1/merchant/payable", shop.merchant().apiKey());
        assertThat(payable.statusCode()).as(payable.body()).isEqualTo(200);
        assertThat(payable.body()).contains("\"bnplFinanced\":\"600.00\"", "\"bnplFees\":\"24.00\"",
                "\"bnplRefunded\":\"200.00\"", "\"bnplFeesReturned\":\"8.00\"", "\"refunded\":\"0.00\"",
                "\"position\":\"" + "384.00" + "\"");
        HttpResponse<String> listed = world.client.get("/v1/merchant/transactions?from=" + today + "&to=" + today,
                shop.merchant().apiKey());
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        // The fixture is a raw posting under bnpl.refund:<id> with no bnpl refund row behind it, so P12-TSK-021's second
        // rank - the refund named only when bnpl's merchant-scoped read confirms it - renders it BNPL_REFUND WITHOUT its
        // identifier (P12-TSK-028; the confirmed id through a real refund is BnplRefundApiTest#anotherMerchantsReport...).
        assertThat(listed.body()).contains("\"kind\":\"BNPL_FINANCING\"", "\"purchaseId\":\"" + purchase + "\"",
                "\"kind\":\"BNPL_REFUND\"").doesNotContain(refund.toString()).doesNotContain("\"kind\":\"REFUND\"");

        Shop other = shop();
        HttpResponse<String> theirs = world.client.get("/v1/merchant/transactions?from=" + today + "&to=" + today,
                other.merchant().apiKey());
        assertThat(theirs.body()).doesNotContain(purchase).doesNotContain(refund.toString());
        assertThat(world.client.get("/v1/merchant/payable", other.merchant().apiKey()).body())
                .contains("\"bnplFinanced\":\"0.00\"");
        assertThat(world.client.get("/v1/merchant/payable", null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("INV-MER-02 with BNPL lines: the drill-down's terms equal the payable reconciled by independent SQL"
            + " over the financing, the payout and the refund rows")
    void thePayableReconcilesWithIndependentSql() throws Exception {
        Shop shop = shop();
        String purchase = financed(shop);
        provider.succeedsWith(PATH, 200, PAID);
        initiate(payouts, shop, 100_00L, BnplApplicationClient.key());
        refundFixture(shop, purchase, 50_00L, 0L, 2_00L);
        long financedNet = BnplApplicationClient.count("SELECT gross_minor - fee_minor FROM bnpl.merchant_financing"
                + " WHERE purchase_id = ?::uuid", purchase);
        long paidOut = BnplApplicationClient.count("SELECT coalesce(sum(amount_minor), 0) FROM merchant.merchant_payout"
                + " WHERE merchant_id = ?::uuid AND status = 'COMPLETED'", shop.id().value().toString());
        long independent = financedNet - paidOut - 50_00L + 2_00L;
        assertThat(positionMinor(shop)).isEqualTo(independent);
        assertThat(BnplPurchaseWorld.creditBalanceOf(shop.payable().value())).isEqualTo(independent);
    }

    // ------------------------------------------------------------------ fixtures

    private record Shop(BnplApplicationClient.Merchant merchant, MerchantId id, LedgerAccountId payable) {}

    private record Offered(CreditFixtures.Customer customer, BnplPurchaseWorld.Offer offer) {}

    /** A BNPL-enabled trading merchant with an effective payout destination. */
    private Shop shop() throws Exception {
        BnplApplicationClient.Merchant merchant = world.merchant();
        try (Connection owner = DatabaseRoles.migrator()) {
            BnplApplicationClient.execute(owner,
                    "INSERT INTO merchant.payout_destination (id, merchant_id, destination_reference, display_suffix,"
                            + " status, proposed_by, proposed_at, proposal_reason, approved_by, approved_at,"
                            + " cooling_off_until, effective_at) VALUES (?, ?::uuid, ?, '0016', 'EFFECTIVE', 'fixture-a',"
                            + " now() - interval '4 days', 'fixture', 'fixture-b', now() - interval '4 days',"
                            + " now() - interval '1 day', now() - interval '1 hour')",
                    IDS.next(), merchant.id(), "pdr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        }
        return new Shop(merchant, MerchantId.of(UUID.fromString(merchant.id())),
                LedgerAccountId.of(BnplPurchaseWorld.payableOf(merchant)));
    }

    private Offered offerAt(Shop shop) throws Exception {
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        return new Offered(customer, world.offered(customer, shop.merchant(), GROSS,
                token -> BnplApplicationClient.payInN(token, 3)));
    }

    /** A pay-in-N purchase accepted at the merchant: F1 credits its payable G and debits the fee. Its purchase id. */
    private String financed(Shop shop) throws Exception {
        Offered offered = offerAt(shop);
        HttpResponse<String> accepted = world.accept(offered.customer().token(), offered.offer(),
                BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        return BnplApplicationClient.field(accepted.body(), "purchaseId");
    }

    /**
     * The owner-role journal fixture standing in for F5 until `P12-TSK-021` (the backlog's own words): {@code DR
     * MERCHANT_PAYABLE R / CR BNPL_PRINCIPAL (R - E); CR CUSTOMER_WALLET E}, the fee returned {@code DR
     * BNPL_MERCHANT_FEE_INCOME r / CR MERCHANT_PAYABLE r}, under {@code bnpl.refund:<refund>} through the ledger's
     * PostingService.
     */
    private UUID refundFixture(Shop shop, String purchase, long refundMinor, long excessMinor, long feeMinor)
            throws Exception {
        UUID refund = IDS.next();
        LedgerAccountId principal = LedgerAccountId.of(UUID.fromString(BnplApplicationClient.scalar(
                "SELECT l.ledger_account_id::text FROM ledger.journal_line l JOIN ledger.journal_entry e ON e.id ="
                        + " l.entry_id JOIN ledger.ledger_account a ON a.id = l.ledger_account_id WHERE"
                        + " e.idempotency_scope = ? AND a.purpose = 'BNPL_PRINCIPAL'",
                "ledger.post:" + MerchantPayable.BNPL_FINANCING_NAMESPACE + purchase)));
        CreditFixtures.Customer someone = world.customers.consentingCustomer();
        LedgerAccountId wallet = world.walletOf(someone.party());
        List<JournalLine> lines = new ArrayList<>();
        lines.add(new JournalLine(shop.payable(), Direction.DEBIT, eur(refundMinor)));
        lines.add(new JournalLine(principal, Direction.CREDIT, eur(refundMinor - excessMinor)));
        if (excessMinor > 0) {
            lines.add(new JournalLine(wallet, Direction.CREDIT, eur(excessMinor)));
        }
        try (SecurityContext.Scope system = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(BnplPurchaseWorld.flow())) {
            transactions.inTransaction(uow -> {
                LedgerAccountId income = ledgerAccountStore.findOperational(uow, AccountPurpose.BNPL_MERCHANT_FEE_INCOME,
                        BnplPurchaseWorld.EUR).orElseThrow().id();
                List<JournalLine> all = new ArrayList<>(lines);
                all.add(new JournalLine(income, Direction.DEBIT, eur(feeMinor)));
                all.add(new JournalLine(shop.payable(), Direction.CREDIT, eur(feeMinor)));
                LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
                return postingService.post(uow, new PostingCommand(MerchantPayable.BNPL_REFUND_NAMESPACE + refund, today,
                        today, refund.toString(), all));
            });
        }
        return refund;
    }

    /** A key issued to the shop with the BNPL_REFUND scope, by the operator who administers its keys (the refund idiom). */
    private String refundScopedKey(Shop shop) throws Exception {
        // MERCHANT_ADMINISTER beside PAYMENT_REFUND (the Phase 12 -> 13 gate's A5-F1).
        String operator = world.client.refundKeyIssuerSession(authorization);
        HttpResponse<String> issued = world.client.post("/v1/operator/merchants/" + shop.merchant().id()
                + "/api-keys?scope=BNPL_REFUND", null, operator, BnplApplicationClient.key());
        assertThat(issued.statusCode()).as(issued.body()).isEqualTo(201);
        return BnplApplicationClient.field(issued.body(), "keyId") + "." + BnplApplicationClient.field(issued.body(),
                "secret");
    }

    /** MERCHANT_PAYABLE accounts below zero by their journal lines - independent SQL, not the balance projection. */
    private static long negativePayables() throws Exception {
        return BnplApplicationClient.count("SELECT count(*) FROM (SELECT a.id FROM ledger.ledger_account a JOIN"
                + " ledger.journal_line l ON l.ledger_account_id = a.id WHERE a.purpose = 'MERCHANT_PAYABLE' GROUP BY a.id"
                + " HAVING sum(CASE l.direction WHEN 'CREDIT' THEN l.amount_minor ELSE -l.amount_minor END) < 0) below");
    }

    /**
     * {@code finapp.ledger.negative.positions{purpose=MERCHANT_PAYABLE}} as the running instance publishes it - awaited
     * (20 s) to read {@code expected}, past its 5 s refresh floor; the last reading.
     */
    private double negativePayablesGauge(long expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        double reading = Double.NaN;
        while (System.nanoTime() < deadline) {
            io.micrometer.core.instrument.Gauge gauge = meterRegistry.find("finapp.ledger.negative.positions")
                    .tag("purpose", "MERCHANT_PAYABLE").gauge();
            assertThat(gauge).as("published eagerly by a running instance").isNotNull();
            reading = gauge.value();
            if (reading == expected) {
                return reading;
            }
            Thread.sleep(250);
        }
        return reading;
    }

    /** The bnpl rows a payout must never touch: the agreement's versions, the obligation, the financing, its entry. */
    private static String bnplFootprint(String purchase) throws Exception {
        return BnplApplicationClient.scalar("SELECT (SELECT count(*) FROM bnpl.bnpl_agreement v JOIN"
                + " bnpl.bnpl_obligation o ON o.id = v.obligation_id WHERE o.purchase_id = ?::uuid) || '|' ||"
                + " (SELECT string_agg(status, ',') FROM bnpl.bnpl_obligation WHERE purchase_id = ?::uuid) || '|' ||"
                + " (SELECT gross_minor || ':' || fee_minor || ':' || journal_entry_id FROM bnpl.merchant_financing WHERE"
                + " purchase_id = ?::uuid) || '|' || (SELECT count(*) FROM ledger.journal_line l JOIN"
                + " ledger.journal_entry e ON e.id = l.entry_id WHERE e.reference = ?)", purchase, purchase, purchase,
                purchase);
    }

    /** The adapter's send calls, counted - the platform's side of the dispatch count. */
    private static final class Counting implements PayoutProvider {

        final AtomicInteger sends = new AtomicInteger();
        private final PayoutProvider delegate;

        Counting(PayoutProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public PayoutAnswer dispatch(PayoutRequest request) {
            sends.incrementAndGet();
            return delegate.dispatch(request);
        }

        @Override
        public PayoutQueryAnswer query(PayoutReference ourReference) {
            return delegate.query(ourReference);
        }
    }

    private static Counting counting() {
        return new Counting(new SimulatedPayoutProvider(URI.create(provider.baseUrl()), Duration.ofMillis(700),
                new byte[32]));
    }

    private MerchantTransactionRunner runner() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return new MerchantTransactionRunner() {
            @Override
            public <R> R inTransaction(java.util.function.Function<Connection, R> work) {
                return template.execute(status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return work.apply(unitOfWork);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
            }
        };
    }

    /** The application's payout composition with the counting wire - one more instance of it. */
    private MerchantPayouts payoutsSendingTo(PayoutProvider wire) {
        return new MerchantPayouts(runner(), idempotentExecutor, new com.finapp.merchant.JdbcMerchantStore(),
                destinations, ledgerAccountStore, holds, payoutStore, wire, outcomes, evidence, auditWriter, IDS, clock);
    }

    private MerchantPayoutResolution resolver(PayoutProvider wire) {
        return new MerchantPayoutResolution(runner(), payoutStore, wire, outcomes, evidence, IDS, clock,
                Duration.ofSeconds(30), Duration.ZERO, 1000);
    }

    /** The merchant's own key asking, as the merchant route establishes it. */
    private static MerchantPayouts.Initiated initiate(MerchantPayouts command, Shop shop, long amountMinor, String key) {
        try (SecurityContext.Scope merchant = SecurityContext.enter(new Actor(shop.id().value().toString(),
                        ActorType.MERCHANT));
                CorrelationContext.Scope flow = CorrelationContext.enter(BnplPurchaseWorld.flow())) {
            return command.initiate(new MerchantPayouts.InitiateCommand(shop.id(), eur(amountMinor), key,
                    Optional.empty(), Optional.empty()));
        }
    }

    private static void queryAnswers(MerchantPayoutId payout, String word) throws Exception {
        String reference = BnplApplicationClient.scalar("SELECT provider_idempotency_reference FROM merchant.merchant_payout WHERE id ="
                + " ?::uuid", payout.value().toString());
        provider.succeedsWith(PATH + "/" + reference, 200, word.equals("paid")
                ? "{\"status\":\"paid\",\"reference\":\"po-q-" + reference + "\"}"
                : "{\"status\":\"" + word + "\"}");
    }

    private void assertCountedOnBothSides(Shop shop, Counting wire, int expected) throws Exception {
        assertThat(payoutRows(shop)).as("merchant_payout rows").isEqualTo(expected);
        assertThat(wire.sends.get()).as("the adapter's send calls").isEqualTo(expected);
        assertThat(provider.awaitRequestCount(PATH, expected, Duration.ofSeconds(10))).as("the scheme's received sends")
                .isEqualTo(expected);
    }

    private static long payoutRows(Shop shop) throws Exception {
        return BnplApplicationClient.count("SELECT count(*) FROM merchant.merchant_payout WHERE merchant_id = ?::uuid",
                shop.id().value().toString());
    }

    private static String payoutStatus(MerchantPayoutId payout) throws Exception {
        return BnplApplicationClient.scalar("SELECT status FROM merchant.merchant_payout WHERE id = ?::uuid",
                payout.value().toString());
    }

    private static List<String> holdStatuses(Shop shop) throws Exception {
        List<String> statuses = new ArrayList<>();
        try (Connection owner = DatabaseRoles.migrator(); Statement read = owner.createStatement();
                ResultSet rows = read.executeQuery("SELECT status FROM ledger.hold WHERE ledger_account_id = '"
                        + shop.payable().value() + "' ORDER BY placed_at")) {
            while (rows.next()) {
                statuses.add(rows.getString(1));
            }
        }
        return statuses;
    }

    private static List<String> entryLines(String key) throws Exception {
        List<String> lines = new ArrayList<>();
        try (Connection owner = DatabaseRoles.migrator(); Statement read = owner.createStatement();
                ResultSet rows = read.executeQuery("SELECT l.direction || ':' || a.purpose || ':' || l.amount_minor"
                        + " FROM ledger.journal_line l JOIN ledger.journal_entry e ON e.id = l.entry_id"
                        + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                        + " WHERE e.idempotency_scope = 'ledger.post:" + key + "'")) {
            while (rows.next()) {
                lines.add(rows.getString(1));
            }
        }
        return lines;
    }

    /** The payable's position as the merchant's drill-down derives it, through the merchant key's read. */
    private long positionMinor(Shop shop) throws Exception {
        HttpResponse<String> read = world.client.get("/v1/merchant/payable", shop.merchant().apiKey());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        return new java.math.BigDecimal(BnplApplicationClient.field(read.body(), "position")).movePointRight(2)
                .longValueExact();
    }

    private static void lock(Connection held, String sql) throws SQLException {
        try (Statement lock = held.createStatement()) {
            lock.execute(sql);
        }
    }

    private static <T> List<T> atOnce(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static int pid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT pg_backend_pid()")) {
            row.next();
            return row.getInt(1);
        }
    }

    private static String awaitStatementBlockedBy(int blocker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try (Connection observer = DatabaseRoles.application(); Statement statement = observer.createStatement();
                    ResultSet row = statement.executeQuery("SELECT query FROM pg_stat_activity WHERE " + blocker
                            + " = ANY (pg_blocking_pids(pid)) AND state = 'active' AND wait_event_type = 'Lock'")) {
                if (row.next()) {
                    return row.getString(1);
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("nothing ever waited behind session " + blocker);
    }

    private static int awaitPidBlockedBy(int blocker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try (Connection observer = DatabaseRoles.application(); Statement statement = observer.createStatement();
                    ResultSet row = statement.executeQuery("SELECT pid FROM pg_stat_activity WHERE " + blocker
                            + " = ANY (pg_blocking_pids(pid)) AND state = 'active' AND wait_event_type = 'Lock'")) {
                if (row.next()) {
                    return row.getInt(1);
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("nothing ever waited behind session " + blocker);
    }
}
