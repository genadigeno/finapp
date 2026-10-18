package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.bnpl.BnplOrigination;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplTermsAdministration;
import com.finapp.credit.CreditProduct;
import com.finapp.identity.Authorization;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
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
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The BNPL purchase suites' world (`P12-TSK-012`, `P12-TSK-013`): a BNPL-enabled merchant, a consenting customer on a
 * {@code MULTI_FACTOR} session, an offer reached through the real doors - the application, credit's real decision, the
 * origination sweep - and the acceptance through its door; recognised lending capital and wallet money planted through the
 * ledger's own {@code PostingService} (the fixtures standing in for bank recognition and payments' pay-in, never a bnpl
 * path). Every figure read back from the rows.
 */
@SuppressWarnings("try")
final class BnplPurchaseWorld {

    static final CurrencyCode EUR = CurrencyCode.of("EUR");
    static final String OFFERS = "/v1/me/bnpl-offers";
    static final String AGREEMENTS = "/v1/me/bnpl-agreements";
    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static boolean creditInForce;

    final BnplApplicationClient client;
    final CreditFixtures customers;
    private final Authorization authorization;
    private final BnplEnablements enablements;
    private final BnplPricing pricing;
    private final FeeSchedules feeSchedules;
    private final BnplTermsAdministration terms;
    private final BnplTransactions transactions;
    private final BnplOrigination origination;
    private final PostingService postings;
    private final LedgerAccountStore<Connection> ledgerAccounts;
    private final LendingCapitalHeadroom headroom;
    private final LoanWallets wallets;

    BnplPurchaseWorld(int port, Authorization authorization, BnplEnablements enablements, BnplPricing pricing,
            FeeSchedules feeSchedules, BnplTermsAdministration terms, BnplTransactions transactions,
            BnplOrigination origination, PostingService postings, LedgerAccountStore<Connection> ledgerAccounts,
            LendingCapitalHeadroom headroom, LoanWallets wallets) {
        this.client = new BnplApplicationClient(port);
        this.customers = new CreditFixtures(port);
        this.authorization = authorization;
        this.enablements = enablements;
        this.pricing = pricing;
        this.feeSchedules = feeSchedules;
        this.terms = terms;
        this.transactions = transactions;
        this.origination = origination;
        this.postings = postings;
        this.ledgerAccounts = ledgerAccounts;
        this.headroom = headroom;
        this.wallets = wallets;
    }

    /** Credit's BNPL policies and each product's terms in force - once per database. */
    BnplPurchaseWorld ready() throws Exception {
        if (!creditInForce) {
            CreditFixtures.seedsInForce();
            for (CreditProduct product : CreditProduct.of(CreditProduct.Family.BNPL)) {
                CreditFixtures.bnplInForce(product);
            }
            creditInForce = true;
        }
        if (!BnplApplicationClient.termsActive(BnplProduct.BNPL_PAY_IN_4)) {
            BnplApplicationClient.termsInForce(terms, transactions,
                    BnplTermsDesk.terms(BnplTermsDeskTest.request(BnplTermsDeskTest.payIn4(), Map.of())));
        }
        if (!BnplApplicationClient.termsActive(BnplProduct.BNPL_PAY_IN_N)) {
            BnplApplicationClient.termsInForce(terms, transactions,
                    BnplTermsDesk.terms(BnplTermsDeskTest.request(BnplTermsDeskTest.payInN(), Map.of())));
        }
        return this;
    }

    /** A trading merchant, BNPL-enabled by two persons, both products priced at 4% with no fixed part. */
    BnplApplicationClient.Merchant merchant() throws Exception {
        BnplApplicationClient.Merchant merchant = client.tradingMerchant(authorization);
        BnplApplicationClient.bnplEnabled(merchant, enablements, pricing, feeSchedules, "0.04", 0);
        return merchant;
    }

    /** An offer: its id, the session it would pay, the session's token and the terms hash its read shows. */
    record Offer(String id, UUID session, String sessionToken, String hash) {}

    /** {@code customer} applies at a fresh session of {@code merchant} for {@code body}, credit approves, the sweep offers. */
    Offer offered(CreditFixtures.Customer customer, BnplApplicationClient.Merchant merchant, long grossMinor,
            java.util.function.Function<String, String> body) throws Exception {
        BnplApplicationClient.Opened session = client.openSession(merchant, grossMinor);
        HttpResponse<String> applied = client.submit(customer.token(), body.apply(session.token()),
                BnplApplicationClient.key());
        assertThat(applied.statusCode()).as(applied.body()).isEqualTo(202);
        UUID request = UUID.fromString(BnplApplicationClient.field(applied.body(), "decisionRequestId"));
        assertThat(CreditFixtures.decide(request)).isEqualTo("APPROVED");
        transactions.inTransaction(uow -> origination.hintRequest(uow, request));
        new BnplOriginationSchedule(origination, transactions, new BnplOriginationMetrics(new SimpleMeterRegistry()),
                Duration.ofSeconds(5), 50, Duration.ofSeconds(30)).sweepOnce();
        String application = BnplApplicationClient.field(applied.body(), "applicationId");
        String offer = BnplApplicationClient.field(client.read(customer.token(), application).body(), "offerId");
        String hash = BnplApplicationClient.field(client.get(OFFERS + "/" + offer, customer.token()).body(),
                "termsSha256");
        return new Offer(offer, session.id(), session.token(), hash);
    }

