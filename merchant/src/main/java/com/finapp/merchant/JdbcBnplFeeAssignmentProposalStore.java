package com.finapp.merchant;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@link BnplFeeAssignmentProposalStore} over {@code merchant.bnpl_fee_assignment_proposal} (`P17-TSK-116`, V021). */
public final class JdbcBnplFeeAssignmentProposalStore implements BnplFeeAssignmentProposalStore<Connection> {

    /** Read by {@link JdbcFeeProposalStore}'s queue too (`P17-TSK-123`): this table's open rows, by kind. */
    static final String TABLE = "merchant.bnpl_fee_assignment_proposal";

    private static final String COLUMNS = "id, merchant_id, product, fee_schedule_id, proposal_reason, proposed_by,"
            + " proposed_by_type, proposed_at, proposal_correlation_id, status, decided_by, decided_by_type, decided_at,"
            + " decision_reason, decision_correlation_id";

    /** PostgreSQL's unique violation. Locale-independent, unlike the messages. */
    private static final String UNIQUE_VIOLATION = "23505";

    @Override
    public void insert(Connection unitOfWork, BnplFeeAssignmentProposal proposed) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(proposed, "proposed must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement("INSERT INTO " + TABLE + " (id, merchant_id, product,"
                + " fee_schedule_id, proposal_reason, proposed_by, proposed_by_type, proposed_at, proposal_correlation_id,"
                + " status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROPOSED')")) {
            insert.setObject(1, proposed.id().value());
            insert.setObject(2, proposed.merchantId().value());
            insert.setString(3, proposed.product().name());
            insert.setObject(4, proposed.feeScheduleId().value());
            insert.setString(5, proposed.proposalReason());
            insert.setString(6, proposed.proposedBy());
            insert.setString(7, proposed.proposedByType());
            insert.setTimestamp(8, Timestamp.from(proposed.proposedAt()));
            insert.setString(9, proposed.proposalCorrelationId());
            insert.executeUpdate();
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())) {
                throw new BnplFeeAssignmentProposal.Refused(BnplFeeAssignmentProposal.Refused.Reason.ALREADY_PROPOSED,
                        "the merchant's BNPL product already has an open fee assignment proposal; decide it first");
            }
            throw new MerchantStorageException(DatabaseFailure.describe("proposing a BNPL fee assignment", failure));
        }
    }

    @Override
    public Optional<BnplFeeAssignmentProposal> find(Connection unitOfWork, BnplFeeAssignmentProposal.Id id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(
                // P17-TSK-124: by id alone, unlocked - the decider's read of what they decide; FEE_SCHEDULE_APPROVE on a
                // MULTI_FACTOR session at app's door stands in for an owner predicate (OwnershipIsScopedTest, ADMINISTERED).
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ?")) {
            read.setObject(1, id.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("reading a BNPL fee assignment proposal", failure));
        }
    }

    @Override
    public Optional<BnplFeeAssignmentProposal> lock(Connection unitOfWork, BnplFeeAssignmentProposal.Id id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(
                // P17-TSK-116: the deciders' lock, by id alone - the approver's act on another person's proposal;
                // FEE_SCHEDULE_APPROVE, the factor and four eyes at app's door stand in for an owner predicate
                // (OwnershipIsScopedTest, ADMINISTERED).
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ? FOR UPDATE")) {
            read.setObject(1, id.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("locking a BNPL fee assignment proposal", failure));
        }
    }

    @Override
    public boolean decide(Connection unitOfWork, BnplFeeAssignmentProposal decided) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(decided, "decided must not be null");
        BnplFeeAssignmentProposal.Decision decision = decided.decision()
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
            throw new MerchantStorageException(DatabaseFailure.describe("deciding a BNPL fee assignment proposal", failure));
        }
    }

    private static BnplFeeAssignmentProposal rehydrate(ResultSet row) throws SQLException {
        BnplFeeAssignmentProposal.Status status = BnplFeeAssignmentProposal.Status.valueOf(row.getString("status"));
        Optional<BnplFeeAssignmentProposal.Decision> decision = status == BnplFeeAssignmentProposal.Status.PROPOSED
                ? Optional.empty()
                : Optional.of(new BnplFeeAssignmentProposal.Decision(row.getString("decided_by"),
                        row.getString("decided_by_type"), row.getTimestamp("decided_at").toInstant(),
                        row.getString("decision_reason"), row.getString("decision_correlation_id")));
        return new BnplFeeAssignmentProposal(BnplFeeAssignmentProposal.Id.of(row.getObject("id", UUID.class)),
                MerchantId.of(row.getObject("merchant_id", UUID.class)),
                MerchantBnplProduct.valueOf(row.getString("product")),
                FeeScheduleId.of(row.getObject("fee_schedule_id", UUID.class)), row.getString("proposal_reason"),
                row.getString("proposed_by"), row.getString("proposed_by_type"),
                row.getTimestamp("proposed_at").toInstant(), row.getString("proposal_correlation_id"), status, decision);
    }
}
