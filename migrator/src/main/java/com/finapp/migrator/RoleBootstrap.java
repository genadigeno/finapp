package com.finapp.migrator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.postgresql.util.PasswordUtil;

/**
 * The deployed roles, created once by the master user ({@code DATA_MIGRATIONS.md} section 6, section 2.2;
 * {@code THREAT_MODEL.md} T7, T33): what {@code infra/postgres/initdb/00-roles.sql} does for a laptop.
 *
 * <p><strong>Roles are infrastructure, never Flyway</strong>: a role is a cluster object, and {@code finapp_migrator} is
 * {@code NOCREATEROLE}. The per-table grants stay in the migrations. This creates each role and gives it its database rights,
 * nothing else, and it holds the only copy of what the master user may do to them here.
 *
 * <p><strong>A password never reaches the server in clear.</strong> {@code CREATE ROLE ... PASSWORD 'x'} is a statement, and
 * statements are logged, sampled and kept in {@code pg_stat_statements}. Each password is turned into its SCRAM verifier
 * <em>here</em>, in this process, which is what the server stores anyway.
 *
 * <p><strong>A role that exists is verified, never changed.</strong> The step can be re-run safely (a later task's role
 * joins the list) and it changes no password: rotation is a procedure of its own. A role found with a right it must not
 * hold stops the step rather than being repaired, because how it got that right is something a person has to look at.
 */
final class RoleBootstrap {

    /** Every role this step creates, its password's variable, and what it may do to the database as a whole. */
    record Role(String name, String passwordVariable, String databaseRights) {}

    static final List<Role> ROLES = List.of(
            new Role("finapp_migrator", "FINAPP_DB_MIGRATOR_PASSWORD", "CREATE, CONNECT"),
            new Role("finapp_app", "FINAPP_DB_APP_PASSWORD", "CONNECT"),
            new Role("finapp_reporting", "FINAPP_DB_REPORTING_PASSWORD", "CONNECT"));

    /** The attributes {@code 00-roles.sql} gives every role, and the two a managed master could otherwise hand down. */
    static final String ATTRIBUTES = "LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION";

    /**
     * What a {@code finapp_app} session may spend idle inside an open transaction: the value the server spells, the same one
     * {@code 00-roles.sql} sets, held between its bounds by {@code DatabaseRoleSettingsAreSafeTest}.
     *
     * <p>An idle transaction can hold an xid and so stop the source-join census's walk, which stops a person noticing anything until the
     * stall page an hour later. This ends it at the source: the server closes the session, the transaction rolls back whole, and the
     * pool discards the connection, so the caller fails and retries under its idempotency key. It reaches only a session that has
     * stopped sending statements while a transaction stands open, never a long statement and never an idle pool connection.
     */
    static final String APP_IDLE_IN_TRANSACTION_TIMEOUT = "5min";

    private static final String APP_ROLE_SETTING =
            "ALTER ROLE finapp_app SET idle_in_transaction_session_timeout = '" + APP_IDLE_IN_TRANSACTION_TIMEOUT + "'";

    private static final long LOCK_KEY = 0x66696e5f726f6cL; // "fin_rol"

    private static final String ROLE_ROW = "SELECT rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolinherit,"
            + " rolbypassrls, rolreplication, (SELECT count(*) FROM pg_catalog.pg_auth_members m WHERE m.member = r.oid)"
            + " FROM pg_catalog.pg_roles r WHERE r.rolname = ?";

    private RoleBootstrap() {}

    /** A role's standing as the catalogue reads it. */
    record Standing(boolean login, boolean superuser, boolean createDb, boolean createRole, boolean inherit,
            boolean bypassRls, boolean replication, long memberships) {

        /** What this role holds that it must not. Empty is correct. */
        List<String> violations() {
            List<String> found = new ArrayList<>();
            if (!login) {
                found.add("NOLOGIN");
            }
            if (superuser) {
                found.add("SUPERUSER");
            }
            if (createDb) {
                found.add("CREATEDB");
            }
            if (createRole) {
                found.add("CREATEROLE");
            }
            if (inherit) {
                found.add("INHERIT");
            }
            if (bypassRls) {
                found.add("BYPASSRLS");
            }
            if (replication) {
                found.add("REPLICATION");
            }
            if (memberships != 0) {
                found.add(memberships + " role membership(s)");
            }
            return found;
        }
    }

