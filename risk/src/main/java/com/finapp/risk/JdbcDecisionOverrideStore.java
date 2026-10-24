package com.finapp.risk;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link DecisionOverrideStore} over {@code risk V007}'s {@code decision_override} and {@code risk V009}'s machine
 * (`P13-TSK-012`): the birth {@code ON CONFLICT DO NOTHING} against the two partial uniques (one live proposal, one
 * standing override per assessment); every edge one conditional {@code UPDATE … RETURNING}, the trigger judging it again
 * and stamping the instants on the database clock; the re-drive's claim one statement, {@code FOR UPDATE SKIP LOCKED},
 * the permit re-stamped in the same statement. Risk's own table only - no other schema is named ({@code INV-RSK-02}). A
 * failure is named by its operation and SQLSTATE only, never the driver's message.
 */
public final class JdbcDecisionOverrideStore implements DecisionOverrideStore {

    static final String COLUMNS = "id, assessment_id, status, effect, target_id, reason_code, reason,"
            + " requires_second_person, proposed_by, proposed_by_type, proposed_at, decided_by, decided_by_type,"
            + " decided_at, decision_reason, action, action_outcome, action_due_at, action_attempts, action_concluded_at,"
            + " correlation_id";

    static final String INSERT = "INSERT INTO risk.decision_override (id, assessment_id, status, effect, target_id,"
            + " reason_code, reason, requires_second_person, proposed_by, proposed_by_type, proposed_at, correlation_id)"
            + " VALUES (?, ?, 'PROPOSED', ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?) ON CONFLICT DO NOTHING RETURNING id";

    static final String APPROVE = "UPDATE risk.decision_override SET status = 'APPROVED', decided_by = ?,"
            + " decided_by_type = ?, decision_reason = ?, action = effect,"
            + " action_due_at = CASE WHEN effect = 'RESUBMISSION' THEN NULL"
            + " ELSE clock_timestamp() + ? * interval '1 millisecond' END,"
            + " action_attempts = CASE WHEN effect = 'RESUBMISSION' THEN 0 ELSE 1 END"
            + " WHERE id = ? AND status = 'PROPOSED' RETURNING " + COLUMNS;

    static final String REJECT = "UPDATE risk.decision_override SET status = 'REJECTED', decided_by = ?,"
            + " decided_by_type = ?, decision_reason = ? WHERE id = ? AND status = 'PROPOSED' RETURNING " + COLUMNS;

    static final String CLAIM_DUE = "UPDATE risk.decision_override SET action_due_at = clock_timestamp()"
            + " + ? * interval '1 millisecond', action_attempts = action_attempts + 1"
            + " WHERE id = (SELECT id FROM risk.decision_override WHERE action_due_at <= clock_timestamp()"
            + " ORDER BY action_due_at, id LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING " + COLUMNS;

    static final String CONCLUDE = "UPDATE risk.decision_override SET action_outcome = ?, action_due_at = NULL"
            + " WHERE id = ? AND status = 'APPROVED' AND action_outcome IS NULL AND action_due_at IS NOT NULL";

