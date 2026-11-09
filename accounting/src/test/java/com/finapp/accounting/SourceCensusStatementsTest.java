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
 * The census's statements and its migration, as text (`P15-TSK-035`): the claim holds one row under {@code SKIP LOCKED} and notices a head
 * past the database's transaction counter; the head moves by the pair; every stamp is the database's clock; a finding converges on its first
 * row, and neither table can be emptied or edited behind the census's back. Their behaviour against the real database is
 * {@code SourceCensusDatabaseTest}'s (owed).
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
    @DisplayName("a head past the database's oldest running transaction is noticed in the claim: a logical restore never walks on")
    void theClaimNoticesAHeadAheadOfTheDatabase() {
        assertThat(JdbcSourceCensusStore.CLAIM).contains("position_txid >= pg_snapshot_xmin(pg_current_snapshot())");
    }

    @Test
    @DisplayName("the head moves by the pair: the transaction and the id")
    void theHeadMovesByThePair() {
        assertThat(JdbcSourceCensusStore.ADVANCE).contains("position_txid = ?::xid8").contains("position_id = ?").contains("advanced_at = clock_timestamp()");
    }

    @Test
    @DisplayName("every stamp is the database's clock: never an instance's, never a transaction-start clock")
    void everyStampIsTheDatabasesClock() {
        for (String sql : List.of(JdbcSourceCensusStore.INSERT_FINDING, JdbcSourceCensusStore.ADVANCE, JdbcSourceCensusStore.TOUCH, JdbcSourceCensusStore.MARK_FAILURE)) {
            assertThat(sql).contains("clock_timestamp()").doesNotContain("now()", "statement_timestamp");
        }
    }

    @Test
    @DisplayName("a finding converges on its first row: re-judging what a crash did not commit is harmless")
    void aFindingConverges() {
        assertThat(JdbcSourceCensusStore.INSERT_FINDING).endsWith("ON CONFLICT (entry_id) DO NOTHING");
    }

    @Test
    @DisplayName("V015: one head row, two tables, no amount column and no foreign key anywhere; the walker's grants, and none to the reporting role")
    void theMigrationIsWhatTheCensusNeeds() {
        String body = body();
        assertThat(body).contains("CREATE TABLE accounting.source_census_head").contains("CREATE TABLE accounting.source_census_finding");
        assertThat(body.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("amount_minor").doesNotContain("references ledger").doesNotContain("foreign key");
        assertThat(body).contains("GRANT SELECT, UPDATE ON accounting.source_census_head TO finapp_app")
                .contains("GRANT SELECT, INSERT ON accounting.source_census_finding TO finapp_app")
                .as("not a report run's tables: no grant to the reporting role").doesNotContain("finapp_reporting");
        assertThat(body).as("the floor is a position before every entry")
                .contains("'00000000-0000-7000-8000-000000000000'").contains("('0', ");
        assertThat(body).as("a finding is never updated or deleted by the application").doesNotContain("GRANT DELETE").doesNotContain("GRANT SELECT, INSERT, UPDATE ON accounting.source_census_finding");
    }

    @Test
    @DisplayName("V015: the head only moves forward and never past a running transaction, by trigger; the findings and the head are never truncated")
    void theRecordsDefendThemselves() {
        String body = body();
        assertThat(body).contains("BEFORE UPDATE OR DELETE ON accounting.source_census_head")
                .contains("NEW.position_txid < OLD.position_txid")
                .contains("NEW.position_txid >= pg_catalog.pg_snapshot_xmin(pg_catalog.pg_current_snapshot())");
        assertThat(body).contains("BEFORE INSERT OR UPDATE OR DELETE ON accounting.source_census_finding")
                .contains("NEW.found_at := pg_catalog.clock_timestamp()")
                .contains("BEFORE TRUNCATE ON accounting.source_census_finding")
                .contains("BEFORE TRUNCATE ON accounting.source_census_head");
    }

    private static String body() {
        return resource(RESOURCE).replaceAll("(?m)^--.*$", "");
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
