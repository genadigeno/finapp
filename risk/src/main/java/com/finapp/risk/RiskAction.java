package com.finapp.risk;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One downstream action, as {@code risk V013}'s {@code risk_action} row holds it (`P13-TSK-017`; {@code PHASE_13_PLAN.md}
 * sections 3.1, 4, 5; {@code RISK_LIFECYCLES.md} section 4.7; ADR-0112) - the <strong>record</strong> of an effect whose
 * <strong>execution</strong> belongs to the owning domain ({@code INV-RSK-02}): where it came from (a case's proposal, or
 * an approved override), what it asks of whom ({@link ActionKind}, its {@link ActionTarget} and the target's id), the
 * closed reason code the owner records, the two persons (proposer and decider - four eyes for a case's action by
 * {@code risk_action_four_eyes}; an override's are the override's own), the permit and the attempts of T-act, and the
 * owner's answer. No money, no score, no person's text.
 *
 * @param decisionAction the case's {@code ACTION_APPROVED} / {@code ACTION_REJECTED} investigator action - a case's
 *     decided action only
 * @param nextAttemptAt the permit - present exactly while {@code APPROVED} or {@code DISPATCHED}
 * @param attempts how many times T-act claimed it - each claim one attempt at the owner's door under the one key
 */
public record RiskAction(
        RiskActionId id,
        ActionSource source,
        Optional<RiskCaseId> caseId,
        Optional<DecisionOverrideId> overrideId,
        ActionKind kind,
        ActionTarget targetKind,
        UUID target,
        String reasonCode,
        ActionStatus status,
        RiskCase.Person proposedBy,
        Optional<Instant> proposedAt,
        Optional<RiskCase.Person> decidedBy,
        Optional<Instant> decidedAt,
        Optional<UUID> decisionAction,
        Optional<Instant> nextAttemptAt,
        int attempts,
        Optional<Instant> dispatchedAt,
        Optional<ActionOutcome> outcome,
        Optional<Instant> concludedAt,
        String correlationId) {

    public RiskAction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(overrideId, "overrideId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(targetKind, "targetKind");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(proposedBy, "proposedBy");
        Objects.requireNonNull(proposedAt, "proposedAt");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(decidedAt, "decidedAt");
        Objects.requireNonNull(decisionAction, "decisionAction");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Objects.requireNonNull(dispatchedAt, "dispatchedAt");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(concludedAt, "concludedAt");
        Objects.requireNonNull(correlationId, "correlationId");
        if ((source == ActionSource.CASE) != caseId.isPresent()
                || (source == ActionSource.OVERRIDE) != overrideId.isPresent()) {
            throw new IllegalArgumentException("a case's action names its case, an override's its override");
        }
        if (overrideId.isPresent() && !overrideId.get().value().equals(id.value())) {
            throw new IllegalArgumentException("an override's action is the override's own id - its key is kept");
        }
        if (kind.target() != targetKind) {
            throw new IllegalArgumentException("a " + kind + " action acts on a " + kind.target());
        }
        if (source == ActionSource.OVERRIDE && !kind.issuedByOverride()) {
            throw new IllegalArgumentException("an override issues only payments' held doors");
        }
        if (!reasonCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("an action's reason is a closed code");
        }
        if ((status == ActionStatus.PROPOSED) != decidedBy.isEmpty()) {
            throw new IllegalArgumentException("a decided action names its decider, a proposed one none");
        }
        if (source == ActionSource.CASE && status.compareTo(ActionStatus.APPROVED) >= 0
                && status != ActionStatus.REJECTED && decidedBy.filter(proposedBy::equals).isPresent()) {
            throw new IllegalArgumentException("a case's action is approved by a second person (risk_action_four_eyes)");
        }
        if (status.permitted() != nextAttemptAt.isPresent()) {
            throw new IllegalArgumentException("a permit exactly while approved or dispatched");
        }
        if (attempts < 0 || (status == ActionStatus.PROPOSED || status == ActionStatus.REJECTED) && attempts != 0) {
            throw new IllegalArgumentException("only a claim counts an attempt");
        }
        boolean dispatched = status == ActionStatus.DISPATCHED || status == ActionStatus.DONE
                || status == ActionStatus.FAILED;
        if (dispatched != dispatchedAt.isPresent() || (dispatched && attempts < 1)) {
            throw new IllegalArgumentException("a dispatched action was claimed at least once, stamped");
        }
        if ((status == ActionStatus.DONE || status == ActionStatus.FAILED) != outcome.isPresent()
                || outcome.isPresent() != concludedAt.isPresent()) {
            throw new IllegalArgumentException("an outcome exactly when concluded");
        }
        if (status == ActionStatus.DONE && outcome.get() == ActionOutcome.REFUSED
                || status == ActionStatus.FAILED && outcome.get() != ActionOutcome.REFUSED) {
            throw new IllegalArgumentException("DONE is DONE or ALREADY; FAILED is the owner's refusal");
        }
    }

    /** The one key every attempt presents the owning domain: {@code risk.action:<id>}. */
    public String key() {
        return RiskActions.keyOf(id);
    }

    /** True while T-act may claim it - approved, or dispatched with no record yet. */
    public boolean awaitsOutcome() {
        return status.permitted();
    }

    /** The status an owner's answer concludes: {@code DONE} and {@code ALREADY} are done; a refusal is {@code FAILED}. */
    public static ActionStatus concludedBy(ActionOutcome outcome) {
        return Objects.requireNonNull(outcome, "outcome") == ActionOutcome.REFUSED ? ActionStatus.FAILED
                : ActionStatus.DONE;
    }

    /** Identifiers and codes only - never the target's subject, a person or a reason's text ({@code INV-RSK-08}). */
    @Override
    public String toString() {
        return "RiskAction[" + id.value() + ", " + source + ", " + kind + ", " + status + outcome.map(o -> ", " + o)
                .orElse("") + "]";
    }
}
