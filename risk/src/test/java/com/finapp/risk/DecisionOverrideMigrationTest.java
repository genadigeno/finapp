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
 * {@code risk V009}'s text held to the code (`P13-TSK-012`; the migration-test idiom, hermetic - the live machine is
 * {@code DecisionOverrideDatabaseTest}'s and the live grants {@code RiskMigrationTest}'s, written and owed under C5):
 * the effects and outcomes are the enums', V007's function is replaced by name and V007 itself untouched, the machine's
 * every rule present, the grant a column-scoped {@code UPDATE} with no {@code DELETE}, every new name held once across
 * every risk migration (the Phase 12 V012 lesson), and no other schema named ({@code INV-RSK-02}).
 */
@DisplayName("risk V009 gives the override its machine as the code declares it (P13-TSK-012)")
class DecisionOverrideMigrationTest {

    private static final String RESOURCE = "db/migration/risk/V009__the_decision_override_machine.sql";

    private static final List<String> EARLIER = List.of(
            "db/migration/risk/V001__initialise_risk_schema.sql",
            "db/migration/risk/V002__risk_policy_versions_as_data.sql",
            "db/migration/risk/V003__risk_signal.sql",
            "db/migration/risk/V004__monitored_transactions_and_the_watermark.sql",
            "db/migration/risk/V005__velocity_windows.sql",
            "db/migration/risk/V006__the_customer_risk_profile.sql",
            "db/migration/risk/V007__the_sealed_assessment.sql",
            "db/migration/risk/V008__the_held_card_payment.sql");

    private static final Pattern ANOTHER_SCHEMA = Pattern.compile("\\b(lending|credit|ledger|platform|checkout|merchant"
            + "|identity|accounts|payments|paymentmethods|bnpl|kyc|party|transfers|crossborder|settlement"
            + "|reconciliation|fx|consent|public)\\.[a-z_]+");

    @Test
    @DisplayName("the vocabularies are the enums': the effects, the outcomes, and the effect each decision admits")
    void theListsAreTheEnums() {
        String sql = collapsed(statements(RESOURCE));
        assertThat(sql).contains("CHECK (effect IN (" + list(OverrideEffect.values()) + "))")
                .contains("action_outcome IN (" + list(ActionOutcome.values()) + ")");
        for (RiskOperation operation : RiskOperation.values()) {
            for (RiskDecision decision : RiskDecision.values()) {
                if (!operation.judgesAMovement() || !decision.isAdmittedBy(operation)) {
                    continue;
                }
                for (OverrideResolution resolution : OverrideResolution.values()) {
                    OverrideEffect.of(operation, decision, resolution).ifPresent(effect -> {
                        if (effect == OverrideEffect.RESUBMISSION) {
                            assertThat(sql).contains("ELSIF judged.decision IN ('DENY', 'REVIEW') THEN admitted :="
                                    + " ARRAY['RESUBMISSION'];");
                        } else {
                            assertThat(sql).as(operation + " " + decision + " " + resolution).containsPattern(
                                    "judged.decision = 'HOLD' AND judged.operation = '" + operation.name()
                                            + "' THEN admitted := ARRAY\\[[^\\]]*'" + effect.name() + "'");
                        }
                    });
                }
            }
        }
    }

    @Test
    @DisplayName("V007's function is replaced by name - its triggers keep calling it - and V007 is never edited: its"
            + " four-eyes CHECK and live-proposal index still stand as written")
    void v007IsReplacedByNameNeverEdited() {
        String sql = collapsed(statements(RESOURCE));
        assertThat(sql).contains("CREATE OR REPLACE FUNCTION risk.decision_override_permits_only_machine_edges()")
                .doesNotContain("CREATE TRIGGER").doesNotContain("DROP CONSTRAINT decision_override_four_eyes")
                .doesNotContain("DROP INDEX");
        String v007 = collapsed(statements(EARLIER.get(6)));
        assertThat(v007).contains("CHECK (status <> 'APPROVED' OR NOT requires_second_person OR decided_by <> proposed_by)")
                .contains("CREATE UNIQUE INDEX decision_override_one_live ON risk.decision_override (assessment_id)"
                        + " WHERE status = 'PROPOSED'");
    }

