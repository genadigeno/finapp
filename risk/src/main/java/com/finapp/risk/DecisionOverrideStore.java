package com.finapp.risk;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for decision overrides (`P13-TSK-012`; {@code risk V007}'s table, {@code risk V009}'s machine; ADR-0033) - a
 * port on the caller's unit of work. Every edge is conditional and the arbiters are the database's: one standing
 * override per assessment (the partial uniques), {@code PROPOSED} to a decision once, an action's outcome once. The
 * instants are the database clock's, stamped by the trigger.
 */
public interface DecisionOverrideStore {

    /** A proposal, judged by the domain before it is written. */
    record NewOverride(
            DecisionOverrideId id,
            RiskAssessmentId assessment,
            OverrideEffect effect,
            Optional<UUID> target,
            OverrideReason reasonCode,
            String reason,
            boolean requiresSecondPerson,
            Actor proposer,
            CorrelationId correlation) {
        public NewOverride {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(assessment, "assessment");
            Objects.requireNonNull(effect, "effect");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(proposer, "proposer");
            Objects.requireNonNull(correlation, "correlation");
        }
    }

    /**
     * Inserts {@code proposal} {@code PROPOSED} - {@code ON CONFLICT DO NOTHING}: false when the assessment already has a
     * standing override (a live proposal or an approved one), nothing written.
     */
    boolean insert(Connection unitOfWork, NewOverride proposal);

    /** The override {@code id}, if one exists - no lock. */
    Optional<DecisionOverride> find(Connection unitOfWork, DecisionOverrideId id);

    /** The override {@code id} locked {@code FOR UPDATE} - the machine's (R4). */
    Optional<DecisionOverride> lock(Connection unitOfWork, DecisionOverrideId id);

    /** Every override of {@code assessment}, oldest first - no lock. */
    List<DecisionOverride> ofAssessment(Connection unitOfWork, RiskAssessmentId assessment);

    /**
     * {@code PROPOSED → APPROVED} by {@code approver} with {@code reason}, issuing the proposed effect: a dispatchable
     * one under a permit due {@code lease} from the database's now and its first attempt; a resubmission with none.
     * Empty when the row was no longer {@code PROPOSED}.
     */
    Optional<DecisionOverride> approve(Connection unitOfWork, DecisionOverrideId id, Actor approver, String reason,
            Duration lease);

    /** {@code PROPOSED → REJECTED} by {@code rejecter} with {@code reason}; empty when no longer {@code PROPOSED}. */
    Optional<DecisionOverride> reject(Connection unitOfWork, DecisionOverrideId id, Actor rejecter, String reason);

    /**
     * Claims the oldest override whose action's permit has come on the database clock - {@code FOR UPDATE SKIP
     * LOCKED} - re-stamping its permit {@code lease} ahead and counting an attempt, so the claim commits before the
     * dispatch and another instance claims it again only once the lease has passed. Empty when none is due.
     */
    Optional<DecisionOverride> claimDue(Connection unitOfWork, Duration lease);

    /** Records {@code outcome} on {@code id}'s awaiting action - false when one is already recorded. */
    boolean conclude(Connection unitOfWork, DecisionOverrideId id, ActionOutcome outcome);
}
