package com.finapp.app.bnpl;

import static com.finapp.app.bnpl.BnplPurchaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.database.DateShifted;
import com.finapp.bnpl.BnplAcceptanceEvidence;
import com.finapp.bnpl.BnplAcceptances;
import com.finapp.bnpl.BnplCapital;
import com.finapp.bnpl.BnplCheckoutSessions;
import com.finapp.bnpl.BnplCreditDecisions;
import com.finapp.bnpl.BnplMerchants;
import com.finapp.bnpl.BnplOfferId;
import com.finapp.bnpl.BnplOfferStore;
import com.finapp.bnpl.BnplOrigination;
import com.finapp.bnpl.BnplPartyStanding;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplPurchaseId;
import com.finapp.bnpl.BnplPurchaseStore;
import com.finapp.bnpl.BnplTermsAdministration;
import com.finapp.bnpl.BnplTermsStore;
import com.finapp.bnpl.BnplWallets;
import com.finapp.bnpl.BnplBook;
import com.finapp.identity.Authorization;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingService;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LoanWallets;
import com.finapp.merchant.BnplEnablements;
import com.finapp.merchant.BnplPricing;
import com.finapp.merchant.FeeSchedules;
import com.finapp.merchant.MerchantAdministration;
import com.finapp.merchant.MerchantId;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
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
 * The BNPL purchase against a real database - credit's real lock and consumption, checkout's real session and order,
 * merchant's real standing, fee pin and payable, lending's real capital row, accounts' real wallet and the ledger's real
 * postings (`P12-TSK-013`; {@code PHASE_12_PLAN.md} sections 7.1-7.4 and 12.4; {@code INV-BNPL-02}, {@code -03},
 * {@code -04}, {@code -10}, {@code INV-LND-13}, {@code INV-CRD-09}, {@code INV-IDEM-01}): one acceptance finances once
 * under one key on two instances and under ten keys; a card or wallet confirmation and an acceptance of one session
 * complete it once, both orders; an acceptance and a credit decision serialise on the profile, both orders; a BNPL
 * acceptance and a wallet-paid capture by the same customer at the same merchant never deadlock, in both UUID orders of
 * the pair; the session judged at once; the last capital admits one; a failure at every step leaves nothing; and the
 * financing, the order, the capital and the exposure move in one commit. Lock-waits asserted on L0, B3, L6 and (P).
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): it plants lending capital, activates terms and
 * credit policies and suspends merchants and customers. Written by `P12-TSK-013` and <strong>owed a first run</strong>
 * (the owner's no-{@code databaseTest} instruction for the pair).
 */
@DateShifted
@Tag("database")
@Tag("own-container")
@SuppressWarnings("try")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the BNPL purchase: purchase, merchant financing and the obligation, atomically (P12-TSK-013)")
class BnplPurchaseDatabaseTest {

    private static final long GROSS = 600_00L;
    private static final int ROUNDS = 6;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private BnplEnablements enablements;
    @Autowired private BnplPricing pricing;
    @Autowired private FeeSchedules feeSchedules;
    @Autowired private MerchantAdministration merchantAdministration;
    @Autowired private BnplTermsAdministration termsAdministration;
    @Autowired private BnplTransactions transactions;
    @Autowired private BnplOrigination origination;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private LendingCapitalHeadroom headroom;
    @Autowired private LoanWallets loanWallets;
    @Autowired private BnplOfferStore offerStore;
    @Autowired private BnplTermsStore termsStore;
    @Autowired private BnplPurchaseStore purchaseStore;
    @Autowired private BnplCreditDecisions creditDecisions;
    @Autowired private BnplPartyStanding partyStanding;
    @Autowired private BnplCheckoutSessions checkoutSessions;
    @Autowired private BnplMerchants bnplMerchants;
    @Autowired private BnplCapital bnplCapital;
    @Autowired private BnplWallets bnplWallets;
    @Autowired private BnplBook bnplBook;
    @Autowired private OutboxWriter<Connection> outboxWriter;
    @Autowired private AuditWriter<Connection> auditWriter;
    @Autowired private IdGenerator ids;
    @Autowired private Clock clock;

    private BnplPurchaseWorld world;
    private static com.finapp.platform.testing.provider.SimulatedProvider provider;

    @BeforeAll
    static void startProvider() {
        if (provider == null) {
            provider = com.finapp.platform.testing.provider.SimulatedProvider.start();
        }
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = com.finapp.platform.testing.provider.SimulatedProvider.start();
        }
        registry.add("finapp.paymentmethods.tokenisation.url", () -> provider.baseUrl());
        registry.add("finapp.paymentmethods.tokenisation.timeout", () -> "PT0.7S");
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

    // ------------------------------------------------------------------ once

    @Test
    @DisplayName("the same acceptance twice finances once: one key on two instances - one 201, the other its replay or"
            + " in progress; ten keys on ten instances - one 201, nine 409 bnpl.OfferNotAcceptable; one purchase, one"
            + " financing, one consumption, one order")
    void theSameAcceptanceTwiceFinancesOnce() throws Exception {
        Bought one = payIn4();
        String key = BnplApplicationClient.key();
        List<HttpResponse<String>> sameKey = atOnce(List.of(
                () -> world.accept(one.customer().token(), one.offer(), key),
                () -> world.accept(one.customer().token(), one.offer(), key)));
        assertThat(sameKey).filteredOn(response -> response.statusCode() == 201).isNotEmpty();
        assertThat(sameKey).allSatisfy(response -> assertThat(response.statusCode()).as(response.body()).isIn(201, 409));
        assertOnce(one);

        Bought ten = payIn4();
        List<Callable<HttpResponse<String>>> keys = new ArrayList<>();
        for (int instance = 0; instance < 10; instance++) {
            keys.add(() -> world.accept(ten.customer().token(), ten.offer(), BnplApplicationClient.key()));
        }
        List<HttpResponse<String>> answers = atOnce(keys);
        assertThat(answers).filteredOn(response -> response.statusCode() == 201).hasSize(1);
        assertThat(answers).filteredOn(response -> response.statusCode() == 409).hasSize(9)
                .allSatisfy(response -> assertThat(response.body()).contains("bnpl.OfferNotAcceptable"));
        assertOnce(ten);
    }

    @Test
    @DisplayName("a lost response replays the agreement: the same key answers the same agreement, purchase and order"
            + " (INV-IDEM-01)")
    void aLostResponseReplaysTheAgreement() throws Exception {
        Bought bought = payIn4();
        String key = BnplApplicationClient.key();
        HttpResponse<String> first = world.accept(bought.customer().token(), bought.offer(), key);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        HttpResponse<String> again = world.accept(bought.customer().token(), bought.offer(), key);
        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(again.body()).isEqualTo(first.body());
        assertOnce(bought);
    }

    @Test
    @DisplayName("BNPL and a card on one session complete it once, both orders: the card first - the acceptance 409"
            + " bnpl.SessionNotPayable, nothing financed; the acceptance first, the card confirmation waiting on the"
            + " session it holds - the card 409 checkout.NotConfirmable; one order either way")
    void bnplAndCardOnOneSessionCompleteOnce() throws Exception {
        CreditFixtures.Customer payer = world.customers.consentingCustomer();
        String card = cardOf(payer);
        Bought cardFirst = payIn4();
        HttpResponse<String> paid = confirmByCard(payer, card, cardFirst.offer().sessionToken());
        assertThat(BnplApplicationClient.field(paid.body(), "status")).as(paid.body()).isEqualTo("COMPLETED");
        HttpResponse<String> refused = world.accept(cardFirst.customer().token(), cardFirst.offer(),
                BnplApplicationClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("bnpl.SessionNotPayable");
        assertNothing(cardFirst, 1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM checkout.checkout_order WHERE session_ref ="
                + " ?::uuid", cardFirst.offer().session().toString())).isEqualTo(1);

        Bought bnplFirst = payIn4();
        HttpResponse<String> lost = rivalBehindTheAcceptance(bnplFirst,
                () -> confirmByCard(payer, card, bnplFirst.offer().sessionToken()));
        assertThat(lost.statusCode()).as(lost.body()).isEqualTo(409);
        assertThat(lost.body()).contains("checkout.NotConfirmable");
        assertOnce(bnplFirst);
    }

    @Test
    @DisplayName("BNPL and a wallet payment on one session complete it once, both orders")
    void bnplAndAWalletPaymentOnOneSessionCompleteOnce() throws Exception {
        CreditFixtures.Customer payer = world.customers.consentingCustomer();
        world.topUpWallet(payer.party(), eur(10_000_00));
        Bought walletFirst = payIn4();
        HttpResponse<String> paid = confirmFromWallet(payer.token(), walletFirst.offer().sessionToken());
        assertThat(BnplApplicationClient.field(paid.body(), "status")).as(paid.body()).isEqualTo("COMPLETED");
        HttpResponse<String> refused = world.accept(walletFirst.customer().token(), walletFirst.offer(),
                BnplApplicationClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertNothing(walletFirst, 1);

        Bought bnplFirst = payIn4();
        HttpResponse<String> lost = rivalBehindTheAcceptance(bnplFirst,
                () -> confirmFromWallet(payer.token(), bnplFirst.offer().sessionToken()));
        assertThat(lost.statusCode()).as(lost.body()).isEqualTo(409);
        assertThat(lost.body()).contains("checkout.NotConfirmable");
        assertOnce(bnplFirst);
    }

    // ------------------------------------------------------------------ the session, judged at once

    @Test
    @DisplayName("the session is judged at once: a session another instrument completed answers 409"
            + " bnpl.SessionNotPayable holding nothing beyond L0, B2 and B3 - it never waits on a held capital row -"
            + " and nothing is consumed")
    void theSessionIsJudgedAtOnce() throws Exception {
        Bought bought = payIn4();
        HttpResponse<String> paid = confirmFromWallet(bought.customer().token(), bought.offer().sessionToken());
        assertThat(BnplApplicationClient.field(paid.body(), "status")).as(paid.body()).isEqualTo("COMPLETED");
        Connection capital = BnplPurchaseWorld.held();
        try {
            try (Statement lock = capital.createStatement()) {
                lock.execute("SELECT 1 FROM ledger.ledger_account WHERE purpose = 'LENDING_CAPITAL' AND currency = 'EUR'"
                        + " FOR UPDATE");
            }
            long started = System.nanoTime();
            HttpResponse<String> refused = world.accept(bought.customer().token(), bought.offer(),
                    BnplApplicationClient.key());
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
            assertThat(refused.body()).contains("bnpl.SessionNotPayable");
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started))
                    .as("refused without waiting on the held L6").isLessThan(10);
        } finally {
            BnplPurchaseWorld.closeQuietly(capital);
        }
        assertNothing(bought, 1);
    }

    @Test
    @DisplayName("the acceptance takes B3 on checkout's session row: another holder of the row makes it wait there, and"
            + " it then completes the session once")
    void theAcceptanceWaitsOnB3() throws Exception {
        Bought bought = payIn4();
        assertWaitsBehind("SELECT 1 FROM checkout.checkout_session WHERE id = '" + bought.offer().session() + "' FOR UPDATE",
                "checkout_session", bought);
        assertOnce(bought);
    }

    // ------------------------------------------------------------------ L0

    @Test
    @DisplayName("an acceptance and a decision for the party serialise on the profile (L0), both orders: the acceptance"
            + " waits on credit_profile behind a holder; a decision waits on credit_profile behind the acceptance")
    void acceptanceAndADecisionSerialiseOnTheProfile() throws Exception {
        Bought first = payIn4();
        assertWaitsBehind("SELECT 1 FROM credit.credit_profile WHERE party_id = '" + first.customer().party()
                + "' FOR UPDATE", "credit_profile", first);
        assertOnce(first);

        // The other order: the acceptance holds L0 (waiting on its offer, held here); a decision for the party waits.
        Bought second = payIn4();
        UUID otherRequest = secondRequest(second.customer());
        Connection offer = BnplPurchaseWorld.held();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            try (Statement lock = offer.createStatement()) {
                lock.execute("SELECT 1 FROM bnpl.bnpl_offer WHERE id = '" + second.offer().id() + "' FOR UPDATE");
            }
            Future<HttpResponse<String>> accepting = pool.submit(() -> world.accept(second.customer().token(),
                    second.offer(), BnplApplicationClient.key()));
            int acceptance = awaitPidBlockedBy(pid(offer));
            Future<String> deciding = pool.submit(() -> CreditFixtures.decide(otherRequest));
            assertThat(awaitStatementBlockedBy(acceptance)).as("the decision waits on the acceptance's L0")
                    .contains("credit_profile");
            offer.rollback();
            assertThat(accepting.get(60, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
            assertThat(deciding.get(60, TimeUnit.SECONDS)).isNotNull();
        } finally {
            pool.shutdownNow();
            BnplPurchaseWorld.closeQuietly(offer);
        }
        assertOnce(second);
    }

    // ------------------------------------------------------------------ (P), both UUID orders

    @Test
    @DisplayName("a BNPL acceptance and a wallet-paid capture by the same customer at the same merchant never deadlock:"
            + " counted rounds in BOTH UUID orders of the payable and the wallet - every acceptance 201, every capture"
            + " completed, no 40P01; and the acceptance waits on the pair's first row when another holds it")
    void aBnplAcceptanceAndAWalletCaptureAtTheSameMerchantNeverDeadlock() throws Exception {
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        BnplApplicationClient.Merchant before = world.merchant();
        UUID wallet = world.walletOf(customer.party()).value();
        BnplApplicationClient.Merchant after = world.merchant();
        assertThat(BnplPurchaseWorld.payableOf(before).compareTo(wallet)).as("the payable sorts first").isNegative();
        assertThat(BnplPurchaseWorld.payableOf(after).compareTo(wallet)).as("the wallet sorts first").isPositive();
        world.topUpWallet(customer.party(), eur(100_000_00L));
        for (BnplApplicationClient.Merchant merchant : List.of(before, after)) {
            for (int round = 0; round < ROUNDS; round++) {
                BnplPurchaseWorld.Offer offer = world.offered(customer, merchant, GROSS, BnplApplicationClient::payIn4);
                BnplApplicationClient.Opened paid = world.client.openSession(merchant, 10_00);
                List<HttpResponse<String>> answers = atOnce(List.of(
                        () -> world.accept(customer.token(), offer, BnplApplicationClient.key()),
                        () -> world.client.post("/v1/checkout/sessions/confirmation", "{\"sessionToken\":\""
                                + paid.token() + "\",\"instrument\":\"WALLET\"}", customer.token(), null)));
                assertThat(answers.get(0).statusCode()).as(answers.get(0).body()).isEqualTo(201);
                assertThat(BnplApplicationClient.field(answers.get(1).body(), "status")).as(answers.get(1).body())
                        .isEqualTo("COMPLETED");
                assertThat(answers).noneMatch(response -> response.body().contains("40P01"));
            }
        }
        Bought held = new Bought(customer, world.offered(customer, before, GROSS, BnplApplicationClient::payIn4));
        UUID first = BnplPurchaseWorld.payableOf(before).compareTo(wallet) < 0 ? BnplPurchaseWorld.payableOf(before)
                : wallet;
        assertWaitsBehind("SELECT 1 FROM ledger.ledger_account WHERE id = '" + first + "' FOR UPDATE", "ledger_account",
                held);
    }

    // ------------------------------------------------------------------ L6

    @Test
    @DisplayName("acceptances racing for the last capital admit one: both wait on the held LENDING_CAPITAL row (L6), then"
            + " one is financed and the other refused 422 bnpl.CapitalUnavailable - headroom never below zero")
    void acceptancesRacingForTheLastCapitalAdmitOne() throws Exception {
        Bought one = payIn4();
        Bought other = payIn4();
        Money free = transactions.inTransaction(uow -> {
            LendingCapitalHeadroom.Headroom now = headroom.atRest(uow, BnplPurchaseWorld.EUR);
            return now.capital().minus(now.committed()).minus(now.outstanding());
        });
        // Exactly one purchase's gross of headroom left: deploy the rest through a planted BNPL principal elsewhere.
        consume(free.minus(eur(GROSS)));
        Connection capital = BnplPurchaseWorld.held();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            try (Statement lock = capital.createStatement()) {
                lock.execute("SELECT 1 FROM ledger.ledger_account WHERE purpose = 'LENDING_CAPITAL' AND currency = 'EUR'"
                        + " FOR UPDATE");
            }
            Future<HttpResponse<String>> a = pool.submit(() -> world.accept(one.customer().token(), one.offer(),
                    BnplApplicationClient.key()));
            Future<HttpResponse<String>> b = pool.submit(() -> world.accept(other.customer().token(), other.offer(),
                    BnplApplicationClient.key()));
            assertThat(awaitStatementBlockedBy(pid(capital))).contains("ledger_account");
            capital.rollback();
            List<Integer> statuses = List.of(a.get(60, TimeUnit.SECONDS).statusCode(),
                    b.get(60, TimeUnit.SECONDS).statusCode());
            assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        } finally {
            pool.shutdownNow();
            BnplPurchaseWorld.closeQuietly(capital);
        }
        Money left = transactions.inTransaction(uow -> {
            LendingCapitalHeadroom.Headroom now = headroom.atRest(uow, BnplPurchaseWorld.EUR);
            return now.capital().minus(now.committed()).minus(now.outstanding());
        });
        assertThat(left.isNegative()).as("INV-LND-13: never over-deployed").isFalse();
        world.headroomAtLeast(eur(1_000_000_00L));
    }

    // ------------------------------------------------------------------ atomic

    @Test
    @DisplayName("a failure at every step leaves nothing: after the consumption, the capital, the fee pin, the financing"
            + " posting and the session's completion - no consumption, purchase, pin, financing entry or order; the offer"
            + " still OFFERED and the session OPEN; then the same offer is financed once")
    void aFailureAtEveryStepLeavesNothing() throws Exception {
        for (String step : List.of("consume", "capital", "pin", "post", "complete")) {
            Bought bought = payIn4();
            BnplAcceptances failing = acceptances(step);
            BnplOfferId offer = BnplOfferId.of(UUID.fromString(bought.offer().id()));
            try {
                act(bought.customer(), uow -> failing.accept(uow, offer, bought.customer().party(),
                        acceptance(bought.offer().hash()), customer(bought.customer()), BnplPurchaseWorld.flow()));
                throw new AssertionError("the injected failure at " + step + " did not surface");
            } catch (Injected expected) {
                // the act rolled back whole
            }
            assertNothing(bought);
            HttpResponse<String> accepted = world.accept(bought.customer().token(), bought.offer(),
                    BnplApplicationClient.key());
            assertThat(accepted.statusCode()).as(step + ": " + accepted.body()).isEqualTo(201);
            assertOnce(bought);
        }
    }

    @Test
    @DisplayName("the financing conserves the purchase: F1's four lines are the financing row's gross and fee, the payable"
            + " rises by exactly the net, the receivable is the gross; pay-in-4's instalment 1 billed and collected in the"
            + " same commit; the order carries the financing entry")
    void theFinancingConservesThePurchase() throws Exception {
        Bought bought = payIn4();
        UUID payable = BnplPurchaseWorld.payableOf(bought.merchant());
        long before = BnplPurchaseWorld.creditBalanceOf(payable);
        HttpResponse<String> accepted = world.accept(bought.customer().token(), bought.offer(),
                BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        String purchase = BnplApplicationClient.field(accepted.body(), "purchaseId");
        assertThat(BnplApplicationClient.scalar("SELECT gross_minor || '|' || fee_minor || '|' || net_minor FROM"
                + " bnpl.merchant_financing WHERE purchase_id = ?::uuid", purchase)).isEqualTo("60000|2400|57600");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM ledger.journal_line l JOIN ledger.journal_entry e"
                + " ON e.id = l.entry_id JOIN bnpl.merchant_financing f ON f.journal_entry_id = e.id"
                + " WHERE f.purchase_id = ?::uuid", purchase)).isEqualTo(4);
        assertThat(BnplPurchaseWorld.creditBalanceOf(payable) - before).as("the payable credited exactly G - f")
                .isEqualTo(576_00);
        String obligation = BnplApplicationClient.scalar("SELECT id::text FROM bnpl.bnpl_obligation WHERE purchase_id ="
                + " ?::uuid", purchase);
        assertThat(BnplPurchaseWorld.obligationBalance(obligation, AccountPurpose.BNPL_PRINCIPAL)).isEqualTo(450_00);
        assertThat(BnplPurchaseWorld.obligationBalance(obligation, AccountPurpose.BNPL_PRINCIPAL_DUE)).isZero();
        assertThat(BnplApplicationClient.scalar("SELECT (o.captured_entry_ref = f.journal_entry_id)::text FROM"
                + " checkout.checkout_order o JOIN bnpl.merchant_financing f ON f.purchase_id = ?::uuid WHERE"
                + " o.session_ref = ?::uuid", purchase, bought.offer().session().toString())).isEqualTo("true");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope LIKE"
                + " 'ledger.post:bnpl.%' AND reference IN (?, ?)", purchase, obligation)).isEqualTo(3);
    }

    @Test
    @DisplayName("the capital and the exposure move in the same commit: in one snapshot the consumption exists exactly"
            + " when the obligation's principal does")
    void theCapitalAndTheExposureMoveInTheSameCommit() throws Exception {
        Bought bought = payIn4();
        HttpResponse<String> accepted = world.accept(bought.customer().token(), bought.offer(),
                BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        String agreement = BnplApplicationClient.field(accepted.body(), "agreementId");
        assertThat(BnplApplicationClient.scalar("SELECT (EXISTS (SELECT 1 FROM credit.credit_decision_consumption c"
                + " WHERE c.consumer_ref = ?::uuid AND c.consumer_kind = 'BNPL_AGREEMENT'))::text || '|' ||"
                + " (SELECT count(*) FROM bnpl.bnpl_obligation o WHERE o.agreement_id = ?::uuid)", agreement, agreement))
                .isEqualTo("true|1");
    }

    // ------------------------------------------------------------------ refusals

    @Test
    @DisplayName("pay-in-4's instalment 1 short in the wallet refuses 422 bnpl.InsufficientFunds, nothing committed; topped"
            + " up, the same offer is accepted")
    void instalmentOneShortRefusesAndCommitsNothing() throws Exception {
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        world.walletOf(customer.party());
        BnplApplicationClient.Merchant merchant = world.merchant();
        Bought bought = new Bought(customer, world.offered(customer, merchant, GROSS, BnplApplicationClient::payIn4),
                merchant);
        HttpResponse<String> refused = world.accept(customer.token(), bought.offer(), BnplApplicationClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("bnpl.InsufficientFunds");
        assertNothing(bought);
        world.topUpWallet(customer.party(), eur(150_00));
        assertThat(world.accept(customer.token(), bought.offer(), BnplApplicationClient.key()).statusCode())
                .isEqualTo(201);
        assertOnce(bought);
    }

    @Test
    @DisplayName("a suspended merchant refuses 422 bnpl.MerchantNotEnabled; nothing consumed")
    void aSuspendedMerchantRefuses() throws Exception {
        Bought bought = payIn4();
        BnplApplicationClient.act(BnplApplicationClient.operator(), uow -> {
            merchantAdministration.suspend(uow, MerchantId.of(bought.merchant().ref()), "investigation");
            return null;
        });
        HttpResponse<String> refused = world.accept(bought.customer().token(), bought.offer(),
                BnplApplicationClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("bnpl.MerchantNotEnabled");
        assertNothing(bought);
    }

    @Test
    @DisplayName("standing lost refuses 409 credit.ApplicantNotEligible; nothing consumed")
    void standingLostRefuses() throws Exception {
        Bought bought = payIn4();
        try (Connection app = DatabaseRoles.application()) {
            BnplApplicationClient.execute(app, "UPDATE party.customer SET status = 'SUSPENDED', status_changed_at ="
                    + " GREATEST(now(), status_changed_at) WHERE party_id = ?", bought.customer().party());
        }
        HttpResponse<String> refused = world.accept(bought.customer().token(), bought.offer(),
                BnplApplicationClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("credit.ApplicantNotEligible");
        assertNothing(bought);
    }

    @Test
    @DisplayName("a mismatched hash refuses 409 bnpl.TermsChanged; nothing consumed")
    void aMismatchedHashRefuses() throws Exception {
        Bought bought = payIn4();
        HttpResponse<String> refused = world.accept(bought.customer().token(), bought.offer().id(), "00".repeat(32),
                BnplApplicationClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("bnpl.TermsChanged");
        assertNothing(bought);
    }

    @Test
    @DisplayName("acceptance and expiry at the boundary leave exactly one of ACCEPTED and EXPIRED: the offer's expiry"
            + " stamped a moment ahead, the acceptance and the sweep's expiry racing across it")
    void acceptanceAndExpiryLeaveExactlyOne() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Bought bought = payIn4();
            expiresIn(bought.offer().id(), 400 + round * 50);
            Thread.sleep(380 + round * 50L);
            List<Object> outcomes = atOnce(List.of(
                    () -> world.accept(bought.customer().token(), bought.offer(), BnplApplicationClient.key())
                            .statusCode(),
                    () -> transactions.inTransaction(uow -> origination.expire(uow,
                            BnplOfferId.of(UUID.fromString(bought.offer().id())), new Actor("platform",
                                    ActorType.SYSTEM))).isPresent()));
            String status = BnplApplicationClient.scalar("SELECT status FROM bnpl.bnpl_offer WHERE id = ?::uuid",
                    bought.offer().id());
            long purchases = BnplApplicationClient.count("SELECT count(*) FROM bnpl.purchase WHERE offer_id = ?::uuid",
                    bought.offer().id());
            assertThat(status).as(String.valueOf(outcomes)).isIn("ACCEPTED", "EXPIRED", "OFFERED");
            assertThat(purchases).isEqualTo("ACCEPTED".equals(status) ? 1 : 0);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private record Bought(CreditFixtures.Customer customer, BnplPurchaseWorld.Offer offer,
            BnplApplicationClient.Merchant merchant) {

        Bought(CreditFixtures.Customer customer, BnplPurchaseWorld.Offer offer) {
            this(customer, offer, null);
        }
    }

    /** A funded customer's pay-in-4 offer at a fresh merchant. */
    private Bought payIn4() throws Exception {
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        world.topUpWallet(customer.party(), eur(1_000_00));
        BnplApplicationClient.Merchant merchant = world.merchant();
        return new Bought(customer, world.offered(customer, merchant, GROSS, BnplApplicationClient::payIn4), merchant);
    }

    /** A second credit request of the party's - pay-in-N at another session, awaiting credit's decision. */
    private UUID secondRequest(CreditFixtures.Customer customer) throws Exception {
        BnplApplicationClient.Opened session = world.client.openSession(world.merchant(), GROSS);
        HttpResponse<String> applied = world.client.submit(customer.token(),
                BnplApplicationClient.payInN(session.token(), 3), BnplApplicationClient.key());
        assertThat(applied.statusCode()).as(applied.body()).isEqualTo(202);
        return UUID.fromString(BnplApplicationClient.field(applied.body(), "decisionRequestId"));
    }

    /** A card holder's payment method, through payments' own tokenisation door (the provider simulated). */
    private String cardOf(CreditFixtures.Customer customer) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        provider.succeedsWith(com.finapp.paymentmethods.SimulatedTokenisationAdapter.TOKENISATIONS_PATH, 200,
                "{\"status\":\"tokenised\",\"token\":\"tok_" + suffix + "\",\"brand\":\"Visa\","
                        + "\"last4\":\"4242\",\"expiryMonth\":12,\"expiryYear\":2030}");
        HttpResponse<String> attached = world.client.post("/v1/me/payment-methods",
                "{\"clientToken\":\"ctok_" + suffix + "\"}", customer.token(), null);
        assertThat(attached.statusCode()).as(attached.body()).isEqualTo(201);
        return BnplApplicationClient.field(attached.body(), "id");
    }

    private HttpResponse<String> confirmByCard(CreditFixtures.Customer customer, String method, String sessionToken)
            throws Exception {
        provider.succeedsWith(com.finapp.payments.SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, 200,
                "{\"status\":\"approved\",\"reference\":\"psp_auth-" + UUID.randomUUID() + "\"}");
        provider.succeedsWith(com.finapp.payments.SimulatedCardPspAdapter.CAPTURES_PATH, 200,
                "{\"status\":\"approved\",\"reference\":\"psp_cap-" + UUID.randomUUID() + "\"}");
        return world.client.post("/v1/checkout/sessions/confirmation", "{\"sessionToken\":\"" + sessionToken
                + "\",\"paymentMethodId\":\"" + method + "\"}", customer.token(), null);
    }

    private HttpResponse<String> confirmFromWallet(String token, String sessionToken) throws Exception {
        return world.client.post("/v1/checkout/sessions/confirmation", "{\"sessionToken\":\"" + sessionToken
                + "\",\"instrument\":\"WALLET\"}", token, null);
    }

    /** The acceptance taken while another transaction holds the session row (B3): {@code rival} runs once it waits. */
    private HttpResponse<String> rivalBehindTheAcceptance(Bought bought, Callable<HttpResponse<String>> rival)
            throws Exception {
        Connection offer = BnplPurchaseWorld.held();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Hold the acceptance AFTER its B3: the wallet row it takes at (P) is held here, so the acceptance holds the
            // session while it waits; the rival's write on the session then waits behind the acceptance.
            try (Statement lock = offer.createStatement()) {
                lock.execute("SELECT 1 FROM ledger.ledger_account WHERE id = '"
                        + world.walletOf(bought.customer().party()).value() + "' FOR UPDATE");
            }
            Future<HttpResponse<String>> accepting = pool.submit(() -> world.accept(bought.customer().token(),
                    bought.offer(), BnplApplicationClient.key()));
            int acceptance = awaitPidBlockedBy(pid(offer));
            Future<HttpResponse<String>> lost = pool.submit(rival);
            assertThat(awaitStatementBlockedBy(acceptance)).as("the rival waits on the session the acceptance holds")
                    .contains("checkout_session");
            offer.rollback();
            assertThat(accepting.get(60, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
            return lost.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            BnplPurchaseWorld.closeQuietly(offer);
        }
    }

    /** The offer's expiry moved {@code millis} ahead - the bootstrap superuser, the row's triggers suspended. */
    private static void expiresIn(String offer, long millis) throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap(); Statement statement = root.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            statement.execute("UPDATE bnpl.bnpl_offer SET expires_at = clock_timestamp() + interval '" + millis
                    + " milliseconds', next_step_at = clock_timestamp() + interval '" + millis + " milliseconds' WHERE id"
                    + " = '" + offer + "'");
        }
    }

    /** Headroom deployed: a planted BNPL principal on an obligation-less owner (the capital suite's fixture). */
    private void consume(Money amount) {
        if (!amount.isPositive()) {
            return;
        }
        try (SecurityContext.Scope system = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(BnplPurchaseWorld.flow())) {
            transactions.inTransaction(uow -> {
                com.finapp.bnpl.BnplLedgerAccounts planted = new com.finapp.bnpl.BnplAccounts(ledgerAccountStore, ids,
                        clock).openFor(uow, com.finapp.bnpl.BnplObligationId.next(ids), BnplPurchaseWorld.EUR);
                java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
                return postingService.post(uow, new com.finapp.ledger.PostingCommand("p12t13-deploy:" + UUID.randomUUID(),
                        today, today, "the purchase suite's capital fixture", List.of(
                                new com.finapp.ledger.JournalLine(planted.principal().id(),
                                        com.finapp.ledger.Direction.DEBIT, amount),
                                new com.finapp.ledger.JournalLine(planted.creditBalance().id(),
                                        com.finapp.ledger.Direction.CREDIT, amount))));
            });
        }
    }

    /** The acceptance over the wired ports, one of them failing after its own work at {@code step}. */
    private BnplAcceptances acceptances(String step) {
        BnplCreditDecisions credit = step.equals("consume") ? failingAfter(creditDecisions, BnplCreditDecisions.class,
                "consume") : creditDecisions;
        BnplCapital capital = step.equals("capital") ? failingAfter(bnplCapital, BnplCapital.class, "underLock")
                : bnplCapital;
        BnplMerchants merchants = step.equals("pin") ? failingAfter(bnplMerchants, BnplMerchants.class, "pinFee")
                : bnplMerchants;
        BnplBook book = step.equals("post") ? failingAfter(bnplBook, BnplBook.class, "post") : bnplBook;
        BnplCheckoutSessions sessions = step.equals("complete")
                ? failingAfter(checkoutSessions, BnplCheckoutSessions.class, "completeByFinancing") : checkoutSessions;
        return new BnplAcceptances(offerStore, termsStore, purchaseStore, credit, partyStanding, sessions, merchants,
                capital, bnplWallets, book, outboxWriter, auditWriter, ids, clock);
    }

    @SuppressWarnings("unchecked")
    private static <T> T failingAfter(T real, Class<T> port, String method) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(BnplPurchaseDatabaseTest.class.getClassLoader(),
                new Class<?>[] {port}, (proxy, invoked, args) -> {
                    Object answer;
                    try {
                        answer = invoked.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    if (invoked.getName().equals(method)) {
                        throw new Injected(method);
                    }
                    return answer;
                });
    }

    private static final class Injected extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        Injected(String step) {
            super("injected after " + step);
        }
    }

    private static BnplAcceptances.Acceptance acceptance(String hash) {
        return new BnplAcceptances.Acceptance(hash, new BnplAcceptanceEvidence(UUID.randomUUID(), UUID.randomUUID(),
                "MULTI_FACTOR", Optional.of("203.0.113.7"), Optional.of("finapp-suite/1.0")));
    }

    private static Actor customer(CreditFixtures.Customer customer) {
        return new Actor(customer.party().toString(), ActorType.CUSTOMER);
    }

    private static <R> R act(CreditFixtures.Customer customer, java.util.function.Function<Connection, R> work)
            throws Exception {
        return BnplApplicationClient.act(customer(customer), work);
    }

    /** Exactly one purchase, financing, consumption and order for the offer's session; the offer ACCEPTED. */
    private static void assertOnce(Bought bought) throws Exception {
        String session = bought.offer().session().toString();
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.purchase WHERE checkout_session_id = ?::uuid"
                + " AND status = 'FINANCED'", session)).isEqualTo(1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.merchant_financing f JOIN bnpl.purchase p ON"
                + " p.id = f.purchase_id WHERE p.checkout_session_id = ?::uuid", session)).isEqualTo(1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM checkout.checkout_order WHERE session_ref ="
                + " ?::uuid", session)).isEqualTo(1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM credit.credit_decision_consumption c JOIN"
                + " bnpl.bnpl_offer o ON o.decision_id = c.decision_id WHERE o.id = ?::uuid", bought.offer().id()))
                .isEqualTo(1);
        assertThat(BnplApplicationClient.scalar("SELECT status FROM bnpl.bnpl_offer WHERE id = ?::uuid",
                bought.offer().id())).isEqualTo("ACCEPTED");
    }

    /** Nothing of the offer's purchase: no consumption, purchase, pin, financing entry or order; offer and session open. */
    private static void assertNothing(Bought bought) throws Exception {
        assertNothing(bought, 0);
    }

    /**
     * Nothing of the offer's purchase beside the {@code otherOrders} orders another instrument completed the session with
     * (a card's or a wallet payment's - the one order its session holds, `P12-TSK-028`): no consumption, purchase, pin or
     * financing entry, no order carrying a BNPL financing entry; the offer open.
     */
    private static void assertNothing(Bought bought, int otherOrders) throws Exception {
        String session = bought.offer().session().toString();
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.purchase WHERE checkout_session_id = ?::uuid",
                session)).isZero();
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM credit.credit_decision_consumption c JOIN"
                + " bnpl.bnpl_offer o ON o.decision_id = c.decision_id WHERE o.id = ?::uuid", bought.offer().id()))
                .isZero();
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM checkout.checkout_order WHERE session_ref ="
                + " ?::uuid", session)).as("only the other instrument's order").isEqualTo(otherOrders);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM checkout.checkout_order o WHERE o.session_ref ="
                + " ?::uuid AND EXISTS (SELECT 1 FROM bnpl.merchant_financing f WHERE f.journal_entry_id ="
                + " o.captured_entry_ref)", session)).as("no order carries a BNPL financing").isZero();
        assertThat(BnplApplicationClient.scalar("SELECT status FROM bnpl.bnpl_offer WHERE id = ?::uuid",
                bought.offer().id())).isEqualTo("OFFERED");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM merchant.bnpl_fee_pin p WHERE NOT EXISTS (SELECT 1"
                + " FROM bnpl.purchase b WHERE b.id = p.purchase_id)")).as("no pin without its purchase").isZero();
    }

    /** {@code holding} held in another transaction; the acceptance is seen waiting on {@code table}, then financed. */
    private void assertWaitsBehind(String holding, String table, Bought bought) throws Exception {
        Connection holder = BnplPurchaseWorld.held();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            try (Statement lock = holder.createStatement()) {
                lock.execute(holding);
            }
            Future<HttpResponse<String>> accepting = pool.submit(() -> world.accept(bought.customer().token(),
                    bought.offer(), BnplApplicationClient.key()));
            assertThat(awaitStatementBlockedBy(pid(holder))).as("the acceptance waits on " + table).contains(table);
            holder.rollback();
            HttpResponse<String> accepted = accepting.get(60, TimeUnit.SECONDS);
            assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        } finally {
            pool.shutdownNow();
            BnplPurchaseWorld.closeQuietly(holder);
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
