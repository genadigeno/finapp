package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The journal port's statements over the ledger's tables (`P14-TSK-007`; {@code INV-ACC-02}, {@code INV-ACC-06},
 * {@code INV-ACC-14}) - their shape, which the database suite proves in behaviour ({@code GlReadModelDatabaseTest}, owed,
 * C5): every one a {@code SELECT} that locks nothing; every range on {@code posting_date} (ledger {@code V029}'s index);
 * the ledger's totals independent of any account; sums grouped by scale; the drill-down a keyset in the index's order;
 * never the entry's reason or actor.
 */
@DisplayName("the journal port's statements (P14-TSK-007)")
class LedgerJournalReadsTest {

    private static final List<String> STATEMENTS = List.of(LedgerJournalReads.AGGREGATES, LedgerJournalReads.TOTALS,
            LedgerJournalReads.LINES + LedgerJournalReads.AFTER + LedgerJournalReads.PAGE, LedgerJournalReads.ENTRY,
            LedgerJournalReads.ENTRY_LINES, LedgerJournalReads.COUNT);

    @Test
    @DisplayName("every statement is a SELECT that writes and locks nothing, and never reads the entry's reference, reason"
            + " or actor_id")
    void everyStatementOnlyReads() {
        for (String sql : STATEMENTS) {
            String upper = sql.toUpperCase(Locale.ROOT);
            assertThat(upper).startsWith("SELECT ");
            for (String forbidden : List.of("INSERT", "UPDATE", "DELETE", "FOR SHARE", "FOR UPDATE", "PG_ADVISORY",
                    "REASON", "ACTOR_ID", "CREATED_AT", "REFERENCE")) {
                assertThat(upper).as("%s in %s", forbidden, sql).doesNotContain(forbidden);
            }
        }
    }

    @Test
    @DisplayName("the period reads bound posting_date both ways; the ledger's totals join no account; sums group by scale")
    void theRangeIsBoundedAndTheTotalsIndependent() {
        for (String sql : List.of(LedgerJournalReads.AGGREGATES, LedgerJournalReads.TOTALS, LedgerJournalReads.LINES)) {
            assertThat(sql).contains("entry.posting_date >= ? AND entry.posting_date <= ?");
        }
        assertThat(LedgerJournalReads.TOTALS).doesNotContain("ledger_account").doesNotContain("counterparty")
                .contains("GROUP BY line.currency, line.scale, line.direction");
        assertThat(LedgerJournalReads.AGGREGATES)
                .contains("GROUP BY account.purpose, account.owner_kind, counterparty.code, line.currency, line.scale,"
                        + " line.direction")
                .contains("SUM(line.amount_minor)");
    }

    @Test
    @DisplayName("the drill-down: the figure's keys and currency, resumed by the index-usable row predicate, ordered as"
            + " ledger V029's index then the line's order, limited")
    void theDrillDownIsAKeyset() {
        assertThat(LedgerJournalReads.LINES).contains("line.currency = ?")
                .contains("(account.purpose || '/' || account.owner_kind) = ANY (?)");
        assertThat(LedgerJournalReads.AFTER).contains("(entry.posting_date, entry.id) >= (?, ?)")
                .contains("(entry.posting_date, entry.id) > (?, ?) OR line.seq > ?");
        assertThat(LedgerJournalReads.PAGE).isEqualTo(" ORDER BY entry.posting_date, entry.id, line.seq LIMIT ?");
        assertThat(LedgerJournalReads.ENTRY_LINES).endsWith("WHERE line.entry_id = ? ORDER BY line.seq");
    }
}
