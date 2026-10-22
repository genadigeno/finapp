package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
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
 * The bnpl schema's privilege floor, proven <strong>live</strong> (`P12-TSK-002`) - {@code LendingMigrationTest}'s
 * idiom, as every schema-owning module has its own.
 *
 * <p>The migration-test idiom ({@code ConsentMigrationTest}) reads the SQL and holds it to the code; this class holds
 * the <em>database</em> to it, because a floor is only a control as it actually stands in the catalogue - a grant added
 * by a later migration or by hand in an environment never edits the file this task shipped. The
 * {@code ApplicationRoleGrantsTest} asymmetry applies: a floor too narrow fails loudly at the first feature; a floor too
 * wide fails silently, forever - so the denials below are asserted at least as carefully as the one permission.
 *
 * <p>Why the floor matters before the first table: every table {@code PHASE_12_PLAN.md} section 8 commits to this schema
 * arrives with its own narrowed grants - INSERT-only money tables, a column-scoped UPDATE of status and permit columns on
 * the machine tables - and that is only reviewable per table because nothing arrives by default ({@code INV-BNPL-01}).
 */
@Tag("database")
@DisplayName("the bnpl schema floor is live (P12-TSK-002)")
class BnplMigrationTest {

    private static final String SCHEMA = "bnpl";

