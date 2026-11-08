package com.finapp.migrator;

import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.util.List;
import javax.net.ssl.SSLException;
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
    private static final String DATABASE_NOT_THERE = "3D000";

    /**
     * The driver's words for the TLS failures it raises with no security exception behind them, all under {@code 08006} or
     * {@code 08004}: {@code MakeSSL}'s hostname check, {@code LibPQFactory}'s root certificate, {@code ConnectionFactoryImpl}'s
     * server with no TLS. Each is the same on the next attempt.
     */
    private static final List<String> TLS_REFUSALS = List.of(
            "could not be verified by hostnameverifier", "SSL root certificate", "The server does not support SSL");

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

    /**
     * The exit for a failure before anything was applied: connecting, and the checks on what it connected to. Class 28 is a
     * credential the server refused and {@code 3D000} a database that is not there; a TLS connection that cannot be verified
     * is a wrong CA bundle, path or host. None gets better by retrying, and calling them transient would send the operator
     * looking for a long transaction.
     */
    static ExitCode beforeAnythingRan(Throwable failure) {
        String sqlState = sqlStateOf(failure);
        if (sqlState != null && (sqlState.startsWith("28") || sqlState.equals(DATABASE_NOT_THERE))) {
            return ExitCode.REFUSED;
        }
        return isTlsRefusal(failure) ? ExitCode.REFUSED : of(failure).exitCode();
    }

    /**
     * A TLS failure that will be the same next time. Not every {@link SSLException} is one: a connection dropped during the
     * handshake surfaces as one too, with the network's exception behind it, and that one may well succeed next time.
     */
    static boolean isTlsRefusal(Throwable failure) {
        boolean tls = false;
        boolean networkWentAway = false;
        for (Throwable t = failure; t != null; t = next(t)) {
            if (t instanceof GeneralSecurityException) {
                return true;
            }
            String message = t.getMessage();
            if (message != null && TLS_REFUSALS.stream().anyMatch(message::contains)) {
                return true;
            }
            tls |= t instanceof SSLException;
            networkWentAway |= t instanceof EOFException || t instanceof SocketException || t instanceof SocketTimeoutException;
        }
        return tls && !networkWentAway;
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
