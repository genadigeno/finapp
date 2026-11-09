package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.EntryType;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The census's journal read (`P15-TSK-035`): read-only statements, bounded both ways by a keyset and the fence, a posting's and a
 * reversal's namespace and key suffix read from the ledger's own scope prefix, an adjustment's namespace from its proposal's origin.
 * Its behaviour against the journal is {@code SourceCensusDatabaseTest}'s (owed).
 */
@DisplayName("the census's walk of the journal (P15-TSK-035)")
class LedgerJournalCensusWalkTest {

    @Test
    @DisplayName("every statement is a SELECT that writes and locks nothing; the page is bounded by the keyset and the fence")
    void everyStatementOnlyReads() {
        for (String sql : List.of(LedgerJournalCensusWalk.PAGE, LedgerJournalCensusWalk.LINES, LedgerJournalCensusWalk.OLDEST_AFTER)) {
            String upper = sql.toUpperCase(Locale.ROOT);
            assertThat(upper).startsWith("SELECT ");
            for (String forbidden : List.of("INSERT ", "UPDATE ", "DELETE ", "FOR SHARE", "FOR UPDATE", "PG_ADVISORY")) {
                assertThat(upper).as(forbidden).doesNotContain(forbidden);
            }
        }
        assertThat(LedgerJournalCensusWalk.PAGE).contains("entry.id > ?").contains("entry.id <").contains("ORDER BY entry.id LIMIT ?");
    }

    @Test
    @DisplayName("a posting's and a reversal's namespace is the command key's, off the ledger's scope prefix; an adjustment's is its origin")
    void aNamespaceIsTheKeysOrTheOrigin() {
        assertThat(LedgerJournalCensusWalk.namespace(EntryType.POSTING, "ledger.post:transfer:018f", null)).hasValue("transfer:");
        assertThat(LedgerJournalCensusWalk.namespace(EntryType.REVERSAL, "ledger.reverse:transfer:018f", null)).hasValue("transfer:");
        assertThat(LedgerJournalCensusWalk.namespace(EntryType.ADJUSTMENT, "ledger.adjust.approve:018f", "RECONCILIATION")).hasValue("RECONCILIATION");
        assertThat(LedgerJournalCensusWalk.namespace(EntryType.POSTING, "something.else:transfer:018f", null)).isEqualTo(Optional.empty());
        assertThat(LedgerJournalCensusWalk.namespace(EntryType.ADJUSTMENT, "other:018f", "RECONCILIATION")).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("the suffix is the key past its namespace; an adjustment's is the proposal; a key with no namespace has none")
    void theSuffix() {
        assertThat(LedgerJournalCensusWalk.suffix(EntryType.POSTING, "ledger.post:lending.accrual:018f:2026-11-09")).isEqualTo("018f:2026-11-09");
        assertThat(LedgerJournalCensusWalk.suffix(EntryType.REVERSAL, "ledger.reverse:transfer:018f")).isEqualTo("018f");
        assertThat(LedgerJournalCensusWalk.suffix(EntryType.ADJUSTMENT, "ledger.adjust.approve:018f")).isEqualTo("018f");
        assertThat(LedgerJournalCensusWalk.suffix(EntryType.POSTING, "ledger.post:no-namespace-here")).isEmpty();
        assertThat(LedgerJournalCensusWalk.suffix(EntryType.POSTING, "something.else:transfer:018f")).isEmpty();
    }
}
