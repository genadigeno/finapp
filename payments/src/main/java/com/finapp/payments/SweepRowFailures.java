package com.finapp.payments;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.persistence.SweepBackoff;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * The one statement that records a sweep row's failure in {@code payments.sweep_row_failure} (`P16-TSK-017`, payments
 * {@code V040}), shared by the four stores whose listings the module's sweeps read. Operational state, never financial truth:
 * it decides only when a listing reads the row again.
 *
 * <h2>Ten instances count one failure per backoff window</h2>
 *
 * <p>The sweeps are leaderless: ten instances may list the same head row in one tick and each fail it. The record's own row is
 * the arbiter. The first recorder inserts it; every other one meets the conflict, waits for that commit, and re-reads the
 * {@code WHERE} against the committed row - its retry now in the future - so it updates nothing and is not counted. The count
 * is incremented in the statement, never read and written back from Java.
 *
 * <h2>The backoff, on the database's clock</h2>
 *
 * <p>The n-th consecutive failure waits {@link SweepBackoff#BASE} times 2^(n-1), capped at {@link SweepBackoff#CAP}, from
 * {@code statement_timestamp()}: no instance's clock decides when a row is listed again. A failure more than twice the cap
 * after the last one starts the count again - the row had recovered in between. Only the failure's class is kept
 * ({@code INV-AUD-02}), and the column's check refuses anything else.
 */
final class SweepRowFailures {

    private static final String RECORD =
            "INSERT INTO payments.sweep_row_failure AS f"
                    + " (leg, row_id, failures, first_failed_at, last_failed_at, retry_at, last_failure)"
                    + " VALUES (?, ?, 1, statement_timestamp(), statement_timestamp(),"
                    + " statement_timestamp() + make_interval(secs => ?), ?)"
                    + " ON CONFLICT (leg, row_id) DO UPDATE SET"
                    // A failure long after the last one is a new run of failures: the row had recovered.
                    + " failures = CASE WHEN f.last_failed_at < statement_timestamp() - make_interval(secs => ?)"
                    + " THEN 1 ELSE f.failures + 1 END,"
                    + " first_failed_at = CASE WHEN f.last_failed_at < statement_timestamp() - make_interval(secs => ?)"
                    + " THEN statement_timestamp() ELSE f.first_failed_at END,"
                    + " last_failed_at = statement_timestamp(),"
                    // Doubling from the base per consecutive failure, capped; the exponent bounded so the power cannot overflow.
                    + " retry_at = statement_timestamp() + CASE"
                    + " WHEN f.last_failed_at < statement_timestamp() - make_interval(secs => ?) THEN make_interval(secs => ?)"
                    + " ELSE LEAST(make_interval(secs => CAST(? AS double precision) * power(2, LEAST(f.failures, 20))),"
                    + " make_interval(secs => ?)) END,"
                    + " last_failure = EXCLUDED.last_failure"
                    // The arbiter: one count per backoff window, however many instances failed the row.
                    + " WHERE f.retry_at <= statement_timestamp()";

    private SweepRowFailures() {}

    /**
     * Records one failure of {@code row} in {@code leg}'s listing, on {@code unitOfWork}. True when this call counted it;
     * false when the row's backoff was still running (another instance counted it first).
     */
    static boolean record(Connection unitOfWork, SweepLeg leg, UUID row, String failure) {
        Objects.requireNonNull(leg, "leg must not be null");
        Objects.requireNonNull(row, "row must not be null");
        Objects.requireNonNull(failure, "failure must not be null");
        long base = SweepBackoff.BASE.toSeconds();
        long cap = SweepBackoff.CAP.toSeconds();
        long recovered = 2 * cap;
        try (PreparedStatement upsert = unitOfWork.prepareStatement(RECORD)) {
            upsert.setString(1, leg.name());
            upsert.setObject(2, row);
            upsert.setLong(3, base);
            upsert.setString(4, failure);
            upsert.setLong(5, recovered);
            upsert.setLong(6, recovered);
            upsert.setLong(7, recovered);
            upsert.setLong(8, base);
            upsert.setLong(9, base);
            upsert.setLong(10, cap);
            return upsert.executeUpdate() == 1;
        } catch (SQLException failed) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("recording a " + leg + " sweep row's failure", failed));
        }
    }
}
