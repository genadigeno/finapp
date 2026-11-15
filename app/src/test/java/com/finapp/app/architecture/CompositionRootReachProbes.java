package com.finapp.app.architecture;

import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalEntry;
import com.finapp.ledger.PostingAttribution;
import com.finapp.ledger.SqlStoreProbe;
import com.finapp.ledger.TrialBalance;
import java.sql.Connection;

/**
 * The composition root reaching into a module, for {@code ModuleBoundaryRulesTest}'s teeth (`P15-REM-001`). Each nested class
 * sits in {@code com.finapp.app.architecture}, so the rules see it as {@code app} and not as its wiring: the wiring is
 * {@link WiresAStoreAdapterProbe}, which must be a top-level class because the rule reads the top-level class's annotation.
 */
@SuppressWarnings("unused")
final class CompositionRootReachProbes {

    private CompositionRootReachProbes() {}

    /** A ledger store adapter named by a class that is not the wiring: refused - use its port. */
    static final class NamesAStoreAdapterOutsideTheWiring {
        private final JdbcLedgerAccountStore accounts = new JdbcLedgerAccountStore();
    }

    /** Only a record nested in a store adapter: still the adapter's, refused. */
    static final class NamesANestedTypeOfAStoreAdapter {
        private SqlStoreProbe.Row row;
    }

    /** A module's published service that runs its own statements: the composition root is its caller, permitted. */
    static final class CallsAModulesSqlService {
        private TrialBalance balance;
    }

    /** The journal appended through its adapter from the composition root: refused by the journal rule. */
    static final class AppendsThroughTheJournalAdapter {
        void post(JdbcJournalEntryStore journal, Connection unitOfWork, JournalEntry entry, PostingAttribution attribution) {
            journal.append(unitOfWork, entry, attribution);
        }
    }
}
