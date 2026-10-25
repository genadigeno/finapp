package com.finapp.risk;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link RiskActionStore} over {@code risk V013}'s {@code risk_action} (`P13-TSK-017`; ADR-0033): the case's proposal one
 * statement, {@code INSERT … ON CONFLICT (case_id, kind, target_ref) WHERE status IN (…live…) DO NOTHING RETURNING} - the
 * partial unique's inference, so a primary-key collision is never swallowed; every edge one conditional
 * {@code UPDATE … RETURNING}, the trigger judging it again and stamping the instants on the database clock; T-act's claim
 * one statement, {@code FOR UPDATE SKIP LOCKED} on a permit that has come, the permit moved a lease ahead and the attempt
 * counted in the same statement. Risk's own table only - no other schema is named ({@code INV-RSK-02}). A failure is
 * named by its operation and SQLSTATE only, never the driver's message.
 */
public final class JdbcRiskActionStore implements RiskActionStore {

    static final String COLUMNS = "id, source, case_id, override_id, kind, target_kind, target_ref, reason_code, status,"
            + " proposed_by, proposed_by_type, proposed_at, decided_by, decided_by_type, decided_at, decision_action_id,"
            + " next_attempt_at, attempts, dispatched_at, outcome, concluded_at, correlation_id";

    /** A case's proposal, under the one-live unique; the stamps the trigger's whatever is passed. */
    static final String INSERT_PROPOSED = "INSERT INTO risk.risk_action (id, source, case_id, kind, target_kind,"
            + " target_ref, reason_code, status, proposed_by, proposed_by_type, proposed_at, attempts, correlation_id)"
            + " VALUES (?, 'CASE', ?, ?, ?, ?, ?, 'PROPOSED', ?, ?, clock_timestamp(), 0, ?)"
            + " ON CONFLICT (case_id, kind, target_ref) WHERE status IN ('PROPOSED', 'APPROVED', 'DISPATCHED')"
            + " DO NOTHING RETURNING " + COLUMNS;

    /** An override's issued action - born APPROVED, due at once (the trigger copies the override's stamps). */
    static final String INSERT_ISSUED = "INSERT INTO risk.risk_action (id, source, override_id, kind, target_kind,"
            + " target_ref, reason_code, status, proposed_by, proposed_by_type, proposed_at, decided_by, decided_by_type,"
            + " decided_at, next_attempt_at, attempts, correlation_id)"
            + " VALUES (?, 'OVERRIDE', ?, ?, ?, ?, ?, 'APPROVED', ?, ?, clock_timestamp(), ?, ?, clock_timestamp(),"
            + " clock_timestamp(), 0, ?) RETURNING " + COLUMNS;

    static final String APPROVE = "UPDATE risk.risk_action SET status = 'APPROVED', decided_by = ?, decided_by_type = ?,"
            + " decision_action_id = ?, next_attempt_at = clock_timestamp()"
            + " WHERE id = ? AND status = 'PROPOSED' RETURNING " + COLUMNS;

    static final String REJECT = "UPDATE risk.risk_action SET status = 'REJECTED', decided_by = ?, decided_by_type = ?,"
            + " decision_action_id = ? WHERE id = ? AND status = 'PROPOSED' RETURNING " + COLUMNS;

    /** The claim of one action: SKIP LOCKED on its due permit, moved a lease ahead, one attempt counted. */
    static final String CLAIM = "UPDATE risk.risk_action SET status = 'DISPATCHED', attempts = attempts + 1,"
            + " next_attempt_at = clock_timestamp() + ? * interval '1 millisecond'"
            + " WHERE id = (SELECT id FROM risk.risk_action WHERE id = ? AND status IN ('APPROVED', 'DISPATCHED')"
            + " AND next_attempt_at <= clock_timestamp() FOR UPDATE SKIP LOCKED) RETURNING " + COLUMNS;

    /** The schedule's claim: the oldest due permit, on the partial index. */
    static final String CLAIM_NEXT_DUE = "UPDATE risk.risk_action SET status = 'DISPATCHED', attempts = attempts + 1,"
            + " next_attempt_at = clock_timestamp() + ? * interval '1 millisecond'"
            + " WHERE id = (SELECT id FROM risk.risk_action WHERE next_attempt_at <= clock_timestamp()"
            + " AND status IN ('APPROVED', 'DISPATCHED')"
            + " ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING " + COLUMNS;

