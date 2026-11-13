package com.finapp.platform.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the idempotency record's sweep selects and what it waits for, read from platform {@code V016} itself (`P16-TSK-011`,
 * ADR-0141; {@code INV-IDEM-01}, {@code INV-LIFE-03}).
 *
 * <p><strong>Hermetic, and parsed rather than searched.</strong> The function's body is cut out of the file with its comments
 * stripped, its selection and its delete are each split into top-level conjuncts that must be exactly the ones below, and the
 * states it selects are compared with {@link IdempotencyState} itself, so a state added to the enum, or {@code IN_PROGRESS} added
 * to the list, turns this red. What PostgreSQL does with the statements is {@code IdempotencySweepDatabaseTest}'s, written and owed
 * to the database pass (`POSTPONED_ISSUES.md` PI-023).
 */
@DisplayName("what the idempotency sweep selects and what it waits for, read from the migration (P16-TSK-011)")
class IdempotencySweepMigrationTest {

    private static final String DIRECTORY = "platform/src/main/resources/db/migration/platform";

    private static final String MIGRATION = DIRECTORY + "/V016__the_idempotency_record_sweep.sql";

    private static final String SQL = withoutComments(read(RepositoryPaths.locate(MIGRATION)));

    private static final String BODY = body("sweep_expired_idempotency_records");

    private static final String BY_GRACE = "expires_at < pg_catalog.now() - (grace_ms * INTERVAL '1 millisecond')";

    @Test
    @DisplayName("the selection is exactly: a terminal state, and an expiry more than the grace behind the database's clock")
    void theSelectionIsExactlyTerminalAndPastTheGrace() {
        assertThat(conjuncts(between(BODY, "WITH doomed AS MATERIALIZED (", "ORDER BY"), "WHERE"))
                .containsExactlyInAnyOrder("r.state IN ('COMPLETED', 'FAILED')", "r." + BY_GRACE);
    }

    @Test
    @DisplayName("an in-flight claim is never selected: the states selected are exactly the enum's terminal ones")
    void anInFlightClaimIsNeverSelected() {
        Set<String> terminal = new TreeSet<>();
        for (IdempotencyState state : IdempotencyState.values()) {
            if (state.isTerminal()) {
                terminal.add(state.name());
            }
        }
        assertThat(statesIn(BODY)).as("every state list in the function").allSatisfy(states ->
                assertThat(states).isEqualTo(terminal).doesNotContain(IdempotencyState.IN_PROGRESS.name()));
        assertThat(statesIn(BODY)).as("the selection, the delete").hasSize(2);
        assertThat(statesIn(index())).as("the index's predicate is the selection's").containsExactly(terminal);
    }

    @Test
    @DisplayName("the delete re-judges the locked row by the same predicate, and takes only what the batch locked")
    void theDeleteReJudgesTheLockedRow() {
        assertThat(conjuncts(BODY.substring(BODY.indexOf("DELETE FROM")), "WHERE"))
                .containsExactlyInAnyOrder(
                        "d.scope = doomed.scope",
                        "d.idempotency_key = doomed.idempotency_key",
                        "d.state IN ('COMPLETED', 'FAILED')",
                        "d." + BY_GRACE);
    }

    @Test
    @DisplayName("each batch is bounded and disjoint: a materialised CTE locking FOR UPDATE SKIP LOCKED, at most the batch")
    void eachBatchIsBoundedAndDisjoint() {
        String selection = between(BODY, "WITH doomed AS MATERIALIZED (", "DELETE FROM");
        assertThat(normalised(selection)).contains("LIMIT batch_size FOR UPDATE OF r SKIP LOCKED )");
        assertThat(normalised(BODY)).contains("batch_size NOT BETWEEN 1 AND 1000").contains("ORDER BY r.expires_at");
        assertThat(RetentionSweep.MAX_BATCH_SIZE).isEqualTo(1000);
    }

