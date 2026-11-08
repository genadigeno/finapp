package com.finapp.migrator;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * What the connection actually is, read from the database before anything runs.
 *
 * <p>A superuser or a role with {@code CREATEROLE} must not be the migrator. On a managed database the master user is
 * not a superuser but does hold {@code CREATEROLE}, so that is the check that catches its credential being handed to the
 * Job by mistake. Objects it created would be owned by an identity the append-only triggers' owner discipline was never
 * written against.
 */
final class ConnectedRole {

    private static final String RIGHTS = "SELECT current_user, r.rolsuper, r.rolcreaterole, r.rolcreatedb, r.rolbypassrls,"
            + " r.rolreplication, (SELECT count(*) FROM pg_catalog.pg_auth_members m WHERE m.member = r.oid)"
            + " FROM pg_catalog.pg_roles r WHERE r.rolname = current_user";

    private static final String LOCK_TIMEOUT =
            "SELECT setting FROM pg_catalog.pg_settings WHERE name = 'lock_timeout'";

    private ConnectedRole() {}

    /** @throws RefusedConfiguration when the role holds an administrative right, or the session has no lock bound */
    static void verify(Connection connection, long expectedLockTimeoutMillis) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            List<String> rights = new ArrayList<>();
            String role;
            try (ResultSet row = statement.executeQuery(RIGHTS)) {
                if (!row.next()) {
                    throw new RefusedConfiguration("the connected role is not in pg_roles");
                }
                role = row.getString(1);
                addIf(rights, row.getBoolean(2), "SUPERUSER");
                addIf(rights, row.getBoolean(3), "CREATEROLE");
                addIf(rights, row.getBoolean(4), "CREATEDB");
                addIf(rights, row.getBoolean(5), "BYPASSRLS");
                addIf(rights, row.getBoolean(6), "REPLICATION");
                // A role carrying none of those can still be handed them by membership: rds_superuser, pg_write_all_data.
                long memberships = row.getLong(7);
                addIf(rights, memberships != 0, memberships + " role membership(s)");
            }
            if (!rights.isEmpty()) {
                throw new RefusedConfiguration("the role " + role + " holds " + rights
                        + ": the migrator's role holds none of these, so this is not the migrator's credential");
            }
            try (ResultSet row = statement.executeQuery(LOCK_TIMEOUT)) {
                row.next();
                long setting = Long.parseLong(row.getString(1));
                if (setting != expectedLockTimeoutMillis) {
                    throw new RefusedConfiguration("lock_timeout is " + setting + " ms on this session, not "
                            + expectedLockTimeoutMillis + " ms: a migration would queue in front of every writer");
                }
            }
        }
    }

    private static void addIf(List<String> rights, boolean holds, String right) {
        if (holds) {
            rights.add(right);
        }
    }
}
