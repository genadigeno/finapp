package com.finapp.platform.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the two sweep functions of platform {@code V012} select, read from the migration itself (`P15-TSK-029`).
 *
 * <p><strong>Hermetic on purpose, and parsed rather than searched.</strong> {@code RetentionSweepDatabaseTest} runs these against
 * PostgreSQL but only at the exit's database pass. A text search would pass on a phrase in a comment or inside an {@code OR}, so
 * comments are stripped, the {@code WHERE} clause is split into its top-level conjuncts, the set must be exactly the one below,
 * and each conjunct is then evaluated against rows by a small interpreter that refuses any form it does not know.
 */
@DisplayName("what the retention sweeps select, read from the migration (P15-TSK-029)")
class RetentionSweepMigrationTest {

    private static final String DIRECTORY = "platform/src/main/resources/db/migration/platform";

    private static final String MIGRATION = DIRECTORY + "/V012__retention_sweeps_of_the_outbox_and_the_inbox.sql";

    private static final String OUTBOX = "sweep_published_outbox_events";

    private static final String INBOX = "sweep_expired_inbox_messages";

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

    private static final Duration BOUND = Duration.ofDays(14);

    private static final String SQL = withoutComments(read(RepositoryPaths.locate(MIGRATION)));

    private static final String BY_BOUND = " < now() - (retain_for_ms * INTERVAL '1 millisecond')";

    private static final String NO_STANDING_PARK =
            "NOT EXISTS ( SELECT 1 FROM platform.inbox_parked_record p WHERE p.dedupe_key = o.event_id::text"
                    + " AND p.state NOT IN ('HANDLED', 'DISCARDED') )";

    @Test
    @DisplayName("the outbox's selection is exactly: published, published before the bound, and named by no standing parked record")
    void theOutboxSelectsOnPublication() {
        assertThat(conjuncts(OUTBOX))
                .containsExactlyInAnyOrder("o.published_at IS NOT NULL", "o.published_at" + BY_BOUND, NO_STANDING_PARK);
    }

    @Test
    @DisplayName("an outbox row a standing parked record names is never selected: a release republishes it, and an unknown state stands")
    void anOutboxRowAStandingParkedRecordNamesIsNeverSelected() {
        List<String> selection = conjuncts(OUTBOX);
        Map<String, Instant> old = row("published_at", NOW.minus(BOUND).minusMillis(1));

        assertThat(selected(selection, old, "FAILING")).as("still being retried").isFalse();
        assertThat(selected(selection, old, "PARKED")).as("waiting on a release or a discard").isFalse();
        assertThat(selected(selection, old, "A_STATE_ADDED_LATER")).as("a state this sweep was never told about").isFalse();
        assertThat(selected(selection, old, "HANDLED")).as("settled").isTrue();
        assertThat(selected(selection, old, "DISCARDED")).as("settled").isTrue();
        assertThat(selected(selection, old, null)).as("named by no parked record").isTrue();
    }

    @Test
    @DisplayName("the inbox's selection is exactly: past the written expiry, and processed before the retention in force")
    void theInboxSelectsOnBothBounds() {
        assertThat(conjuncts(INBOX)).containsExactlyInAnyOrder("m.expires_at < now()", "m.processed_at" + BY_BOUND);
    }

    @Test
    @DisplayName("a non-terminal outbox row is never selected: pending, abandoned, at any age; only a published row past its bound")
    void aNonTerminalRowIsNeverSelected() {
        List<String> selection = conjuncts(OUTBOX);
        Instant old = NOW.minus(Duration.ofDays(400));

        assertThat(selected(selection, Map.of())).as("pending, whatever its age").isFalse();
        assertThat(selected(selection, row("dead_lettered_at", old))).as("abandoned, whatever its age").isFalse();
        assertThat(selected(selection, row("published_at", NOW.minus(BOUND).plusMillis(1)))).as("published, inside the bound").isFalse();
        assertThat(selected(selection, row("published_at", NOW.minus(BOUND)))).as("published, exactly at the bound").isFalse();
        assertThat(selected(selection, row("published_at", NOW.minus(BOUND).minusMillis(1)))).as("published, past it").isTrue();
    }

