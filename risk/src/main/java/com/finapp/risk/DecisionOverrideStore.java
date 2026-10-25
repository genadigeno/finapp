package com.finapp.risk;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for decision overrides (`P13-TSK-012`; {@code risk V007}'s table, {@code risk V009}'s machine, {@code risk
 * V013}'s fold; ADR-0033) - a port on the caller's unit of work. Every edge is conditional and the arbiters are the
 * database's: one standing override per assessment (the partial uniques), {@code PROPOSED} to a decision once. The
 * instants are the database clock's, stamped by the trigger. The issued action is the action machine's since
 * `P13-TSK-017` ({@link RiskActionStore}); a read serves its permit, attempts and outcome beside the override (the
 * {@code risk_action} row under the override's id).
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
     * {@code PROPOSED → APPROVED} by {@code approver} with {@code reason}, the approval naming the proposed effect as the
     * action it issues - the action itself is the caller's to issue into the machine in the same transaction
     * ({@link ActionIssuer}). Empty when the row was no longer {@code PROPOSED}.
     */
    Optional<DecisionOverride> approve(Connection unitOfWork, DecisionOverrideId id, Actor approver, String reason);

    /** {@code PROPOSED → REJECTED} by {@code rejecter} with {@code reason}; empty when no longer {@code PROPOSED}. */
    Optional<DecisionOverride> reject(Connection unitOfWork, DecisionOverrideId id, Actor rejecter, String reason);
}
