package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The census's statements and its migration, as text (`P15-TSK-035`): the claim holds one row under {@code SKIP LOCKED}; every stamp
 * is the database's clock; a finding converges on its first row and no statement writes an amount; the fence reads open transactions
 * from {@code pg_stat_activity}, and is taken before the walk's own transaction by {@code SourceCensusTest}'s ordering. Their behaviour
 * against the real database is {@code SourceCensusDatabaseTest}'s (owed).
 */
@DisplayName("the census's statements and V015 say what they should (P15-TSK-035)")
class SourceCensusStatementsTest {

    private static final String RESOURCE = "db/migration/accounting/V015__the_source_join_census.sql";

    @Test
    @DisplayName("one walker at a time: the head is claimed FOR UPDATE SKIP LOCKED, and only that row")
    void theHeadIsClaimedSkipLocked() {
        assertThat(JdbcSourceCensusStore.CLAIM).contains("accounting.source_census_head").endsWith("FOR UPDATE SKIP LOCKED");
    }

    @Test
    @DisplayName("every stamp is the database's clock: never an instance's, never a transaction-start clock")
    void everyStampIsTheDatabasesClock() {
        for (String sql : List.of(JdbcSourceCensusStore.INSERT_FINDING, JdbcSourceCensusStore.ADVANCE, JdbcSourceCensusStore.TOUCH, JdbcSourceCensusStore.MARK_FAILURE)) {
            assertThat(sql).contains("clock_timestamp()").doesNotContain("now()", "statement_timestamp");
        }
    }

    @Test
    @DisplayName("the fence: the oldest open transaction this role can see, excluding the session asking, bounded by the database's now")
    void theFenceReadsOpenTransactions() {
        assertThat(JdbcSourceCensusStore.FENCE).contains("pg_stat_activity").contains("xact_start IS NOT NULL")
                .contains("pg_backend_pid()").contains("least(clock_timestamp()");
    }

    @Test
    @DisplayName("a finding converges on its first row: re-judging what a crash did not commit is harmless")
    void aFindingConverges() {
        assertThat(JdbcSourceCensusStore.INSERT_FINDING).endsWith("ON CONFLICT (entry_id) DO NOTHING");
    }

    @Test
    @DisplayName("V015: one head row, two tables, no amount column and no foreign key anywhere; the walker's grants")
    void theMigrationIsWhatTheCensusNeeds() {
        String migration = resource(RESOURCE);
        String body = migration.replaceAll("(?m)^--.*$", "");
        assertThat(body).contains("CREATE TABLE accounting.source_census_head").contains("CREATE TABLE accounting.source_census_finding");
        assertThat(body.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("amount_minor").doesNotContain("references ledger").doesNotContain("foreign key");
        assertThat(body).contains("GRANT SELECT, UPDATE ON accounting.source_census_head TO finapp_app")
                .contains("GRANT SELECT, INSERT ON accounting.source_census_finding TO finapp_app")
                .as("not a report run's tables: no grant to the reporting role").doesNotContain("finapp_reporting");
        assertThat(body).as("the floor is a position before every entry")
                .contains("'00000000-0000-7000-8000-000000000000'");
        assertThat(body).as("a finding is never updated or deleted by the application").doesNotContain("GRANT DELETE").doesNotContain("GRANT SELECT, INSERT, UPDATE ON accounting.source_census_finding");
    }

    private static String resource(String name) {
        try (InputStream stream = SourceCensusStatementsTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(stream).as(name).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
