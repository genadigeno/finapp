package com.finapp.credit;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link UnderwritingCaseStore} over {@code credit V013} (`P10-TSK-018`). Stateless. */
public final class JdbcUnderwritingCaseStore implements UnderwritingCaseStore {

    private static final String SELECT = "SELECT c.id, c.decision_request_id, c.party_id, c.product, c.currency,"
            + " c.basis_evaluation_id, e.assessment_id, a.snapshot_id, e.reason_codes AS referral_codes, c.requested_minor,"
            + " c.approvable_minor, c.four_eyes_threshold_minor, c.status, c.assignee, c.first_outcome,"
            + " c.first_approved_minor, c.first_reason_codes, c.first_reason, c.first_decided_by, c.first_decided_at,"
            + " c.second_decided_by, c.closure_reason, c.opened_at, r.expires_at"
            + " FROM credit.underwriting_case c"
            + " JOIN credit.policy_evaluation e ON e.id = c.basis_evaluation_id"
            + " JOIN credit.credit_assessment a ON a.id = e.assessment_id"
            + " JOIN credit.decision_request r ON r.id = c.decision_request_id";

    @Override
    public boolean open(Connection unitOfWork, NewCase opening, Actor actor) {
        DecisionRequest request = opening.request();
        try {
            try (PreparedStatement insert = unitOfWork.prepareStatement("INSERT INTO credit.underwriting_case (id,"
                    + " decision_request_id, party_id, product, currency, basis_evaluation_id, requested_minor,"
                    + " approvable_minor, four_eyes_threshold_minor, status, opened_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', statement_timestamp())"
                    + " ON CONFLICT (decision_request_id) DO NOTHING")) {
                insert.setObject(1, opening.id().value());
                insert.setObject(2, request.id().value());
                insert.setObject(3, request.party());
                insert.setString(4, request.application().product().name());
                insert.setString(5, request.application().requested().currency().code());
                insert.setObject(6, opening.basisEvaluation().value());
                insert.setLong(7, request.application().requested().minorUnits());
                insert.setLong(8, opening.approvable().minorUnits());
                insert.setLong(9, opening.fourEyesThreshold().minorUnits());
                if (insert.executeUpdate() != 1) {
                    return false;
                }
            }
            history(unitOfWork, opening.id(), Optional.empty(), UnderwritingCaseStatus.OPEN, actor, Optional.empty(),
                    Optional.empty(), Optional.empty());
            return true;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("opening an underwriting case", failure));
        }
    }

    @Override
    public Optional<UnderwritingCase> byId(Connection unitOfWork, UnderwritingCaseId id) {
        return one(unitOfWork, SELECT + " WHERE c.id = ?", id.value(), "reading an underwriting case");
    }

    @Override
    public Optional<UnderwritingCase> lock(Connection unitOfWork, UnderwritingCaseId id) {
        return one(unitOfWork, SELECT + " WHERE c.id = ? FOR UPDATE OF c", id.value(), "locking an underwriting case");
    }

    @Override
    public Optional<UnderwritingCase> byRequest(Connection unitOfWork, DecisionRequestId request) {
        return one(unitOfWork, SELECT + " WHERE c.decision_request_id = ?", request.value(), "reading a request's underwriting case");
    }

    @Override
    public Optional<UnderwritingCase> lockByRequest(Connection unitOfWork, DecisionRequestId request) {
        return one(unitOfWork, SELECT + " WHERE c.decision_request_id = ? FOR UPDATE OF c", request.value(),
                "locking a request's underwriting case");
    }

    @Override
    public boolean move(Connection unitOfWork, UnderwritingCase from, Edge edge, Actor actor) {
        try {
            try (PreparedStatement update = unitOfWork.prepareStatement("UPDATE credit.underwriting_case SET status = ?,"
                    + " assignee = ?, first_outcome = ?, first_approved_minor = ?, first_reason_codes = ?, first_reason = ?,"
                    + " first_decided_by = ?,"
                    // the trigger stamps a new first decision's instant; a carried one keeps its own
                    + " first_decided_at = CASE ? WHEN 'NEW' THEN statement_timestamp() WHEN 'KEEP' THEN first_decided_at END,"
                    + " second_decided_by = ?, second_decided_at = CASE WHEN ?::text IS NULL THEN NULL"
                    + " ELSE statement_timestamp() END, closure_reason = ? WHERE id = ? AND status = ?")) {
                update.setString(1, edge.to().name());
                update.setString(2, edge.assignee().orElse(null));
                Optional<FirstWrite> first = edge.first();
                update.setString(3, first.map(write -> write.outcome().name()).orElse(null));
                if (first.isPresent() && first.get().approved().isPresent()) {
                    update.setLong(4, first.get().approved().get().minorUnits());
                } else {
                    update.setNull(4, Types.BIGINT);
                }
                update.setArray(5, first.isPresent() ? codes(unitOfWork, first.get().reasons()) : null);
                update.setString(6, first.map(FirstWrite::reason).orElse(null));
                update.setString(7, first.map(FirstWrite::decidedBy).orElse(null));
                update.setString(8, first.isEmpty() ? "NONE" : from.first().isPresent() ? "KEEP" : "NEW");
                update.setString(9, edge.secondDecidedBy().orElse(null));
                update.setString(10, edge.secondDecidedBy().orElse(null));
                update.setString(11, edge.closureReason().orElse(null));
                update.setObject(12, from.id().value());
                update.setString(13, from.status().name());
                if (update.executeUpdate() != 1) {
                    return false;
                }
            }
            // The history: a decision's content and reason, or a refusal's reason, and the first decider a second
            // person's act answered.
            Optional<FirstWrite> recorded = edge.first().isPresent() ? edge.first() : from.first().map(FirstWrite::of);
            boolean decision = from.status() == UnderwritingCaseStatus.ASSIGNED
                    && (edge.to() == UnderwritingCaseStatus.AWAITING_SECOND || edge.to() == UnderwritingCaseStatus.DECIDED);
            boolean second = from.status() == UnderwritingCaseStatus.AWAITING_SECOND
                    && (edge.to() == UnderwritingCaseStatus.ASSIGNED || edge.to() == UnderwritingCaseStatus.DECIDED);
            history(unitOfWork, from.id(), Optional.of(from.status()), edge.to(), actor,
                    decision || second ? recorded : Optional.empty(),
                    decision ? edge.first().map(FirstWrite::reason) : edge.reason(),
                    second ? from.first().map(UnderwritingCase.FirstDecision::decidedBy) : Optional.empty());
            return true;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("moving an underwriting case", failure));
        }
    }

    @Override
    public List<UnderwritingCase> queue(Connection unitOfWork, Optional<UnderwritingCaseStatus> status, int limit) {
        String sql = SELECT + (status.isPresent() ? " WHERE c.status = ?" : "") + " ORDER BY c.opened_at, c.id LIMIT ?";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            int index = 1;
            if (status.isPresent()) {
                select.setString(index++, status.get().name());
            }
            select.setInt(index, limit);
            List<UnderwritingCase> cases = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    cases.add(read(row));
                }
            }
            return cases;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading the review queue", failure));
        }
    }

    @Override
    public Optional<Duration> oldestOpenAge(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT (extract(epoch FROM statement_timestamp() - min(opened_at)) * 1000)::bigint"
                        + " FROM credit.underwriting_case WHERE status = 'OPEN'");
                ResultSet row = select.executeQuery()) {
            row.next();
            long millis = row.getLong(1);
            return row.wasNull() ? Optional.empty() : Optional.of(Duration.ofMillis(Math.max(0, millis)));
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading the oldest open case", failure));
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static Optional<UnderwritingCase> one(Connection unitOfWork, String sql, UUID key, String doing) {
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, key);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe(doing, failure));
        }
    }

    private static UnderwritingCase read(ResultSet row) throws SQLException {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
        Optional<UnderwritingCase.FirstDecision> first = Optional.empty();
        String outcome = row.getString("first_outcome");
        if (outcome != null) {
            long approved = row.getLong("first_approved_minor");
            Optional<Money> amount = row.wasNull() ? Optional.empty() : Optional.of(Money.ofMinorUnits(approved, currency));
            first = Optional.of(new UnderwritingCase.FirstDecision(DecisionOutcome.valueOf(outcome), amount,
                    reasons(row.getArray("first_reason_codes")), row.getString("first_reason"),
                    row.getString("first_decided_by"), row.getTimestamp("first_decided_at").toInstant()));
        }
        return new UnderwritingCase(
                UnderwritingCaseId.of(row.getObject("id", UUID.class)),
                DecisionRequestId.of(row.getObject("decision_request_id", UUID.class)),
                row.getObject("party_id", UUID.class),
                CreditProduct.valueOf(row.getString("product")),
                PolicyEvaluationId.of(row.getObject("basis_evaluation_id", UUID.class)),
                CreditAssessmentId.of(row.getObject("assessment_id", UUID.class)),
                DecisionSnapshotId.of(row.getObject("snapshot_id", UUID.class)),
                reasons(row.getArray("referral_codes")),
                Money.ofMinorUnits(row.getLong("requested_minor"), currency),
                Money.ofMinorUnits(row.getLong("approvable_minor"), currency),
                Money.ofMinorUnits(row.getLong("four_eyes_threshold_minor"), currency),
                UnderwritingCaseStatus.valueOf(row.getString("status")),
                Optional.ofNullable(row.getString("assignee")),
                first,
                Optional.ofNullable(row.getString("second_decided_by")),
                Optional.ofNullable(row.getString("closure_reason")),
                row.getTimestamp("opened_at").toInstant(),
                row.getTimestamp("expires_at").toInstant());
    }

    private static List<ReasonCode> reasons(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((String[]) array.getArray()).map(JdbcUnderwritingCaseStore::reasonCode).toList();
    }

    private static ReasonCode reasonCode(String code) {
        for (ReasonCode candidate : ReasonCode.values()) {
            if (candidate.code().equals(code)) {
                return candidate;
            }
        }
        throw new IllegalStateException("a stored reason code is not in the catalogue enum");
    }

    private static Array codes(Connection unitOfWork, List<ReasonCode> reasons) throws SQLException {
        return unitOfWork.createArrayOf("text", reasons.stream().map(ReasonCode::code).toArray(String[]::new));
    }

    private static void history(
            Connection unitOfWork,
            UnderwritingCaseId id,
            Optional<UnderwritingCaseStatus> from,
            UnderwritingCaseStatus to,
            Actor actor,
            Optional<FirstWrite> decision,
            Optional<String> reason,
            Optional<String> firstDecidedBy)
            throws SQLException {
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO credit.underwriting_case_event (id, case_id, from_status, to_status, actor_id, actor_type,"
                        + " outcome, approved_minor, reason_codes, reason, first_decided_by, occurred_at)"
                        + " VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp())")) {
            insert.setObject(1, id.value());
            insert.setString(2, from.map(Enum::name).orElse(null));
            insert.setString(3, to.name());
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setString(6, decision.map(write -> write.outcome().name()).orElse(null));
            if (decision.isPresent() && decision.get().approved().isPresent()) {
                insert.setLong(7, decision.get().approved().get().minorUnits());
            } else {
                insert.setNull(7, Types.BIGINT);
            }
            insert.setArray(8, decision.isPresent() ? codes(unitOfWork, decision.get().reasons()) : null);
            insert.setString(9, reason.orElse(null));
            insert.setString(10, firstDecidedBy.orElse(null));
            insert.executeUpdate();
        }
    }
}
