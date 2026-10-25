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
 * {@code risk V010}'s text held to the code (`P13-TSK-015`; the migration-test idiom, hermetic - the live table is
 * {@code AlertDatabaseTest}'s and the live grants {@code RiskMigrationTest}'s, written and owed under C5): the
 * vocabularies are the enums', the unique is the plan's triple and the store's birth names it, the machine's every rule
 * is present and the four-eyes rule is the {@code CHECK}'s alone, the grant is a column-scoped {@code UPDATE} with no
 * {@code DELETE}, every name is new across V001 to V009 (the Phase 12 V012 lesson) and no other schema is named.
 */
@DisplayName("risk V010 holds the born-once alert as the code declares it (P13-TSK-015)")
class RiskAlertMigrationTest {

    private static final String RESOURCE = "db/migration/risk/V010__the_born_once_alert.sql";

    private static final List<String> EARLIER = List.of(
            "db/migration/risk/V001__initialise_risk_schema.sql",
            "db/migration/risk/V002__risk_policy_versions_as_data.sql",
            "db/migration/risk/V003__risk_signal.sql",
            "db/migration/risk/V004__monitored_transactions_and_the_watermark.sql",
            "db/migration/risk/V005__velocity_windows.sql",
            "db/migration/risk/V006__the_customer_risk_profile.sql",
            "db/migration/risk/V007__the_sealed_assessment.sql",
            "db/migration/risk/V008__the_held_card_payment.sql",
            "db/migration/risk/V009__the_decision_override_machine.sql");

    private static final Pattern ANOTHER_SCHEMA = Pattern.compile("\\b(lending|credit|ledger|platform|checkout|merchant"
            + "|identity|accounts|payments|paymentmethods|bnpl|kyc|party|transfers|crossborder|settlement"
            + "|reconciliation|fx|consent|public)\\.[a-z_]+");

    /**
     * risk V019 (P13-TSK-025): the kind and rule-code CHECKs, and the two that pair the own-code kinds with no policy,
     * dropped and re-made for the fact kind OBLIGATION_DEFAULTED - V010 is merged and never edited, so their effective
     * definition is V019's ({@code RiskCollectionsMigrationTest} holds it).
     */
    private static final String COLLECTIONS = "db/migration/risk/V019__the_collections_case.sql";

    @Test
    @DisplayName("the vocabularies are the enums': kind, severity, subject kind, rule code, window kind, status and the"
            + " suppression's reason code - and the operational kinds the CHECKs name are AlertKind's operational ones")
    void theListsAreTheEnums() {
        String sql = collapsed(statements(RESOURCE));
        String effective = collapsed(statements(COLLECTIONS));
        assertThat(effective)
                .contains("CHECK (kind IN (" + list(AlertKind.values()) + "))")
                .contains("CHECK (rule_code IN (" + quoted(AlertRule.codes()) + "))");
        assertThat(sql)
                .contains("CHECK (severity IN (" + list(AlertSeverity.values()) + "))")
                .contains("CHECK (subject_kind IN (" + list(SubjectKind.values()) + "))")
                .contains("CHECK (window_kind IN (" + list(WindowKind.values()) + "))")
                .contains("CHECK (status IN (" + list(AlertStatus.values()) + "))")
                .contains("suppression_reason_code IN (" + list(SuppressionReason.values()) + ")");
        String operational = quoted(Arrays.stream(AlertKind.values()).filter(AlertKind::operational).map(Enum::name)
                .toList());
        assertThat(operational).isEqualTo("'UNCLASSIFIED_TRANSACTION', 'VELOCITY_DIVERGENCE'");
        Matcher named = Pattern.compile("IN \\('UNCLASSIFIED_TRANSACTION', 'VELOCITY_DIVERGENCE'\\)").matcher(sql);
        int count = 0;
        while (named.find()) {
            count++;
        }
        assertThat(count).as("the operational pair in every CHECK that names it").isGreaterThanOrEqualTo(6);
        assertThat(sql).doesNotContain("'DUPLICATE'");
        // The pairing is by vocabulary, never "rule_code = kind" alone: SCREENING_HIT names both an alert kind and a
        // rule kind, and its rule's alert must be admitted.
        assertThat(sql).contains("CONSTRAINT risk_alert_rule_matches_kind CHECK ((kind IN ("
                + operational + ")) = (rule_code IN (" + operational + ")) AND (kind NOT IN (" + operational
                + ") OR rule_code = kind))");
        assertThat(AlertRule.of(RuleKind.SCREENING_HIT).code()).isEqualTo(AlertKind.SCREENING_HIT.name());
    }

