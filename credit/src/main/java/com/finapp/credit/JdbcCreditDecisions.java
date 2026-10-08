package com.finapp.credit;

import com.finapp.platform.persistence.DatabaseFailure;
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
import java.util.UUID;

/**
 * The credit decision's persistence over {@code credit V011} (`P10-TSK-016`) and the published {@link CreditDecisions}
 * read. Stateless; on the caller's unit of work.
 */
public final class JdbcCreditDecisions implements CreditDecisions<Connection> {

    private static final String COLUMNS = "id, decision_request_id, party_id, profile_id, product, snapshot_id,"
            + " snapshot_sha256, outcome, currency, requested_minor, approved_minor, term_months, decided_at, valid_until,"
            + " decided_by, decided_by_type, policy_version_id, model_version_id, engine_version";

    /** What the deciding transaction records; the database stamps the instants. */
    public record NewDecision(
            CreditDecisionId id,
            DecisionRequest request,
            DecisionSnapshot snapshot,
            DecisionOutcome outcome,
            Optional<Money> approved,
            List<ReasonCode> reasons,
            Duration validity,
            String decidedBy,
            String decidedByType) {}

    /**
     * Inserts the decision and its reasons, unless the request already has one ({@code ON CONFLICT
     * (decision_request_id) DO NOTHING}); true when this call wrote it.
     */
    public boolean insert(Connection unitOfWork, NewDecision decision) {
        DecisionRequest.Application application = decision.request().application();
        PinnedVersions versions = decision.snapshot().content().versions();
        try {
            try (PreparedStatement insert = unitOfWork.prepareStatement("INSERT INTO credit.credit_decision (id,"
                    + " decision_request_id, party_id, profile_id, product, snapshot_id, snapshot_sha256, outcome, currency,"
                    + " requested_minor, approved_minor, term_months, decision_validity, decided_at, valid_until, decided_by,"
                    + " decided_by_type, policy_version_id, model_version_id, engine_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                    + " ?, ?, ?, ?, ? * interval '1 millisecond', statement_timestamp(), statement_timestamp(), ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (decision_request_id) DO NOTHING")) {
                insert.setObject(1, decision.id().value());
                insert.setObject(2, decision.request().id().value());
                insert.setObject(3, decision.request().party());
                insert.setObject(4, decision.request().profile().value());
                insert.setString(5, application.product().name());
                insert.setObject(6, decision.snapshot().id().value());
                insert.setBytes(7, decision.snapshot().sha256());
                insert.setString(8, decision.outcome().name());
                insert.setString(9, application.requested().currency().code());
                insert.setLong(10, application.requested().minorUnits());
                if (decision.approved().isPresent()) {
                    insert.setLong(11, decision.approved().get().minorUnits());
                } else {
                    insert.setNull(11, Types.BIGINT);
                }
                if (decision.outcome() == DecisionOutcome.APPROVED && application.termMonths().isPresent()) {
                    insert.setInt(12, application.termMonths().get());
                } else {
                    insert.setNull(12, Types.INTEGER);
                }
                insert.setLong(13, decision.validity().toMillis());
                insert.setString(14, decision.decidedBy());
                insert.setString(15, decision.decidedByType());
                insert.setObject(16, versions.policyVersion());
                insert.setObject(17, versions.modelVersion());
                insert.setInt(18, versions.engineVersion());
                if (insert.executeUpdate() != 1) {
                    return false;
                }
            }
            try (PreparedStatement insert = unitOfWork.prepareStatement(
                    "INSERT INTO credit.credit_decision_reason (decision_id, ordinal, reason_code) VALUES (?, ?, ?)")) {
                int ordinal = 1;
                for (ReasonCode reason : decision.reasons()) {
                    insert.setObject(1, decision.id().value());
                    insert.setInt(2, ordinal++);
                    insert.setString(3, reason.code());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return true;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("recording a credit decision", failure));
        }
    }

    @Override
    public Optional<CreditDecision> forRequest(Connection unitOfWork, UUID decisionRequest) {
        return one(unitOfWork, "SELECT " + COLUMNS + " FROM credit.credit_decision WHERE decision_request_id = ?",
                decisionRequest);
    }

    @Override
    public Optional<CreditDecision> byId(Connection unitOfWork, CreditDecisionId id) {
        return one(unitOfWork, "SELECT " + COLUMNS + " FROM credit.credit_decision WHERE id = ?", id.value());
    }

    private static Optional<CreditDecision> one(Connection unitOfWork, String sql, UUID key) {
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, key);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                UUID id = row.getObject("id", UUID.class);
                CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
                long approved = row.getLong("approved_minor");
                Optional<Money> approvedAmount = row.wasNull() ? Optional.empty()
                        : Optional.of(Money.ofMinorUnits(approved, currency));
                int term = row.getInt("term_months");
                Optional<Integer> termMonths = row.wasNull() ? Optional.empty() : Optional.of(term);
                return Optional.of(new CreditDecision(
                        CreditDecisionId.of(id),
                        row.getObject("decision_request_id", UUID.class),
                        row.getObject("party_id", UUID.class),
                        CreditProfileId.of(row.getObject("profile_id", UUID.class)),
                        CreditProduct.valueOf(row.getString("product")),
                        DecisionSnapshotId.of(row.getObject("snapshot_id", UUID.class)),
                        row.getBytes("snapshot_sha256"),
                        DecisionOutcome.valueOf(row.getString("outcome")),
                        Money.ofMinorUnits(row.getLong("requested_minor"), currency),
                        approvedAmount,
                        termMonths,
                        reasons(unitOfWork, id),
                        new PinnedVersions(row.getObject("policy_version_id", UUID.class),
                                row.getObject("model_version_id", UUID.class), row.getInt("engine_version")),
                        row.getString("decided_by"),
                        row.getString("decided_by_type"),
                        row.getTimestamp("decided_at").toInstant(),
                        row.getTimestamp("valid_until").toInstant()));
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading a credit decision", failure));
        }
    }

    private static List<ReasonCode> reasons(Connection unitOfWork, UUID decision) throws SQLException {
        List<ReasonCode> reasons = new ArrayList<>();
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT reason_code FROM credit.credit_decision_reason WHERE decision_id = ? ORDER BY ordinal")) {
            select.setObject(1, decision);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    String code = row.getString(1);
                    reasons.add(java.util.Arrays.stream(ReasonCode.values())
                            .filter(candidate -> candidate.code().equals(code))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("a stored reason code is not catalogued")));
                }
            }
        }
        return reasons;
    }
}
