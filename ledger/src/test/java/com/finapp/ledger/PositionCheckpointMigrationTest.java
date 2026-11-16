package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code ledger V038} as text (`P16-REM-001`; ADR-0133 decision 3): the line's commit-order position stamped for every writer as
 * {@code V034} stamps the entry's, indexed by account for the checkpointed read, and the checkpoint tables - one row per watermark, the
 * cluster and the classification's version on every row, the application role able to read and add and never to change or remove.
 * Its behaviour on a real database is {@code PositionCheckpointDatabaseTest}'s.
 */
@DisplayName("ledger V038: the payable read's checkpoint (P16-REM-001)")
class PositionCheckpointMigrationTest {

    private static final String RESOURCE = "db/migration/ledger/V038__the_payable_read_checkpoint.sql";

    @Test
    @DisplayName("the line carries its top-level transaction, by default and by a trigger a writer cannot get around")
    void theLineIsStampedForEveryWriter() {
        String body = body();
        assertThat(body).contains("ALTER TABLE ledger.journal_line")
                .contains("ADD COLUMN txid xid8 NOT NULL DEFAULT pg_catalog.pg_current_xact_id()")
                .contains("NEW.txid := pg_catalog.pg_current_xact_id()")
                .contains("CREATE TRIGGER journal_line_stamps_its_transaction")
                .contains("BEFORE INSERT ON ledger.journal_line")
                .contains("FOR EACH ROW");
    }

    @Test
    @DisplayName("the stamp reads no clock, runs with a fixed search_path and is no definer, and fires last of the line's BEFORE triggers")
    void theStampIsPlainAndFiresLast() {
        String function = body().substring(body().indexOf("CREATE OR REPLACE FUNCTION ledger.journal_line_stamps_its_transaction"));
        assertThat(function).contains("SET search_path = pg_catalog, pg_temp");
        assertThat(function.toLowerCase(Locale.ROOT))
                .doesNotContain("security definer").doesNotContain("clock_timestamp").doesNotContain("now()").doesNotContain("statement_timestamp");
        assertThat("journal_line_stamps_its_transaction").as("BEFORE triggers fire in name order")
                .isGreaterThan("journal_line_account_accepts_postings")
                .isGreaterThan("journal_line_respects_the_posting_cutoff")
                .isGreaterThan("journal_line_reversal_is_bounded");
    }

    @Test
    @DisplayName("the read has its index: the account, then the transaction")
    void theReadHasItsIndex() {
        assertThat(body()).as("IF NOT EXISTS: an index built CONCURRENTLY by hand first, on a large journal, is kept")
                .contains("CREATE INDEX IF NOT EXISTS journal_line_by_account_and_transaction ON ledger.journal_line (ledger_account_id, txid)");
    }

    @Test
    @DisplayName("a checkpoint names its account, version, precedence, namespaces, cluster and watermark, once per watermark")
    void aCheckpointNamesWhatItIsAFoldOf() {
        assertThat(body()).contains("CREATE TABLE ledger.position_checkpoint (")
                .contains("classification_version integer  NOT NULL")
                .contains("cluster_identity       text     NOT NULL")
                .contains("watermark              xid8     NOT NULL")
                .contains("UNIQUE (ledger_account_id, classification_version, precedence, namespaces, cluster_identity, watermark)")
                .contains("CONSTRAINT position_checkpoint_one_per_watermark");
        assertThat(CheckpointedPositionBreakdown.STORE).contains("ON CONFLICT ON CONSTRAINT position_checkpoint_one_per_watermark DO NOTHING");
    }

    @Test
    @DisplayName("a bucket is an amount with its currency and scale, positive, in a known direction")
    void aBucketIsMoney() {
        assertThat(body()).contains("CREATE TABLE ledger.position_checkpoint_bucket (")
                .contains("amount_minor BIGINT NOT NULL, currency CHAR(3) NOT NULL, scale SMALLINT NOT NULL")
                .contains("CHECK (amount_minor > 0)")
                .contains("CHECK (direction IN ('DEBIT', 'CREDIT'))");
    }

    @Test
    @DisplayName("the application role reads and adds checkpoints, and is granted nothing that changes or removes one")
    void theApplicationReadsAndAddsOnly() {
        String body = body();
        assertThat(body).contains("GRANT SELECT, INSERT ON ledger.position_checkpoint TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON ledger.position_checkpoint_bucket TO finapp_app;");
        assertThat(body.toUpperCase(Locale.ROOT)).doesNotContain("GRANT UPDATE").doesNotContain("GRANT DELETE").doesNotContain("GRANT ALL");
    }

    private static String body() {
        return resource().replaceAll("(?m)^--.*$", "");
    }

    private static String resource() {
        try (InputStream stream = PositionCheckpointMigrationTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            assertThat(stream).as(RESOURCE).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
