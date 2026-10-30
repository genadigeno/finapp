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
 * Ledger {@code V028} - the posting cutoff - and its Java constants agree (`P14-TSK-004`, ADR-0114, {@code INV-ACC-08}):
 * namespace {@code 14} key {@code cutoff} pinned on both sides, the refusal's SQLSTATE, the trigger's shape, and every
 * constraint, trigger and index name new to the ledger. The behaviour under a live PostgreSQL is
 * {@code PostingCutoffDatabaseTest}'s (owed, C5).
 */
@DisplayName("the posting cutoff's schema and its constants agree (P14-TSK-004)")
class LedgerCutoffMigrationTest {

    private static final String DIRECTORY = "db/migration/ledger";
    private static final String MIGRATION = DIRECTORY + "/V028__the_posting_cutoff.sql";

    @Test
    @DisplayName("namespace 14 key cutoff is pinned: the move's exclusive lock, the posting's shared lock, never 'mapping'")
    void namespaceFourteenKeyCutoffIsPinned() {
        assertThat(PostingCutoffs.NAMESPACE).isEqualTo(14);
        assertThat(PostingCutoffs.KEY).isEqualTo("cutoff").isNotEqualTo("mapping");
        assertThat(PostingCutoffs.LOCK_SQL).isEqualTo("SELECT pg_advisory_xact_lock(14, hashtext('cutoff'))");
        assertThat(PostingCutoffs.SHARED_LOCK).isEqualTo("pg_advisory_xact_lock_shared(14, hashtext('cutoff'))");
        String statements = statements(MIGRATION);
        assertThat(statements)
                .as("the posting trigger takes the key shared; the move's trigger re-takes it exclusive")
                .contains("PERFORM " + PostingCutoffs.SHARED_LOCK + ";")
                .contains("PERFORM pg_advisory_xact_lock(14, hashtext('cutoff'));")
                .doesNotContain("'mapping'");
    }

    @Test
    @DisplayName("every journal_entry insert takes the shared lock BEFORE it reads the cutoff, and refuses on or before it")
    void theTriggerSerialisesThenRefusesOnOrBefore() {
        String trigger = function("journal_entry_respects_the_posting_cutoff");
        int lock = trigger.indexOf("PERFORM " + PostingCutoffs.SHARED_LOCK);
        int read = trigger.indexOf("FROM ledger.posting_cutoff WHERE id = 1");
        assertThat(lock).as("the shared lock is taken").isNotNegative();
        assertThat(read).as("the read follows the lock - a fresh snapshot after the grant").isGreaterThan(lock);
        assertThat(trigger)
                .as("on or before - the cutoff day itself is in the locked period (INV-ACC-03)")
                .contains("IF cut IS NOT NULL AND NEW.posting_date <= cut THEN")
                .contains("USING ERRCODE = '" + PostingCutoffs.PERIOD_CLOSED_SQLSTATE + "'")
                .as("a REPEATABLE READ poster reads FOR SHARE - a cut after its snapshot is 40001, never missed")
                .contains("IF current_setting('transaction_isolation') = 'read committed' THEN")
                .contains("WHERE id = 1 FOR SHARE;")
                .as("a missing row fails closed")
                .contains("IF NOT coalesce(present, false) THEN");
        assertThat(collapsed(MIGRATION))
                .contains("CREATE TRIGGER journal_entry_respects_the_posting_cutoff BEFORE INSERT ON ledger.journal_entry"
                        + " FOR EACH ROW EXECUTE FUNCTION ledger.journal_entry_respects_the_posting_cutoff();");
        assertThat(PostingCutoffs.PERIOD_CLOSED_SQLSTATE).hasSize(5).matches("[0-9A-Z]{5}");
    }

    @Test
    @DisplayName("the cutoff starts unset - nothing is refused until the first lock")
    void theCutoffStartsUnset() {
        assertThat(statements(MIGRATION))
                .contains("INSERT INTO ledger.posting_cutoff (id, closed_through, revision) VALUES (1, NULL, 0);");
        assertThat(collapsed(MIGRATION))
                .contains("closed_through date,")
                .contains("CONSTRAINT posting_cutoff_is_one_row CHECK (id = 1)");
    }