    @Test
    @DisplayName("an inbox record younger than either bound is never selected, whichever way the retention moved")
    void anInboxRecordYoungerThanItsBoundIsNeverSelected() {
        List<String> selection = conjuncts(INBOX);
        Instant pastTheBound = NOW.minus(BOUND).minusMillis(1);
        Instant expired = NOW.minusMillis(1);

        assertThat(selected(selection, inbox(pastTheBound, expired))).as("past both").isTrue();
        assertThat(selected(selection, inbox(NOW.minus(BOUND).plusMillis(1), expired)))
                .as("expired on the retention it was written with, but the retention has since been raised")
                .isFalse();
        assertThat(selected(selection, inbox(pastTheBound, NOW.plusMillis(1))))
                .as("old enough for today's retention, but written under a longer one or extended by a replay order")
                .isFalse();
        assertThat(selected(selection, inbox(NOW.minus(BOUND), expired))).as("exactly at the bound").isFalse();
        assertThat(selected(selection, row("expires_at", expired))).as("no processing instant").isFalse();
    }

    @Test
    @DisplayName("an unreviewed inbox column stops the sweep before it selects, and the reviewed list is the table's real columns")
    void anUnknownStateIsNeverSelected() {
        String body = body(INBOX);
        Matcher guard = Pattern.compile("attname <> ALL \\(ARRAY\\[([^\\]]*)]::name\\[]\\)").matcher(body);
        assertThat(guard.find()).as("the guard compares every column with a list").isTrue();
        Set<String> reviewed = new TreeSet<>();
        for (String quoted : guard.group(1).split(",")) {
            reviewed.add(quoted.trim().replace("'", ""));
        }

        assertThat(reviewed)
                .as("a column added to platform.inbox_message may carry a state this sweep would delete across: review it, add a"
                        + " positive conjunct for its terminal values, and list it")
                .isEqualTo(columnsOfTheInbox());

        int raised = body.indexOf("USING ERRCODE = 'object_not_in_prerequisite_state'");
        assertThat(raised).as("the guard raises").isPositive();
        assertThat(raised).as("and it does so before anything is selected").isLessThan(body.indexOf("WITH doomed AS"));
        assertThat(body.substring(body.indexOf("IF EXISTS"), raised)).contains("RAISE EXCEPTION");
    }

    @Test
    @DisplayName("each sweep selects a bounded batch under SKIP LOCKED and deletes only from its own table, by the rows it locked")
    void eachSweepIsBatchedAndDisjoint() {
        for (Map.Entry<String, String> sweep : Map.of(OUTBOX, "outbox_event", INBOX, "inbox_message").entrySet()) {
            String body = body(sweep.getKey());
            String cte = cte(sweep.getKey());
            assertThat(cte).as(sweep.getKey()).contains("LIMIT batch_size").contains("SKIP LOCKED").contains("FROM platform." + sweep.getValue());
            assertThat(deletes(body)).as("one DELETE, from its own table, joined to what it locked")
                    .containsExactly("platform." + sweep.getValue());
            assertThat(body).contains("USING doomed").contains("batch_size NOT BETWEEN 1 AND 1000");
        }
    }

    @Test
    @DisplayName("the batch cap in the database is the one the code enforces")
    void theCapsAgree() {
        assertThat(body(OUTBOX)).contains("BETWEEN 1 AND " + RetentionSweep.MAX_BATCH_SIZE);
        assertThat(body(INBOX)).contains("BETWEEN 1 AND " + RetentionSweep.MAX_BATCH_SIZE);
    }

    @Test
    @DisplayName("each predicate has the index it needs: the ordering column, and the outbox's partial on the terminal condition")
    void theIndexesServeThePredicates() {
        assertThat(flat(SQL))
                .contains("CREATE INDEX outbox_event_sweepable ON platform.outbox_event (published_at) WHERE published_at IS NOT NULL;")
                .contains("CREATE INDEX inbox_message_sweepable ON platform.inbox_message (expires_at);");
        assertThat(cte(OUTBOX)).contains("ORDER BY o.published_at");
        assertThat(cte(INBOX)).contains("ORDER BY m.expires_at");
    }

    @Test
    @DisplayName("deletion is granted to the sweep's path only: definer functions, pinned search_path, no PUBLIC, DELETE revoked")
    void deletionIsTheFunctionsAlone() {
        for (String function : List.of(OUTBOX, INBOX)) {
            String definition = header(function);
            String signature = "platform." + function + "(bigint, integer)";
            assertThat(definition).contains("SECURITY DEFINER").contains("SET search_path = pg_catalog, pg_temp");
            assertThat(flat(SQL))
                    .contains("REVOKE ALL ON FUNCTION " + signature + " FROM PUBLIC;")
                    .contains("GRANT EXECUTE ON FUNCTION " + signature + " TO finapp_app;");
        }
        assertThat(flat(SQL))
                .contains("REVOKE DELETE ON platform.outbox_event FROM finapp_app;")
                .contains("REVOKE DELETE ON platform.inbox_message FROM finapp_app;");
    }

