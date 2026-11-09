package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code risk V020} as written (`P15-TSK-037`; lending {@code V033}'s rule, {@code INV-RSK-05}, {@code INV-HIST-04}) - a text pin, which proves only the text: the
 * clauses run with the database's clock stepped back in {@code RiskStampOrderDatabaseTest}, owed to {@code P15-TST-002} (C14). It keeps the retirement's end after
 * the latest assessment, the successor's start on its predecessor's end by row order, and the assessed instant on or after its version's start from leaving
 * unnoticed, and holds that the replaced retirement differs from its {@code V002} predecessor in only the intended lines.
 */
@DisplayName("risk V020 as written - a text pin: a policy version ends after its assessments, its successor starts there (P15-TSK-037)")
class RiskPolicyClockMigrationTest {

    private static final String V002 = read("db/migration/risk/V002__risk_policy_versions_as_data.sql");
    private static final String V020 = read("db/migration/risk/V020__a_policy_version_ends_after_its_assessments_and_its_successor_starts_there.sql");

    @Test
    @DisplayName("the retirement ends the version one microsecond after its latest assessment, beside V002's three terms")
    void theRetirementEndsAfterTheLatestAssessment() {
        assertThat(V020).contains("NEW.effective_to := GREATEST(clock_timestamp(), transaction_timestamp(), OLD.effective_from + interval '1 microsecond',\n"
                + "                (SELECT max(a.assessed_at) FROM risk.risk_assessment a WHERE a.policy_version_id = OLD.id) + interval '1 microsecond');");
        assertThat(V020).contains("CREATE INDEX risk_assessment_by_policy_version ON risk.risk_assessment (policy_version_id, assessed_at);");
    }

    @Test
    @DisplayName("the successor starts exactly at its predecessor's end, the operation's highest-numbered RETIRED version - never the greatest end of the operation")
    void theSuccessorStartsExactlyThere() {
        assertThat(V020).contains("NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN COALESCE(\n"
                + "                (SELECT v.effective_to FROM risk.risk_policy_version v\n"
                + "                  WHERE v.operation = NEW.operation AND v.status = 'RETIRED' AND v.version < NEW.version\n"
                + "                  ORDER BY v.version DESC LIMIT 1),\n"
                + "                transaction_timestamp()) END;");
        assertThat(V020).doesNotContain("max(v.effective_to)");
    }

    @Test
    @DisplayName("an assessment's instant is never before its pinned version's start - V007's clock otherwise; an unavailable evaluation pins none")
    void theAssessmentIsNeverBeforeItsVersion() {
        assertThat(V020).contains("SELECT v.effective_from INTO version_start\n"
                + "      FROM risk.risk_policy_version v WHERE v.id = NEW.policy_version_id;")
                .contains("NEW.assessed_at := GREATEST(clock_timestamp(), version_start);");
    }

    @Test
    @DisplayName("the replaced retirement differs from V002's body in only the intended lines; V020 replaces exactly the two functions it names and adds no grant or data")
    void itReplacesOnlyWhatItNames() {
        assertThat(V020.split("CREATE OR REPLACE FUNCTION ", -1)).hasSize(3);
        assertThat(V020).doesNotContain("GRANT ").doesNotContain("DROP ").doesNotContain("INSERT INTO").doesNotContain("ALTER TABLE");

        String predecessor = bodyOf(V002, "risk.risk_policy_version_permits_only_machine_edges");
        String replaced = bodyOf(V020, "risk.risk_policy_version_permits_only_machine_edges");
        List<String> removed = diff(predecessor, replaced);
        List<String> added = diff(replaced, predecessor);
        assertThat(removed).containsExactly(
                "NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN GREATEST(transaction_timestamp(),",
                "(SELECT max(v.effective_to) FROM risk.risk_policy_version v WHERE v.operation = NEW.operation)) END;",
                "NEW.effective_to := GREATEST(clock_timestamp(), transaction_timestamp(), OLD.effective_from + interval '1 microsecond');");
        assertThat(added).containsExactly(
                "NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN COALESCE(",
                "(SELECT v.effective_to FROM risk.risk_policy_version v",
                "WHERE v.operation = NEW.operation AND v.status = 'RETIRED' AND v.version < NEW.version",
                "ORDER BY v.version DESC LIMIT 1),",
                "transaction_timestamp()) END;",
                "NEW.effective_to := GREATEST(clock_timestamp(), transaction_timestamp(), OLD.effective_from + interval '1 microsecond',",
                "(SELECT max(a.assessed_at) FROM risk.risk_assessment a WHERE a.policy_version_id = OLD.id) + interval '1 microsecond');");
    }

    /** The function's body, comments out: what actually runs. */
    private static String bodyOf(String migration, String name) {
        Matcher matcher = Pattern.compile("(?s)CREATE OR REPLACE FUNCTION " + Pattern.quote(name) + "\\(\\).*?\\$\\$(.*?)\\$\\$;").matcher(migration);
        assertThat(matcher.find()).as("the function " + name + " is in the migration").isTrue();
        return matcher.group(1);
    }

    /** The trimmed, non-comment, non-blank lines of {@code left} that {@code right} does not hold, in {@code left}'s order. */
    private static List<String> diff(String left, String right) {
        List<String> onlyLeft = new ArrayList<>();
        for (String line : left.split("\n", -1)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("--") && !right.contains(trimmed)) {
                onlyLeft.add(trimmed);
            }
        }
        return onlyLeft;
    }

    private static String read(String resource) {
        try (InputStream stream = RiskPolicyClockMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
