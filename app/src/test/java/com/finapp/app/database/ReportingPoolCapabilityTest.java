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
 * {@code pg_write_all_data}, while {@code finapp_reporting} itself opens - is {@code ReportingPoolCapabilityDatabaseTest}'s,
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
    @DisplayName("the check refuses through every chain: MEMBER of finapp_app (inheriting or only settable), and a write held by"
            + " any role the reporting role is a member of, itself and PUBLIC included")
    void theCheckResolvesMembershipThroughEveryChain() {
        String check = ReportingPool.CAPABILITY_CHECK;
        assertThat(check).contains("writer.rolname = 'finapp_app'")
                .contains("pg_catalog.pg_has_role(current_user, writer.oid, 'MEMBER')")
                .as("USAGE would miss a membership that only lets the role SET ROLE to the writer")
                .doesNotContain("'USAGE'");
        assertThat(check).contains("pg_catalog.pg_has_role(current_user, member.oid, 'MEMBER')")
                .contains("pg_catalog.has_table_privilege(mine.oid, judged.oid, act.privilege)")
                .as("a direct grant on one row of pg_auth_members is the defect a chain walks past")
                .doesNotContain("pg_auth_members");
    }

    @Test
    @DisplayName("the check judges every write - INSERT, UPDATE, DELETE, TRUNCATE - on every table, view and foreign table"
            + " outside the system schemas, and refuses to pass with no ledger table to judge")
    void theCheckJudgesEveryWriteAndCannotPassEmpty() {
        String check = ReportingPool.CAPABILITY_CHECK;
        assertThat(check).contains("ARRAY['INSERT', 'UPDATE', 'DELETE', 'TRUNCATE']")
                .contains("judged.relkind IN ('r', 'p', 'v', 'f')")
                .contains("home.nspname <> 'information_schema' AND home.nspname !~ '^pg_'")
                .as("the positive control: a catalogue with no ledger table refuses the start")
                .contains("WHERE home.nspname = 'ledger' AND judged.relkind IN ('r', 'p');")
                .contains("IF ledger_tables = 0 THEN");
        assertThat(check.split("RAISE EXCEPTION", -1)).as("three refusals: the writer's rights, nothing to judge, a write")
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
