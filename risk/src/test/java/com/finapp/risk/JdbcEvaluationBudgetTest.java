package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The savepoint and the statement timeout on the wire (`P13-TSK-004`; {@code PHASE_13_PLAN.md} section 7.6): over a
 * recording connection, a timed step reads the transaction's timeout, sets a savepoint, arms {@code SET LOCAL} 250 ms
 * <em>after</em> it; keeping restores the transaction's value and then releases; undoing rolls back to the savepoint and
 * restates the value; the untimed step (the pin's) touches no setting at all; a driver's failure is a
 * {@link RiskStorageException} naming its SQLSTATE only. The live behaviour - a cancelled statement leaving the caller's
 * transaction usable - is {@code RiskEvaluationDatabaseTest#aTimeoutSealsTheFailSafe}'s (written, owed - C5).
 */
@DisplayName("JdbcEvaluationBudget: savepoint first, SET LOCAL after, restored before the release (P13-TSK-004)")
class JdbcEvaluationBudgetTest {

    private final List<String> wire = new ArrayList<>();
    private final JdbcEvaluationBudget budget = new JdbcEvaluationBudget();

    @Test
    @DisplayName("a timed step: the timeout read, the savepoint, SET LOCAL 250ms; kept: the value restored, then released")
    void aTimedStepIsKept() {
        Connection connection = recording(false);
        EvaluationBudget.Mark mark = budget.open(connection, Optional.of(EvaluationBudget.READ_TIMEOUT));
        budget.keep(connection, mark);
        assertThat(wire).containsExactly(
                "query SELECT current_setting('statement_timeout')",
                "setSavepoint",
                "execute SELECT set_config('statement_timeout', ?, true) [250ms]",
                "execute SELECT set_config('statement_timeout', ?, true) [0]",
                "releaseSavepoint");
    }

    @Test
    @DisplayName("a timed step undone: rolled back to its savepoint - which reverts the SET LOCAL - and the value restated")
    void aTimedStepIsUndone() {
        Connection connection = recording(false);
        EvaluationBudget.Mark mark = budget.open(connection, Optional.of(Duration.ofMillis(250)));
        wire.clear();
        budget.undo(connection, mark);
        assertThat(wire).containsExactly("rollback(savepoint)",
                "execute SELECT set_config('statement_timeout', ?, true) [0]");
    }

    @Test
    @DisplayName("the pin's step: a savepoint and nothing of the timeout - an evaluation behind an activation waits for it")
    void anUntimedStepTouchesNoSetting() {
        Connection connection = recording(false);
        EvaluationBudget.Mark mark = budget.open(connection, Optional.empty());
        budget.keep(connection, mark);
        assertThat(wire).containsExactly("setSavepoint", "releaseSavepoint");
    }

    @Test
    @DisplayName("a driver's failure is a RiskStorageException naming the SQLSTATE only")
    void aFailureNamesItsSqlState() {
        Connection broken = recording(true);
        assertThatThrownBy(() -> budget.open(broken, Optional.of(EvaluationBudget.READ_TIMEOUT)))
                .isInstanceOf(RiskStorageException.class).hasMessageEndingWith("SQLSTATE 08006")
                .satisfies(e -> assertThat(((RiskStorageException) e).sqlState()).contains("08006"));
    }

    private Connection recording(boolean fail) {
        Savepoint savepoint = (Savepoint) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Savepoint.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "prepareStatement" -> {
                            if (fail) {
                                throw new SQLException("connection lost", "08006");
                            }
                            return statement((String) args[0]);
                        }
                        case "setSavepoint" -> {
                            wire.add("setSavepoint");
                            return savepoint;
                        }
                        case "releaseSavepoint" -> {
                            wire.add("releaseSavepoint");
                            return null;
                        }
                        case "rollback" -> {
                            wire.add(args != null && args.length == 1 && args[0] == savepoint ? "rollback(savepoint)"
                                    : "rollback");
                            return null;
                        }
                        case "toString" -> {
                            return "RecordingConnection";
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private PreparedStatement statement(String sql) {
        List<Object> parameters = new ArrayList<>();
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setString" -> {
                        parameters.add(args[1]);
                        yield null;
                    }
                    case "execute" -> {
                        wire.add("execute " + sql + " " + parameters);
                        yield true;
                    }
                    case "executeQuery" -> {
                        wire.add("query " + sql);
                        yield result();
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ResultSet result() {
        boolean[] read = {false};
        return (ResultSet) Proxy.newProxyInstance(JdbcEvaluationBudgetTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        boolean first = !read[0];
                        read[0] = true;
                        yield first;
                    }
                    case "getString" -> "0";
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
