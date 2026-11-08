package com.finapp.migrator;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The migrator refuses to run as anything but the migrator, and without its lock bound ({@code P15-TSK-004}; T33). */
@DisplayName("the identity and session the migrator will run as (P15-TSK-004)")
class ConnectedRoleTest {

    private static final long BOUND = 3000;

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static ResultSet oneRow(Object... columns) {
        boolean[] read = {false};
        return fake(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
            case "next" -> {
                boolean first = !read[0];
                read[0] = true;
                yield first;
            }
            case "getString", "getBoolean", "getLong" -> columns[(int) args[0] - 1];
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    /** A session whose role holds the five rights given, in the order the query asks, with this {@code lock_timeout}. */
    private static Connection session(boolean[] rights, String lockTimeout) {
        return session(rights, 0L, lockTimeout);
    }

    private static Connection session(boolean[] rights, long memberships, String lockTimeout) {
        Statement statement = fake(Statement.class, (proxy, method, args) -> switch (method.getName()) {
            case "executeQuery" -> ((String) args[0]).contains("pg_roles")
                    ? oneRow("whoever", rights[0], rights[1], rights[2], rights[3], rights[4], memberships)
                    : oneRow(lockTimeout);
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        return fake(Connection.class, (proxy, method, args) -> switch (method.getName()) {
            case "createStatement" -> statement;
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    @Test
    @DisplayName("the migrator's role, with the bound in force, passes")
    void theMigratorPasses() {
        assertThatCode(() -> ConnectedRole.verify(session(new boolean[5], "3000"), BOUND)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    @DisplayName("each administrative right on its own is a refusal: superuser, CREATEROLE, CREATEDB, BYPASSRLS, REPLICATION")
    void anAdministrativeRightIsRefused(int right) {
        boolean[] rights = new boolean[5];
        rights[right] = true;
        assertThatThrownBy(() -> ConnectedRole.verify(session(rights, "3000"), BOUND))
                .isInstanceOf(RefusedConfiguration.class)
                .hasMessageContaining("not the migrator's credential");
    }

    @Test
    @DisplayName("a role with no attribute of its own but a membership is refused: rds_superuser and pg_write_all_data are memberships")
    void aMembershipIsARight() {
        assertThatThrownBy(() -> ConnectedRole.verify(session(new boolean[5], 1L, "3000"), BOUND))
                .isInstanceOf(RefusedConfiguration.class)
                .hasMessageContaining("1 role membership(s)")
                .hasMessageContaining("not the migrator's credential");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "2999", "60000"})
    @DisplayName("a session where the bound did not take is refused, the value that disables it included")
    void aSessionWithoutTheBoundIsRefused(String setting) {
        assertThatThrownBy(() -> ConnectedRole.verify(session(new boolean[5], setting), BOUND))
                .isInstanceOf(RefusedConfiguration.class)
                .hasMessageContaining("lock_timeout");
    }
}
