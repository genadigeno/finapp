package com.finapp.app.lending;

import static com.finapp.app.lending.LoanApplicationClient.field;
import static com.finapp.app.lending.LoanApplicationClient.key;
import static com.finapp.app.lending.LoanApplicationClient.line;
import static com.finapp.app.lending.LoanApplicationClient.loan;
import static com.finapp.app.lending.LoanApplicationClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.LendingFundedAsOfCutoff;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.app.accounting.LendingFundedAsOfCutoffReads;
import com.finapp.app.accounting.LedgerTrialBalanceReads;
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
import com.finapp.lending.LoanOrigination;
import com.finapp.lending.LoanProduct;
import com.finapp.lending.LoanTermsAdministration;
import com.finapp.lending.LoanTermsStore;
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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * {@code committed} as of a cutoff, on loans born through the real origination chain (`P14-TSK-019`; ADR-0096 section 6; {@code INV-ACC-04}): {@code LendingFundedAsOfCutoffReads}' statement against the live figure it must agree with today and the journal it must be the exact complement of. OWED (C5): written and walked by hand; it has never executed.
 *
 * <p>A loan's dates are the database clock's and cannot be chosen, so the cutoff moves instead: today, and the day before. The terms' servicing zone is {@code UTC} ({@code LoanApplicationDatabaseTest#terms}), so a loan's business date is today's.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): the read covers every loan in the database and the suite plants capital. One case, so nothing crosses a case. <strong>Midnight:</strong> a run across 00:00 UTC could open a loan on one date and read on another; the case reads today once from the database, first, and asserts the two dates it names, so such a run fails loudly rather than passes wrongly, and passes on a re-run.
 */