    // ------------------------------------------------------------------------------------------------------------------

    /** True when every conjunct holds for {@code row}; a form the interpreter does not know fails the test. */
    private static boolean selected(List<String> conjuncts, Map<String, Instant> row) {
        return selected(conjuncts, row, null);
    }

    /** {@code parkedState} is the state of the parked record naming the row, or {@code null} when none does. */
    private static boolean selected(List<String> conjuncts, Map<String, Instant> row, String parkedState) {
        Pattern notNull = Pattern.compile("\\w+\\.(\\w+) IS NOT NULL");
        Pattern beforeNow = Pattern.compile("\\w+\\.(\\w+) < now\\(\\)");
        Pattern beforeBound = Pattern.compile("\\w+\\.(\\w+)" + Pattern.quote(BY_BOUND));
        Pattern noStandingPark = Pattern.compile(
                "NOT EXISTS \\( SELECT 1 FROM platform\\.inbox_parked_record p WHERE p\\.dedupe_key = o\\.event_id::text"
                        + " AND p\\.state NOT IN \\(([^)]*)\\) \\)");
        for (String conjunct : conjuncts) {
            Matcher matched;
            boolean holds;
            if ((matched = noStandingPark.matcher(conjunct)).matches()) {
                holds = parkedState == null || Arrays.asList(matched.group(1).replace("'", "").split(",\\s*")).contains(parkedState);
            } else if ((matched = notNull.matcher(conjunct)).matches()) {
                holds = row.get(matched.group(1)) != null;
            } else if ((matched = beforeNow.matcher(conjunct)).matches()) {
                Instant value = row.get(matched.group(1));
                holds = value != null && value.isBefore(NOW);
            } else if ((matched = beforeBound.matcher(conjunct)).matches()) {
                Instant value = row.get(matched.group(1));
                holds = value != null && value.isBefore(NOW.minus(BOUND));
            } else {
                throw new AssertionError("a conjunct this test cannot evaluate, so the predicate needs reviewing: " + conjunct);
            }
            if (!holds) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Instant> row(String column, Instant value) {
        Map<String, Instant> row = new HashMap<>();
        row.put(column, value);
        return row;
    }

    private static Map<String, Instant> inbox(Instant processedAt, Instant expiresAt) {
        Map<String, Instant> row = row("processed_at", processedAt);
        row.put("expires_at", expiresAt);
        return row;
    }

    /** The top-level conjuncts of the sweep's selection, whitespace normalised. */
    private static List<String> conjuncts(String function) {
        String cte = cte(function);
        int where = cte.indexOf(" WHERE ");
        int orderBy = cte.indexOf(" ORDER BY ");
        assertThat(where).as("the selection has a WHERE clause").isPositive();
        assertThat(orderBy).as("and an ORDER BY after it").isGreaterThan(where);
        String clause = cte.substring(where + " WHERE ".length(), orderBy);
        assertThat(clause.toUpperCase(Locale.ROOT)).as("no disjunction, which could select around the allow-list").doesNotContain(" OR ");

        List<String> conjuncts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < clause.length(); i++) {
            char at = clause.charAt(i);
            depth += at == '(' ? 1 : at == ')' ? -1 : 0;
            if (depth == 0 && clause.startsWith(" AND ", i)) {
                conjuncts.add(clause.substring(start, i).trim());
                start = i + " AND ".length();
            }
        }
        conjuncts.add(clause.substring(start).trim());
        return conjuncts;
    }

    /** The {@code doomed} common table expression, on one line. */
    private static String cte(String function) {
        Matcher cte = Pattern.compile("WITH doomed AS \\((.*?)\\)\\s*DELETE", Pattern.DOTALL).matcher(body(function));
        assertThat(cte.find()).as("%s selects through the doomed CTE", function).isTrue();
        return flat(cte.group(1));
    }

    /** Every table a {@code DELETE FROM} in {@code body} names. */
    private static List<String> deletes(String body) {
        Matcher delete = Pattern.compile("DELETE\\s+FROM\\s+([\\w.]+)", Pattern.CASE_INSENSITIVE).matcher(body);
        List<String> tables = new ArrayList<>();
        while (delete.find()) {
            tables.add(delete.group(1));
        }
        return tables;
    }

    private static String body(String function) {
        int from = definition(function);
        int open = SQL.indexOf("$$", from);
        int close = SQL.indexOf("$$", open + 2);
        assertThat(open).as("%s has a body", function).isPositive();
        assertThat(close).as("%s's body ends", function).isGreaterThan(open);
        return SQL.substring(open + 2, close);
    }

    private static String header(String function) {
        int from = definition(function);
        return flat(SQL.substring(from, SQL.indexOf("$$", from)));
    }

    private static int definition(String function) {
        int from = SQL.indexOf("CREATE FUNCTION platform." + function + "(");
        assertThat(from).as("the migration defines %s", function).isGreaterThanOrEqualTo(0);
        return from;
    }

    /** The table's real columns: V007's definition, then every later migration's add, drop and rename, in version order. */
    private static Set<String> columnsOfTheInbox() {
        Pattern created = Pattern.compile("CREATE TABLE platform\\.inbox_message\\s*\\((.*?)\\n\\);", Pattern.DOTALL);
        Pattern altered = Pattern.compile("ALTER TABLE (?:IF EXISTS )?(?:ONLY )?platform\\.inbox_message\\b(.*?);", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        // ADD's COLUMN is optional in PostgreSQL, so "ADD parked_at ..." is read as well; ADD CONSTRAINT and its kin are not columns.
        Pattern added = Pattern.compile(
                "\\bADD\\s+(?:COLUMN\\s+)?(?:IF NOT EXISTS\\s+)?(?!CONSTRAINT\\b|PRIMARY\\b|UNIQUE\\b|CHECK\\b|FOREIGN\\b|EXCLUDE\\b)(\\w+)",
                Pattern.CASE_INSENSITIVE);
        Pattern dropped = Pattern.compile("DROP COLUMN (?:IF EXISTS )?(\\w+)", Pattern.CASE_INSENSITIVE);
        Pattern renamed = Pattern.compile("RENAME COLUMN (\\w+) TO (\\w+)", Pattern.CASE_INSENSITIVE);

        Set<String> columns = new TreeSet<>();
        boolean seen = false;
        Path directory = RepositoryPaths.locate(MIGRATION).getParent();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.getFileName().toString().matches("V\\d+__.*\\.sql")).sorted().toList()) {
                String sql = withoutComments(read(file));
                Matcher table = created.matcher(sql);
                if (table.find()) {
                    seen = true;
                    for (String item : topLevelItems(table.group(1))) {
                        String first = item.trim().split("\\s+")[0];
                        if (!Set.of("CONSTRAINT", "PRIMARY", "UNIQUE", "CHECK", "FOREIGN", "EXCLUDE", "LIKE").contains(first.toUpperCase(Locale.ROOT))) {
                            columns.add(first);
                        }
                    }
                }
                Matcher alter = altered.matcher(sql);
                while (alter.find()) {
                    String change = alter.group(1);
                    added.matcher(change).results().forEach(m -> columns.add(m.group(1)));
                    dropped.matcher(change).results().forEach(m -> columns.remove(m.group(1)));
                    renamed.matcher(change).results().forEach(m -> {
                        columns.remove(m.group(1));
                        columns.add(m.group(2));
                    });
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(seen).as("the migration creating platform.inbox_message was read").isTrue();
        assertThat(columns).as("the parser reached its columns").contains("consumer", "dedupe_key", "expires_at");
        return columns;
    }

    private static List<String> topLevelItems(String definition) {
        List<String> items = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < definition.length(); i++) {
            char at = definition.charAt(i);
            depth += at == '(' ? 1 : at == ')' ? -1 : 0;
            if (depth == 0 && at == ',') {
                items.add(definition.substring(start, i));
                start = i + 1;
            }
        }
        items.add(definition.substring(start));
        return items;
    }

    /** The text without {@code --} comments, none of which contain a quote that matters to the statements read here. */
    private static String withoutComments(String sql) {
        return String.join("\n", Arrays.stream(sql.split("\\R")).map(RetentionSweepMigrationTest::code).toList());
    }

    /** One line with any {@code --} comment outside a string literal removed. */
    private static String code(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char at = line.charAt(i);
            if (at == '\'') {
                inString = !inString;
            } else if (!inString && at == '-' && line.charAt(i + 1) == '-') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String flat(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
