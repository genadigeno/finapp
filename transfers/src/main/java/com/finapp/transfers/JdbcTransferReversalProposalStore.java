package com.finapp.transfers;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@link TransferReversalProposalStore} over {@code transfers.transfer_reversal_proposal} (`P17-TSK-031`, V005). */
public final class JdbcTransferReversalProposalStore implements TransferReversalProposalStore<Connection> {

    private static final String TABLE = "transfers.transfer_reversal_proposal";

    private static final String COLUMNS = "id, transfer_id, proposal_reason, proposed_by, proposed_by_type, proposed_at,"
            + " proposal_correlation_id, status, decided_by, decided_by_type, decided_at, decision_reason,"
            + " decision_correlation_id";

    /** PostgreSQL's unique violation. Locale-independent, unlike the messages. */
    private static final String UNIQUE_VIOLATION = "23505";

    @Override
    public void insert(Connection unitOfWork, TransferReversalProposal proposed) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(proposed, "proposed must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement("INSERT INTO " + TABLE + " (id, transfer_id,"
                + " proposal_reason, proposed_by, proposed_by_type, proposed_at, proposal_correlation_id, status)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, 'PROPOSED')")) {
            insert.setObject(1, proposed.id().value());
            insert.setObject(2, proposed.transferId().value());
            insert.setString(3, proposed.proposalReason());
            insert.setString(4, proposed.proposedBy());
            insert.setString(5, proposed.proposedByType());
            insert.setTimestamp(6, Timestamp.from(proposed.proposedAt()));
            insert.setString(7, proposed.proposalCorrelationId());
            insert.executeUpdate();
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())) {
                throw new TransferReversalRefusedException(TransferReversalRefusedException.Reason.ALREADY_PROPOSED,
                        "the transfer already has an open reversal proposal");
            }
            throw new TransfersStorageException(DatabaseFailure.describe("proposing a reversal", failure));
        }
    }

    @Override
    public Optional<TransferReversalProposal> lock(Connection unitOfWork, TransferReversalProposalId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(
                // P17-TSK-031: by id alone - the deciders' lock; TRANSFER_REVERSE_APPROVE, the factor and four eyes at app's
                // door stand in for an owner predicate (OwnershipIsScopedTest, ADMINISTERED).
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ? FOR UPDATE")) {
            read.setObject(1, id.value());
            return single(read);
        } catch (SQLException failure) {
            throw new TransfersStorageException(DatabaseFailure.describe("locking a reversal proposal", failure));
        }
    }

    @Override
    public Optional<TransferReversalProposal> find(Connection unitOfWork, TransferReversalProposalId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(
                // P17-TSK-124: by id alone, unlocked - the decider's read of what they decide; TRANSFER_REVERSE_APPROVE on a
                // MULTI_FACTOR session at app's door stands in for an owner predicate (OwnershipIsScopedTest, ADMINISTERED).
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ?")) {
            read.setObject(1, id.value());
            return single(read);
        } catch (SQLException failure) {
            throw new TransfersStorageException(DatabaseFailure.describe("reading a reversal proposal", failure));
        }
    }

    @Override
    public Optional<TransferReversalProposal> findOpenFor(Connection unitOfWork, TransferId transferId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(transferId, "transferId must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(
                // P17-TSK-031: the transfer's open proposal, under the transfer's FOR UPDATE the proposer holds.
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE transfer_id = ? AND status = 'PROPOSED'")) {
            read.setObject(1, transferId.value());
            return single(read);
        } catch (SQLException failure) {
            throw new TransfersStorageException(DatabaseFailure.describe("reading a transfer's open proposal", failure));
        }
    }

    @Override
    public boolean decide(Connection unitOfWork, TransferReversalProposal decided) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(decided, "decided must not be null");
        TransferReversalProposal.Decision decision = decided.decision()
                .orElseThrow(() -> new IllegalArgumentException("a decided proposal carries its decision"));
        try (PreparedStatement update = unitOfWork.prepareStatement("UPDATE " + TABLE + " SET status = ?, decided_by = ?,"
                + " decided_by_type = ?, decided_at = ?, decision_reason = ?, decision_correlation_id = ?"
                + " WHERE id = ? AND status = 'PROPOSED'")) {
            update.setString(1, decided.status().name());
            update.setString(2, decision.decidedBy());
            update.setString(3, decision.decidedByType());
            update.setTimestamp(4, Timestamp.from(decision.decidedAt()));
            update.setString(5, decision.reason());
            update.setString(6, decision.correlationId());
            update.setObject(7, decided.id().value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new TransfersStorageException(DatabaseFailure.describe("deciding a reversal proposal", failure));
        }
    }

    private static Optional<TransferReversalProposal> single(PreparedStatement read) throws SQLException {
        try (ResultSet row = read.executeQuery()) {
            return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
        }
    }

    private static TransferReversalProposal rehydrate(ResultSet row) throws SQLException {
        ReversalProposalStatus status = ReversalProposalStatus.valueOf(row.getString("status"));
        Optional<TransferReversalProposal.Decision> decision = status == ReversalProposalStatus.PROPOSED
                ? Optional.empty()
                : Optional.of(new TransferReversalProposal.Decision(row.getString("decided_by"),
                        row.getString("decided_by_type"), row.getTimestamp("decided_at").toInstant(),
                        row.getString("decision_reason"), row.getString("decision_correlation_id")));
        return new TransferReversalProposal(TransferReversalProposalId.of(row.getObject("id", UUID.class)),
                TransferId.of(row.getObject("transfer_id", UUID.class)), row.getString("proposal_reason"),
                row.getString("proposed_by"), row.getString("proposed_by_type"), row.getTimestamp("proposed_at").toInstant(),
                row.getString("proposal_correlation_id"), status, decision);
    }

    @Override
    public List<PendingProposal> pendingReversals(Connection unitOfWork, Optional<PendingPosition> after, int limit) {
        return pending(unitOfWork,
                "SELECT id, proposed_by, proposed_at, proposal_reason AS reason, transfer_id::text AS subject"
                        + " FROM transfers.transfer_reversal_proposal WHERE status = 'PROPOSED'",
                after, limit, "listing the transfer reversals awaiting a decision");
    }

    /** A queue's page: the pending rows of {@code select}, oldest first, strictly after {@code after}. */
    private static List<PendingProposal> pending(
            Connection unitOfWork, String select, Optional<PendingPosition> after, int limit, String doing) {
        Objects.requireNonNull(after, "after must not be null");
        StringBuilder sql = new StringBuilder(select);
        List<Object> arguments = new ArrayList<>();
        after.ifPresent(position -> {
            sql.append(" AND (proposed_at, id) > (?, ?)");
            arguments.add(java.sql.Timestamp.from(position.proposedAt()));
            arguments.add(position.id());
        });
        sql.append(" ORDER BY proposed_at ASC, id ASC LIMIT ?");
        arguments.add(limit);
        try (PreparedStatement statement = unitOfWork.prepareStatement(sql.toString())) {
            for (int i = 0; i < arguments.size(); i++) {
                statement.setObject(i + 1, arguments.get(i));
            }
            List<PendingProposal> page = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    page.add(new PendingProposal(
                            row.getObject("id", java.util.UUID.class),
                            row.getString("proposed_by"),
                            row.getTimestamp("proposed_at").toInstant(),
                            row.getString("reason"),
                            row.getString("subject")));
                }
            }
            return List.copyOf(page);
        } catch (SQLException failure) {
            throw new TransfersStorageException(DatabaseFailure.describe(doing, failure));
        }
    }
}
