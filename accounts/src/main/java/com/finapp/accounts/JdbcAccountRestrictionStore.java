package com.finapp.accounts;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@link AccountRestrictionStore} over {@code accounts.account_restriction} (`P17-TSK-028`, V003). */
public final class JdbcAccountRestrictionStore implements AccountRestrictionStore<Connection> {

    private static final String TABLE = "accounts.account_restriction";

    private static final String COLUMNS = "id, account_id, customer_id, kind, reason_code, proposal_reason, proposed_by,"
            + " proposed_by_type, proposed_at, proposal_correlation_id, status, decided_by, decided_by_type, decided_at,"
            + " decision_reason, decision_correlation_id";

    /** PostgreSQL's unique violation. Locale-independent, unlike the messages. */
    private static final String UNIQUE_VIOLATION = "23505";

    @Override
    public void insert(Connection unitOfWork, AccountRestriction proposed) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(proposed, "proposed must not be null");
        if (proposed.status() != RestrictionStatus.PROPOSED) {
            throw new IllegalArgumentException("a restriction is born PROPOSED");
        }
        try (PreparedStatement insert = unitOfWork.prepareStatement("INSERT INTO " + TABLE + " (id, account_id, customer_id,"
                + " kind, reason_code, proposal_reason, proposed_by, proposed_by_type, proposed_at, proposal_correlation_id,"
                + " status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROPOSED')")) {
            insert.setObject(1, proposed.id().value());
            insert.setObject(2, proposed.accountId().value());
            insert.setObject(3, proposed.customerId());
            insert.setString(4, proposed.kind().name());
            insert.setString(5, proposed.reasonCode().name());
            insert.setString(6, proposed.proposalReason());
            insert.setString(7, proposed.proposedBy());
            insert.setString(8, proposed.proposedByType());
            insert.setTimestamp(9, Timestamp.from(proposed.proposedAt()));
            insert.setString(10, proposed.proposalCorrelationId());
            insert.executeUpdate();
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())) {
                throw new AccountRestrictionRefusedException(AccountRestrictionRefusedException.Reason.ALREADY_PROPOSED,
                        "the agreement already has an open restriction");
            }
            throw new AccountsStorageException(DatabaseFailure.describe("proposing a restriction", failure));
        }
    }

    @Override
    public Optional<AccountRestriction> find(Connection unitOfWork, AccountRestrictionId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(
                // P17-TSK-124: by id alone, unlocked - the approver's read of what they decide; ACCOUNT_RESTRICT_APPROVE on a
                // MULTI_FACTOR session at app's door stands in for an owner predicate (OwnershipIsScopedTest, ADMINISTERED).
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ?")) {
            read.setObject(1, id.value());
            return single(read);
        } catch (SQLException failure) {
            throw new AccountsStorageException(DatabaseFailure.describe("reading a restriction", failure));
        }
    }

    @Override
    public Optional<AccountRestriction> lock(Connection unitOfWork, AccountRestrictionId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(
                // P17-TSK-028: by id alone - the approver's lock; the permission, the factor and four eyes at app's door
                // stand in for an owner predicate (OwnershipIsScopedTest, ADMINISTERED).
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ? FOR UPDATE")) {
            read.setObject(1, id.value());
            return single(read);
        } catch (SQLException failure) {
            throw new AccountsStorageException(DatabaseFailure.describe("locking a restriction", failure));
        }
    }

    @Override
    public Optional<AccountRestriction> findOpenFor(Connection unitOfWork, CustomerAccountId accountId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(
                // P17-TSK-028: the agreement's open proposal, under the agreement's FOR UPDATE the proposer holds.
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE account_id = ? AND status = 'PROPOSED'")) {
            read.setObject(1, accountId.value());
            return single(read);
        } catch (SQLException failure) {
            throw new AccountsStorageException(DatabaseFailure.describe("reading an agreement's open restriction", failure));
        }
    }

    @Override
    public boolean decide(Connection unitOfWork, AccountRestriction decided) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(decided, "decided must not be null");
        AccountRestriction.Decision decision = decided.decision()
                .orElseThrow(() -> new IllegalArgumentException("a decided restriction carries its decision"));
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
            throw new AccountsStorageException(DatabaseFailure.describe("deciding a restriction", failure));
        }
    }

    private static Optional<AccountRestriction> single(PreparedStatement read) throws SQLException {
        try (ResultSet row = read.executeQuery()) {
            return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
        }
    }

    private static AccountRestriction rehydrate(ResultSet row) throws SQLException {
        RestrictionStatus status = RestrictionStatus.valueOf(row.getString("status"));
        Optional<AccountRestriction.Decision> decision = status == RestrictionStatus.PROPOSED
                ? Optional.empty()
                : Optional.of(new AccountRestriction.Decision(row.getString("decided_by"), row.getString("decided_by_type"),
                        row.getTimestamp("decided_at").toInstant(), row.getString("decision_reason"),
                        row.getString("decision_correlation_id")));
        return new AccountRestriction(AccountRestrictionId.of(row.getObject("id", UUID.class)),
                CustomerAccountId.of(row.getObject("account_id", UUID.class)), row.getObject("customer_id", UUID.class),
                RestrictionKind.valueOf(row.getString("kind")), RestrictionReason.valueOf(row.getString("reason_code")),
                row.getString("proposal_reason"), row.getString("proposed_by"), row.getString("proposed_by_type"),
                row.getTimestamp("proposed_at").toInstant(), row.getString("proposal_correlation_id"), status, decision);
    }
}