    @Override
    public boolean insert(Connection unitOfWork, NewOverride proposal) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(proposal, "proposal");
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT)) {
            int i = 0;
            insert.setObject(++i, proposal.id().value());
            insert.setObject(++i, proposal.assessment().value());
            insert.setString(++i, proposal.effect().name());
            insert.setObject(++i, proposal.target().orElse(null), Types.OTHER);
            insert.setString(++i, proposal.reasonCode().name());
            insert.setString(++i, proposal.reason());
            insert.setBoolean(++i, proposal.requiresSecondPerson());
            insert.setString(++i, proposal.proposer().id());
            insert.setString(++i, proposal.proposer().type().name());
            insert.setString(++i, proposal.correlation().value());
            try (ResultSet born = insert.executeQuery()) {
                return born.next();
            }
        } catch (SQLException failure) {
            throw failed("inserting a decision override", failure);
        }
    }

    @Override
    public Optional<DecisionOverride> find(Connection unitOfWork, DecisionOverrideId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + " FROM risk.decision_override WHERE id = ?")) {
            select.setObject(1, id.value());
            return single(select);
        } catch (SQLException failure) {
            throw failed("reading a decision override", failure);
        }
    }

    @Override
    public Optional<DecisionOverride> lock(Connection unitOfWork, DecisionOverrideId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + " FROM risk.decision_override WHERE id = ? FOR UPDATE")) {
            select.setObject(1, id.value());
            return single(select);
        } catch (SQLException failure) {
            throw failed("locking a decision override", failure);
        }
    }

    @Override
    public List<DecisionOverride> ofAssessment(Connection unitOfWork, RiskAssessmentId assessment) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(assessment, "assessment");
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT " + COLUMNS
                + " FROM risk.decision_override WHERE assessment_id = ? ORDER BY proposed_at, id")) {
            select.setObject(1, assessment.value());
            List<DecisionOverride> found = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    found.add(override(rows));
                }
            }
            return List.copyOf(found);
        } catch (SQLException failure) {
            throw failed("reading an assessment's overrides", failure);
        }
    }

    @Override
    public Optional<DecisionOverride> approve(Connection unitOfWork, DecisionOverrideId id, Actor approver,
            String reason, Duration lease) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(approver, "approver");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(lease, "lease");
        try (PreparedStatement update = unitOfWork.prepareStatement(APPROVE)) {
            update.setString(1, approver.id());
            update.setString(2, approver.type().name());
            update.setString(3, reason);
            update.setLong(4, millis(lease));
            update.setObject(5, id.value());
            return single(update);
        } catch (SQLException failure) {
            throw failed("approving a decision override", failure);
        }
    }

    @Override
    public Optional<DecisionOverride> reject(Connection unitOfWork, DecisionOverrideId id, Actor rejecter,
            String reason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rejecter, "rejecter");
        Objects.requireNonNull(reason, "reason");
        try (PreparedStatement update = unitOfWork.prepareStatement(REJECT)) {
            update.setString(1, rejecter.id());
            update.setString(2, rejecter.type().name());
            update.setString(3, reason);
            update.setObject(4, id.value());
            return single(update);
        } catch (SQLException failure) {
            throw failed("rejecting a decision override", failure);
        }
    }

    @Override
    public Optional<DecisionOverride> claimDue(Connection unitOfWork, Duration lease) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(lease, "lease");
        try (PreparedStatement claim = unitOfWork.prepareStatement(CLAIM_DUE)) {
            claim.setLong(1, millis(lease));
            return single(claim);
        } catch (SQLException failure) {
            throw failed("claiming a due override action", failure);
        }
    }

    @Override
    public boolean conclude(Connection unitOfWork, DecisionOverrideId id, ActionOutcome outcome) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(outcome, "outcome");
        try (PreparedStatement update = unitOfWork.prepareStatement(CONCLUDE)) {
            update.setString(1, outcome.name());
            update.setObject(2, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("recording an override action's outcome", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static Optional<DecisionOverride> single(PreparedStatement statement) throws SQLException {
        try (ResultSet row = statement.executeQuery()) {
            return row.next() ? Optional.of(override(row)) : Optional.empty();
        }
    }

    private static DecisionOverride override(ResultSet row) throws SQLException {
        return new DecisionOverride(
                DecisionOverrideId.of(row.getObject("id", UUID.class)),
                RiskAssessmentId.of(row.getObject("assessment_id", UUID.class)),
                OverrideStatus.valueOf(row.getString("status")),
                OverrideEffect.valueOf(row.getString("effect")),
                Optional.ofNullable(row.getObject("target_id", UUID.class)),
                OverrideReason.valueOf(row.getString("reason_code")),
                row.getString("reason"),
                row.getBoolean("requires_second_person"),
                row.getString("proposed_by"),
                ActorType.valueOf(row.getString("proposed_by_type")),
                instant(row, "proposed_at"),
                Optional.ofNullable(row.getString("decided_by")),
                Optional.ofNullable(row.getString("decided_by_type")).map(ActorType::valueOf),
                instant(row, "decided_at"),
                Optional.ofNullable(row.getString("decision_reason")),
                Optional.ofNullable(row.getString("action")).map(OverrideEffect::valueOf),
                Optional.ofNullable(row.getString("action_outcome")).map(ActionOutcome::valueOf),
                instant(row, "action_due_at"),
                row.getInt("action_attempts"),
                instant(row, "action_concluded_at"),
                row.getString("correlation_id"));
    }

    private static Optional<Instant> instant(ResultSet row, String column) throws SQLException {
        Timestamp at = row.getTimestamp(column);
        return Optional.ofNullable(at).map(Timestamp::toInstant);
    }

    /** The lease in whole milliseconds - an integer to the database, never a floating-point interval. */
    private static long millis(Duration lease) {
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("an action's lease is positive");
        }
        return lease.toMillis();
    }

    private static RiskStorageException failed(String operation, SQLException failure) {
        return new RiskStorageException("risk storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