    /** The record: conditional on DISPATCHED, once. */
    static final String CONCLUDE = "UPDATE risk.risk_action SET status = ?, outcome = ?, next_attempt_at = NULL"
            + " WHERE id = ? AND status = 'DISPATCHED' RETURNING " + COLUMNS;

    @Override
    public Optional<RiskAction> insertProposed(Connection unitOfWork, NewProposal proposal) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(proposal, "proposal");
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT_PROPOSED)) {
            int i = 0;
            insert.setObject(++i, proposal.id().value());
            insert.setObject(++i, proposal.caseId().value());
            insert.setString(++i, proposal.kind().name());
            insert.setString(++i, proposal.kind().target().name());
            insert.setObject(++i, proposal.target());
            insert.setString(++i, proposal.reasonCode());
            insert.setString(++i, proposal.proposer().id());
            insert.setString(++i, proposal.proposer().type().name());
            insert.setString(++i, proposal.correlationId());
            return single(insert);
        } catch (SQLException failure) {
            throw failed("proposing a risk action", failure);
        }
    }

    @Override
    public RiskAction insertIssued(Connection unitOfWork, NewIssue issue) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(issue, "issue");
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT_ISSUED)) {
            int i = 0;
            insert.setObject(++i, issue.override().value());
            insert.setObject(++i, issue.override().value());
            insert.setString(++i, issue.kind().name());
            insert.setString(++i, issue.kind().target().name());
            insert.setObject(++i, issue.target());
            insert.setString(++i, issue.reasonCode());
            insert.setString(++i, issue.proposer().id());
            insert.setString(++i, issue.proposer().type().name());
            insert.setString(++i, issue.decider().id());
            insert.setString(++i, issue.decider().type().name());
            insert.setString(++i, issue.correlationId());
            return single(insert).orElseThrow(() -> new RiskStorageException(
                    "risk storage failed issuing an override's action: no row returned"));
        } catch (SQLException failure) {
            throw failed("issuing an override's action", failure);
        }
    }

    @Override
    public Optional<RiskAction> find(Connection unitOfWork, RiskActionId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT " + COLUMNS
                + " FROM risk.risk_action WHERE id = ?")) {
            select.setObject(1, id.value());
            return single(select);
        } catch (SQLException failure) {
            throw failed("reading a risk action", failure);
        }
    }

    @Override
    public Optional<RiskAction> lock(Connection unitOfWork, RiskActionId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT " + COLUMNS
                + " FROM risk.risk_action WHERE id = ? FOR UPDATE")) {
            select.setObject(1, id.value());
            return single(select);
        } catch (SQLException failure) {
            throw failed("locking a risk action", failure);
        }
    }

    @Override
    public Optional<RiskAction> findLive(Connection unitOfWork, RiskCaseId caseId, ActionKind kind, UUID target) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT " + COLUMNS + " FROM risk.risk_action"
                + " WHERE case_id = ? AND kind = ? AND target_ref = ? AND status IN ('PROPOSED', 'APPROVED',"
                + " 'DISPATCHED')")) {
            select.setObject(1, caseId.value());
            select.setString(2, kind.name());
            select.setObject(3, target);
            return single(select);
        } catch (SQLException failure) {
            throw failed("reading a case's live risk action", failure);
        }
    }

    @Override
    public List<RiskAction> ofCase(Connection unitOfWork, RiskCaseId caseId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(caseId, "caseId");
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT " + COLUMNS
                + " FROM risk.risk_action WHERE case_id = ? ORDER BY proposed_at, id")) {
            select.setObject(1, caseId.value());
            List<RiskAction> found = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    found.add(action(rows));
                }
            }
            return List.copyOf(found);
        } catch (SQLException failure) {
            throw failed("reading a case's risk actions", failure);
        }
    }

    @Override
    public Optional<RiskAction> approve(Connection unitOfWork, RiskActionId id, Actor approver, UUID decisionAction) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement update = unitOfWork.prepareStatement(APPROVE)) {
            return decided(update, id, approver, decisionAction);
        } catch (SQLException failure) {
            throw failed("approving a risk action", failure);
        }
    }

    @Override
    public Optional<RiskAction> reject(Connection unitOfWork, RiskActionId id, Actor rejecter, UUID decisionAction) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement update = unitOfWork.prepareStatement(REJECT)) {
            return decided(update, id, rejecter, decisionAction);
        } catch (SQLException failure) {
            throw failed("rejecting a risk action", failure);
        }
    }

    @Override
    public Optional<RiskAction> claim(Connection unitOfWork, RiskActionId id, Duration lease) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement claim = unitOfWork.prepareStatement(CLAIM)) {
            claim.setLong(1, millis(lease));
            claim.setObject(2, id.value());
            return single(claim);
        } catch (SQLException failure) {
            throw failed("claiming a risk action", failure);
        }
    }

    @Override
    public Optional<RiskAction> claimNextDue(Connection unitOfWork, Duration lease) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        try (PreparedStatement claim = unitOfWork.prepareStatement(CLAIM_NEXT_DUE)) {
            claim.setLong(1, millis(lease));
            return single(claim);
        } catch (SQLException failure) {
            throw failed("claiming a due risk action", failure);
        }
    }

    @Override
    public Optional<RiskAction> conclude(Connection unitOfWork, RiskActionId id, ActionOutcome outcome) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(outcome, "outcome");
        try (PreparedStatement update = unitOfWork.prepareStatement(CONCLUDE)) {
            update.setString(1, RiskAction.concludedBy(outcome).name());
            update.setString(2, outcome.name());
            update.setObject(3, id.value());
            return single(update);
        } catch (SQLException failure) {
            throw failed("recording a risk action's outcome", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * A decision's parameters on the statement its public edge prepared itself - so {@code OwnershipIsScopedTest} sees
     * every method that targets an action by its id (a helper preparing it would hide them from the detector).
     */
    private static Optional<RiskAction> decided(PreparedStatement update, RiskActionId id, Actor decider,
            UUID decisionAction) throws SQLException {
        Objects.requireNonNull(decider, "decider");
        Objects.requireNonNull(decisionAction, "decisionAction");
        update.setString(1, decider.id());
        update.setString(2, decider.type().name());
        update.setObject(3, decisionAction);
        update.setObject(4, id.value());
        return single(update);
    }

    private static Optional<RiskAction> single(PreparedStatement statement) throws SQLException {
        try (ResultSet row = statement.executeQuery()) {
            return row.next() ? Optional.of(action(row)) : Optional.empty();
        }
    }

    static RiskAction action(ResultSet row) throws SQLException {
        Optional<String> decidedBy = Optional.ofNullable(row.getString("decided_by"));
        Optional<String> decidedByType = Optional.ofNullable(row.getString("decided_by_type"));
        return new RiskAction(
                RiskActionId.of(row.getObject("id", UUID.class)),
                ActionSource.valueOf(row.getString("source")),
                Optional.ofNullable(row.getObject("case_id", UUID.class)).map(RiskCaseId::of),
                Optional.ofNullable(row.getObject("override_id", UUID.class)).map(DecisionOverrideId::of),
                ActionKind.valueOf(row.getString("kind")),
                ActionTarget.valueOf(row.getString("target_kind")),
                row.getObject("target_ref", UUID.class),
                row.getString("reason_code"),
                ActionStatus.valueOf(row.getString("status")),
                new RiskCase.Person(row.getString("proposed_by"), ActorType.valueOf(row.getString("proposed_by_type"))),
                instant(row, "proposed_at"),
                decidedBy.map(id -> new RiskCase.Person(id, ActorType.valueOf(decidedByType.orElseThrow()))),
                instant(row, "decided_at"),
                Optional.ofNullable(row.getObject("decision_action_id", UUID.class)),
                instant(row, "next_attempt_at"),
                row.getInt("attempts"),
                instant(row, "dispatched_at"),
                Optional.ofNullable(row.getString("outcome")).map(ActionOutcome::valueOf),
                instant(row, "concluded_at"),
                row.getString("correlation_id"));
    }

    private static Optional<Instant> instant(ResultSet row, String column) throws SQLException {
        Timestamp at = row.getTimestamp(column);
        return Optional.ofNullable(at).map(Timestamp::toInstant);
    }

    /** The lease in whole milliseconds - an integer to the database, never a floating-point interval. */
    private static long millis(Duration lease) {
        Objects.requireNonNull(lease, "lease");
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("an action's lease is positive");
        }
        return lease.toMillis();
    }

    private static RiskStorageException failed(String operation, SQLException failure) {
        return new RiskStorageException("risk storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
