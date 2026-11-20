package com.finapp.payments;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@link RefundProposalStore} over {@code payments.refund_proposal} (`P17-TSK-031`, V043). */
public final class JdbcRefundProposalStore implements RefundProposalStore<Connection> {

    private static final String TABLE = "payments.refund_proposal";

    private static final String COLUMNS = "id, intent_id, amount_minor, currency, scale, cumulative_minor, threshold_version,"
            + " proposal_reason, proposed_by, proposed_by_type, proposed_at, proposal_correlation_id, status, decided_by,"
            + " decided_by_type, decided_at, decision_reason, decision_correlation_id, refund_id";

    /** PostgreSQL's unique violation. Locale-independent, unlike the messages. */
    private static final String UNIQUE_VIOLATION = "23505";

    @Override
    public void insert(Connection unitOfWork, RefundProposal proposed) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(proposed, "proposed must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement("INSERT INTO " + TABLE + " (id, intent_id, amount_minor,"
                + " currency, scale, cumulative_minor, threshold_version, proposal_reason, proposed_by, proposed_by_type,"
                + " proposed_at, proposal_correlation_id, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROPOSED')")) {
            insert.setObject(1, proposed.id().value());
            insert.setObject(2, proposed.intentId().value());
            insert.setLong(3, proposed.amount().minorUnits());
            insert.setString(4, proposed.amount().currency().code());
            insert.setInt(5, proposed.amount().scale());
            insert.setLong(6, proposed.cumulative().minorUnits());
            insert.setInt(7, proposed.thresholdVersion());
            insert.setString(8, proposed.proposalReason());
            insert.setString(9, proposed.proposedBy());
            insert.setString(10, proposed.proposedByType());
            insert.setTimestamp(11, Timestamp.from(proposed.proposedAt()));
            insert.setString(12, proposed.proposalCorrelationId());
            insert.executeUpdate();
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())) {
                throw new RefundProposal.Refused(RefundProposal.Refused.Reason.ALREADY_PROPOSED,
                        "the payment already has an open refund proposal; decide it first");
            }
            throw new PaymentsStorageException(DatabaseFailure.describe("proposing a refund", failure));
        }
    }

    @Override
    public Optional<RefundProposal> find(Connection unitOfWork, RefundProposal.Id id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(
                // P17-TSK-031: by id alone - the approver's read of another person's proposal; PAYMENT_REFUND_APPROVE, the
                // factor and four eyes at app's door stand in for an owner predicate (OwnershipIsScopedTest, ADMINISTERED).
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ?")) {
            read.setObject(1, id.value());
            return single(read);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("reading a refund proposal", failure));
        }
    }

    @Override
    public Optional<RefundProposal> lock(Connection unitOfWork, RefundProposal.Id id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(
                // P17-TSK-031: the deciders' lock, by id alone (ADMINISTERED, as find).
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ? FOR UPDATE")) {
            read.setObject(1, id.value());
            return single(read);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("locking a refund proposal", failure));
        }
    }

    @Override
    public Optional<RefundProposal> findOpenFor(Connection unitOfWork, PaymentIntentId intentId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(intentId, "intentId must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(
                // P17-TSK-031: the payment's open proposal - the operator refund door's own payment, under PAYMENT_REFUND.
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE intent_id = ? AND status = 'PROPOSED'")) {
            read.setObject(1, intentId.value());
            return single(read);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("reading a payment's open refund proposal", failure));
        }
    }

    @Override
    public boolean decide(Connection unitOfWork, RefundProposal decided) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(decided, "decided must not be null");
        RefundProposal.Decision decision = decided.decision()
                .orElseThrow(() -> new IllegalArgumentException("a decided proposal carries its decision"));
        try (PreparedStatement update = unitOfWork.prepareStatement("UPDATE " + TABLE + " SET status = ?, decided_by = ?,"
                + " decided_by_type = ?, decided_at = ?, decision_reason = ?, decision_correlation_id = ?, refund_id = ?"
                + " WHERE id = ? AND status = 'PROPOSED'")) {
            update.setString(1, decided.status().name());
            update.setString(2, decision.decidedBy());
            update.setString(3, decision.decidedByType());
            update.setTimestamp(4, Timestamp.from(decision.decidedAt()));
            update.setString(5, decision.reason());
            update.setString(6, decision.correlationId());
            update.setObject(7, decided.refundId().map(RefundId::value).orElse(null));
            update.setObject(8, decided.id().value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("deciding a refund proposal", failure));
        }
    }

    private static Optional<RefundProposal> single(PreparedStatement read) throws SQLException {
        try (ResultSet row = read.executeQuery()) {
            return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
        }
    }

    private static RefundProposal rehydrate(ResultSet row) throws SQLException {
        RefundProposal.Status status = RefundProposal.Status.valueOf(row.getString("status"));
        CurrencyCode currency = CurrencyCode.of(row.getString("currency"));
        Optional<RefundProposal.Decision> decision = status == RefundProposal.Status.PROPOSED
                ? Optional.empty()
                : Optional.of(new RefundProposal.Decision(row.getString("decided_by"), row.getString("decided_by_type"),
                        row.getTimestamp("decided_at").toInstant(), row.getString("decision_reason"),
                        row.getString("decision_correlation_id")));
        UUID refund = row.getObject("refund_id", UUID.class);
        return new RefundProposal(RefundProposal.Id.of(row.getObject("id", UUID.class)),
                PaymentIntentId.of(row.getObject("intent_id", UUID.class)),
                Money.ofMinorUnits(row.getLong("amount_minor"), currency),
                Money.ofMinorUnits(row.getLong("cumulative_minor"), currency), row.getInt("threshold_version"),
                row.getString("proposal_reason"), row.getString("proposed_by"), row.getString("proposed_by_type"),
                row.getTimestamp("proposed_at").toInstant(), row.getString("proposal_correlation_id"), status, decision,
                refund == null ? Optional.empty() : Optional.of(RefundId.of(refund)));
    }
}
