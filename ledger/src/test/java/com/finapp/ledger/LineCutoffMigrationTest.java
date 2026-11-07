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
 * Ledger {@code V032} - a journal line respects the posting cutoff (`P14-DOC-001`, the exit review's financial audit, F1;
 * {@code INV-ACC-03}, {@code INV-ACC-08}): every {@code journal_line} insert reads its entry's posting date and refuses it as
 * {@code V028} refuses an entry - the same key, taken shared before the read, the same {@code LDCUT}, the same fail-closed
 * missing row - so a balancing pair appended by raw SQL to an entry of a locked month is refused too. The behaviour under a
 * live PostgreSQL is {@code PostingCutoffDatabaseTest#aLineAppendedToALockedMonthsEntryIsRefused}.
 */
@DisplayName("a journal line respects the posting cutoff as an entry does (P14-DOC-001)")
class LineCutoffMigrationTest {

    private static final String DIRECTORY = "db/migration/ledger";
    private static final String MIGRATION = DIRECTORY + "/V032__a_line_respects_the_posting_cutoff.sql";

    @Test
    @DisplayName("every journal_line insert takes the shared key BEFORE it reads the cutoff, and refuses its entry's date on or"
            + " before it, as V028 refuses an entry's")
    void theLineTriggerSerialisesThenRefusesOnOrBefore() {
        String trigger = function("journal_line_respects_the_posting_cutoff");
        int lock = trigger.indexOf("PERFORM " + PostingCutoffs.SHARED_LOCK);
        int dated = trigger.indexOf("SELECT posting_date INTO dated FROM ledger.journal_entry WHERE id = NEW.entry_id");
        int read = trigger.indexOf("FROM ledger.posting_cutoff WHERE id = 1");
        assertThat(lock).as("the shared lock is taken").isNotNegative();
        assertThat(dated).as("the entry's date is read").isGreaterThan(lock);
        assertThat(read).as("the cutoff read follows the lock - a fresh snapshot after the grant").isGreaterThan(lock);
        assertThat(trigger)
                .as("on or before - the cutoff day itself is in the locked period (INV-ACC-03)")
                .contains("IF dated IS NOT NULL AND cut IS NOT NULL AND dated <= cut THEN")
                .contains("USING ERRCODE = '" + PostingCutoffs.PERIOD_CLOSED_SQLSTATE + "'")
                .as("a REPEATABLE READ writer reads FOR SHARE - a cut after its snapshot is 40001, never missed")
                .contains("IF current_setting('transaction_isolation') = 'read committed' THEN")
                .contains("WHERE id = 1 FOR SHARE;")
                .as("a missing row fails closed")
                .contains("IF NOT coalesce(present, false) THEN");
        assertThat(collapsed(MIGRATION))
                .contains("CREATE TRIGGER journal_line_respects_the_posting_cutoff BEFORE INSERT ON ledger.journal_line"
                        + " FOR EACH ROW EXECUTE FUNCTION ledger.journal_line_respects_the_posting_cutoff();");
    }

    @Test
    @DisplayName("V032 grants nothing and creates no table: the one trigger and its function")
    void itGrantsNothing() {
        String statements = statements(MIGRATION);
        assertThat(statements).doesNotContain("GRANT").doesNotContain("REVOKE").doesNotContain("CREATE TABLE")
                .doesNotContain("'mapping'");
        assertThat(names(statements)).containsExactly("FUNCTION journal_line_respects_the_posting_cutoff",
                "TRIGGER journal_line_respects_the_posting_cutoff");
    }

    @Test
    @DisplayName("every name V032 adds is new to the ledger")
    void theNamesAreNew() throws IOException, URISyntaxException {
        List<String> introduced = names(statements(MIGRATION));
        Set<String> earlier = new HashSet<>();
        for (String other : earlierMigrations()) {
            earlier.addAll(names(statements(other)));
        }
        assertThat(introduced).as("no name collides with V001..V031's").doesNotContainAnyElementsOf(earlier);
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
        URL directory = LineCutoffMigrationTest.class.getClassLoader().getResource(DIRECTORY);
        assertThat(directory).as("the ledger migrations on the test classpath").isNotNull();
        try (Stream<Path> files = Files.list(Path.of(directory.toURI()))) {
            List<String> earlier = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("V0\\d\\d__.*\\.sql") && name.compareTo("V032__") < 0)
                    .map(name -> DIRECTORY + "/" + name)
                    .sorted()
                    .toList();
            assertThat(earlier).hasSize(31);
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
        try (InputStream in = LineCutoffMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
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
