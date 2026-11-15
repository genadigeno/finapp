package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code accounting V017} and {@link JdbcOpeningCheckpointStore}'s statements as text (`P16-REM-003`): a checkpoint served only while
 * its month stands under the lock it was folded under, and under a cut only when every entry it folds is one the cut keeps; written
 * only under that lock, once per lock, by a writer that never waits; the application role able to read and add and never to change or
 * remove; the reporting role reading both tables. Their behaviour against PostgreSQL is {@code OpeningCheckpointDatabaseTest}'s.
 */
@DisplayName("accounting V017 and the opening checkpoint's statements (P16-REM-003)")
class OpeningCheckpointStatementsTest {

    private static final String RESOURCE = "db/migration/accounting/V017__the_report_runs_opening_checkpoint.sql";

    @Test
    @DisplayName("served while valid: the period not OPEN and its locked_at the checkpoint's; the newest before the day; under a cut, only"
            + " one whose latest creation the cut keeps")
    void theReadServesOnlyAStandingLock() {
        assertThat(JdbcOpeningCheckpointStore.LATEST)
                .contains("ON period.id = checkpoint.period_id AND period.locked_at = checkpoint.period_locked_at")
                .contains("period.status <> 'OPEN'")
                .contains("period.period_end < ?");
        assertThat(JdbcOpeningCheckpointStore.UNDER_THE_CUT)
                .isEqualTo(" AND (checkpoint.latest_created IS NULL OR checkpoint.latest_created <= ?)");
        assertThat(JdbcOpeningCheckpointStore.NEWEST).isEqualTo(" ORDER BY period.period_end DESC LIMIT 1");
        assertThat(JdbcOpeningCheckpointStore.GROUPS).contains("WHERE checkpoint_id = ?");
    }

    @Test
    @DisplayName("the reads are what a report's snapshot admits: one SELECT each, no lock, no ledger table - the screen itself is"
            + " OpeningCheckpointReadsTest's")
    void theReadsPassTheSnapshot() {
        for (String sql : List.of(JdbcOpeningCheckpointStore.LATEST + JdbcOpeningCheckpointStore.NEWEST,
                JdbcOpeningCheckpointStore.LATEST + JdbcOpeningCheckpointStore.UNDER_THE_CUT + JdbcOpeningCheckpointStore.NEWEST,
                JdbcOpeningCheckpointStore.GROUPS)) {
            String lower = sql.toLowerCase(Locale.ROOT);
            assertThat(lower).startsWith("select ").doesNotContain(";").doesNotContain("for update").doesNotContain("for share")
                    .doesNotContain("ledger.").doesNotContain("pg_advisory");
        }
    }

    @Test
    @DisplayName("the writer never waits: a transaction-scoped try-lock on accounting's namespace 14 under its own key")
    void theClaimIsATryLock() {
        assertThat(JdbcOpeningCheckpointStore.CLAIM_SQL).isEqualTo("SELECT pg_try_advisory_xact_lock(14, hashtext('opening-checkpoint'))");
        assertThat(JdbcOpeningCheckpointStore.NAMESPACE).isEqualTo(JdbcGlMappingStore.NAMESPACE);
        assertThat(JdbcOpeningCheckpointStore.KEY).isNotEqualTo(JdbcGlMappingStore.KEY).isNotEqualTo("cutoff");
    }

    @Test
    @DisplayName("the oldest month first, never an OPEN one, and only one whose present lock has no checkpoint")
    void thePendingMonthIsTheOldestUnderItsPresentLock() {
        assertThat(JdbcOpeningCheckpointStore.PENDING).contains("period.status <> 'OPEN' AND period.locked_at IS NOT NULL")
                .contains("checkpoint.period_id = period.id AND checkpoint.period_locked_at = period.locked_at")
                .contains("NOT EXISTS").endsWith(" ORDER BY period.period_start LIMIT 1");
    }

    @Test
    @DisplayName("the insert reads the lock again and writes nothing once it moved; a second checkpoint of the same lock is a no-op")
    void theStoreWritesOnlyUnderTheLockItFoldedUnder() {
        assertThat(JdbcOpeningCheckpointStore.STORE)
                .contains("SELECT ?, period.id, period.locked_at, ? FROM accounting.accounting_period period")
                .contains("WHERE period.id = ? AND period.status <> 'OPEN' AND period.locked_at = ?")
                .endsWith(" ON CONFLICT (period_id, period_locked_at) DO NOTHING");
    }

    @Test
    @DisplayName("the tables: one checkpoint per lock, each group once, its shapes and sums constrained")
    void theTablesHoldTheirShapes() {
        String body = body();
        assertThat(body).contains("CREATE TABLE accounting.opening_checkpoint (")
                .contains("period_id        uuid        NOT NULL REFERENCES accounting.accounting_period (id)")
                .contains("CONSTRAINT opening_checkpoint_one_per_lock UNIQUE (period_id, period_locked_at)")
                .contains("CREATE TABLE accounting.opening_checkpoint_group (")
                .contains("CHECK (shape IN ('GL', 'LEDGER', 'COUNTERPARTY'))")
                .contains("CHECK (total > 0 AND total = trunc(total))")
                .contains("CHECK (lines > 0)")
                .contains("UNIQUE NULLS NOT DISTINCT")
                .contains("(checkpoint_id, shape, purpose, owner_kind, counterparty, currency, scale, direction)")
                .contains("DEFAULT pg_catalog.clock_timestamp()")
                .doesNotContain("ledger.");
    }

    @Test
    @DisplayName("never written over: the application role reads and adds, nothing more; the reporting role reads both")
    void neverWrittenOver() {
        String body = body();
        assertThat(body).contains("GRANT SELECT, INSERT ON accounting.opening_checkpoint TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON accounting.opening_checkpoint_group TO finapp_app;")
                .contains("GRANT SELECT ON accounting.opening_checkpoint TO finapp_reporting;")
                .contains("GRANT SELECT ON accounting.opening_checkpoint_group TO finapp_reporting;");
        assertThat(body.lines().filter(line -> line.toUpperCase(Locale.ROOT).contains("GRANT"))).as("every grant, and no other")
                .containsExactly("GRANT SELECT, INSERT ON accounting.opening_checkpoint TO finapp_app;",
                        "GRANT SELECT, INSERT ON accounting.opening_checkpoint_group TO finapp_app;",
                        "GRANT SELECT ON accounting.opening_checkpoint TO finapp_reporting;",
                        "GRANT SELECT ON accounting.opening_checkpoint_group TO finapp_reporting;");
        String upper = body.toUpperCase(Locale.ROOT);
        assertThat(upper).doesNotContain("TRUNCATE").doesNotContain("CREATE TRIGGER").doesNotContain("DELETE ON")
                .doesNotContain("UPDATE ON");
    }

    private static String body() {
        return resource().replaceAll("(?m)^--.*$", "");
    }

    private static String resource() {
        try (InputStream stream = OpeningCheckpointStatementsTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            assertThat(stream).as(RESOURCE).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
