package com.finapp.app.lending;

import static com.finapp.app.lending.LoanApplicationClient.count;
import static com.finapp.app.lending.LoanApplicationClient.field;
import static com.finapp.app.lending.LoanApplicationClient.key;
import static com.finapp.app.lending.LoanApplicationClient.line;
import static com.finapp.app.lending.LoanApplicationClient.loan;
import static com.finapp.app.lending.LoanApplicationClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.PurposeBalances;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LendingProofs;
import com.finapp.lending.LoanDisbursements;
import com.finapp.lending.LoanOrigination;
import com.finapp.lending.LoanProduct;
import com.finapp.lending.LoanTermsAdministration;
import com.finapp.lending.LoanTermsStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lending's operations reports against a real database (`P11-TSK-030`; PHASE_11_PLAN.md sections 9 and 15;
 * {@code INV-AUD-01}, {@code INV-AUD-02}): loans and a line born through the real origination chain, disbursed, serviced
 * past a due date and drawn over the doors; then the four reports, read over HTTP by a {@code LENDING_OFFICER}, each equal
 * to what the ledger's own API derives account by account - the portfolio per product and balance, tied to the ledger's
 * book and the trial balance, with the proofs' counts; the delinquency per bucket with its dues; the accrual per product
 * tied to its entries; the capital equal to the headroom the acceptance itself judges - each recorded
 * {@code lending.ReportRead} at every serving. A malformed date is 422 before any read, unrecorded; a date the platform
 * has not had answers empty. Only an investigator reads them.
 *
 * <p>The ten-instance answer: two report services - two instances - read the portfolio concurrently while draws commit
 * beside them; every report read is WHOLE (its rows tie to the ledger's book and the trial balance, which a torn read across
 * statements would break), and at rest the two instances serve the same report.
 *
 * <p>The needle walk over lending's doors: everything the context logged while the flows and reports ran is searched for
 * every loan and party identifier and every planted amount - none appears; nor does a party in any report.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): the reports read every loan in the database, and the
 * suite plants EUR lending capital by a posting from {@code CASH_AT_BANK} (the fixture's, never a path the platform has).
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("lending's operations reports (P11-TSK-030)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class LendingReportsDatabaseTest {

    private static final String OFFERS = "/v1/me/loan-offers";
    private static final String LOANS = "/v1/me/loans";
    private static final String REPORTS = "/v1/operator/reports/lending/";
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<AccountPurpose> PURPOSES = List.of(AccountPurpose.LOAN_PRINCIPAL,
            AccountPurpose.LOAN_PRINCIPAL_DUE, AccountPurpose.LOAN_INTEREST_ACCRUED, AccountPurpose.LOAN_INTEREST_DUE,
            AccountPurpose.LOAN_FEES_DUE, AccountPurpose.LOAN_CREDIT_BALANCE);

    @LocalServerPort private int port;
    @Autowired private LoanTermsAdministration termsAdministration;
    @Autowired private LoanTermsStore termsStore;
    @Autowired private LendingTransactions lendingTransactions;
    @Autowired private LoanOrigination origination;
    @Autowired private LoanOfferMetrics offerMetrics;
    @Autowired private IdGenerator ids;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private PurposeBalances<Connection> purposeBalances;
    @Autowired private LendingCapitalHeadroom headroom;
    @Autowired private LoanDisbursements disbursements;
    @Autowired private LoanDisbursementMetrics disbursementMetrics;
    @Autowired private com.finapp.lending.LoanServicing loanServicing;
    @Autowired private LoanServicingMetrics servicingMetrics;
    @Autowired private Authorization authorization;
    @Autowired private LendingProofs lendingProofs;
    @Autowired private AuditWriter<Connection> auditWriter;
    @Autowired private LendingReports reports;

    private LoanApplicationClient client;
    private CreditFixtures customers;
    private LoanOriginationSchedule sweep;

    @BeforeEach
    void creditTermsAndCapitalInForce() {
        client = new LoanApplicationClient(port);
        customers = new CreditFixtures(port);
        CreditFixtures.seedsInForce();
        for (LoanProduct product : LoanProduct.values()) {
            lendingTransactions.inTransaction(uow -> {
                if (termsStore.active(uow, product).isEmpty()) {
                    var proposed = termsAdministration.propose(uow, LoanApplicationDatabaseTest.terms(product),
                            "the suite's terms", employee(), CorrelationId.generate(ids));
                    termsAdministration.approve(uow, proposed.id(), employee(), "reviewed", CorrelationId.generate(ids));
                }
                return null;
            });
        }
        sweep = new LoanOriginationSchedule(origination, lendingTransactions, offerMetrics, Duration.ofSeconds(5), 50,
                Duration.ofHours(1));
        Money free = lendingTransactions.inTransaction(uow -> headroom.atRest(uow, EUR)).headroom();
        if (free.minorUnits() < 50_000_00) {
            plantCapital(Money.ofMinorUnits(100_000_00, EUR));
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("the four reports equal the ledger's own derivation account by account - the portfolio tied to the book and"
            + " the trial balance with the proofs counted, the delinquency by bucket, the accrual tied to its entries, the"
            + " capital the acceptance's own headroom - each recorded lending.ReportRead; no loan, party or amount in any"
            + " log line, no party in any report")
    void theReportsEqualTheLedgerAndAreRecorded(CapturedOutput output) throws Exception {
        // A loan disbursed and serviced a day past its first due date - accrued, billed, delinquent.
        CreditFixtures.Customer borrower = customers.consentingCustomer();
        String pastDue = acceptedLoan(borrower, loan("3617.43", 12, 10));
        disburse();
        LoanAccountDatabaseTest.toFirstDueDate(pastDue, 1);
        service();
        // A loan accepted and not yet disbursed - capital committed.
        CreditFixtures.Customer waiting = customers.consentingCustomer();
        String pending = acceptedLoan(waiting, loan("5123.87", 12, 10));
        // A line drawn over the door.
        CreditFixtures.Customer drawer = customers.consentingCustomer();
        String drawn = acceptedLoan(drawer, line("2483.19"));
        HttpResponse<String> draw = client.post(LOANS + "/" + drawn + "/draws",
                "{\"amount\":\"417.29\",\"currency\":\"EUR\"}", drawer.token(), key());
        assertThat(draw.statusCode()).as(draw.body()).isEqualTo(201);

        LocalDate today = LocalDate.parse(scalar("SELECT (statement_timestamp() AT TIME ZONE 'UTC')::date::text"));
        String officer = sessionWith(RoleName.LENDING_OFFICER);
        long recorded = reportReads();

        // ---- the portfolio
        JsonNode portfolio = read("portfolio?date=" + today, officer);
        assertThat(portfolio.path("known").asBoolean()).isTrue();
        Map<String, Map<AccountPurpose, Long>> derived = derivedByProduct(today);
        for (JsonNode row : portfolio.path("rows")) {
            String product = row.path("product").asString();
            Map<AccountPurpose, Long> expected = derived.getOrDefault(product, Map.of());
            assertThat(row.path("principalMinor").asLong()).as(product).isEqualTo(expected.getOrDefault(
                    AccountPurpose.LOAN_PRINCIPAL, 0L));
            assertThat(row.path("principalDueMinor").asLong()).as(product).isEqualTo(expected.getOrDefault(
                    AccountPurpose.LOAN_PRINCIPAL_DUE, 0L));
            assertThat(row.path("interestAccruedMinor").asLong()).as(product).isEqualTo(expected.getOrDefault(
                    AccountPurpose.LOAN_INTEREST_ACCRUED, 0L));
            assertThat(row.path("interestDueMinor").asLong()).as(product).isEqualTo(expected.getOrDefault(
                    AccountPurpose.LOAN_INTEREST_DUE, 0L));
            assertThat(row.path("feesDueMinor").asLong()).as(product).isEqualTo(expected.getOrDefault(
                    AccountPurpose.LOAN_FEES_DUE, 0L));
            assertThat(row.path("creditBalanceMinor").asLong()).as(product).isEqualTo(-expected.getOrDefault(
                    AccountPurpose.LOAN_CREDIT_BALANCE, 0L));
            long states = row.path("pendingDisbursement").asLong() + row.path("active").asLong()
                    + row.path("closing").asLong() + row.path("closed").asLong() + row.path("cancelled").asLong();
            assertThat(states).as("%s's accounts by state", product).isEqualTo(count(
                    "SELECT count(*) FROM lending.loan WHERE lower(product) = ?", product));
        }
        assertThat(portfolio.path("rows").valueStream().map(row -> row.path("product").asString()).toList())
                .contains("personal_loan", "credit_line");
        assertThat(portfolio.path("rows").valueStream().filter(row -> row.path("product").asString().equals("personal_loan"))
                .findFirst().orElseThrow().path("pendingDisbursement").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(portfolio.path("currencies")).isNotEmpty().allSatisfy(tie -> {
            assertThat(tie.path("tiesToProducts").asBoolean()).as("the book ties to the products").isTrue();
            assertThat(tie.path("trialBalanceZero").asBoolean()).as("the trial balance is zero").isTrue();
        });
        long loans = count("SELECT count(*) FROM lending.loan");
        JsonNode proofs = portfolio.path("proofs");
        assertThat(proofs.path("holding").asLong() + proofs.path("failing").asLong()).isEqualTo(loans);
        assertThat(proofs.path("identical").asLong() + proofs.path("diverged").asLong()).isEqualTo(loans);
        assertThat(proofs.path("failingLoans").size()).isEqualTo((int) Math.min(100, proofs.path("failing").asLong()));

        // ---- the delinquency
        JsonNode delinquency = read("delinquency?date=" + today, officer);
        assertThat(delinquency.path("known").asBoolean()).isTrue();
        Map<String, Long> buckets = new TreeMap<>();
        for (Map.Entry<String, List<String>> bucket : loansByBucket().entrySet()) {
            Map<AccountPurpose, Long> due = derived(bucket.getValue(), today);
            JsonNode row = delinquency.path("rows").valueStream()
                    .filter(candidate -> (candidate.path("product").asString() + "|" + candidate.path("bucket").asString())
                            .equals(bucket.getKey()))
                    .findFirst().orElseThrow(() -> new AssertionError("no row for " + bucket.getKey()));
            assertThat(row.path("accounts").asLong()).isEqualTo(bucket.getValue().size());
            assertThat(row.path("principalDueMinor").asLong()).isEqualTo(due.getOrDefault(AccountPurpose.LOAN_PRINCIPAL_DUE, 0L));
            assertThat(row.path("interestDueMinor").asLong()).isEqualTo(due.getOrDefault(AccountPurpose.LOAN_INTEREST_DUE, 0L));
            assertThat(row.path("feesDueMinor").asLong()).isEqualTo(due.getOrDefault(AccountPurpose.LOAN_FEES_DUE, 0L));
            buckets.put(bucket.getKey(), row.path("accounts").asLong());
        }
        assertThat(buckets).as("the past-due loan stands in b1_1_29").containsKey("personal_loan|b1_1_29");
        assertThat(delinquency.path("rows").size()).isEqualTo(buckets.size());

        // ---- the accrual, for yesterday: every accrual born for it, tied to its entries
        LocalDate yesterday = today.minusDays(1);
        JsonNode accrual = read("accrual?date=" + yesterday, officer);
        assertThat(accrual.path("rows")).isNotEmpty();
        for (JsonNode row : accrual.path("rows")) {
            String product = row.path("product").asString();
            assertThat(row.path("accounts").asLong()).isEqualTo(count("SELECT count(*) FROM lending.interest_accrual x"
                    + " JOIN lending.loan l ON l.id = x.loan_id WHERE lower(l.product) = ? AND x.accrual_date = ?::date",
                    product, yesterday.toString()));
            assertThat(row.path("accruedMinor").asLong()).isEqualTo(count("SELECT COALESCE(sum(x.posted_minor), 0)"
                    + " FROM lending.interest_accrual x JOIN lending.loan l ON l.id = x.loan_id WHERE lower(l.product) = ?"
                    + " AND x.accrual_date = ?::date", product, yesterday.toString()));
            assertThat(row.path("ledgerMinor").asLong()).isEqualTo(row.path("accruedMinor").asLong());
            assertThat(row.path("ties").asBoolean()).isTrue();
        }

        // ---- the capital: the very headroom the acceptance judges, at rest
        JsonNode capital = read("capital?date=" + today, officer);
        LendingCapitalHeadroom.Headroom judged = lendingTransactions.inSnapshot(uow -> headroom.atRest(uow, EUR));
        JsonNode eur = capital.path("rows").valueStream().filter(row -> row.path("currency").asString().equals("EUR"))
                .findFirst().orElseThrow();
        assertThat(eur.path("recognisedMinor").asLong()).isEqualTo(judged.capital().minorUnits());
        assertThat(eur.path("committedMinor").asLong()).isEqualTo(judged.committed().minorUnits())
                .isGreaterThanOrEqualTo(512_387);
        assertThat(eur.path("outstandingMinor").asLong()).isEqualTo(judged.outstanding().minorUnits());
        assertThat(eur.path("headroomMinor").asLong()).isEqualTo(judged.headroom().minorUnits());
        assertThat(eur.path("lendingFundedMinor").asLong()).isEqualTo(judged.lendingFunded().minorUnits());
        assertThat(capital.path("rows").size()).isEqualTo(com.finapp.ledger.SupportedCurrencies.ALL.size());

        // ---- every serving recorded, naming the report and the date - never a figure
        assertThat(reportReads()).isEqualTo(recorded + 4);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'lending.ReportRead'"
                + " AND target_type = 'lending_report' AND target_id IN ('portfolio', 'delinquency', 'accrual', 'capital')"
                + " AND outcome = 'SUCCEEDED' AND change_summary LIKE '%known=true%'")).isGreaterThanOrEqualTo(4);

        // ---- the needle walk: no loan, party or planted amount in anything logged, no party in any report
        Set<String> identifiers = Set.of(pastDue, pending, drawn, borrower.party().toString(), waiting.party().toString(),
                drawer.party().toString());
        String logged = output.getAll();
        assertThat(identifiers).allSatisfy(identifier -> assertThat(logged).doesNotContain(identifier));
        for (String amount : List.of("3617.43", "361743", "5123.87", "512387", "2483.19", "248319", "417.29", "41729")) {
            assertThat(Pattern.compile("(?<![0-9])" + Pattern.quote(amount) + "(?![0-9])").matcher(logged).find())
                    .as("the amount %s in a log line", amount).isFalse();
        }
        String served = portfolio.toString() + delinquency + accrual + capital;
        assertThat(Set.of(borrower.party().toString(), waiting.party().toString(), drawer.party().toString()))
                .allSatisfy(party -> assertThat(served).doesNotContain(party));
    }

    @Test
    @DisplayName("ten readers on two instances read the portfolio while draws commit beside them: every report is whole -"
            + " tied to the book and the trial balance - every serving recorded; at rest both instances serve one report")
    void twoInstancesReadOneInstantAndEveryReadIsWhole() throws Exception {
        CreditFixtures.Customer drawer = customers.consentingCustomer();
        String lineId = acceptedLoan(drawer, line("3000.00"));
        LendingReports other = new LendingReports(lendingTransactions, lendingProofs, auditWriter, ids, Clock.systemUTC());
        List<LendingReports> instances = List.of(reports, other);
        String today = scalar("SELECT (statement_timestamp() AT TIME ZONE 'UTC')::date::text");
        long recorded = reportReads();

        AtomicBoolean drawing = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(11);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> writer = pool.submit(() -> {
                start.await();
                int draws = 0;
                while (drawing.get() && draws < 20) {
                    HttpResponse<String> drawn = client.post(LOANS + "/" + lineId + "/draws",
                            "{\"amount\":\"10.00\",\"currency\":\"EUR\"}", drawer.token(), key());
                    assertThat(drawn.statusCode()).as(drawn.body()).isEqualTo(201);
                    draws++;
                }
                return draws;
            });
            List<Future<List<LendingReports.LendingPortfolioReport>>> readers = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                LendingReports instance = instances.get(i % 2);
                readers.add(pool.submit(() -> {
                    start.await();
                    List<LendingReports.LendingPortfolioReport> read = new ArrayList<>();
                    for (int round = 0; round < 3; round++) {
                        read.add(asInvestigator(() -> instance.portfolio(today)));
                    }
                    return read;
                }));
            }
            start.countDown();
            for (Future<List<LendingReports.LendingPortfolioReport>> reader : readers) {
                for (LendingReports.LendingPortfolioReport report : reader.get(5, TimeUnit.MINUTES)) {
                    assertThat(report.known()).isTrue();
                    assertThat(report.currencies()).isNotEmpty().allSatisfy(tie -> {
                        assertThat(tie.tiesToProducts()).as("a report read whole, never torn by a committing draw").isTrue();
                        assertThat(tie.trialBalanceZero()).isTrue();
                    });
                }
            }
            drawing.set(false);
            assertThat(writer.get(5, TimeUnit.MINUTES)).as("draws committed beside the readers").isPositive();
        } finally {
            pool.shutdownNow();
        }
        assertThat(reportReads()).as("every serving recorded, once").isEqualTo(recorded + 30);

        // At rest - nothing committing - two instances reading one instant serve one report.
        LendingReports.LendingPortfolioReport first = asInvestigator(() -> reports.portfolio(today));
        LendingReports.LendingPortfolioReport second = asInvestigator(() -> other.portfolio(today));
        assertThat(second).isEqualTo(first);
        assertThat(asInvestigator(() -> other.capital(today))).isEqualTo(asInvestigator(() -> reports.capital(today)));
    }

    @Test
    @DisplayName("a malformed or missing date is 422 api.ValidationFailed before any read and records nothing; a date the"
            + " platform has not had - before its first business day or after any zone's today - answers empty, recorded;"
            + " only a LENDING_INVESTIGATE holder reads: 401 without a session, 403 to a customer and a servicing agent")
    void datesAndAccess() throws Exception {
        String officer = sessionWith(RoleName.LENDING_OFFICER);
        long recorded = reportReads();
        for (String report : List.of("portfolio", "delinquency", "accrual", "capital")) {
            for (String query : List.of("?date=2026-13-01", "?date=20261014", "?date=2026-02-30", "", "?date=")) {
                HttpResponse<String> refused = client.get(REPORTS + report + query, officer);
                assertThat(refused.statusCode()).as(report + query + " " + refused.body()).isEqualTo(422);
                assertThat(refused.body()).contains("api.ValidationFailed");
            }
        }
        assertThat(reportReads()).as("a refused date reads and records nothing").isEqualTo(recorded);
        for (String report : List.of("portfolio", "delinquency", "accrual", "capital")) {
            for (String date : List.of("2025-12-31", "2099-01-01")) {
                JsonNode empty = read(report + "?date=" + date, officer);
                assertThat(empty.path("known").asBoolean()).as(report + " " + date).isFalse();
                assertThat(empty.path("rows")).as(report + " " + date).isEmpty();
                assertThat(empty.path("date").asString()).isEqualTo(date);
            }
        }
        assertThat(reportReads()).as("an empty report is still recorded").isEqualTo(recorded + 8);

        String agent = sessionWith(RoleName.LOAN_SERVICING_AGENT);
        CreditFixtures.Customer customer = customers.consentingCustomer();
        for (String report : List.of("portfolio", "delinquency", "accrual", "capital")) {
            String path = REPORTS + report + "?date=2026-10-01";
            assertThat(client.get(path, null).statusCode()).as(report).isEqualTo(401);
            assertThat(client.get(path, customer.token()).statusCode()).as(report).isEqualTo(403);
            assertThat(client.get(path, agent).statusCode()).as(report).isEqualTo(403);
        }
        assertThat(reportReads()).as("a refused caller reads and records nothing").isEqualTo(recorded + 8);
    }

    // ------------------------------------------------------------------ the oracle: the ledger's own API, account by account

    /** Per product (lowercase): DR - CR of every purpose over every loan's accounts, value-dated on or before {@code day}. */
    private Map<String, Map<AccountPurpose, Long>> derivedByProduct(LocalDate day) throws Exception {
        Map<String, List<String>> byProduct = new TreeMap<>();
        try (Connection app = DatabaseRoles.application(); var select = app.prepareStatement(
                "SELECT lower(product), id::text FROM lending.loan"); var rows = select.executeQuery()) {
            while (rows.next()) {
                byProduct.computeIfAbsent(rows.getString(1), ignored -> new ArrayList<>()).add(rows.getString(2));
            }
        }
        Map<String, Map<AccountPurpose, Long>> derived = new TreeMap<>();
        for (Map.Entry<String, List<String>> product : byProduct.entrySet()) {
            derived.put(product.getKey(), derived(product.getValue(), day));
        }
        return derived;
    }

    /** DR - CR per purpose over {@code loans}' accounts, folded through Money from the ledger's dated lines. */
    private Map<AccountPurpose, Long> derived(List<String> loans, LocalDate day) {
        Map<AccountPurpose, Money> sums = new EnumMap<>(AccountPurpose.class);
        lendingTransactions.inSnapshot(uow -> {
            for (String loan : loans) {
                List<LedgerAccount> accounts = ledgerAccountStore.findAllOwned(uow, UUID.fromString(loan));
                Map<com.finapp.ledger.LedgerAccountId, AccountPurpose> purposes = accounts.stream()
                        .collect(Collectors.toMap(LedgerAccount::id, LedgerAccount::purpose));
                for (PurposeBalances.DatedLine line : purposeBalances.datedLinesOn(uow, purposes.keySet(), day)) {
                    Money signed = line.direction() == Direction.DEBIT ? line.amount() : line.amount().negated();
                    sums.merge(purposes.get(line.account()), signed, Money::plus);
                }
            }
            return null;
        });
        Map<AccountPurpose, Long> minor = new EnumMap<>(AccountPurpose.class);
        sums.forEach((purpose, money) -> {
            if (PURPOSES.contains(purpose)) {
                minor.put(purpose, money.minorUnits());
            }
        });
        return minor;
    }

    /** {@code product|bucket} (lowercase) to the loans in servicing in it - the latest recorded change, CURRENT when none. */
    private static Map<String, List<String>> loansByBucket() throws Exception {
        Map<String, List<String>> buckets = new TreeMap<>();
        try (Connection app = DatabaseRoles.application(); var select = app.prepareStatement(
                "SELECT lower(l.product), lower(COALESCE((SELECT c.bucket FROM lending.loan_condition_event c"
                        + " WHERE c.loan_id = l.id AND c.kind = 'DELINQUENCY' ORDER BY c.business_date DESC LIMIT 1),"
                        + " 'CURRENT')), l.id::text FROM lending.loan l WHERE l.status IN ('ACTIVE', 'CLOSING')");
                var rows = select.executeQuery()) {
            while (rows.next()) {
                buckets.computeIfAbsent(rows.getString(1) + "|" + rows.getString(2), ignored -> new ArrayList<>())
                        .add(rows.getString(3));
            }
        }
        return buckets;
    }

    // ------------------------------------------------------------------ fixtures

    private JsonNode read(String path, String session) throws Exception {
        HttpResponse<String> response = client.get(REPORTS + path, session);
        assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static long reportReads() throws Exception {
        return count("SELECT count(*) FROM platform.audit_record WHERE operation = 'lending.ReportRead'");
    }

    private <T> T asInvestigator(java.util.function.Supplier<T> read) {
        try (SecurityContext.Scope actor = SecurityContext.enter(employee());
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(ids)))) {
            return read.get();
        }
    }

    private String acceptedLoan(CreditFixtures.Customer customer, String application) throws Exception {
        String offer = offered(customer, application);
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        HttpResponse<String> accepted = client.post(OFFERS + "/" + offer + "/acceptance",
                "{\"termsSha256\":\"" + hash + "\"}", customer.token(), key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        return field(accepted.body(), "loanId");
    }

    private String offered(CreditFixtures.Customer customer, String body) throws Exception {
        HttpResponse<String> submitted = client.submit(customer.token(), body, key());
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        UUID request = UUID.fromString(field(submitted.body(), "decisionRequestId"));
        assertThat(CreditFixtures.decide(request)).isEqualTo("APPROVED");
        lendingTransactions.inTransaction(uow -> origination.hintRequest(uow, request));
        sweep.sweepOnce();
        return field(client.read(customer.token(), field(submitted.body(), "applicationId")).body(), "offerId");
    }

    private void disburse() {
        new LoanDisbursementSchedule(disbursements, lendingTransactions, disbursementMetrics, Duration.ofSeconds(5), 100,
                Duration.ofMinutes(1)).sweepOnce();
    }

    private void service() {
        LoanServicingSchedule servicing = new LoanServicingSchedule(loanServicing, lendingTransactions, servicingMetrics,
                Duration.ofSeconds(5), 500, Duration.ofMinutes(15));
        while (servicing.sweepOnce() == 500) {
            // every due account serviced
        }
    }

    private void plantCapital(Money amount) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(ids)).causing(CausationId.of("p11t30")))) {
            lendingTransactions.inTransaction(uow -> {
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                return postingService.post(uow, new PostingCommand("p11t30-capital:" + UUID.randomUUID(), today, today,
                        "the suite's planted capital", List.of(
                                new JournalLine(ledgerAccountStore.findOperational(uow, AccountPurpose.CASH_AT_BANK, EUR)
                                        .orElseThrow().id(), Direction.DEBIT, amount),
                                new JournalLine(headroom.capitalAccount(uow, EUR).id(), Direction.CREDIT, amount))));
            });
        }
    }

    private void assign(UUID identity, RoleName role) throws Exception {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(ids)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
    }

    private String sessionWith(RoleName role) throws Exception {
        String login = "lr." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String password = "a-perfectly-fine-pw-7";
        assertThat(client.post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Grace"
                + " Hopper\",\"password\":\"" + password + "\"}", null, key()).statusCode()).isEqualTo(201);
        assign(UUID.fromString(scalar("SELECT id::text FROM identity.identity WHERE login_identifier = ?", login)), role);
        return field(client.post("/v1/authentications", "{\"loginIdentifier\":\"" + login + "\",\"password\":\""
                + password + "\"}", null, key()).body(), "sessionToken");
    }

    private static Actor employee() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }
}
