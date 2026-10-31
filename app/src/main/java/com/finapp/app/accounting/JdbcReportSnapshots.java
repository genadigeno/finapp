package com.finapp.app.accounting;

import com.finapp.accounting.AccountingStorageException;
import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.function.Function;

/**
 * {@link ReportSnapshots} over a connection source (`P14-TSK-008`; {@code INV-ACC-14}; the {@code CreditReadingSnapshot}
 * shape, made explicit): each read opens <strong>its own connection</strong> from the pool - never the one bound to the
 * caller's transaction - turns autocommit off, and makes {@link #BEGIN_SQL} the transaction's first statement, so the
 * isolation and the read-only-ness are the transaction's own and never leak into the pooled session. Then it
 * <strong>proves</strong> them ({@link #PROOF_SQL}) before any report code reads: a snapshot that is not read-only
 * {@code REPEATABLE READ} - a driver or pool configured otherwise - is refused, fail closed. The work runs; the
 * transaction is rolled back however it ends - a snapshot never commits anything, because it wrote nothing it could.
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

    public JdbcReportSnapshots(Connections connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
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
                return work.apply(snapshot);
            } finally {
                snapshot.rollback();
            }
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe("opening a report snapshot", failure));
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
