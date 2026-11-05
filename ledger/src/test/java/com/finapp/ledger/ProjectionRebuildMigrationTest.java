package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ledger {@code V031} - the projection rebuild's record - and its Java agree (`P14-TSK-026`, ADR-0119 decision 1;
 * {@code INV-AUD-04}, {@code INV-LED-03}, {@code INV-LED-04}): the status list is the enum's, four eyes is a {@code CHECK},
 * one standing rebuild per account, the payload frozen, the grants exactly the decision's columns and no {@code DELETE},
 * and nothing granted on the journal or the projection changes. The behaviour under a live PostgreSQL is
 * {@code ProjectionRebuildDatabaseTest}'s (owed, C5).
 */
@DisplayName("the projection rebuild's schema and its Java agree (P14-TSK-026)")
class ProjectionRebuildMigrationTest {

    private static final String DIRECTORY = "db/migration/ledger";
    private static final String MIGRATION = DIRECTORY + "/V031__projection_rebuild.sql";

    @Test
    @DisplayName("the status list is ProjectionRebuildStatus; the approver is not the proposer for both approvals, a"
            + " withdrawal stays the proposer's")
    void statusesAndFourEyes() {
        String sql = collapsed(MIGRATION);
        String statuses = Arrays.stream(ProjectionRebuildStatus.values()).map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
        assertThat(sql).contains("CHECK (status IN (" + statuses + "))")
                .contains("CONSTRAINT projection_rebuild_approver_is_not_proposer CHECK (status NOT IN ('APPLIED',"
                        + " 'UNCHANGED') OR decided_by <> proposed_by)");
        assertThat(ProjectionRebuildStatus.APPLIED.approved()).isTrue();
        assertThat(ProjectionRebuildStatus.UNCHANGED.approved()).isTrue();
        assertThat(ProjectionRebuildStatus.REJECTED.approved()).isFalse();
    }

    @Test
    @DisplayName("one standing rebuild per account; the currency bound to the account's; UNCHANGED found agreement and"
            + " APPLIED moved the row")
    void oneStandingAndTheFiguresCohere() {
        String sql = collapsed(MIGRATION);
        assertThat(sql).contains("CREATE UNIQUE INDEX projection_rebuild_one_standing_per_account ON ledger.projection_rebuild"
                        + " (ledger_account_id) WHERE status = 'PROPOSED'")
                .contains("FOREIGN KEY (ledger_account_id, currency) REFERENCES ledger.ledger_account (id, currency)")
                .contains("CONSTRAINT projection_rebuild_unchanged_found_agreement")
                .contains("CONSTRAINT projection_rebuild_applied_moved_the_row")
                .contains("after_seq IS NULL OR after_seq >= 1");
    }

    @Test
    @DisplayName("the proposal's payload is frozen, the edges are the machine's, nothing is ever deleted or truncated")
    void thePayloadIsFrozen() {
        String sql = statements(MIGRATION);
        for (String frozen : List.of("ledger_account_id", "currency", "scale", "reason", "proposed_by", "proposed_at",
                "observed_row", "observed_posted_minor", "observed_holds_minor", "observed_seq",
                "observed_derived_posted_minor", "observed_derived_holds_minor", "observed_derived_seq")) {
            assertThat(sql).as(frozen).contains("NEW." + frozen + " IS DISTINCT FROM OLD." + frozen);
        }
        assertThat(sql).contains("IF NOT (OLD.status = 'PROPOSED' AND NEW.status IN ('APPLIED', 'UNCHANGED', 'REJECTED'))")
                .contains("BEFORE UPDATE OR DELETE ON ledger.projection_rebuild")
                .contains("BEFORE TRUNCATE ON ledger.projection_rebuild");
    }

