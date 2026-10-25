package com.finapp.risk;

import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The risk action's rows (`P13-TSK-017`; {@code risk V013}'s {@code risk_action}) - every write one statement on risk's own
 * table, conditional on the state it leaves; the stamps the trigger's on the database clock; no {@code DELETE}.
 */
public interface RiskActionStore {

    /** A case's proposal: its id is the case's {@code ACTION_PROPOSED} investigator action's (written just before). */
    record NewProposal(RiskActionId id, RiskCaseId caseId, ActionKind kind, UUID target, String reasonCode,
            Actor proposer, String correlationId) {
        public NewProposal {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(caseId, "caseId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(proposer, "proposer");
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    /** An approved override's issued action: its id the override's, born {@code APPROVED} with a permit due now. */
    record NewIssue(DecisionOverrideId override, ActionKind kind, UUID target, String reasonCode,
            RiskCase.Person proposer, RiskCase.Person decider, String correlationId) {
        public NewIssue {
            Objects.requireNonNull(override, "override");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(proposer, "proposer");
            Objects.requireNonNull(decider, "decider");
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    /**
     * Inserts a case's proposal {@code PROPOSED} - {@code ON CONFLICT DO NOTHING} against {@code risk_action_one_live}:
     * empty when a live twin (case, kind, target) already stands.
     */
    Optional<RiskAction> insertProposed(Connection unitOfWork, NewProposal proposal);

    /** Inserts an approved override's action {@code APPROVED}, its permit due at once. */
    RiskAction insertIssued(Connection unitOfWork, NewIssue issue);

    Optional<RiskAction> find(Connection unitOfWork, RiskActionId id);

    /** (R6) the action row {@code FOR UPDATE} - under the case's lock (R4) in T-case. */
    Optional<RiskAction> lock(Connection unitOfWork, RiskActionId id);

    /** The live action of (case, kind, target), if one stands. */
    Optional<RiskAction> findLive(Connection unitOfWork, RiskCaseId caseId, ActionKind kind, UUID target);

    /** A case's actions, oldest first. */
    List<RiskAction> ofCase(Connection unitOfWork, RiskCaseId caseId);

    /** {@code PROPOSED → APPROVED} by {@code approver} - the permit due at once; empty when it was not proposed. */
    Optional<RiskAction> approve(Connection unitOfWork, RiskActionId id, Actor approver, UUID decisionAction);

    /** {@code PROPOSED → REJECTED} by {@code rejecter}; empty when it was not proposed. */
    Optional<RiskAction> reject(Connection unitOfWork, RiskActionId id, Actor rejecter, UUID decisionAction);

    /**
     * T-act's claim of {@code id} - the approval door's: {@code FOR UPDATE SKIP LOCKED} where it is approved or dispatched
     * and its permit has come on the database clock, moved {@code DISPATCHED}, its permit {@code lease} ahead and an
     * attempt counted. Empty when another claimer holds it, its permit has not come, or it is concluded.
     */
    Optional<RiskAction> claim(Connection unitOfWork, RiskActionId id, Duration lease);

    /** The schedule's claim: the oldest due permit, as {@link #claim}. */
    Optional<RiskAction> claimNextDue(Connection unitOfWork, Duration lease);

    /**
     * Records the owner's answer once: {@code DISPATCHED → DONE | FAILED}, the permit cleared, the instant stamped; empty
     * when it was not awaiting a record (another drive recorded first).
     */
    Optional<RiskAction> conclude(Connection unitOfWork, RiskActionId id, ActionOutcome outcome);
}
