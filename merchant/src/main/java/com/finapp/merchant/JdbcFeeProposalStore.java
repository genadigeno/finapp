package com.finapp.merchant;

import com.finapp.platform.money.MoneyColumns;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link FeeProposalStore} over {@code merchant.fee_schedule_version_proposal} and {@code merchant.fee_assignment_proposal}
 * (`P17-TSK-123`, V022), and the approver's reads over those two and {@code merchant.bnpl_fee_assignment_proposal} (V021).
 */
public final class JdbcFeeProposalStore implements FeeProposalStore<Connection> {

    private static final String VERSIONS = "merchant.fee_schedule_version_proposal";
    private static final String ASSIGNMENTS = "merchant.fee_assignment_proposal";

    private static final String DECISION_COLUMNS = "status, decided_by, decided_by_type, decided_at, decision_reason,"
            + " decision_correlation_id";

    private static final String VERSION_COLUMNS = "id, fee_schedule_id, rate, fixed_amount_minor, fixed_currency,"
            + " fixed_scale, rounding_policy, refund_fee_policy, effective_from, proposal_reason, proposed_by,"
            + " proposed_by_type, proposed_at, proposal_correlation_id, " + DECISION_COLUMNS + ", fee_schedule_version_id";

    private static final String ASSIGNMENT_COLUMNS = "id, merchant_id, fee_schedule_id, proposal_reason, proposed_by,"
            + " proposed_by_type, proposed_at, proposal_correlation_id, " + DECISION_COLUMNS;

    /**
     * A kind's queue: its table's open rows, the subject its doors are addressed by. {@code WHERE status = 'PROPOSED'} is the
     * table's partial unique index's predicate, so the read scans the open rows alone.
     */
    private static String pendingOf(Kind kind) {
        return switch (kind) {
            case FEE_SCHEDULE_VERSION -> "SELECT id, proposed_by, proposed_at, proposal_reason, fee_schedule_id AS subject_id"
                    + " FROM " + VERSIONS + " WHERE status = 'PROPOSED'";
            case FEE_ASSIGNMENT -> "SELECT id, proposed_by, proposed_at, proposal_reason, merchant_id AS subject_id FROM "
                    + ASSIGNMENTS + " WHERE status = 'PROPOSED'";
            case BNPL_FEE_ASSIGNMENT -> "SELECT id, proposed_by, proposed_at, proposal_reason, merchant_id AS subject_id"
                    + " FROM " + JdbcBnplFeeAssignmentProposalStore.TABLE + " WHERE status = 'PROPOSED'";
        };
    }

    /** PostgreSQL's unique violation. Locale-independent, unlike the messages. */
    private static final String UNIQUE_VIOLATION = "23505";

    // ------------------------------------------------------------------ the version proposal

