package com.finapp.ledger;

import java.sql.Connection;

/**
 * The ledger appending to its own journal, for {@code ModuleBoundaryRulesTest}'s teeth (`P15-REM-001`): the exemption of
 * {@code onlyTheLedgerAppendsToTheJournal}, which must hold, or no posting could be written. A test class claiming
 * {@code com.finapp.ledger}; see {@code SystemClockProbe} for why.
 */
@SuppressWarnings("unused")
public final class AppendsInsideTheLedgerProbe {

    private AppendsInsideTheLedgerProbe() {}

    static void post(JournalEntryStore<Connection> journal, Connection unitOfWork, JournalEntry entry, PostingAttribution attribution) {
        journal.append(unitOfWork, entry, attribution);
    }
}
