package com.finapp.platform.abuse;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What platform {@code V014} declares, read with no database (`P15-TSK-022`). Whether PostgreSQL does what it says is
 * {@code WindowCounterDatabaseTest}'s, owed to the exit's pass.
 */
@DisplayName("the window counter's table and its sweep, read from the migration (P15-TSK-022)")
class WindowCounterMigrationTest {

    private static final String MIGRATION = "platform/src/main/resources/db/migration/platform/V014__the_window_counter.sql";

    private static final String SQL = withoutComments(read());

    @Test
    @DisplayName("the table is UNLOGGED: a flood writes no WAL, and a crash loses only a count")
    void theTableIsUnlogged() {
        assertThat(SQL).contains("CREATE UNLOGGED TABLE platform.window_counter");
    }

    @Test
    @DisplayName("the application role can increment a count and cannot delete one")
    void theRoleCannotDelete() {
        assertThat(SQL).contains("GRANT SELECT, INSERT, UPDATE ON platform.window_counter TO finapp_app;");
        assertThat(SQL).doesNotContain("GRANT SELECT, INSERT, UPDATE, DELETE");
        assertThat(SQL).contains("GRANT EXECUTE ON FUNCTION platform.sweep_expired_window_counters(bigint, integer) TO finapp_app;");
    }

    @Test
    @DisplayName("the sweep selects on the row's own end, on the database's clock, under SKIP LOCKED: no setting can delete a live window")
    void theSweepSelectsOnTheRowsOwnEnd() {
        assertThat(SQL).contains("c.window_ends_at < now() - (retain_for_ms * INTERVAL '1 millisecond')");
        assertThat(SQL).contains("FOR UPDATE OF c SKIP LOCKED");
        assertThat(SQL).contains("WITH doomed AS MATERIALIZED");
        assertThat(SQL).contains("SET search_path = pg_catalog, pg_temp");
        assertThat(SQL).contains("retain_for_ms < 1");
    }

    @Test
    @DisplayName("a key cannot be wide, and a count starts at one")
    void theRowsAreBounded() {
        assertThat(SQL).contains("window_counter_key_shape CHECK (counter_key ~ '^[A-Za-z0-9._:-]{1,64}$')");
        assertThat(SQL).contains("window_counter_admitted_at_least_one CHECK (admitted >= 1)");
    }

    private static String read() {
        try {
            return Files.readString(RepositoryPaths.locate(MIGRATION), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String withoutComments(String sql) {
        return sql.replaceAll("(?m)^\\s*--.*$", "").replaceAll("--[^\n]*", "");
    }
}