    @Test
    @DisplayName("every move is shaped, locked and evented at the schema: one period, one revision, one event")
    void everyMoveIsShapedAtTheSchema() {
        String shape = function("posting_cutoff_moves_one_period");
        assertThat(shape)
                .contains("PERFORM pg_advisory_xact_lock(14, hashtext('cutoff'));")
                .contains("NEW.revision IS DISTINCT FROM OLD.revision + 1")
                .contains("NEW.closed_through = ledger.posting_cutoff_month_end(OLD.closed_through + 1)")
                .contains("NEW.closed_through < (now() AT TIME ZONE 'UTC')::date")
                .contains("WHERE movement = 'ADVANCE' AND to_closed_through = OLD.closed_through")
                .contains(PostingCutoffs.REFUSED_MOVE_MARKER);
        assertThat(collapsed(MIGRATION))
                .contains("CREATE CONSTRAINT TRIGGER posting_cutoff_move_is_evented AFTER UPDATE ON ledger.posting_cutoff"
                        + " DEFERRABLE INITIALLY DEFERRED")
                .contains("CONSTRAINT posting_cutoff_event_one_per_revision UNIQUE (revision)")
                .contains("BEFORE DELETE ON ledger.posting_cutoff")
                .contains("BEFORE UPDATE OR DELETE ON ledger.posting_cutoff_event");
        assertThat(PostingCutoffs.MOVE)
                .as("the API's edge is conditional on the expected cutoff and moves the revision by one")
                .contains("closed_through IS NOT DISTINCT FROM ?")
                .contains("revision = revision + 1")
                .contains("RETURNING revision");
        for (PostingCutoffs.Movement movement : PostingCutoffs.Movement.values()) {
            assertThat(collapsed(MIGRATION)).contains("'" + movement.name() + "'");
        }
    }

    @Test
    @DisplayName("the grants are the moves only: no DELETE, UPDATE on the two moving columns, the journal's unchanged")
    void theGrantsAreTheMovesOnly() {
        List<String> grants = statements(MIGRATION).lines()
                .filter(line -> line.startsWith("GRANT") || line.startsWith("REVOKE"))
                .toList();
        assertThat(grants).containsExactly(
                "GRANT SELECT ON ledger.posting_cutoff TO finapp_app;",
                "GRANT UPDATE (closed_through, revision) ON ledger.posting_cutoff TO finapp_app;",
                "GRANT SELECT, INSERT ON ledger.posting_cutoff_event TO finapp_app;");
    }

    @Test
    @DisplayName("every constraint, trigger, index and function name V028 adds is new to the ledger and unique")
    void theNamesAreUnique() throws IOException, URISyntaxException {
        List<String> introduced = names(statements(MIGRATION));
        assertThat(introduced).doesNotHaveDuplicates().hasSizeGreaterThan(15);
        Set<String> earlier = new HashSet<>();
        for (String other : earlierMigrations()) {
            earlier.addAll(names(statements(other)));
        }
        assertThat(introduced).as("no name collides with V001..V027's").doesNotContainAnyElementsOf(earlier);
    }

    // -----------------------------------------------------------------

    /** A named object and its kind - a function and its trigger may share a name (the V009 precedent), two of a kind not. */
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
        URL directory = LedgerCutoffMigrationTest.class.getClassLoader().getResource(DIRECTORY);
        assertThat(directory).as("the ledger migrations on the test classpath").isNotNull();
        try (Stream<Path> files = Files.list(Path.of(directory.toURI()))) {
            List<String> earlier = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("V0\\d\\d__.*\\.sql") && !name.startsWith("V028__"))
                    .map(name -> DIRECTORY + "/" + name)
                    .sorted()
                    .toList();
            assertThat(earlier).hasSize(27);
            return earlier;
        }
    }

    /** One plpgsql function's body, from its CREATE to its closing dollar quote. */
    private static String function(String name) {
        String text = migration(MIGRATION);
        int start = text.indexOf("CREATE OR REPLACE FUNCTION ledger." + name + "()");
        assertThat(start).as("function %s exists", name).isNotNegative();
        int end = text.indexOf("$$;", start);
        return text.substring(start, end);
    }

    private static String migration(String resource) {
        try (InputStream in = LedgerCutoffMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
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
