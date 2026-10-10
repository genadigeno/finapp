package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * No lending table holds a mutable monetary value (P11-TSK-002; {@code INV-LND-01}, {@code INV-MON-01},
 * {@code PHASE_11_PLAN.md} sections 3.1 and 8, ADR-0090 point 2).
 *
 * <p><strong>The rule.</strong> Every money column is {@code *_minor bigint}, and every relation carrying one is
 * INSERT-only: owned by the migrator, and no other grantee - the application role, any other role, {@code PUBLIC} -
 * holds {@code UPDATE} (on the table or on any of its columns), {@code DELETE} or {@code TRUNCATE} on it. The machine
 * tables ({@code loan}, {@code loan_offer}, ...) take a column-scoped {@code UPDATE} of their status and permit
 * columns, and that is admitted precisely because they carry no money: a loan's figures are ledger balances and its
 * operational amounts born-once rows, so a figure the borrower is charged can never be a column someone updated. And no
 * column anywhere in the schema is binary floating point or PostgreSQL's {@code money} type.
 *
 * <p><strong>Judged against the catalogue, not the migrations.</strong> A grant added by a later migration, by a
 * default privilege or by hand in an environment never edits a file a text check would read; {@code pg_class.relacl}
 * and {@code pg_attribute.attacl} are what the database will enforce, so they are what is read. From the first lending
 * table on (`P11-TSK-004`), each one is held to the rule the moment its migration lands.
 *
 * <p><strong>The guard is proven able to fail, in-suite.</strong> {@code V001} creates no table, so over the live
 * schema the rule is vacuously true today; every planted case below creates its relation in the {@code lending} schema
 * itself inside a transaction that is rolled back, runs the very query the live check runs over that uncommitted
 * catalogue, and asserts it is refused - an {@code UPDATE} on the table, on the money column alone, on a status column
 * of a money table, a {@code DELETE}, a {@code TRUNCATE} to {@code PUBLIC}, a non-{@code bigint} money column, a
 * floating-point column, a money table owned by another role - while an INSERT-only fact table and a money-free machine
 * table with a column-scoped status {@code UPDATE} are admitted, so the guard does not merely refuse everything.
 */
@Tag("database")
@DisplayName("no lending table holds a mutable money column (P11-TSK-002, INV-LND-01)")
class LendingSchemaHasNoMutableMoneyTest {

    private static final String SCHEMA = "lending";

    /** The role every schema-owning module's objects belong to (P0-TSK-022). */
    private static final String OWNER = "finapp_migrator";

    private static Connection migrator;
    private static Connection bootstrap;

    @BeforeAll
    static void connect() throws SQLException {
        migrator = DatabaseRoles.migrator();
        migrator.setAutoCommit(false);
        bootstrap = DatabaseRoles.bootstrap();
        bootstrap.setAutoCommit(false);
    }

    @AfterAll
    static void disconnect() throws SQLException {
        for (Connection connection : new Connection[] {migrator, bootstrap}) {
            if (connection != null) {
                connection.rollback();
                connection.close();
            }
        }
    }

    @Test
    @DisplayName("the live lending schema holds no mutable, non-bigint or floating-point money column")
    void theLiveSchemaHoldsNoMutableMoney() throws SQLException {
        try {
            assertThat(violations(migrator))
                    .as("every *_minor column on an INSERT-only, migrator-owned relation; no floating point anywhere")
                    .isEmpty();
        } finally {
            migrator.rollback();
        }
    }

    @Test
    @DisplayName("a planted money table granting the application role UPDATE is refused")
    void aTableLevelUpdateIsRefused() throws SQLException {
        assertThat(plantedAsMigrator(
                        "CREATE TABLE lending.planted_mutable_money (id uuid, amount_minor bigint, currency char(3))",
                        "GRANT SELECT, INSERT, UPDATE ON lending.planted_mutable_money TO finapp_app"))
                .anySatisfy(v -> assertThat(v).contains("planted_mutable_money", "finapp_app", "UPDATE"));
    }

    @Test
    @DisplayName("an UPDATE granted on the *_minor column alone is refused")
    void aColumnUpdateOnTheMoneyColumnIsRefused() throws SQLException {
        assertThat(plantedAsMigrator(
                        "CREATE TABLE lending.planted_mutable_money (id uuid, amount_minor bigint, currency char(3))",
                        "GRANT SELECT, INSERT ON lending.planted_mutable_money TO finapp_app",
                        "GRANT UPDATE (amount_minor) ON lending.planted_mutable_money TO finapp_app"))
                .anySatisfy(v -> assertThat(v).contains("planted_mutable_money.amount_minor", "finapp_app", "UPDATE"));
    }

