package com.finapp.app.accounting;

import com.finapp.accounting.AccountingStorageException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

/**
 * The GL's one read-only snapshot per request (`P14-TSK-007`; {@code INV-ACC-14}, owner decision C10; the
 * {@code CreditReadingSnapshot} shape): a connection of its own, {@code REPEATABLE READ} and read-only, every statement of
 * the read on it, always rolled back - so the period's pin, the version, the aggregates, the ledger's totals and the
 * drill-down page see one instant (an entry wholly in or wholly out), and the database itself refuses a write.
 *
 * <p><strong>Verified, not assumed.</strong> The first statement - which also starts the snapshot - reads the session's
 * {@code transaction_isolation} and {@code transaction_read_only} back; anything but {@code repeatable read} and
 * {@code on} refuses the read before a figure is computed, so a pool, a driver or a configuration that dropped either
 * setting fails closed instead of serving figures from a writable, moving transaction.
 *
 * <p><strong>Bounded.</strong> The second arms {@code statement_timeout} for the rest of the transaction
 * ({@code set_config(..., true)} - {@code SET LOCAL}, gone with the rollback; a setting, never a write, so the read-only
 * transaction admits it; the {@code RiskProofs.bound} idiom), {@code finapp.accounting.gl.statement-timeout},
 * {@link #STATEMENT_TIMEOUT} by default. A statement past it is cancelled by PostgreSQL ({@code 57014}) and the read ends
 * as {@link ReadTimedOut} - the door's {@code accounting.ReadTimedOut} 503, never a stack trace - and the snapshot rolls
 * back like any other. A connection the network black-holes is not bounded here: the data source's socket timeout gives it up
 * after five minutes (ADR-0139).
 */
public final class GlSnapshots {

    /** The first statement: the snapshot's own settings, read back. */
    static final String VERIFY = "SELECT current_setting('transaction_isolation'),"
            + " current_setting('transaction_read_only')";

    /** The second: the statement timeout, local to the transaction. */
    static final String BOUND = "SELECT set_config('statement_timeout', ?, true)";

    /** PostgreSQL's {@code query_canceled} - a statement cancelled by {@code statement_timeout}. */
    static final String QUERY_CANCELED = "57014";

    /** A GL read's statement timeout unless configured ({@code finapp.accounting.gl.statement-timeout}). */
    public static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(10);

    /** Opens a connection for one read. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    /** The session was not the read-only repeatable-read snapshot it was set up as - nothing was read. */
    public static final class SnapshotRefused extends IllegalStateException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SnapshotRefused(String detail) {
            super(detail);
        }
    }

    /** A statement of the read outlived the statement timeout and was cancelled - nothing was read whole. */
    public static final class ReadTimedOut extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ReadTimedOut() {
            super("a GL read's statement was cancelled by its statement timeout (SQLSTATE " + QUERY_CANCELED + ")");
        }
    }

    private final Connections connections;
    private final Duration statementTimeout;

    public GlSnapshots(Connections connections, Duration statementTimeout) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.statementTimeout = Objects.requireNonNull(statementTimeout, "statementTimeout");
        if (statementTimeout.toMillis() < 1) {
            // PostgreSQL reads zero as "no timeout": a bound under a millisecond is no bound.
            throw new IllegalArgumentException("a GL read's statement timeout is at least one millisecond");
        }
    }

    /** {@code work} on one verified, bounded {@code REPEATABLE READ READ ONLY} snapshot, always rolled back. */
    public <R> R read(Function<Connection, R> work) {
        Objects.requireNonNull(work, "work");
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            try {
                verify(connection);
                bound(connection, statementTimeout);
                return work.apply(connection);
            } catch (RuntimeException failure) {
                if (timedOut(failure)) {
                    throw new ReadTimedOut();
                }
                throw failure;
            } finally {
                connection.rollback();
            }
        } catch (SQLException failure) {
            if (QUERY_CANCELED.equals(failure.getSQLState())) {
                throw new ReadTimedOut();
            }
            throw new AccountingStorageException("could not read a GL snapshot: SQLSTATE " + failure.getSQLState());
        }
    }

    /**
     * True when {@code failure} is a statement cancelled by the timeout: the stores report a failure as their operation
     * and {@code (SQLState 57014)} ({@code DatabaseFailure.describe}'s form - never the driver's message or cause), so the
     * cancellation is read from that suffix.
     */
    static boolean timedOut(RuntimeException failure) {
        return failure instanceof AccountingStorageException
                && failure.getMessage() != null
                && failure.getMessage().endsWith("(SQLState " + QUERY_CANCELED + ")");
    }

    private static void verify(Connection connection) throws SQLException {
        try (PreparedStatement read = connection.prepareStatement(VERIFY); ResultSet row = read.executeQuery()) {
            if (!row.next()) {
                throw new SnapshotRefused("the GL snapshot's settings could not be read back");
            }
            String isolation = row.getString(1);
            String readOnly = row.getString(2);
            if (!"repeatable read".equals(isolation) || !"on".equals(readOnly)) {
                throw new SnapshotRefused("the GL reads only in a REPEATABLE READ READ ONLY snapshot; the session was "
                        + isolation + ", read only " + readOnly);
            }
        }
    }

    private static void bound(Connection connection, Duration statementTimeout) throws SQLException {
        try (PreparedStatement set = connection.prepareStatement(BOUND)) {
            set.setString(1, statementTimeout.toMillis() + "ms");
            try (ResultSet armed = set.executeQuery()) {
                if (!armed.next()) {
                    throw new SnapshotRefused("the GL snapshot's statement timeout could not be armed");
                }
            }
        }
    }
}
