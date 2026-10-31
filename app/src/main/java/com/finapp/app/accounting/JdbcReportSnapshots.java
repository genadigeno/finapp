package com.finapp.app.accounting;

import com.finapp.accounting.AccountingStorageException;
import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

/**
 * {@link ReportSnapshots} over a connection source (`P14-TSK-008`; {@code INV-ACC-14}; the {@code CreditReadingSnapshot}
 * shape, made explicit): each read opens <strong>its own connection</strong> from the pool - never the one bound to the
 * caller's transaction - turns autocommit off, and makes {@link #BEGIN_SQL} the transaction's first statement, so the
 * isolation and the read-only-ness are the transaction's own and never leak into the pooled session. Then it
 * <strong>proves</strong> them ({@link #PROOF_SQL}) before any report code reads: a snapshot that is not read-only
 * {@code REPEATABLE READ} - a driver or pool configured otherwise - is refused, fail closed. Then it arms the snapshot's
 * <strong>time bound</strong> ({@link #BOUND}: {@code statement_timeout}, local to the transaction - the
 * {@code GlSnapshots} idiom) at the configured generation bound, which {@code ReportRuns.Leases} holds below the
 * generator's lease (`P14-TSK-008`'s review, I-1); a report generation re-arms it to what is left of its budget before
 * every statement ({@code ReadOnlySnapshot}). The work runs; the transaction is rolled back however it ends - a snapshot
 * never commits anything, because it wrote nothing it could.
 *
 * <p>{@link #BEGIN_SQL} is the database half of "a write inside generation is refused": PostgreSQL answers {@code 25006
 * read_only_sql_transaction} to any {@code INSERT}, {@code UPDATE}, {@code DELETE} or DDL inside it
 * ({@code ReportRunDatabaseTest#aWriteInsideGenerationIsRefused}, owed C5); {@code JdbcReportSnapshotsTest} pins the
 * statement and the proof.
 */
public final class JdbcReportSnapshots implements ReportSnapshots {

    /** The snapshot's first statement: its isolation and its read-only-ness, the transaction's own. */
    static final String BEGIN_SQL = "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY";

    /** What the database says the transaction is - read before any report code runs. */
    static final String PROOF_SQL =
            "SELECT current_setting('transaction_read_only'), current_setting('transaction_isolation')";

    /** The bound, armed after the proof - {@code SET LOCAL statement_timeout}, gone with the rollback. */
    static final String BOUND = "SELECT set_config('statement_timeout', ?, true)";

    /** A connection source - the pool, never a caller's bound connection. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    /** The snapshot is not what it must be - nothing was read. */
    public static final class NotReadOnly extends IllegalStateException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NotReadOnly(String detail) {
            super(detail);
        }
    }

    private final Connections connections;
    private final Duration statementTimeout;

    /**
     * @param connections the pool
     * @param statementTimeout the snapshot's bound - the configured generation bound, at least a millisecond
     */
    public JdbcReportSnapshots(Connections connections, Duration statementTimeout) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.statementTimeout = Objects.requireNonNull(statementTimeout, "statementTimeout");
        if (statementTimeout.toMillis() < 1) {
            // PostgreSQL reads zero as "no timeout": a bound under a millisecond is no bound.
            throw new IllegalArgumentException("a report snapshot's statement timeout is at least one millisecond");
        }
    }

    @Override
    public <R> R read(Function<Connection, R> work) {
        Objects.requireNonNull(work, "work");
        try (Connection snapshot = connections.open()) {
            snapshot.setAutoCommit(false);
            snapshot.setReadOnly(true);
            try {
                try (Statement begin = snapshot.createStatement()) {
                    begin.execute(BEGIN_SQL);
                }
                prove(snapshot);
                bound(snapshot, statementTimeout);
                return work.apply(snapshot);
            } finally {
                snapshot.rollback();
            }
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe("opening a report snapshot", failure));
        }
    }

    private static void bound(Connection snapshot, Duration statementTimeout) throws SQLException {
        try (PreparedStatement set = snapshot.prepareStatement(BOUND)) {
            set.setString(1, statementTimeout.toMillis() + "ms");
            try (ResultSet armed = set.executeQuery()) {
                if (!armed.next()) {
                    throw new NotReadOnly("a report snapshot's statement timeout could not be armed");
                }
            }
        }
    }

    /** {@code on} and {@code repeatable read}, or nothing is read. */
    private static void prove(Connection snapshot) throws SQLException {
        try (Statement proof = snapshot.createStatement(); ResultSet row = proof.executeQuery(PROOF_SQL)) {
            if (!row.next() || !"on".equals(row.getString(1)) || !"repeatable read".equals(row.getString(2))) {
                throw new NotReadOnly("a report snapshot is a read-only REPEATABLE READ transaction, and this one is not");
            }
        }
    }
}