    /** The acceptance door. */
    HttpResponse<String> accept(String token, Offer offer, String key) throws Exception {
        return accept(token, offer.id(), offer.hash(), key);
    }

    HttpResponse<String> accept(String token, String offer, String hash, String key) throws Exception {
        return client.post(OFFERS + "/" + offer + "/acceptance", "{\"termsSha256\":\"" + hash + "\"}", token, key);
    }

    /** {@code amount} of recognised lending capital planted: DR CASH_AT_BANK / CR LENDING_CAPITAL. */
    void plantCapital(Money amount) {
        posted("p12t13-capital:", uow -> List.of(
                new JournalLine(ledgerAccounts.findOperational(uow, AccountPurpose.CASH_AT_BANK, EUR).orElseThrow().id(),
                        Direction.DEBIT, amount),
                new JournalLine(headroom.capitalAccount(uow, EUR).id(), Direction.CREDIT, amount)));
    }

    /** The currency's free headroom set to exactly {@code target} by planting capital when short. */
    void headroomAtLeast(Money target) {
        Money free = transactions.inTransaction(uow -> {
            LendingCapitalHeadroom.Headroom now = headroom.atRest(uow, EUR);
            return now.capital().minus(now.committed()).minus(now.outstanding());
        });
        if (free.compareTo(target) < 0) {
            plantCapital(target.minus(free));
        }
    }

    /** The customer's wallet - opened through accounts' door when absent - and its ledger account. */
    LedgerAccountId walletOf(UUID party) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            return transactions.inTransaction(uow -> wallets.walletFor(uow, party, EUR).orElseThrow());
        }
    }

    /** {@code amount} paid into the party's wallet - the fixture standing in for payments' pay-in. */
    void topUpWallet(UUID party, Money amount) {
        LedgerAccountId wallet = walletOf(party);
        posted("p12t13-topup:", uow -> List.of(
                new JournalLine(ledgerAccounts.findOperational(uow, AccountPurpose.CASH_AT_BANK, EUR).orElseThrow().id(),
                        Direction.DEBIT, amount),
                new JournalLine(wallet, Direction.CREDIT, amount)));
    }

    private void posted(String keyPrefix, java.util.function.Function<Connection, List<JournalLine>> lines) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            transactions.inTransaction(uow -> {
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                return postings.post(uow, new PostingCommand(keyPrefix + UUID.randomUUID(), today, today,
                        "the BNPL purchase suites' fixture", lines.apply(uow)));
            });
        }
    }

    static Correlation flow() {
        return Correlation.startingWith(CorrelationId.generate(IDS)).causing(CausationId.of("p12t13"));
    }

    static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    /** DR - CR of an obligation's accounts of {@code purpose} - the migrator's own sum, an independent reading. */
    static long obligationBalance(String obligation, AccountPurpose purpose) throws Exception {
        return BnplApplicationClient.count("SELECT coalesce(sum(CASE l.direction WHEN 'DEBIT' THEN l.amount_minor ELSE"
                + " -l.amount_minor END), 0) FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id ="
                + " l.ledger_account_id WHERE a.owner_ref = ?::uuid AND a.purpose = ?", obligation, purpose.name());
    }

    /** CR - DR of one ledger account - its credit-normal balance, summed by the migrator. */
    static long creditBalanceOf(UUID account) throws Exception {
        return BnplApplicationClient.count("SELECT coalesce(sum(CASE l.direction WHEN 'CREDIT' THEN l.amount_minor ELSE"
                + " -l.amount_minor END), 0) FROM ledger.journal_line l WHERE l.ledger_account_id = ?", account);
    }

    /** The merchant's payable account. */
    static UUID payableOf(BnplApplicationClient.Merchant merchant) throws Exception {
        return UUID.fromString(BnplApplicationClient.scalar("SELECT id::text FROM ledger.ledger_account WHERE owner_ref"
                + " = ?::uuid AND purpose = 'MERCHANT_PAYABLE' AND currency = 'EUR'", merchant.id()));
    }

    static void closeQuietly(Connection connection) {
        try {
            connection.rollback();
            connection.close();
        } catch (java.sql.SQLException ignored) {
            // closing a held connection a case already released
        }
    }

    static Connection held() throws java.sql.SQLException {
        Connection connection = DatabaseRoles.application();
        connection.setAutoCommit(false);
        return connection;
    }
}
