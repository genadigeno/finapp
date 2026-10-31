package com.finapp.lending;

import static com.finapp.lending.DisbursementWorld.BALANCES;
import static com.finapp.lending.DisbursementWorld.CLOCK;
import static com.finapp.lending.DisbursementWorld.IDS;
import static com.finapp.lending.DisbursementWorld.LEDGER_ACCOUNTS;
import static com.finapp.lending.DisbursementWorld.awaitBlockedBy;
import static com.finapp.lending.DisbursementWorld.count;
import static com.finapp.lending.DisbursementWorld.inOneTransaction;
import static com.finapp.lending.DisbursementWorld.pid;
import static com.finapp.lending.DisbursementWorld.scalar;
import static com.finapp.lending.InterestAccrualDatabaseTest.RATE;
import static com.finapp.lending.InterestAccrualDatabaseTest.SERVICING_STORE;
import static com.finapp.lending.InterestAccrualDatabaseTest.assertChain;
import static com.finapp.lending.InterestAccrualDatabaseTest.assertAccruedOnce;
import static com.finapp.lending.InterestAccrualDatabaseTest.backdate;
import static com.finapp.lending.InterestAccrualDatabaseTest.backdateTo;
import static com.finapp.lending.InterestAccrualDatabaseTest.disbursed;
import static com.finapp.lending.InterestAccrualDatabaseTest.rows;
import static com.finapp.lending.InterestAccrualDatabaseTest.service;
import static com.finapp.lending.InterestAccrualDatabaseTest.servicing;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.IllegalCutoffMoveException;
import com.finapp.ledger.PostingCutoffs;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Lending's accrual made cutoff-safe against a real database (`P14-TSK-006`; owner decision C3, ratified 2026-10-30;
 * rule {@code LND-2} version 2; {@code PHASE_14_PLAN.md} section 8.3 row 11; {@code INV-ACC-03}, {@code -08},
 * {@code INV-LND-02}): a catch-up across a lock posts the locked dates on the first open date, value-dated each accrual
 * date, and is never refused - whether the lock commits while the step waits (floored) or the step holds the key while
 * the lock waits (dated in the period, committed before the cut); a timely accrual keeps its date; one row and one entry
 * per date and the figures unchanged; the close's per-currency read sees the lag.
 *
 * <p><strong>The cutoff is one global row.</strong> These cases lock the month before the database's own UTC today (the
 * latest month a lock may cover) or January 1999, and every case returns the cutoff to unset before and after itself
 * through the ledger's own one-period moves ({@code PostingCutoffDatabaseTest}'s discipline), so no other suite of the
 * tier meets a lock. Written by `P14-TSK-006` and owed to the next database pass (C5).
 */
