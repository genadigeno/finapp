package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.MoneyColumnFloor;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * No bnpl table holds a mutable monetary value (`P12-TSK-002`; {@code INV-BNPL-01}, {@code INV-MON-01},
 * {@code PHASE_12_PLAN.md} sections 3.1 and 8, ADR-0101 decision 2) - lending's guard re-applied to bnpl's schema, through
 * the one judgement both share ({@link MoneyColumnFloor}), never a copy of the rule.
 *
 * <p><strong>The rule.</strong> Every money column is {@code *_minor bigint}, and every relation carrying one is
 * INSERT-only: owned by the migrator, and no other grantee - the application role, any other role, {@code PUBLIC} -
 * holds {@code UPDATE} (on the table or on any of its columns), {@code DELETE} or {@code TRUNCATE} on it. The machine
 * tables ({@code bnpl_obligation}, {@code bnpl_offer}, {@code purchase}, ...) take a column-scoped {@code UPDATE} of their
 * status and permit columns, and that is admitted precisely because they carry no money: an obligation's figures are
 * ledger balances and its operational amounts born-once rows, so a figure a customer owes or a merchant is credited can
 * never be a column someone updated. And no column anywhere in the schema is binary floating point or PostgreSQL's
 * {@code money} type.
 *
 * <p><strong>Judged against the catalogue, not the migrations</strong> - a grant added later, by a default privilege or
 * by hand never edits a file a text check would read. From the first bnpl table on (`P12-TSK-004`), each one is held to
 * the rule the moment its migration lands.
 *
 * <p><strong>The guard is proven able to fail, in-suite.</strong> {@code V001} creates no table, so over the live schema
 * the rule is vacuously true today; every planted case below creates its relation in the {@code bnpl} schema itself
 * inside a transaction that is rolled back, runs the very query the live check runs over that uncommitted catalogue, and
 * asserts it is refused - an {@code UPDATE} on the table, on the money column alone, on a status column of a money table,
 * a {@code DELETE}, a {@code TRUNCATE} to {@code PUBLIC}, a non-{@code bigint} money column, a floating-point column, a
 * money table owned by another role - while an INSERT-only fact table and a money-free machine table with a column-scoped
 * status {@code UPDATE} are admitted, so the guard does not merely refuse everything.
 */
@Tag("database")
@DisplayName("no bnpl table holds a mutable money column (P12-TSK-002, INV-BNPL-01)")
class BnplSchemaHasNoMutableMoneyTest {

    private static final String SCHEMA = "bnpl";

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
    @DisplayName("the live bnpl schema holds no mutable, non-bigint or floating-point money column")
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
                        "CREATE TABLE bnpl.planted_mutable_money (id uuid, amount_minor bigint, currency char(3))",
                        "GRANT SELECT, INSERT, UPDATE ON bnpl.planted_mutable_money TO finapp_app"))
                .anySatisfy(v -> assertThat(v).contains("planted_mutable_money", "finapp_app", "UPDATE"));
    }

    @Test
    @DisplayName("an UPDATE granted on the *_minor column alone is refused")
    void aColumnUpdateOnTheMoneyColumnIsRefused() throws SQLException {
        assertThat(plantedAsMigrator(
                        "CREATE TABLE bnpl.planted_mutable_money (id uuid, amount_minor bigint, currency char(3))",
                        "GRANT SELECT, INSERT ON bnpl.planted_mutable_money TO finapp_app",
                        "GRANT UPDATE (amount_minor) ON bnpl.planted_mutable_money TO finapp_app"))
                .anySatisfy(v -> assertThat(v).contains("planted_mutable_money.amount_minor", "finapp_app", "UPDATE"));
    }

    @Test
    @DisplayName("an UPDATE of any column of a table carrying money is refused - the table is no longer INSERT-only")
    void anUpdateOfAStatusColumnOnAMoneyTableIsRefused() throws SQLException {
        assertThat(plantedAsMigrator(
                        "CREATE TABLE bnpl.planted_mutable_money (id uuid, status text, amount_minor bigint)",
                        "GRANT SELECT, INSERT ON bnpl.planted_mutable_money TO finapp_app",
                        "GRANT UPDATE (status) ON bnpl.planted_mutable_money TO finapp_app"))
                .anySatisfy(v -> assertThat(v).contains("planted_mutable_money.status", "finapp_app", "UPDATE"));
    }

    @Test
    @DisplayName("DELETE to the application role and TRUNCATE to PUBLIC are each refused")
    void deleteAndTruncateAreRefused() throws SQLException {
        List<String> violations = plantedAsMigrator(
                "CREATE TABLE bnpl.planted_mutable_money (id uuid, amount_minor bigint)",
                "GRANT SELECT, INSERT, DELETE ON bnpl.planted_mutable_money TO finapp_app",
                "GRANT TRUNCATE ON bnpl.planted_mutable_money TO PUBLIC");
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("finapp_app", "DELETE"));
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("PUBLIC", "TRUNCATE"));
    }

    @Test
    @DisplayName("a *_minor column that is not bigint, and a floating-point column anywhere, are refused")
    void nonBigintMoneyAndFloatingPointAreRefused() throws SQLException {
        List<String> violations = plantedAsMigrator(
                "CREATE TABLE bnpl.planted_money_types (id uuid, amount_minor numeric(20, 0))",
                "CREATE TABLE bnpl.planted_float (id uuid, rate double precision, weights real[], price money)",
                "GRANT SELECT, INSERT ON bnpl.planted_money_types, bnpl.planted_float TO finapp_app");
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
                    "CREATE TABLE bnpl.planted_mutable_money (id uuid, amount_minor bigint)",
                    "ALTER TABLE bnpl.planted_mutable_money OWNER TO finapp_app");
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
        // pass every case above and forbid the very tables PHASE_12_PLAN.md section 8 commits to. Only
        // the planted relations are judged here - a breach in the live schema is the first case's to
        // report, not this one's.
        assertThat(plantedAsMigrator(
                        "CREATE TABLE bnpl.planted_fact (id uuid, amount_minor bigint, currency char(3))",
                        "GRANT SELECT, INSERT ON bnpl.planted_fact TO finapp_app",
                        "CREATE TABLE bnpl.planted_machine (id uuid, status text, version bigint)",
                        "GRANT SELECT, INSERT ON bnpl.planted_machine TO finapp_app",
                        "GRANT UPDATE (status) ON bnpl.planted_machine TO finapp_app"))
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
     * Every breach of the rule in the {@code bnpl} schema, as seen by {@code connection} - the one judgement
     * ({@link MoneyColumnFloor}, lending's and bnpl's).
     */
    static List<String> violations(Connection connection) throws SQLException {
        return MoneyColumnFloor.violations(connection, SCHEMA);
    }
}
