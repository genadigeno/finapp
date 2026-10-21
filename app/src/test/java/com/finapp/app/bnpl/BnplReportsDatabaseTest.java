package com.finapp.app.bnpl;

import static com.finapp.app.bnpl.BnplPurchaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.database.DateShifted;
import com.finapp.bnpl.BnplOrigination;
import com.finapp.bnpl.BnplProofs;
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
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.TransactionRunner;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * BNPL's operations reports against a real database (`P12-TSK-026`; lending's {@code LendingReportsDatabaseTest}): the four
 * reports' sums equal the ledger's own derivation, line by line - the portfolio's balances each obligation account's
 * DR - CR, its book tied to every {@code BNPL_OBLIGATION} account and the trial balance; the merchant financing's exposure
 * the financings' net less the refunds' payable debit and tied to the payables' lines; the refund's impact what its own
 * entry left on the receivables; the delinquency's dues the ledger's - each served once and recorded {@code bnpl.ReportRead}
 * once; two instances reading one instant serve one report, and ten readers while a refund commits beside them each read
 * a whole one; the door is {@code BNPL_INVESTIGATE}'s (403 without it, the passing negative); a malformed date is 422 and
 * unrecorded, an unknown one empty and recorded; and the needle walk over BNPL's doors - no agreement, purchase, party or
 * amount in anything the context logged while the flows and the reports ran ({@code INV-AUD-02}).
 *
 * <p><strong>A database of its own</strong> ({@code own-container}). Written by `P12-TSK-026` and <strong>owed a first
 * run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@DateShifted
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("BNPL's operations reports (P12-TSK-026)")
class BnplReportsDatabaseTest {

    private static final long GROSS = 600_00L;
    private static final String REPORTS = "/v1/operator/reports/bnpl/";
    private static final String OPERATOR_PURCHASES = "/v1/operator/bnpl-purchases/";
    private static final String REFUND = "{\"amount\":\"180.00\",\"currency\":\"EUR\",\"reason\":\"goods returned\"}";

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
    @Autowired private BnplProofs bnplProofs;
    @Autowired private AuditWriter<Connection> auditWriter;
    @Autowired private IdGenerator idGenerator;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private TransactionRunner settlementTransactionRunner;

    private BnplPurchaseWorld world;

    @BeforeEach
    void world() throws Exception {
        world = new BnplPurchaseWorld(port, authorization, enablements, pricing, feeSchedules, terms, transactions,
                origination, postingService, ledgerAccountStore, headroom, loanWallets).ready();
        // Recognised capital: the book report states the three-way proof, whose capital arm fails a raw plant
        // (P12-TSK-028).
        new BnplRecognisedCapital(world.client, authorization, headroom, transactions, reception, parsing, acceptance,
                settlementTransactionRunner).headroomAtLeast(eur(100_000_00L));
    }

