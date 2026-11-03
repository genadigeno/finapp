package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <strong>A repudiation does not wedge the close</strong> (`P14-TSK-012`, the independent review, I-2): a month whose only counting run was repudiated must have a way on, or the rule would make it a state no person can leave. It has: the
 * same source and business date can be accepted again. Pinned here without a database, as the migrations state it; {@code QuietMonthDatabaseTest#aRepudiatedBatchsRunCountsForNothingAndTheNextBatchCounts} walks it against PostgreSQL (owed, C5), and {@code
 * com.finapp.app.reconciliation.BatchRepudiationDatabaseTest#theGenuineFileIsRecovered} and {@code #aFabricatedStatementIsRepudiated} are that door's own suite.
 *
 * <ul>
 *   <li><strong>Other bytes</strong> - a corrected file, a nil report: a new content address, so a new file; settlement's live uniques exclude {@code REPUDIATED}, so its batch parses into the freed identity; the accept leg gives it the next
 *       gapless sequence; the run is keyed by batch and by that sequence, never by date, so it is a new run of the same date.
 *   <li><strong>The same bytes</strong> meet the file's content address, a {@code DUPLICATE} at the door; a person's readmission stands outside that index, and its trigger admits an {@code ACCEPTED} file whose batch is {@code REPUDIATED}.
 * </ul>
 */
@DisplayName("after a repudiation the same source and date can be accepted again (P14-TSK-012)")
class TheWayOnAfterARepudiationTest {

    private static final String SETTLEMENT = "db/migration/settlement/";
    private static final String RECONCILIATION = "db/migration/reconciliation/";

    @Test
    @DisplayName("other bytes: settlement's live identity and statement sequence each exclude REPUDIATED, so a repudiated batch frees both")
    void theLiveUniquesExcludeARepudiatedBatch() throws IOException {
        assertThat(collapsed(SETTLEMENT + "V003__the_psp_format_parse_normalise_reject_whole.sql"))
                .contains("CREATE UNIQUE INDEX batch_live_identity ON settlement.batch (source_id, external_batch_ref, currency) WHERE status NOT IN ('REJECTED', 'REPUDIATED');");
        assertThat(collapsed(SETTLEMENT + "V005__the_bank_statement.sql"))
                .contains("CREATE UNIQUE INDEX batch_live_statement_sequence ON settlement.batch (source_id, currency, statement_sequence) WHERE statement_sequence IS NOT NULL AND status NOT IN ('REJECTED', 'REPUDIATED');");
    }

    @Test
    @DisplayName("the new batch has a run of its own: reconciliation keys a run by its batch and by the gapless sequence, and nothing is unique by business date")
    void aRunIsKeyedByBatchAndSequenceNeverByDate() throws IOException {
        String runs = collapsed(RECONCILIATION + "V003__runs_and_external_items.sql");
        assertThat(runs).contains("CONSTRAINT reconciliation_batch_batch_once UNIQUE (batch_id)")
                .contains("CREATE UNIQUE INDEX reconciliation_batch_sequence_once ON reconciliation.reconciliation_batch (source_id, source_sequence) WHERE kind = 'BATCH';");
        assertThat(runs).as("no unique on a run's date").doesNotContainPattern("UNIQUE [^;]*reconciliation_batch[^;]*business_date");
        assertThat(collapsed(SETTLEMENT + "V004__acceptance_joins_the_machines.sql")).contains("ADD CONSTRAINT batch_sequence_once UNIQUE (source_id, source_sequence);");
    }

    @Test
    @DisplayName("the same bytes: the file's content address stands, and a readmission stands outside it, its trigger admitting an ACCEPTED file whose batch is REPUDIATED")
    void theSameBytesAreAPersonsReadmission() throws IOException {
        assertThat(collapsed(SETTLEMENT + "V002__source_register_file_store_and_door_screen.sql"))
                .contains("CREATE UNIQUE INDEX file_content_address ON settlement.file (source_id, content_sha256) WHERE readmits_file_id IS NULL;");
        assertThat(collapsed(SETTLEMENT + "V015__the_fx_provider_report.sql")).contains("WHERE b.file_id = o.id AND b.status = 'REPUDIATED'");
    }

    private static String collapsed(String resource) throws IOException {
        try (InputStream in = TheWayOnAfterARepudiationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        }
    }
}
