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
 * {@code risk V011} held to the code (`P13-TSK-014`; the migration-test idiom, hermetic - the live ranks are the owed
 * {@code RiskMigrationTest}'s and {@code MerchantAbuseDatabaseTest}'s under C5): four widenings and nothing narrowed -
 * the signal's two reference columns and three kinds, the merchant's numerator index, the {@code SESSION} dimension on
 * the windows and the parameters, the assessment's {@code session_id}; every re-made constraint dropped first under its
 * own name; one new name, held once across every risk migration; no grant, no row, no other schema.
 */
@DisplayName("risk V011 - payment, merchant and BNPL abuse, held to the code (P13-TSK-014)")
class RiskAbuseMigrationTest {

    private static final String MIGRATION = "db/migration/risk/V011__payment_merchant_and_bnpl_abuse.sql";

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
            "db/migration/risk/V010__the_born_once_alert.sql");

    private final String sql = statements(MIGRATION);

    @Test
    @DisplayName("the columns: risk_signal's merchant_subject_ref and intent_ref and risk_assessment's session_id, uuid, nullable -"
            + " the code's attribute columns and the store's statements name exactly them")
    void theColumns() {
        assertThat(sql).contains("ALTER TABLE risk.risk_signal ADD COLUMN merchant_subject_ref uuid;")
                .contains("ALTER TABLE risk.risk_signal ADD COLUMN intent_ref uuid;")
                .contains("ALTER TABLE risk.risk_assessment ADD COLUMN session_id uuid;");
        assertThat(sql).doesNotContainPattern("ADD COLUMN [a-z_]+ [a-z]+ NOT NULL")
                .doesNotContainPattern("ADD COLUMN [^;]*\\bDEFAULT\\b");
        assertThat(SignalKind.ATTRIBUTE_COLUMNS).endsWith("merchant_subject_ref", "intent_ref");
        assertThat(JdbcRiskAssessmentStore.INSERT_HEAD).contains("session_id");
        assertThat(JdbcRiskAssessmentStore.JUDGED_PAYMENT).contains("risk.risk_assessment")
                .contains("operation IN ('CARD_PAYMENT', 'WALLET_PAYMENT')").contains("command_ref = ?");
    }

    @Test
    @DisplayName("the guard: a ratio rule stored before V011 without its new threshold_count refuses the migration -"
            + " policy content is born once, so it could never be read again; never reinterpreted in place")
    void aStoredRatioRuleWithoutItsFloorRefusesTheMigration() {
        String collapsed = sql.replaceAll("\\s+", " ");
        assertThat(collapsed).contains("IF EXISTS (SELECT 1 FROM risk.risk_rule stored_rule WHERE stored_rule.kind IN"
                + " ('REFUND_RATIO', 'CHARGEBACK_RATIO') AND NOT EXISTS (SELECT 1 FROM risk.risk_rule_parameter"
                + " stored_parameter WHERE stored_parameter.policy_version_id = stored_rule.policy_version_id AND"
                + " stored_parameter.rule_ordinal = stored_rule.ordinal AND stored_parameter.name = 'threshold_count'))"
                + " THEN RAISE EXCEPTION");
        assertThat(RuleKind.REFUND_RATIO.parameters()).extracting(RuleKind.Parameter::name).contains("threshold_count");
        assertThat(RuleKind.CHARGEBACK_RATIO.parameters()).extracting(RuleKind.Parameter::name).contains("threshold_count");
        assertThat(collapsed.indexOf("RAISE EXCEPTION")).as("the guard runs before any widening")
                .isLessThan(collapsed.indexOf("ADD COLUMN"));
    }

    @Test
    @DisplayName("the kinds: the kind CHECK is exactly SignalKind, each kind's subject the code's, each kind's shape counts"
            + " over all fifteen attribute columns - so no older kind may carry the new ones")
    void theKinds() {
        Matcher kinds = Pattern.compile("risk_signal_kind_is_known CHECK \\(kind IN \\(([^)]*)\\)", Pattern.DOTALL)
                .matcher(sql);
        assertThat(kinds.find()).isTrue();
        assertThat(quoted(kinds.group(1))).containsExactlyElementsOf(
                Arrays.stream(SignalKind.values()).map(Enum::name).toList());
        for (SignalKind kind : List.of(SignalKind.REFUND_COMPLETED, SignalKind.CHARGEBACK_RECEIVED,
                SignalKind.BNPL_APPLICATION_SUBMITTED)) {
            assertThat(sql).containsPattern("WHEN '" + kind.name() + "'\\s+THEN 'PARTY'");
        }
        String columns = String.join(", ", SignalKind.ATTRIBUTE_COLUMNS);
        Matcher counted = Pattern.compile("num_nonnulls\\(([^)]*)\\)").matcher(sql);
        int arms = 0;
        while (counted.find()) {
            arms++;
            assertThat(counted.group(1).replaceAll("\\s+", " ").strip()).as("every arm counts every column")
                    .isEqualTo(columns);
        }
        assertThat(arms).as("an arm per kind").isEqualTo(SignalKind.values().length);
    }

    @Test
    @DisplayName("SESSION on the window table's and the parameter table's dimension lists - exactly VelocityDimension")
    void theSessionDimension() {
        String dimensions = Arrays.stream(VelocityDimension.values()).map(d -> "'" + d.name() + "'")
                .collect(Collectors.joining(", "));
        String collapsed = sql.replaceAll("\\s+", " ");
        assertThat(collapsed).contains("CHECK (dimension IN (" + dimensions + "))")
                .contains("value_velocity_dimension IN (" + dimensions + ")");
    }

    @Test
    @DisplayName("every re-made constraint is dropped first under its own name; the one new name, the merchant's index,"
            + " is held nowhere earlier; nothing is granted, seeded or named in another schema")
    void namesDropsAndNothingElse() {
        List<String> made = names(sql);
        assertThat(made).doesNotHaveDuplicates();
        Set<String> earlier = new HashSet<>();
        for (String migration : EARLIER) {
            earlier.addAll(names(statements(migration)));
        }
        List<String> fresh = made.stream().filter(name -> !earlier.contains(name)).toList();
        assertThat(fresh).containsExactly("risk_signal_by_merchant");
        for (String remade : made.stream().filter(earlier::contains).toList()) {
            assertThat(sql).as(remade + " is dropped before it is made again").containsPattern(
                    "DROP CONSTRAINT " + remade + ";");
        }
        assertThat(made).contains("risk_signal_kind_is_known", "risk_signal_subject_matches_kind",
                "risk_signal_attributes_match_kind", "velocity_window_dimension_is_known",
                "risk_rule_parameter_value_typed");
        assertThat(sql).contains("CREATE INDEX risk_signal_by_merchant ON risk.risk_signal (merchant_subject_ref, kind,"
                + " observed_at DESC)").contains("WHERE merchant_subject_ref IS NOT NULL");
        assertThat(sql).doesNotContain("GRANT").doesNotContain("REVOKE").doesNotContain("INSERT INTO")
                .doesNotContain("UPDATE risk.").doesNotContain("DELETE");
        assertThat(sql).doesNotContainPattern("\\b(payments|bnpl|merchant|checkout|ledger|identity)\\.[a-z_]+")
                .doesNotContainPattern("amount|balance");
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

    private static List<String> quoted(String list) {
        List<String> values = new ArrayList<>();
        Matcher quoted = Pattern.compile("'([A-Z_]+)'").matcher(list);
        while (quoted.find()) {
            values.add(quoted.group(1));
        }
        return values;
    }

    /** The migration with its comment lines removed - what executes. Its COMMENT ON strings stay. */
    private static String statements(String resource) {
        try (InputStream stream = RiskAbuseMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + resource);
            }
            String executed = new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.stripLeading().startsWith("--"))
                    .collect(Collectors.joining("\n"));
            // A COMMENT ON's text is documentation, not a statement's body: dropped whole.
            return executed.replaceAll("(?s)COMMENT ON [^;]*? IS\\s*'(?:[^']|'')*';", "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
