package com.finapp.app.bnpl;

import static com.finapp.app.bnpl.BnplPurchaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.bnpl.BnplOrigination;
import com.finapp.bnpl.BnplTermsAdministration;
import com.finapp.identity.Authorization;
import com.finapp.identity.RoleName;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingService;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LoanWallets;
import com.finapp.merchant.BnplEnablements;
import com.finapp.merchant.BnplPricing;
import com.finapp.merchant.FeeSchedules;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The refund doors' contract over HTTP (`P12-TSK-021`; ADR-0105 decision 9, {@code PHASE_12_PLAN.md} section 9; ADR-0052,
 * ADR-0031, {@code INV-MER-01}): no key 401; a key without the {@code BNPL_REFUND} scope 403 {@code
 * bnpl.RefundScopeRequired}, recorded {@code FAILED}, nothing locked; a key issued with it refunds (201, purchase-level
 * figures only), the same key replays the same refund; another merchant's purchase - and an unknown or malformed id - one
 * 404, nothing locked or posted; another merchant reads none of the refunds and its transaction report names none; the
 * operator's door under {@code PAYMENT_REFUND}; the merchant's payable and transaction report re-asserted through a real
 * refund (`-016`'s carry: {@code bnplRefunded}, {@code bnplFeesReturned}, the {@code BNPL_REFUND} kind and its refund);
 * and the pair element (P) against a wallet-paid capture's {@code lockPairInFixedOrder} for the same customer and
 * merchant - never {@code 40P01}, in both id orders, counted.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): it brings credit's BNPL policies and terms into force.
 * Written by `P12-TSK-021` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the BNPL refund doors' contract (P12-TSK-021)")
class BnplRefundApiTest {

    private static final long GROSS = 600_00L;
    private static final String PURCHASES = "/v1/merchant/bnpl-purchases/";
    private static final String OPERATOR_PURCHASES = "/v1/operator/bnpl-purchases/";
    private static final String BODY = "{\"amount\":\"180.00\",\"currency\":\"EUR\",\"reason\":\"goods returned\"}";

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

    private BnplPurchaseWorld world;

    @BeforeEach
    void world() throws Exception {
        world = new BnplPurchaseWorld(port, authorization, enablements, pricing, feeSchedules, terms, transactions,
                origination, postingService, ledgerAccountStore, headroom, loanWallets).ready();
        world.headroomAtLeast(eur(100_000_00L));
    }

    @Test
    @DisplayName("a key without the BNPL_REFUND scope is 403 bnpl.RefundScopeRequired, recorded FAILED, nothing locked or"
            + " posted; a key issued with it refunds - 201, the purchase-level figures only - and the same key replays")
    void aKeyWithoutTheScopeIsRefused() throws Exception {
        Bought bought = bought();
        String key = BnplApplicationClient.key();
        HttpResponse<String> refused = world.client.post(PURCHASES + bought.purchase() + "/refunds", BODY,
                bought.merchant().apiKey(), key);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).contains("bnpl.RefundScopeRequired");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'bnpl.RefundScopeRefused' AND outcome = 'FAILED' AND target_id = ?", bought.purchase())).isEqualTo(1);
        assertThat(refunds(bought.purchase())).isZero();