    @Test
    @DisplayName("the grace has a floor of a day, the same in the function and at start, and a grace below it is refused at start")
    void theGraceHasTheSameFloorInBothPlaces() {
        Matcher floor = Pattern.compile("grace_ms < (\\d+)").matcher(BODY);
        assertThat(floor.find()).as("the function's floor").isTrue();
        assertThat(Duration.ofMillis(Long.parseLong(floor.group(1)))).isEqualTo(RetentionSweep.IDEMPOTENCY_GRACE_FLOOR)
                .isEqualTo(Duration.ofDays(1));
        assertThat(normalised(BODY)).contains("IF grace_ms IS NULL OR grace_ms < " + floor.group(1))
                .contains("USING ERRCODE = 'invalid_parameter_value'");

        RetentionConnectionSource never = () -> {
            throw new AssertionError("no connection at construction");
        };
        assertThatThrownBy(() -> RetentionSweep.ofExpiredIdempotencyRecords(never, Duration.ofHours(23), 500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PT24H");
        assertThat(RetentionSweep.ofExpiredIdempotencyRecords(never, Duration.ofDays(1), 500).table())
                .isEqualTo("idempotency_record");
        assertThat(RetentionSweep.ofExpiredIdempotencyRecords(never, Duration.ofDays(30), 500).bound())
                .isEqualTo(Duration.ofDays(30));
    }

    @Test
    @DisplayName("nothing is swept unless the journal's posting scope has a valid unique index on exactly that one column, read before the delete")
    void theSweepWaitsForThePostingScopesUniqueness() {
        String guard = normalised(between(BODY, "IF NOT EXISTS (", "END IF;"));
        assertThat(guard)
                .contains("FROM pg_catalog.pg_index i")
                .contains("i.indrelid = pg_catalog.to_regclass('ledger.journal_entry')")
                .contains("AND i.indisunique")
                .contains("AND i.indisvalid")
                .contains("AND i.indnatts = 1")
                .contains("AND i.indpred IS NULL")
                .contains("AND i.indexprs IS NULL")
                .contains("a.attnum = i.indkey[0]")
                .contains("AND a.attname = 'idempotency_scope'")
                .contains("USING ERRCODE = 'object_not_in_prerequisite_state'");
        assertThat(BODY.indexOf("IF NOT EXISTS (")).as("the guard comes before the delete").isLessThan(BODY.indexOf("DELETE FROM"));
    }

    @Test
    @DisplayName("a column the sweep was not reviewed against stops it, and the reviewed list is exactly the table's columns")
    void anUnreviewedColumnStopsTheSweep() {
        Matcher reviewed = Pattern.compile("a\\.attname <> ALL \\(ARRAY\\[([^\\]]+)\\]").matcher(BODY);
        assertThat(reviewed.find()).isTrue();
        Set<String> listed = new TreeSet<>();
        for (String name : reviewed.group(1).split(",")) {
            listed.add(name.trim().replace("'", ""));
        }
        assertThat(listed).isEqualTo(tableColumns());
        assertThat(normalised(between(BODY, "IF EXISTS (", "END IF;")))
                .contains("a.attrelid = 'platform.idempotency_record'::pg_catalog.regclass")
                .contains("USING ERRCODE = 'object_not_in_prerequisite_state'");
    }

    @Test
    @DisplayName("deletion is the function's alone: SECURITY DEFINER with its path pinned, EXECUTE to the application role only, its DELETE revoked")
    void deletionIsTheFunctionsAlone() {
        String header = normalised(between(SQL, "CREATE FUNCTION platform.sweep_expired_idempotency_records", "AS $$"));
        assertThat(header).contains("SECURITY DEFINER").contains("SET search_path = pg_catalog, pg_temp");
        String grants = normalised(SQL.substring(SQL.lastIndexOf("$$;")));
        assertThat(grants)
                .contains("REVOKE ALL ON FUNCTION platform.sweep_expired_idempotency_records(bigint, integer) FROM PUBLIC;")
                .contains("GRANT EXECUTE ON FUNCTION platform.sweep_expired_idempotency_records(bigint, integer) TO finapp_app;")
                .contains("REVOKE DELETE ON platform.idempotency_record FROM finapp_app;");
        assertThat(Pattern.compile("GRANT[^;]* ON (TABLE )?platform\\.idempotency_record\\b", Pattern.CASE_INSENSITIVE).matcher(grants).find())
                .as("no grant on the table itself").isFalse();
        assertThat(Pattern.compile("(?<![\\w.])now\\(\\)").matcher(BODY.replace("pg_catalog.now()", "")).find())
                .as("every clock call qualified").isFalse();
    }

    // ------------------------------------------------------------------------------------------------------------------

    /** The table's columns, from {@code V002}'s {@code CREATE TABLE} and every later {@code ADD COLUMN}, read from the files. */
    private static Set<String> tableColumns() {
        Set<String> columns = new TreeSet<>();
        String create = withoutComments(read(RepositoryPaths.locate(DIRECTORY + "/V002__create_idempotency_record.sql")));
        String table = between(create, "CREATE TABLE platform.idempotency_record", "CONSTRAINT idempotency_record_pk");
        Matcher column = Pattern.compile("(?m)^\\s+([a-z_]+)\\s+(TEXT|BYTEA|TIMESTAMPTZ)\\b").matcher(table);
        while (column.find()) {
            columns.add(column.group(1));
        }
        try (var files = Files.list(RepositoryPaths.locate(DIRECTORY + "/V002__create_idempotency_record.sql").getParent())) {
            for (Path file : files.sorted().toList()) {
                Matcher added = Pattern.compile("ALTER TABLE platform\\.idempotency_record\\s+ADD COLUMN ([a-z_]+)",
                        Pattern.CASE_INSENSITIVE).matcher(withoutComments(read(file)));
                while (added.find()) {
                    columns.add(added.group(1).toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(columns).as("the table is read, not assumed").contains("state", "expires_at", "lease_expires_at").hasSize(12);
        return columns;
    }

    /** Every {@code state IN (...)} list in {@code sql}, each as a set. */
    private static List<Set<String>> statesIn(String sql) {
        List<Set<String>> lists = new ArrayList<>();
        Matcher in = Pattern.compile("state IN \\(([^)]*)\\)").matcher(sql);
        while (in.find()) {
            Set<String> states = new TreeSet<>();
            for (String state : in.group(1).split(",")) {
                states.add(state.trim().replace("'", ""));
            }
            lists.add(states);
        }
        return lists;
    }

    private static String index() {
        return normalised(between(SQL, "CREATE INDEX IF NOT EXISTS idempotency_record_sweepable", ";"));
    }

    /** The top-level {@code AND}-conjuncts of the first {@code WHERE} clause of {@code sql}, up to its end. */
    private static Set<String> conjuncts(String sql, String keyword) {
        String text = normalised(sql);
        int from = text.indexOf(keyword + " ");
        assertThat(from).as("a %s clause", keyword).isNotNegative();
        String clause = text.substring(from + keyword.length() + 1);
        for (String end : List.of(" ORDER BY", ";", " )")) {
            int at = clause.indexOf(end);
            if (at >= 0) {
                clause = clause.substring(0, at);
            }
        }
        Set<String> parts = new LinkedHashSet<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < clause.length(); i++) {
            char at = clause.charAt(i);
            depth += at == '(' ? 1 : at == ')' ? -1 : 0;
            if (depth == 0 && clause.startsWith(" AND ", i)) {
                parts.add(clause.substring(start, i).trim());
                start = i + 5;
            }
        }
        parts.add(clause.substring(start).trim());
        return parts;
    }

    private static String body(String function) {
        String from = SQL.substring(SQL.indexOf("CREATE FUNCTION platform." + function));
        return between(from, "AS $$", "$$;");
    }

    private static String between(String text, String from, String to) {
        int start = text.indexOf(from);
        assertThat(start).as("'%s' in the text", from).isNotNegative();
        int end = text.indexOf(to, start + from.length());
        assertThat(end).as("'%s' after '%s'", to, from).isNotNegative();
        return text.substring(start, end + to.length());
    }

    private static String normalised(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static String withoutComments(String sql) {
        return String.join("\n", Arrays.stream(sql.split("\\R")).map(IdempotencySweepMigrationTest::code).toList());
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

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