    @Test
    @DisplayName("the four reports equal the ledger's own derivation - balances, book, trial balance, exposure, the refund's"
            + " entry, the dues - each served and recorded once; and nothing logged names an agreement, a purchase, a"
            + " party or an amount")
    void theReportsEqualTheLedgerAndAreRecorded(CapturedOutput output) throws Exception {
        Bought first = bought();
        Bought second = bought();
        String operator = world.client.operatorSession(authorization, RoleName.LEDGER_OPERATOR);
        HttpResponse<String> refunded = world.client.post(OPERATOR_PURCHASES + second.purchase() + "/refunds", REFUND,
                operator, BnplApplicationClient.key());
        assertThat(refunded.statusCode()).as(refunded.body()).isEqualTo(201);
        String day = BnplApplicationClient.scalar("SELECT business_date::text FROM bnpl.purchase WHERE id = ?::uuid",
                second.purchase());
        String officer = world.client.operatorSession(authorization, RoleName.BNPL_OFFICER);
        long before = reportReads();

        HttpResponse<String> portfolio = world.client.get(REPORTS + "portfolio?date=" + day, officer);
        assertThat(portfolio.statusCode()).as(portfolio.body()).isEqualTo(200);
        long outstanding = BnplApplicationClient.count("SELECT COALESCE(sum(CASE WHEN ln.direction = 'DEBIT' THEN"
                + " ln.amount_minor ELSE -ln.amount_minor END), 0) FROM ledger.ledger_account a JOIN ledger.journal_line ln"
                + " ON ln.ledger_account_id = a.id JOIN ledger.journal_entry e ON e.id = ln.entry_id"
                + " WHERE a.owner_kind = 'BNPL_OBLIGATION' AND a.purpose <> 'BNPL_CREDIT_BALANCE' AND e.value_date <= ?::date",
                day);
        assertThat(portfolio.body()).contains("\"known\":true", "\"tiesToProducts\":true", "\"trialBalanceZero\":true",
                "\"threeWayHolds\":true", "\"outstandingMinor\":" + outstanding, "\"failing\":0", "\"diverged\":0");

        HttpResponse<String> financing = world.client.get(REPORTS + "merchant-financing?date=" + day, officer);
        assertThat(financing.statusCode()).as(financing.body()).isEqualTo(200);
        long exposure = BnplApplicationClient.count("SELECT (SELECT sum(net_minor) FROM bnpl.merchant_financing)"
                + " - COALESCE((SELECT sum(payable_debit_minor) FROM bnpl.bnpl_refund), 0)");
        assertThat(financing.body()).contains("\"exposureMinor\":" + exposure, "\"ledgerPayableMinor\":" + exposure,
                "\"tiesToLedger\":true", "\"merchantLegsFailing\":0");

        HttpResponse<String> refunds = world.client.get(REPORTS + "refunds?date=" + day, officer);
        assertThat(refunds.statusCode()).as(refunds.body()).isEqualTo(200);
        long cancelled = BnplApplicationClient.count("SELECT sum(cancelled_unbilled_minor + cancelled_billed_minor)"
                + " FROM bnpl.bnpl_refund WHERE value_date = ?::date", day);
        assertThat(refunds.body()).contains("\"door\":\"operator\"", "\"principalCancelledMinor\":" + cancelled,
                "\"tiesToLedger\":true");

        HttpResponse<String> delinquency = world.client.get(REPORTS + "delinquency?date=" + day, officer);
        assertThat(delinquency.statusCode()).as(delinquency.body()).isEqualTo(200);
        assertThat(delinquency.body()).contains("\"bucket\":\"current\"");

        assertThat(reportReads() - before).as("each serving recorded once").isEqualTo(4);
        // The needle walk: nothing the context logged names an agreement, a purchase, a party or a planted amount.
        String logged = output.getAll();
        for (Bought bought : List.of(first, second)) {
            assertThat(logged).doesNotContain(bought.purchase()).doesNotContain(bought.agreement())
                    .doesNotContain(bought.party());
        }
        assertThat(logged).doesNotContain("180.00").doesNotContain("600.00").doesNotContain(Long.toString(outstanding));
    }

