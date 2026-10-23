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
 * {@code risk V005}'s text held to the code (`P13-TSK-006`; the migration-test idiom, hermetic - the live ranks are
 * {@code VelocityWindowDatabaseTest}'s and {@code RiskMigrationTest}'s, written and owed under C5): the value lists are
 * the enums', the counters only move forward and only on the database clock, the application's grant is the upsert's
 * two columns and nothing else, nothing is deleted, the parameter table's retyping is guarded, every name is held once,
 * and no other schema is named.
 */
@DisplayName("risk V005 holds the velocity windows' ranks as the code declares them (P13-TSK-006)")
class VelocityWindowMigrationTest {

    private static final String RESOURCE = "db/migration/risk/V005__velocity_windows.sql";

    @Test
    @DisplayName("the dimension and window lists are exactly VelocityDimension's and WindowKind's, on the table and on the"
            + " parameter table; the bounds are the engine's")
    void theListsAreTheEnums() {
        String sql = collapsed(statements());
        assertThat(sql).contains("CHECK (dimension IN (" + list(VelocityDimension.values()) + "))")
                .contains("CHECK (window_kind IN (" + list(WindowKind.values()) + "))")
                .contains("value_velocity_dimension IN (" + list(VelocityDimension.values()) + ")")
                .contains("CHECK (count BETWEEN 1 AND " + EvaluationInputs.MAX_COUNT + ")")
                .contains("CHECK (sum_minor BETWEEN 0 AND " + EvaluationInputs.MAX_MINOR + ")");
        for (WindowKind kind : WindowKind.values()) {
            long hours = kind.length().toHours();
            String length = hours == 1 ? "interval '1 hour'" : "interval '" + kind.length().toDays()
                    + (kind.length().toDays() == 1 ? " day'" : " days'");
            assertThat(sql).as(kind.name()).contains("WHEN '" + kind.name() + "' THEN " + length);
        }
        assertThat(sql).contains("date_bin(risk.velocity_window_length(window_kind), window_start, TIMESTAMPTZ '"
                + "1970-01-05 00:00:00+00')");
        assertThat(WindowStarts.ANCHOR).as("the SQL anchor and the Java one are one instant")
                .isEqualTo(java.time.OffsetDateTime.parse("1970-01-05T00:00:00+00:00").toInstant());
        assertThat(sql).contains("subject_ref ~ '" + DeviceHash.SHAPE.pattern() + "'");
    }

    @Test
    @DisplayName("forward only, for every role: born at 1; an update exactly +1 with a sum that never decreases and the"
            + " key, currency and scale unchanged; written only while the database clock is inside the window")
    void theCountersOnlyMoveForwardOnTheDatabaseClock() {
        String sql = collapsed(statements());
        assertThat(sql).contains("IF NEW.count <> 1 THEN")
                .contains("IF NEW.count <> OLD.count + 1 THEN")
                .contains("IF NEW.sum_minor < OLD.sum_minor THEN")
                .contains("OR NEW.currency IS DISTINCT FROM OLD.currency")
                .contains("counted_at timestamptz := clock_timestamp();")
                .contains("IF NEW.window_start > counted_at OR counted_at >= NEW.window_start"
                        + " + risk.velocity_window_length(NEW.window_kind) + interval '1 minute' THEN")
                .contains("NEW.last_counted_at := counted_at;")
                .contains("BEFORE INSERT OR UPDATE ON risk.velocity_window FOR EACH ROW")
                .contains("BEFORE DELETE ON risk.velocity_window FOR EACH ROW")
                .contains("BEFORE TRUNCATE ON risk.velocity_window FOR EACH STATEMENT");
        assertThat(sql).doesNotContain("now()").doesNotContain("transaction_timestamp()");
    }

    @Test
    @DisplayName("the application holds SELECT, INSERT and UPDATE of the two counters - the upsert's columns - and"
            + " nothing else; no DELETE, no default privilege, nothing seeded")
    void theGrantsAreTheUpsertsAlone() {
        String sql = statements();
        assertThat(grants(sql)).containsExactly(
                "GRANT SELECT, INSERT ON risk.velocity_window TO finapp_app",
                "GRANT UPDATE (count, sum_minor) ON risk.velocity_window TO finapp_app",
                "GRANT EXECUTE ON FUNCTION risk.velocity_window_length(text) TO finapp_app");
        assertThat(sql).doesNotContain("INSERT INTO risk.").doesNotContain("ALTER DEFAULT PRIVILEGES")
                .doesNotContain("GRANT DELETE").doesNotContain("GRANT TRUNCATE");
    }

    @Test
    @DisplayName("the parameter table is retyped behind a guard: a stored SUBJECT_KIND refuses the migration; the column"
            + " renamed; V002's two CHECKs dropped and re-made; V002 itself untouched")
    void theRetypingIsGuarded() {
        String sql = collapsed(statements());
        assertThat(sql).contains("IF EXISTS (SELECT 1 FROM risk.risk_rule_parameter WHERE type = 'SUBJECT_KIND') THEN"
                        + " RAISE EXCEPTION")
                .contains("RENAME COLUMN value_subject_kind TO value_velocity_dimension")
                .contains("CHECK (type IN (" + list(RuleKind.ParameterType.values()) + "))");
        assertThat(sql.indexOf("WHERE type = 'SUBJECT_KIND'"))
                .as("the guard runs before anything is altered")
                .isLessThan(sql.indexOf("DROP CONSTRAINT"));
        assertThat(Arrays.stream(RuleKind.ParameterType.values()).map(Enum::name)).doesNotContain("SUBJECT_KIND");
    }

    @Test
    @DisplayName("every constraint, index and trigger name is held once across the migration (the Phase 12 V012 lesson)"
            + " and no other schema is named")
    void namesAreUniqueAndNoOtherSchemaIsNamed() {
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
        assertThat(names).as("the guard is not vacuous").hasSizeGreaterThan(14);
        assertThat(names).doesNotHaveDuplicates();
        assertThat(names).allMatch(name -> name.startsWith("velocity_window_") || name.startsWith("risk_rule_parameter_"));
        Matcher schemas = Pattern.compile("\\b(lending|credit|ledger|platform|checkout|merchant|identity|accounts"
                + "|payments|bnpl|kyc|party|transfers|crossborder|public)\\.").matcher(statements());
        assertThat(schemas.results().map(java.util.regex.MatchResult::group).toList()).isEmpty();
    }

    // -----------------------------------------------------------------

    private static String list(Enum<?>[] values) {
        return Arrays.stream(values).map(v -> "'" + v.name() + "'").collect(Collectors.joining(", "));
    }

    private static String collapsed(String sql) {
        return sql.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }

    private static List<String> grants(String sql) {
        return Arrays.stream(sql.split(";"))
                .map(statement -> statement.strip().replaceAll("\\s+", " "))
                .filter(statement -> statement.startsWith("GRANT") || statement.startsWith("REVOKE"))
                .toList();
    }

    /** The migration with its comment lines removed - what actually executes. */
    static String statements() {
        return migration().lines()
                .filter(line -> !line.strip().startsWith("--"))
                .collect(Collectors.joining("\n"));
    }

    private static String migration() {
        try (InputStream in = VelocityWindowMigrationTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            assertThat(in).as(RESOURCE + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