    @Test
    @DisplayName("the machine's rules are all present: the birth judged against the assessment (the decision, the"
            + " effect, the target, C7 failing closed), the proposal frozen, the decision reasoned and stamped, a"
            + " rejection issuing nothing, the action's permit and its one outcome, nothing deleted")
    void theMachinesRulesArePresent() {
        String sql = collapsed(statements(RESOURCE));
        assertThat(sql)
                .contains("IF NOT FOUND OR judged.decision NOT IN ('DENY', 'HOLD', 'REVIEW') THEN")
                .contains("NEW.target_id::text IS DISTINCT FROM judged.command_ref")
                .contains("demanded := COALESCE(judged.decision = 'DENY' OR judged.override_threshold_minor IS NULL"
                        + " OR judged.currency <> judged.policy_currency OR judged.scale <> judged.policy_scale"
                        + " OR judged.amount_minor >= judged.override_threshold_minor, true);")
                .contains("IF demanded AND NOT NEW.requires_second_person THEN")
                .contains("RAISE EXCEPTION 'an override''s proposal never changes (P13-TSK-012)'")
                .contains("NEW.decided_at := clock_timestamp();")
                .contains("RAISE EXCEPTION 'a rejection issues nothing - the decision stands'")
                .contains("RAISE EXCEPTION 'an approval issues exactly the effect proposed'")
                .contains("NEW.action_concluded_at := clock_timestamp();")
                .contains("RAISE EXCEPTION 'a decided override never moves (P13-TSK-012)'")
                .contains("RAISE EXCEPTION 'a decision override is never deleted or truncated (P13-TSK-004)';")
                .contains("CREATE UNIQUE INDEX decision_override_one_standing ON risk.decision_override (assessment_id)"
                        + " WHERE status IN ('PROPOSED', 'APPROVED');");
        assertThat(sql).doesNotContain("now()").doesNotContain("transaction_timestamp()");
    }

    @Test
    @DisplayName("the grant: a column-scoped UPDATE of the decision and the action's columns - never the instants, the"
            + " proposal or the effect - and no DELETE, no ALL, no default privilege")
    void theGrantIsColumnScoped() {
        String sql = statements(RESOURCE);
        List<String> grants = Arrays.stream(sql.split(";")).map(s -> s.strip().replaceAll("\\s+", " "))
                .filter(s -> s.startsWith("GRANT") || s.startsWith("REVOKE")).toList();
        assertThat(grants).containsExactly("GRANT UPDATE (status, decided_by, decided_by_type, decision_reason, action,"
                + " action_outcome, action_due_at, action_attempts) ON risk.decision_override TO finapp_app");
        assertThat(sql).doesNotContain("DELETE ON").doesNotContain("GRANT ALL").doesNotContain("ALTER DEFAULT PRIVILEGES")
                .doesNotContain("INSERT INTO risk.");
    }

    @Test
    @DisplayName("every constraint, index and trigger name V009 creates is new across V001 to V008, and the store's"
            + " SQL names columns V007 or V009 declares - no other schema anywhere")
    void namesAreUniqueAndNoOtherSchema() {
        Set<String> earlier = new HashSet<>();
        for (String migration : EARLIER) {
            earlier.addAll(names(statements(migration)));
        }
        List<String> v009 = names(statements(RESOURCE));
        assertThat(v009).as("the guard is not vacuous").hasSizeGreaterThanOrEqualTo(10);
        assertThat(v009).doesNotHaveDuplicates().noneMatch(earlier::contains)
                .allMatch(name -> name.startsWith("decision_override_"));
        String unquoted = statements(RESOURCE).replaceAll("'(?:[^']|'')*'", "''");
        assertThat(ANOTHER_SCHEMA.matcher(unquoted).find()).isFalse();
        // Since P13-TSK-017 the store reads the override (o: V007's and V009's columns) beside its issued action (a:
        // V013's risk_action - the four action columns V013 moved there).
        String tables = collapsed(statements(EARLIER.get(6)) + statements(RESOURCE));
        String actionTable = collapsed(statements("db/migration/risk/V013__downstream_risk_actions.sql"));
        Matcher selected = Pattern.compile("\\b([oa])\\.([a-z_]+)").matcher(JdbcDecisionOverrideStore.SELECT);
        int read = 0;
        while (selected.find()) {
            read++;
            assertThat(selected.group(1).equals("o") ? tables : actionTable).as(selected.group())
                    .containsPattern("\\b" + selected.group(2) + "\\s+(uuid|text|boolean|integer|timestamptz)");
        }
        assertThat(read).as("the guard is not vacuous").isGreaterThanOrEqualTo(20);
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
        return Arrays.stream(values).map(v -> "'" + v.name() + "'").collect(Collectors.joining(", "));
    }

    private static String collapsed(String sql) {
        return sql.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }

    /** The migration with its comment lines removed - what actually executes. */
    private static String statements(String resource) {
        try (InputStream in = DecisionOverrideMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.strip().startsWith("--"))
                    .collect(Collectors.joining("\n"));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