    /** PostgreSQL's SQLState for a refused privilege. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private static Connection migrator;
    private static Connection application;

    @BeforeAll
    static void connect() throws SQLException {
        migrator = DatabaseRoles.migrator();
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (migrator != null) {
            migrator.close();
        }
        if (application != null) {
            application.close();
        }
    }

    @Test
    @DisplayName("the ACL is exactly {finapp_migrator=UC, finapp_app=U} - no PUBLIC, no one else")
    void theAclIsExactlyTheFloor() throws SQLException {
        // Read from pg_namespace rather than via has_schema_privilege, because the acceptance
        // criterion is the WHOLE list: an extra grantee is invisible to a per-role probe, and an
        // extra grantee is precisely the silent failure a floor exists to prevent.
        assertThat(aclOf(SCHEMA))
                .containsExactlyInAnyOrder(
                        "finapp_migrator=UC/finapp_migrator", "finapp_app=U/finapp_migrator");
    }

    @Test
    @DisplayName("the schema is owned by the migrator, never the superuser")
    void theMigratorOwnsTheSchema() throws SQLException {
        // P0-TSK-022: the floor is only a control because the objects are owned by a role that
        // cannot bypass the grants it applies.
        assertThat(scalar("SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = '"
                        + SCHEMA + "'"))
                .isEqualTo("finapp_migrator");
    }

    @Test
    @DisplayName("no default privileges exist - every future table's grants must be explicit")
    void noDefaultPrivileges() throws SQLException {
        // The deliberate absence the migration records: a default UPDATE or DELETE here would make
        // every future money table mutable the moment it was created (INV-BNPL-01).
        assertThat(scalar("SELECT count(*)::text FROM pg_default_acl d"
                        + " JOIN pg_namespace n ON d.defaclnamespace = n.oid"
                        + " WHERE n.nspname = '" + SCHEMA + "'"))
                .isEqualTo("0");
        // The statements, not the commentary - the migration's comment NAMES the deliberate
        // absence, so the text check reads only what executes.
        assertThat(statementsOf(migration())).doesNotContain("ALTER DEFAULT PRIVILEGES");
    }

    @Test
    @DisplayName("the schema holds Flyway's history - which the application cannot read - and exactly the tables and"
            + " functions its migrations created: V001 the floor alone, V002 the terms versions (P12-TSK-004), V003 the application (P12-TSK-010), V004 the offer (P12-TSK-011), V006 the acceptance (P12-TSK-013), V007 the exposure view (P12-TSK-012), V008 the billing and the application (P12-TSK-015), V010 the closure (P12-TSK-018), V011 the collection attempt (P12-TSK-019), V012 the refund (P12-TSK-021), V013 the condition history and the late fee (P12-TSK-020), V014 the payoff quote and execution (P12-TSK-022), V015 the dispute, its claim, hold, proposal and history (P12-TSK-023), V016 the repayment reversal and the fee waiver (P12-TSK-024), V020 the reversal's fee release (the Phase 12 transition gate's R3)")
    void floorsOnlyAndTheHistoryIsConfined() throws SQLException {
        // Flyway keeps this schema's history HERE, which is the record of what was applied. An application role that
        // could read or write it could make the schema's provenance disagree with the schema. The first bnpl tables are
        // P12-TSK-004's (the terms version); each later task rewrites this list with its own.
        assertThat(tablesIn(SCHEMA)).containsExactlyInAnyOrder("bnpl_acceptance", "bnpl_agreement", "bnpl_application",
                "bnpl_application_event", "bnpl_billing", "bnpl_collection_attempt", "bnpl_condition_event", "bnpl_credit_balance_allocation",
                "bnpl_credit_balance_application", "bnpl_dispute", "bnpl_dispute_claim", "bnpl_dispute_event",
                "bnpl_dispute_hold", "bnpl_dispute_proposal", "bnpl_fee_assessment",
                "bnpl_instalment", "bnpl_interest_accrual", "bnpl_obligation", "bnpl_obligation_closure",
                "bnpl_obligation_event",
                "bnpl_offer", "bnpl_offer_event", "bnpl_offer_terms", "bnpl_payoff_execution", "bnpl_payoff_quote",
                "bnpl_refund", "bnpl_refund_adjustment",
                "bnpl_repayment", "bnpl_repayment_allocation", "bnpl_repayment_allocation_reversal",
                "bnpl_repayment_reversal", "bnpl_waiver", "bnpl_waiver_decision",
                // V020 (the Phase 12 transition gate's R3, A7-3): a reversal's release of the late fee it re-opened.
                "bnpl_reversal_fee_release",
                "bnpl_schedule", "bnpl_terms", "bnpl_terms_version", "bnpl_terms_version_event", "flyway_schema_history",
                "merchant_financing", "purchase", "purchase_event");
        assertThat(column("SELECT viewname FROM pg_views WHERE schemaname = '" + SCHEMA + "'"))
                .containsExactly("party_principal_exposure");
        assertThat(column("SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                + " WHERE n.nspname = '" + SCHEMA + "' ORDER BY p.proname")).containsExactlyInAnyOrder(
                "acceptance_history_is_append_only", "acceptance_rows_are_never_truncated",
                "bnpl_acceptance_is_born_once", "bnpl_agreement_is_born_once", "bnpl_billing_is_born_once",
                "bnpl_instalment_is_born_once", "bnpl_obligation_permits_only_machine_edges",
                "bnpl_repayment_allocation_is_born_once", "bnpl_repayment_is_allocated_whole",
                "bnpl_repayment_is_born_once", "bnpl_schedule_conserves", "bnpl_schedule_is_born_once", "business_date",
                "business_date_latest", "merchant_financing_is_born_once", "principal_value_floor",
                "purchase_is_financed_at_commit", "purchase_permits_only_machine_edges",
                "bnpl_application_event_is_append_only", "bnpl_application_is_never_truncated",
                "bnpl_application_never_rests_submitted", "bnpl_application_permits_only_machine_edges",
                "bnpl_offer_born_beside_its_application", "bnpl_offer_event_is_append_only",
                "bnpl_offer_is_never_truncated", "bnpl_offer_permits_only_machine_edges", "bnpl_offer_terms_is_born_once",
                "bnpl_terms_is_born_once", "bnpl_terms_is_never_truncated", "bnpl_terms_retires_only_beside_its_successor",
                "bnpl_terms_version_event_is_append_only", "bnpl_terms_version_has_its_terms",
                "bnpl_terms_version_permits_only_machine_edges", "holds_account_identifier_shape",
                "holds_card_number_shape", "holds_instrument_shape", "is_strictly_ascending", "luhn_valid",
                "mod97_check_holds",
                // V008 (P12-TSK-015), V010 (P12-TSK-018) and V011 (P12-TSK-019).
                "billing_component_billed", "billing_component_paid", "bnpl_credit_balance_allocation_is_born_once",
                "bnpl_credit_balance_application_conserves", "bnpl_credit_balance_application_is_born_once",
                "bnpl_interest_accrual_is_born_once", "bnpl_obligation_closure_closes_its_obligation",
                "bnpl_obligation_closure_is_born_once", "bnpl_repayment_allocation_conserves",
                "bnpl_auto_collection_has_its_attempt", "bnpl_collection_attempt_is_born_once",
                // V012 (P12-TSK-021).
                "bnpl_refund_adjustment_is_born_once", "bnpl_refund_conserves", "bnpl_refund_is_born_once",
                // V013 (P12-TSK-020).
                "bnpl_condition_event_is_born_once", "bnpl_fee_assessment_is_born_once", "days_past_due",
                "delinquency_bucket",
                // V014 (P12-TSK-022).
                "bnpl_payoff_execution_closes_its_obligation", "bnpl_payoff_execution_is_born_once",
                "bnpl_payoff_quote_is_born_once",
                // V016 (P12-TSK-024).
                "bnpl_repayment_allocation_reversal_is_born_once", "bnpl_repayment_reversal_is_whole_at_commit",
                "bnpl_repayment_reversal_permits_only_machine_edges", "bnpl_waiver_decision_is_born_once",
                "bnpl_waiver_is_born_once", "fee_waiver_room",
                // V017 (P12-TST-001): the reversal's decision ordered.
                "bnpl_repayment_reversal_stamps_its_decided_order",
                // V020 (the Phase 12 transition gate's R3): the release's born-once trigger and the reversal's commit-time
                // rank (A7-3); the reversal's edge function, billing_component_paid and fee_waiver_room replaced, listed above.
                "bnpl_reversal_fee_release_is_born_once", "bnpl_repayment_reversal_releases_refunded_fees",
                // V015 (P12-TSK-023) - days_past_due replaced, listed above.
                "bnpl_collection_attempt_respects_the_dispute_hold", "bnpl_dispute_claim_is_born_once",
                "bnpl_dispute_event_is_append_only", "bnpl_dispute_hold_is_born_once", "bnpl_dispute_is_whole",
                "bnpl_dispute_permits_only_machine_edges", "bnpl_dispute_proposal_permits_only_machine_edges",
                "bnpl_fee_assessment_respects_the_dispute_hold", "bnpl_refund_is_not_beside_an_open_dispute",
                "dispute_clock_start", "dispute_holds");

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("SELECT * FROM " + SCHEMA + ".flyway_schema_history"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
    }

    @Test
    @DisplayName("the exposure view is the read's columns alone: a definer view owned by the migrator, SELECT to the"
            + " application role and nothing else, never writable by it (P12-TSK-012)")
    void theExposureViewIsTheReadsAlone() throws SQLException {
        assertThat(column("SELECT column_name FROM information_schema.columns WHERE table_schema = '" + SCHEMA
                + "' AND table_name = 'party_principal_exposure' ORDER BY ordinal_position")).containsExactly(
                "obligation_id", "party_id", "decision_id", "product", "status", "currency");
        assertThat(scalar("SELECT pg_get_userbyid(c.relowner) FROM pg_class c JOIN pg_namespace n ON n.oid ="
                + " c.relnamespace WHERE n.nspname = '" + SCHEMA + "' AND c.relname = 'party_principal_exposure'"))
                .isEqualTo("finapp_migrator");
        assertThat(scalar("SELECT array_to_string(c.reloptions, ',') FROM pg_class c JOIN pg_namespace n ON n.oid ="
                + " c.relnamespace WHERE n.nspname = '" + SCHEMA + "' AND c.relname = 'party_principal_exposure'"))
                .isEqualTo("security_invoker=false");
        assertThat(column("SELECT grantee || ':' || privilege_type FROM information_schema.role_table_grants"
                + " WHERE table_schema = '" + SCHEMA + "' AND table_name = 'party_principal_exposure'"
                + " AND grantee <> 'finapp_migrator'")).containsExactly("finapp_app:SELECT");
        assertThatCode(() -> asApplication("SELECT count(*) FROM " + SCHEMA + ".party_principal_exposure"))
                .doesNotThrowAnyException();
        application.rollback();
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("DELETE FROM " + SCHEMA + ".party_principal_exposure"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
    }

    @Test
    @DisplayName("the application role can see the schema and create nothing in it")
    void usageWithoutCreate() throws SQLException {
        // The positive control first: without USAGE every denial below would pass for the wrong
        // reason, because the role could not name the schema at all.
        assertThatCode(() -> asApplication("SELECT to_regnamespace('" + SCHEMA + "')"))
                .doesNotThrowAnyException();
        application.rollback();

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("CREATE TABLE " + SCHEMA + ".probe (id INT)"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
    }

    // -----------------------------------------------------------------

    private static void asApplication(String sql) throws SQLException {
        try (Statement statement = application.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> aclOf(String schema) throws SQLException {
        try (Statement statement = migrator.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT unnest(nspacl)::text FROM pg_namespace WHERE nspname = '"
                                        + schema + "'")) {
            List<String> entries = new ArrayList<>();
            while (rows.next()) {
                entries.add(rows.getString(1));
            }
            return entries;
        }
    }

    private static List<String> tablesIn(String schema) throws SQLException {
        try (Statement statement = migrator.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT tablename FROM pg_tables WHERE schemaname = '" + schema
                                        + "' ORDER BY tablename")) {
            List<String> tables = new ArrayList<>();
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
            return tables;
        }
    }

    private static List<String> column(String sql) throws SQLException {
        try (Statement statement = migrator.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    private static String scalar(String sql) throws SQLException {
        try (Statement statement = migrator.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as("one row from: %s", sql).isTrue();
            return rows.getString(1);
        }
    }

    /** The migration with its comment lines removed - what actually executes. */
    private static String statementsOf(String migration) {
        return migration.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    }

    /** From the classpath, the sibling migration tests' idiom. */
    private static String migration() {
        String resource = "db/migration/bnpl/V001__initialise_bnpl_schema.sql";
        try (InputStream stream =
                BnplMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
