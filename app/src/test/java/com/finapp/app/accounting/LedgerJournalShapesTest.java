package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.EntryType;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The journal-shape port's statements and key reading (`P14-TSK-010`; {@code INV-ACC-12}, {@code INV-ACC-14}): every
 * statement a {@code SELECT} that writes and locks nothing and never reads the entry's reference, reason or actor; the
 * month bounded both ways and paged by ledger {@code V029}'s keyset; a posting's and a reversal's namespace read from the
 * ledger's own scope prefix, an adjustment's rule from its proposal's origin. Its behaviour against the journal is
 * {@code AccountingBreakDatabaseTest}'s (owed, C5).
 */
@DisplayName("the journal-shape port's statements (P14-TSK-010)")
class LedgerJournalShapesTest {

    @Test
    @DisplayName("every statement is a SELECT that writes and locks nothing, and never reads the entry's reference, reason"
            + " or actor_id")
    void everyStatementOnlyReads() {
        for (String sql : List.of(LedgerJournalShapes.ENTRIES + LedgerJournalShapes.AFTER + LedgerJournalShapes.PAGE,
                LedgerJournalShapes.LINES)) {
            String upper = sql.toUpperCase(Locale.ROOT);
            assertThat(upper).startsWith("SELECT ");
            for (String forbidden : List.of("INSERT", "UPDATE", "DELETE", "FOR SHARE", "FOR UPDATE", "PG_ADVISORY",
                    "REASON", "ACTOR_ID", "CREATED_AT", "REFERENCE")) {
                assertThat(upper).as("%s in %s", forbidden, sql).doesNotContain(forbidden);
            }
        }
    }

    @Test
    @DisplayName("the month is bounded both ways and paged by (posting_date, id) - ledger V029's index order")
    void theMonthIsAKeyset() {
        assertThat(LedgerJournalShapes.ENTRIES).contains("entry.posting_date >= ? AND entry.posting_date <= ?")
                .contains("LEFT JOIN ledger.adjustment_proposal proposal ON proposal.journal_entry_id = entry.id");
        assertThat(LedgerJournalShapes.AFTER).isEqualTo(" AND (entry.posting_date, entry.id) > (?, ?)");
        assertThat(LedgerJournalShapes.PAGE).isEqualTo(" ORDER BY entry.posting_date, entry.id LIMIT ?");
        assertThat(LedgerJournalShapes.LINES).contains("WHERE line.entry_id = ANY (?) ORDER BY line.entry_id, line.seq");
    }

    @Test
    @DisplayName("the namespace: the ledger's scope prefix stripped to the command key's namespace; an adjustment's"
            + " proposal origin; anything else none")
    void theNamespaceIsTheCommandKeys() {
        assertThat(LedgerJournalShapes.namespace(EntryType.POSTING, "ledger.post:transfer:0192f0e0", null))
                .contains("transfer:");
        assertThat(LedgerJournalShapes.namespace(EntryType.REVERSAL, "ledger.reverse:fx-trade:abc", null))
                .contains("fx-trade:");
        assertThat(LedgerJournalShapes.namespace(EntryType.POSTING, "ledger.post:gl-read-0192", null))
                .as("a key with no namespace is its own").contains("gl-read-0192");
        assertThat(LedgerJournalShapes.namespace(EntryType.ADJUSTMENT, "ledger.adjust.approve:0192f0e0", "MANUAL"))
                .contains("MANUAL");
        assertThat(LedgerJournalShapes.namespace(EntryType.ADJUSTMENT, "ledger.adjust.approve:0192f0e0", null))
                .isEmpty();
        assertThat(LedgerJournalShapes.namespace(EntryType.POSTING, "ledger.reverse:transfer:x", null))
                .as("a posting under another door's scope").isEqualTo(Optional.empty());
        assertThat(LedgerJournalShapes.namespace(EntryType.POSTING, "ledger.post:", null)).isEmpty();
    }
}
