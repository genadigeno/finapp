package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code risk V003} held to the code (`P13-TSK-005`) - the migration-test idiom every schema-owning module keeps: the
 * SQL is read as text and its vocabularies, its born-once arbiter, its grant and its refusals are compared with what
 * {@link SignalKind}, {@link SubjectKind} and the design say. What the SQL does when it <em>runs</em> - the unique
 * arbitrating ten instances, the trigger refusing the migrator, the shape {@code CHECK} refusing an attribute outside
 * its kind - is the database tier's ({@code RiskMigrationTest}, {@code SignalIntakeDatabaseTest}), written and owed
 * under C5.
 */
@DisplayName("risk V003 - the born-once signal, held to the code (P13-TSK-005)")
class RiskSignalMigrationTest {

    private static final String MIGRATION = "db/migration/risk/V003__risk_signal.sql";

    private final String sql = statementsOf(migration());

    @Test
    @DisplayName("the kind CHECK admits exactly SignalKind, and the subject-kind CHECK exactly SubjectKind")
    void theVocabulariesAreTheCode() {
        assertThat(quotedList(sql, "risk_signal_kind_is_known CHECK \\(kind IN \\(([^)]*)\\)"))
                .containsExactlyElementsOf(names(SignalKind.values()));
        assertThat(quotedList(sql, "risk_signal_subject_kind_is_known CHECK \\(subject_kind IN \\(([^)]*)\\)"))
                .containsExactlyElementsOf(names(SubjectKind.values()));
    }

    @Test
    @DisplayName("each kind's subject in the SQL is its subject in the code")
    void eachKindsSubjectIsTheCodes() {
        for (SignalKind kind : SignalKind.values()) {
            assertThat(sql)
                    .as("%s is about a %s", kind, kind.subject())
                    .containsPattern("WHEN '" + kind.name() + "'\\s+THEN '" + kind.subject().name() + "'");
        }
    }

    @Test
    @DisplayName("each kind's attribute shape in the SQL names exactly the code's attributes, non-null, and counts them")
    void eachKindsShapeIsTheCodes() {
        Matcher shape = Pattern.compile(
                "risk_signal_attributes_match_kind CHECK \\(CASE kind(.*?)ELSE false", Pattern.DOTALL).matcher(sql);
        assertThat(shape.find()).as("the shape CHECK exists").isTrue();
        String cases = shape.group(1);
        for (SignalKind kind : SignalKind.values()) {
            Matcher arm = Pattern.compile("WHEN '" + kind.name() + "' THEN(.*?)(?=WHEN '|$)", Pattern.DOTALL)
                    .matcher(cases);
            assertThat(arm.find()).as("an arm for %s", kind).isTrue();
            String body = arm.group(1);
            for (SignalKind.Attribute attribute : kind.attributes()) {
                assertThat(body).as("%s requires %s", kind, attribute.name())
                        .containsPattern("(?<![a-z_])" + attribute.name() + " IS NOT NULL");
            }
            for (String column : SignalKind.ATTRIBUTE_COLUMNS) {
                if (kind.attribute(column).isEmpty()) {
                    assertThat(body).as("%s does not carry %s", kind, column)
                            .doesNotContainPattern("(?<![a-z_])" + column + " IS NOT NULL");
                }
            }
            assertThat(body).as("%s carries exactly %s attributes", kind, kind.attributes().size())
                    .containsPattern("= " + kind.attributes().size() + "\\s*$|= " + kind.attributes().size() + "\\s");
        }
    }

    @Test
    @DisplayName("born once: UNIQUE (source_event_id); a device is a hash and every other subject a UUID")
    void theArbiterAndTheDeviceRule() {
        assertThat(sql).contains("CONSTRAINT risk_signal_born_once UNIQUE (source_event_id)");
        assertThat(sql).contains("WHEN 'DEVICE' THEN subject_ref ~ '^[0-9a-f]{64}$'");
        assertThat(sql).contains("ELSE subject_ref ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'");
        assertThat(sql).contains("CREATE INDEX risk_signal_by_subject ON risk.risk_signal"
                + " (subject_kind, subject_ref, kind, observed_at DESC)");
    }

    @Test
    @DisplayName("INSERT-only for every writer: SELECT, INSERT granted and nothing else; UPDATE, DELETE and TRUNCATE refused by trigger")
    void insertOnlyForEveryWriter() {
        assertThat(sql).contains("GRANT SELECT, INSERT ON risk.risk_signal TO finapp_app");
        assertThat(sql).doesNotContainPattern("GRANT[^;]*(UPDATE|DELETE|TRUNCATE|ALL)");
        assertThat(sql).doesNotContain("ALTER DEFAULT PRIVILEGES");
        assertThat(sql).containsPattern("BEFORE UPDATE OR DELETE ON risk.risk_signal\\s+FOR EACH ROW\\s+EXECUTE FUNCTION"
                + " risk.risk_signal_is_never_edited\\(\\)");
        assertThat(sql).containsPattern("BEFORE TRUNCATE ON risk.risk_signal\\s+FOR EACH STATEMENT\\s+EXECUTE FUNCTION"
                + " risk.risk_signal_is_never_edited\\(\\)");
        assertThat(sql).contains("NEW.recorded_at := clock_timestamp()");
        assertThat(sql).doesNotContain("REFERENCES");
        assertThat(sql).doesNotContainPattern("amount|balance");
    }

    @Test
    @DisplayName("every constraint, index and trigger name is unique in the migration (the V012 class)")
    void everyNameIsUnique() {
        // A trigger and the function it executes share a name by the bnpl idiom (different catalogues); a CONSTRAINT
        // name used twice on one table is what PostgreSQL refuses at the second migration that uses it (23505).
        List<String> names = new ArrayList<>();
        Matcher named = Pattern.compile("(?:CONSTRAINT|CREATE INDEX|CREATE TRIGGER)\\s*(\\w+)").matcher(sql);
        while (named.find()) {
            names.add(named.group(1));
        }
        assertThat(names).hasSizeGreaterThan(15);
        assertThat(names).doesNotHaveDuplicates();
    }

    // -----------------------------------------------------------------

    private static List<String> quotedList(String sql, String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(sql);
        assertThat(matcher.find()).as("found: %s", regex).isTrue();
        List<String> values = new ArrayList<>();
        Matcher quoted = Pattern.compile("'([A-Z_]+)'").matcher(matcher.group(1));
        while (quoted.find()) {
            values.add(quoted.group(1));
        }
        return values;
    }

    private static List<String> names(Enum<?>[] values) {
        return java.util.Arrays.stream(values).map(Enum::name).toList();
    }

    /** The migration with its comment lines removed - what actually executes. */
    private static String statementsOf(String migration) {
        return migration.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    }

    private static String migration() {
        try (InputStream stream = RiskSignalMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (stream == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + MIGRATION);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
