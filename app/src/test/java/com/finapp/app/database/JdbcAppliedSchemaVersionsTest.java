package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The read over a stubbed driver (`P15-TSK-005`): what it asks of the connection and what it makes of the rows. Against
 * PostgreSQL and the real pool it is {@code SchemaVersionReadDatabaseTest}, owed.
 */
@DisplayName("the guard's read is bounded, and picks each schema's highest version as a version (P15-TSK-005)")
class JdbcAppliedSchemaVersionsTest {

    private final List<String> calls = new ArrayList<>();

    private DataSource returning(String[]... rows) {
        Deque<String[]> left = new ArrayDeque<>(List.of(rows));
        ResultSet[] holder = new ResultSet[1];
        String[][] current = new String[1][];
        holder[0] = proxy(ResultSet.class, (method, args) -> switch (method) {
            case "next" -> {
                current[0] = left.poll();
                yield current[0] != null;
            }
            case "getString" -> current[0][(Integer) args[0] - 1];
            default -> null;
        });
        PreparedStatement statement = proxy(PreparedStatement.class, (method, args) -> "executeQuery".equals(method) ? holder[0] : null);
        Connection connection = proxy(Connection.class, (method, args) -> {
            switch (method) {
                case "setNetworkTimeout" -> calls.add("timeout:" + args[1]);
                case "prepareStatement" -> {
                    calls.add("prepare");
                    return statement;
                }
                default -> { }
            }
            return null;
        });
        return proxy(DataSource.class, (method, args) -> "getConnection".equals(method) ? connection : null);
    }

    private interface Answer {
        Object call(String method, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Answer answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
            Object answered = answer.call(method.getName(), args);
            return answered == null && method.getReturnType() == boolean.class ? Boolean.FALSE : answered;
        });
    }

    @Test
    @DisplayName("the connection's network timeout is set to the bound before the statement is prepared")
    void theReadIsBoundedBeforeItRuns() throws Exception {
        new JdbcAppliedSchemaVersions(returning()).read();

        assertThat(calls).containsExactly("timeout:" + JdbcAppliedSchemaVersions.NETWORK_TIMEOUT_MILLIS, "prepare");
        assertThat(JdbcAppliedSchemaVersions.NETWORK_TIMEOUT_MILLIS).isPositive();
    }

    @Test
    @DisplayName("each schema's highest is chosen as a version, whatever order the rows come in")
    void theHighestIsChosenAsAVersion() throws Exception {
        Map<String, SchemaVersion> read = new JdbcAppliedSchemaVersions(
                returning(new String[] {"ledger", "10"}, new String[] {"ledger", "9"}, new String[] {"platform", "013"}, new String[] {"platform", "012"}))
                .read();

        assertThat(read).containsOnlyKeys("ledger", "platform");
        assertThat(read.get("ledger")).isEqualTo(SchemaVersion.parse("10"));
        assertThat(read.get("platform")).isEqualTo(SchemaVersion.parse("13"));
    }

    @Test
    @DisplayName("a version that does not parse is thrown to the guard, not skipped")
    void anUnparseableVersionIsNotSkipped() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JdbcAppliedSchemaVersions(returning(new String[] {"ledger", "not-a-version"})).read());
    }
}
