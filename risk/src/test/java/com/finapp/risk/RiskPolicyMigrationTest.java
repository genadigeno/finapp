package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code risk V002}'s text held to the code (`P13-TSK-002`; the migration-test idiom - {@code BnplTermsMigrationTest}'s
 * statement reading - hermetic, so it runs on every build while the live ranks are {@code RiskPolicyDatabaseTest}'s,
 * written and owed under C5): the machine's value lists are the enums', the four-eyes {@code CHECK} and the one-ACTIVE
 * partial unique are present and no one-PROPOSED unique is (the entry's ten proposals), the successor trigger is
 * deferred, the content and the history are INSERT-only and the machine row updates only its decision columns, nothing
 * is seeded, every decision stamp is the instant after the lock, the engine named is this build's, no other schema is
 * named, every constraint name is held once (the Phase 12 V012 lesson), and advisory namespace 13 is the
 * administration's, pinned.
 */
@DisplayName("risk V002 holds the policy machine's ranks as the code declares them (P13-TSK-002)")
class RiskPolicyMigrationTest {

    private static final String RESOURCE = "db/migration/risk/V002__risk_policy_versions_as_data.sql";

    /** risk V005 (P13-TSK-006): the parameter table's type and value CHECKs re-made for the velocity dimension. */
    private static final String VELOCITY_RESOURCE = "db/migration/risk/V005__velocity_windows.sql";

    @Test
    @DisplayName("the status, operation, engine, kind, decision, parameter type, window, subject, assurance and rating"
            + " lists are exactly the enums' members")
    void theValueListsAreTheEnums() {
        String sql = statements();
        assertThat(sql).contains("CHECK (status IN (" + RiskPolicyVersionStatus.sqlValueList() + "))");
        String operations = Arrays.stream(RiskOperation.values()).filter(o -> o != RiskOperation.CREDIT_SIGNAL)
                .map(o -> "'" + o.name() + "'").collect(Collectors.joining(", "));
        assertThat(constraint("risk_policy_version_operation_carries_a_policy")).contains("operation IN (" + operations + ")");
        assertThat(constraint("risk_policy_version_engine_is_this_builds")).contains("engine IN (" + list(RiskEngine.values()) + ")");
        assertThat(collapsed("risk_rule_kind_is_known")).contains("kind IN (" + list(RuleKind.values()) + ")");
        assertThat(constraint("risk_rule_hard_decision_is_known")).contains("hard_decision IN (" + list(RiskDecision.values()) + ")");
        // The parameter table's two CHECKs as they stand: risk V005 (P13-TSK-006) dropped V002's and re-made them with
        // the velocity dimension in place of the subject kind - V002 is merged and never edited, so the effective
        // definition is the latest migration's, and THAT is held to the enums.
        assertThat(statements(VELOCITY_RESOURCE)).contains(
                "ALTER TABLE risk.risk_rule_parameter DROP CONSTRAINT risk_rule_parameter_type_is_known;",
                "ALTER TABLE risk.risk_rule_parameter DROP CONSTRAINT risk_rule_parameter_value_typed;");
        assertThat(collapsed(VELOCITY_RESOURCE, "risk_rule_parameter_type_is_known"))
                .contains("type IN (" + list(RuleKind.ParameterType.values()) + ")");
        String typed = collapsed(VELOCITY_RESOURCE, "risk_rule_parameter_value_typed");
        assertThat(typed).contains("value_window IN (" + list(WindowKind.values()) + ")")
                .contains("value_velocity_dimension IN (" + list(VelocityDimension.values()) + ")")
                .contains("value_assurance IN (" + list(RiskAssurance.values()) + ")")
                .contains("value_rating IN (" + list(RiskRating.values()) + ")");
        for (RuleKind.ParameterType type : RuleKind.ParameterType.values()) {
            assertThat(typed).as("every parameter type has its column").contains("(type = '" + type.name() + "') = (");
        }
        String bands = collapsed("risk_policy_band_shape_coherent");
        assertThat(bands).contains("decision IN (" + list(RiskDecision.values()) + ")")
                .contains("rating IN ('LOW', 'MEDIUM', 'HIGH')")
                .contains("decision IN ('ALLOW', 'DENY')");
        assertThat(sql).contains("BETWEEN 0 AND " + ScoreBand.CEILING)
                .contains("weight BETWEEN 1 AND " + ScoreBand.CEILING)
                .contains("hold_validity_hours BETWEEN " + RiskPolicy.MIN_HOLD_HOURS + " AND " + RiskPolicy.MAX_HOLD_HOURS);
    }

    @Test
    @DisplayName("four eyes by CHECK for every writer; one ACTIVE per operation by partial unique and NO one-PROPOSED"
            + " unique (ten proposals leave ten versions); the successor trigger deferred and holding a LATER successor")
    void fourEyesAndOneActivePerOperation() {
        String sql = statements();
        assertThat(sql).contains("CONSTRAINT risk_policy_version_four_eyes CHECK (status NOT IN ('ACTIVE', 'RETIRED') OR"
                + " decided_by <> proposed_by)");
        assertThat(sql).contains("CREATE UNIQUE INDEX risk_policy_one_active ON risk.risk_policy_version (operation) WHERE"
                + " status = 'ACTIVE'");
        assertThat(sql).doesNotContain("WHERE status = 'PROPOSED'");
        assertThat(sql).contains("DEFERRABLE INITIALLY DEFERRED").contains("retired with no later ACTIVE successor")
                .contains("successor.version > NEW.version");
        assertThat(sql).contains("CONSTRAINT risk_policy_version_number_unique UNIQUE (operation, version)");
    }

    @Test
    @DisplayName("each operation's knobs are its own: the sweep's version carries none, a judged version the assurance and"
            + " the threshold, the withdrawal alone the hold validity")
    void theKnobsAreTheOperations() {
        String knobs = collapsed("risk_policy_knobs_coherent");
        assertThat(knobs).contains("operation = 'MONITORING' AND challenge_assurance IS NULL AND override_threshold_minor IS"
                + " NULL AND hold_validity_hours IS NULL")
                .contains("(operation = 'WITHDRAWAL') = (hold_validity_hours IS NOT NULL)");
        assertThat(constraint("risk_policy_challenge_assurance_satisfies_a_challenge")).contains("= 'MULTI_FACTOR'");
        assertThat(constraint("risk_policy_currency_is_the_references")).contains("currency = 'EUR'");
    }

    @Test
    @DisplayName("the content and the history are INSERT-only; the machine row updates its decision columns alone;"
            + " nothing is seeded; no default privilege; no DELETE for any role")
    void theContentIsNeverUpdatedOrDeletedByAnyRole() {
        String sql = statements();
        assertThat(grants(sql)).containsExactlyInAnyOrder(
                "GRANT SELECT, INSERT ON risk.risk_policy_version TO finapp_app",
                "GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason, effective_from,"
                        + " effective_to) ON risk.risk_policy_version TO finapp_app",
                "GRANT SELECT, INSERT ON risk.risk_policy TO finapp_app",
                "GRANT SELECT, INSERT ON risk.risk_rule TO finapp_app",
                "GRANT SELECT, INSERT ON risk.risk_rule_parameter TO finapp_app",
                "GRANT SELECT, INSERT ON risk.risk_policy_band TO finapp_app",
                "GRANT SELECT, INSERT ON risk.risk_policy_version_event TO finapp_app");
        assertThat(sql).doesNotContain("INSERT INTO risk.").doesNotContain("ALTER DEFAULT PRIVILEGES")
                .doesNotContain("GRANT DELETE");
        assertThat(sql).contains("immutable from insert, for every role")
                .contains("BEFORE INSERT OR UPDATE OR DELETE ON risk.risk_policy\n")
                .contains("BEFORE INSERT OR UPDATE OR DELETE ON risk.risk_rule\n")
                .contains("BEFORE INSERT OR UPDATE OR DELETE ON risk.risk_rule_parameter\n")
                .contains("BEFORE INSERT OR UPDATE OR DELETE ON risk.risk_policy_band\n");
        assertThat(sql).contains("a risk policy version is born PROPOSED and undecided");
        assertThat(sql).contains("committed without its content").contains("committed without a rule")
                .contains("committed without its rating bands").contains("committed without its score and fail-safe bands");
    }

    @Test
    @DisplayName("every decision stamp is clock_timestamp() - the instant after the lock - and the effective period is"
            + " clamped on a backward clock (credit V014's rule, confirmed for this table)")
    void theDecisionIsStampedAfterTheLock() {
        String trigger = statements();
        assertThat(trigger).contains("NEW.decided_at := clock_timestamp();")
                .contains("NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN GREATEST(transaction_timestamp(),")
                .contains("NEW.effective_to := GREATEST(clock_timestamp(), transaction_timestamp(), OLD.effective_from"
                        + " + interval '1 microsecond');")
                .doesNotContain("NEW.decided_at := transaction_timestamp()");
    }

    @Test
    @DisplayName("the migration names no other schema - a precondition elsewhere is the domain's, through app's port -"
            + " and screens every reason with its own instrument-shape twin")
    void namesNoOtherSchema() {
        Matcher schemas = Pattern.compile("\\b(lending|credit|ledger|platform|checkout|merchant|identity|accounts"
                + "|payments|bnpl|kyc|party|transfers|crossborder|public)\\.").matcher(statements());
        assertThat(schemas.results().map(java.util.regex.MatchResult::group).toList()).isEmpty();
        assertThat(statements()).contains("risk.holds_instrument_shape(proposal_reason)")
                .contains("risk.holds_instrument_shape(decision_reason)")
                .contains("NOT risk.holds_instrument_shape(reason)");
    }

    @Test
    @DisplayName("every constraint, index and trigger name is held once across the migration (the Phase 12 V012 lesson)")
    void constraintNamesAreUnique() {
        List<String> names = new ArrayList<>();
        Matcher constraints = Pattern.compile("CONSTRAINT\\s+([a-z0-9_]+)\\s+(?:PRIMARY|UNIQUE|CHECK|FOREIGN)")
                .matcher(statements());
        while (constraints.find()) {
            names.add(constraints.group(1));
        }
        Matcher indexes = Pattern.compile("CREATE (?:UNIQUE )?INDEX\\s+([a-z0-9_]+)").matcher(statements());
        while (indexes.find()) {
            names.add(indexes.group(1));
        }
        Matcher triggers = Pattern.compile("CREATE (?:CONSTRAINT )?TRIGGER\\s+([a-z0-9_]+)").matcher(statements());
        while (triggers.find()) {
            names.add(triggers.group(1));
        }
        assertThat(names).as("the guard is not vacuous").hasSizeGreaterThan(40);
        assertThat(names).doesNotHaveDuplicates();
        assertThat(names).allMatch(name -> name.startsWith("risk_"));
    }

    @Test
    @DisplayName("advisory namespace 13 is the administration's, in its one statement, and the pin's shared re-read the"
            + " same key (DISTRIBUTED_EXECUTION.md section 3)")
    void namespaceThirteenIsPinned() {
        assertThat(JdbcRiskPolicyStore.NAMESPACE).isEqualTo(13);
        assertThat(JdbcRiskPolicyStore.LOCK_SQL).isEqualTo("SELECT pg_advisory_xact_lock(13, hashtext(?))");
        assertThat(JdbcRiskPolicyStore.SHARED_LOCK_SQL).isEqualTo("SELECT pg_advisory_xact_lock_shared(13, hashtext(?))");
        // The migration's own text names the namespace - in its commentary, the record a reviewer reads beside the
        // table; the statements hold no lock, so the pin is the Java constants above.
        assertThat(migration()).contains("advisory namespace 13");
    }

    // -----------------------------------------------------------------

    private static String list(Enum<?>[] values) {
        return Arrays.stream(values).map(v -> "'" + v.name() + "'").collect(Collectors.joining(", "));
    }

    /** {@code constraint(name)} with its whitespace collapsed and the list brackets closed up, for a one-line read. */
    private static String collapsed(String name) {
        return collapsed(RESOURCE, name);
    }

    private static String collapsed(String resource, String name) {
        return constraint(resource, name).replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }

    private static String constraint(String name) {
        return constraint(RESOURCE, name);
    }

    private static String constraint(String resource, String name) {
        String sql = statements(resource);
        int start = sql.indexOf("CONSTRAINT " + name + " CHECK");
        assertThat(start).as(name).isNotNegative();
        int next = sql.indexOf("CONSTRAINT ", start + 1);
        int end = sql.indexOf("\n);", start);
        if (next < 0 || (end >= 0 && end < next)) {
            next = end;
        }
        return sql.substring(start, next < 0 ? sql.length() : next);
    }

    private static List<String> grants(String sql) {
        return Arrays.stream(sql.split(";"))
                .map(statement -> statement.strip().replaceAll("\\s+", " "))
                .filter(statement -> statement.startsWith("GRANT") || statement.startsWith("REVOKE"))
                .toList();
    }

    /** The migration with its comment lines removed - what actually executes. */
    private static String statements() {
        return statements(RESOURCE);
    }

    private static String statements(String resource) {
        return migration(resource).lines()
                .filter(line -> !line.strip().startsWith("--"))
                .collect(Collectors.joining("\n"));
    }

    private static String migration() {
        return migration(RESOURCE);
    }

    private static String migration(String resource) {
        try (InputStream in = RiskPolicyMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
