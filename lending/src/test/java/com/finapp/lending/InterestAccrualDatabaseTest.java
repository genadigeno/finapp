package com.finapp.lending;

import static com.finapp.lending.DisbursementWorld.BALANCES;
import static com.finapp.lending.DisbursementWorld.EUR;
import static com.finapp.lending.DisbursementWorld.IDS;
import static com.finapp.lending.DisbursementWorld.LEDGER_ACCOUNTS;
import static com.finapp.lending.DisbursementWorld.LOAN_STORE;
import static com.finapp.lending.DisbursementWorld.POSTINGS;
import static com.finapp.lending.DisbursementWorld.TERMS_STORE;
import static com.finapp.lending.DisbursementWorld.acceptedLine;
import static com.finapp.lending.DisbursementWorld.acceptedLoan;
import static com.finapp.lending.DisbursementWorld.activate;
import static com.finapp.lending.DisbursementWorld.asOwner;
import static com.finapp.lending.DisbursementWorld.awaitBlockedBy;
import static com.finapp.lending.DisbursementWorld.cashAtBank;
import static com.finapp.lending.DisbursementWorld.correlation;
import static com.finapp.lending.DisbursementWorld.count;
import static com.finapp.lending.DisbursementWorld.disburse;
import static com.finapp.lending.DisbursementWorld.execute;
import static com.finapp.lending.DisbursementWorld.inOneTransaction;
import static com.finapp.lending.DisbursementWorld.loanAccount;
import static com.finapp.lending.DisbursementWorld.netDebit;
import static com.finapp.lending.DisbursementWorld.pid;
import static com.finapp.lending.DisbursementWorld.race;
import static com.finapp.lending.DisbursementWorld.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.PostingCommand;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Daily interest accrual against a real database (`P11-TSK-016`; {@code lending V010}, {@code PHASE_11_PLAN.md}
 * sections 7.1 T-svc, 7.4, 7.5, 12.3 and 12.8 rule 2; ADR-0094; {@code INV-LND-02}, {@code INV-LND-09},
 * {@code INV-LND-10}, {@code INV-IDEM-02}, {@code INV-BAL-05}): every count read from the rows and the ledger - the
 * accrual run twice, by ten sweepers and crashed mid-run accrues each date once (scenario 4); instance skew never accrues
 * early or twice; missed days caught up oldest first; the base derived from journal lines, never the projection; 29
 * February one ordinary day; zero days a row and no entry; an account without an accrual start nothing; a line's accrual
 * following its drawn principal through its statement cycles; a failing account never holding the page; the step taking
 * the loan's lock - and every rank held for every writer by raw SQL.
 *
 * <p>Loans are disbursed by the real T-dis ({@link DisbursementWorld}); an elapsed history is made by <em>backdating</em>
 * a loan's dated facts as the bootstrap owner with the triggers suspended - the disbursement entry's dates, the
 * disbursement's value date, the accrual start, the schedule's dates, the opening instant - the fixture's clock moving,
 * never a path the platform has. The database clock itself is the real one.
 */
