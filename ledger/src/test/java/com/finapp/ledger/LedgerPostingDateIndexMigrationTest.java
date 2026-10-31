package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ledger {@code V029} - the journal by posting date (`P14-TSK-007`; ADR-0113's Consequences): exactly one index,
 * {@code journal_entry (posting_date, id)} in that order - the GL's period range and its drill-down's keyset order - a
 * name new to the ledger, and nothing else: no column, constraint, trigger or grant. The planner using it is
 * {@code GlReadModelDatabaseTest#theMonthReadUsesThePostingDateIndex}'s (owed, C5).
 */
@DisplayName("the journal by posting date - ledger V029 (P14-TSK-007)")
class LedgerPostingDateIndexMigrationTest {

    private static final String DIRECTORY = "db/migration/ledger";
    private static final String MIGRATION = DIRECTORY + "/V029__the_journal_by_posting_date.sql";

    @Test
    @DisplayName("one index on journal_entry, posting_date then id - the range first, the keyset's tie-break second")
    void theIndexIsPostingDateThenId() {
        assertThat(statements(MIGRATION).strip())
                .isEqualTo("CREATE INDEX journal_entry_by_posting_date ON ledger.journal_entry (posting_date, id);");
    }

    @Test
    @DisplayName("nothing but the index: no grant, no column, no constraint, no trigger, not CONCURRENTLY (the migration's"
            + " own transaction)")
    void nothingElseChanges() {
        String statements = statements(MIGRATION);
        for (String forbidden : List.of("GRANT", "REVOKE", "ALTER", "CONSTRAINT", "TRIGGER", "CONCURRENTLY", "DROP",
                "UPDATE", "DELETE", "INSERT")) {
            assertThat(statements).as("V029 does not %s", forbidden).doesNotContain(forbidden);
        }
    }

    @Test
    @DisplayName("the index's name is new to the ledger - no earlier migration created it")
    void theNameIsNew() throws IOException, URISyntaxException {
        URL directory = LedgerPostingDateIndexMigrationTest.class.getClassLoader().getResource(DIRECTORY);
        assertThat(directory).isNotNull();
        try (Stream<Path> files = Files.list(Path.of(directory.toURI()))) {
            List<String> earlier = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("V0\\d\\d__.*\\.sql") && name.compareTo("V029__") < 0)
                    .sorted().toList();
            assertThat(earlier).hasSize(28).last().asString().startsWith("V028__");
            for (String name : earlier) {
                assertThat(statements(DIRECTORY + "/" + name)).doesNotContain("journal_entry_by_posting_date");
            }
        }
    }

    private static String statements(String resource) {
        try (InputStream in = LedgerPostingDateIndexMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("%s on the test classpath", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.stripLeading().startsWith("--"))
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
