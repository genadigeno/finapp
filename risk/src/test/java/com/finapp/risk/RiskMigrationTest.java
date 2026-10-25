package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The risk schema's privilege floor and its first tables, proven <strong>live</strong> (`P13-TSK-001`; `P13-TSK-019`
 * adds {@code V004}: {@code monitored_transaction}, {@code monitoring_watermark}; `P13-TSK-006` adds {@code V005}:
 * {@code velocity_window}; `P13-TSK-007` adds {@code V006}: {@code customer_risk_profile},
 * {@code customer_risk_profile_version}; `P13-TSK-004` adds {@code V007}: {@code risk_assessment},
 * {@code risk_assessment_input}, {@code risk_rule_hit}, {@code risk_reason}, {@code decision_override}; `P13-TSK-012`
 * adds {@code V009}: the override's machine - no new table, its column-scoped {@code UPDATE}; `P13-TSK-015` adds
 * {@code V010}: {@code risk_alert}, its machine's trigger and its column-scoped {@code UPDATE}) - {@code BnplMigrationTest}'s
 * idiom, as every schema-owning module has its own.
 *
 * <p>The migration-test idiom reads the SQL and holds it to the code; this class holds the <em>database</em> to it,
 * because a floor is only a control as it actually stands in the catalogue - a grant added by a later migration or by
 * hand in an environment never edits the file this task shipped. The {@code ApplicationRoleGrantsTest} asymmetry
 * applies: a floor too narrow fails loudly at the first feature; a floor too wide fails silently, forever - so the
 * denials below are asserted at least as carefully as the one permission.
 *
 * <p>Each task rewrites the table, view and function lists with its own objects, keeping the others' (the wave's
 * rule): `-002` adds the policy tables, `-005` the signal table; this file names what `-001` and `-019` created.
 */
@Tag("database")
@DisplayName("the risk schema floor and the monitoring tables are live (P13-TSK-001, P13-TSK-019)")
class RiskMigrationTest {

    private static final String SCHEMA = "risk";

