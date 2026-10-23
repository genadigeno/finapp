package com.finapp.risk;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link EvaluationBudget} over JDBC savepoints and PostgreSQL's {@code statement_timeout} (`P13-TSK-004`;
 * {@code PHASE_13_PLAN.md} section 7.6). The timeout is set with {@code set_config(..., true)} - {@code SET LOCAL} -
 * <em>after</em> the savepoint, so rolling back to it reverts the setting with everything else; keeping restores the
 * transaction's own value explicitly before the release, so nothing of the budget outlives the step. Three statements
 * of overhead per step, every one on the caller's connection.
 */
public final class JdbcEvaluationBudget implements EvaluationBudget {

    @Override
    public Mark open(Connection unitOfWork, Optional<Duration> timeout) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(timeout, "timeout");
        try {
            Optional<String> restored = timeout.isPresent() ? Optional.of(currentTimeout(unitOfWork)) : Optional.empty();
            Savepoint savepoint = unitOfWork.setSavepoint();
            if (timeout.isPresent()) {
                setTimeout(unitOfWork, timeout.get().toMillis() + "ms");
            }
            return new Mark(savepoint, restored);
        } catch (SQLException failure) {
            throw failed("opening the evaluation's savepoint", failure);
        }
    }

    @Override
    public void keep(Connection unitOfWork, Mark mark) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(mark, "mark");
        try {
            if (mark.restoredTimeout().isPresent()) {
                setTimeout(unitOfWork, mark.restoredTimeout().get());
            }
            unitOfWork.releaseSavepoint((Savepoint) mark.savepoint());
        } catch (SQLException failure) {
            throw failed("keeping the evaluation's step", failure);
        }
    }

    @Override
    public void undo(Connection unitOfWork, Mark mark) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(mark, "mark");
        try {
            unitOfWork.rollback((Savepoint) mark.savepoint());
            if (mark.restoredTimeout().isPresent()) {
                // Rolling back reverted the SET LOCAL already; restating the transaction's value costs one statement
                // and holds whatever a driver does with savepoints.
                setTimeout(unitOfWork, mark.restoredTimeout().get());
            }
        } catch (SQLException failure) {
            // The savepoint cannot be rolled back to: the caller's transaction is lost, and says so.
            throw failed("undoing the evaluation's step", failure);
        }
    }

    private static String currentTimeout(Connection unitOfWork) throws SQLException {
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT current_setting('statement_timeout')");
                ResultSet row = select.executeQuery()) {
            if (!row.next()) {
                throw new RiskStorageException("risk storage failed reading the statement timeout: no row");
            }
            return row.getString(1);
        }
    }

    private static void setTimeout(Connection unitOfWork, String value) throws SQLException {
        try (PreparedStatement set = unitOfWork.prepareStatement("SELECT set_config('statement_timeout', ?, true)")) {
            set.setString(1, value);
            set.execute();
        }
    }

    private static RiskStorageException failed(String operation, SQLException failure) {
        return new RiskStorageException("risk storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