    @Test
    @DisplayName("an UPDATE of any column of a table carrying money is refused - the table is no longer INSERT-only")
    void anUpdateOfAStatusColumnOnAMoneyTableIsRefused() throws SQLException {
        assertThat(plantedAsMigrator(
                        "CREATE TABLE lending.planted_mutable_money (id uuid, status text, amount_minor bigint)",
                        "GRANT SELECT, INSERT ON lending.planted_mutable_money TO finapp_app",
                        "GRANT UPDATE (status) ON lending.planted_mutable_money TO finapp_app"))
                .anySatisfy(v -> assertThat(v).contains("planted_mutable_money.status", "finapp_app", "UPDATE"));
    }

    @Test
    @DisplayName("DELETE to the application role and TRUNCATE to PUBLIC are each refused")
    void deleteAndTruncateAreRefused() throws SQLException {
        List<String> violations = plantedAsMigrator(
                "CREATE TABLE lending.planted_mutable_money (id uuid, amount_minor bigint)",
                "GRANT SELECT, INSERT, DELETE ON lending.planted_mutable_money TO finapp_app",
                "GRANT TRUNCATE ON lending.planted_mutable_money TO PUBLIC");
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("finapp_app", "DELETE"));
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("PUBLIC", "TRUNCATE"));
    }

    @Test
    @DisplayName("a *_minor column that is not bigint, and a floating-point column anywhere, are refused")
    void nonBigintMoneyAndFloatingPointAreRefused() throws SQLException {
        List<String> violations = plantedAsMigrator(
                "CREATE TABLE lending.planted_money_types (id uuid, amount_minor numeric(20, 0))",
                "CREATE TABLE lending.planted_float (id uuid, rate double precision, weights real[], price money)",
                "GRANT SELECT, INSERT ON lending.planted_money_types, lending.planted_float TO finapp_app");
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("planted_money_types.amount_minor", "numeric"));
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("planted_float.rate", "double precision"));
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("planted_float.weights", "real[]"));
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("planted_float.price", "money"));
    }

    @Test
    @DisplayName("a money table owned by any role but the migrator is refused - an owner can always update")
    void aMoneyTableOwnedByAnotherRoleIsRefused() throws SQLException {
        // Planted through the bootstrap role because only it can hand an object to another owner - a
        // fixture making no privilege claim (DatabaseRoles#bootstrap); the claim is the guard's verdict.
        try {
            execute(bootstrap,
                    "CREATE TABLE lending.planted_mutable_money (id uuid, amount_minor bigint)",
                    "ALTER TABLE lending.planted_mutable_money OWNER TO finapp_app");
            assertThat(violations(bootstrap))
                    .anySatisfy(v -> assertThat(v).contains("planted_mutable_money", "owned by finapp_app"));
        } finally {
            bootstrap.rollback();
        }
    }

    @Test
    @DisplayName("an INSERT-only fact table and a money-free machine table updating its status are admitted")
    void theAdmittedShapesAreAdmitted() throws SQLException {
        // The non-vacuity half from the other side: a guard that refused every planted relation would
        // pass every case above and forbid the very tables PHASE_11_PLAN.md section 8 commits to. Only
        // the planted relations are judged here - a breach in the live schema is the first case's to
        // report, not this one's.
        assertThat(plantedAsMigrator(
                        "CREATE TABLE lending.planted_fact (id uuid, amount_minor bigint, currency char(3))",
                        "GRANT SELECT, INSERT ON lending.planted_fact TO finapp_app",
                        "CREATE TABLE lending.planted_machine (id uuid, status text, version bigint)",
                        "GRANT SELECT, INSERT ON lending.planted_machine TO finapp_app",
                        "GRANT UPDATE (status) ON lending.planted_machine TO finapp_app"))
                .filteredOn(v -> v.startsWith("planted_"))
                .isEmpty();
    }

    // -----------------------------------------------------------------

    /** Runs {@code ddl} as the schema's owner, judges the uncommitted catalogue, and rolls everything back. */
    private static List<String> plantedAsMigrator(String... ddl) throws SQLException {
        try {
            execute(migrator, ddl);
            return violations(migrator);
        } finally {
            migrator.rollback();
        }
    }

    private static void execute(Connection connection, String... statements) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /**
     * Every breach of the rule in the {@code lending} schema, as seen by {@code connection} - one line each, naming the
     * relation (and column), the grantee and the privilege, so a failure says what to fix.
     */
    static List<String> violations(Connection connection) throws SQLException {
        List<String> violations = new ArrayList<>();

        // 1. A money column is bigint minor units - never numeric, text or a float.
        collect(connection, violations, """
                SELECT c.relname || '.' || a.attname || ': a money column is bigint minor units, never '
                       || format_type(a.atttypid, a.atttypmod)
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                 WHERE n.nspname = ? AND c.relkind IN %s
                   AND a.attname LIKE '%%!_minor' ESCAPE '!'
                   AND a.atttypid <> 'bigint'::regtype
                """.formatted(RELATIONS));

        // 2. No binary floating point (or the money type) anywhere in the schema, arrays included.
        collect(connection, violations, """
                SELECT c.relname || '.' || a.attname || ': ' || format_type(a.atttypid, a.atttypmod)
                       || ' is binary floating point or the money type (INV-MON-01)'
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                  JOIN pg_type t ON t.oid = a.atttypid
                 WHERE n.nspname = ? AND c.relkind IN %s
                   AND COALESCE(NULLIF(t.typelem, 0), t.oid)
                       IN ('real'::regtype, 'double precision'::regtype, 'money'::regtype)
                """.formatted(RELATIONS));

        // 3. A relation carrying money is owned by the migrator - its owner can always update it.
        collect(connection, violations, """
                SELECT c.relname || ': carries money and is owned by ' || pg_get_userbyid(c.relowner)
                       || ', not %s - an owner can always update'
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = ? AND c.relkind IN %s AND %s
                   AND pg_get_userbyid(c.relowner) <> '%s'
                """.formatted(OWNER, RELATIONS, CARRIES_MONEY, OWNER));

        // 4. No other grantee - any role, or PUBLIC - holds UPDATE, DELETE or TRUNCATE on it...
        collect(connection, violations, """
                SELECT c.relname || ': carries money and ' || COALESCE(r.rolname, 'PUBLIC') || ' holds '
                       || acl.privilege_type || ' - not INSERT-only'
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 CROSS JOIN LATERAL aclexplode(c.relacl) acl
                  LEFT JOIN pg_roles r ON r.oid = acl.grantee
                 WHERE n.nspname = ? AND c.relkind IN %s AND %s
                   AND acl.grantee <> c.relowner
                   AND acl.privilege_type IN ('UPDATE', 'DELETE', 'TRUNCATE')
                """.formatted(RELATIONS, CARRIES_MONEY));

        // 5. ...nor UPDATE on any one of its columns - the money column or any other.
        collect(connection, violations, """
                SELECT c.relname || '.' || a.attname || ': carries money and ' || COALESCE(r.rolname, 'PUBLIC')
                       || ' holds column ' || acl.privilege_type || ' - not INSERT-only'
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                 CROSS JOIN LATERAL aclexplode(a.attacl) acl
                  LEFT JOIN pg_roles r ON r.oid = acl.grantee
                 WHERE n.nspname = ? AND c.relkind IN %s AND %s
                   AND acl.grantee <> c.relowner
                   AND acl.privilege_type = 'UPDATE'
                """.formatted(RELATIONS, CARRIES_MONEY));

        return violations;
    }

    /**
     * Relations a money column can sit on: tables, partitioned tables, views (an updatable view is a write path),
     * materialised views and foreign tables.
     */
    private static final String RELATIONS = "('r', 'p', 'v', 'm', 'f')";

    /** The relation {@code c} has a live column named {@code *_minor} - the schema's spelling of money. */
    private static final String CARRIES_MONEY = "EXISTS (SELECT 1 FROM pg_attribute m WHERE m.attrelid = c.oid"
            + " AND m.attnum > 0 AND NOT m.attisdropped AND m.attname LIKE '%!_minor' ESCAPE '!')";

    /** A single-statement query whose one bind is the schema. */
    private static void collect(Connection connection, List<String> into, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, SCHEMA);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    into.add(rows.getString(1));
                }
            }
        }
    }
}