    @Test
    @DisplayName("INV-RSK-06: the unique is the plan's triple, and the store's birth is one INSERT naming it -"
            + " ON CONFLICT ON CONSTRAINT risk_alert_born_once DO NOTHING, never a bare ON CONFLICT")
    void theUniqueIsTheTriple() {
        String sql = collapsed(statements(RESOURCE));
        assertThat(sql).contains("CONSTRAINT risk_alert_born_once UNIQUE (subject_ref, rule_code, window_start)");
        assertThat(JdbcRiskAlertStore.INSERT).startsWith("INSERT INTO risk.risk_alert (")
                .contains("ON CONFLICT ON CONSTRAINT risk_alert_born_once DO NOTHING RETURNING ")
                .contains("'OPEN', clock_timestamp()");
        assertThat(JdbcRiskAlertStore.FIND_BY_KEY).contains("WHERE subject_ref = ? AND rule_code = ? AND window_start = ?");
        assertThat(sql).contains("CONSTRAINT risk_alert_window_start_is_aligned CHECK (window_start ="
                + " date_bin(risk.velocity_window_length(window_kind), window_start, TIMESTAMPTZ '1970-01-05 00:00:00+00'))");
    }

    @Test
    @DisplayName("the machine's rules are present: born OPEN and stamped, the facts frozen, nothing out of a terminal"
            + " state, the four edges stamped on the database clock, nothing deleted - and four eyes is the CHECK's"
            + " alone, the trigger never judging it")
    void theMachinesRulesArePresent() {
        String sql = collapsed(statements(RESOURCE));
        assertThat(sql)
                .contains("RAISE EXCEPTION 'a risk alert is never deleted or truncated (P13-TSK-015, INV-RSK-06)';")
                .contains("RAISE EXCEPTION 'a risk alert is born OPEN, unattached and unproposed (P13-TSK-015)'")
                .contains("NEW.raised_at := clock_timestamp();")
                .contains("RAISE EXCEPTION 'a risk alert''s facts never change (P13-TSK-015)'")
                .contains("RAISE EXCEPTION 'an attached or suppressed risk alert never moves (P13-TSK-015)'")
                .contains("NEW.attached_at := clock_timestamp();")
                .contains("NEW.suppression_proposed_at := clock_timestamp();")
                .contains("NEW.suppression_decided_at := clock_timestamp();")
                .contains("NEW.suppression_proposed_at := NULL;")
                .contains("BEFORE INSERT OR UPDATE OR DELETE ON risk.risk_alert")
                .contains("BEFORE TRUNCATE ON risk.risk_alert")
                .contains("CONSTRAINT risk_alert_suppression_four_eyes CHECK (status <> 'SUPPRESSED' OR"
                        + " suppression_decided_by <> suppression_proposed_by)")
                .contains("(suppression_proposed_by_type IS NULL OR suppression_proposed_by_type = 'EMPLOYEE')")
                .contains("(suppression_decided_by_type IS NULL OR suppression_decided_by_type = 'EMPLOYEE')");
        assertThat(sql).doesNotContain("now()").doesNotContain("transaction_timestamp()");
        String trigger = sql.substring(sql.indexOf("CREATE FUNCTION risk.risk_alert_permits_only_machine_edges()"),
                sql.indexOf("CREATE TRIGGER risk_alert_permits_only_machine_edges"));
        assertThat(trigger).as("four eyes is provable by the CHECK alone")
                .doesNotContain("suppression_decided_by <>").doesNotContain("suppression_decided_by =");
    }

