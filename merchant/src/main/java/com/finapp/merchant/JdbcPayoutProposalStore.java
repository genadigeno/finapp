package com.finapp.merchant;

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

/** {@link PayoutProposalStore} over {@code merchant.payout_proposal} (`P17-TSK-031`, V020). */
public final class JdbcPayoutProposalStore implements PayoutProposalStore<Connection> {

    private static final String TABLE = "merchant.payout_proposal";

    private static final String COLUMNS = "id, merchant_id, amount_minor, currency, scale, threshold_version,"
            + " proposal_reason, proposed_by, proposed_by_type, proposed_at, proposal_correlation_id, status, decided_by,"
            + " decided_by_type, decided_at, decision_reason, decision_correlation_id, payout_id";

    /** PostgreSQL's unique violation. Locale-independent, unlike the messages. */
    private static final String UNIQUE_VIOLATION = "23505";

    @Override
    public void insert(Connection unitOfWork, PayoutProposal proposed) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(proposed, "proposed must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement("INSERT INTO " + TABLE + " (id, merchant_id, amount_minor,"
                + " currency, scale, threshold_version, proposal_reason, proposed_by, proposed_by_type,"
                + " proposed_at, proposal_correlation_id, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROPOSED')")) {
            insert.setObject(1, proposed.id().value());
            insert.setObject(2, proposed.merchantId().value());
            insert.setLong(3, proposed.amount().minorUnits());
            insert.setString(4, proposed.amount().currency().code());
            insert.setInt(5, proposed.amount().scale());
            insert.setInt(6, proposed.thresholdVersion());
            insert.setString(7, proposed.proposalReason());
            insert.setString(8, proposed.proposedBy());
            insert.setString(9, proposed.proposedByType());
            insert.setTimestamp(11, Timestamp.from(proposed.proposedAt()));
            insert.setString(11, proposed.proposalCorrelationId());
            insert.executeUpdate();
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())) {
                throw new PayoutProposal.Refused(PayoutProposal.Refused.Reason.ALREADY_PROPOSED,
                        "the merchant already has an open payout proposal; decide it first");
            }
            throw new MerchantStorageException(DatabaseFailure.describe("proposing a payout", failure));
        }
    }

    @Override
    public Optional<PayoutProposal> find(Connection unitOfWork, PayoutProposal.Id id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(
                // P17-TSK-031: by id alone - the approver's read of another person's proposal; MERCHANT_PAYOUT_APPROVE, the
                // factor and four eyes at app's door stand in for an owner predicate (OwnershipIsScopedTest, ADMINISTERED).
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ?")) {
            read.setObject(1, id.value());
            return single(read);
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("reading a payout proposal", failure));
        }
    }

    @Override
    public Optional<PayoutProposal> lock(Connection unitOfWork, PayoutProposal.Id id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(
                // P17-TSK-031: the deciders' lock, by id alone (ADMINISTERED, as find).
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ? FOR UPDATE")) {
            read.setObject(1, id.value());
            return single(read);
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("locking a payout proposal", failure));
        }
    }

    @Override
    public Optional<PayoutProposal> findOpenFor(Connection unitOfWork, MerchantId merchantId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(
                // P17-TSK-031: the merchant's open proposal - the operator payout door's own merchant, under MERCHANT_PAYOUT.
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE merchant_id = ? AND status = 'PROPOSED'")) {
            read.setObject(1, merchantId.value());
            return single(read);
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("reading a merchant's open payout proposal", failure));
        }
    }

    @Override
    public boolean decide(Connection unitOfWork, PayoutProposal decided) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(decided, "decided must not be null");
        PayoutProposal.Decision decision = decided.decision()
                .orElseThrow(() -> new IllegalArgumentException("a decided proposal carries its decision"));
        try (PreparedStatement update = unitOfWork.prepareStatement("UPDATE " + TABLE + " SET status = ?, decided_by = ?,"
                + " decided_by_type = ?, decided_at = ?, decision_reason = ?, decision_correlation_id = ?, payout_id = ?"
                + " WHERE id = ? AND status = 'PROPOSED'")) {
            update.setString(1, decided.status().name());
            update.setString(2, decision.decidedBy());
            update.setString(3, decision.decidedByType());
            update.setTimestamp(4, Timestamp.from(decision.decidedAt()));
            update.setString(5, decision.reason());
            update.setString(6, decision.correlationId());
            update.setObject(7, decided.payoutId().map(MerchantPayoutId::value).orElse(null));
            update.setObject(8, decided.id().value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("deciding a payout proposal", failure));
        }
    }

    private static Optional<PayoutProposal> single(PreparedStatement read) throws SQLException {
        try (ResultSet row = read.executeQuery()) {
            return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
        }
    }

    private static PayoutProposal rehydrate(ResultSet row) throws SQLException {
        PayoutProposal.Status status = PayoutProposal.Status.valueOf(row.getString("status"));
        CurrencyCode currency = CurrencyCode.of(row.getString("currency"));
        Optional<PayoutProposal.Decision> decision = status == PayoutProposal.Status.PROPOSED
                ? Optional.empty()
                : Optional.of(new PayoutProposal.Decision(row.getString("decided_by"), row.getString("decided_by_type"),
                        row.getTimestamp("decided_at").toInstant(), row.getString("decision_reason"),
                        row.getString("decision_correlation_id")));
        UUID payout = row.getObject("payout_id", UUID.class);
        return new PayoutProposal(PayoutProposal.Id.of(row.getObject("id", UUID.class)),
                MerchantId.of(row.getObject("merchant_id", UUID.class)),
                Money.ofMinorUnits(row.getLong("amount_minor"), currency),
                row.getInt("threshold_version"),
                row.getString("proposal_reason"), row.getString("proposed_by"), row.getString("proposed_by_type"),
                row.getTimestamp("proposed_at").toInstant(), row.getString("proposal_correlation_id"), status, decision,
                payout == null ? Optional.empty() : Optional.of(MerchantPayoutId.of(payout)));
    }
}
