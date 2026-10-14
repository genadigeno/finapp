package com.finapp.lending;

import static com.finapp.lending.DisbursementWorld.BALANCES;
import static com.finapp.lending.DisbursementWorld.LEDGER_ACCOUNTS;
import static com.finapp.lending.DisbursementWorld.OFFER_STORE;
import static com.finapp.lending.DisbursementWorld.TERMS_STORE;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;

/**
 * The lending proofs over the shared database (`P11-TSK-029`): the real record store, subledger proof and replay, each
 * reading on a fresh read-only {@code REPEATABLE READ} snapshot of the application role - exactly as the gauges and the
 * investigator's replay read.
 */
final class LendingProofsWorld {

    static final JdbcLoanRecordStore RECORDS = new JdbcLoanRecordStore(OFFER_STORE, TERMS_STORE, LEDGER_ACCOUNTS, BALANCES);
    static final LoanSubledgerProof SUBLEDGER = new LoanSubledgerProof();
    static final LoanReplayProof REPLAY = new LoanReplayProof();
    static final LendingProofs PROOFS = new LendingProofs(RECORDS, SUBLEDGER, REPLAY);

    private LendingProofsWorld() {}

    /** {@code loan} proven on a fresh snapshot. */
    static LendingProofs.Proven proven(LoanId loan) {
        return onSnapshot(snapshot -> PROOFS.prove(snapshot, loan).orElseThrow());
    }

    /** {@code loan}'s record, read on a fresh snapshot. */
    static LoanRecord record(LoanId loan) {
        return onSnapshot(snapshot -> RECORDS.read(snapshot, loan).orElseThrow());
    }

    /** {@code work} on one read-only {@code REPEATABLE READ} snapshot, rolled back. */
    static <R> R onSnapshot(Function<Connection, R> work) {
        try (Connection snapshot = DatabaseRoles.application()) {
            snapshot.setAutoCommit(false);
            snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            snapshot.setReadOnly(true);
            try {
                return work.apply(snapshot);
            } finally {
                snapshot.rollback();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
