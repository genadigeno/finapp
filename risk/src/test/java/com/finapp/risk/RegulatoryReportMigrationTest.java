package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code risk V016}'s text held to the code (`P13-TSK-021`; the migration-test idiom, hermetic - the live constraints,
 * triggers and grants are {@code RegulatoryReportDatabaseTest}'s, written and owed under C5): the status vocabulary is
 * {@link RegulatoryReportStatus}'s; <strong>one live or accepted report per case</strong> - the partial unique's predicate
 * {@code status <> 'REJECTED'}, never the entry's {@code IN ('DRAFT', 'APPROVED')} ({@code DESIGN-021} D3); four eyes a
 * {@code CHECK} of its own; the persons {@code CUSTOMER} or {@code EMPLOYEE}, as {@link Cases#person} admits;
 * <strong>no content column is in the application role's {@code UPDATE} grant</strong> and the trigger names every one
 * of them frozen (D4); the case's {@code REPORT_DRAFTED} act bound both ways (D2); the drive's claim only on a due permit;
 * no {@code DELETE}; every name new; no other schema named.
 */
@DisplayName("risk V016: the regulatory report, one live per case, four eyes, the content never granted and frozen"
        + " (P13-TSK-021)")
class RegulatoryReportMigrationTest {

    private static final String RESOURCE = "db/migration/risk/V016__the_regulatory_report.sql";

    /** Every column of the content and the drafter - what no writer may ever change. */
    static final List<String> CONTENT = List.of("jurisdiction", "format_version", "reporting_entity_ref", "subject_kind",
            "subject_ref", "account_refs", "transaction_ids", "ground_codes", "narrative", "attachment_refs",
            "drafted_by", "drafted_by_type", "drafted_at", "correlation_id", "case_id");

    /** The machine's columns - exactly the application role's column-scoped UPDATE. */
    static final List<String> MACHINE = List.of("status", "approved_by", "approved_by_type", "filed_by", "filed_by_type",
            "provider_reference", "rejection_code", "next_inquiry_at", "attempts");

    private static final List<String> EARLIER = List.of(
            "db/migration/risk/V001__initialise_risk_schema.sql",
            "db/migration/risk/V002__risk_policy_versions_as_data.sql",
            "db/migration/risk/V003__risk_signal.sql",
            "db/migration/risk/V004__monitored_transactions_and_the_watermark.sql",
            "db/migration/risk/V005__velocity_windows.sql",
            "db/migration/risk/V006__the_customer_risk_profile.sql",
            "db/migration/risk/V007__the_sealed_assessment.sql",
            "db/migration/risk/V008__the_held_card_payment.sql",
            "db/migration/risk/V009__the_decision_override_machine.sql",
            "db/migration/risk/V010__the_born_once_alert.sql",
            "db/migration/risk/V011__payment_merchant_and_bnpl_abuse.sql",
            "db/migration/risk/V012__the_case.sql",
            "db/migration/risk/V013__downstream_risk_actions.sql",
            "db/migration/risk/V014__the_queues_and_the_suppression_persons.sql",
            "db/migration/risk/V015__monitored_transaction_by_counterparty.sql");

    private static final Pattern ANOTHER_SCHEMA = Pattern.compile("\\b(lending|credit|ledger|platform|checkout|merchant"
            + "|identity|accounts|payments|paymentmethods|bnpl|kyc|party|transfers|crossborder|settlement"
            + "|reconciliation|fx|consent|public)\\.[a-z_]+");

    @Test
    @DisplayName("the vocabulary is RegulatoryReportStatus's, for the report and its history alike")
    void theStatusVocabulary() {
        String sql = collapsed(statements(RESOURCE));
        String statuses = Arrays.stream(RegulatoryReportStatus.values()).map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
        assertThat(sql).contains("CONSTRAINT regulatory_report_status_is_known CHECK (status IN (" + statuses + "))")
                .contains("CONSTRAINT regulatory_report_event_status_is_known CHECK (status IN (" + statuses + "))");
    }

    @Test
    @DisplayName("one live or accepted report per case: the partial unique over every status but REJECTED (D3) - never"
            + " the entry's DRAFT and APPROVED, which would admit a second filing beside a FILED one - and the store's"
            + " birth infers it")
    void oneLiveReportPerCase() {
        String sql = collapsed(statements(RESOURCE));
        assertThat(sql).contains("CREATE UNIQUE INDEX regulatory_report_one_live_per_case ON risk.regulatory_report"
                + " (case_id) WHERE status <> 'REJECTED';");
        assertThat(sql).doesNotContain("WHERE status IN ('DRAFT', 'APPROVED')");
        assertThat(JdbcRegulatoryReportStore.INSERT).contains("ON CONFLICT (case_id) WHERE status <> 'REJECTED' DO NOTHING");
        assertThat(JdbcRegulatoryReportStore.LIVE_OF).contains("WHERE case_id = ? AND status <> 'REJECTED'");
        for (RegulatoryReportStatus status : RegulatoryReportStatus.values()) {
            assertThat(status.occupiesItsCase()).as("%s occupies its case exactly when the unique holds it", status)
                    .isEqualTo(status != RegulatoryReportStatus.REJECTED);
        }
        assertThat(sql).contains("CONSTRAINT regulatory_report_event_pk PRIMARY KEY (report_id, status)");
    }

    @Test
    @DisplayName("four eyes is a CHECK of its own, provable alone; the persons CUSTOMER or EMPLOYEE - exactly what"
            + " Cases.person admits - and the authority's answer the platform's")
    void fourEyesAndThePersons() {
        String sql = collapsed(statements(RESOURCE));
        assertThat(sql).contains("CONSTRAINT regulatory_report_four_eyes CHECK (approved_by IS NULL OR approved_by <>"
                + " drafted_by)");
        assertThat(sql).contains("CONSTRAINT regulatory_report_by_persons CHECK (drafted_by_type IN ('CUSTOMER',"
                + " 'EMPLOYEE') AND (approved_by_type IS NULL OR approved_by_type IN ('CUSTOMER', 'EMPLOYEE')) AND"
                + " (filed_by_type IS NULL OR filed_by_type IN ('CUSTOMER', 'EMPLOYEE')))");
        String persons = sql.substring(sql.indexOf("CONSTRAINT regulatory_report_by_persons"),
                sql.indexOf("CONSTRAINT regulatory_report_four_eyes"));
        for (com.finapp.platform.security.ActorType type : com.finapp.platform.security.ActorType.values()) {
            assertThat(persons.contains("'" + type.name() + "'")).as("%s admitted exactly when Cases.person admits it",
                    type).isEqualTo(Cases.person(new com.finapp.platform.security.Actor("someone", type)));
        }
        assertThat(sql).contains("(status IN ('ACKNOWLEDGED', 'REJECTED') AND actor_type = 'SYSTEM')");
        String machine = function(sql, "regulatory_report_permits_only_machine_edges");
        assertThat(machine).as("the trigger leaves four eyes to the CHECK").doesNotContain("approved_by = NEW.drafted_by")
                .doesNotContain("approved_by <> ");
    }

    @Test
    @DisplayName("immutable by both shapes (D4): no content column in the application's UPDATE grant - the grant is"
            + " exactly the machine's columns - and the trigger names every content column frozen, for every writer")
    void theContentIsNeverGrantedAndFrozen() {
        String sql = collapsed(statements(RESOURCE));
        Matcher grant = Pattern.compile("GRANT UPDATE \\(([^)]*)\\) ON risk\\.regulatory_report TO finapp_app;")
                .matcher(sql);
        assertThat(grant.find()).as("the column-scoped UPDATE grant").isTrue();
        List<String> granted = Arrays.stream(grant.group(1).split(",")).map(String::strip).toList();
        assertThat(granted).containsExactlyInAnyOrderElementsOf(MACHINE).doesNotContainAnyElementsOf(CONTENT);
        assertThat(sql).contains("GRANT SELECT, INSERT ON risk.regulatory_report TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON risk.regulatory_report_event TO finapp_app;")
                .doesNotContain("GRANT DELETE").doesNotContain("GRANT ALL").doesNotContain("GRANT TRUNCATE");
        String machine = function(sql, "regulatory_report_permits_only_machine_edges");
        String frozen = machine.substring(0, machine.indexOf("content never changes"));
        for (String column : CONTENT) {
            assertThat(frozen).as("%s frozen by the trigger", column)
                    .contains("NEW." + column + " IS DISTINCT FROM OLD." + column);
        }
        assertThat(machine).contains("an answered regulatory report never moves")
                .contains("a regulatory report is never deleted or truncated");
        for (String statement : List.of(JdbcRegulatoryReportStore.APPROVE, JdbcRegulatoryReportStore.FILE,
                JdbcRegulatoryReportStore.CLAIM, JdbcRegulatoryReportStore.CLAIM_NEXT_DUE,
                JdbcRegulatoryReportStore.ACKNOWLEDGE, JdbcRegulatoryReportStore.REJECT, JdbcRegulatoryReportStore.AWAIT)) {
            String set = statement.substring(statement.indexOf(" SET ") + 5, statement.indexOf(" WHERE "));
            for (String assignment : set.split(",(?![^(]*\\))")) {
                String column = assignment.strip().split(" ")[0];
                assertThat(MACHINE).as("the store sets only granted machine columns: %s", statement).contains(column);
            }
        }
    }

    @Test
    @DisplayName("the case's REPORT_DRAFTED act and the report bound both ways (D2): the report's id is the act's, the act"
            + " only on a confirmed-suspicious AML case past its revision, and every such act has its report by commit")
    void theCasesActAndTheReport() {
        String sql = collapsed(statements(RESOURCE));
        assertThat(sql).contains("CONSTRAINT regulatory_report_drafted_on_its_case FOREIGN KEY (id, case_id) REFERENCES"
                + " risk.risk_case_action (id, case_id)");
        assertThat(function(sql, "regulatory_report_permits_only_machine_edges"))
                .contains("a.kind = 'REPORT_DRAFTED'").contains("c.kind = 'AML'")
                .contains("c.disposition = 'CONFIRMED_SUSPICIOUS'").contains("c.subject_ref = NEW.subject_ref::text")
                .contains("FROM risk.monitored_transaction m").contains("al.attached_case_id = NEW.case_id");
        assertThat(function(sql, "risk_case_action_report_drafted_coherent")).contains("NEW.ordinal > c.revision")
                .contains("c.status = 'DISPOSED'");
        assertThat(sql).contains("CREATE TRIGGER risk_case_action_report_drafted_coherent BEFORE INSERT ON"
                + " risk.risk_case_action FOR EACH ROW WHEN (NEW.kind = 'REPORT_DRAFTED')")
                .contains("CREATE CONSTRAINT TRIGGER risk_case_action_report_drafted_has_its_report AFTER INSERT ON"
                        + " risk.risk_case_action DEFERRABLE INITIALLY DEFERRED")
                .contains("CREATE CONSTRAINT TRIGGER regulatory_report_has_its_history AFTER INSERT OR UPDATE ON"
                        + " risk.regulatory_report DEFERRABLE INITIALLY DEFERRED");
        assertThat(collapsed(statements("db/migration/risk/V012__the_case.sql")))
                .as("V012 is never edited: it admitted the kind and wrote nothing of it")
                .contains("'DISPOSITION_REJECTED', 'REPORT_DRAFTED'))");
    }

    @Test
    @DisplayName("filed before the call: the filing sets the permit due at once with no attempt; the claim takes only a"
            + " due FILED permit, SKIP LOCKED, counting one attempt; the record is conditional on FILED and the attempt")
    void theDrivesShape() {
        String machine = function(collapsed(statements(RESOURCE)), "regulatory_report_permits_only_machine_edges");
        assertThat(machine).contains("NEW.filed_at := clock_timestamp(); NEW.next_inquiry_at := clock_timestamp();")
                .contains("OLD.next_inquiry_at > clock_timestamp()").contains("NEW.attempts = OLD.attempts + 1")
                .contains("the authority''s answer follows a claim and counts no attempt");
        assertThat(JdbcRegulatoryReportStore.CLAIM).contains("FOR UPDATE SKIP LOCKED").contains("status = 'FILED'")
                .contains("next_inquiry_at <= clock_timestamp()").contains("attempts = attempts + 1");
        assertThat(JdbcRegulatoryReportStore.CLAIM_NEXT_DUE).contains("ORDER BY next_inquiry_at, id LIMIT 1 FOR UPDATE"
                + " SKIP LOCKED");
        for (String record : List.of(JdbcRegulatoryReportStore.ACKNOWLEDGE, JdbcRegulatoryReportStore.REJECT,
                JdbcRegulatoryReportStore.AWAIT)) {
            assertThat(record).contains("WHERE id = ? AND status = 'FILED' AND attempts = ?")
                    .contains("provider_reference = COALESCE(provider_reference, ?)");
        }
        assertThat(collapsed(statements(RESOURCE))).contains("CREATE INDEX regulatory_report_due ON"
                + " risk.regulatory_report (next_inquiry_at, id) WHERE next_inquiry_at IS NOT NULL;");
    }

    @Test
    @DisplayName("nothing else: two tables, their functions and triggers, every name new across V001 to V015; no other"
            + " schema named; no earlier migration edited")
    void everyNameNewAndNoOtherSchema() {
        String sql = statements(RESOURCE);
        Set<String> earlier = new HashSet<>();
        for (String migration : EARLIER) {
            earlier.addAll(names(statements(migration)));
        }
        List<String> v016 = names(sql);
        assertThat(v016).contains("regulatory_report_pk", "regulatory_report_one_live_per_case",
                "regulatory_report_four_eyes", "regulatory_report_event_pk",
                "regulatory_report_permits_only_machine_edges", "risk_case_action_report_drafted_coherent");
        assertThat(v016.stream().filter(earlier::contains)).isEmpty();
        assertThat(sql).doesNotContain("DROP ").doesNotContain("ALTER TABLE").doesNotContain("CREATE OR REPLACE");
        String unquoted = sql.replaceAll("'(?:[^']|'')*'", "''");
        assertThat(ANOTHER_SCHEMA.matcher(unquoted).find()).isFalse();
    }

    // -----------------------------------------------------------------

    /** The body of function {@code name}, from its CREATE to its closing dollar-quote. */
    private static String function(String sql, String name) {
        int start = sql.indexOf("CREATE FUNCTION risk." + name + "()");
        assertThat(start).as("function %s", name).isNotNegative();
        int body = sql.indexOf("$$", start);
        return sql.substring(start, sql.indexOf("$$", body + 2));
    }

    private static List<String> names(String sql) {
        List<String> names = new ArrayList<>();
        for (Pattern pattern : List.of(
                Pattern.compile("(?<!ADD )CONSTRAINT\\s+([a-z0-9_]+)\\s+(?:PRIMARY|UNIQUE|CHECK|FOREIGN)"),
                Pattern.compile("CREATE (?:UNIQUE )?INDEX\\s+([a-z0-9_]+)"),
                Pattern.compile("CREATE (?:CONSTRAINT )?TRIGGER\\s+([a-z0-9_]+)"),
                Pattern.compile("CREATE (?:OR REPLACE )?FUNCTION\\s+risk\\.([a-z0-9_]+)"))) {
            Matcher matched = pattern.matcher(sql);
            while (matched.find()) {
                names.add(matched.group(1));
            }
        }
        return names.stream().distinct().collect(Collectors.toList());
    }

    private static String collapsed(String sql) {
        return sql.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }

    /** The migration with its comment lines removed - what actually executes. */
    private static String statements(String resource) {
        try (InputStream in = RegulatoryReportMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.strip().startsWith("--"))
                    .collect(Collectors.joining("\n"));
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
