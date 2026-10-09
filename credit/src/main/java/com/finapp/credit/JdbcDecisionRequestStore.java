package com.finapp.credit;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** {@link DecisionRequestStore} over {@code credit V010} (`P10-TSK-014`). Stateless. */
public final class JdbcDecisionRequestStore implements DecisionRequestStore {

    private static final String COLUMNS = "id, party_id, profile_id, product, currency, requested_minor, term_months,"
            + " declared_income_minor, declared_expenditure_minor, status, closure_reason, submitted_at, expires_at,"
            + " pinned_policy_version_id, pinned_model_version_id, pinned_engine_version, correlation_id";

    private static final String OPEN = "('SUBMITTED', 'COLLECTING', 'READY', 'EVALUATED', 'IN_REVIEW')";

    @Override
    public boolean insert(
            Connection unitOfWork,
            DecisionRequestId id,
            UUID party,
            CreditProfileId profile,
            DecisionRequest.Application application,
            Duration validity,
            String correlation,
            Actor actor) {
        try {
            try (PreparedStatement insert = unitOfWork.prepareStatement(
                    "INSERT INTO credit.decision_request (id, party_id, profile_id, product, currency, requested_minor,"
                            + " term_months, declared_income_minor, declared_expenditure_minor, status, request_validity,"
                            + " submitted_at, expires_at, next_step_at, correlation_id)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUBMITTED', ? * interval '1 millisecond', statement_timestamp(),"
                            + " statement_timestamp(), statement_timestamp(), ?)"
                            + " ON CONFLICT (party_id, product) WHERE status IN " + OPEN + " DO NOTHING")) {
                insert.setObject(1, id.value());
                insert.setObject(2, party);
                insert.setObject(3, profile.value());
                insert.setString(4, application.product().name());
                insert.setString(5, application.requested().currency().code());
                insert.setLong(6, application.requested().minorUnits());
                if (application.termMonths().isPresent()) {
                    insert.setInt(7, application.termMonths().get());
                } else {
                    insert.setNull(7, Types.INTEGER);
                }
                minor(insert, 8, application.declaredMonthlyIncome());
                minor(insert, 9, application.declaredMonthlyExpenditure());
                insert.setLong(10, validity.toMillis());
                insert.setString(11, correlation);
                if (insert.executeUpdate() != 1) {
                    return false;
                }
            }
            history(unitOfWork, id, Optional.empty(), DecisionRequestStatus.SUBMITTED, actor, Optional.empty());
            return true;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("submitting a decision request", failure));
        }
    }

    @Override
    public Optional<DecisionRequestId> openFor(Connection unitOfWork, UUID party, CreditProduct product) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id FROM credit.decision_request WHERE party_id = ? AND product = ? AND status IN " + OPEN)) {
            select.setObject(1, party);
            select.setString(2, product.name());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(DecisionRequestId.of(row.getObject(1, UUID.class))) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading an open decision request", failure));
        }
    }

    @Override
    public Optional<DecisionRequest> ownedBy(Connection unitOfWork, DecisionRequestId id, UUID party) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + " FROM credit.decision_request WHERE id = ? AND party_id = ?")) {
            return owned(select, id, party);
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading a decision request", failure));
        }
    }

    @Override
    public Optional<DecisionRequest> lockOwnedBy(Connection unitOfWork, DecisionRequestId id, UUID party) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + " FROM credit.decision_request WHERE id = ? AND party_id = ? FOR UPDATE")) {
            return owned(select, id, party);
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("locking a decision request", failure));
        }
    }

    @Override
    public boolean transition(
            Connection unitOfWork,
            DecisionRequestId id,
            Set<DecisionRequestStatus> from,
            DecisionRequestStatus to,
            Optional<ClosureReason> closureReason,
            Actor actor,
            Optional<String> reason) {
        try {
            DecisionRequestStatus prior;
            try (PreparedStatement select = unitOfWork.prepareStatement(
                    "SELECT status FROM credit.decision_request WHERE id = ? FOR UPDATE")) {
                select.setObject(1, id.value());
                try (ResultSet row = select.executeQuery()) {
                    if (!row.next()) {
                        return false;
                    }
                    prior = DecisionRequestStatus.valueOf(row.getString(1));
                }
            }
            if (!from.contains(prior)) {
                return false;
            }
            try (PreparedStatement update = unitOfWork.prepareStatement(
                    "UPDATE credit.decision_request SET status = ?, closure_reason = ? WHERE id = ? AND status = ?")) {
                update.setString(1, to.name());
                update.setString(2, closureReason.map(Enum::name).orElse(null));
                update.setObject(3, id.value());
                update.setString(4, prior.name());
                if (update.executeUpdate() != 1) {
                    return false;
                }
            }
            history(unitOfWork, id, Optional.of(prior), to, actor, reason);
            return true;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("moving a decision request", failure));
        }
    }

    @Override
    public List<DecisionRequestId> claimDue(Connection unitOfWork, int limit, Duration permit) {
        if (limit < 1) {
            throw new IllegalArgumentException("a claim takes at least one request");
        }
        try (PreparedStatement claim = unitOfWork.prepareStatement(
                "UPDATE credit.decision_request SET next_step_at = statement_timestamp() + ? * interval '1 millisecond'"
                        + " WHERE id IN (SELECT id FROM credit.decision_request WHERE status IN " + OPEN
                        + " AND next_step_at <= statement_timestamp()"
                        + " ORDER BY next_step_at, id LIMIT ? FOR UPDATE SKIP LOCKED)"
                        + " AND status IN " + OPEN + " RETURNING id")) {
            claim.setLong(1, permit.toMillis());
            claim.setInt(2, limit);
            List<DecisionRequestId> claimed = new ArrayList<>();
            try (ResultSet rows = claim.executeQuery()) {
                while (rows.next()) {
                    claimed.add(DecisionRequestId.of(rows.getObject("id", UUID.class)));
                }
            }
            return claimed;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("claiming due decision requests", failure));
        }
    }

    @Override
    public Optional<Locked> lock(Connection unitOfWork, DecisionRequestId id) {
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT " + COLUMNS
                + ", expires_at <= statement_timestamp() AS expired FROM credit.decision_request WHERE id = ? FOR UPDATE")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(new Locked(request(row), row.getBoolean("expired"))) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("locking a decision request", failure));
        }
    }

    @Override
    public boolean pin(Connection unitOfWork, DecisionRequestId id, PinnedVersions versions, Actor actor) {
        try {
            try (PreparedStatement update = unitOfWork.prepareStatement(
                    "UPDATE credit.decision_request SET status = 'COLLECTING', pinned_policy_version_id = ?,"
                            + " pinned_model_version_id = ?, pinned_engine_version = ? WHERE id = ? AND status = 'SUBMITTED'")) {
                update.setObject(1, versions.policyVersion());
                update.setObject(2, versions.modelVersion());
                update.setInt(3, versions.engineVersion());
                update.setObject(4, id.value());
                if (update.executeUpdate() != 1) {
                    return false;
                }
            }
            history(unitOfWork, id, Optional.of(DecisionRequestStatus.SUBMITTED), DecisionRequestStatus.COLLECTING, actor,
                    Optional.empty());
            return true;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("pinning a decision request", failure));
        }
    }

    @Override
    public Optional<UUID> partyOf(Connection unitOfWork, DecisionRequestId id) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT party_id FROM credit.decision_request WHERE id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(row.getObject(1, UUID.class)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading a decision request's party", failure));
        }
    }

    @Override
    public Optional<String> correlationOf(Connection unitOfWork, DecisionRequestId id) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT correlation_id FROM credit.decision_request WHERE id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(row.getString(1)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading a decision request's correlation", failure));
        }
    }

    @Override
    public java.util.Map<DecisionRequestStatus, Duration> oldestOpenAges(Connection unitOfWork) {
        java.util.Map<DecisionRequestStatus, Duration> ages = new java.util.EnumMap<>(DecisionRequestStatus.class);
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT status, (extract(epoch FROM statement_timestamp() - min(submitted_at)) * 1000)::bigint"
                        + " FROM credit.decision_request WHERE status = ANY (?) GROUP BY status")) {
            select.setArray(1, unitOfWork.createArrayOf("text",
                    DecisionRequestStatus.OPEN.stream().map(Enum::name).toArray()));
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    ages.put(DecisionRequestStatus.valueOf(rows.getString(1)),
                            Duration.ofMillis(Math.max(0, rows.getLong(2))));
                }
            }
            return ages;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading the oldest open requests", failure));
        }
    }

    private static void history(
            Connection unitOfWork,
            DecisionRequestId id,
            Optional<DecisionRequestStatus> from,
            DecisionRequestStatus to,
            Actor actor,
            Optional<String> reason)
            throws SQLException {
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO credit.decision_request_event (id, decision_request_id, from_status, to_status, actor_id,"
                        + " actor_type, reason, occurred_at) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, statement_timestamp())")) {
            insert.setObject(1, id.value());
            insert.setString(2, from.map(Enum::name).orElse(null));
            insert.setString(3, to.name());
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setString(6, reason.orElse(null));
            insert.executeUpdate();
        }
    }

    /** Binds and runs one of the owner-scoped statements above - the statement, owner predicate included, is theirs. */
    private static Optional<DecisionRequest> owned(PreparedStatement select, DecisionRequestId id, UUID party)
            throws SQLException {
        select.setObject(1, id.value());
        select.setObject(2, party);
        try (ResultSet row = select.executeQuery()) {
            return row.next() ? Optional.of(request(row)) : Optional.empty();
        }
    }

    private static DecisionRequest request(ResultSet row) throws SQLException {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
        int term = row.getInt("term_months");
        Optional<Integer> termMonths = row.wasNull() ? Optional.empty() : Optional.of(term);
        DecisionRequest.Application application = new DecisionRequest.Application(
                CreditProduct.valueOf(row.getString("product")),
                Money.ofMinorUnits(row.getLong("requested_minor"), currency),
                termMonths,
                money(row, "declared_income_minor", currency),
                money(row, "declared_expenditure_minor", currency));
        UUID policy = row.getObject("pinned_policy_version_id", UUID.class);
        Optional<PinnedVersions> pinned = policy == null ? Optional.empty() : Optional.of(new PinnedVersions(
                policy, row.getObject("pinned_model_version_id", UUID.class), row.getInt("pinned_engine_version")));
        String closure = row.getString("closure_reason");
        return new DecisionRequest(
                DecisionRequestId.of(row.getObject("id", UUID.class)),
                row.getObject("party_id", UUID.class),
                CreditProfileId.of(row.getObject("profile_id", UUID.class)),
                application,
                DecisionRequestStatus.valueOf(row.getString("status")),
                Optional.ofNullable(closure).map(ClosureReason::valueOf),
                pinned,
                row.getTimestamp("submitted_at").toInstant(),
                row.getTimestamp("expires_at").toInstant(),
                row.getString("correlation_id"));
    }

    private static Optional<Money> money(ResultSet row, String column, CurrencyCode currency) throws SQLException {
        long minor = row.getLong(column);
        return row.wasNull() ? Optional.empty() : Optional.of(Money.ofMinorUnits(minor, currency));
    }

    private static void minor(PreparedStatement statement, int index, Optional<Money> amount) throws SQLException {
        if (amount.isPresent()) {
            statement.setLong(index, amount.get().minorUnits());
        } else {
            statement.setNull(index, Types.BIGINT);
        }
    }
}
