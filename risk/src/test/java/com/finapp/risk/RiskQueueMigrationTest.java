package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code risk V014}'s text held to the code (`P13-TSK-018`; the migration-test idiom, hermetic - the live constraint and
 * indexes are {@code QueueDatabaseTest}'s, written and owed under C5): the suppression's persons widened by name to an
 * authenticated human - {@code CUSTOMER} or {@code EMPLOYEE}, as {@link Cases#person} reads it - and nothing else;
 * {@code V010} left as applied; the four keyset indexes on the columns and the order the queue statements read; no
 * table, function, trigger or grant; every new name new; no other schema named.
 */
@DisplayName("risk V014 widens the suppression's persons by name and adds the queues' keyset indexes (P13-TSK-018)")
class RiskQueueMigrationTest {

    private static final String RESOURCE = "db/migration/risk/V014__the_queues_and_the_suppression_persons.sql";

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
            "db/migration/risk/V013__downstream_risk_actions.sql");

    private static final Pattern ANOTHER_SCHEMA = Pattern.compile("\\b(lending|credit|ledger|platform|checkout|merchant"
            + "|identity|accounts|payments|paymentmethods|bnpl|kyc|party|transfers|crossborder|settlement"
            + "|reconciliation|fx|consent|public)\\.[a-z_]+");

    @Test
    @DisplayName("the persons: risk_alert_suppression_by_persons dropped and re-made by name over CUSTOMER and EMPLOYEE"
            + " for both acts - the vocabulary Cases.person admits, never SYSTEM, SERVICE or MERCHANT")
    void thePersonsAreWidenedByName() {
        String sql = collapsed(statements(RESOURCE));
        assertThat(sql)
                .contains("ALTER TABLE risk.risk_alert DROP CONSTRAINT risk_alert_suppression_by_persons;")
                .contains("ALTER TABLE risk.risk_alert ADD CONSTRAINT risk_alert_suppression_by_persons CHECK ("
                        + "(suppression_proposed_by_type IS NULL OR suppression_proposed_by_type IN ('CUSTOMER',"
                        + " 'EMPLOYEE')) AND (suppression_decided_by_type IS NULL OR suppression_decided_by_type IN"
                        + " ('CUSTOMER', 'EMPLOYEE')));");
        assertThat(sql).doesNotContain("'SYSTEM'").doesNotContain("'SERVICE'").doesNotContain("'MERCHANT'");
        for (com.finapp.platform.security.ActorType type : com.finapp.platform.security.ActorType.values()) {
            boolean admitted = sql.contains("'" + type.name() + "'");
            assertThat(admitted).as("%s admitted by V014 exactly when Cases.person admits it", type)
                    .isEqualTo(Cases.person(new com.finapp.platform.security.Actor("someone", type)));
        }
        assertThat(collapsed(statements("db/migration/risk/V010__the_born_once_alert.sql")))
                .as("V010 is never edited: it still reads EMPLOYEE alone, and V014 re-makes the constraint")
                .contains("(suppression_proposed_by_type IS NULL OR suppression_proposed_by_type = 'EMPLOYEE')");
    }

    @Test
    @DisplayName("the keyset indexes: on the immutable keys in the order the queue statements read them - a named"
            + " status, the case's alerts, the open cases")
    void theKeysetIndexes() {
        String sql = collapsed(statements(RESOURCE));
        assertThat(sql)
                .contains("CREATE INDEX risk_alert_queue ON risk.risk_alert (status, raised_at, id);")
                .contains("CREATE INDEX risk_alert_of_case ON risk.risk_alert (attached_case_id, raised_at, id) WHERE"
                        + " attached_case_id IS NOT NULL;")
                .contains("CREATE INDEX risk_case_queue ON risk.risk_case (status, opened_at, id);")
                .contains("CREATE INDEX risk_case_open_queue ON risk.risk_case (opened_at, id) WHERE status <>"
                        + " 'DISPOSED';");
        assertThat(JdbcRiskAlertStore.QUEUE_ORDER).isEqualTo(" ORDER BY raised_at, id LIMIT ?");
        assertThat(JdbcRiskAlertStore.QUEUE_AFTER).contains("(raised_at, id) >");
        assertThat(JdbcRiskAlertStore.OF_CASE).contains("WHERE attached_case_id = ? ORDER BY raised_at, id");
        assertThat(JdbcRiskCaseStore.QUEUE_ORDER).isEqualTo(" ORDER BY opened_at, id LIMIT ?");
        assertThat(JdbcRiskCaseStore.QUEUE_AFTER).contains("(opened_at, id) >");
        assertThat(JdbcRiskCaseStore.QUEUE_OPEN).isEqualTo(" AND status <> 'DISPOSED'");
        assertThat(JdbcRiskCaseStore.BACKLOG).contains("WHERE status <> 'DISPOSED' GROUP BY kind")
                .contains("statement_timestamp()");
        assertThat(JdbcRiskCaseStore.queueStatement(false, true, true))
                .isEqualTo(JdbcRiskCaseStore.QUEUE + JdbcRiskCaseStore.QUEUE_OPEN + JdbcRiskCaseStore.QUEUE_KIND
                        + JdbcRiskCaseStore.QUEUE_AFTER + JdbcRiskCaseStore.QUEUE_ORDER);
        assertThat(JdbcRiskAlertStore.queueStatement(false, false))
                .isEqualTo(JdbcRiskAlertStore.QUEUE + JdbcRiskAlertStore.QUEUE_ORDER);
    }

    @Test
    @DisplayName("nothing else: no table, column, function, trigger or grant; every name new across V001 to V013 but the"
            + " one re-made by name; no other schema named")
    void nothingElseAndNoOtherSchema() {
        String sql = statements(RESOURCE);
        assertThat(sql).doesNotContain("CREATE TABLE").doesNotContain("ADD COLUMN").doesNotContain("CREATE FUNCTION")
                .doesNotContain("CREATE TRIGGER").doesNotContain("GRANT").doesNotContain("REVOKE")
                .doesNotContain("DELETE").doesNotContain("UPDATE risk.");
        Set<String> earlier = new HashSet<>();
        for (String migration : EARLIER) {
            earlier.addAll(names(statements(migration)));
        }
        List<String> v014 = names(sql);
        assertThat(v014).containsExactlyInAnyOrder("risk_alert_suppression_by_persons", "risk_alert_queue",
                "risk_alert_of_case", "risk_case_queue", "risk_case_open_queue");
        assertThat(v014.stream().filter(earlier::contains)).containsExactly("risk_alert_suppression_by_persons");
        String unquoted = sql.replaceAll("'(?:[^']|'')*'", "''");
        assertThat(ANOTHER_SCHEMA.matcher(unquoted).find()).isFalse();
    }

    // -----------------------------------------------------------------

    private static List<String> names(String sql) {
        List<String> names = new ArrayList<>();
        for (Pattern pattern : List.of(
                Pattern.compile("ADD CONSTRAINT\\s+([a-z0-9_]+)\\s+(?:PRIMARY|UNIQUE|CHECK|FOREIGN)"),
                Pattern.compile("(?<!ADD )CONSTRAINT\\s+([a-z0-9_]+)\\s+(?:PRIMARY|UNIQUE|CHECK|FOREIGN)"),
                Pattern.compile("CREATE (?:UNIQUE )?INDEX\\s+([a-z0-9_]+)"),
                Pattern.compile("CREATE (?:CONSTRAINT )?TRIGGER\\s+([a-z0-9_]+)"))) {
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
        try (InputStream in = RiskQueueMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.strip().startsWith("--"))
                    .collect(Collectors.joining("\n"));
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
