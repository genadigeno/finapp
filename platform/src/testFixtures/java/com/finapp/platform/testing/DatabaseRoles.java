package com.finapp.platform.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Connections as the two ordinary database roles.
 *
 * <p>Moved here from the audit tests by {@code P0-TST-009}: it was package-private in
 * {@code com.finapp.platform.audit}, which is why two earlier tasks put schema-wide tests in the
 * audit package purely to reach it. It is test support, not an audit concern.
 *
 * <p><strong>Why this exists rather than a second {@code DriverManager.getConnection} in each
 * test.</strong> A privilege-level invariant is only tested if the connection cannot bypass
 * privileges, and a superuser bypasses every one of them silently. A test that connected as the
 * bootstrap superuser would pass with the grants correct, with the grants wrong, and with no
 * grants at all — the worst kind of green.
 *
 * <p>{@link #assertCannotBypassPrivileges} is therefore called by every test that asserts a
 * denial, and asserts the properties that would make the denial meaningless: superuser status,
 * and {@code BYPASSRLS}. It is cheap, and it is the difference between a test that proves
 * {@code INV-HIST-03} and a test that proves nothing while looking identical.
 */
public final class DatabaseRoles {

    private DatabaseRoles() {}

    /** The role the application connects as: per-table DML only, no DDL, not a superuser. */
    public static Connection application() throws SQLException {
        return DriverManager.getConnection(
                required("finapp.db.url"),
                required("finapp.db.app.user"),
                required("finapp.db.app.password"));
    }

    /** The role that owns the schema. For fixtures that legitimately need DDL, and nothing else. */
    public static Connection migrator() throws SQLException {
        return DriverManager.getConnection(
                required("finapp.db.url"),
                required("finapp.db.migrator.user"),
                required("finapp.db.migrator.password"));
    }

    /**
     * Fails if this connection's role could ignore the grants under test.
     *
     * <p>Without this, changing the test's credentials to the superuser — an easy thing to do
     * while debugging, and an easy thing to leave behind — would turn every denial assertion
     * into a no-op that still reported success.
     */
    public static void assertCannotBypassPrivileges(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT rolsuper, rolbypassrls, current_user FROM pg_roles "
                                        + "WHERE rolname = current_user")) {
            assertThat(rows.next()).as("the connected role must exist in pg_roles").isTrue();
            String role = rows.getString("current_user");
            assertThat(rows.getBoolean("rolsuper"))
                    .as("%s is a superuser, so every privilege assertion below is vacuous", role)
                    .isFalse();
            assertThat(rows.getBoolean("rolbypassrls"))
                    .as("%s can bypass row-level security", role)
                    .isFalse();
        }
    }

    public static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "System property "
                            + name
                            + " is not set. Run this through './gradlew databaseTest'. That task"
                            + " supplies finapp.db.image, and DatabaseUnderTest then starts a"
                            + " container and publishes these coordinates - so running the class"
                            + " directly from an IDE lands here (P0-TSK-035).");
        }
        return value;
    }
}
