package com.finapp.migrator;

import java.sql.SQLException;
import org.flywaydb.core.internal.exception.FlywayMigrateException;

/**
 * Which failures another attempt can help, by what PostgreSQL said rather than by what the message reads like.
 *
 * <p>Deliberately narrow, because the wide reading costs twice: retrying a failure that is really a defect hides it for the
 * length of the retries, and a failed <em>non-transactional</em> migration has already written a failed row, so running
 * it again only repeats Flyway's refusal.
 */
enum FailureClass {

    /** A lock was not granted within {@code lock_timeout}, or the statement was a deadlock's victim. Retried here. */
    LOCK_CONTENTION(ExitCode.TRANSIENT),

    /** The database, or Flyway's lock held by another run, was not available within its bound. Left to the Job. */
    UNAVAILABLE(ExitCode.TRANSIENT),

    /** Everything else. It would fail the same way again. */
    DEFINITE(ExitCode.FAILED);

    /** {@code PostgreSQLAdvisoryLockTemplate}'s words: Flyway raises this as a plain exception, with no SQLSTATE. */
    private static final String HISTORY_LOCK_EXHAUSTED =
            "Number of retries exceeded while attempting to acquire PostgreSQL advisory lock";

    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final String DEADLOCK_DETECTED = "40P01";
    private static final String TOO_MANY_CONNECTIONS = "53300";

    /** Class 08 is connection exceptions; {@code 57P01}, {@code 57P02} and {@code 57P03} a server stopping or starting. */
    private static boolean isUnavailable(String sqlState) {
        return sqlState.startsWith("08") || sqlState.startsWith("57P") || sqlState.equals(TOO_MANY_CONNECTIONS);
    }

    private final ExitCode exitCode;

    FailureClass(ExitCode exitCode) {
        this.exitCode = exitCode;
    }

    ExitCode exitCode() {
        return exitCode;
    }

    static FailureClass of(Throwable failure) {
        String sqlState = sqlStateOf(failure);
        if (sqlState != null) {
            if (sqlState.equals(LOCK_NOT_AVAILABLE) || sqlState.equals(DEADLOCK_DETECTED)) {
                return leftNoRecord(failure) ? LOCK_CONTENTION : DEFINITE;
            }
            if (isUnavailable(sqlState)) {
                return UNAVAILABLE;
            }
            return DEFINITE;
        }
        return mentionsHistoryLockExhausted(failure) ? UNAVAILABLE : DEFINITE;
    }

    /** True unless Flyway says this migration could not run in a transaction, which is when it writes a failed row. */
    private static boolean leftNoRecord(Throwable failure) {
        for (Throwable t = failure; t != null; t = next(t)) {
            if (t instanceof FlywayMigrateException migrate) {
                return migrate.isExecutableInTransaction();
            }
        }
        return true;
    }

    private static String sqlStateOf(Throwable failure) {
        for (Throwable t = failure; t != null; t = next(t)) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            if (t instanceof FlywayMigrateException migrate && migrate.getSqlState() != null) {
                return migrate.getSqlState();
            }
        }
        return null;
    }

    private static boolean mentionsHistoryLockExhausted(Throwable failure) {
        for (Throwable t = failure; t != null; t = next(t)) {
            if (t.getMessage() != null && t.getMessage().contains(HISTORY_LOCK_EXHAUSTED)) {
                return true;
            }
        }
        return false;
    }

    private static Throwable next(Throwable t) {
        return t.getCause() == t ? null : t.getCause();
    }
}
