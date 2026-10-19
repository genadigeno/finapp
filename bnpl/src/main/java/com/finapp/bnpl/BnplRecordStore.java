package com.finapp.bnpl;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;

/**
 * Reads a BNPL obligation's whole record - its rows, its pinned terms and offer, its ledger accounts and their journal
 * lines, and its financing's and refunds' lines on the accounts it shares - on the caller's connection (`P12-TSK-025`;
 * lending's {@code LoanRecordStore}). Read-only: the caller holds ONE {@code REPEATABLE READ} snapshot, so every part of the
 * record is one state.
 */
public interface BnplRecordStore {

    /** Every obligation's id, oldest first - the proofs' population. */
    List<BnplObligationId> obligationIds(java.sql.Connection unitOfWork);

    /** {@code obligation}'s whole record, or empty when no such obligation exists. */
    Optional<BnplRecord> read(Connection unitOfWork, BnplObligationId obligation);
}
