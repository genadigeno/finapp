package com.finapp.accounts;

import com.finapp.ledger.JournalEntry;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.PostingAttribution;
import com.finapp.ledger.TimestampConverterProbe;
import com.finapp.ledger.TrialBalance;
import com.finapp.ledger.internal.InternalTypeProbe;
import java.sql.Connection;

/**
 * A business module reaching into the ledger, for {@code ModuleBoundaryRulesTest}'s teeth (`P15-REM-001`). Each nested class
 * is one reach, so each refusal names its own probe.
 *
 * <p>The holder declares {@code com.finapp.accounts}, a module that depends on the ledger, while living in {@code app}'s test
 * sources: a module is derived from the package name ({@code ProductionModules.of}), and a fixture in
 * {@code com.finapp.app.architecture} would be the composition root, which the rules treat differently. Test classes, so
 * {@code DoNotIncludeTests} keeps them out of every production sweep; only the teeth tests import them.
 */
@SuppressWarnings("unused")
public final class LedgerReachProbes {

    private LedgerReachProbes() {}

    /** A type of the ledger's internal package: refused by {@code moduleInternalsArePrivateToTheirModule}. */
    public static final class ReachesLedgerInternals {
        private InternalTypeProbe internal;
    }

    /** A ledger class that runs the ledger's statements, published service or not: refused between business modules. */
    public static final class ReachesALedgerSqlService {
        private TrialBalance balance;
    }

    /** A ledger class that touches {@code java.sql} and executes no statement: not the ledger's state, so permitted. */
    public static final class NamesALedgerClassThatRunsNoStatement {
        private Object converted = TimestampConverterProbe.instant(null);
    }

    /** The journal's write port, called outside the ledger: refused by {@code onlyTheLedgerAppendsToTheJournal}. */
    public static final class AppendsToTheJournal {
        void post(JournalEntryStore<Connection> journal, Connection unitOfWork, JournalEntry entry, PostingAttribution attribution) {
            journal.append(unitOfWork, entry, attribution);
        }
    }

    /** The same append taken as a method reference, which is a call waiting to happen: refused. */
    public static final class AppendsByMethodReference {
        interface Appender {
            void append(Connection unitOfWork, JournalEntry entry, PostingAttribution attribution);
        }

        Appender appender(JournalEntryStore<Connection> journal) {
            return journal::append;
        }
    }

    /** A read of the journal through its port: the rule names the write, so this passes it. */
    public static final class ReadsTheJournal {
        Object read(JournalEntryStore<Connection> journal, Connection unitOfWork, JournalEntryId id) {
            return journal.findById(unitOfWork, id);
        }
    }
}