@Tag("database")
@DisplayName("daily interest accrual (P11-TSK-016)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class InterestAccrualDatabaseTest {

    static final JdbcLoanServicingStore SERVICING_STORE = new JdbcLoanServicingStore();
    static final LoanServicing SERVICING = servicing(SERVICING_STORE);
    static final BigDecimal RATE = new BigDecimal("0.0899");

    // ------------------------------------------------------------------ scenario 4

    @Test
    @DisplayName("scenario 4 - the accrual run twice accrues once: twice in a row, by ten racing steps, by ten racing"
            + " sweepers over three accounts, and crashed mid-run then re-run - one row and one entry per (account,"
            + " date); beneath L3 a duplicate row is a unique violation and an entry already under the key is a defect,"
            + " never a second posting")
    void theAccrualRunTwiceAccruesOnce() throws Exception {
        // Twice in a row.
        LoanId twice = disbursed(4_000_00, 12);
        backdate(twice, 3);
        assertThat(service(twice).posted()).isEqualTo(3);
        LoanServicing.Outcome again = service(twice);
        assertThat(again.posted() + again.zero()).as("the second run finds nothing to accrue").isZero();
        assertAccruedOnce(twice, 3);

        // Ten racing steps on one account: serialised on L3, the later nine find every date accrued.
        LoanId raced = disbursed(4_100_00, 12);
        backdate(raced, 3);
        List<LoanServicing.Outcome> outcomes = race(10, index -> service(raced));
        assertThat(outcomes.stream().mapToInt(LoanServicing.Outcome::posted).sum()).isEqualTo(3);
        assertAccruedOnce(raced, 3);

        // Ten racing sweepers over three accounts - the schedule's shape: a claim, then each claimed account's step.
        List<LoanId> three = List.of(disbursed(1_000_00, 12), disbursed(1_100_00, 12), disbursed(1_200_00, 12));
        for (LoanId each : three) {
            backdate(each, 3);
        }
        race(10, index -> {
            sweep(SERVICING, Duration.ofMinutes(5));
            return null;
        });
        for (LoanId each : three) {
            assertAccruedOnce(each, 3);
        }

        // A crash mid-run: the second date's row fails after its posting - the whole step rolls back, rows and entries.
        LoanId crashed = disbursed(4_200_00, 12);
        backdate(crashed, 3);
        AtomicInteger inserts = new AtomicInteger();
        LoanServicing crashing = servicing(new CrashingStore(SERVICING_STORE, inserts, 2));
        assertThatThrownBy(() -> service(crashing, crashed)).hasMessageContaining("crash");
        assertThat(rows(crashed)).as("a crash between dates - and between a posting and its row - writes neither").isZero();
        assertThat(entries(crashed)).isZero();
        assertThat(count("SELECT count(*) FROM platform.idempotency_record WHERE scope = 'ledger.post'"
                + " AND idempotency_key LIKE ?", LoanServicing.POSTING_KEY_PREFIX + crashed.value() + ":%"))
                .as("no key consumed").isZero();
        assertThat(service(crashed).posted()).as("the restarted step accrues every date").isEqualTo(3);
        assertAccruedOnce(crashed, 3);

        // Beneath L3: the unique, with the triggers suspended.
        assertThatThrownBy(() -> asOwner("INSERT INTO lending.interest_accrual SELECT gen_random_uuid(), loan_id,"
                + " accrual_date, period_start, period_end, currency, scale, base_minor, cumulative_exact, 0,"
                + " agreement_id, agreement_version, accrual_engine_version, NULL, created_at, correlation_id"
                + " FROM lending.interest_accrual WHERE loan_id = '" + twice.value() + "' LIMIT 1"))
                .hasCauseInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure.getCause()).getSQLState()).isEqualTo("23505"));

        // Beneath L3: the ledger key. A date's row removed by the owner leaves its entry under the key; the step that
        // reaches the date again finds the key replayed - a defect, refused, nothing written.
        String last = scalar("SELECT max(accrual_date)::text FROM lending.interest_accrual WHERE loan_id = ?",
                twice.value());
        asOwner("DELETE FROM lending.interest_accrual WHERE loan_id = '" + twice.value() + "' AND accrual_date = '"
                + last + "'");
        assertThatThrownBy(() -> service(twice)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INV-LND-02");
        assertThat(entries(twice)).as("the key's one entry, never a second").isEqualTo(3);
        assertThat(rows(twice)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ the database clock

    @Test
    @DisplayName("skew never accrues early or twice: run by instances whose own clocks sit in zones fourteen hours ahead"
            + " of UTC and twelve behind, racing two at a time, an account pinned to UTC and one pinned fourteen hours"
            + " behind each accrue exactly through the day before the database clock's business date in their own zone,"
            + " each date once; a raw insert of a date that has not ended is refused for every writer")
    void skewNeverAccruesEarlyOrTwice() throws Exception {
        LoanId utc = disbursed(2_000_00, 12);
        LoanId west;
        try {
            activate(LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0899").servicingZone("-14:00").build());
            west = disbursed(2_000_00, 12);
        } finally {
            activate(LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0899").build());
        }
        backdate(utc, 2);
        backdate(west, 2);
        // The instances' own clocks in zones whose date differs from UTC's at every hour of the day (one of them, at
        // least): whatever an instance's clock says, the database's business date in the pinned zone decides.
        TimeZone own = TimeZone.getDefault();
        try {
            for (String instanceZone : List.of("Pacific/Kiritimati", "Etc/GMT+12")) {
                TimeZone.setDefault(TimeZone.getTimeZone(instanceZone));
                for (LoanId each : List.of(utc, west)) {
                    race(2, index -> service(each));
                }
            }
        } finally {
            TimeZone.setDefault(own);
        }
        assertAccruedOnce(utc, 2);
        assertAccruedOnce(west, 2);
        assertThat(scalar("SELECT max(accrual_date)::text FROM lending.interest_accrual WHERE loan_id = ?", utc.value()))
                .isEqualTo(scalar("SELECT (lending.business_date(statement_timestamp(), 'UTC') - 1)::text"));
        assertThat(scalar("SELECT max(accrual_date)::text FROM lending.interest_accrual WHERE loan_id = ?", west.value()))
                .isEqualTo(scalar("SELECT (lending.business_date(statement_timestamp(), '-14:00') - 1)::text"));

        // Never early, for every writer: the business date that has not ended yet, in each pinned zone.
        try (Connection app = DatabaseRoles.application()) {
            refused(app, rawAccrual(utc, "lending.business_date(statement_timestamp(), 'UTC')"), "P0001", "has ended");
            refused(app, rawAccrual(west, "lending.business_date(statement_timestamp(), '-14:00')"), "P0001",
                    "has ended");
        }
    }

    // ------------------------------------------------------------------ catch-up

    @Test
    @DisplayName("three missed days are caught up oldest first in one step: one row a date, consecutive from the accrual"
            + " start, each date's cumulative figure the previous one's plus a day on its base, the entries dated their"
            + " accrual dates, and the permit the start of the next business date")
    void threeMissedDaysAreCaughtUpOldestFirst() {
        LoanId loan = disbursed(5_000_00, 24);
        backdate(loan, 3);
        LocalDate start = LocalDate.parse(scalar("SELECT start_date::text FROM lending.accrual_start WHERE loan_id = ?",
                loan.value()));
        // The lag reads it: the oldest unaccrued date ended at least two days ago (the fleet-wide maximum, so at least).
        assertThat(inOneTransaction(SERVICING_STORE::oldestUnaccruedLag)).hasValueSatisfying(
                lag -> assertThat(lag).isGreaterThanOrEqualTo(Duration.ofDays(2)));
        LoanServicing.Outcome step = service(loan);
        assertThat(step.posted()).isEqualTo(3);
        assertThat(step.caughtUp()).isTrue();
        assertThat(dates(loan, "created_at, accrual_date"))
                .as("oldest first").containsExactly(start, start.plusDays(1), start.plusDays(2));
        assertChain(loan, RATE);
        assertAccruedOnce(loan, 3);
        assertThat(scalar("SELECT (next_servicing_at = (lending.business_date(statement_timestamp(), 'UTC') + 1)"
                + "::timestamp AT TIME ZONE 'UTC')::text FROM lending.loan WHERE id = ?", loan.value()))
                .as("the permit is the start of the next business date in the pinned zone").isEqualTo("true");
    }

    @Test
    @DisplayName("a long outage is caught up in bounded steps: ninety-three missed days - one step accrues the oldest"
            + " ninety-two and makes the account due at once, the next the last; every date once, rounded within its own"
            + " instalment period")
    void aLongOutageIsCaughtUpInBoundedSteps() {
        LoanId loan = disbursed(9_000_00, 24);
        backdate(loan, LoanServicing.MAX_ACCRUALS_PER_STEP + 1);
        LoanServicing.Outcome first = service(loan);
        assertThat(first.posted() + first.zero()).isEqualTo(LoanServicing.MAX_ACCRUALS_PER_STEP);
        assertThat(first.caughtUp()).isFalse();
        // Due at once: stamped the step's own database instant, never the next business date's start. (Compared with the
        // next business date rather than with a later statement's clock - a container's clock can step back a second
        // under load, which the permit tolerates: it is a permit, never a fact.)
        assertThat(scalar("SELECT (next_servicing_at < (lending.business_date(statement_timestamp(), 'UTC') + 1)"
                + "::timestamp AT TIME ZONE 'UTC')::text FROM lending.loan WHERE id = ?",
                loan.value())).as("the rest is due at once").isEqualTo("true");
        LoanServicing.Outcome second = service(loan);
        assertThat(second.posted() + second.zero()).isEqualTo(1);
        assertThat(second.caughtUp()).isTrue();
        assertAccruedOnce(loan, LoanServicing.MAX_ACCRUALS_PER_STEP + 1);
        assertThat(count("SELECT count(DISTINCT period_start) FROM lending.interest_accrual WHERE loan_id = ?",
                loan.value())).as("more than one instalment period crossed").isGreaterThan(1);
        assertChain(loan, RATE);
    }

    @Test
    @DisplayName("the longest first period is one period: a 59-day first instalment period (28 days' minimum, then the"
            + " intended day a month on - the terms' widest) accrues every date within it, rounded once across it")
    void theLongestFirstPeriodIsOnePeriod() {
        LoanId loan = disbursed(4_400_00, 12);
        long length = count("SELECT days FROM lending.loan_instalment WHERE loan_id = ? AND sequence = 1", loan.value());
        int earlier = Math.toIntExact(59 - length);
        UUID id = loan.value();
        // The fixture: the first period's start moved back - the due dates where the schedule put them.
        asOwner("UPDATE ledger.journal_entry SET posting_date = posting_date - " + earlier + ", value_date = value_date - "
                + earlier + " WHERE id = (SELECT journal_entry_id FROM lending.loan_disbursement WHERE loan_id = '" + id + "');"
                + " UPDATE lending.loan_disbursement SET value_date = value_date - " + earlier + " WHERE loan_id = '" + id + "';"
                + " UPDATE lending.accrual_start SET start_date = start_date - " + earlier + " WHERE loan_id = '" + id + "';"
                + " UPDATE lending.repayment_schedule SET start_date = start_date - " + earlier + " WHERE loan_id = '" + id + "';"
                + " UPDATE lending.loan_instalment SET period_start = period_start - " + earlier + ", days = days + " + earlier
                + " WHERE loan_id = '" + id + "' AND sequence = 1");
        assertThat(service(loan).posted()).isEqualTo(earlier);
        assertThat(count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ? AND period_end - period_start = 59",
                id)).isEqualTo(earlier);
        assertChain(loan, RATE);
    }

    // ------------------------------------------------------------------ the base

    @Test
    @DisplayName("the base is derived from postings, not the projection: principal outstanding by value date - a"
            + " principal movement value-dated mid-run lowers it from that date, accrued interest never joins it, and a"
            + " corrupted projection changes nothing")
    void theBaseIsDerivedFromPostingsNotTheProjection() {
        LoanId loan = disbursed(6_000_00, 24);
        backdate(loan, 4);
        LocalDate start = LocalDate.parse(scalar("SELECT start_date::text FROM lending.accrual_start WHERE loan_id = ?",
                loan.value()));
        // A principal movement value-dated the third date (the fixture's - a repayment is -018's).
        postFixture("p11t16-principal:" + UUID.randomUUID(), start.plusDays(2), List.of(
                new JournalLine(cashAtBank(), Direction.DEBIT, eur(1_000_00)),
                new JournalLine(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL), Direction.CREDIT, eur(1_000_00))));
        // Interest and fees already on the account, value-dated the first date (the fixture's - earlier accruals and a
        // late fee are -016's own rows and -025's): never in the base (A4).
        postFixture("p11t16-interest:" + UUID.randomUUID(), start, List.of(
                new JournalLine(loanAccount(loan, AccountPurpose.LOAN_INTEREST_ACCRUED), Direction.DEBIT, eur(50_00)),
                new JournalLine(cashAtBank(), Direction.CREDIT, eur(50_00))));
        postFixture("p11t16-fee:" + UUID.randomUUID(), start, List.of(
                new JournalLine(loanAccount(loan, AccountPurpose.LOAN_FEES_DUE), Direction.DEBIT, eur(15_00)),
                new JournalLine(cashAtBank(), Direction.CREDIT, eur(15_00))));
        UUID principal = loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL).value();
        asOwner("UPDATE ledger.account_balance SET posted_minor = posted_minor + 99999999 WHERE ledger_account_id = '"
                + principal + "'");
        try {
            assertThat(service(loan).posted()).isEqualTo(4);
        } finally {
            asOwner("UPDATE ledger.account_balance SET posted_minor = posted_minor - 99999999 WHERE ledger_account_id = '"
                    + principal + "'");
        }
        assertThat(bases(loan)).as("P, P (interest and fees never in it), P - 1,000.00 from its value date, P - 1,000.00")
                .containsExactly(6_000_00L, 6_000_00L, 5_000_00L, 5_000_00L);
        assertChain(loan, RATE);
    }

    // ------------------------------------------------------------------ the calendar

    @Test
    @DisplayName("a leap day accrues one day: 27 February to 1 March 2024 are four rows, 29 February's increment one"
            + " ordinary day at /365, and nothing accrues once principal is zero")
    void leapDayAccruesOneDay() {
        LoanId loan = disbursed(7_300_00, 12);
        backdateTo(loan, LocalDate.of(2024, 2, 27));
        postFixture("p11t16-zero:" + UUID.randomUUID(), LocalDate.of(2024, 3, 2), List.of(
                new JournalLine(cashAtBank(), Direction.DEBIT, eur(7_300_00)),
                new JournalLine(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL), Direction.CREDIT, eur(7_300_00))));
        assertThat(service(loan).posted()).isEqualTo(4);
        assertThat(dates(loan, "accrual_date")).containsExactly(LocalDate.of(2024, 2, 27), LocalDate.of(2024, 2, 28),
                LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 1));
        BigDecimal day = new BigDecimal("7300.00").multiply(RATE).divide(BigDecimal.valueOf(365), 20, RoundingMode.HALF_EVEN);
        assertThat(new BigDecimal(scalar("SELECT (cumulative_exact - lag(cumulative_exact) OVER (ORDER BY accrual_date))"
                + "::text FROM lending.interest_accrual WHERE loan_id = ? ORDER BY accrual_date OFFSET 2 LIMIT 1",
                loan.value()))).as("29 February: one day at /365").isEqualByComparingTo(day);
        assertChain(loan, RATE);
        assertThat(service(loan).posted()).as("principal zero from 2 March: nothing more accrues").isZero();
        assertThat(rows(loan)).isEqualTo(4);
    }

    @Test
    @DisplayName("zero days write a row and no entry: at one basis point a day's interest rounds to nothing - each date's"
            + " row carries its cumulative figure, posts zero and names no journal entry")
    void zeroDaysWriteARowAndNoEntry() {
        LoanId loan;
        try {
            activate(LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0001").build());
            loan = disbursed(500_00, 6);
        } finally {
            activate(LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0899").build());
        }
        backdate(loan, 3);
        LoanServicing.Outcome step = service(loan);
        assertThat(step.zero()).isEqualTo(3);
        assertThat(step.posted()).isZero();
        assertThat(rows(loan)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ? AND posted_minor = 0"
                + " AND journal_entry_id IS NULL AND cumulative_exact > 0", loan.value())).isEqualTo(3);
        assertThat(entries(loan)).isZero();
        assertChain(loan, new BigDecimal("0.0001"));
    }

    // ------------------------------------------------------------------ what accrues nothing

    @Test
    @DisplayName("an account awaiting its payout accrues nothing: without an accrual start (path X's state until its"
            + " payout concludes, A30) the step writes no row and moves the permit to the next business date, and a raw"
            + " insert is refused for every writer")
    void anAwaitingPayoutLoanAccruesNothing() throws Exception {
        LoanId loan = disbursed(3_000_00, 12);
        backdate(loan, 3);
        // The fixture: the wallet loan without its accrual start - path X's real state is -015's to re-assert.
        asOwner("DELETE FROM lending.accrual_start WHERE loan_id = '" + loan.value() + "'");
        LoanServicing.Outcome step = service(loan);
        assertThat(step.kind()).isEqualTo(LoanServicing.Outcome.Kind.SERVICED);
        assertThat(step.posted() + step.zero()).isZero();
        assertThat(rows(loan)).isZero();
        assertThat(entries(loan)).isZero();
        assertThat(scalar("SELECT (next_servicing_at > statement_timestamp())::text FROM lending.loan WHERE id = ?",
                loan.value())).isEqualTo("true");
        try (Connection app = DatabaseRoles.application()) {
            refused(app, rawAccrual(loan, "current_date - 2"), "P0001", "accrual start");
        }
    }

    @Test
    @DisplayName("a real path X loan awaiting its payout accrues nothing (P11-TSK-015's re-assertion of the fixture above):"
            + " disbursed with its hold and payout PENDING, then DISPATCHED with payments undecided - no accrual start, no"
            + " row, no entry, the permit moved on; the payout's conclusion then births the accrual start the sweep reads")
    void aRealPathXLoanAwaitingItsPayoutAccruesNothing() throws Exception {
        LoanId loan = DisbursementWorld.disbursedExternally(3_100_00, 12).loan();
        backdate(loan, 3);
        for (int round = 0; round < 2; round++) {
            LoanServicing.Outcome step = service(loan);
            assertThat(step.kind()).isEqualTo(LoanServicing.Outcome.Kind.SERVICED);
            assertThat(step.posted() + step.zero()).isZero();
            assertThat(rows(loan)).isZero();
            assertThat(entries(loan)).isZero();
            assertThat(scalar("SELECT (next_servicing_at > statement_timestamp())::text FROM lending.loan WHERE id = ?",
                    loan.value())).isEqualTo("true");
            // The first round with the payout PENDING; the second with it DISPATCHED and payments undecided.
            assertThat(DisbursementWorld.sweepPayouts(java.time.Duration.ofMillis(1))).containsKey(loan);
        }
        assertThat(scalar("SELECT status FROM lending.loan_payout WHERE loan_id = ?", loan.value())).isEqualTo("DISPATCHED");
        assertThat(count("SELECT count(*) FROM lending.accrual_start WHERE loan_id = ?", loan.value())).isZero();
        DisbursementWorld.PAYMENTS.outcomes.put(loan, new LoanPayouts.Outcome.Concluded(PayoutStatus.PAID_OUT,
                java.time.Instant.now()));
        Thread.sleep(20);
        assertThat(DisbursementWorld.sweepPayouts(java.time.Duration.ofMillis(1)).get(loan))
                .containsExactly(LoanPayoutSteps.Outcome.Kind.PAID_OUT);
        assertThat(scalar("SELECT origin FROM lending.accrual_start WHERE loan_id = ?", loan.value()))
                .isEqualTo("PAYOUT_CONCLUDED");
        assertThat(service(loan).kind()).as("the sweep reads the payout's accrual start").isEqualTo(
                LoanServicing.Outcome.Kind.SERVICED);
    }

    // ------------------------------------------------------------------ the line

    @Test
    @DisplayName("a line's accrual follows its drawn principal: rows only on dates with principal drawn, none while it"
            + " is zero, each rounded within its statement cycle - the cumulative figure resetting at a cycle's start")
    void aLinesAccrualFollowsItsDrawnPrincipal() {
        LoanId line = acceptedLine(10_000_00).loan();
        LocalDate today = LocalDate.parse(scalar("SELECT current_date::text"));
        backdateOpening(line, 40);
        // An owner-role journal fixture standing in for draws and a paydown (re-asserted through real draws by -021).
        LocalDate opened = today.minusDays(40);
        postFixture("p11t16-draw:" + UUID.randomUUID(), opened.plusDays(2), List.of(
                new JournalLine(loanAccount(line, AccountPurpose.LOAN_PRINCIPAL), Direction.DEBIT, eur(1_000_00)),
                new JournalLine(cashAtBank(), Direction.CREDIT, eur(1_000_00))));
        postFixture("p11t16-paydown:" + UUID.randomUUID(), opened.plusDays(20), List.of(
                new JournalLine(cashAtBank(), Direction.DEBIT, eur(1_000_00)),
                new JournalLine(loanAccount(line, AccountPurpose.LOAN_PRINCIPAL), Direction.CREDIT, eur(1_000_00))));
        postFixture("p11t16-draw:" + UUID.randomUUID(), opened.plusDays(30), List.of(
                new JournalLine(loanAccount(line, AccountPurpose.LOAN_PRINCIPAL), Direction.DEBIT, eur(500_00)),
                new JournalLine(cashAtBank(), Direction.CREDIT, eur(500_00))));
        LoanServicing.Outcome step = service(line);
        // Drawn from day 2 to day 19 (18 dates), zero from day 20 to 29, drawn again from day 30 to day 39 (10 dates).
        assertThat(step.posted() + step.zero()).isEqualTo(28);
        assertThat(count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ? AND accrual_date"
                + " BETWEEN ? AND ?", line.value(), opened.plusDays(20), opened.plusDays(29))).isZero();
        assertThat(count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ? AND base_minor = 50000",
                line.value())).isEqualTo(10);
        StatementTerms terms = StatementTerms.of(LoanTermsFixtures.of(LoanProduct.CREDIT_LINE, "0.1299").build(), 10);
        for (LocalDate date : dates(line, "accrual_date")) {
            AccrualPeriod cycle = StatementEngineV1.cycle(terms, opened, date);
            assertThat(scalar("SELECT period_start::text || '/' || period_end::text FROM lending.interest_accrual"
                    + " WHERE loan_id = ? AND accrual_date = ?", line.value(), date))
                    .as("the statement cycle of " + date).isEqualTo(cycle.start() + "/" + cycle.endExclusive());
        }
        assertChain(line, new BigDecimal(scalar("SELECT nominal_annual_rate::text FROM lending.loan_agreement"
                + " WHERE loan_id = ?", line.value())));
        assertThat(netDebit(loanAccount(line, AccountPurpose.LOAN_INTEREST_ACCRUED)).minorUnits())
                .isEqualTo(Long.parseLong(scalar("SELECT sum(posted_minor)::text FROM lending.interest_accrual"
                        + " WHERE loan_id = ?", line.value())));
    }

    // ------------------------------------------------------------------ failure and locking

    @Test
    @DisplayName("a failing account does not hold the page: its step rolls back and keeps the claim's retry cadence,"
            + " and the account claimed after it in the same sweep is serviced")
    void aFailingAccountDoesNotHoldThePage() throws Exception {
        LoanId failing = disbursed(2_500_00, 12);
        LoanId healthy = disbursed(2_600_00, 12);
        backdate(failing, 2);
        backdate(healthy, 2);
        // The fixture's defect: an accrual start without its schedule's periods - the step refuses, never guesses.
        asOwner("DELETE FROM lending.loan_instalment WHERE loan_id = '" + failing.value() + "';"
                + " DELETE FROM lending.repayment_schedule WHERE loan_id = '" + failing.value() + "'");
        asOwner("UPDATE lending.loan SET next_servicing_at = statement_timestamp() - interval '2 hours' WHERE id = '"
                + failing.value() + "'; UPDATE lending.loan SET next_servicing_at = statement_timestamp()"
                + " - interval '1 hour' WHERE id = '" + healthy.value() + "'");
        sweep(SERVICING, Duration.ofMinutes(30));
        assertThat(rows(failing)).isZero();
        assertThat(entries(failing)).isZero();
        assertAccruedOnce(healthy, 2);
        assertThat(scalar("SELECT (next_servicing_at BETWEEN statement_timestamp() + interval '20 minutes'"
                + " AND statement_timestamp() + interval '31 minutes')::text FROM lending.loan WHERE id = ?",
                failing.value())).as("the failing account keeps the claim's cadence").isEqualTo("true");
    }

    @Test
    @DisplayName("servicing takes the loan's lock (L3): with the loan held FOR UPDATE elsewhere the step waits in the"
            + " loan's lock statement, and accrues once the holder commits")
    void servicingTakesTheLoanLock() throws Exception {
        LoanId loan = disbursed(3_300_00, 12);
        backdate(loan, 2);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM lending.loan WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, loan.value());
                lock.executeQuery().close();
            }
            Future<LoanServicing.Outcome> step = pool.submit(() -> service(loan));
            DisbursementWorld.Blocked blocked = awaitBlockedBy(pid(holder));
            assertThat(blocked.query()).as("the step waits in the loan's lock").contains("FROM lending.loan l")
                    .contains("FOR UPDATE OF l");
            assertThat(rows(loan)).isZero();
            holder.commit();
            assertThat(step.get(30, TimeUnit.SECONDS).posted()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
        assertAccruedOnce(loan, 2);
    }

    // ------------------------------------------------------------------ every writer

    @Test
    @DisplayName("the rows and the permit are held for every writer: an accrual is never updated, deleted or truncated;"
            + " out of order refused; the permit moves alone and only on an account in servicing")
    void theRowsAndThePermitAreHeldForEveryWriter() throws Exception {
        LoanId loan = disbursed(2_200_00, 12);
        backdate(loan, 3);
        assertThat(service(loan).posted()).isEqualTo(3);
        UUID id = loan.value();
        try (Connection app = DatabaseRoles.application(); Connection migrator = DatabaseRoles.migrator()) {
            refused(app, "UPDATE lending.interest_accrual SET posted_minor = 0 WHERE loan_id = '" + id + "'", "42501", "");
            refused(app, "DELETE FROM lending.interest_accrual WHERE loan_id = '" + id + "'", "42501", "");
            refused(migrator, "UPDATE lending.interest_accrual SET posted_minor = 0 WHERE loan_id = '" + id + "'",
                    "P0001", "immutable");
            refused(migrator, "DELETE FROM lending.interest_accrual WHERE loan_id = '" + id + "'", "P0001", "immutable");
            refused(migrator, "TRUNCATE lending.interest_accrual", "P0001", "");
            refused(app, rawAccrual(loan, "current_date - 3"), "P0001", "oldest first");
            // The permit alone, on an account in servicing.
            execute(app, "UPDATE lending.loan SET next_servicing_at = statement_timestamp() WHERE id = '" + id + "'");
            refused(app, "UPDATE lending.loan SET next_servicing_at = statement_timestamp(), status = 'CLOSED' WHERE id = '"
                    + id + "'", "P0001", "");
            refused(app, "UPDATE lending.loan SET opened_at = statement_timestamp() WHERE id = '" + id + "'", "42501", "");
        }
    }

    // ------------------------------------------------------------------ the world

    static LoanServicing servicing(LoanServicingStore store) {
        return new LoanServicing(LOAN_STORE, store, TERMS_STORE, LEDGER_ACCOUNTS, BALANCES, POSTINGS, IDS,
                DisbursementWorld.BILLINGS, DisbursementWorld.CLOSURES, DisbursementWorld.COLLECTIONS);
    }

    /** A fresh wallet loan disbursed by the real T-dis. */
    static LoanId disbursed(long minor, int term) {
        DisbursementWorld.Accepted accepted = acceptedLoan(minor, term);
        assertThat(disburse(accepted.loan()).kind()).isEqualTo(LoanDisbursements.Outcome.Kind.POSTED);
        return accepted.loan();
    }

    static LoanServicing.Outcome service(LoanId loan) {
        return service(SERVICING, loan);
    }

    static LoanServicing.Outcome service(LoanServicing servicing, LoanId loan) {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            return inOneTransaction(uow -> servicing.service(uow, loan));
        }
    }

    /** The schedule's shape: claims until a page comes back short, each claimed account's step in its own transaction. */
    static void sweep(LoanServicing servicing, Duration cadence) {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            List<LoanId> claimed;
            do {
                claimed = inOneTransaction(uow -> servicing.claim(uow, 200, cadence));
                for (LoanId loan : claimed) {
                    try {
                        inOneTransaction(uow -> servicing.service(uow, loan));
                    } catch (RuntimeException failure) {
                        // The schedule's rule: a failing account never holds the page.
                    }
                }
            } while (claimed.size() == 200);
        }
    }

    /** Moves every dated fact of {@code loan} {@code days} into the past - the fixture's clock, never a path. */
    static void backdate(LoanId loan, int days) {
        UUID id = loan.value();
        asOwner("UPDATE ledger.journal_entry SET posting_date = posting_date - " + days + ", value_date = value_date - "
                + days + " WHERE id = (SELECT journal_entry_id FROM lending.loan_disbursement WHERE loan_id = '" + id + "');"
                + " UPDATE lending.loan_disbursement SET value_date = value_date - " + days + " WHERE loan_id = '" + id + "';"
                + " UPDATE lending.accrual_start SET start_date = start_date - " + days + " WHERE loan_id = '" + id + "';"
                + " UPDATE lending.repayment_schedule SET start_date = start_date - " + days + " WHERE loan_id = '" + id + "';"
                + " UPDATE lending.loan_instalment SET period_start = period_start - " + days + ", due_date = due_date - "
                + days + " WHERE loan_id = '" + id + "';"
                + " UPDATE lending.loan SET opened_at = opened_at - interval '" + days + " days' WHERE id = '" + id + "'");
    }

    /** {@link #backdate} to an accrual start of {@code start}. */
    static void backdateTo(LoanId loan, LocalDate start) {
        LocalDate now = LocalDate.parse(scalar("SELECT start_date::text FROM lending.accrual_start WHERE loan_id = ?",
                loan.value()));
        backdate(loan, Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(start, now)));
    }

    /** A line's opening moved {@code days} into the past (and its database-UTC business date with it). */
    static void backdateOpening(LoanId line, int days) {
        asOwner("UPDATE lending.loan SET opened_at = date_trunc('day', statement_timestamp() AT TIME ZONE 'UTC')"
                + " AT TIME ZONE 'UTC' - interval '" + days + " days' + interval '1 hour' WHERE id = '" + line.value() + "'");
    }

    /** A balanced fixture entry dated {@code date} through the real ledger. */
    static void postFixture(String key, LocalDate date, List<JournalLine> lines) {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(correlation()).causing(CausationId.of("p11t16")))) {
            inOneTransaction(uow -> POSTINGS.post(uow, new PostingCommand(key, date, date, "the suite's fixture", lines)));
        }
    }

    /** One row and at most one entry per date, dated it, the accrued account the sum of the rows' postings. */
    static void assertAccruedOnce(LoanId loan, int dates) {
        UUID id = loan.value();
        assertThat(rows(loan)).as("one row per date").isEqualTo(dates);
        assertThat(count("SELECT count(DISTINCT accrual_date) FROM lending.interest_accrual WHERE loan_id = ?", id))
                .isEqualTo(dates);
        long posted = count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ? AND posted_minor > 0", id);
        assertThat(entries(loan)).as("one entry per date that posted").isEqualTo(posted);
        assertThat(count("SELECT count(*) FROM lending.interest_accrual a JOIN ledger.journal_entry e"
                + " ON e.id = a.journal_entry_id WHERE a.loan_id = ? AND (e.value_date <> a.accrual_date"
                + " OR e.posting_date <> a.accrual_date OR e.idempotency_scope <> 'ledger.post:lending.accrual:'"
                + " || a.loan_id || ':' || a.accrual_date)", id)).as("each entry dated its date, under its key").isZero();
        // The accrued account is the rows' postings less what billing made due (P11-TSK-017's arm of the proof).
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_INTEREST_ACCRUED)).minorUnits())
                .isEqualTo(Long.parseLong(scalar("SELECT (coalesce((SELECT sum(posted_minor) FROM lending.interest_accrual"
                        + " WHERE loan_id = ?), 0) - coalesce((SELECT sum(interest_due_minor) FROM lending.instalment_billing"
                        + " WHERE loan_id = ?), 0))::text", id, id)));
    }

    /**
     * The rows re-derived independently (ADR-0094 point 3): within a period each cumulative figure is the previous one's
     * plus {@code base x rate / 365} at scale 20, at a new period just that; each posting the change in the cumulative
     * figure rounded once {@code HALF_EVEN}.
     */
    static void assertChain(LoanId loan, BigDecimal rate) {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(
                "SELECT accrual_date, period_start, base_minor, cumulative_exact, posted_minor"
                        + " FROM lending.interest_accrual WHERE loan_id = ? ORDER BY accrual_date")) {
            select.setObject(1, loan.value());
            LocalDate period = null;
            BigDecimal previous = BigDecimal.ZERO;
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    LocalDate start = row.getObject("period_start", LocalDate.class);
                    BigDecimal before = start.equals(period) ? previous : BigDecimal.ZERO;
                    BigDecimal day = BigDecimal.valueOf(row.getLong("base_minor"), 2).multiply(rate)
                            .divide(BigDecimal.valueOf(365), 20, RoundingMode.HALF_EVEN);
                    BigDecimal cumulative = row.getBigDecimal("cumulative_exact");
                    assertThat(cumulative).as("the cumulative figure of " + row.getObject("accrual_date"))
                            .isEqualByComparingTo(before.add(day));
                    long posted = cumulative.setScale(2, RoundingMode.HALF_EVEN).movePointRight(2).longValueExact()
                            - before.setScale(2, RoundingMode.HALF_EVEN).movePointRight(2).longValueExact();
                    assertThat(row.getLong("posted_minor")).isEqualTo(posted);
                    period = start;
                    previous = cumulative;
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static long rows(LoanId loan) {
        return count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ?", loan.value());
    }

    static long entries(LoanId loan) {
        return count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope LIKE ?",
                "ledger.post:" + LoanServicing.POSTING_KEY_PREFIX + loan.value() + ":%");
    }

    static List<LocalDate> dates(LoanId loan, String order) {
        return longsOrDates(loan, "accrual_date", order).stream().map(LocalDate::parse).toList();
    }

    static List<Long> bases(LoanId loan) {
        return longsOrDates(loan, "base_minor", "accrual_date").stream().map(Long::parseLong).toList();
    }

    private static List<String> longsOrDates(LoanId loan, String column, String order) {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(
                "SELECT " + column + "::text FROM lending.interest_accrual WHERE loan_id = ? ORDER BY " + order)) {
            select.setObject(1, loan.value());
            List<String> values = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    values.add(row.getString(1));
                }
            }
            return values;
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** An application-role insert of an otherwise well-formed accrual row of {@code loan} for {@code dateSql}. */
    static String rawAccrual(LoanId loan, String dateSql) {
        String agreement = scalar("SELECT id::text FROM lending.loan_agreement WHERE loan_id = ? AND version = 1",
                loan.value());
        return "INSERT INTO lending.interest_accrual (id, loan_id, accrual_date, period_start, period_end, currency, scale,"
                + " base_minor, cumulative_exact, posted_minor, agreement_id, agreement_version, accrual_engine_version,"
                + " journal_entry_id, created_at, correlation_id) VALUES (gen_random_uuid(), '" + loan.value() + "', "
                + dateSql + ", " + dateSql + ", " + dateSql + " + 1, 'EUR', 2, 100, 0, 0, '" + agreement + "', 1, 1,"
                + " NULL, statement_timestamp(), 'p11t16-raw')";
    }

    /** {@code sql}, run alone by {@code connection}, is refused with {@code sqlState} and a message holding {@code text}. */
    static void refused(Connection connection, String sql, String sqlState, String text) {
        assertThatThrownBy(() -> execute(connection, sql))
                .as(sql)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(text)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).as(sql).isEqualTo(sqlState));
    }

    static Money eur(long minor) {
        return Money.ofPersisted(minor, EUR, 2);
    }

    /** A store whose {@code crashAt}-th accrual insert crashes - after its date's posting, inside the step's transaction. */
    static final class CrashingStore implements LoanServicingStore {

        private final LoanServicingStore delegate;
        private final AtomicInteger inserts;
        private final int crashAt;

        CrashingStore(LoanServicingStore delegate, AtomicInteger inserts, int crashAt) {
            this.delegate = delegate;
            this.inserts = inserts;
            this.crashAt = crashAt;
        }

        @Override
        public Instant databaseNow(Connection unitOfWork) {
            return delegate.databaseNow(unitOfWork);
        }

        @Override
        public List<LoanId> claimDue(Connection unitOfWork, int limit, Duration retryCadence) {
            return delegate.claimDue(unitOfWork, limit, retryCadence);
        }

        @Override
        public boolean restamp(Connection unitOfWork, LoanId loan, Instant permit) {
            return delegate.restamp(unitOfWork, loan, permit);
        }

        @Override
        public Optional<LocalDate> accrualStartOf(Connection unitOfWork, LoanId loan) {
            return delegate.accrualStartOf(unitOfWork, loan);
        }

        @Override
        public Optional<InstalmentCalendar> calendarOf(Connection unitOfWork, LoanId loan) {
            return delegate.calendarOf(unitOfWork, loan);
        }

        @Override
        public Optional<RecordedAccrual> latestAccrual(Connection unitOfWork, LoanId loan) {
            return delegate.latestAccrual(unitOfWork, loan);
        }

        @Override
        public void insertAccrual(Connection unitOfWork, RecordedAccrual accrual, CorrelationId correlation) {
            if (inserts.incrementAndGet() == crashAt) {
                throw new IllegalStateException("a crash mid-run");
            }
            delegate.insertAccrual(unitOfWork, accrual, correlation);
        }

        @Override
        public Optional<Duration> oldestUnaccruedLag(Connection unitOfWork) {
            return delegate.oldestUnaccruedLag(unitOfWork);
        }
    }
}