    /** PostgreSQL's SQLState for a refused privilege. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** PostgreSQL's SQLState for a trigger's or CHECK's refusal. */
    private static final String CHECK_VIOLATION = "23514";

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
        assertThat(aclOf(SCHEMA))
                .containsExactlyInAnyOrder("finapp_migrator=UC/finapp_migrator", "finapp_app=U/finapp_migrator");
    }

    @Test
    @DisplayName("the schema is owned by the migrator, never the superuser")
    void theMigratorOwnsTheSchema() throws SQLException {
        assertThat(scalar("SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = '" + SCHEMA + "'"))
                .isEqualTo("finapp_migrator");
    }

    @Test
    @DisplayName("no default privileges exist - every table's grants are explicit")
    void noDefaultPrivileges() throws SQLException {
        assertThat(scalar("SELECT count(*)::text FROM pg_default_acl d"
                        + " JOIN pg_namespace n ON d.defaclnamespace = n.oid"
                        + " WHERE n.nspname = '" + SCHEMA + "'"))
                .isEqualTo("0");
        assertThat(statementsOf(migration("V001__initialise_risk_schema.sql"))).doesNotContain("ALTER DEFAULT PRIVILEGES");
    }

    @Test
    @DisplayName("the schema holds Flyway's history - which the application cannot read - and exactly the tables and"
            + " functions its migrations created: V001 the floor alone, V002 the policy versions (P13-TSK-002), V003 the"
            + " signal (P13-TSK-005), V004 the monitored transactions and the watermark (P13-TSK-019), V005 the velocity"
            + " windows (P13-TSK-006), V006 the customer risk profile (P13-TSK-007), V007 the sealed assessment and the"
            + " override's table (P13-TSK-004), V010 the born-once alert (P13-TSK-015), V012 the case (P13-TSK-016),"
            + " V013 the downstream action (P13-TSK-017)")
    void floorsOnlyAndTheHistoryIsConfined() throws SQLException {
        // Flyway keeps this schema's history HERE, which is the record of what was applied. An application role that
        // could read or write it could make the schema's provenance disagree with the schema. Each later task rewrites
        // this list with its own tables, views and functions, keeping the others' (the wave's rule); the lists are in
        // the catalogue's order.
        assertThat(tablesIn(SCHEMA)).containsExactly(
                // V006 (P13-TSK-007): the profile's head and its versions.
                "customer_risk_profile",
                "customer_risk_profile_version",
                // V007 (P13-TSK-004): the override's table (its machine P13-TSK-012's).
                "decision_override",
                "flyway_schema_history",
                // V004 (P13-TSK-019): the born-once record and the intake's position per source.
                "monitored_transaction",
                "monitoring_watermark",
                // V013 (P13-TSK-017): the downstream action.
                "risk_action",
                // V010 (P13-TSK-015): the born-once alert.
                "risk_alert",
                // V007 (P13-TSK-004): the sealed assessment's head and its inputs.
                "risk_assessment",
                "risk_assessment_input",
                // V012 (P13-TSK-016): the case, its investigator actions and its history.
                "risk_case",
                "risk_case_action",
                "risk_case_event",
                // V002 (P13-TSK-002): the machine row, the content, the rules and their parameters, the bands, the history.
                "risk_policy",
                "risk_policy_band",
                "risk_policy_version",
                "risk_policy_version_event",
                // V007 (P13-TSK-004): the ordered reasons.
                "risk_reason",
                "risk_rule",
                // V007 (P13-TSK-004): every rule's hit.
                "risk_rule_hit",
                "risk_rule_parameter",
                // V003 (P13-TSK-005): the born-once signal.
                "risk_signal",
                // V005 (P13-TSK-006): the durable velocity windows.
                "velocity_window");
        assertThat(column("SELECT viewname FROM pg_views WHERE schemaname = '" + SCHEMA + "'")).isEmpty();
        assertThat(column("SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                + " WHERE n.nspname = '" + SCHEMA + "' ORDER BY p.proname")).containsExactly(
                // V006 (P13-TSK-007): nothing removed; the head moving with its versions; the versions append-only, in
                // sequence and PROHIBITED sticky against the sweep.
                "customer_risk_profile_is_never_removed",
                "customer_risk_profile_moves_with_its_versions",
                "customer_risk_profile_version_is_append_only",
                "customer_risk_profile_version_is_born_in_sequence",
                // V013 (P13-TSK-017): an approved dispatchable override has its risk_action (deferred).
                "decision_override_issues_its_action",
                // V007 (P13-TSK-004): the override born PROPOSED over a refusal - its body replaced by name by V009
                // (P13-TSK-012) with the machine, and again by V013 (P13-TSK-017) for the fold, the same function.
                "decision_override_permits_only_machine_edges",
                // V002 (P13-TSK-002): the reason screen's twin and the policy machine's triggers.
                "holds_account_identifier_shape",
                "holds_card_number_shape",
                "holds_instrument_shape",
                "luhn_valid",
                "mod97_check_holds",
                // V004 (P13-TSK-019): the monitored row's stamp and freeze, the watermark's derivation, the uuid prefix.
                "monitored_transaction_is_append_only",
                "monitored_transaction_is_stamped_by_the_database",
                "monitoring_watermark_derives_its_sequence",
                // V013 (P13-TSK-017): the action's machine.
                "risk_action_permits_only_machine_edges",
                // V010 (P13-TSK-015): the alert's machine - born OPEN, facts frozen, four edges, never deleted.
                "risk_alert_permits_only_machine_edges",
                // V007 (P13-TSK-004): born with its inputs, stamped by the database, sealed for every writer.
                "risk_assessment_has_its_inputs",
                "risk_assessment_is_born_stamped",
                "risk_assessment_is_sealed",
                // V012 (P13-TSK-016): the actions and the history append-only, every step its history row, the machine.
                "risk_case_action_is_append_only",
                // V013 (P13-TSK-017): a disposition waits for a standing action proposal.
                "risk_case_disposes_no_standing_action",
                "risk_case_event_is_append_only",
                "risk_case_has_its_history",
                "risk_case_permits_only_machine_edges",
                // V002 (P13-TSK-002), continued.
                "risk_policy_content_is_born_once",
                "risk_policy_is_never_truncated",
                "risk_policy_version_event_is_append_only",
                "risk_policy_version_has_its_content",
                "risk_policy_version_permits_only_machine_edges",
                "risk_policy_version_retires_only_beside_its_successor",
                // V003 (P13-TSK-005): the signal's stamp and its refusal of every edit.
                "risk_signal_is_born_once",
                "risk_signal_is_never_edited",
                // V004 (P13-TSK-019).
                "uuid7_sequence",
                // V005 (P13-TSK-006): forward only on the database clock, never removed, the window's length.
                "velocity_window_counts_forward",
                "velocity_window_is_never_removed",
                "velocity_window_length");

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("SELECT * FROM " + SCHEMA + ".flyway_schema_history"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
    }

    @Test
    @DisplayName("risk_signal (V003): the application holds SELECT and INSERT and nothing else; no role holds UPDATE, DELETE"
            + " or TRUNCATE, and the trigger refuses the migrator's edit too (P13-TSK-005, INV-RSK-06)")
    void theSignalTableIsInsertOnlyForEveryWriter() throws SQLException {
        // The whole ACL, as for the schema: an extra grantee or an extra privilege is the silent failure a floor exists
        // to prevent, invisible to a per-role probe.
        assertThat(column("SELECT grantee || '=' || string_agg(privilege_type, ',' ORDER BY privilege_type)"
                + " FROM information_schema.role_table_grants"
                + " WHERE table_schema = '" + SCHEMA + "' AND table_name = 'risk_signal'"
                + " GROUP BY grantee ORDER BY grantee"))
                .containsExactlyInAnyOrder(
                        "finapp_app=INSERT,SELECT",
                        // The owner's full set as PostgreSQL 18 (the catalogue's image) reports it - MAINTAIN since 17.
                        "finapp_migrator=DELETE,INSERT,MAINTAIN,REFERENCES,SELECT,TRIGGER,TRUNCATE,UPDATE");

        // The application role: refused by privilege before any trigger runs.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("UPDATE " + SCHEMA + ".risk_signal SET kind = kind"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("DELETE FROM " + SCHEMA + ".risk_signal"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();

        // The owner: holds the privilege and is refused by the trigger - a signal is evidence for EVERY writer. The
        // row is planted as the migrator, in a transaction rolled back after the probe.
        migrator.setAutoCommit(false);
        try (Statement plant = migrator.createStatement()) {
            plant.execute("INSERT INTO " + SCHEMA + ".risk_signal (id, kind, subject_kind, subject_ref,"
                    + " source_event_id, source_event_type, source_event_version, producer, observed_at,"
                    + " correlation_id, causation_id)"
                    + " VALUES (gen_random_uuid(), 'CREDENTIAL_CHANGED', 'IDENTITY', gen_random_uuid()::text,"
                    + " gen_random_uuid(), 'identity.CredentialChanged', 1, 'identity', now(), 'flow', 'cause')");
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> plant.execute("UPDATE " + SCHEMA + ".risk_signal SET producer = 'x'"))
                    .withMessageContaining("never updated or deleted");
            migrator.rollback();
            plant.execute("INSERT INTO " + SCHEMA + ".risk_signal (id, kind, subject_kind, subject_ref,"
                    + " source_event_id, source_event_type, source_event_version, producer, observed_at,"
                    + " correlation_id, causation_id)"
                    + " VALUES (gen_random_uuid(), 'CREDENTIAL_CHANGED', 'IDENTITY', gen_random_uuid()::text,"
                    + " gen_random_uuid(), 'identity.CredentialChanged', 1, 'identity', now(), 'flow', 'cause')");
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> plant.execute("DELETE FROM " + SCHEMA + ".risk_signal"))
                    .withMessageContaining("never updated or deleted");
            migrator.rollback();
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> plant.execute("TRUNCATE " + SCHEMA + ".risk_signal"))
                    .withMessageContaining("never updated or deleted");
            migrator.rollback();
        } finally {
            migrator.setAutoCommit(true);
        }
    }

    @Test
    @DisplayName("every constraint name in the schema is unique per table, and the CHECKs refuse a description as a device"
            + " and an attribute its kind does not carry (the V012 class; OQ-4)")
    void theConstraintsAreNamedOnceAndLive() throws SQLException {
        assertThat(column("SELECT conname FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace"
                + " WHERE n.nspname = '" + SCHEMA + "' GROUP BY conname HAVING count(*) > 1"))
                .as("a constraint name used twice on one table is refused by PostgreSQL at the SECOND migration that"
                        + " uses it (23505) - the Phase 12 V012 class")
                .isEmpty();

        String row = "INSERT INTO " + SCHEMA + ".risk_signal (id, kind, subject_kind, subject_ref, source_event_id,"
                + " source_event_type, source_event_version, producer, observed_at, correlation_id, causation_id,"
                + " product) VALUES (gen_random_uuid(), ?, ?, ?, gen_random_uuid(), ?, 1, 'x', now(), 'flow', 'cause', ?)";
        // A device description as a subject reference: refused by the shape CHECK, whatever the writer.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication(row, "ACCOUNT_OPENED", "DEVICE", "Chrome on Windows",
                        "accounts.AccountOpened", "WALLET"))
                .matches(e -> "23514".equals(e.getSQLState()));
        application.rollback();
        // An attribute a kind does not carry: CREDENTIAL_CHANGED carries none.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication(row, "CREDENTIAL_CHANGED", "IDENTITY",
                        java.util.UUID.randomUUID().toString(), "identity.CredentialChanged", "WALLET"))
                .matches(e -> "23514".equals(e.getSQLState()));
        application.rollback();
        // The positive control: the shape admits what the kind declares.
        assertThatCode(() -> asApplication(row, "ACCOUNT_OPENED", "ACCOUNT", java.util.UUID.randomUUID().toString(),
                "accounts.AccountOpened", "WALLET"))
                .doesNotThrowAnyException();
        application.rollback();
    }

    @Test
    @DisplayName("the application role can see the schema and create nothing in it")
    void usageWithoutCreate() throws SQLException {
        assertThatCode(() -> asApplication("SELECT to_regnamespace('" + SCHEMA + "')"))
                .doesNotThrowAnyException();
        application.rollback();

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("CREATE TABLE " + SCHEMA + ".probe (id INT)"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
    }

    // ------------------------------------------------------------------ V004 (P13-TSK-019)

    @Test
    @DisplayName("V004: the grants - the fact table SELECT and INSERT only; the watermark SELECT and an UPDATE of its"
            + " position and permit alone; no INSERT of a source, no DELETE anywhere")
    void theMonitoringGrants() throws SQLException {
        assertThat(grantsOn("monitored_transaction")).containsExactlyInAnyOrder("finapp_app:INSERT", "finapp_app:SELECT");
        assertThat(grantsOn("monitoring_watermark")).containsExactlyInAnyOrder("finapp_app:SELECT");
        assertThat(column("SELECT column_name FROM information_schema.column_privileges"
                + " WHERE table_schema = 'risk' AND table_name = 'monitoring_watermark' AND grantee = 'finapp_app'"
                + " AND privilege_type = 'UPDATE' ORDER BY column_name"))
                .containsExactly("last_entry_id", "next_sweep_at");
        // The statements, not the commentary.
        String statements = statementsOf(migration("V004__monitored_transactions_and_the_watermark.sql"));
        assertThat(statements).doesNotContain("DELETE ON").doesNotContain("GRANT ALL");
    }

    @Test
    @DisplayName("V004: the monetary shape is MoneyColumns.ColumnNames.ddl()'s verbatim, and the kind CHECK is exactly"
            + " MonitoredKind's members")
    void theShapesAreTheCodes() throws SQLException {
        String migration = migration("V004__monitored_transactions_and_the_watermark.sql");
        assertThat(migration).contains("amount_minor BIGINT NOT NULL, currency CHAR(3) NOT NULL, scale SMALLINT NOT NULL,"
                + " CHECK (currency ~ '^[A-Z]{3}$'), CHECK (scale BETWEEN 0 AND 9),");
        String kinds = scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                + " WHERE conname = 'monitored_transaction_kind_is_known'");
        for (MonitoredKind kind : MonitoredKind.values()) {
            assertThat(kinds).as(kind.name()).contains("'" + kind.name() + "'::text");
        }
        assertThat(kinds.split("::text").length - 1).as("no kind the enum does not declare")
                .isEqualTo(MonitoredKind.values().length);
        for (MonitoredEntry.EntryType type : MonitoredEntry.EntryType.values()) {
            assertThat(scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                    + " WHERE conname = 'monitored_transaction_entry_type_is_known'")).contains("'" + type.name() + "'");
        }
        for (MonitoredCounterpartyKind kind : MonitoredCounterpartyKind.values()) {
            assertThat(scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                    + " WHERE conname = 'monitored_transaction_counterparty_kind_is_known'"))
                    .contains("'" + kind.name() + "'");
        }
    }

    // ------------------------------------------------------------------ V011 (P13-TSK-014)

    @Test
    @DisplayName("V011: the signal's two reference columns and the merchant's index live; the abuse kinds admitted with"
            + " exactly their attributes and an older kind refused one; SESSION admitted on the windows; the assessment's"
            + " session_id; no grant changed, every constraint name unique")
    void thePaymentAbuseWidenings() throws SQLException {
        assertThat(column("SELECT column_name FROM information_schema.columns WHERE table_schema = 'risk'"
                + " AND table_name = 'risk_signal' AND column_name IN ('merchant_subject_ref', 'intent_ref') ORDER BY column_name"))
                .containsExactly("intent_ref", "merchant_subject_ref");
        assertThat(scalar("SELECT data_type FROM information_schema.columns WHERE table_schema = 'risk'"
                + " AND table_name = 'risk_assessment' AND column_name = 'session_id'")).isEqualTo("uuid");
        assertThat(column("SELECT indexname FROM pg_indexes WHERE schemaname = 'risk' AND tablename = 'risk_signal'"
                + " ORDER BY indexname")).contains("risk_signal_by_merchant", "risk_signal_by_subject");
        assertThat(grantsOn("risk_signal")).containsExactlyInAnyOrder("finapp_app:INSERT", "finapp_app:SELECT");

        String row = "INSERT INTO " + SCHEMA + ".risk_signal (id, kind, subject_kind, subject_ref, source_event_id,"
                + " source_event_type, source_event_version, producer, observed_at, correlation_id, causation_id,"
                + " merchant_subject_ref, intent_ref) VALUES (gen_random_uuid(), ?, 'PARTY', ?, gen_random_uuid(), ?, 1, 'x',"
                + " now(), 'flow', 'cause', ?::uuid, ?::uuid)";
        String party = UUID.randomUUID().toString();
        assertThatCode(() -> asApplication(row, "REFUND_COMPLETED", party, "payments.RefundCompleted",
                UUID.randomUUID().toString(), UUID.randomUUID().toString())).doesNotThrowAnyException();
        application.rollback();
        assertThatExceptionOfType(SQLException.class).as("a refund names its merchant")
                .isThrownBy(() -> asApplication(row, "CHARGEBACK_RECEIVED", party, "payments.ChargebackReceived",
                        null, UUID.randomUUID().toString()))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
        application.rollback();
        assertThatExceptionOfType(SQLException.class).as("an older kind never carries the new columns")
                .isThrownBy(() -> asApplication("INSERT INTO " + SCHEMA + ".risk_signal (id, kind, subject_kind,"
                        + " subject_ref, source_event_id, source_event_type, source_event_version, producer, observed_at,"
                        + " correlation_id, causation_id, merchant_subject_ref) VALUES (gen_random_uuid(), 'CREDENTIAL_CHANGED',"
                        + " 'IDENTITY', ?, gen_random_uuid(), 'identity.CredentialChanged', 1, 'x', now(), 'flow',"
                        + " 'cause', gen_random_uuid())", party))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
        application.rollback();
        assertThatCode(() -> asApplication("INSERT INTO " + SCHEMA + ".velocity_window (dimension, subject_ref,"
                + " window_kind, window_start, count, sum_minor, currency, scale) VALUES ('SESSION', ?, 'H1',"
                + " date_bin(interval '1 hour', clock_timestamp(), TIMESTAMPTZ '1970-01-05 00:00:00+00'), 1, 100, 'EUR', 2)",
                party)).as("SESSION is a dimension").doesNotThrowAnyException();
        application.rollback();
        assertThat(column("SELECT conname FROM pg_constraint WHERE connamespace = 'risk'::regnamespace"
                + " GROUP BY conname HAVING count(*) > 1")).as("the V012 class").isEmpty();
    }

    // ------------------------------------------------------------------ V013 (P13-TSK-017)

    @Test
    @DisplayName("V013: the action's grants - SELECT, INSERT and an UPDATE of the machine's columns alone (never the"
            + " proposal, the target or a stamp); no DELETE; the one-live partial unique and the four-eyes CHECK live;"
            + " the case's re-made CHECKs and the disposition's guard live; every constraint name unique")
    void theActionGrants() throws SQLException {
        assertThat(grantsOn("risk_action")).containsExactlyInAnyOrder("finapp_app:INSERT", "finapp_app:SELECT");
        assertThat(column("SELECT column_name FROM information_schema.column_privileges"
                + " WHERE table_schema = 'risk' AND table_name = 'risk_action' AND grantee = 'finapp_app'"
                + " AND privilege_type = 'UPDATE' ORDER BY column_name"))
                .containsExactly("attempts", "decided_by", "decided_by_type", "decision_action_id", "next_attempt_at",
                        "outcome", "status");
        String statements = statementsOf(migration("V013__downstream_risk_actions.sql"));
        assertThat(statements).doesNotContainPattern("GRANT[^;]*(DELETE|TRUNCATE)").doesNotContain("GRANT ALL");
        assertThat(scalar("SELECT indexdef FROM pg_indexes WHERE indexname = 'risk_action_one_live'"))
                .contains("UNIQUE INDEX").contains("(case_id, kind, target_ref)");
        assertThat(column("SELECT conname FROM pg_constraint WHERE connamespace = 'risk'::regnamespace"
                + " AND conname IN ('risk_action_four_eyes', 'risk_action_proposed_on_its_case',"
                + " 'risk_case_action_texted', 'risk_case_event_kind_is_known', 'risk_case_event_names_its_act')"
                + " ORDER BY conname")).containsExactly("risk_action_four_eyes", "risk_action_proposed_on_its_case",
                "risk_case_action_texted", "risk_case_event_kind_is_known", "risk_case_event_names_its_act");
        assertThat(scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                + " WHERE conname = 'risk_case_event_kind_is_known'")).contains("ACTION_APPROVED");
        assertThat(column("SELECT conname FROM pg_constraint WHERE connamespace = 'risk'::regnamespace"
                + " GROUP BY conname HAVING count(*) > 1")).as("the V012 class").isEmpty();
    }

    // ------------------------------------------------------------------ V012 (P13-TSK-016)

    @Test
    @DisplayName("V012: the case's grants - SELECT, INSERT and an UPDATE of the machine's columns alone on risk_case"
            + " (never a fact or a stamp), SELECT and INSERT alone on the actions and the history; no DELETE; the"
            + " one-open partial unique live; the two foreign keys the earlier tasks left; every constraint name unique")
    void theCaseGrants() throws SQLException {
        assertThat(grantsOn("risk_case")).containsExactlyInAnyOrder("finapp_app:INSERT", "finapp_app:SELECT");
        assertThat(grantsOn("risk_case_action")).containsExactlyInAnyOrder("finapp_app:INSERT", "finapp_app:SELECT");
        assertThat(grantsOn("risk_case_event")).containsExactlyInAnyOrder("finapp_app:INSERT", "finapp_app:SELECT");
        assertThat(column("SELECT column_name FROM information_schema.column_privileges"
                + " WHERE table_schema = 'risk' AND table_name = 'risk_case' AND grantee = 'finapp_app'"
                + " AND privilege_type = 'UPDATE' ORDER BY column_name"))
                .containsExactly("disposition", "disposition_approved_by", "disposition_approved_by_type",
                        "disposition_id", "disposition_prohibits", "disposition_proposed_by",
                        "disposition_proposed_by_type", "level", "queue", "revision", "status", "taken_by",
                        "taken_by_type");
        String statements = statementsOf(migration("V012__the_case.sql"));
        assertThat(statements).doesNotContainPattern("GRANT[^;]*(DELETE|TRUNCATE)").doesNotContain("GRANT ALL");
        assertThat(scalar("SELECT indexdef FROM pg_indexes WHERE indexname = 'risk_case_one_open'"))
                .contains("UNIQUE INDEX").contains("(subject_ref, kind)").contains("WHERE (status <> 'DISPOSED'::text)");
        assertThat(column("SELECT conname FROM pg_constraint WHERE connamespace = 'risk'::regnamespace"
                + " AND conname IN ('risk_alert_attached_case_fk', 'customer_risk_profile_version_disposition_fk',"
                + " 'risk_case_disposition_four_eyes', 'risk_case_disposition_fk') ORDER BY conname"))
                .containsExactly("customer_risk_profile_version_disposition_fk", "risk_alert_attached_case_fk",
                        "risk_case_disposition_fk", "risk_case_disposition_four_eyes");
        assertThat(column("SELECT conname FROM pg_constraint WHERE connamespace = 'risk'::regnamespace"
                + " GROUP BY conname HAVING count(*) > 1")).as("the V012 class").isEmpty();
    }

    // ------------------------------------------------------------------ V010 (P13-TSK-015)

    @Test
    @DisplayName("V010: the alert's grants - SELECT, INSERT and an UPDATE of the status, the attachment and the"
            + " suppression columns alone (never a fact or a stamp); no DELETE; the arbiters live and every constraint"
            + " name unique in the schema")
    void theAlertGrants() throws SQLException {
        assertThat(grantsOn("risk_alert")).containsExactlyInAnyOrder("finapp_app:INSERT", "finapp_app:SELECT");
        assertThat(column("SELECT column_name FROM information_schema.column_privileges"
                + " WHERE table_schema = 'risk' AND table_name = 'risk_alert' AND grantee = 'finapp_app'"
                + " AND privilege_type = 'UPDATE' ORDER BY column_name"))
                .containsExactly("attached_case_id", "status", "suppression_decided_by", "suppression_decided_by_type",
                        "suppression_decision_reason", "suppression_proposed_by", "suppression_proposed_by_type",
                        "suppression_reason", "suppression_reason_code");
        String statements = statementsOf(migration("V010__the_born_once_alert.sql"));
        // The trigger names DELETE and TRUNCATE to refuse them; no GRANT names either.
        assertThat(statements).doesNotContainPattern("GRANT[^;]*(DELETE|TRUNCATE)").doesNotContain("GRANT ALL");
        assertThat(column("SELECT conname FROM pg_constraint WHERE connamespace = 'risk'::regnamespace"
                + " AND conname LIKE 'risk_alert_%' ORDER BY conname")).contains("risk_alert_born_once",
                "risk_alert_pk", "risk_alert_policy_version_fk", "risk_alert_suppression_four_eyes",
                "risk_alert_suppression_by_persons", "risk_alert_window_start_is_aligned",
                "risk_alert_operational_never_attached", "risk_alert_evidence_bounded");
        assertThat(scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'risk_alert_born_once'"))
                .isEqualTo("UNIQUE (subject_ref, rule_code, window_start)");
        assertThat(column("SELECT indexname FROM pg_indexes WHERE schemaname = 'risk' AND tablename = 'risk_alert'"
                + " ORDER BY indexname")).containsExactly("risk_alert_born_once", "risk_alert_open_queue",
                "risk_alert_pk");
        assertThat(column("SELECT conname FROM pg_constraint WHERE connamespace = 'risk'::regnamespace"
                + " GROUP BY conname HAVING count(*) > 1")).as("the V012 class").isEmpty();
    }

    // ------------------------------------------------------------------ V009 (P13-TSK-012)

    @Test
    @DisplayName("V009, folded by V013: the override's grants - SELECT, INSERT and an UPDATE of the decision and the"
            + " action's kind alone (never the proposal, the effect or the instants; the moved columns gone); no DELETE;"
            + " the constraints that stand live and every constraint name unique in the schema")
    void theOverrideGrants() throws SQLException {
        assertThat(grantsOn("decision_override")).containsExactlyInAnyOrder("finapp_app:INSERT", "finapp_app:SELECT");
        assertThat(column("SELECT column_name FROM information_schema.column_privileges"
                + " WHERE table_schema = 'risk' AND table_name = 'decision_override' AND grantee = 'finapp_app'"
                + " AND privilege_type = 'UPDATE' ORDER BY column_name"))
                .containsExactly("action", "decided_by", "decided_by_type", "decision_reason", "status");
        assertThat(column("SELECT column_name FROM information_schema.columns WHERE table_schema = 'risk'"
                + " AND table_name = 'decision_override' AND column_name LIKE 'action%' ORDER BY column_name"))
                .as("V013 moved the issued action's state to risk_action").containsExactly("action");
        String statements = statementsOf(migration("V009__the_decision_override_machine.sql"));
        assertThat(statements).doesNotContain("DELETE ON").doesNotContain("GRANT ALL").doesNotContain("CREATE TABLE");
        assertThat(column("SELECT conname FROM pg_constraint WHERE connamespace = 'risk'::regnamespace"
                + " AND conname LIKE 'decision_override_%' ORDER BY conname")).contains(
                "decision_override_action_is_the_effect", "decision_override_action_shape",
                "decision_override_effect_is_known", "decision_override_four_eyes",
                "decision_override_target_coherent").doesNotContain("decision_override_attempts_bounded",
                "decision_override_conclusion_coherent", "decision_override_issued_awaits_or_concludes",
                "decision_override_outcome_is_known", "decision_override_permit_coherent");
        assertThat(column("SELECT indexname FROM pg_indexes WHERE schemaname = 'risk'"
                + " AND tablename = 'decision_override' ORDER BY indexname")).containsExactly(
                "decision_override_one_live", "decision_override_one_standing", "decision_override_pk");
        assertThat(column("SELECT conname FROM pg_constraint WHERE connamespace = 'risk'::regnamespace"
                + " GROUP BY conname HAVING count(*) > 1")).as("the V012 class").isEmpty();
    }

    // ------------------------------------------------------------------ V006 (P13-TSK-007)

    @Test
    @DisplayName("V006: the grants - the head SELECT, INSERT and an UPDATE of its version, rating and permit alone; the"
            + " versions SELECT and INSERT only; no DELETE anywhere - and every constraint name unique in the schema")
    void theProfileGrants() throws SQLException {
        assertThat(grantsOn("customer_risk_profile"))
                .containsExactlyInAnyOrder("finapp_app:INSERT", "finapp_app:SELECT");
        assertThat(column("SELECT column_name FROM information_schema.column_privileges"
                + " WHERE table_schema = 'risk' AND table_name = 'customer_risk_profile' AND grantee = 'finapp_app'"
                + " AND privilege_type = 'UPDATE' ORDER BY column_name"))
                .containsExactly("current_version", "next_recompute_at", "rating", "recomputed_at");
        assertThat(grantsOn("customer_risk_profile_version"))
                .containsExactlyInAnyOrder("finapp_app:INSERT", "finapp_app:SELECT");
        String statements = statementsOf(migration("V006__the_customer_risk_profile.sql"));
        assertThat(statements).doesNotContain("DELETE ON").doesNotContain("GRANT ALL");
        assertThat(column("SELECT conname FROM pg_constraint WHERE connamespace = 'risk'::regnamespace"
                + " GROUP BY conname HAVING count(*) > 1")).as("the V012 class").isEmpty();
        assertThat(scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                + " WHERE conname = 'customer_risk_profile_version_rating_is_known'"))
                .contains("'LOW'", "'MEDIUM'", "'HIGH'", "'PROHIBITED'");
        assertThat(scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                + " WHERE conname = 'customer_risk_profile_version_cause_is_known'"))
                .contains("'" + ProfileCause.RECOMPUTE.name() + "'", "'" + ProfileCause.DISPOSITION.name() + "'");
    }

    @Test
    @DisplayName("V007: the five tables SELECT and INSERT for the application and nothing else - no column UPDATE, no"
            + " DELETE - and every constraint name unique in the schema")
    void theAssessmentGrants() throws SQLException {
        // decision_override is V007's too, its column-scoped UPDATE V009's (P13-TSK-012) - theOverrideGrants below.
        for (String table : List.of("risk_assessment", "risk_assessment_input", "risk_rule_hit", "risk_reason")) {
            assertThat(grantsOn(table)).as(table).containsExactlyInAnyOrder("finapp_app:INSERT", "finapp_app:SELECT");
            assertThat(column("SELECT column_name FROM information_schema.column_privileges"
                    + " WHERE table_schema = 'risk' AND table_name = '" + table + "' AND grantee = 'finapp_app'"
                    + " AND privilege_type = 'UPDATE'")).as(table).isEmpty();
        }
        String statements = statementsOf(migration("V007__the_sealed_assessment.sql"));
        assertThat(statements).doesNotContain("DELETE ON").doesNotContain("GRANT ALL").doesNotContain("GRANT UPDATE");
        assertThat(column("SELECT conname FROM pg_constraint WHERE connamespace = 'risk'::regnamespace"
                + " GROUP BY conname HAVING count(*) > 1")).as("the V012 class").isEmpty();
    }

    @Test
    @DisplayName("V004: every constraint name is unique in the schema (the Phase 12 V012 class)")
    void constraintNamesAreUnique() throws SQLException {
        assertThat(column("SELECT conname FROM pg_constraint WHERE connamespace = 'risk'::regnamespace"
                + " GROUP BY conname HAVING count(*) > 1")).isEmpty();
    }

    @Test
    @DisplayName("V004: the one watermark row is seeded for the ledger journal with its sequence derived by the database"
            + " from its position; the application cannot write the sequence, insert a source or delete the row")
    void theWatermarkIsSeededAndDerived() throws SQLException {
        // The one row, whatever position the intake suites have moved it to in this database - and its sequence
        // always the database's derivation of that position (the seed is the floor, sequence 0).
        assertThat(column("SELECT source FROM risk.monitoring_watermark")).containsExactly("ledger.journal");
        assertThat(scalar("SELECT (last_sequence = risk.uuid7_sequence(last_entry_id))::text"
                + " FROM risk.monitoring_watermark WHERE source = 'ledger.journal'")).isEqualTo("true");
        assertThat(statementsOf(migration("V004__monitored_transactions_and_the_watermark.sql")))
                .contains("VALUES ('ledger.journal', '00000000-0000-7000-8000-000000000000', 0, clock_timestamp(),"
                        + " clock_timestamp())");
        assertThat(scalar("SELECT risk.uuid7_sequence('0199a3c1-2b3c-7d4e-8f00-000000000000'::uuid)::text"))
                .isEqualTo(Long.toString(0x0199a3c12b3cL));
        assertThat(scalar("SELECT risk.uuid7_sequence('00000000-0000-7000-8000-000000000000'::uuid)::text"))
                .isEqualTo("0");

        // The move the sweep makes: the position, the permit - and the sequence follows from the id by trigger.
        UUID position = MonitoringWatermark.positionAt(1_700_000_000_000L);
        try (PreparedStatement move = application.prepareStatement("UPDATE risk.monitoring_watermark"
                + " SET last_entry_id = ?, next_sweep_at = clock_timestamp() WHERE source = 'ledger.journal'")) {
            move.setObject(1, position);
            assertThat(move.executeUpdate()).isEqualTo(1);
        }
        try (PreparedStatement read = application.prepareStatement(
                "SELECT last_sequence FROM risk.monitoring_watermark WHERE source = 'ledger.journal'");
                ResultSet row = read.executeQuery()) {
            assertThat(row.next()).isTrue();
            assertThat(row.getLong(1)).isEqualTo(1_700_000_000_000L);
        }
        application.rollback();

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("UPDATE risk.monitoring_watermark SET last_sequence = 5"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("INSERT INTO risk.monitoring_watermark"
                        + " (source, last_entry_id, last_sequence, next_sweep_at, advanced_at)"
                        + " VALUES ('ledger.journal', '00000000-0000-7000-8000-000000000000', 0, now(), now())"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("DELETE FROM risk.monitoring_watermark"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
    }

    @Test
    @DisplayName("V004: a monitored transaction is born once - a second row for the entry is refused - and never edited,"
            + " by privilege for the application and by trigger for the migrator")
    void theMonitoredTransactionIsBornOnceAndAppendOnly() throws SQLException {
        UUID entry = UUID.randomUUID();
        insertAsApplication(UUID.fromString("0199a3c1-2b3c-7d4e-8f00-000000000001"), entry);
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insertAsApplication(UUID.fromString("0199a3c1-2b3c-7d4e-8f00-000000000002"), entry))
                .matches(e -> "23505".equals(e.getSQLState()));
        application.rollback();

        insertAsApplication(UUID.fromString("0199a3c1-2b3c-7d4e-8f00-000000000003"), entry);
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("UPDATE risk.monitored_transaction SET kind = 'TRANSFER'"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("DELETE FROM risk.monitored_transaction"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();

        // The migrator holds the privilege and meets the trigger instead.
        migrator.setAutoCommit(false);
        try {
            insertAs(migrator, UUID.fromString("0199a3c1-2b3c-7d4e-8f00-000000000004"), UUID.randomUUID());
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> {
                        try (Statement statement = migrator.createStatement()) {
                            statement.execute("UPDATE risk.monitored_transaction SET kind = 'TRANSFER'");
                        }
                    })
                    .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
            migrator.rollback();
            insertAs(migrator, UUID.fromString("0199a3c1-2b3c-7d4e-8f00-000000000005"), UUID.randomUUID());
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> {
                        try (Statement statement = migrator.createStatement()) {
                            statement.execute("DELETE FROM risk.monitored_transaction");
                        }
                    })
                    .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
            migrator.rollback();
        } finally {
            migrator.setAutoCommit(true);
        }
    }

    @Test
    @DisplayName("V004: recorded_at is the database clock's whatever the insert said, and the CHECKs refuse a subject"
            + " without its reference, a direction without a subject and a reversal without its original")
    void theRowIsStampedAndWhole() throws SQLException {
        UUID id = UUID.fromString("0199a3c1-2b3c-7d4e-8f00-000000000006");
        insertAsApplication(id, UUID.randomUUID());
        try (PreparedStatement read = application.prepareStatement(
                "SELECT recorded_at > '2000-01-01'::timestamptz FROM risk.monitored_transaction WHERE id = ?")) {
            read.setObject(1, id);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getBoolean(1)).as("the insert said the year 2000; the trigger said now").isTrue();
            }
        }
        application.rollback();

        for (String broken : List.of(
                "subject_kind = 'ACCOUNT'",
                "direction = 'IN'",
                "entry_type = 'REVERSAL'",
                "kind = 'SOMETHING_ELSE'",
                "amount_minor = -1")) {
            assertThatExceptionOfType(SQLException.class)
                    .as(broken)
                    .isThrownBy(() -> insertBroken(broken))
                    .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
            application.rollback();
        }
    }

    // ------------------------------------------------------------------ V005 (P13-TSK-006)

    @Test
    @DisplayName("V005: the velocity window's grants - SELECT, INSERT and UPDATE of count and sum_minor alone, the"
            + " upsert's columns; no DELETE or TRUNCATE for any grantee; the trigger refuses the owner's DELETE too")
    void theVelocityWindowGrants() throws SQLException {
        assertThat(grantsOn("velocity_window")).containsExactlyInAnyOrder("finapp_app:INSERT", "finapp_app:SELECT");
        assertThat(column("SELECT column_name FROM information_schema.column_privileges"
                + " WHERE table_schema = 'risk' AND table_name = 'velocity_window' AND grantee = 'finapp_app'"
                + " AND privilege_type = 'UPDATE' ORDER BY column_name"))
                .containsExactly("count", "sum_minor");
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("DELETE FROM " + SCHEMA + ".velocity_window"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
        migrator.setAutoCommit(false);
        try (Statement owner = migrator.createStatement()) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> owner.execute("TRUNCATE " + SCHEMA + ".velocity_window"))
                    .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
        } finally {
            migrator.rollback();
            migrator.setAutoCommit(true);
        }
        String statements = statementsOf(migration("V005__velocity_windows.sql"));
        assertThat(statements).doesNotContain("DELETE ON").doesNotContain("GRANT ALL").doesNotContain("TRUNCATE ON risk");
    }

    // -----------------------------------------------------------------

    private static void insertAsApplication(UUID id, UUID entry) throws SQLException {
        insertAs(application, id, entry);
    }

    private static void insertAs(Connection as, UUID id, UUID entry) throws SQLException {
        try (PreparedStatement insert = as.prepareStatement(
                "INSERT INTO risk.monitored_transaction (id, journal_entry_id, journal_sequence, entry_type,"
                        + " posting_namespace, kind, counterparty_kind, amount_minor, currency, scale, value_date,"
                        + " posted_at, recorded_at)"
                        + " VALUES (?, ?, 1, 'POSTING', 'transfer:', 'TRANSFER', 'OPERATIONAL', 100, 'EUR', 2,"
                        + " '2026-10-23', '2026-10-23T09:00:00Z', '2000-01-01T00:00:00Z')")) {
            insert.setObject(1, id);
            insert.setObject(2, entry);
            insert.executeUpdate();
        }
    }

    /** A whole, valid row with exactly one column set to {@code broken}'s value ({@code column = value}). */
    private static void insertBroken(String broken) throws SQLException {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("id", "'0199a3c1-2b3c-7d4e-8f00-000000000008'");
        values.put("journal_entry_id", "'" + UUID.randomUUID() + "'");
        values.put("journal_sequence", "1");
        values.put("entry_type", "'POSTING'");
        values.put("posting_namespace", "'transfer:'");
        values.put("kind", "'TRANSFER'");
        values.put("subject_kind", "NULL");
        values.put("subject_ref", "NULL");
        values.put("direction", "NULL");
        values.put("counterparty_kind", "'OPERATIONAL'");
        values.put("amount_minor", "100");
        values.put("currency", "'EUR'");
        values.put("scale", "2");
        values.put("value_date", "'2026-10-23'");
        values.put("posted_at", "'2026-10-23T09:00:00Z'");
        values.put("recorded_at", "now()");
        values.put("reverses_journal_entry_id", "NULL");
        String column = broken.substring(0, broken.indexOf('=')).trim();
        String value = broken.substring(broken.indexOf('=') + 1).trim();
        if (!values.containsKey(column)) {
            throw new IllegalArgumentException(column);
        }
        values.put(column, value);
        asApplication("INSERT INTO risk.monitored_transaction (" + String.join(", ", values.keySet())
                + ") VALUES (" + String.join(", ", values.values()) + ")");
    }

    private static void asApplication(String sql) throws SQLException {
        try (Statement statement = application.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> grantsOn(String table) throws SQLException {
        return column("SELECT grantee || ':' || privilege_type FROM information_schema.table_privileges"
                + " WHERE table_schema = 'risk' AND table_name = '" + table + "' AND grantee <> 'finapp_migrator'");
    }

    private static void asApplication(String sql, Object... arguments) throws SQLException {
        try (java.sql.PreparedStatement statement = application.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.execute();
        }
    }

    private static List<String> aclOf(String schema) throws SQLException {
        return column("SELECT unnest(nspacl)::text FROM pg_namespace WHERE nspname = '" + schema + "'");
    }

    private static List<String> tablesIn(String schema) throws SQLException {
        return column("SELECT tablename FROM pg_tables WHERE schemaname = '" + schema + "' ORDER BY tablename");
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
        try (Statement statement = migrator.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as("one row from: %s", sql).isTrue();
            return rows.getString(1);
        }
    }

    /** The migration with its comment lines removed - what actually executes. */
    private static String statementsOf(String migration) {
        return Arrays.stream(migration.split("\\R"))
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    }

    /** From the classpath, the sibling migration tests' idiom. */
    private static String migration(String file) {
        String resource = "db/migration/risk/" + file;
        try (InputStream stream = RiskMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
