package com.finapp.app.lending;

import static com.finapp.app.lending.LoanApplicationClient.field;
import static com.finapp.app.lending.LoanApplicationClient.key;
import static com.finapp.app.lending.LoanApplicationClient.line;
import static com.finapp.app.lending.LoanApplicationClient.loan;
import static com.finapp.app.lending.LoanApplicationClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.LendingFundedAsOfCutoff;
import com.finapp.accounting.Side;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.app.accounting.LedgerTrialBalanceReads;
import com.finapp.app.accounting.LendingFundedAsOfCutoffReads;
import com.finapp.app.accounting.SafeguardingMonths;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * <p><strong>A loan's dates are the database clock's, so a case moves them:</strong> {@code session_replication_role = replica} on the owner's connection, {@code LoanAccountDatabaseTest#toFirstDueDate}'s idiom, because a loan's triggers refuse every writer otherwise. That is how {@code value_date > ?} comes to return a row, a cancellation to come after a cutoff and a FINAL to hold a non-zero committed. {@code opened_at} is the only row moved; each case's journal line and {@code value_date} are the real poster's. The terms' servicing zone is {@code UTC} ({@code LoanApplicationDatabaseTest#terms}) but in the one case that moves it, on a version of its own that no other loan pinned, and restores it in a {@code finally}.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): the read covers every loan in the database and the suite plants capital. <strong>No state crosses a case, in any order:</strong> {@link #noLoanLeftPending} sweeps after each, so {@code committed(today)} starts every case at zero, and what a case leaves behind for an earlier cutoff or for the journal, it reads as a difference from its own {@code before}. Only {@link #aFinalHoldsACommittedLoanAndRegeneratesIdenticalAfterItsDisbursement} closes a month, January 1999, and it touches no other case's cutoff.
 * <strong>Midnight:</strong> a run across 00:00 UTC could open a loan on one date and read on another; each case reads today once, first, so such a run fails loudly rather than passes wrongly, and passes on a re-run.
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
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LendingFundedAsOfCutoffReads READS = new LendingFundedAsOfCutoffReads();
    private static final TrialBalanceReads JOURNAL = new LedgerTrialBalanceReads(new JdbcGlMappingStore());
    private static final String FIRST = "3617.43";
    private static final long FIRST_MINOR = 3_617_43;
    private static final String SECOND = "5123.87";
    private static final long SECOND_MINOR = 5_123_87;
    private static final String THIRD = "1800.00";
    private static final long THIRD_MINOR = 1_800_00;

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
    private LocalDate today;
    private LocalDate yesterday;

    @BeforeEach
    void creditTermsAndCapitalInForce() throws Exception {
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
        today = LocalDate.parse(scalar("SELECT (statement_timestamp() AT TIME ZONE 'UTC')::date::text"));
        yesterday = today.minusDays(1);
    }

    @AfterEach
    void noLoanLeftPending() {
        disburse();
    }

    @Test
    @DisplayName("agreeing with the live headroom today, nothing the day before; a disbursement crossing to the journal on one date, never twice and never in neither; a cancellation releasing it; a line committing nothing")
    void committedIsReadAsOfTheCutoff() throws Exception {
        long stoodYesterday = committed(yesterday);
        long outstandingBefore = outstanding(today);
        long outstandingYesterday = outstanding(yesterday);

        acceptedLoan(customers.consentingCustomer(), loan(FIRST, 12, 10));
        assertThat(committed(today)).as("the real statement agrees with the live figure").isEqualTo(liveCommitted()).isEqualTo(FIRST_MINOR);
        assertThat(committed(yesterday)).as("opened today, so not yet by yesterday").isEqualTo(stoodYesterday);
        assertThat(outstanding(today)).isEqualTo(outstandingBefore);

        acceptedLoan(customers.consentingCustomer(), loan(SECOND, 12, 10));
        assertThat(committed(today)).isEqualTo(liveCommitted()).isEqualTo(FIRST_MINOR + SECOND_MINOR);
        assertThat(loans(today)).as("counted per loan, no join multiplying one").isEqualTo(2);

        // The sweep disburses both: each moves from committed to the journal, dated today, in one transaction.
        disburse();
        assertThat(liveCommitted()).isZero();
        assertThat(committed(today)).as("its value date is on the cutoff").isZero();
        assertThat(outstanding(today) - outstandingBefore).as("its journal line is on the cutoff").isEqualTo(FIRST_MINOR + SECOND_MINOR);
        assertThat(committed(yesterday)).as("nothing stood yesterday, in either term").isEqualTo(stoodYesterday);
        assertThat(outstanding(yesterday)).isEqualTo(outstandingYesterday);

        // A standing lost before the sweep: CANCELLED, nothing posted, released on today's date.
        CreditFixtures.Customer lost = customers.consentingCustomer();
        String cancelled = acceptedLoan(lost, loan(THIRD, 12, 10));
        assertThat(committed(today)).isEqualTo(THIRD_MINOR);
        suspend(lost.party());
        disburse();
        assertThat(scalar("SELECT status FROM lending.loan WHERE id = ?::uuid", cancelled)).isEqualTo("CANCELLED");
        assertThat(liveCommitted()).isZero();
        assertThat(committed(today)).as("the failure_reason's branch: released on its own date").isZero();
        assertThat(outstanding(today) - outstandingBefore).as("nothing posted").isEqualTo(FIRST_MINOR + SECOND_MINOR);

        // A credit line commits no capital at acceptance (A28), and has no disbursement row to be read.
        acceptedLoan(customers.consentingCustomer(), line("2483.19"));
        assertThat(liveCommitted()).isZero();
        assertThat(committed(today)).isZero();
        assertThat(loans(today)).isZero();
    }

    @Test
    @DisplayName("M-4: a loan opened before the cutoff and disbursed after it stood committed at it: value_date > ? really returns the row, the journal holds it only from its own date, and at each cutoff it is in one term once")
    void aLoanOpenedBeforeTheCutoffAndDisbursedAfterItStoodCommitted() throws Exception {
        long stoodYesterday = committed(yesterday);
        long outstandingBefore = outstanding(today);
        long outstandingYesterday = outstanding(yesterday);

        String opened = acceptedLoan(customers.consentingCustomer(), loan(FIRST, 12, 10));
        assertThat(committed(yesterday)).isEqualTo(stoodYesterday);
        openedAt(opened, "opened_at - interval '1 day'");

        assertThat(committed(yesterday)).as("value_date IS NULL's branch").isEqualTo(stoodYesterday + FIRST_MINOR);
        assertThat(committed(today)).isEqualTo(FIRST_MINOR);

        disburse();

        assertThat(committed(yesterday)).as("value_date > ?'s branch: it stood then, and nothing that came after moves it").isEqualTo(stoodYesterday + FIRST_MINOR);
        assertThat(outstanding(yesterday)).as("and is in that term alone").isEqualTo(outstandingYesterday);
        assertThat(committed(today)).isZero();
        assertThat(outstanding(today) - outstandingBefore).as("and in this one alone").isEqualTo(FIRST_MINOR);
    }

    @Test
    @DisplayName("M-4: a loan cancelled after the cutoff stood committed at it, and is released from its cancellation's own date")
    void aLoanCancelledAfterTheCutoffStoodCommittedAtIt() throws Exception {
        long stoodYesterday = committed(yesterday);
        long outstandingBefore = outstanding(today);

        CreditFixtures.Customer lost = customers.consentingCustomer();
        String opened = acceptedLoan(lost, loan(FIRST, 12, 10));
        openedAt(opened, "opened_at - interval '1 day'");
        assertThat(committed(yesterday)).isEqualTo(stoodYesterday + FIRST_MINOR);

        suspend(lost.party());
        disburse();

        assertThat(scalar("SELECT status FROM lending.loan WHERE id = ?::uuid", opened)).isEqualTo("CANCELLED");
        assertThat(committed(yesterday)).as("the CASE's date, after this cutoff").isEqualTo(stoodYesterday + FIRST_MINOR);
        assertThat(committed(today)).as("and on this one").isZero();
        assertThat(outstanding(today)).as("nothing posted").isEqualTo(outstandingBefore);
    }

    @Test
    @DisplayName("M-4: the loan's own servicing zone decides its date: opened at 00:00:01 UTC today, it is yesterday's at -14:00 and today's at UTC")
    void aServicingZoneDecidesTheDate() throws Exception {
        // The zone moves on a terms version this loan alone pins: every earlier case's loan keeps its own, so its date cannot move with it, in any order.
        lendingTransactions.inTransaction(uow -> {
            var proposed = termsAdministration.propose(uow, LoanApplicationDatabaseTest.terms(LoanProduct.PERSONAL_LOAN), "a version for one loan", employee(), CorrelationId.generate(ids));
            return termsAdministration.approve(uow, proposed.id(), employee(), "reviewed", CorrelationId.generate(ids));
        });
        long stoodYesterday = committed(yesterday);

        String opened = acceptedLoan(customers.consentingCustomer(), loan(FIRST, 12, 10));
        openedAt(opened, "(DATE '" + today + "' + TIME '00:00:01') AT TIME ZONE 'UTC'");
        assertThat(committed(yesterday)).as("at UTC").isEqualTo(stoodYesterday);
        try {
            servicingZone(opened, "-14:00");
            assertThat(committed(yesterday)).as("at -14:00").isEqualTo(stoodYesterday + FIRST_MINOR);
        } finally {
            servicingZone(opened, "UTC");
        }
        assertThat(committed(yesterday)).as("at UTC again: the zone moved it and nothing else").isEqualTo(stoodYesterday);
    }

    @Test
    @DisplayName("M-4, P14-TSK-023: a real FINAL with a non-zero committed: the term and its loan in the run's bytes, a line and no part of the subtraction, the two positions not apart at all by it, and the real disbursement after the close moving no byte")
    void aFinalHoldsACommittedLoanAndRegeneratesIdenticalAfterItsDisbursement() throws Exception {
        SafeguardingMonths.chartInForce();
        String opened = acceptedLoan(customers.consentingCustomer(), loan(SECOND, 12, 10));
        openedAt(opened, "TIMESTAMPTZ '1999-01-20 12:00:00+00'");
        SafeguardingMonths.closed(SafeguardingMonths.JANUARY);

        SafeguardingMonths.Issued issued = SafeguardingMonths.issued(SafeguardingMonths.JANUARY);

        JsonNode eur = null;
        for (JsonNode element : JSON.readTree(issued.content()).get("body").get("currencies")) {
            if (element.get("currency").asText().equals("EUR")) {
                eur = element;
            }
        }
        assertThat(eur).as("the commitment alone gives the currency its element").isNotNull();
        assertThat(minorOf(eur, "principalCommitted")).isEqualTo(SECOND_MINOR);
        assertThat(eur.get("principalCommittedLoans").asText()).isEqualTo("1");
        assertThat(minorOf(eur, "principalUnrepaid")).as("accepted, not disbursed: a line and no part of the term").isZero();
        assertThat(minorOf(eur, "position") - minorOf(eur, "positionBeforePrincipalUnrepaid")).as("an acceptance moves neither position").isZero();

        disburse();

        assertThat(scalar("SELECT status FROM lending.loan WHERE id = ?::uuid", opened)).as("the live figure moved").isEqualTo("ACTIVE");
        assertThat(SafeguardingMonths.regeneratesIdentical(issued.run())).as("the FINAL did not").isTrue();
    }

    @Test
    @DisplayName("M-4: the crossing's one date through the real poster: every POSTED disbursement's value_date is its journal entry's posting_date. Each side read in its own statement, compared here; none disagrees")
    void theRealPosterDatesItsLineTheValueDate() throws Exception {
        acceptedLoan(customers.consentingCustomer(), loan(FIRST, 12, 10));
        disburse();

        List<String> mismatches = new ArrayList<>();
        int compared = 0;
        try (Connection owner = DatabaseRoles.migrator();
                PreparedStatement disbursed = owner.prepareStatement("SELECT journal_entry_id, value_date FROM lending.loan_disbursement WHERE status = 'POSTED'");
                ResultSet row = disbursed.executeQuery()) {
            while (row.next()) {
                String entry = row.getObject("journal_entry_id", UUID.class).toString();
                String valueDate = row.getObject("value_date", LocalDate.class).toString();
                String postingDate = scalar("SELECT posting_date::text FROM ledger.journal_entry WHERE id = ?::uuid", entry);
                compared++;
                if (!valueDate.equals(postingDate)) {
                    mismatches.add(entry);
                }
            }
        }
        assertThat(compared).as("not vacuous").isGreaterThanOrEqualTo(1);
        assertThat(mismatches).isEmpty();
    }

    private long committed(LocalDate cutoff) {
        return lendingTransactions.inSnapshot(uow -> READS.committed(uow, cutoff, Optional.empty())).stream()
                .filter(held -> held.currency().equals("EUR")).map(LendingFundedAsOfCutoff.Committed::minor).reduce(BigInteger.ZERO, BigInteger::add).longValueExact();
    }

    private long loans(LocalDate cutoff) {
        return lendingTransactions.inSnapshot(uow -> READS.committed(uow, cutoff, Optional.empty())).stream()
                .filter(held -> held.currency().equals("EUR")).mapToLong(LendingFundedAsOfCutoff.Committed::loans).sum();
    }

    private long liveCommitted() {
        return lendingTransactions.inSnapshot(uow -> headroom.atRest(uow, EUR)).committed().minorUnits();
    }

    private static long minorOf(JsonNode figures, String member) {
        return Long.parseLong(figures.get(member).get("minor").asText());
    }

    /** The trial balance's own statements, every principal purpose, through {@code cutoff}: the outstanding term as the definition reads it. */
    private long outstanding(LocalDate cutoff) {
        return lendingTransactions.inSnapshot(uow -> JOURNAL.before(uow, cutoff.plusDays(1), Optional.empty())).stream()
                .filter(aggregate -> aggregate.currency().equals("EUR") && LendingFundedAsOfCutoff.PRINCIPAL_PURPOSES.contains(aggregate.purpose()))
                .map(SafeguardingCommittedDatabaseTest::signed).reduce(BigDecimal.ZERO, BigDecimal::add).movePointRight(2).longValueExact();
    }

    private static BigDecimal signed(JournalReads.Aggregate aggregate) {
        return aggregate.side() == Side.DEBIT ? aggregate.amount() : aggregate.amount().negate();
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

    /** {@code opened_at} set to {@code expression}, on the owner's connection with the triggers off: a loan's identity is frozen for every other writer. */
    private static void openedAt(String loanId, String expression) throws SQLException {
        replica("UPDATE lending.loan SET opened_at = " + expression + " WHERE id = '" + UUID.fromString(loanId) + "'");
    }

    /** The zone of the terms version {@code loanId} pins, likewise. */
    private static void servicingZone(String loanId, String zone) throws SQLException {
        replica("UPDATE lending.loan_terms SET servicing_zone = '" + zone + "' WHERE terms_version_id = (SELECT offer.terms_version_id FROM lending.loan loan"
                + " JOIN lending.loan_offer offer ON offer.id = loan.offer_id WHERE loan.id = '" + UUID.fromString(loanId) + "')");
    }

    private static void replica(String update) throws SQLException {
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                assertThat(statement.executeUpdate(update)).isEqualTo(1);
            }
            owner.commit();
        }
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
                LocalDate day = LocalDate.now(ZoneOffset.UTC);
                return postingService.post(uow, new PostingCommand("p14t19-capital:" + UUID.randomUUID(), day, day, "the suite's planted capital", List.of(
                        new JournalLine(ledgerAccountStore.findOperational(uow, AccountPurpose.CASH_AT_BANK, EUR).orElseThrow().id(), Direction.DEBIT, amount),
                        new JournalLine(headroom.capitalAccount(uow, EUR).id(), Direction.CREDIT, amount))));
            });
        }
    }

    private static Actor employee() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }
}