@DateShifted
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("committed as of the cutoff, on real loans (P14-TSK-019)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class SafeguardingCommittedDatabaseTest {

    private static final String OFFERS = "/v1/me/loan-offers";
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final LendingFundedAsOfCutoffReads READS = new LendingFundedAsOfCutoffReads();
    private static final TrialBalanceReads JOURNAL = new LedgerTrialBalanceReads(new JdbcGlMappingStore());

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
                    var proposed = termsAdministration.propose(uow, LoanApplicationDatabaseTest.terms(product), "the suite's terms", employee(), CorrelationId.generate(ids));
                    termsAdministration.approve(uow, proposed.id(), employee(), "reviewed", CorrelationId.generate(ids));
                }
                return null;
            });
        }
        sweep = new LoanOriginationSchedule(origination, lendingTransactions, offerMetrics, Duration.ofSeconds(5), 50, Duration.ofHours(1));
        plantCapital(Money.ofMinorUnits(100_000_00, EUR));
    }

    @Test
    @DisplayName("agreeing with the live headroom today, nothing the day before; a disbursement crossing to the journal on one date, never twice and never in neither; a cancellation releasing it; a line committing nothing")
    void committedIsReadAsOfTheCutoff() throws Exception {
        LocalDate today = LocalDate.parse(scalar("SELECT (statement_timestamp() AT TIME ZONE 'UTC')::date::text"));
        LocalDate yesterday = today.minusDays(1);

        CreditFixtures.Customer waiting = customers.consentingCustomer();
        acceptedLoan(waiting, loan("3617.43", 12, 10));
        assertThat(committed(today)).as("the real statement agrees with the live figure").isEqualTo(liveCommitted()).isEqualTo(3_617_43);
        assertThat(committed(yesterday)).as("opened today, so not yet by yesterday").isZero();
        assertThat(outstanding(today)).isZero();

        CreditFixtures.Customer another = customers.consentingCustomer();
        acceptedLoan(another, loan("5123.87", 12, 10));
        assertThat(committed(today)).isEqualTo(liveCommitted()).isEqualTo(3_617_43 + 5_123_87);
        assertThat(rows(today)).as("counted per loan, no join multiplying one").isEqualTo(2);

        // The sweep disburses both: each moves from committed to the journal, dated today, in one transaction.
        disburse();
        assertThat(liveCommitted()).isZero();
        assertThat(committed(today)).as("its value date is on the cutoff").isZero();
        assertThat(outstanding(today)).as("its journal line is on the cutoff").isEqualTo(3_617_43 + 5_123_87);
        assertThat(committed(today) + outstanding(today)).as("never twice and never in neither").isEqualTo(3_617_43 + 5_123_87);
        assertThat(committed(yesterday) + outstanding(yesterday)).as("nothing stood yesterday, in either term").isZero();

        // A standing lost before the sweep: CANCELLED, nothing posted, released on today's date. That it still stood at a cutoff before its cancellation's date is LendingFundedAsOfCutoffTest's: a loan's dates cannot be chosen here.
        CreditFixtures.Customer cancelled = customers.consentingCustomer();
        acceptedLoan(cancelled, loan("1800.00", 12, 10));
        assertThat(committed(today)).isEqualTo(1_800_00);
        suspend(cancelled.party());
        disburse();
        assertThat(scalar("SELECT count(*) FROM lending.loan WHERE status = 'CANCELLED'")).isEqualTo("1");
        assertThat(liveCommitted()).isZero();
        assertThat(committed(today)).as("the failure_reason's branch: released on its own date").isZero();
        assertThat(outstanding(today)).as("nothing posted").isEqualTo(3_617_43 + 5_123_87);

        // A credit line commits no capital at acceptance (A28), and has no disbursement row to be read.
        acceptedLoan(customers.consentingCustomer(), line("2483.19"));
        assertThat(liveCommitted()).isZero();
        assertThat(committed(today)).isZero();
        assertThat(rows(today)).isZero();
    }

    private long committed(LocalDate cutoff) {
        return lendingTransactions.inSnapshot(uow -> READS.committed(uow, cutoff, Optional.empty())).stream()
                .filter(held -> held.currency().equals("EUR")).map(LendingFundedAsOfCutoff.Committed::minor).reduce(BigInteger.ZERO, BigInteger::add).longValueExact();
    }

    private long rows(LocalDate cutoff) {
        return lendingTransactions.inSnapshot(uow -> READS.committed(uow, cutoff, Optional.empty())).stream()
                .filter(held -> held.currency().equals("EUR")).mapToLong(LendingFundedAsOfCutoff.Committed::loans).sum();
    }

    private long liveCommitted() {
        return lendingTransactions.inSnapshot(uow -> headroom.atRest(uow, EUR)).committed().minorUnits();
    }

    /** The trial balance's own statements, every principal purpose, through {@code cutoff}: the outstanding term as the definition reads it. */
    private long outstanding(LocalDate cutoff) {
        return lendingTransactions.inSnapshot(uow -> JOURNAL.before(uow, cutoff.plusDays(1), Optional.empty())).stream()
                .filter(aggregate -> aggregate.currency().equals("EUR") && LendingFundedAsOfCutoff.PRINCIPAL_PURPOSES.contains(aggregate.purpose()))
                .map(SafeguardingCommittedDatabaseTest::signed).reduce(BigDecimal.ZERO, BigDecimal::add).movePointRight(2).longValueExact();
    }

    private static BigDecimal signed(JournalReads.Aggregate aggregate) {
        return aggregate.side() == com.finapp.accounting.Side.DEBIT ? aggregate.amount() : aggregate.amount().negate();
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

    private void disburse() {
        new LoanDisbursementSchedule(disbursements, lendingTransactions, disbursementMetrics, Duration.ofSeconds(5), 100, Duration.ofMinutes(1)).sweepOnce();
    }

    /** The party's standing lost, as {@code LendingStormCatalogue}'s scenario loses it. */
    private static void suspend(UUID party) throws SQLException {
        try (Connection owner = DatabaseRoles.migrator();
                PreparedStatement update = owner.prepareStatement("UPDATE party.customer SET status = 'SUSPENDED', status_changed_at = GREATEST(now(), status_changed_at) WHERE party_id = ?")) {
            update.setObject(1, party);
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }

    private void plantCapital(Money amount) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(ids)).causing(CausationId.of("p14t19")))) {
            lendingTransactions.inTransaction(uow -> {
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                return postingService.post(uow, new PostingCommand("p14t19-capital:" + UUID.randomUUID(), today, today, "the suite's planted capital", List.of(
                        new JournalLine(ledgerAccountStore.findOperational(uow, AccountPurpose.CASH_AT_BANK, EUR).orElseThrow().id(), Direction.DEBIT, amount),
                        new JournalLine(headroom.capitalAccount(uow, EUR).id(), Direction.CREDIT, amount))));
            });
        }
    }

    private static Actor employee() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }
}
