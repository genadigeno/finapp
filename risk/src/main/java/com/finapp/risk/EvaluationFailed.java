package com.finapp.risk;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The engine could not produce a verdict (`P13-TSK-003`; ADR-0108 decision 4; {@code INV-RSK-04}) - never a partial
 * one. The caller (`-004`) answers it with the recorded fail-safe ({@link RiskEngineV1#failSafe}) under
 * {@code RISK_UNAVAILABLE} and raises the alert; the cause names what refused, and the message never carries a
 * reading's value or a threshold.
 */
public final class EvaluationFailed extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    /** Why no verdict could be produced. */
    public enum Cause {
        /** The inputs are for another operation than the policy's. */
        WRONG_OPERATION,
        /** The policy is written for an engine version this class is not. */
        WRONG_ENGINE,
        /** The judged amount is in another currency than the policy's thresholds. */
        CURRENCY_MISMATCH,
        /** A rule's evaluation threw. */
        RULE_THREW,
        /** The composed decision is one the operation does not admit - impossible by the policy door, asserted. */
        DECISION_NOT_ADMITTED
    }

    private final Cause cause;
    /** The rule's ordinal when one threw, else 0. */
    private final int ruleOrdinal;
    /** The rule's kind when one threw, else null. */
    private final RuleKind ruleKind;

    EvaluationFailed(Cause cause, String detail) {
        super(cause + ": " + detail);
        this.cause = Objects.requireNonNull(cause, "cause");
        this.ruleOrdinal = 0;
        this.ruleKind = null;
    }

    EvaluationFailed(RiskRule rule, RuntimeException thrown) {
        super(Cause.RULE_THREW + ": rule " + rule.ordinal() + " (" + rule.kind() + ") threw "
                + thrown.getClass().getSimpleName(), thrown);
        this.cause = Cause.RULE_THREW;
        this.ruleOrdinal = rule.ordinal();
        this.ruleKind = rule.kind();
    }

    public Cause failure() {
        return cause;
    }

    /** The rule that threw, when one did. */
    public OptionalInt ruleOrdinal() {
        return ruleOrdinal == 0 ? OptionalInt.empty() : OptionalInt.of(ruleOrdinal);
    }

    public Optional<RuleKind> ruleKind() {
        return Optional.ofNullable(ruleKind);
    }
}