        String scoped = scopedKey(bought.merchant());
        HttpResponse<String> refunded = world.client.post(PURCHASES + bought.purchase() + "/refunds", BODY, scoped, key);
        assertThat(refunded.statusCode()).as(refunded.body()).isEqualTo(201);
        assertThat(refunded.body()).contains("\"amount\":\"180.00\"", "\"feeReturned\":\"7.20\"",
                "\"payableDebit\":\"172.80\"", "\"purchaseStatus\":\"PARTIALLY_REFUNDED\"")
                .doesNotContain("excess").doesNotContain("instalment").doesNotContain("goods returned");
        HttpResponse<String> again = world.client.post(PURCHASES + bought.purchase() + "/refunds", BODY, scoped, key);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(201);
        assertThat(BnplApplicationClient.field(again.body(), "refundId"))
                .isEqualTo(BnplApplicationClient.field(refunded.body(), "refundId"));
        assertThat(refunds(bought.purchase())).isEqualTo(1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'bnpl.RefundApplied' AND outcome = 'SUCCEEDED' AND target_id = ?",
                BnplApplicationClient.field(refunded.body(), "refundId"))).isEqualTo(1);
    }

    @Test
    @DisplayName("another merchant's purchase - and an unknown or malformed id - is one 404 bnpl.NotFound, nothing locked or"
            + " posted (INV-MER-01, ADR-0031's negative)")
    void anotherMerchantsPurchaseIsNotFound() throws Exception {
        Bought bought = bought();
        BnplApplicationClient.Merchant stranger = world.merchant();
        String strangerKey = scopedKey(stranger);
        for (String id : List.of(bought.purchase(), UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> refund = world.client.post(PURCHASES + id + "/refunds", BODY, strangerKey,
                    BnplApplicationClient.key());
            assertThat(refund.statusCode()).as(id + ": " + refund.body()).isEqualTo(404);
            assertThat(refund.body()).contains("bnpl.NotFound");
        }
        assertThat(refunds(bought.purchase())).isZero();
    }

    @Test
    @DisplayName("another merchant reads none of the purchase's refunds: its read is the uniform 404; the owner's read shows"
            + " them, PARTIALLY_REFUNDED (the -014 carry)")
    void anotherMerchantReadsNoneOfTheRefunds() throws Exception {
        Bought bought = bought();
        String scoped = scopedKey(bought.merchant());
        assertThat(world.client.post(PURCHASES + bought.purchase() + "/refunds", BODY, scoped,
                BnplApplicationClient.key()).statusCode()).isEqualTo(201);
        HttpResponse<String> own = world.client.get(PURCHASES + bought.purchase(), bought.merchant().apiKey());
        assertThat(own.statusCode()).as(own.body()).isEqualTo(200);
        assertThat(own.body()).contains("\"status\":\"PARTIALLY_REFUNDED\"", "\"refunded\":\"180.00\"", "goods returned");
        BnplApplicationClient.Merchant stranger = world.merchant();
        HttpResponse<String> theirs = world.client.get(PURCHASES + bought.purchase(), stranger.apiKey());
        assertThat(theirs.statusCode()).as(theirs.body()).isEqualTo(404);
        assertThat(theirs.body()).doesNotContain("180.00");
    }

    @Test
    @DisplayName("the merchant's payable and transaction report through a real refund (-016's carry): bnplRefunded and"
            + " bnplFeesReturned, the BNPL_REFUND kind naming the refund - and another merchant's report names none")
    void anotherMerchantsReportNamesNoRefund() throws Exception {
        Bought bought = bought();
        String scoped = scopedKey(bought.merchant());
        HttpResponse<String> refunded = world.client.post(PURCHASES + bought.purchase() + "/refunds", BODY, scoped,
                BnplApplicationClient.key());
        assertThat(refunded.statusCode()).as(refunded.body()).isEqualTo(201);
        String refund = BnplApplicationClient.field(refunded.body(), "refundId");
        HttpResponse<String> payable = world.client.get("/v1/merchant/payable", bought.merchant().apiKey());
        assertThat(payable.body()).contains("\"bnplRefunded\":\"180.00\"", "\"bnplFeesReturned\":\"7.20\"");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        HttpResponse<String> listed = world.client.get("/v1/merchant/transactions?from=" + today + "&to=" + today,
                bought.merchant().apiKey());
        assertThat(listed.body()).contains("BNPL_REFUND", refund);
        BnplApplicationClient.Merchant stranger = world.merchant();
        HttpResponse<String> theirs = world.client.get("/v1/merchant/transactions?from=" + today + "&to=" + today,
                stranger.apiKey());
        assertThat(theirs.body()).doesNotContain(refund).doesNotContain("BNPL_REFUND");
    }

    @Test
    @DisplayName("the operator's door refunds under PAYMENT_REFUND with a reason - 201 with the split - and a session"
            + " without the permission is 403 and posts nothing")
    void theOperatorsDoor() throws Exception {
        Bought bought = bought();
        String unprivileged = world.client.operatorSession(authorization, RoleName.MERCHANT_ADMINISTRATOR);
        HttpResponse<String> refused = world.client.post(OPERATOR_PURCHASES + bought.purchase() + "/refunds", BODY,
                unprivileged, BnplApplicationClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refunds(bought.purchase())).isZero();
        String operator = world.client.operatorSession(authorization, RoleName.LEDGER_OPERATOR);
        HttpResponse<String> refunded = world.client.post(OPERATOR_PURCHASES + bought.purchase() + "/refunds", BODY,
                operator, BnplApplicationClient.key());
        assertThat(refunded.statusCode()).as(refunded.body()).isEqualTo(201);
        assertThat(refunded.body()).contains("\"cancelledUnbilled\":\"180.00\"", "\"excessTo\":\"NONE\"");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_refund WHERE purchase_id = ?::uuid AND"
                + " door = 'OPERATOR'", bought.purchase())).isEqualTo(1);
    }

    @Test
    @DisplayName("a refund racing a wallet-paid capture for the same customer at the same merchant never deadlocks: (P)"
            + " takes the payable and the wallet in UUID.compareTo order, payments' lockPairInFixedOrder - both id orders"
            + " observed, no 40P01")
    void aRefundRacingAWalletPaidCaptureNeverDeadlocks() throws Exception {
        int[] orders = new int[2];
        for (int round = 0; round < 8 && (orders[0] == 0 || orders[1] == 0); round++) {
            Bought bought = bought();
            String scoped = scopedKey(bought.merchant());
            UUID payable = ledgerAccount("SELECT a.id FROM ledger.ledger_account a WHERE a.purpose = 'MERCHANT_PAYABLE'"
                    + " AND a.owner_ref = ?::uuid", bought.merchant().id());
            UUID wallet = ledgerAccount("SELECT a.id FROM ledger.ledger_account a WHERE a.purpose = 'CUSTOMER_WALLET'"
                    + " AND a.owner_ref = (SELECT w.id FROM accounts.customer_account w WHERE w.customer_id = ?::uuid"
                    + " LIMIT 1)", bought.party().toString());
            orders[payable.compareTo(wallet) < 0 ? 0 : 1]++;
            UUID first = payable.compareTo(wallet) < 0 ? payable : wallet;
            UUID second = first.equals(payable) ? wallet : payable;
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<Integer> refund = pool.submit((Callable<Integer>) () -> {
                    start.await();
                    return world.client.post(PURCHASES + bought.purchase() + "/refunds",
                            "{\"amount\":\"600.00\",\"currency\":\"EUR\",\"reason\":\"returned\"}", scoped,
                            BnplApplicationClient.key()).statusCode();
                });
                Future<String> capture = pool.submit((Callable<String>) () -> {
                    start.await();
                    try (Connection racer = DatabaseRoles.application()) {
                        racer.setAutoCommit(false);
                        try (Statement lock = racer.createStatement()) {
                            // payments' book rail: the pair in UUID.compareTo order, then a pause inside the lock.
                            lock.execute("SELECT 1 FROM ledger.ledger_account WHERE id = '" + first + "' FOR UPDATE");
                            lock.execute("SELECT pg_sleep(0.05)");
                            lock.execute("SELECT 1 FROM ledger.ledger_account WHERE id = '" + second + "' FOR UPDATE");
                        }
                        racer.rollback();
                        return "ok";
                    } catch (SQLException failure) {
                        return failure.getSQLState();
                    }
                });
                start.countDown();
                assertThat(capture.get(60, TimeUnit.SECONDS)).as("never 40P01").isEqualTo("ok");
                assertThat(refund.get(60, TimeUnit.SECONDS)).isEqualTo(201);
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(orders[0] + orders[1]).isPositive();
    }

    // ------------------------------------------------------------------ plumbing

    /** A pay-in-4 purchase accepted by a fresh customer at a fresh merchant - its id, the merchant, the customer. */
    record Bought(String purchase, BnplApplicationClient.Merchant merchant, UUID party) {}

    private Bought bought() throws Exception {
        CreditFixtures.Customer owner = world.customers.consentingCustomer();
        world.topUpWallet(owner.party(), eur(1_000_00));
        BnplApplicationClient.Merchant merchant = world.merchant();
        BnplPurchaseWorld.Offer offer = world.offered(owner, merchant, GROSS, BnplApplicationClient::payIn4);
        HttpResponse<String> accepted = world.accept(owner.token(), offer, BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        return new Bought(BnplApplicationClient.field(accepted.body(), "purchaseId"), merchant, owner.party());
    }

    /** A key issued to {@code merchant} with the BNPL_REFUND scope, by the operator who administers its keys. */
    private String scopedKey(BnplApplicationClient.Merchant merchant) throws Exception {
        String operator = world.client.operatorSession(authorization, RoleName.MERCHANT_ADMINISTRATOR);
        HttpResponse<String> issued = world.client.post("/v1/operator/merchants/" + merchant.id()
                + "/api-keys?scope=BNPL_REFUND", null, operator, BnplApplicationClient.key());
        assertThat(issued.statusCode()).as(issued.body()).isEqualTo(201);
        return BnplApplicationClient.field(issued.body(), "keyId") + "." + BnplApplicationClient.field(issued.body(),
                "secret");
    }

    private static long refunds(String purchase) throws Exception {
        return BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_refund WHERE purchase_id = ?::uuid",
                purchase);
    }

    private static UUID ledgerAccount(String sql, String parameter) throws Exception {
        try (Connection migrator = DatabaseRoles.migrator();
                java.sql.PreparedStatement select = migrator.prepareStatement(sql)) {
            select.setString(1, parameter);
            try (java.sql.ResultSet row = select.executeQuery()) {
                row.next();
                return row.getObject(1, UUID.class);
            }
        }
    }
}
