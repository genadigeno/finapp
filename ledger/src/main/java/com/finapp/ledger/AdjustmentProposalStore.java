package com.finapp.ledger;

import java.time.Instant;
import java.util.Optional;

/**
 * Storage for adjustment proposals (`P3-TSK-021`). {@code T} is the unit of work — a JDBC
 * {@link java.sql.Connection} (ADR-0033).
 *
 * <p>The transitions are a conditional {@code UPDATE} whose row count is the outcome, under
 * the caller's {@code FOR UPDATE} on the proposal row: lock-then-look (`P2-TSK-015`'s
 * idiom), because the approval reads the proposal, posts the entry and links it — three
 * statements one snapshot cannot span.
 */
public interface AdjustmentProposalStore<T> {

    /**
     * Inserts a new proposal with its lines.
     *
     * @throws UnknownPostingAccountException a line names an unknown account or a currency
     *     foreign to it — `V010`'s FKs refusing at <em>proposal</em> time the line the
     *     journal would refuse at approval
     */
    void insert(T unitOfWork, AdjustmentProposal proposal);

    /** Plain read, for the surface that shows an approver what they would approve. */
    Optional<AdjustmentProposal> findById(T unitOfWork, AdjustmentProposalId id);

    /**
     * Reads the proposal under {@code SELECT … FOR UPDATE} on its row, so the caller's
     * subsequent look and decision run against a snapshot no sibling decision can invalidate
     * — N racing approvals serialise here, and the losers resume onto the winner's committed
     * decision.
     */
    Optional<AdjustmentProposal> lockById(T unitOfWork, AdjustmentProposalId id);

    /**
     * Moves {@code PROPOSED} to a terminal state, recording who and when — and, for an
     * approval, the entry the approval posted. Conditional on {@code status = 'PROPOSED'}
     * in the statement (belt under the caller's lock); the row count is the outcome.
     */
    boolean decide(
            T unitOfWork,
            AdjustmentProposalId id,
            AdjustmentProposalStatus to,
            String decidedBy,
            Instant decidedAt,
            Optional<JournalEntryId> entry);
}
