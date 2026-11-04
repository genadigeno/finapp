package com.finapp.app.lending;

import static com.finapp.app.lending.LoanApplicationClient.count;
import static com.finapp.app.lending.LoanApplicationClient.field;
import static com.finapp.app.lending.LoanApplicationClient.key;
import static com.finapp.app.lending.LoanApplicationClient.loan;
import static com.finapp.app.lending.LoanApplicationClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.app.api.DecimalText;
import com.finapp.app.credit.BnplCreditExposure;
import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.database.DateShifted;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LoanDisbursements;
import com.finapp.lending.LoanId;
import com.finapp.lending.LoanOrigination;
import com.finapp.lending.LoanProduct;
import com.finapp.lending.LoanServicing;
import com.finapp.lending.LoanServicingStore;
import com.finapp.lending.LoanTerms;
import com.finapp.lending.LoanTermsAdministration;
import com.finapp.lending.LoanTermsStore;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyConflictException;
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
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * A loan's write-off over the wired platform, through its own desk (`P14-TSK-021`): what only the assembled SQL can say. The operator
 * reports read it as of a date, before and on it; the loan arm of credit exposure v4 leaves the entry's lines out; a written-off loan is
 * no accrual gap; and the keys. A loan is really accepted and disbursed, moved back by the storm's born-in-the-past fixture so its first
 * instalment is more than ninety days old, and defaulted by the real servicing step: the suite's terms do not collect.
 *
 * <p>OWED (C5): written and walked by hand, not yet run. It commits ledger lines and plants rows, so it takes a container of its own.
 */
