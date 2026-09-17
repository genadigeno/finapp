package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reversal schema and its one-definition sources agree (`P3-TSK-016` — the
 * {@code JournalEntryMigrationTest} discipline): the reference implication, the chain
 * refusal, the bound trigger with its advisory serializer, and the grants that must NOT
 * change.
 */
@DisplayName("the reversal schema and its definitions agree (P3-TSK-016)")
class ReversalMigrationTest {

    private static final String MIGRATION =
            "db/migration/ledger/V009__reversal_reference_and_bound.sql";

    @Test
    @DisplayName("a reversal references its original, and nothing else may - the implication")
    void theReferenceIsAnImplication() {
        assertThat(migration())
                .contains(
                        "CHECK ((entry_type = 'REVERSAL') = (reverses_entry_id IS NOT NULL))")
                .contains("CHECK (reverses_entry_id <> id)");
    }

    @Test
    @DisplayName("the bound trigger exists, and the advisory lock is its serializer")
    void theBoundTriggerSerializesOnTheOriginal() {
        assertThat(migration())
                .contains("CREATE TRIGGER journal_line_reversal_is_bounded")
                .contains("BEFORE INSERT ON ledger.journal_line")
                // Namespace 2, registered in DISTRIBUTED_EXECUTION.md - the two-argument
                // form, because advisory locks share one cluster-wide key space.
                .contains("pg_advisory_xact_lock(2, hashtext(original::text))");
    }

    @Test
    @DisplayName("a reversal of a reversal is refused for every writer")
    void theChainIsRefused() {
        assertThat(migration())
                .contains("CREATE TRIGGER journal_entry_reversal_references_a_posting")
                .contains("ledger_reversal_of_reversal");
    }

    @Test
    @DisplayName("no grant changes: the absence of UPDATE and DELETE is the point")
    void theGrantsDoNotChange() {
        assertThat(migration())
                .doesNotContain("GRANT UPDATE")
                .doesNotContain("GRANT DELETE")
                .doesNotContain("GRANT ALL");
    }

    private static String migration() {
        try (InputStream migration =
                ReversalMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException(
                        "Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
