package com.finapp.risk;

import java.time.Duration;
import java.util.Set;

/**
 * What T-mon's rule leg reports for the meters (`P13-TSK-020`; {@code PHASE_13_PLAN.md} section 15; the
 * {@link RiskEvaluationObserver} shape) - a port, because risk carries no metrics library; {@code app}'s
 * {@code RiskMonitoringMetrics} turns each report into {@code finapp.risk.monitoring.subject{outcome}},
 * {@code finapp.risk.monitoring.latency} and {@code finapp.risk.rule.hit{operation=MONITORING,rule}}. Every value is a
 * closed code or a duration - never a party, an amount or a rule's parameter ({@code INV-RSK-08}). Called inside the
 * claim's transaction: a rolled-back claim may have been counted, which a counter tolerates.
 */
public interface MonitoringObserver {

    /** Reports nothing - the suites'. */
    MonitoringObserver NONE = new MonitoringObserver() {};

    /** What became of one party a run met. */
    enum SubjectOutcome {
        /** Judged under the pinned version, its alerts raised and its profile recomputed - in its savepoint. */
        JUDGED,
        /** Its leg threw and was rolled back to its savepoint; the run went on. */
        FAILED,
        /** No {@code MONITORING} version was {@code ACTIVE}: nothing raised, no head enrolled. */
        NOT_JUDGED
    }

    /** One party a run met, and what became of it. */
    default void subject(SubjectOutcome outcome) {
        // Nothing by default.
    }

    /**
     * One party's leg failed - for the one log line {@code app} writes: the cause's class only, never its message
     * (a storage failure's message can quote a row) and never the party.
     */
    default void subjectFailed(Class<? extends Throwable> cause) {
        // Nothing by default.
    }

    /** Customer accounts a run met that no party could be placed behind - their movements unjudged. */
    default void unplaced(int accounts) {
        // Nothing by default.
    }

    /** One judged party's leg, its savepoint released - the head's lock wait included. */
    default void judged(Duration latency) {
        // Nothing by default.
    }

    /**
     * The rule kinds one profile recompute's {@code MONITORING} evaluations fired, each kind once however many movements
     * it fired for - the rule leg's and `-007`'s permit leg's alike.
     */
    default void fired(Set<RuleKind> fired) {
        // Nothing by default.
    }
}
