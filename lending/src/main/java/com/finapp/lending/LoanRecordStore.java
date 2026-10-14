package com.finapp.lending;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;

/**
 * Reads a loan's whole record - its rows, its pinned terms and offer, its ledger accounts and their journal lines - on the
 * caller's connection (`P11-TSK-029`). Read-only: the caller holds ONE {@code REPEATABLE READ} snapshot, so every part of
 * the record is one state.
 */
public interface LoanRecordStore {

    /** Every loan account's id, oldest first - the proofs' population. */
    List<LoanId> loanIds(Connection unitOfWork);

    /** {@code loan}'s whole record, or empty when no such loan exists. */
    Optional<LoanRecord> read(Connection unitOfWork, LoanId loan);
}