    @Test
    @DisplayName("the grants: SELECT and INSERT, UPDATE of the decision and approval columns only, no DELETE - and no grant"
            + " on the journal or the projection: the rebuild writes V006's four columns and reads the journal")
    void theGrants() {
        String sql = collapsed(MIGRATION);
        assertThat(sql).contains("GRANT SELECT, INSERT ON ledger.projection_rebuild TO finapp_app;")
                .contains("GRANT UPDATE (status, decided_by, decided_at, before_row, before_posted_minor, before_holds_minor,"
                        + " before_seq, after_posted_minor, after_holds_minor, after_seq) ON ledger.projection_rebuild TO"
                        + " finapp_app;")
                .doesNotContain("ON ledger.journal_entry")
                .doesNotContain("ON ledger.journal_line")
                .doesNotContain("ON ledger.account_balance");
        Matcher grants = Pattern.compile("GRANT ([^;]*);").matcher(sql);
        int count = 0;
        while (grants.find()) {
            count++;
            assertThat(grants.group(1)).contains("ON ledger.projection_rebuild").doesNotContain("DELETE")
                    .doesNotContain("TRUNCATE");
        }
        assertThat(count).isEqualTo(2);
        // The JDBC store writes exactly the granted decision columns.
        for (String column : List.of("status", "decided_by", "decided_at", "before_row", "before_posted_minor",
                "before_holds_minor", "before_seq", "after_posted_minor", "after_holds_minor", "after_seq")) {
            assertThat(JdbcProjectionRebuildStore.DECIDE_SQL).contains(column + " = ?");
        }
    }

    @Test
    @DisplayName("every constraint, trigger, index and function name V031 adds is new to the ledger and unique")
    void theNamesAreUnique() throws IOException, URISyntaxException {
        List<String> introduced = names(statements(MIGRATION));
        assertThat(introduced).doesNotHaveDuplicates().hasSizeGreaterThan(15);
        Set<String> earlier = new HashSet<>();
        for (String other : earlierMigrations()) {
            earlier.addAll(names(statements(other)));
        }
        assertThat(introduced).as("no name collides with V001..V030's").doesNotContainAnyElementsOf(earlier);
    }

    // -----------------------------------------------------------------

    private static final Pattern NAMED = Pattern.compile(
            "(CONSTRAINT|CREATE TRIGGER|CREATE CONSTRAINT TRIGGER|CREATE INDEX|CREATE UNIQUE INDEX"
                    + "|CREATE OR REPLACE FUNCTION|CREATE TABLE)\\s+(?:ledger\\.)?([a-z_]+)");

    private static List<String> names(String statements) {
        List<String> found = new ArrayList<>();
        Matcher matcher = NAMED.matcher(statements);
        while (matcher.find()) {
            String kind = matcher.group(1).replace("CREATE ", "").replace("OR REPLACE ", "").replace("UNIQUE ", "")
                    .replace("CONSTRAINT TRIGGER", "TRIGGER");
            found.add(kind + " " + matcher.group(2));
        }
        return found;
    }

    private static List<String> earlierMigrations() throws IOException, URISyntaxException {
        URL directory = ProjectionRebuildMigrationTest.class.getClassLoader().getResource(DIRECTORY);
        assertThat(directory).as("the ledger migrations on the test classpath").isNotNull();
        try (Stream<Path> files = Files.list(Path.of(directory.toURI()))) {
            List<String> earlier = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("V0\\d\\d__.*\\.sql") && name.compareTo("V031__") < 0)
                    .map(name -> DIRECTORY + "/" + name)
                    .sorted()
                    .toList();
            assertThat(earlier).hasSize(30);
            return earlier;
        }
    }

    private static String migration(String resource) {
        try (InputStream in = ProjectionRebuildMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("%s on the test classpath", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The executable lines, comments removed. */
    private static String statements(String resource) {
        return migration(resource).lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .map(line -> line.contains(" -- ") ? line.substring(0, line.indexOf(" -- ")) : line)
                .collect(Collectors.joining("\n"));
    }

    private static String collapsed(String resource) {
        return statements(resource).replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }
}
