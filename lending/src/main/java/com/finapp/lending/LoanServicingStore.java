package com.finapp.lending;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for the servicing step (`P11-TSK-016`, {@code lending V010}): the servicing permit on the loan
 * ({@code next_servicing_at}), the facts a step reads (the accrual start, the latest schedule's periods, the latest
 * accrual) and the accrual rows it writes - INSERT-only. Every time the database's. No door reaches any of it: servicing
 * is the system's act.
 */
public interface LoanServicingStore {

    /** The database clock ({@code statement_timestamp()}), read after the caller's locks. */
    Instant databaseNow(Connection unitOfWork);

    /**
     * Claims at most {@code limit} accounts in servicing ({@code ACTIVE} or {@code CLOSING}) whose permit has come, in ONE
     * statement: each claimed permit re-stamped {@code retryCadence} ahead on the database clock - the cadence a step that
     * fails is retried at - and rows another claimer or a servicing act holds skipped ({@code FOR UPDATE SKIP LOCKED}).
     * Oldest permit first. Answers the claimed accounts.
     */
    List<LoanId> claimDue(Connection unitOfWork, int limit, Duration retryCadence);

    /** The permit of an account in servicing set to {@code permit}, conditional; whether it moved. */
    boolean restamp(Connection unitOfWork, LoanId loan, Instant permit);

    /** An instalment loan's accrual start date; empty while there is none (path X awaiting its payout). */
    Optional<LocalDate> accrualStartOf(Connection unitOfWork, LoanId loan);

    /** The accrual periods of an instalment loan's latest schedule version; empty when it has no schedule. */
    Optional<InstalmentCalendar> calendarOf(Connection unitOfWork, LoanId loan);

    /** The account's latest accrual - its highest date; empty when nothing has accrued. */
    Optional<RecordedAccrual> latestAccrual(Connection unitOfWork, LoanId loan);

    /** Births one accrual row; the database stamps the instant. */
    void insertAccrual(Connection unitOfWork, RecordedAccrual accrual, CorrelationId correlation);

    /**
     * How long the oldest unaccrued elapsed date of an instalment loan in servicing has been elapsed, on the database
     * clock in its pinned zone - from the end of that date; empty when every elapsed date is accrued.
     */
    Optional<Duration> oldestUnaccruedLag(Connection unitOfWork);

    /**
     * The credit lines in servicing ({@code ACTIVE} or {@code CLOSING}) ever drawn whose next accrual date - the day after
     * the latest accrual, or the opening business date - has elapsed on the database clock in the pinned zone: the
     * candidates of the accrual lag's line arm (`P11-TSK-023`). Whether principal was outstanding on those dates is a
     * journal-line question this store does not ask ({@link AccrualLags} does, through the ledger's port).
     */
    List<LineAwaitingAccrual> linesAwaitingAccrual(Connection unitOfWork);

    /** A line whose dates from {@code nextDate} up to (excluding) {@code today} have not been accrued. */
    record LineAwaitingAccrual(LoanId loan, CurrencyCode currency, LocalDate nextDate, LocalDate today, String zone) {

        public LineAwaitingAccrual {
            java.util.Objects.requireNonNull(loan, "loan must not be null");
            java.util.Objects.requireNonNull(currency, "currency must not be null");
            java.util.Objects.requireNonNull(nextDate, "nextDate must not be null");
            java.util.Objects.requireNonNull(today, "today must not be null");
            java.util.Objects.requireNonNull(zone, "zone must not be null");
        }
    }
}