    @Override
    public void insertVersion(Connection unitOfWork, FeeScheduleVersionProposal proposed) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(proposed, "proposed must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement("INSERT INTO " + VERSIONS + " (id, fee_schedule_id,"
                + " rate, fixed_amount_minor, fixed_currency, fixed_scale, rounding_policy, refund_fee_policy,"
                + " effective_from, proposal_reason, proposed_by, proposed_by_type, proposed_at, proposal_correlation_id,"
                + " status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROPOSED')")) {
            insert.setObject(1, proposed.id().value());
            insert.setObject(2, proposed.feeScheduleId().value());
            insert.setBigDecimal(3, proposed.rate().toBigDecimal());
            insert.setLong(4, MoneyColumns.amountMinorOf(proposed.fixed()));
            insert.setString(5, MoneyColumns.currencyOf(proposed.fixed()));
            insert.setShort(6, MoneyColumns.scaleOf(proposed.fixed()));
            insert.setString(7, proposed.roundingPolicy().policyName());
            insert.setString(8, proposed.refundFeePolicy().name());
            insert.setTimestamp(9, proposed.effectiveFrom().map(Timestamp::from).orElse(null));
            insert.setString(10, proposed.proposalReason());
            insert.setString(11, proposed.proposedBy());
            insert.setString(12, proposed.proposedByType());
            insert.setTimestamp(13, Timestamp.from(proposed.proposedAt()));
            insert.setString(14, proposed.proposalCorrelationId());
            insert.executeUpdate();
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())) {
                throw new FeeProposalRefused(FeeProposalRefused.Reason.ALREADY_PROPOSED,
                        "the fee schedule already has an open version proposal; decide it first");
            }
            throw new MerchantStorageException(DatabaseFailure.describe("proposing a fee schedule version", failure));
        }
    }

    @Override
    public Optional<FeeScheduleVersionProposal> lockVersion(Connection unitOfWork, FeeScheduleVersionProposal.Id id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(
                // P17-TSK-123: the deciders' lock, by id alone - the approver's act on another person's proposal;
                // FEE_SCHEDULE_APPROVE, the factor and four eyes at app's door stand in for an owner predicate
                // (OwnershipIsScopedTest, ADMINISTERED).
                "SELECT " + VERSION_COLUMNS + " FROM " + VERSIONS + " WHERE id = ? FOR UPDATE")) {
            read.setObject(1, id.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(version(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("locking a fee schedule version proposal", failure));
        }
    }

    @Override
    public boolean decideVersion(Connection unitOfWork, FeeScheduleVersionProposal decided) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(decided, "decided must not be null");
        FeeProposalDecision decision = decided.decision()
                .orElseThrow(() -> new IllegalArgumentException("a decided proposal carries its decision"));
        try (PreparedStatement update = unitOfWork.prepareStatement("UPDATE " + VERSIONS + " SET status = ?, decided_by = ?,"
                + " decided_by_type = ?, decided_at = ?, decision_reason = ?, decision_correlation_id = ?,"
                + " fee_schedule_version_id = ? WHERE id = ? AND status = 'PROPOSED'")) {
            bindDecision(update, decided.status(), decision);
            update.setObject(7, decided.mintedVersion().map(minted -> minted.value()).orElse(null));
            update.setObject(8, decided.id().value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("deciding a fee schedule version proposal", failure));
        }
    }

    // ------------------------------------------------------------------ the assignment proposal

    @Override
    public void insertAssignment(Connection unitOfWork, FeeAssignmentProposal proposed) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(proposed, "proposed must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement("INSERT INTO " + ASSIGNMENTS + " (id, merchant_id,"
                + " fee_schedule_id, proposal_reason, proposed_by, proposed_by_type, proposed_at, proposal_correlation_id,"
                + " status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PROPOSED')")) {
            insert.setObject(1, proposed.id().value());
            insert.setObject(2, proposed.merchantId().value());
            insert.setObject(3, proposed.feeScheduleId().value());
            insert.setString(4, proposed.proposalReason());
            insert.setString(5, proposed.proposedBy());
            insert.setString(6, proposed.proposedByType());
            insert.setTimestamp(7, Timestamp.from(proposed.proposedAt()));
            insert.setString(8, proposed.proposalCorrelationId());
            insert.executeUpdate();
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())) {
                throw new FeeProposalRefused(FeeProposalRefused.Reason.ALREADY_PROPOSED,
                        "the merchant already has an open fee assignment proposal; decide it first");
            }
            throw new MerchantStorageException(DatabaseFailure.describe("proposing a fee assignment", failure));
        }
    }

    @Override
    public Optional<FeeAssignmentProposal> lockAssignment(Connection unitOfWork, FeeAssignmentProposal.Id id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(
                // P17-TSK-123: the deciders' lock, by id alone (lockVersion's reasoning).
                "SELECT " + ASSIGNMENT_COLUMNS + " FROM " + ASSIGNMENTS + " WHERE id = ? FOR UPDATE")) {
            read.setObject(1, id.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(assignment(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("locking a fee assignment proposal", failure));
        }
    }

    @Override
    public boolean decideAssignment(Connection unitOfWork, FeeAssignmentProposal decided) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(decided, "decided must not be null");
        FeeProposalDecision decision = decided.decision()
                .orElseThrow(() -> new IllegalArgumentException("a decided proposal carries its decision"));
        try (PreparedStatement update = unitOfWork.prepareStatement("UPDATE " + ASSIGNMENTS + " SET status = ?,"
                + " decided_by = ?, decided_by_type = ?, decided_at = ?, decision_reason = ?, decision_correlation_id = ?"
                + " WHERE id = ? AND status = 'PROPOSED'")) {
            bindDecision(update, decided.status(), decision);
            update.setObject(7, decided.id().value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("deciding a fee assignment proposal", failure));
        }
    }

    // ------------------------------------------------------------------ the approver's reads

    @Override
    public List<Pending> pending(Connection unitOfWork, Kind kind, Optional<PendingCursor> after, int limit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(after, "after must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("a page holds at least one proposal: " + limit);
        }
        String sql = pendingOf(kind) + (after.isPresent() ? " AND (proposed_at, id) > (?, ?)" : "")
                + " ORDER BY proposed_at, id LIMIT ?";
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            int at = 1;
            if (after.isPresent()) {
                read.setTimestamp(at++, Timestamp.from(after.get().proposedAt()));
                read.setObject(at++, after.get().id());
            }
            read.setInt(at, limit);
            List<Pending> pending = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    pending.add(new Pending(row.getObject("id", UUID.class), kind,
                            row.getString("proposed_by"), row.getTimestamp("proposed_at").toInstant(),
                            row.getString("proposal_reason"), row.getObject("subject_id", UUID.class)));
                }
            }
            return pending;
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("listing the " + kind.qualified()
                    + " proposals awaiting a decision", failure));
        }
    }

    @Override
    public Optional<Found> find(Connection unitOfWork, UUID id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try {
            // P17-TSK-123: the approver's read of what to decide, by id alone - FEE_SCHEDULE_APPROVE at app's door stands in
            // for an owner predicate (OwnershipIsScopedTest, ADMINISTERED). A UUIDv7 is one row of one table at most; a BNPL
            // fee assignment proposal is P17-TSK-124's door's, never this read's.
            Optional<Found> found = one(unitOfWork, "SELECT " + VERSION_COLUMNS + " FROM " + VERSIONS + " WHERE id = ?", id,
                    row -> new FoundVersion(version(row)));
            if (found.isEmpty()) {
                found = one(unitOfWork, "SELECT " + ASSIGNMENT_COLUMNS + " FROM " + ASSIGNMENTS + " WHERE id = ?", id,
                        row -> new FoundAssignment(assignment(row)));
            }
            return found;
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("reading a fee proposal", failure));
        }
    }

    // ------------------------------------------------------------------ plumbing

    @FunctionalInterface
    private interface RowReader {
        Found read(ResultSet row) throws SQLException;
    }

    private static Optional<Found> one(Connection unitOfWork, String sql, UUID id, RowReader reader) throws SQLException {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, id);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(reader.read(row)) : Optional.empty();
            }
        }
    }

    private static void bindDecision(PreparedStatement update, FeeProposalStatus status, FeeProposalDecision decision)
            throws SQLException {
        update.setString(1, status.name());
        update.setString(2, decision.decidedBy());
        update.setString(3, decision.decidedByType());
        update.setTimestamp(4, Timestamp.from(decision.decidedAt()));
        update.setString(5, decision.reason());
        update.setString(6, decision.correlationId());
    }

    private static Optional<FeeProposalDecision> decision(ResultSet row, FeeProposalStatus status) throws SQLException {
        return status == FeeProposalStatus.PROPOSED
                ? Optional.empty()
                : Optional.of(new FeeProposalDecision(row.getString("decided_by"), row.getString("decided_by_type"),
                        row.getTimestamp("decided_at").toInstant(), row.getString("decision_reason"),
                        row.getString("decision_correlation_id")));
    }

    private static FeeScheduleVersionProposal version(ResultSet row) throws SQLException {
        FeeProposalStatus status = FeeProposalStatus.valueOf(row.getString("status"));
        Money fixed = MoneyColumns.read(row.getLong("fixed_amount_minor"), row.getString("fixed_currency"),
                row.getShort("fixed_scale"));
        Timestamp effectiveFrom = row.getTimestamp("effective_from");
        UUID minted = row.getObject("fee_schedule_version_id", UUID.class);
        return new FeeScheduleVersionProposal(FeeScheduleVersionProposal.Id.of(row.getObject("id", UUID.class)),
                FeeScheduleId.of(row.getObject("fee_schedule_id", UUID.class)), FeeRate.ofStored(row.getBigDecimal("rate")),
                fixed, RoundingPolicy.ofName(row.getString("rounding_policy")),
                RefundFeePolicy.ofName(row.getString("refund_fee_policy")),
                Optional.ofNullable(effectiveFrom).map(Timestamp::toInstant), row.getString("proposal_reason"),
                row.getString("proposed_by"), row.getString("proposed_by_type"), row.getTimestamp("proposed_at").toInstant(),
                row.getString("proposal_correlation_id"), status, decision(row, status),
                Optional.ofNullable(minted).map(FeeScheduleVersionId::of));
    }

    private static FeeAssignmentProposal assignment(ResultSet row) throws SQLException {
        FeeProposalStatus status = FeeProposalStatus.valueOf(row.getString("status"));
        Instant proposedAt = row.getTimestamp("proposed_at").toInstant();
        return new FeeAssignmentProposal(FeeAssignmentProposal.Id.of(row.getObject("id", UUID.class)),
                MerchantId.of(row.getObject("merchant_id", UUID.class)),
                FeeScheduleId.of(row.getObject("fee_schedule_id", UUID.class)), row.getString("proposal_reason"),
                row.getString("proposed_by"), row.getString("proposed_by_type"), proposedAt,
                row.getString("proposal_correlation_id"), status, decision(row, status));
    }
}
