package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.retention.RetentionSweep;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What identity {@code V027}'s session sweep selects, read from the migration (`P16-TSK-010`). On PostgreSQL it is
 * {@code SessionSweepDatabaseTest}'s (owed to the database pass); a short container check ran it once on PostgreSQL 18.6. Comments
 * are stripped, the selection is split into its top-level conjuncts, and the set must be exactly the one below: a phrase inside an
 * {@code OR} or a comment cannot pass for it.
 */
@DisplayName("the session sweep selects only dead sessions past the grace, and stops at a column it was not reviewed against (P16-TSK-010)")
class SessionSweepMigrationTest {

    private static final String SWEEP = "db/migration/identity/V027__the_session_sweep.sql";

    private static final String SQL = flat(withoutComments(resource(SWEEP)));

    @Test
    @DisplayName("the selection is exactly: a known state, and an idle bound passed more than the grace ago on the database's clock")
    void theSelectionIsExactlyDeadPastTheGrace() {
        String cte = SQL.substring(SQL.indexOf("WITH doomed AS MATERIALIZED ("), SQL.indexOf("DELETE FROM identity.session x"));
        String where = cte.substring(cte.indexOf(" WHERE ") + 7, cte.indexOf(" ORDER BY "));

        assertThat(Arrays.stream(where.split(" AND ")).map(String::trim).toList()).containsExactlyInAnyOrder(
                "s.status IN (" + SessionStatus.sqlValueList() + ")",
                "s.idle_expires_at < now() - (grace_ms * INTERVAL '1 millisecond')");
        assertThat(where).as("no OR can widen it").doesNotContainIgnoringCase(" OR ");
        assertThat(cte).contains("ORDER BY s.idle_expires_at LIMIT batch_size FOR UPDATE OF s SKIP LOCKED");
        assertThat(SQL).contains("DELETE FROM identity.session x USING doomed WHERE x.id = doomed.id;");
    }

    @Test
    @DisplayName("never a live session: a live row's idle bound is after now(), the sweep's is before now() less a grace of a minute or more")
    void neverALiveSession() {
        // JdbcSessionStore's LIVE: status = 'ACTIVE' AND idle_expires_at > now() AND absolute_expires_at > now(). Both cannot hold.
        Matcher floor = Pattern.compile("IF grace_ms IS NULL OR grace_ms < (\\d+) OR batch_size IS NULL OR batch_size NOT BETWEEN 1 AND (\\d+) THEN")
                .matcher(SQL);
        assertThat(floor.find()).as("the function judges its grace against a floor").isTrue();
        assertThat(Long.parseLong(floor.group(1))).as("the floor the code refuses at start").isEqualTo(SessionRetention.MIN_GRACE.toMillis())
                .isPositive();
        assertThat(Integer.parseInt(floor.group(2))).isEqualTo(RetentionSweep.MAX_BATCH_SIZE);
        int refused = SQL.indexOf("USING ERRCODE = 'invalid_parameter_value'");
        assertThat(refused).as("it refuses before it selects").isPositive().isLessThan(SQL.indexOf("WITH doomed AS"));
    }

    @Test
    @DisplayName("an unreviewed column stops the sweep before it selects, and the reviewed list is the table's real columns")
    void anUnreviewedColumnStopsTheSweep() {
        Matcher guard = Pattern.compile("attname <> ALL \\(ARRAY\\[([^\\]]*)]::name\\[]\\)").matcher(SQL);
        assertThat(guard.find()).as("the guard compares every column with a list").isTrue();
        Set<String> reviewed = new TreeSet<>();
        for (String quoted : guard.group(1).split(",")) {
            reviewed.add(quoted.trim().replace("'", ""));
        }

        assertThat(reviewed)
                .as("a column added to identity.session may carry a hold this sweep would delete across: review it and list it")
                .isEqualTo(new TreeSet<>(columnsOfTheSessionTable()));
        int raised = SQL.indexOf("USING ERRCODE = 'object_not_in_prerequisite_state'");
        assertThat(raised).isPositive().isLessThan(SQL.indexOf("WITH doomed AS"));
    }

    @Test
    @DisplayName("deletion is the function's alone: definer, path pinned, no PUBLIC, the application role executes it and holds no DELETE")
    void deletionIsTheFunctionsAlone() {
        String header = SQL.substring(SQL.indexOf("CREATE FUNCTION identity.sweep_expired_sessions("), SQL.indexOf("AS $$"));
        assertThat(header).contains("SECURITY DEFINER").contains("SET search_path = pg_catalog, pg_temp");
        assertThat(SQL).contains("REVOKE ALL ON FUNCTION identity.sweep_expired_sessions(bigint, integer) FROM PUBLIC;")
                .contains("GRANT EXECUTE ON FUNCTION identity.sweep_expired_sessions(bigint, integer) TO finapp_app;")
                .doesNotContainPattern("GRANT [^;]*DELETE");
        assertThat(SessionRetention.FUNCTION).isEqualTo("identity.sweep_expired_sessions");
        assertThat(SQL).contains("CREATE INDEX IF NOT EXISTS session_sweepable ON identity.session (idle_expires_at);");
    }

    // ------------------------------------------------------------------------------------------------------------------

    /** V005's columns and every column a later identity migration adds to the session table. */
    private static List<String> columnsOfTheSessionTable() {
        String created = withoutComments(resource("db/migration/identity/V005__create_session.sql"));
        String table = created.substring(created.indexOf("CREATE TABLE identity.session"), created.indexOf("CONSTRAINT session_assurance_is_known"));
        List<String> columns = new ArrayList<>();
        Matcher column = Pattern.compile("(?m)^\\s+([a-z_]+)\\s+(uuid|text|timestamptz)\\b").matcher(table);
        while (column.find()) {
            columns.add(column.group(1));
        }
        assertThat(columns).as("V005's columns were read").hasSizeGreaterThanOrEqualTo(10);
        Pattern added = Pattern.compile("ALTER TABLE identity\\.session ADD COLUMN ([a-z_]+)");
        List<String> files = everyMigration();
        assertThat(files).as("every identity migration was listed, this one's included").contains("V027__the_session_sweep.sql")
                .hasSizeGreaterThanOrEqualTo(27);
        for (String file : files) {
            Matcher addition = added.matcher(flat(withoutComments(resource("db/migration/identity/" + file))));
            while (addition.find()) {
                columns.add(addition.group(1));
            }
        }
        assertThat(columns).as("V016's live_from is read too").contains("live_from");
        return columns;
    }

    /** Every identity migration on the test's classpath, by name, in order: the directory the build's resources are copied to. */
    private static List<String> everyMigration() {
        try {
            java.net.URL directory = SessionSweepMigrationTest.class.getClassLoader().getResource("db/migration/identity");
            assertThat(directory).as("the identity migrations on the classpath").isNotNull();
            try (var listing = java.nio.file.Files.list(java.nio.file.Path.of(directory.toURI()))) {
                return listing.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".sql")).sorted().toList();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String resource(String path) {
        try (InputStream in = SessionSweepMigrationTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("%s on the classpath", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String flat(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static String withoutComments(String sql) {
        return sql.replaceAll("(?m)--.*$", "");
    }
}
