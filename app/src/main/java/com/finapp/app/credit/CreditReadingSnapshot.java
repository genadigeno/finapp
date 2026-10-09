package com.finapp.app.credit;

import com.finapp.credit.CreditStorageException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Function;

/**
 * One {@code REPEATABLE READ} read-only snapshot, rolled back (`P10-TSK-019`; PHASE_10_PLAN.md section 7's last row) -
 * the replay route's and the replay proof's reading. It sees nothing committed after it began and can write nothing,
 * so every instance reading one committed state reaches one verdict. The {@code FxProofMetrics} shape, credit's own.
 */
public final class CreditReadingSnapshot {

    /** Opens a connection for one reading. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final Connections connections;

    public CreditReadingSnapshot(Connections connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /** {@code work} on one read-only {@code REPEATABLE READ} snapshot, always rolled back. */
    public <R> R read(Function<Connection, R> work) {
        Objects.requireNonNull(work, "work");
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            try {
                return work.apply(connection);
            } finally {
                connection.rollback();
            }
        } catch (SQLException failure) {
            throw new CreditStorageException("could not read a credit snapshot: " + failure.getSQLState());
        }
    }
}
