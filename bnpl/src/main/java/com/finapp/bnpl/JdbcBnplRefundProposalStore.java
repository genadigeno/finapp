package com.finapp.bnpl;

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

/** {@link BnplRefundProposalStore} over {@code bnpl.refund_proposal} (`P17-TSK-116`, V023). */
public final class JdbcBnplRefundProposalStore implements BnplRefundProposalStore<Connection> {

    private static final String TABLE = "bnpl.refund_proposal";

    private static final String COLUMNS = "id, purchase_id, amount_minor, currency, scale, cumulative_minor, threshold_version,"
            + " proposal_reason, proposed_by, proposed_by_type, proposed_at, proposal_correlation_id, status, decided_by,"
            + " decided_by_type, decided_at, decision_reason, decision_correlation_id, refund_id";

    /** PostgreSQL's unique violation. Locale-independent, unlike the messages. */
    private static final String UNIQUE_VIOLATION = "23505";

    @Override
    public void insert(Connection unitOfWork, BnplRefundProposal proposed) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(proposed, "proposed must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement("INSERT INTO " + TABLE + " (id, purchase_id, amount_minor,"
                + " currency, scale, cumulative_minor, threshold_version, proposal_reason, proposed_by, proposed_by_type,"
                + " proposed_at, proposal_correlation_id, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROPOSED')")) {
            insert.setObject(1, proposed.id().value());
            insert.setObject(2, proposed.purchaseId().value());
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
                throw new BnplRefundProposal.Refused(BnplRefundProposal.Refused.Reason.ALREADY_PROPOSED,
                        "the purchase already has an open refund proposal; decide it first");
            }
            throw new BnplStorageException(DatabaseFailure.describe("proposing a refund", failure));
        }
    }

    @Override
    public Optional<BnplRefundProposal> find(Connection unitOfWork, BnplRefundProposal.Id id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(
                // P17-TSK-116: by id alone - the approver's read of another person's proposal; PAYMENT_REFUND_APPROVE, the
                // factor and four eyes at app's door stand in for an owner predicate (OwnershipIsScopedTest, ADMINISTERED).
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ?")) {
            read.setObject(1, id.value());
            return single(read);
        } catch (SQLException failure) {
            throw new BnplStorageException(DatabaseFailure.describe("reading a refund proposal", failure));
        }
    }

    @Override
    public Optional<BnplRefundProposal> lock(Connection unitOfWork, BnplRefundProposal.Id id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(
                // P17-TSK-116: the deciders' lock, by id alone (ADMINISTERED, as find).
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ? FOR UPDATE")) {
            read.setObject(1, id.value());
            return single(read);
        } catch (SQLException failure) {
            throw new BnplStorageException(DatabaseFailure.describe("locking a refund proposal", failure));
        }
    }

    @Override
    public Optional<BnplRefundProposal> findOpenFor(Connection unitOfWork, BnplPurchaseId purchaseId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(purchaseId, "purchaseId must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(
                // P17-TSK-116: the purchase's open proposal - the operator refund door's own purchase, under PAYMENT_REFUND.
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE purchase_id = ? AND status = 'PROPOSED'")) {
            read.setObject(1, purchaseId.value());
            return single(read);
        } catch (SQLException failure) {
            throw new BnplStorageException(DatabaseFailure.describe("reading a purchase's open refund proposal", failure));
        }
    }

    @Override
    public boolean decide(Connection unitOfWork, BnplRefundProposal decided) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(decided, "decided must not be null");
        BnplRefundProposal.Decision decision = decided.decision()
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
            update.setObject(7, decided.refundId().orElse(null));
            update.setObject(8, decided.id().value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new BnplStorageException(DatabaseFailure.describe("deciding a refund proposal", failure));
        }
    }

    private static Optional<BnplRefundProposal> single(PreparedStatement read) throws SQLException {
        try (ResultSet row = read.executeQuery()) {
            return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
        }
    }

    private static BnplRefundProposal rehydrate(ResultSet row) throws SQLException {
        BnplRefundProposal.Status status = BnplRefundProposal.Status.valueOf(row.getString("status"));
        CurrencyCode currency = CurrencyCode.of(row.getString("currency"));
        int scale = row.getInt("scale");
        Optional<BnplRefundProposal.Decision> decision = status == BnplRefundProposal.Status.PROPOSED
                ? Optional.empty()
                : Optional.of(new BnplRefundProposal.Decision(row.getString("decided_by"), row.getString("decided_by_type"),
                        row.getTimestamp("decided_at").toInstant(), row.getString("decision_reason"),
                        row.getString("decision_correlation_id")));
        UUID refund = row.getObject("refund_id", UUID.class);
        return new BnplRefundProposal(BnplRefundProposal.Id.of(row.getObject("id", UUID.class)),
                BnplPurchaseId.of(row.getObject("purchase_id", UUID.class)),
                Money.ofPersisted(row.getLong("amount_minor"), currency, scale),
                Money.ofPersisted(row.getLong("cumulative_minor"), currency, scale), row.getInt("threshold_version"),
                row.getString("proposal_reason"), row.getString("proposed_by"), row.getString("proposed_by_type"),
                row.getTimestamp("proposed_at").toInstant(), row.getString("proposal_correlation_id"), status, decision,
                refund == null ? Optional.empty() : Optional.of(refund));
    }
}
