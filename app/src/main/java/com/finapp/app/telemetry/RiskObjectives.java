package com.finapp.app.telemetry;

import com.finapp.app.risk.RiskProofMetrics;
import com.finapp.risk.CaseKind;
import com.finapp.risk.FilingCensus;
import com.finapp.risk.SlaDeadlines;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * The objectives Phase 13's risk alerts are drawn against (`P13-TSK-024`; {@code PHASE_13_PLAN.md} section 15) - declared
 * once, here, beside the code's own figures they restate, so {@code infra/prometheus/rules/risk.yml} and the guards
 * reading it cannot drift apart ({@code RiskAlertRulesTest} and {@code AlertRulesResolveTest#theRiskRulesResolve} hold
 * each rule's threshold and window equal to these). {@link BnplObjectives}' shape.
 *
 * <ul>
 *   <li><strong>The evaluation budget</strong>: T-eval's p99 within 50 ms ({@code PHASE_13_PLAN.md} section 7.6, M3's
 *       split trigger) - read exactly as "more than 1% of a five-minute window's evaluations above the 50 ms bucket", the
 *       bucket one of {@code RiskEvaluationMetrics}' service-level objectives.
 *   <li><strong>The monitoring's hour</strong> (the entry's "monitoring lag over an hour"; the review's IMPORTANT 2):
 *       the oldest journal entry not yet monitored is at most an hour old - an AGE on the database's clock
 *       ({@code finapp.risk.monitoring.age}), because the lag counts entries, which a busy ledger keeps above zero and a
 *       quiet one keeps near zero however stalled the sweep.
 *   <li><strong>The unclassified hour</strong>: new {@code UNCLASSIFIED} rows in an hour - the gauge's growth, never its
 *       level (the rows are insert-only, so a level rule could never resolve).
 *   <li><strong>A case's SLA</strong>: {@link SlaDeadlines} - the code's per-kind figure, the database's
 *       {@code sla_deadline}, restated by no one.
 *   <li><strong>The queues' staffed capacity</strong> and <strong>the alert flood</strong>: defaults pending the owner's
 *       staffing figures and the production policy's measured baseline (owner items, {@code PHASE_13_PLAN.md} section
 *       1.2) - each a number the activation gate reviews.
 *   <li><strong>The filing's two intervals</strong>: twice {@link FilingCensus#OVERDUE_AFTER} (`-021`'s 15 minutes,
 *       {@code finapp.risk.filing.inquiry-interval}'s default) - a report still {@code FILED} that long after its filing
 *       was asked again and not concluded ({@code finapp.risk.report.unconcluded.age}; the review's IMPORTANT 1).
 *   <li><strong>The sparse failures' hour</strong>: a filing call or a party's monitoring leg - events an inquiry
 *       interval or a party's next movement apart - read over an hour, any failure the ticket.
 *   <li><strong>The proofs' two floors</strong>: twice {@link RiskProofMetrics#DEFAULT_REFRESH} - one instance's reading
 *       failing at two floors in a row, or none completing in two; the unreadable gauges' wait a little past them.
 *   <li><strong>The rescreening backlog's day</strong>: permits standing {@code PENDING} for a day (section 15's row).
 * </ul>
 */
public final class RiskObjectives {

    /** T-eval's p99 budget - a bucket of {@code finapp.risk.evaluation.latency}. */
    public static final Duration EVALUATION_LATENCY_P99 = Duration.ofMillis(50);

    /**
     * The share of a window's evaluations that may exceed the budget, in percent: p99 - 1 (the rule reads {@code > 0.01}).
     * A whole number, never a {@code double} field (`NoFloatingPointMoneyRulesTest`).
     */
    public static final long EVALUATION_LATENCY_SHARE_PERCENT = 1;

    /** How long the oldest journal entry may stand unmonitored before the monitoring is lagging. */
    public static final Duration MONITORING_AGE = Duration.ofHours(1);

    /** The window over which new UNCLASSIFIED rows, failed filing calls and failed party legs are counted. */
    public static final Duration SPARSE_FAILURE_WINDOW = Duration.ofHours(1);

    /** How long a risk gauge may read NaN before it is ticketed - past the proofs' two floors. */
    public static final Duration UNREADABLE_WAIT = Duration.ofMinutes(35);

    /** An hour's alerts of one kind past which the queue is flooded - a default, the owner's to set from the baseline. */
    public static final long ALERT_FLOOD_PER_HOUR = 500L;

    /** How long permits may stand {@code PENDING} before the rescreening is judged stalled. */
    public static final Duration RESCREENING_BACKLOG_STANDING = Duration.ofDays(1);

    private static final Map<CaseKind, Long> CAPACITY = new EnumMap<>(Map.of(
            // The open cases a kind's desk can carry inside its SLA - defaults pending the owner's staffing figures.
            CaseKind.ATO, 50L,
            CaseKind.FRAUD, 200L,
            CaseKind.MERCHANT_ABUSE, 100L,
            CaseKind.AML, 200L,
            CaseKind.COLLECTIONS, 1_000L));

    static {
        for (CaseKind kind : CaseKind.values()) {
            if (CAPACITY.get(kind) == null || CAPACITY.get(kind) < 1) {
                throw new ExceptionInInitializerError("no staffed capacity for " + kind);
            }
        }
    }

    private RiskObjectives() {}

    /** The SLA of {@code kind} - {@link SlaDeadlines}' own. */
    public static Duration caseSla(CaseKind kind) {
        return SlaDeadlines.of(Objects.requireNonNull(kind, "kind"));
    }

    /** The open cases {@code kind}'s desk is staffed to carry. */
    public static long caseCapacity(CaseKind kind) {
        return CAPACITY.get(Objects.requireNonNull(kind, "kind"));
    }

    /** The filing's inquiry interval. */
    public static Duration filingInquiryInterval() {
        return FilingCensus.OVERDUE_AFTER;
    }

    /** How long a report may stand {@code FILED} before the filing is UNKNOWN past its interval: two intervals. */
    public static Duration filingUnconcluded() {
        return FilingCensus.OVERDUE_AFTER.multipliedBy(2);
    }

    /** Two floors of the proofs' reading - the window and the wait of the reading rules. */
    public static Duration proofReadingFloors() {
        return RiskProofMetrics.DEFAULT_REFRESH.multipliedBy(2);
    }
}