    @Test
    @DisplayName("the grant: SELECT and INSERT, and a column-scoped UPDATE of the status, the attachment and the"
            + " suppression - never a fact or a stamp - and no DELETE, no ALL, no default privilege")
    void theGrantIsColumnScoped() {
        String sql = statements(RESOURCE);
        List<String> grants = Arrays.stream(sql.split(";")).map(s -> s.strip().replaceAll("\\s+", " "))
                .filter(s -> s.startsWith("GRANT") || s.startsWith("REVOKE")).toList();
        assertThat(grants).containsExactly(
                "GRANT SELECT, INSERT ON risk.risk_alert TO finapp_app",
                "GRANT UPDATE (status, attached_case_id, suppression_reason_code, suppression_reason,"
                        + " suppression_proposed_by, suppression_proposed_by_type, suppression_decided_by,"
                        + " suppression_decided_by_type, suppression_decision_reason) ON risk.risk_alert TO finapp_app");
        // The trigger names DELETE and TRUNCATE (to refuse them); no grant does.
        assertThat(grants).noneMatch(grant -> grant.contains("DELETE") || grant.contains("TRUNCATE"));
        assertThat(sql).doesNotContain("GRANT ALL").doesNotContain("ALTER DEFAULT PRIVILEGES")
                .doesNotContain("INSERT INTO risk.");
        for (String statement : List.of(JdbcRiskAlertStore.ATTACH, JdbcRiskAlertStore.PROPOSE,
                JdbcRiskAlertStore.APPROVE, JdbcRiskAlertStore.REJECT)) {
            assertThat(statement).as("every edge conditional on OPEN").contains("WHERE id = ? AND status = 'OPEN'");
        }
        assertThat(JdbcRiskAlertStore.APPROVE).as("four eyes is the domain's and the CHECK's, never this statement's")
                .doesNotContain("<>");
    }

    @Test
    @DisplayName("every constraint, index and trigger name V010 creates is new across V001 to V009 and its own, the"
            + " store's columns are V010's, and no other schema is named")
    void namesAreUniqueAndNoOtherSchema() {
        Set<String> earlier = new HashSet<>();
        for (String migration : EARLIER) {
            earlier.addAll(names(statements(migration)));
        }
        List<String> v010 = names(statements(RESOURCE));
        assertThat(v010).as("the guard is not vacuous").hasSizeGreaterThanOrEqualTo(20);
        assertThat(v010).doesNotHaveDuplicates().noneMatch(earlier::contains)
                .allMatch(name -> name.startsWith("risk_alert_"));
        String unquoted = statements(RESOURCE).replaceAll("'(?:[^']|'')*'", "''");
        assertThat(ANOTHER_SCHEMA.matcher(unquoted).find()).isFalse();
        String table = collapsed(statements(RESOURCE));
        for (String column : JdbcRiskAlertStore.COLUMNS.split(",")) {
            assertThat(table).as(column.strip()).containsPattern("\\b" + column.strip()
                    + "\\s+(uuid|text|timestamptz|uuid\\[\\])[\\s,]");
        }
    }

    // -----------------------------------------------------------------

    private static List<String> names(String sql) {
        List<String> names = new ArrayList<>();
        for (Pattern pattern : List.of(
                Pattern.compile("CONSTRAINT\\s+([a-z0-9_]+)\\s+(?:PRIMARY|UNIQUE|CHECK|FOREIGN)"),
                Pattern.compile("CREATE (?:UNIQUE )?INDEX\\s+([a-z0-9_]+)"),
                Pattern.compile("CREATE (?:CONSTRAINT )?TRIGGER\\s+([a-z0-9_]+)"))) {
            Matcher matched = pattern.matcher(sql);
            while (matched.find()) {
                names.add(matched.group(1));
            }
        }
        return names;
    }

    private static String list(Enum<?>[] values) {
        return quoted(Arrays.stream(values).map(Enum::name).toList());
    }

    private static String quoted(List<String> values) {
        return values.stream().map(v -> "'" + v + "'").collect(Collectors.joining(", "));
    }

    private static String collapsed(String sql) {
        return sql.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }

    /** The migration with its comment lines removed - what actually executes. */
    private static String statements(String resource) {
        try (InputStream in = RiskAlertMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.strip().startsWith("--"))
                    .collect(Collectors.joining("\n"));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
