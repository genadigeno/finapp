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
 * <p><strong>A role that exists is verified; its rights and its password are never changed.</strong> The step can be re-run
 * safely (a later task's role joins the list) and it changes no password: rotation is a procedure of its own. A role found
 * with a right it must not hold stops the step rather than being repaired, because how it got that right is something a
 * person has to look at. <strong>A role's settings are the exception</strong>: applied on every run, so a release that changes
 * one reaches a role that already exists when the step is run for it ({@code DATA_MIGRATIONS.md} section 2.2).
 *
 * <h2>The alternating users (`P15-TSK-020`; {@code SECRET_MANAGEMENT.md} section 5.1, {@code database-fleet-credential})</h2>
 *
 * <p>A fleet's database credential rotates by alternating users: the store moves from one user to the other while both are
 * valid, the fleet rolls, and the old user is retired. Each of the two holds <strong>exactly</strong> the per-table grants the
 * migrations make to its carrier, {@code finapp_app} or {@code finapp_reporting}, because it holds them only <em>by
 * membership</em> of the carrier ({@code WITH INHERIT TRUE, SET FALSE}): a grant a later migration adds reaches both with
 * nothing to copy, and no grant names an alternate. An alternate has no database right of its own (its {@code CONNECT} is the
 * carrier's), no password when it is created (it cannot sign in until a rotation gives it one, which is that procedure's act,
 * never this step's), and the carrier's role settings, which a membership does not pass on. A member of a carrier that is not
 * one of its alternates, and can use the carrier's rights, stops the step: it would hold the application's every right.
 */
final class RoleBootstrap {

    /**
     * Every role this step creates: its password's variable and its rights over the database as a whole, or, for an alternating
     * user, none of either and the carrier it is a member of.
     */
    record Role(String name, String passwordVariable, String databaseRights, String carrier) {

        /** A user that alternates with another under {@link #carrier}'s grants, created without a password. */
        boolean alternate() {
            return carrier != null;
        }
    }

    static final List<Role> ROLES = List.of(
            new Role("finapp_migrator", "FINAPP_DB_MIGRATOR_PASSWORD", "CREATE, CONNECT", null),
            new Role("finapp_app", "FINAPP_DB_APP_PASSWORD", "CONNECT", null),
            new Role("finapp_reporting", "FINAPP_DB_REPORTING_PASSWORD", "CONNECT", null),
            new Role("finapp_app_a", null, null, "finapp_app"),
            new Role("finapp_app_b", null, null, "finapp_app"),
            new Role("finapp_reporting_a", null, null, "finapp_reporting"),
            new Role("finapp_reporting_b", null, null, "finapp_reporting"));

    /** The attributes {@code 00-roles.sql} gives every role, and the two a managed master could otherwise hand down. */
    static final String ATTRIBUTES = "LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION";

    /**
     * The one membership an alternating user holds: its carrier's rights inherited, the carrier's identity not assumable, and
     * nothing it can hand on. {@code NOINHERIT} on the role is only the default a membership's own option overrides.
     */
    static final String MEMBERSHIP_OPTIONS = "WITH INHERIT TRUE, SET FALSE";

    /**
     * What a {@code finapp_app} session may spend idle inside an open transaction: the value the server spells, the same one
     * {@code 00-roles.sql} sets, held between its bounds by {@code DatabaseRoleSettingsAreSafeTest}.
     *
     * <p>An idle transaction can hold an xid and so stop the source-join census's walk, which stops a person noticing anything until the
     * stall page an hour later. This ends it at the source: the server closes the session, the transaction rolls back whole, and the
     * pool discards the connection, so the caller fails and retries under its idempotency key. It reaches only a session that has
     * stopped sending statements while a transaction stands open, never a long statement and never an idle pool connection.
     * <strong>It is set on {@code finapp_app}'s alternating users too</strong>: a role setting belongs to the user a session signs
     * in as, and a membership does not carry it.
     */
    static final String APP_IDLE_IN_TRANSACTION_TIMEOUT = "5min";

    private static final String APP_ROLE_SETTING =
            "ALTER ROLE finapp_app SET idle_in_transaction_session_timeout = '" + APP_IDLE_IN_TRANSACTION_TIMEOUT + "'";

    private static final long LOCK_KEY = 0x66696e5f726f6cL; // "fin_rol"

    private static final String ROLE_ROW = "SELECT rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolinherit,"
            + " rolbypassrls, rolreplication, (SELECT count(*) FROM pg_catalog.pg_auth_members m WHERE m.member = r.oid)"
            + " FROM pg_catalog.pg_roles r WHERE r.rolname = ?";

    /** The memberships a user holds, as {@code carrier|inherit|set|admin}. */
    static final String MEMBERSHIPS_OF = "SELECT g.rolname, m.inherit_option, m.set_option, m.admin_option"
            + " FROM pg_catalog.pg_auth_members m JOIN pg_catalog.pg_roles g ON g.oid = m.roleid"
            + " JOIN pg_catalog.pg_roles u ON u.oid = m.member WHERE u.rolname = ? ORDER BY g.rolname";

    /**
     * Who can use a carrier's rights by membership. A member holding only {@code ADMIN} is a creator's bookkeeping (a managed
     * master that is not a superuser is given it on every role it creates) and uses none of them.
     */
    static final String USERS_OF = "SELECT u.rolname FROM pg_catalog.pg_auth_members m"
            + " JOIN pg_catalog.pg_roles g ON g.oid = m.roleid JOIN pg_catalog.pg_roles u ON u.oid = m.member"
            + " WHERE g.rolname = ? AND (m.inherit_option OR m.set_option) ORDER BY u.rolname";

    private RoleBootstrap() {}

    /** A role's standing as the catalogue reads it. */
    record Standing(boolean login, boolean superuser, boolean createDb, boolean createRole, boolean inherit,
            boolean bypassRls, boolean replication, long memberships) {

        /** What this role holds that it must not, for a role that is a member of nothing. Empty is correct. */
        List<String> violations() {
            return violations(0);
        }

        /** What this role holds that it must not, given how many memberships it may hold. Empty is correct. */
        List<String> violations(long allowedMemberships) {
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
            if (memberships != allowedMemberships) {
                found.add(memberships + " role membership(s)");
            }
            return found;
        }
    }

    /** One membership as the catalogue reads it. */
    record Membership(String carrier, boolean inherit, boolean set, boolean admin) {}

    /** The passwords this step will use, after refusing the ones it must not. An alternating user is given none. */
    static Map<Role, String> passwords(Map<String, String> environment) {
        Map<Role, String> passwords = new java.util.LinkedHashMap<>();
        Set<String> distinct = new HashSet<>();
        for (Role role : ROLES) {
            if (role.alternate()) {
                continue;
            }
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

    /** An alternating user: no password, so no way in until a rotation gives it one. */
    static String createAlternateStatement(Role role) {
        return "CREATE ROLE " + role.name() + " " + ATTRIBUTES;
    }

    /** The alternating user's one membership, made only when the step creates the user. */
    static String membershipStatement(Role role) {
        return "GRANT " + role.carrier() + " TO " + role.name() + " " + MEMBERSHIP_OPTIONS;
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
            for (Role role : ROLES) {
                Optional<Standing> standing = read(master, role.name());
                if (standing.isPresent()) {
                    List<String> violations = standing.get().violations(role.alternate() ? 1 : 0);
                    if (!violations.isEmpty()) {
                        throw new RefusedConfiguration("the role " + role.name() + " already exists and holds " + violations
                                + ": this step does not repair a role, a person has to look at how it got that");
                    }
                    report.add("role=" + role.name() + " present, unprivileged, unchanged");
                } else if (role.alternate()) {
                    statement.execute(createAlternateStatement(role));
                    statement.execute(membershipStatement(role));
                    report.add("role=" + role.name() + " created, a member of " + role.carrier() + " and holding nothing else,"
                            + " with no password until a rotation gives it one");
                } else {
                    String password = passwords.get(role);
                    if (password == null) {
                        throw new RefusedConfiguration(role.passwordVariable() + " is required");
                    }
                    statement.execute(createStatement(role, password));
                    report.add("role=" + role.name() + " created");
                }
                if (role.databaseRights() != null) {
                    grantOnThisDatabase(statement, "GRANT " + role.databaseRights() + " ON DATABASE %I TO " + role.name());
                }
            }
            verifyMemberships(master);
            // Default-deny for everyone else, as the local script: PostgreSQL grants PUBLIC CONNECT on a new database and
            // CREATE on its public schema, which would let any role of the cluster connect and create objects beside ours.
            grantOnThisDatabase(statement, "REVOKE ALL ON DATABASE %I FROM PUBLIC");
            statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            // A setting, not a right: re-applied on every run, so a role that already exists has it from the next run of this step.
            statement.execute(APP_ROLE_SETTING);
            report.add("role=finapp_app idle_in_transaction_session_timeout=" + APP_IDLE_IN_TRANSACTION_TIMEOUT);
            for (Role role : ROLES) {
                if ("finapp_app".equals(role.carrier())) {
                    statement.execute(appSettingOf(role));
                    report.add("role=" + role.name() + " idle_in_transaction_session_timeout=" + APP_IDLE_IN_TRANSACTION_TIMEOUT);
                }
            }
            master.commit();
            return report;
        } catch (SQLException | RuntimeException failure) {
            master.rollback();
            throw failure;
        }
    }

    /** {@code finapp_app}'s setting, for one of its alternating users. */
    static String appSettingOf(Role alternate) {
        return "ALTER ROLE " + alternate.name() + " SET idle_in_transaction_session_timeout = '" + APP_IDLE_IN_TRANSACTION_TIMEOUT + "'";
    }

    /**
     * Each alternating user holds exactly one membership, its carrier's, with exactly the options above; and nobody but a
     * carrier's own alternating users can use its rights by membership. Anything else stops the step before it commits.
     */
    static void verifyMemberships(Connection master) throws SQLException {
        Set<String> carriers = new java.util.TreeSet<>();
        for (Role role : ROLES) {
            if (!role.alternate()) {
                continue;
            }
            carriers.add(role.carrier());
            List<Membership> held = memberships(master, role.name());
            if (!held.equals(List.of(new Membership(role.carrier(), true, false, false)))) {
                throw new RefusedConfiguration("the alternating user " + role.name() + " holds " + held + " where it must hold"
                        + " exactly membership of " + role.carrier() + " " + MEMBERSHIP_OPTIONS
                        + ": its rights must be the carrier's grants and nothing else, so a person has to look at how it got that");
            }
        }
        for (String carrier : carriers) {
            Set<String> allowed = new HashSet<>();
            ROLES.stream().filter(role -> carrier.equals(role.carrier())).forEach(role -> allowed.add(role.name()));
            for (String user : usersOf(master, carrier)) {
                if (!allowed.contains(user)) {
                    throw new RefusedConfiguration("the role " + user + " can use " + carrier + "'s rights by membership and is not"
                            + " one of its alternating users: it holds the carrier's every grant, so a person has to look at how it got that");
                }
            }
        }
    }

    private static List<Membership> memberships(Connection connection, String user) throws SQLException {
        List<Membership> found = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(MEMBERSHIPS_OF)) {
            query.setString(1, user);
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    found.add(new Membership(row.getString(1), row.getBoolean(2), row.getBoolean(3), row.getBoolean(4)));
                }
            }
        }
        return found;
    }

    private static List<String> usersOf(Connection connection, String carrier) throws SQLException {
        List<String> found = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(USERS_OF)) {
            query.setString(1, carrier);
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    found.add(row.getString(1));
                }
            }
        }
        return found;
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