@DateShifted
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings("try") // The Scopes are used for their close side effect (the idiom).
@DisplayName("a loan's write-off over the wired platform (P14-TSK-021)")
class LoanWriteOffLedgerDatabaseTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final String OFFERS = "/v1/me/loan-offers";

    @LocalServerPort private int port;
    @Autowired private LoanTermsAdministration termsAdministration;
    @Autowired private LoanTermsStore termsStore;
    @Autowired private LendingTransactions lendingTransactions;
    @Autowired private LoanOrigination origination;
    @Autowired private LoanOfferMetrics offerMetrics;
    @Autowired private IdGenerator ids;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private LendingCapitalHeadroom headroom;
    @Autowired private LoanDisbursements disbursements;
    @Autowired private LoanDisbursementMetrics disbursementMetrics;
    @Autowired private LoanServicing loanServicing;
    @Autowired private LoanServicingStore servicingStore;
    @Autowired private LendingReports reports;
    @Autowired private BnplCreditExposure exposure;
    @Autowired private LoanWriteOffDesk desk;

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
        sweep = new LoanOriginationSchedule(origination, lendingTransactions, offerMetrics, Duration.ofSeconds(5), 50, Duration.ofHours(1));
        Money free = lendingTransactions.inTransaction(uow -> headroom.atRest(uow, EUR)).headroom();
        if (free.minorUnits() < 50_000_00) {
            plantCapital(Money.ofMinorUnits(100_000_00, EUR));
        }
    }

    @Test
    @DisplayName("the portfolio and the capital report read it as of a date: before the write-off the loan is active and its principal outstanding,"
            + " on its date it is written off and the principal has moved to the written-off term; an earlier date's reports do not move")
    void theReportsReadItAsOfItsDate() throws Exception {
        Defaulted defaulted = defaulted();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LendingReports.LendingPortfolioRow yesterdayBefore = personalLoans(today.minusDays(1));
        LendingReports.LendingPortfolioRow todayBefore = personalLoans(today);
        LendingReports.LendingCapitalRow capitalYesterdayBefore = capital(today.minusDays(1));
        LendingReports.LendingCapitalRow capitalTodayBefore = capital(today);

        LoanWriteOffDesk.LoanWriteOffReceipt proposed = as(operator(), () -> desk.propose(defaulted.loan(), key(), "uncollectable after collections"));
        as(operator(), () -> desk.approve(defaulted.loan(), proposed.writeOffId(), key(), attesting(proposed)));
        long principal = DecimalText.parse(proposed.principalTotal()).movePointRight(2).longValueExact();
        assertThat(principal).isPositive();

        assertThat(personalLoans(today.minusDays(1))).as("an earlier date is what it was").isEqualTo(yesterdayBefore);
        assertThat(capital(today.minusDays(1))).isEqualTo(capitalYesterdayBefore);
        assertThat(yesterdayBefore.writtenOff()).as("nothing is written off before its date").isZero();
        assertThat(capitalYesterdayBefore.writtenOffMinor()).isZero();
        LendingReports.LendingPortfolioRow todayAfter = personalLoans(today);
        assertThat(todayAfter.writtenOff()).isEqualTo(todayBefore.writtenOff() + 1);
        assertThat(todayAfter.active()).isEqualTo(todayBefore.active() - 1);
        LendingReports.LendingCapitalRow capitalAfter = capital(today);
        assertThat(capitalAfter.writtenOffMinor()).isEqualTo(capitalTodayBefore.writtenOffMinor() + principal);
        assertThat(capitalAfter.outstandingMinor()).isEqualTo(capitalTodayBefore.outstandingMinor() - principal);
        assertThat(capitalAfter.headroomMinor()).as("unchanged by the loss").isEqualTo(capitalTodayBefore.headroomMinor());
        assertThat(capitalAfter.lendingFundedMinor()).isEqualTo(capitalTodayBefore.lendingFundedMinor());
    }

    @Test
    @DisplayName("the loan arm of credit exposure v4 leaves the entry's lines out: the customer's exposure is what it was, and a repayment's credit"
            + " to the same principal, which is no write-off, still lowers it")
    void theExposureStillCountsIt() throws Exception {
        Defaulted defaulted = defaulted();
        Money before = exposureOf(defaulted);
        assertThat(before.isPositive()).isTrue();
        LoanWriteOffDesk.LoanWriteOffReceipt proposed = as(operator(), () -> desk.propose(defaulted.loan(), key(), "uncollectable after collections"));
        as(operator(), () -> desk.approve(defaulted.loan(), proposed.writeOffId(), key(), attesting(proposed)));
        assertThat(exposureOf(defaulted)).as("the loss hands the customer no new credit").isEqualTo(before);

        // The control: the exclusion is the key's, not the purpose's. A posting under any other key still counts.
        LoanId id = LoanId.of(UUID.fromString(defaulted.loan()));
        Money one = Money.ofMinorUnits(1_00, EUR);
        try (SecurityContext.Scope system = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            lendingTransactions.inTransaction(uow -> {
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                return postingService.post(uow, new PostingCommand("p14t21-control:" + UUID.randomUUID(), today, today, "the suite's control", List.of(
                        new JournalLine(ledgerAccountStore.findOperational(uow, AccountPurpose.CASH_AT_BANK, EUR).orElseThrow().id(), Direction.DEBIT, one),
                        new JournalLine(ledgerAccountStore.findOwned(uow, id.value(), AccountPurpose.LOAN_PRINCIPAL, EUR).orElseThrow().id(), Direction.CREDIT, one))));
            });
        }
        assertThat(exposureOf(defaulted)).isEqualTo(before.minus(one));
    }

    @Test
    @DisplayName("a written-off loan is no accrual gap: with its last dates gone it is not listed, and the same rows under ACTIVE are (the control)")
    void aWrittenOffLoanLeavesNoAccrualGap() throws Exception {
        Defaulted defaulted = defaulted();
        LoanWriteOffDesk.LoanWriteOffReceipt proposed = as(operator(), () -> desk.propose(defaulted.loan(), key(), "uncollectable after collections"));
        as(operator(), () -> desk.approve(defaulted.loan(), proposed.writeOffId(), key(), attesting(proposed)));
        LocalDate gap = LocalDate.now(ZoneOffset.UTC).minusDays(5);
        asOwner("DELETE FROM lending.interest_accrual WHERE loan_id = '" + defaulted.loan() + "' AND accrual_date >= DATE '" + gap + "'");

        java.util.Map<CurrencyCode, LocalDate> written = lendingTransactions.inTransaction(servicingStore::oldestUnaccruedByCurrency);
        assertThat(written.get(EUR) == null || written.get(EUR).isAfter(gap)).as("%s", written).isTrue();

        asOwner("UPDATE lending.loan SET status = 'ACTIVE' WHERE id = '" + defaulted.loan() + "'");
        try {
            java.util.Map<CurrencyCode, LocalDate> active = lendingTransactions.inTransaction(servicingStore::oldestUnaccruedByCurrency);
            assertThat(active.get(EUR)).as("the same rows under ACTIVE are the gap").isEqualTo(gap);
        } finally {
            asOwner("UPDATE lending.loan SET status = 'WRITTEN_OFF' WHERE id = '" + defaulted.loan() + "'");
        }
    }

    @Test
    @DisplayName("the key: the same key replays the same receipt and posts once (a timeout's retry and a response lost after the commit are this);"
            + " the same key with another body conflicts")
    void theKeyReplaysAndAnotherBodyConflicts() throws Exception {
        Defaulted defaulted = defaulted();
        Actor proposer = operator();
        String proposalKey = key();
        LoanWriteOffDesk.LoanWriteOffReceipt proposed = as(proposer, () -> desk.propose(defaulted.loan(), proposalKey, "uncollectable"));
        assertThat(as(proposer, () -> desk.propose(defaulted.loan(), proposalKey, "uncollectable"))).isEqualTo(proposed);
        assertThatExceptionOfType(IdempotencyConflictException.class)
                .isThrownBy(() -> as(proposer, () -> desk.propose(defaulted.loan(), proposalKey, "another reason")));

        Actor approver = operator();
        String approvalKey = key();
        LoanWriteOffDesk.LoanWriteOffReceipt approved = as(approver, () -> desk.approve(defaulted.loan(), proposed.writeOffId(), approvalKey, attesting(proposed)));
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(as(approver, () -> desk.approve(defaulted.loan(), proposed.writeOffId(), approvalKey, attesting(proposed))))
                .as("the retry after a lost response answers the recorded receipt").isEqualTo(approved);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:lending.write-off:" + defaulted.loan())).isEqualTo(1);
        assertThatExceptionOfType(IdempotencyConflictException.class).isThrownBy(() -> as(approver, () ->
                desk.approve(defaulted.loan(), proposed.writeOffId(), approvalKey, new LoanWriteOffController.LoanWriteOffApprovalRequest("EUR",
                        proposed.principal(), proposed.principalDue(), proposed.interestAccrued(), proposed.interestDue(), proposed.feesDue(),
                        proposed.revision() + 1, "read and agreed"))));
    }

    // ------------------------------------------------------------------ plumbing

    private record Defaulted(String loan, UUID party) {}

    /**
     * A loan accepted, disbursed to its wallet and moved back so its first instalment fell due over ninety days ago on its repayment day,
     * its status history moved back with it - then the real step, until the default is recorded.
     */
    private Defaulted defaulted() throws Exception {
        CreditFixtures.Customer borrower = customers.consentingCustomer();
        String id = acceptedLoan(borrower, loan("1000.00", 12, 10));
        new LoanDisbursementSchedule(disbursements, lendingTransactions, disbursementMetrics, Duration.ofSeconds(5), 100, Duration.ofMinutes(1)).sweepOnce();
        assertThat(scalar("SELECT status FROM lending.loan WHERE id = ?::uuid", id)).isEqualTo("ACTIVE");
        LoanTerms terms = LoanApplicationDatabaseTest.terms(LoanProduct.PERSONAL_LOAN);
        LocalDate firstDue = LocalDate.now(ZoneOffset.UTC).minusDays(130).withDayOfMonth(10);
        LendingStormWorld.bornInThePast(id, terms, firstDue, Instant.now());
        asOwner("UPDATE lending.loan_event SET occurred_at = occurred_at - interval '200 days' WHERE loan_id = '" + id + "'");
        LoanId loanId = LoanId.of(UUID.fromString(id));
        for (int step = 0; step < 12 && defaults(id) == 0; step++) {
            try (SecurityContext.Scope system = SecurityContext.enterSystem();
                    CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
                lendingTransactions.inTransaction(uow -> loanServicing.service(uow, loanId));
            }
        }
        assertThat(defaults(id)).as("defaulted past ninety days").isEqualTo(1);
        return new Defaulted(id, borrower.party());
    }

    private static long defaults(String loan) throws Exception {
        return count("SELECT count(*) FROM lending.loan_condition_event WHERE kind = 'DEFAULT' AND defaulted AND loan_id = ?::uuid", loan);
    }

    private String acceptedLoan(CreditFixtures.Customer customer, String application) throws Exception {
        HttpResponse<String> submitted = client.submit(customer.token(), application, key());
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        UUID request = UUID.fromString(field(submitted.body(), "decisionRequestId"));
        assertThat(CreditFixtures.decide(request)).isEqualTo("APPROVED");
        lendingTransactions.inTransaction(uow -> origination.hintRequest(uow, request));
        sweep.sweepOnce();
        String offer = field(client.read(customer.token(), field(submitted.body(), "applicationId")).body(), "offerId");
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        HttpResponse<String> accepted = client.post(OFFERS + "/" + offer + "/acceptance", "{\"termsSha256\":\"" + hash + "\"}", customer.token(), key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        return field(accepted.body(), "loanId");
    }

    private LendingReports.LendingPortfolioRow personalLoans(LocalDate day) {
        return as(employee(), () -> reports.portfolio(day.toString())).rows().stream()
                .filter(row -> row.product().equalsIgnoreCase(LoanProduct.PERSONAL_LOAN.name()) && row.currency().equals("EUR")).findFirst().orElseThrow();
    }

    private LendingReports.LendingCapitalRow capital(LocalDate day) {
        return as(employee(), () -> reports.capital(day.toString())).rows().stream().filter(row -> row.currency().equals("EUR")).findFirst().orElseThrow();
    }

    private Money exposureOf(Defaulted defaulted) {
        return lendingTransactions.inTransaction(uow -> exposure.outstandingFor(uow, defaulted.party(), EUR));
    }

    /** A person of their own party - a registered login, so the wired acting party can place them. */
    private Actor operator() throws Exception {
        CreditFixtures.Customer person = customers.consentingCustomer();
        return new Actor(scalar("SELECT id::text FROM identity.identity WHERE party_id = ?::uuid", person.party().toString()), ActorType.EMPLOYEE);
    }

    private static Actor employee() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }

    private static LoanWriteOffController.LoanWriteOffApprovalRequest attesting(LoanWriteOffDesk.LoanWriteOffReceipt read) {
        return new LoanWriteOffController.LoanWriteOffApprovalRequest(read.currency(), read.principal(), read.principalDue(), read.interestAccrued(),
                read.interestDue(), read.feesDue(), read.revision(), "read and agreed");
    }

    private <R> R as(Actor actor, Supplier<R> door) {
        try (SecurityContext.Scope scope = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(flow())) {
            return door.get();
        }
    }

    private Correlation flow() {
        return Correlation.startingWith(CorrelationId.generate(ids)).causing(CausationId.of("p14t21"));
    }

    private void plantCapital(Money amount) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            lendingTransactions.inTransaction(uow -> {
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                return postingService.post(uow, new PostingCommand("p14t21-capital:" + UUID.randomUUID(), today, today,
                        "the suite's planted capital", List.of(
                                new JournalLine(ledgerAccountStore.findOperational(uow, AccountPurpose.CASH_AT_BANK, EUR).orElseThrow().id(), Direction.DEBIT, amount),
                                new JournalLine(headroom.capitalAccount(uow, EUR).id(), Direction.CREDIT, amount))));
            });
        }
    }

    /** {@code sql} as the bootstrap superuser, the database's triggers suspended for this one transaction. */
    private static void asOwner(String sql) throws Exception {
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                statement.execute(sql);
            }
            owner.commit();
        }
    }
}
