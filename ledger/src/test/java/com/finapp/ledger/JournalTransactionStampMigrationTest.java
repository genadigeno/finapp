package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code ledger V034} as text (`P15-TSK-035`, the fix round): the journal's commit-order column is stamped from the transaction for every
 * writer, by a function with no clock and a fixed {@code search_path}, indexed for the walk; and no earlier migration is rewritten. Its
 * behaviour on a real database is {@code SourceCensusDatabaseTest}'s (owed).
 */
@DisplayName("ledger V034: the journal records its transaction (P15-TSK-035)")
class JournalTransactionStampMigrationTest {

    private static final String RESOURCE = "db/migration/ledger/V034__the_journal_records_its_transaction.sql";

    @Test
    @DisplayName("the column is the top-level transaction's id, by default and by a trigger a writer cannot get around")
    void theColumnIsStampedForEveryWriter() {
        String body = body();
        assertThat(body).contains("ADD COLUMN txid xid8 NOT NULL DEFAULT pg_catalog.pg_current_xact_id()");
        assertThat(body).contains("NEW.txid := pg_catalog.pg_current_xact_id()");
        assertThat(body).contains("CREATE TRIGGER journal_entry_stamps_its_transaction").contains("BEFORE INSERT ON ledger.journal_entry").contains("FOR EACH ROW");
    }

    @Test
    @DisplayName("the function reads no clock, runs with a fixed search_path and is not a definer: it chooses nothing and reaches nothing")
    void theStampingFunctionIsPlain() {
        String body = body();
        String function = body.substring(body.indexOf("CREATE OR REPLACE FUNCTION ledger.journal_entry_stamps_its_transaction"));
        assertThat(function).contains("SET search_path = pg_catalog, pg_temp");
        assertThat(function.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("security definer").doesNotContain("clock_timestamp").doesNotContain("now()").doesNotContain("statement_timestamp");
    }

    @Test
    @DisplayName("the walk has its index: the transaction, then the id")
    void theWalkHasItsIndex() {
        assertThat(body()).contains("CREATE INDEX journal_entry_by_transaction ON ledger.journal_entry (txid, id)");
    }

    @Test
    @DisplayName("its name fires last of the BEFORE triggers, so a refused entry costs nothing and nothing after it changes the stamp")
    void itFiresLast() {
        assertThat("journal_entry_stamps_its_transaction").as("BEFORE triggers fire in name order")
                .isGreaterThan("journal_entry_respects_the_posting_cutoff")
                .isGreaterThan("journal_entry_reversal_references_a_posting");
    }

    private static String body() {
        return resource().replaceAll("(?m)^--.*$", "");
    }

    private static String resource() {
        try (InputStream stream = JournalTransactionStampMigrationTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            assertThat(stream).as(RESOURCE).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
