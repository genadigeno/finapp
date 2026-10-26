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
 * {@code risk V017}'s text held to the code (`P13-TSK-025`; the migration-test idiom, hermetic - the live constraints are
 * {@code RiskMigrationTest#theCollectionsCase}'s and {@code CollectionsCaseDatabaseTest}'s, written and owed under C5):
 * the fact kind {@code OBLIGATION_DEFAULTED} admitted by re-making V010's four CHECKs by name over {@link AlertKind}'s
 * own-code kinds, the fact's shape held for every writer, the collections case's three rules - a party's, opened by its
 * default, disposed by facts only ({@link DispositionRules#COLLECTIONS}) - and nothing else: V010's born-once unique
 * and V012's one-open unique untouched, no table, grant or trigger, no other schema.
 */
@DisplayName("risk V017 admits a default's alert and holds the collections case to facts (P13-TSK-025)")
class RiskCollectionsMigrationTest {

    private static final String RESOURCE = "db/migration/risk/V017__the_collections_case.sql";

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
            "db/migration/risk/V015__monitored_transaction_by_counterparty.sql",
            "db/migration/risk/V016__the_regulatory_report.sql");

    private static final Pattern ANOTHER_SCHEMA = Pattern.compile("\\b(lending|credit|ledger|platform|checkout|merchant"
            + "|identity|accounts|payments|paymentmethods|bnpl|kyc|party|transfers|crossborder|settlement"
            + "|reconciliation|fx|consent|public)\\.[a-z_]+");

    private final String sql = collapsed(statements(RESOURCE));

    @Test
    @DisplayName("the fact's alert: the kind and rule-code CHECKs are AlertKind's and AlertRule.codes() exactly, and the"
            + " two pairing CHECKs name AlertKind's own-code kinds - the operational pair and the fact - each dropped"
            + " first under its own name")
    void theFactKindIsAdmittedByName() {
        assertThat(sql)
                .contains("ADD CONSTRAINT risk_alert_kind_is_known CHECK (kind IN (" + list(AlertKind.values()) + "));")
                .contains("ADD CONSTRAINT risk_alert_rule_code_is_known CHECK (rule_code IN ("
                        + quoted(AlertRule.codes()) + "));");
        String own = quoted(Arrays.stream(AlertKind.values()).filter(AlertKind::ownCode).map(Enum::name).toList());
        assertThat(own).isEqualTo("'UNCLASSIFIED_TRANSACTION', 'VELOCITY_DIVERGENCE', 'OBLIGATION_DEFAULTED'");
        assertThat(sql)
                .contains("ADD CONSTRAINT risk_alert_rule_matches_kind CHECK ((kind IN (" + own + ")) = (rule_code IN ("
                        + own + ")) AND (kind NOT IN (" + own + ") OR rule_code = kind));")
                .contains("ADD CONSTRAINT risk_alert_policy_named CHECK ((kind IN (" + own
                        + ")) = (policy_version_id IS NULL));");
        for (String remade : List.of("risk_alert_kind_is_known", "risk_alert_rule_code_is_known",
                "risk_alert_rule_matches_kind", "risk_alert_policy_named")) {
            assertThat(sql.indexOf("DROP CONSTRAINT " + remade + ";")).as(remade + " dropped before it is made again")
                    .isGreaterThanOrEqualTo(0).isLessThan(sql.indexOf("ADD CONSTRAINT " + remade + " "));
        }
        assertThat(AlertKind.OBLIGATION_DEFAULTED.factSubject()).contains(SubjectKind.CREDIT_AGREEMENT);
        assertThat(sql).contains("ADD CONSTRAINT risk_alert_default_names_its_agreement CHECK (kind <>"
                + " 'OBLIGATION_DEFAULTED' OR (subject_kind = 'CREDIT_AGREEMENT' AND window_kind = 'D1' AND"
                + " cardinality(evidence_signal_ids) = 1 AND cardinality(evidence_assessment_ids) = 0 AND"
                + " cardinality(evidence_journal_entry_ids) = 0));");
    }

    @Test
    @DisplayName("the collections case: a party's, opened by its default's alert alone, disposed by "
            + "DispositionRules.COLLECTIONS only - facts, never a finding")
    void theCollectionsCaseIsFactsOnly() {
        assertThat(DispositionRules.COLLECTIONS).containsExactlyInAnyOrder(Disposition.CLOSED_NO_ACTION,
                Disposition.REFERRED);
        String facts = DispositionRules.COLLECTIONS.stream().map(d -> "'" + d.name() + "'").sorted()
                .collect(Collectors.joining(", "));
        assertThat(sql)
                .contains("ADD CONSTRAINT risk_case_collections_of_a_party CHECK (kind <> 'COLLECTIONS' OR subject_kind"
                        + " = 'PARTY');")
                .contains("ADD CONSTRAINT risk_case_collections_opened_by_a_default CHECK (kind <> 'COLLECTIONS' OR"
                        + " (opening_alert_id IS NOT NULL AND opening_assessment_id IS NULL));")
                .contains("ADD CONSTRAINT risk_case_collections_disposed_by_facts CHECK (kind <> 'COLLECTIONS' OR"
                        + " disposition IS NULL OR disposition IN (" + facts + "));");
    }

    @Test
    @DisplayName("nothing else: the four re-made names are V010's and the four new names are new across V001 to V016;"
            + " the born-once and one-open uniques untouched; no table, column, index, function, trigger, grant or"
            + " write; no other schema, no amount")
    void nothingElse() {
        Set<String> earlier = new HashSet<>();
        for (String migration : EARLIER) {
            earlier.addAll(names(statements(migration)));
        }
        List<String> made = names(sql);
        assertThat(made).doesNotHaveDuplicates();
        assertThat(made.stream().filter(earlier::contains)).containsExactlyInAnyOrder("risk_alert_kind_is_known",
                "risk_alert_rule_code_is_known", "risk_alert_rule_matches_kind", "risk_alert_policy_named");
        assertThat(made.stream().filter(name -> !earlier.contains(name))).containsExactlyInAnyOrder(
                "risk_alert_default_names_its_agreement", "risk_case_collections_of_a_party",
                "risk_case_collections_opened_by_a_default", "risk_case_collections_disposed_by_facts");
        assertThat(sql).doesNotContain("risk_alert_born_once").doesNotContain("risk_case_one_open")
                .doesNotContain("CREATE ").doesNotContain("GRANT").doesNotContain("REVOKE").doesNotContain("INSERT INTO")
                .doesNotContain("UPDATE risk.").doesNotContain("DELETE").doesNotContain("ADD COLUMN")
                .doesNotContainPattern("amount|balance|_minor");
        String unquoted = statements(RESOURCE).replaceAll("'(?:[^']|'')*'", "''");
        assertThat(ANOTHER_SCHEMA.matcher(unquoted).find()).as("no other schema named").isFalse();
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

    /** The migration with its comment lines and its COMMENT ON texts removed - what executes. */
    private static String statements(String resource) {
        try (InputStream in = RiskCollectionsMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource + " on the classpath").isNotNull();
            String executed = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.strip().startsWith("--"))
                    .collect(Collectors.joining("\n"));
            return executed.replaceAll("(?s)COMMENT ON [^;]*? IS\\s*'(?:[^']|'')*';", "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