    /** The passwords this step will use, after refusing the ones it must not. */
    static Map<Role, String> passwords(Map<String, String> environment) {
        Map<Role, String> passwords = new java.util.LinkedHashMap<>();
        Set<String> distinct = new HashSet<>();
        for (Role role : ROLES) {
            String password = environment.get(role.passwordVariable());
            if (password == null || password.isBlank()) {
                throw new RefusedConfiguration(role.passwordVariable() + " is required");
            }
            if (MigratorEnvironment.MARKED_LOCAL_DEFAULT.equals(password)) {
                throw new RefusedConfiguration(role.passwordVariable() + " is the marked local credential");
            }
            if (!distinct.add(password)) {
                throw new RefusedConfiguration(role.passwordVariable()
                        + " repeats another role's password: one leak would be every role");
            }
            passwords.put(role, password);
        }
        return passwords;
    }

    /** The only way a role is written: the statement holds the verifier, so no path here can put the password in one. */
    static String createStatement(Role role, String password) {
        return "CREATE ROLE " + role.name() + " " + ATTRIBUTES + " PASSWORD '" + PasswordUtil.encodeScramSha256(password.toCharArray()) + "'";
    }

    /**
     * The whole step, in one transaction, serialised: two runs cannot both find a role missing. Advisory locks are per
     * database and roles are per cluster, so a run against another database of the cluster can still collide; that surfaces
     * as {@code 42710} and the caller runs it again, finding the role present.
     *
     * @return one line per role saying what happened
     */
    static List<String> apply(Connection master, Map<Role, String> passwords) throws SQLException {
        master.setAutoCommit(false);
        List<String> report = new ArrayList<>();
        try (Statement statement = master.createStatement()) {
            statement.execute("SELECT pg_advisory_xact_lock(" + LOCK_KEY + ")");
            for (Map.Entry<Role, String> entry : passwords.entrySet()) {
                Role role = entry.getKey();
                Optional<Standing> standing = read(master, role.name());
                if (standing.isPresent()) {
                    List<String> violations = standing.get().violations();
                    if (!violations.isEmpty()) {
                        throw new RefusedConfiguration("the role " + role.name() + " already exists and holds " + violations
                                + ": this step does not repair a role, a person has to look at how it got that");
                    }
                    report.add("role=" + role.name() + " present, unprivileged, unchanged");
                } else {
                    statement.execute(createStatement(role, entry.getValue()));
                    report.add("role=" + role.name() + " created");
                }
                grantOnThisDatabase(statement, "GRANT " + role.databaseRights() + " ON DATABASE %I TO " + role.name());
            }
            // Default-deny for everyone else, as the local script: PostgreSQL grants PUBLIC CONNECT on a new database and
            // CREATE on its public schema, which would let any role of the cluster connect and create objects beside ours.
            grantOnThisDatabase(statement, "REVOKE ALL ON DATABASE %I FROM PUBLIC");
            statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            // A setting, not a right: re-applied on every run, so a role that already exists has it from the next run of this step.
            statement.execute(APP_ROLE_SETTING);
            report.add("role=finapp_app idle_in_transaction_session_timeout=" + APP_IDLE_IN_TRANSACTION_TIMEOUT);
            master.commit();
            return report;
        } catch (SQLException | RuntimeException failure) {
            master.rollback();
            throw failure;
        }
    }

    private static Optional<Standing> read(Connection connection, String role) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(ROLE_ROW)) {
            query.setString(1, role);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Standing(row.getBoolean(1), row.getBoolean(2), row.getBoolean(3), row.getBoolean(4),
                        row.getBoolean(5), row.getBoolean(6), row.getBoolean(7), row.getLong(8)));
            }
        }
    }

    /** {@code GRANT} takes no parameter, so the database's name is quoted by the server's own {@code format()}. */
    private static void grantOnThisDatabase(Statement statement, String template) throws SQLException {
        String sql;
        try (ResultSet row = statement.executeQuery(
                "SELECT pg_catalog.format('" + template + "', pg_catalog.current_database())")) {
            row.next();
            sql = row.getString(1);
        }
        statement.execute(sql);
    }
}