    @Test
    @DisplayName("two instances reading one instant serve one report; ten readers while a refund commits beside them each"
            + " read a whole one - tied, whatever commits")
    void twoInstancesReadOneInstantAndEveryReadIsWhole() throws Exception {
        Bought bought = bought();
        String day = BnplApplicationClient.scalar("SELECT business_date::text FROM bnpl.purchase WHERE id = ?::uuid",
                bought.purchase());
        BnplReports one = instance();
        BnplReports other = instance();
        assertThat(asInvestigator(() -> other.portfolio(day))).isEqualTo(asInvestigator(() -> one.portfolio(day)));
        assertThat(asInvestigator(() -> other.merchantFinancing(day)))
                .isEqualTo(asInvestigator(() -> one.merchantFinancing(day)));

        String operator = world.client.operatorSession(authorization, RoleName.LEDGER_OPERATOR);
        ExecutorService pool = Executors.newFixedThreadPool(11);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<BnplReports.BnplPortfolioReport>> reads = new ArrayList<>();
            for (int reader = 0; reader < 10; reader++) {
                BnplReports instance = reader % 2 == 0 ? one : other;
                reads.add(pool.submit((Callable<BnplReports.BnplPortfolioReport>) () -> {
                    start.await();
                    return asInvestigator(() -> instance.portfolio(day));
                }));
            }
            Future<Integer> refund = pool.submit((Callable<Integer>) () -> {
                start.await();
                return world.client.post(OPERATOR_PURCHASES + bought.purchase() + "/refunds", REFUND, operator,
                        BnplApplicationClient.key()).statusCode();
            });
            start.countDown();
            assertThat(refund.get(60, TimeUnit.SECONDS)).isEqualTo(201);
            for (Future<BnplReports.BnplPortfolioReport> read : reads) {
                assertThat(read.get(60, TimeUnit.SECONDS).currencies()).allSatisfy(tie -> {
                    assertThat(tie.tiesToProducts()).isTrue();
                    assertThat(tie.trialBalanceZero()).isTrue();
                });
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("without BNPL_INVESTIGATE every report is 403; a malformed or missing date is 422 api.ValidationFailed"
            + " and records nothing; a date the platform has not had is empty and recorded")
    void datesAndAccess() throws Exception {
        String outsider = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        for (String report : List.of("portfolio", "merchant-financing", "refunds", "delinquency")) {
            assertThat(world.client.get(REPORTS + report + "?date=2026-10-15", outsider).statusCode()).as(report)
                    .isEqualTo(403);
        }
        String officer = world.client.operatorSession(authorization, RoleName.BNPL_OFFICER);
        long before = reportReads();
        for (String date : List.of("", "?date=2026-02-30", "?date=15-10-2026")) {
            HttpResponse<String> refused = world.client.get(REPORTS + "portfolio" + date, officer);
            assertThat(refused.statusCode()).as(date).isEqualTo(422);
            assertThat(refused.body()).contains("api.ValidationFailed");
        }
        assertThat(reportReads()).isEqualTo(before);
        HttpResponse<String> future = world.client.get(REPORTS + "refunds?date=2999-01-01", officer);
        assertThat(future.statusCode()).isEqualTo(200);
        assertThat(future.body()).contains("\"known\":false", "\"rows\":[]");
        assertThat(reportReads()).isEqualTo(before + 1);
    }

    // ------------------------------------------------------------------

    record Bought(String purchase, String agreement, String party) {}

    private Bought bought() throws Exception {
        CreditFixtures.Customer owner = world.customers.consentingCustomer();
        world.topUpWallet(owner.party(), eur(1_000_00));
        BnplPurchaseWorld.Offer offer = world.offered(owner, world.merchant(), GROSS, BnplApplicationClient::payIn4);
        HttpResponse<String> accepted = world.accept(owner.token(), offer, BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        return new Bought(BnplApplicationClient.field(accepted.body(), "purchaseId"),
                BnplApplicationClient.field(accepted.body(), "agreementId"), owner.party().toString());
    }

    /** Another instance's reports - they hold no state, so a second one over the same database is a second instance. */
    private BnplReports instance() {
        return new BnplReports(BnplReports.over(transactions), new JdbcBnplReportReads(), bnplProofs::prove, auditWriter,
                idGenerator, Clock.systemUTC());
    }

    @SuppressWarnings("try")
    private static <R> R asInvestigator(java.util.function.Supplier<R> report) {
        try (SecurityContext.Scope actor = SecurityContext.enter(new Actor("bnpl-investigator", ActorType.EMPLOYEE));
                CorrelationContext.Scope correlation = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("bnpl-report-reader")))) {
            return report.get();
        }
    }

    private static long reportReads() throws Exception {
        return BnplApplicationClient.count("SELECT count(*) FROM platform.audit_record WHERE operation = 'bnpl.ReportRead'");
    }
}
