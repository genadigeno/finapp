package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The reporting pool asks the database what its role can do, on every connection, before handing one out (`P15-TSK-043`;
 * {@code THREAT_MODEL.md} §6 item 36 (g), T44). {@code ReportingRoleIsSelectOnlyTest} is the static half: the grants the
 * migrations write. This is the runtime half's wiring: hermetic, because the pool is lazy and building one opens nothing.
 *
 * <p>What the statement refuses on a real server - a member of {@code finapp_app} through a chain, a membership that only
 * lets the role {@code SET ROLE} to it, a direct write on a ledger table, a {@code TRUNCATE} in another schema,
 * {@code pg_write_all_data}, a column grant (to the role, through a membership, to {@code PUBLIC}), {@code TRIGGER}, a sequence's
 * {@code UPDATE} and {@code USAGE}, a schema's {@code CREATE}, the server's program and file writers, while
 * {@code finapp_reporting} and a sequence-reading role open - is {@code ReportingPoolCapabilityDatabaseTest}'s,
 * owed to the exit database pass (C14). These cases hold that the pool runs it at all, and the clauses each of those relies
 * on, so a refactor that drops one is red here before anything reaches a database.
 */
@DisplayName("the reporting pool runs the capability check on every connection it makes (P15-TSK-043)")
class ReportingPoolCapabilityTest {

    @Test
    @DisplayName("every connection the pool makes runs the check first, whatever the role is called")
    void everyConnectionRunsTheCheck() {
        for (String role : new String[] {"finapp_reporting", "finapp_reporting_a", "anything_a_deployment_names"}) {
            try (ReportingPool pool = new ReportingPoolBeans().reportingPool(settings(role))) {
                assertThat(pool.connectionInitSql()).as(role).isEqualTo(ReportingPool.CAPABILITY_CHECK);
            }
        }
    }

    @Test
    @DisplayName("the check refuses through every chain: MEMBER of finapp_app (inheriting or only settable) or of the server's"
            + " program and file writers, and a write held by any role the reporting role is a member of, itself and PUBLIC included")
    void theCheckResolvesMembershipThroughEveryChain() {
        String check = ReportingPool.CAPABILITY_CHECK;
        assertThat(check).contains("writer.rolname IN ('finapp_app', 'pg_execute_server_program', 'pg_write_server_files')")
                .contains("pg_catalog.pg_has_role(current_user, writer.oid, 'MEMBER')")
                .as("USAGE would miss a membership that only lets the role SET ROLE to the writer")
                .doesNotContain("writer.oid, 'USAGE'").doesNotContain("member.oid, 'USAGE'");
        assertThat(check).contains("pg_catalog.pg_has_role(current_user, member.oid, 'MEMBER')")
                .as("a direct grant on one row of pg_auth_members is the defect a chain walks past")
                .doesNotContain("pg_auth_members");
    }

    @Test
    @DisplayName("a column grant is a write: INSERT and UPDATE are asked of any column (has_any_column_privilege), never of the"
            + " table's own privileges alone, which a column grant is not in (P15-TSK-043's review)")
    void aColumnGrantIsJudged() {
        String check = ReportingPool.CAPABILITY_CHECK;
        assertThat(check).contains("CROSS JOIN unnest(ARRAY['INSERT', 'UPDATE']) AS act(privilege)")
                .contains("pg_catalog.has_any_column_privilege(mine.oid, judged.oid, act.privilege)");
        assertThat(check.split("has_table_privilege", -1)).as("has_table_privilege asked once, of the three it is right for")
                .hasSize(2);
        assertThat(check).contains("CROSS JOIN unnest(ARRAY['DELETE', 'TRUNCATE', 'TRIGGER']) AS act(privilege)")
                .contains("pg_catalog.has_table_privilege(mine.oid, judged.oid, act.privilege)");
    }

    @Test
    @DisplayName("the check judges every write route - a column's or a table's INSERT and UPDATE, DELETE, TRUNCATE and TRIGGER on any"
            + " table, view or foreign table; UPDATE and USAGE on a sequence; CREATE on a schema - outside the system schemas, and"
            + " refuses to pass with no ledger table to judge")
    void theCheckJudgesEveryWriteAndCannotPassEmpty() {
        String check = ReportingPool.CAPABILITY_CHECK;
        assertThat(check).contains("judged.relkind IN ('r', 'p', 'v', 'f')")
                .contains("home.nspname <> 'information_schema' AND home.nspname !~ '^pg_'")
                .as("a sequence set or advanced, SELECT staying a read")
                .contains("WHERE judged.relkind = 'S'")
                .contains("CROSS JOIN unnest(ARRAY['UPDATE', 'USAGE']) AS act(privilege)")
                .contains("pg_catalog.has_sequence_privilege(mine.oid, judged.oid, act.privilege)")
                .as("a schema to create in")
                .contains("pg_catalog.has_schema_privilege(mine.oid, nonsystem.oid, 'CREATE')")
                .as("the positive control: a catalogue with no ledger table refuses the start")
                .contains("WHERE home.nspname = 'ledger' AND judged.relkind IN ('r', 'p');")
                .contains("IF ledger_tables = 0 THEN");
        assertThat(check.split("JOIN nonsystem ON nonsystem.oid = judged.relnamespace", -1))
                .as("every relation pass is held to the same schemas").hasSize(4);
        assertThat(check.split("RAISE EXCEPTION", -1)).as("three refusals: a writer's rights, nothing to judge, a write")
                .hasSize(4);
    }

    @Test
    @DisplayName("the statement is one DO block the driver passes whole: no JDBC escape, no placeholder, one dollar-quoted body")
    void theStatementIsOneBlock() {
        String check = ReportingPool.CAPABILITY_CHECK;
        assertThat(check).startsWith("DO $capability$").endsWith("$capability$")
                .doesNotContain("{").doesNotContain("}").doesNotContain("?");
        assertThat(check.split("\\$capability\\$", -1)).as("one body between the two tags").hasSize(3);
    }

    private static MockEnvironment settings(String reportingUser) {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/finapp")
                .withProperty("spring.datasource.username", "finapp_app")
                .withProperty(ReportingPool.USERNAME_PROPERTY, reportingUser)
                .withProperty(ReportingPool.LOGIN_PROPERTY, "supplied-by-the-test")
                .withProperty(ReportingPool.POOL_SIZE_PROPERTY, "1");
    }
}
