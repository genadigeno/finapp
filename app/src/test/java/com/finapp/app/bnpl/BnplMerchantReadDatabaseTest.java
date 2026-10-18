package com.finapp.app.bnpl;

import static com.finapp.app.bnpl.BnplPurchaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.bnpl.BnplOrigination;
import com.finapp.bnpl.BnplPurchaseId;
import com.finapp.bnpl.BnplPurchaseStore;
import com.finapp.bnpl.BnplTermsAdministration;
import com.finapp.bnpl.MerchantBnplPurchaseView;
import com.finapp.identity.Authorization;
import com.finapp.identity.RoleName;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingService;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LoanWallets;
import com.finapp.merchant.BnplEnablements;
import com.finapp.merchant.BnplPricing;
import com.finapp.merchant.FeeRate;
import com.finapp.merchant.FeeScheduleId;
import com.finapp.merchant.FeeSchedules;
import com.finapp.merchant.RefundFeePolicy;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The merchant's BNPL purchase read over HTTP and a real database (`P12-TSK-014`; {@code PHASE_12_PLAN.md} sections 9 and
 * 11; {@code INV-MER-01}, {@code INV-MER-03}, {@code INV-BNPL-02}, {@code INV-BNPL-04}, {@code INV-CRD-04}, ADR-0031,
 * ADR-0052): a merchant reads its own purchase - figures equal to the financing entry F1's payable lines, the fee and
 * version the purchase's pin even after a new schedule version is in force; another merchant's purchase is the same
 * {@code 404} as an unknown one, byte for byte; a suspended merchant's key and a revoked key read nothing; no credit datum
 * of the customer's reaches the response; and ten readers racing the acceptance's commit each see nothing or the whole.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): it brings credit's BNPL policies and terms into force
 * ({@link BnplPurchaseWorld}). Written by `P12-TSK-014` and <strong>owed a first run</strong> (the owner's
 * no-{@code databaseTest} instruction).
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the merchant's BNPL purchase read (P12-TSK-014)")
class BnplMerchantReadDatabaseTest {

    static final String PURCHASES = "/v1/merchant/bnpl-purchases";
    private static final long GROSS = 600_00L;
    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private BnplEnablements enablements;
    @Autowired private BnplPricing pricing;
    @Autowired private FeeSchedules feeSchedules;
    @Autowired private BnplTermsAdministration terms;
    @Autowired private BnplTransactions transactions;
    @Autowired private BnplOrigination origination;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private LendingCapitalHeadroom headroom;
    @Autowired private LoanWallets loanWallets;
    @Autowired private BnplPurchaseStore purchases;

    private BnplPurchaseWorld world;

    @BeforeEach
    void world() throws Exception {
        world = new BnplPurchaseWorld(port, authorization, enablements, pricing, feeSchedules, terms, transactions,
                origination, postingService, ledgerAccountStore, headroom, loanWallets).ready();
        world.headroomAtLeast(eur(100_000_00L));
    }

