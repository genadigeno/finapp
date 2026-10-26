package com.finapp.app.telemetry;

import com.finapp.app.risk.RiskMonitoringMetrics;
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
 *   <li><strong>The monitoring lag's growth</strong>: the lag is journal entries, not time, and stands above zero
 *       whenever the ledger posts faster than one poll - so the alert is its <em>growth</em> over an hour by more than
 *       one tick's intake at the defaults ({@code finapp.risk.monitoring.batch} 200 x {@code .batches-per-tick} 10), or
 *       the figure saturated ({@link RiskMonitoringMetrics#LAG_CAP}), where it can no longer grow.
 *   <li><strong>A case's SLA</strong>: {@link SlaDeadlines} - the code's per-kind figure, the database's
 *       {@code sla_deadline}, restated by no one.
 *   <li><strong>The queues' staffed capacity</strong> and <strong>the alert flood</strong>: defaults pending the owner's
 *       staffing figures and the production policy's measured baseline (owner items, {@code PHASE_13_PLAN.md} section
 *       1.2) - each a number the activation gate reviews.
 *   <li><strong>The filing's inquiry interval</strong>: {@link FilingCensus#OVERDUE_AFTER} (`-021`'s 15 minutes,
 *       {@code finapp.risk.filing.inquiry-interval}'s default) - an {@code UNKNOWN} answered at two inquiries in a row.
 *   <li><strong>The proofs' two floors</strong>: twice {@link RiskProofMetrics#DEFAULT_REFRESH} - a reading failing at two
 *       floors in a row, or none completing in two.
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

    /** The window over which the monitoring lag's growth is judged. */
    public static final Duration MONITORING_LAG_WINDOW = Duration.ofHours(1);

    /** One tick's intake at the defaults - a growth beyond it over the window is a sweep not keeping up. */
    public static final long MONITORING_LAG_GROWTH = 200L * 10L;

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

    /** The filing's inquiry interval - the window and the wait of the unknown-filing rule. */
    public static Duration filingInquiryInterval() {
        return FilingCensus.OVERDUE_AFTER;
    }

    /** Two floors of the proofs' reading - the window and the wait of the reading rules. */
    public static Duration proofReadingFloors() {
        return RiskProofMetrics.DEFAULT_REFRESH.multipliedBy(2);
    }

    /** The lag at which the gauge saturates. */
    public static long monitoringLagCap() {
        return RiskMonitoringMetrics.LAG_CAP;
    }
}