@Tag("database")
@DisplayName("lending's accrual across a lock: floored to the first open date, never refused (P14-TSK-006, C3)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class LoanAccrualCutoffDatabaseTest {

    private final PostingCutoffs cutoffs = new PostingCutoffs(IDS, CLOCK);

    @BeforeEach
    void startUnset() {
        restoreUnset();
    }

    @AfterEach
    void leaveUnset() {
        restoreUnset();
    }

    // ------------------------------------------------------------------ contention row 11

    @Test
    @DisplayName("row 11 - a catch-up across a lock posts in the open period: the lock committed while the step waits"
            + " on the cutoff's key, every locked date floored to the first open date, its value date kept, none"
            + " refused; and the other order - the step holding the key while the lock waits - dates them in the"
            + " period and commits them before the cut")
    void aCatchUpAcrossALockPostsInTheOpenPeriod() throws Exception {
        LocalDate cut = lastMonthEnd();
        LocalDate firstOpen = cut.plusDays(1);

        // The lock first: held uncommitted while the step reaches its first posting and queues on the shared key.
        LoanId waiting = disbursed(6_000_00, 12);
        backdateTo(waiting, cut.minusDays(4));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection lock = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            lock.setAutoCommit(false);
            assertThat(cutoffs.advance(lock, Optional.empty(), cut, "lock the previous month (P14-TSK-006 race)"))
                    .isEqualTo(PostingCutoffs.Outcome.MOVED);
            Future<LoanServicing.Outcome> step = pool.submit(() -> service(waiting));
            DisbursementWorld.Blocked blocked = awaitBlockedBy(pid(lock));
            assertThat(blocked.query()).as("the step waits on the cutoff's shared key, at its first posting")
                    .contains("pg_advisory_xact_lock_shared");
            assertThat(rows(waiting)).isZero();
            lock.commit();
            LoanServicing.Outcome serviced = step.get(60, TimeUnit.SECONDS);
            assertThat(serviced.caughtUp()).isTrue();
            assertThat(serviced.posted()).as("every elapsed date posted - none refused").isEqualTo(rows(waiting));
        } finally {
            pool.shutdownNow();
        }
        assertFloored(waiting, cut);
        assertThat(lockedDates(waiting)).as("cut-4 .. cut").isEqualTo(5);
        assertThat(postedOn(waiting, firstOpen)).as("the locked dates, on the first open date").isGreaterThanOrEqualTo(5);
        restoreUnset();

        // The step first: paused after its first posting - holding the shared key - while the lock queues behind it.
        LoanId holding = disbursed(6_000_00, 12);
        backdateTo(holding, cut.minusDays(4));
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger stepPid = new AtomicInteger();
        LoanServicing pausing = servicing(new PausingStore(SERVICING_STORE, stepPid, paused, release));
        ExecutorService steps = Executors.newFixedThreadPool(2);
        try {
            Future<LoanServicing.Outcome> step = steps.submit(() -> service(pausing, holding));
            assertThat(paused.await(30, TimeUnit.SECONDS)).as("the step holds the key, its first date posted").isTrue();
            Future<PostingCutoffs.Outcome> lock = steps.submit(() -> {
                try (Connection connection = DatabaseRoles.application();
                        SecurityContext.Scope actor = SecurityContext.enterSystem();
                        CorrelationContext.Scope flow = flow()) {
                    connection.setAutoCommit(false);
                    PostingCutoffs.Outcome moved = cutoffs.advance(connection, Optional.empty(), cut,
                            "lock the previous month behind a step (P14-TSK-006 race)");
                    connection.commit();
                    return moved;
                }
            });
            DisbursementWorld.Blocked blocked = awaitBlockedBy(stepPid.get());
            assertThat(blocked.query()).as("the lock waits for the step's shared key").contains("pg_advisory_xact_lock");
            release.countDown();
            assertThat(step.get(60, TimeUnit.SECONDS).caughtUp()).isTrue();
            assertThat(lock.get(60, TimeUnit.SECONDS)).isEqualTo(PostingCutoffs.Outcome.MOVED);
        } finally {
            release.countDown();
            steps.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM lending.interest_accrual a JOIN ledger.journal_entry e"
                + " ON e.id = a.journal_entry_id WHERE a.loan_id = ? AND a.accrual_date <= ?::date"
                + " AND e.posting_date = a.accrual_date", holding.value(), cut.toString()))
                .as("committed before the cut, dated in the period it now locks").isEqualTo(5);
        assertAccruedOnce(holding, Math.toIntExact(rows(holding)));
        assertChain(holding, RATE);
    }

    // ------------------------------------------------------------------ the floor without a race

    @Test
    @DisplayName("a catch-up after a lock floors only the locked dates: posted on the first open date, value-dated each"
            + " accrual date, under its unchanged key; the later dates posted on themselves; the figures unchanged;"
            + " each floor named on the entry's audit record")
    void aCatchUpAfterALockFloorsOnlyTheLockedDates() {
        LocalDate cut = lastMonthEnd();
        LoanId loan = disbursed(5_000_00, 24);
        backdateTo(loan, cut.minusDays(4));
        lock(cut);

        LoanServicing.Outcome step = service(loan);
        assertThat(step.caughtUp()).isTrue();
        assertThat(step.posted()).isEqualTo(rows(loan));
        assertFloored(loan, cut);
        assertThat(lockedDates(loan)).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM lending.interest_accrual a JOIN platform.audit_record r"
                + " ON r.target_id = a.journal_entry_id::text WHERE a.loan_id = ? AND a.accrual_date <= ?::date"
                + " AND r.change_summary LIKE '%flooredFrom=' || a.accrual_date::text || '%'", loan.value(),
                cut.toString())).as("each floored entry names its stated date on its audit record").isEqualTo(5);
        assertChain(loan, RATE);
        assertThat(service(loan).posted()).as("caught up: nothing more").isZero();
    }

    @Test
    @DisplayName("a timely accrual keeps its date: with a lock standing on an earlier month, each entry is posted and"
            + " value-dated its accrual date - the floor changes nothing it does not have to")
    void aTimelyAccrualKeepsItsDate() {
        lock(LocalDate.of(1999, 1, 31));
        LoanId loan = disbursed(4_000_00, 12);
        backdate(loan, 2);
        assertThat(service(loan).posted()).isEqualTo(2);
        assertAccruedOnce(loan, 2); // posting_date = value_date = accrual_date, under the key
        assertThat(count("SELECT count(*) FROM lending.interest_accrual a JOIN platform.audit_record r"
                + " ON r.target_id = a.journal_entry_id::text WHERE a.loan_id = ? AND r.change_summary LIKE"
                + " '%flooredFrom%'", loan.value())).isZero();
    }

    // ------------------------------------------------------------------ the close's read (AccrualCompleteness)

    @Test
    @DisplayName("the close's read: a loan behind makes its currency lag on or before its oldest unaccrued date, in the"
            + " store's instalment arm and the merged per-currency read")
    void theOldestUnaccruedDateIsReadPerCurrency() {
        LoanId loan = disbursed(3_000_00, 12);
        backdate(loan, 3);
        LocalDate start = LocalDate.parse(scalar("SELECT start_date::text FROM lending.accrual_start WHERE loan_id = ?",
                loan.value()));
        CurrencyCode eur = CurrencyCode.of("EUR");
        // The tier shares one database: another suite's loan may lag further back - "on or before", never "equal".
        Map<CurrencyCode, LocalDate> instalments = inOneTransaction(SERVICING_STORE::oldestUnaccruedByCurrency);
        assertThat(instalments).containsKey(eur);
        assertThat(instalments.get(eur)).isBeforeOrEqualTo(start);
        AccrualLags lags = new AccrualLags(SERVICING_STORE, LEDGER_ACCOUNTS, BALANCES);
        Map<CurrencyCode, LocalDate> merged = inOneTransaction(lags::oldestUnaccruedByCurrency);
        assertThat(merged.get(eur)).isBeforeOrEqualTo(start);
        assertThat(merged.keySet()).containsAll(instalments.keySet());
        service(loan);
        assertThat(rows(loan)).isEqualTo(3);
    }

    // ------------------------------------------------------------------

    /** Each accrual's entry: value-dated its date, posted on it or - on or before {@code cut} - the day after the cut. */
    private static void assertFloored(LoanId loan, LocalDate cut) {
        assertThat(count("SELECT count(*) FROM lending.interest_accrual a JOIN ledger.journal_entry e"
                + " ON e.id = a.journal_entry_id WHERE a.loan_id = ? AND (e.value_date <> a.accrual_date"
                + " OR e.posting_date <> GREATEST(a.accrual_date, ?::date + 1)"
                + " OR e.idempotency_scope <> 'ledger.post:lending.accrual:' || a.loan_id || ':' || a.accrual_date)",
                loan.value(), cut.toString()))
                .as("value date the accrual date; posting date floored at the first open date; the key unchanged")
                .isZero();
        assertThat(count("SELECT count(*) FROM lending.interest_accrual a JOIN ledger.journal_entry e"
                + " ON e.id = a.journal_entry_id WHERE a.loan_id = ? AND e.posting_date <= ?::date",
                loan.value(), cut.toString())).as("nothing in the locked period (INV-ACC-03)").isZero();
        assertThat(count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ? AND posted_minor > 0"
                + " AND journal_entry_id IS NULL", loan.value())).as("every date that posts has its entry").isZero();
    }

    private static long lockedDates(LoanId loan) {
        return count("SELECT count(*) FROM lending.interest_accrual a JOIN ledger.journal_entry e"
                + " ON e.id = a.journal_entry_id WHERE a.loan_id = ? AND e.value_date < e.posting_date", loan.value());
    }

    private static long postedOn(LoanId loan, LocalDate date) {
        return count("SELECT count(*) FROM lending.interest_accrual a JOIN ledger.journal_entry e"
                + " ON e.id = a.journal_entry_id WHERE a.loan_id = ? AND e.posting_date = ?::date", loan.value(),
                date.toString());
    }

    /** The month end before the database's UTC today - the latest month a lock may cover (the cutoff never covers today). */
    private static LocalDate lastMonthEnd() {
        LocalDate today = LocalDate.parse(scalar("SELECT (statement_timestamp() AT TIME ZONE 'UTC')::date::text"));
        return today.withDayOfMonth(1).minusDays(1);
    }

    private void lock(LocalDate cut) {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem(); CorrelationContext.Scope flow = flow()) {
            PostingCutoffs.Outcome moved = inOneTransaction(connection -> cutoffs.advance(connection,
                    Optional.empty(), cut, "lock a month (P14-TSK-006 test)"));
            assertThat(moved).isEqualTo(PostingCutoffs.Outcome.MOVED);
        }
    }

    /** Back to unset through the ledger's own moves - one period at a time, whichever the history allows. */
    private void restoreUnset() {
        for (int guard = 0; guard < 400; guard++) {
            Optional<LocalDate> standing = inOneTransaction(cutoffs::closedThrough);
            if (standing.isEmpty()) {
                return;
            }
            LocalDate from = standing.get();
            if (!moveBack(from, Optional.empty())) {
                assertThat(moveBack(from, Optional.of(from.withDayOfMonth(1).minusDays(1))))
                        .as("the cutoff steps back one period").isTrue();
            }
        }
        throw new AssertionError("the cutoff did not return to unset");
    }

    private boolean moveBack(LocalDate from, Optional<LocalDate> to) {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem(); CorrelationContext.Scope flow = flow()) {
            inOneTransaction(connection -> cutoffs.moveBack(connection, from, to, "the test restores the cutoff"));
            return true;
        } catch (IllegalCutoffMoveException refused) {
            return false;
        }
    }

    private static CorrelationContext.Scope flow() {
        return CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
    }

    /**
     * The real store, its first accrual insert paused - after that date's posting, inside the step's transaction, the
     * cutoff's shared key held - until released; the step's backend pid published so a waiter can be found.
     */
    static final class PausingStore implements LoanServicingStore {

        private final LoanServicingStore delegate;
        private final AtomicInteger pid;
        private final CountDownLatch paused;
        private final CountDownLatch release;

        PausingStore(LoanServicingStore delegate, AtomicInteger pid, CountDownLatch paused, CountDownLatch release) {
            this.delegate = delegate;
            this.pid = pid;
            this.paused = paused;
            this.release = release;
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
        public void insertAccrual(Connection unitOfWork, RecordedAccrual accrual,
                com.finapp.sharedkernel.correlation.CorrelationId correlation) {
            delegate.insertAccrual(unitOfWork, accrual, correlation);
            if (paused.getCount() > 0) {
                try {
                    pid.set(pid(unitOfWork));
                    paused.countDown();
                    if (!release.await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("never released");
                    }
                } catch (java.sql.SQLException | InterruptedException failure) {
                    throw new IllegalStateException(failure);
                }
            }
        }

        @Override
        public Optional<java.time.Duration> oldestUnaccruedLag(Connection unitOfWork) {
            return delegate.oldestUnaccruedLag(unitOfWork);
        }

        @Override
        public Map<CurrencyCode, LocalDate> oldestUnaccruedByCurrency(Connection unitOfWork) {
            return delegate.oldestUnaccruedByCurrency(unitOfWork);
        }

        @Override
        public List<LineAwaitingAccrual> linesAwaitingAccrual(Connection unitOfWork) {
            return delegate.linesAwaitingAccrual(unitOfWork);
        }
    }
}