    @Test
    @DisplayName("a merchant reads its own purchase - FINANCED, the session and order, the gross, fee and net, the pinned"
            + " version and the financing entry")
    void aMerchantReadsItsOwnPurchase() throws Exception {
        BnplApplicationClient.Merchant merchant = world.merchant();
        Purchased purchased = purchase(merchant);
        HttpResponse<String> read = read(merchant.apiKey(), purchased.purchase());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.body()).contains("\"status\":\"FINANCED\"", "\"product\":\"BNPL_PAY_IN_4\"",
                "\"currency\":\"EUR\"", "\"gross\":\"600.00\"", "\"fee\":\"24.00\"", "\"net\":\"576.00\"",
                "\"checkoutId\":\"" + purchased.session() + "\"", "\"orderId\":\"" + purchased.order() + "\"",
                "\"feeScheduleVersionId\":\"" + pinnedVersion(purchased.purchase()) + "\"",
                "\"entryId\":\"" + financingEntry(purchased.purchase()) + "\"");
    }

    @Test
    @DisplayName("INV-MER-01: another merchant's purchase is the same 404 bnpl.NotFound as an unknown or malformed id,"
            + " byte for byte - never 403 - and its owner reads it (ADR-0031's negative)")
    void anotherMerchantsPurchaseIsNotFound() throws Exception {
        BnplApplicationClient.Merchant owner = world.merchant();
        BnplApplicationClient.Merchant stranger = world.merchant();
        Purchased purchased = purchase(owner);

        HttpResponse<String> foreign = read(stranger.apiKey(), purchased.purchase());
        HttpResponse<String> unknown = read(stranger.apiKey(), IDS.next().toString());
        HttpResponse<String> malformed = read(stranger.apiKey(), "not-a-uuid");
        for (HttpResponse<String> refused : List.of(foreign, unknown, malformed)) {
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(404);
            assertThat(refused.body()).contains("bnpl.NotFound").doesNotContain("600.00", purchased.purchase(),
                    owner.id());
        }
        assertThat(normalised(foreign.body())).isEqualTo(normalised(unknown.body()));
        assertThat(read(owner.apiKey(), purchased.purchase()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("a suspended merchant's key reads nothing - the platform's 401 - and reads again once reinstated")
    void aSuspendedMerchantsKeyReadsNothing() throws Exception {
        BnplApplicationClient.Merchant merchant = world.merchant();
        Purchased purchased = purchase(merchant);
        String operator = world.client.operatorSession(authorization, RoleName.MERCHANT_ADMINISTRATOR);
        assertThat(world.client.call("POST", "/v1/operator/merchants/" + merchant.id() + "/suspension",
                "{\"reason\":\"risk review\"}", operator).statusCode()).isEqualTo(200);
        HttpResponse<String> refused = read(merchant.apiKey(), purchased.purchase());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(401);
        assertThat(refused.body()).doesNotContain("600.00", purchased.purchase());
        assertThat(world.client.call("POST", "/v1/operator/merchants/" + merchant.id() + "/reinstatement",
                "{\"reason\":\"review closed\"}", operator).statusCode()).isEqualTo(200);
        assertThat(read(merchant.apiKey(), purchased.purchase()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("a revoked key is refused - the platform's 401")
    void aRevokedKeyIsRefused() throws Exception {
        BnplApplicationClient.Merchant merchant = world.merchant();
        Purchased purchased = purchase(merchant);
        String operator = world.client.operatorSession(authorization, RoleName.MERCHANT_ADMINISTRATOR);
        String keyId = merchant.apiKey().substring(0, merchant.apiKey().indexOf('.'));
        assertThat(world.client.call("DELETE", "/v1/operator/merchants/" + merchant.id() + "/api-keys/" + keyId,
                "{\"reason\":\"the key was rotated\"}", operator).statusCode()).isEqualTo(204);
        HttpResponse<String> refused = read(merchant.apiKey(), purchased.purchase());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(401);
        assertThat(refused.body()).doesNotContain("600.00", purchased.purchase());
    }

    @Test
    @DisplayName("INV-CRD-04, the needle walk: the customer's party, decision, score, request, agreement, obligation,"
            + " plan, schedule and repayment are on record, and none of them reaches the merchant's response")
    void noCreditDataReachesTheMerchant() throws Exception {
        BnplApplicationClient.Merchant merchant = world.merchant();
        Purchased purchased = purchase(merchant);
        String obligation = scalar("SELECT id::text FROM bnpl.bnpl_obligation WHERE purchase_id = ?::uuid",
                purchased.purchase());
        String decision = scalar("SELECT decision_id::text FROM bnpl.bnpl_obligation WHERE id = ?::uuid", obligation);
        String request = scalar("SELECT decision_request_id::text FROM credit.credit_decision WHERE id = ?::uuid",
                decision);
        String score = scalar("SELECT a.score::text FROM credit.credit_assessment a WHERE a.decision_request_id ="
                + " ?::uuid", request);
        List<String> needles = new ArrayList<>(List.of(purchased.party().toString(), decision, request, obligation,
                scalar("SELECT agreement_id::text FROM bnpl.bnpl_obligation WHERE id = ?::uuid", obligation),
                scalar("SELECT id::text FROM bnpl.bnpl_repayment WHERE obligation_id = ?::uuid", obligation),
                scalar("SELECT id::text FROM bnpl.bnpl_schedule WHERE obligation_id = ?::uuid", obligation),
                scalar("SELECT offer_id::text FROM bnpl.purchase WHERE id = ?::uuid", purchased.purchase()),
                "\"PAY_IN_4\"", "150.00", "450.00", ":" + score + ",", ":" + score + "}", "\"" + score + "\""));
        for (String reason : list("SELECT r.reason_code FROM credit.credit_decision_reason r WHERE r.decision_id ="
                + " ?::uuid", decision)) {
            needles.add(reason);
        }
        HttpResponse<String> read = read(merchant.apiKey(), purchased.purchase());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        for (String needle : needles) {
            assertThat(read.body()).as("a credit datum reached the merchant: %s", needle).doesNotContain(needle);
        }
        // The merchant's own fee schedule version is the merchant's datum, not the customer's schedule.
        assertThat(read.body().toLowerCase(java.util.Locale.ROOT).replace("feeschedule", "")).doesNotContain("party", "plan", "decision", "score",
                "reason", "schedule", "instalment", "repayment", "agreement", "obligation", "delinquen", "late");
    }

    @Test
    @DisplayName("INV-BNPL-04: the gross, fee and net shown equal the financing entry F1's payable lines - CR the gross,"
            + " DR the fee, the net their difference")
    void theFiguresEqualTheFinancingEntry() throws Exception {
        BnplApplicationClient.Merchant merchant = world.merchant();
        Purchased purchased = purchase(merchant);
        String entry = financingEntry(purchased.purchase());
        UUID payable = BnplPurchaseWorld.payableOf(merchant);
        long credited = BnplApplicationClient.count("SELECT coalesce(sum(amount_minor), 0) FROM ledger.journal_line"
                + " WHERE entry_id = ?::uuid AND ledger_account_id = ? AND direction = 'CREDIT'", entry, payable);
        long debited = BnplApplicationClient.count("SELECT coalesce(sum(amount_minor), 0) FROM ledger.journal_line"
                + " WHERE entry_id = ?::uuid AND ledger_account_id = ? AND direction = 'DEBIT'", entry, payable);
        HttpResponse<String> read = read(merchant.apiKey(), purchased.purchase());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(BnplApplicationClient.field(read.body(), "gross")).isEqualTo(decimal(credited));
        assertThat(BnplApplicationClient.field(read.body(), "fee")).isEqualTo(decimal(debited));
        assertThat(BnplApplicationClient.field(read.body(), "net")).isEqualTo(decimal(credited - debited));
        assertThat(credited).isEqualTo(GROSS);
    }

    @Test
    @DisplayName("INV-MER-03: a schedule version in force after the purchase changes nothing shown - the fee and version"
            + " are the purchase's pin, never the merchant's current schedule")
    void theFeeVersionIsThePinNotTheCurrent() throws Exception {
        BnplApplicationClient.Merchant merchant = world.merchant();
        Purchased purchased = purchase(merchant);
        String pinned = pinnedVersion(purchased.purchase());
        UUID schedule = UUID.fromString(scalar("SELECT fee_schedule_id::text FROM merchant.merchant_bnpl_fee_schedule"
                + " WHERE merchant_id = ?::uuid AND product = 'BNPL_PAY_IN_4'", merchant.id()));
        BnplApplicationClient.act(BnplApplicationClient.operator(), uow -> feeSchedules.addVersion(uow,
                FeeScheduleId.of(schedule), new FeeSchedules.NewVersion(FeeRate.of(new BigDecimal("0.09")),
                        Money.ofMinorUnits(0, BnplPurchaseWorld.EUR), RoundingPolicy.HALF_EVEN,
                        RefundFeePolicy.RETURNED, Optional.empty(), "repriced after the purchase")));
        String current = scalar("SELECT id::text FROM merchant.fee_schedule_version WHERE fee_schedule_id = ?"
                + " ORDER BY version DESC LIMIT 1", schedule);
        assertThat(current).isNotEqualTo(pinned);

        HttpResponse<String> read = read(merchant.apiKey(), purchased.purchase());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(BnplApplicationClient.field(read.body(), "feeScheduleVersionId")).isEqualTo(pinned);
        assertThat(BnplApplicationClient.field(read.body(), "fee")).isEqualTo("24.00");
    }

    @Test
    @DisplayName("INV-BNPL-02: ten readers racing the acceptance's commit each see, in one snapshot, nothing or the whole"
            + " purchase with its financing - never a purchase without it - and every whole answer identical")
    void aReadRacingTheAcceptanceSeesAllOrNothing() throws Exception {
        BnplApplicationClient.Merchant merchant = world.merchant();
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        world.topUpWallet(customer.party(), eur(1_000_00));
        BnplPurchaseWorld.Offer offer = world.offered(customer, merchant, GROSS, BnplApplicationClient::payIn4);
        UUID tenant = merchant.ref();

        ConcurrentLinkedQueue<Optional<MerchantBnplPurchaseView>> answers = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> torn = new ConcurrentLinkedQueue<>();
        AtomicBoolean accepted = new AtomicBoolean();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService readers = Executors.newFixedThreadPool(10);
        List<Future<?>> running = new ArrayList<>();
        for (int reader = 0; reader < 10; reader++) {
            running.add(readers.submit(() -> {
                start.await();
                int afterwards = 0;
                while (afterwards < 3) {
                    if (accepted.get()) {
                        afterwards++;
                    }
                    try (Connection snapshot = DatabaseRoles.application()) {
                        snapshot.setAutoCommit(false);
                        snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                        snapshot.setReadOnly(true);
                        Optional<UUID> visible = purchaseOf(snapshot, offer.id());
                        Optional<MerchantBnplPurchaseView> answer = visible.flatMap(id ->
                                purchases.atMerchant(snapshot, BnplPurchaseId.of(id), tenant));
                        if (visible.isPresent() && answer.isEmpty()) {
                            torn.add(visible.get().toString());
                        }
                        answers.add(answer);
                        snapshot.rollback();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        HttpResponse<String> acceptance = world.accept(customer.token(), offer, BnplApplicationClient.key());
        accepted.set(true);
        for (Future<?> reader : running) {
            reader.get(60, TimeUnit.SECONDS);
        }
        readers.shutdownNow();

        assertThat(acceptance.statusCode()).as(acceptance.body()).isEqualTo(201);
        assertThat(torn).as("a purchase seen without its financing").isEmpty();
        List<MerchantBnplPurchaseView> whole = answers.stream().flatMap(Optional::stream).toList();
        assertThat(whole).as("every reader saw the committed purchase at the end").hasSizeGreaterThanOrEqualTo(10);
        assertThat(whole.stream().distinct()).as("every whole answer is the same one").hasSize(1);
        assertThat(whole.get(0).gross()).isEqualTo(eur(GROSS));
    }

    // ------------------------------------------------------------------ the world

    private record Purchased(String purchase, UUID session, String order, UUID party) {}

    /** A pay-in-4 purchase of {@link #GROSS} at {@code merchant}, by a consenting customer, through the real doors. */
    private Purchased purchase(BnplApplicationClient.Merchant merchant) throws Exception {
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        world.topUpWallet(customer.party(), eur(1_000_00));
        BnplPurchaseWorld.Offer offer = world.offered(customer, merchant, GROSS, BnplApplicationClient::payIn4);
        HttpResponse<String> accepted = world.accept(customer.token(), offer, BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        return new Purchased(BnplApplicationClient.field(accepted.body(), "purchaseId"), offer.session(),
                BnplApplicationClient.field(accepted.body(), "orderId"), customer.party());
    }

    private HttpResponse<String> read(String key, String purchase) throws Exception {
        return world.client.get(PURCHASES + "/" + purchase, key);
    }

    private static String pinnedVersion(String purchase) throws Exception {
        return scalar("SELECT fee_schedule_version_id::text FROM merchant.bnpl_fee_pin WHERE purchase_id = ?::uuid",
                purchase);
    }

    private static String financingEntry(String purchase) throws Exception {
        return scalar("SELECT journal_entry_id::text FROM bnpl.merchant_financing WHERE purchase_id = ?::uuid",
                purchase);
    }

    private static Optional<UUID> purchaseOf(Connection snapshot, String offer) throws Exception {
        try (PreparedStatement select = snapshot.prepareStatement(
                "SELECT id FROM bnpl.purchase WHERE offer_id = ?::uuid")) {
            select.setString(1, offer);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(row.getObject("id", UUID.class)) : Optional.empty();
            }
        }
    }

    private static String scalar(String sql, Object... parameters) throws Exception {
        return BnplApplicationClient.scalar(sql, parameters);
    }

    private static List<String> list(String sql, Object... parameters) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement select = migrator.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
        }
        return values;
    }

    private static String decimal(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }

    /** A problem body with its per-request members - instance, correlation, timestamp - blanked. */
    static String normalised(String body) {
        return body.replaceAll("\"(instance|correlationId|timestamp|traceId)\":\"[^\"]*\"", "\"$1\":\"-\"");
    }
}
