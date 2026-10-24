package com.finapp.risk;

import com.finapp.platform.security.ActorType;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One decision override as stored (`P13-TSK-012`; {@code risk V007}'s table, {@code risk V009}'s machine;
 * {@code RISK_LIFECYCLES.md} section 4.2a): the assessment it overrides (named, never edited - {@code INV-RSK-04}),
 * its state, the effect proposed (frozen), the effect's target (the judged command - a withdrawal's or a payment
 * intent's id; none for a {@link OverrideEffect#RESUBMISSION}), the reason code and the proposer's text
 * ({@code RESTRICTED-PII}), whether C7 requires a second holder (fixed at the proposal), the proposer and the decider,
 * the decision's reason, and the action the approval issued with its permit, attempts and the owning domain's outcome.
 * A value: the store reads it whole.
 */
public record DecisionOverride(
        DecisionOverrideId id,
        RiskAssessmentId assessment,
        OverrideStatus status,
        OverrideEffect effect,
        Optional<UUID> target,
        OverrideReason reasonCode,
        String reason,
        boolean requiresSecondPerson,
        String proposedBy,
        ActorType proposedByType,
        Optional<Instant> proposedAt,
        Optional<String> decidedBy,
        Optional<ActorType> decidedByType,
        Optional<Instant> decidedAt,
        Optional<String> decisionReason,
        Optional<OverrideEffect> action,
        Optional<ActionOutcome> actionOutcome,
        Optional<Instant> actionDueAt,
        int actionAttempts,
        Optional<Instant> actionConcludedAt,
        String correlationId) {

    public DecisionOverride {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(proposedBy, "proposedBy");
        Objects.requireNonNull(proposedByType, "proposedByType");
        Objects.requireNonNull(proposedAt, "proposedAt");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(decidedByType, "decidedByType");
        Objects.requireNonNull(decidedAt, "decidedAt");
        Objects.requireNonNull(decisionReason, "decisionReason");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actionOutcome, "actionOutcome");
        Objects.requireNonNull(actionDueAt, "actionDueAt");
        Objects.requireNonNull(actionConcludedAt, "actionConcludedAt");
        Objects.requireNonNull(correlationId, "correlationId");
        if (effect.dispatches() == target.isEmpty()) {
            throw new IllegalArgumentException("a dispatchable effect names its target; a resubmission names none");
        }
        if ((status == OverrideStatus.PROPOSED) != decidedBy.isEmpty()) {
            throw new IllegalArgumentException("a decided override names its decider, a proposed one none");
        }
        if (action.isPresent() && (status != OverrideStatus.APPROVED || action.get() != effect)) {
            throw new IllegalArgumentException("only an approval issues an action, and exactly the effect proposed");
        }
        if (actionOutcome.isPresent() && action.isEmpty()) {
            throw new IllegalArgumentException("an outcome is an issued action's");
        }
        if (actionAttempts < 0) {
            throw new IllegalArgumentException("an action's attempts are never negative");
        }
    }

    /** The key payments' door is called under for this override's action - every re-drive's the same. */
    public String actionKey() {
        return OverrideActions.keyOf(id);
    }

    /** True while the approved action has been issued through payments and its outcome is not yet recorded. */
    public boolean awaitsOutcome() {
        return status == OverrideStatus.APPROVED && effect.dispatches() && actionOutcome.isEmpty();
    }

    /** True when the approval was the proposer's own act - admitted only below C7's threshold. */
    public boolean decidedByProposer() {
        return decidedBy.filter(proposedBy::equals).isPresent();
    }

    @Override
    public String toString() {
        // Identifiers and codes only: the reasons are RESTRICTED-PII and the people CONFIDENTIAL.
        return "DecisionOverride[" + id.value() + ", " + status + ", " + effect + actionOutcome.map(o -> ", " + o)
                .orElse("") + "]";
    }
}
