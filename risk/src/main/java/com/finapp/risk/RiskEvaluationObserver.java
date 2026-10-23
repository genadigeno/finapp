package com.finapp.risk;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What T-eval reports for the meters (`P13-TSK-004`; {@code PHASE_13_PLAN.md} section 15; ADR-0108 operational
 * impact) - a port, because risk carries no metrics library; {@code app}'s {@code RiskEvaluationMetrics} turns each
 * report into {@code finapp.risk.evaluation{operation,verdict}}, {@code .evaluation.latency{operation}},
 * {@code .evaluation.unavailable{operation,reason_code}}, {@code .rule.hit{operation,rule}} and
 * {@code .challenge{operation,outcome}}. Every value is a closed code - never an id, an amount or a name
 * ({@code INV-RSK-08}). Called once per sealed assessment, after the insert, inside the caller's transaction: a
 * rolled-back caller may have been counted, which a counter tolerates and an audit would not.
 */
@FunctionalInterface
public interface RiskEvaluationObserver {

    /** Reports nothing - the suites'. */
    RiskEvaluationObserver NONE = report -> { };

    void evaluated(Report report);

    /**
     * One sealed assessment's codes - and its id and the unavailability's cause, for the one structured log line a
     * fail-safe writes until `-015`'s alert model raises {@code RiskEvaluationUnavailable} (ids and codes only); never a
     * tag.
     */
    record Report(RiskAssessmentId assessment, RiskOperation operation, RiskDecision decision,
            Optional<UnavailableCause> unavailable, List<ReasonCode> firedRules, Optional<Boolean> challengeSatisfied,
            VelocityOutcome velocity, Duration latency) {
        public Report {
            Objects.requireNonNull(assessment, "assessment");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(unavailable, "unavailable");
            firedRules = List.copyOf(Objects.requireNonNull(firedRules, "firedRules"));
            Objects.requireNonNull(challengeSatisfied, "challengeSatisfied");
            Objects.requireNonNull(velocity, "velocity");
            Objects.requireNonNull(latency, "latency");
        }

        /** The fail-safe's reason code, when the fail-safe answered. */
        public Optional<ReasonCode> failSafe() {
            return unavailable.map(UnavailableCause::reason);
        }
    }
}
