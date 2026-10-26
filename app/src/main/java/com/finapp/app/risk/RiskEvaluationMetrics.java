package com.finapp.app.risk;

import com.finapp.risk.ReasonCode;
import com.finapp.risk.RiskAssessmentId;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskEvaluationObserver;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RuleKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * T-eval's meters (`P13-TSK-004`; {@code PHASE_13_PLAN.md} section 15; ADR-0108 operational impact), the
 * {@link RiskEvaluationObserver} the composition gives {@code RiskEvaluation} - every series registered eagerly, so a
 * fresh instance publishes it from startup:
 *
 * <ul>
 *   <li>{@code finapp.risk.evaluation{operation,verdict}} - every sealed assessment, by its decision;</li>
 *   <li>{@code finapp.risk.evaluation.latency{operation}} - T-eval from the pin to the seal, p50/p99/max (the 50 ms p99
 *       budget the storm measures);</li>
 *   <li>{@code finapp.risk.evaluation.unavailable{operation,reason_code}} - every recorded fail-safe, by its reason
 *       ({@code RISK_UNAVAILABLE}, {@code NO_POLICY_ACTIVE}): the input of the alert {@code RiskEvaluationUnavailable}
 *       that `-015`'s model and `-024`'s rules raise; until then this counter and one log line per
 *       {@code RISK_UNAVAILABLE} - the assessment's id, its operation and the cause, ids and codes only, never the
 *       decision ({@code INV-RSK-08});</li>
 *   <li>{@code finapp.risk.rule.hit{operation,rule}} - every fired rule, by its kind's code (C9: {@code rule} is a
 *       bounded vocabulary of the policy's rule kinds, never a parameter);</li>
 *   <li>{@code finapp.risk.challenge{operation,outcome}} - every {@code CHALLENGE}, {@code satisfied} or
 *       {@code refused};</li>
 *   <li>{@code finapp.risk.alert.unraised{operation}} (`P13-TSK-024`, `-013`'s MINOR 6) - a sealed assessment whose
 *       alerts were undone behind their savepoint ({@link #alertsUnraised}): the decision stands, its alerts and their
 *       case were never born - the input of {@code RiskAlertsUnraised}.</li>
 * </ul>
 *
 * No tag carries an id, an amount, a score or a name. Counted inside the caller's transaction: a caller that rolls back
 * after its assessment was sealed has been counted - a counter's tolerance, never an audit's.
 */
@Slf4j
public final class RiskEvaluationMetrics implements RiskEvaluationObserver {

    public static final String EVALUATION = "finapp.risk.evaluation";
    public static final String LATENCY = "finapp.risk.evaluation.latency";
    public static final String UNAVAILABLE = "finapp.risk.evaluation.unavailable";
    public static final String RULE_HIT = "finapp.risk.rule.hit";
    public static final String CHALLENGE = "finapp.risk.challenge";
    public static final String UNRAISED = "finapp.risk.alert.unraised";

    private final Map<RiskOperation, Map<RiskDecision, Counter>> evaluations = new EnumMap<>(RiskOperation.class);
    private final Map<RiskOperation, Timer> latencies = new EnumMap<>(RiskOperation.class);
    private final Map<RiskOperation, Map<ReasonCode, Counter>> unavailable = new EnumMap<>(RiskOperation.class);
    private final Map<RiskOperation, Map<ReasonCode, Counter>> hits = new EnumMap<>(RiskOperation.class);
    private final Map<RiskOperation, Map<Boolean, Counter>> challenges = new EnumMap<>(RiskOperation.class);
    private final Map<RiskOperation, Counter> unraised = new EnumMap<>(RiskOperation.class);

    public RiskEvaluationMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        for (RiskOperation operation : RiskOperation.values()) {
            if (!operation.judgesAMovement()) {
                continue;
            }
            Map<RiskDecision, Counter> byVerdict = new EnumMap<>(RiskDecision.class);
            for (RiskDecision decision : RiskDecision.admittedBy(operation)) {
                byVerdict.put(decision, Counter.builder(EVALUATION)
                        .description("Sealed risk assessments, by operation and decision")
                        .tags("operation", operation.name(), "verdict", decision.name())
                        .register(registry));
            }
            evaluations.put(operation, byVerdict);
            latencies.put(operation, Timer.builder(LATENCY)
                    .description("T-eval from the policy pin to the sealed assessment, inside the caller's money lock")
                    .tags("operation", operation.name())
                    // Buckets around the 50 ms p99 budget and the 250 ms statement timeout: p50/p99 by
                    // histogram_quantile, max the timer's own - no client-side percentile, whose "phi" tag is no
                    // allowed key.
                    .serviceLevelObjectives(Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25),
                            Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500))
                    .register(registry));
            Map<ReasonCode, Counter> byReason = new EnumMap<>(ReasonCode.class);
            for (ReasonCode reason : new ReasonCode[] {ReasonCode.RISK_UNAVAILABLE, ReasonCode.NO_POLICY_ACTIVE}) {
                byReason.put(reason, Counter.builder(UNAVAILABLE)
                        .description("Recorded fail-safe decisions, by operation and reason")
                        .tags("operation", operation.name(), "reason_code", reason.name())
                        .register(registry));
            }
            unavailable.put(operation, byReason);
            Map<ReasonCode, Counter> byRule = new EnumMap<>(ReasonCode.class);
            for (RuleKind kind : RuleKind.values()) {
                if (kind.appliesTo(operation)) {
                    byRule.put(ReasonCode.of(kind), Counter.builder(RULE_HIT)
                            .description("Fired risk rules, by operation and rule kind")
                            .tags("operation", operation.name(), "rule", kind.name())
                            .register(registry));
                }
            }
            hits.put(operation, byRule);
            Map<Boolean, Counter> byOutcome = new HashMap<>();
            byOutcome.put(true, Counter.builder(CHALLENGE).description("Risk challenges, by operation and outcome")
                    .tags("operation", operation.name(), "outcome", "satisfied").register(registry));
            byOutcome.put(false, Counter.builder(CHALLENGE).description("Risk challenges, by operation and outcome")
                    .tags("operation", operation.name(), "outcome", "refused").register(registry));
            challenges.put(operation, byOutcome);
            unraised.put(operation, Counter.builder(UNRAISED)
                    .description("Sealed assessments whose alerts were undone behind their savepoint - the decision"
                            + " stands, its alerts and case were never born. Alert on any increase")
                    .tags("operation", operation.name())
                    .register(registry));
        }
    }

    @Override
    public void evaluated(Report report) {
        Objects.requireNonNull(report, "report");
        RiskOperation operation = report.operation();
        Counter verdict = evaluations.getOrDefault(operation, Map.of()).get(report.decision());
        if (verdict != null) {
            verdict.increment();
        }
        Timer latency = latencies.get(operation);
        if (latency != null) {
            latency.record(report.latency());
        }
        report.failSafe().ifPresent(reason -> {
            Counter counter = unavailable.getOrDefault(operation, Map.of()).get(reason);
            if (counter != null) {
                counter.increment();
            }
            if (reason == ReasonCode.RISK_UNAVAILABLE) {
                // The alert's stand-in until -015: identifiers and codes only, never a subject, an amount or a score.
                // NO_POLICY_ACTIVE is production's state until the activation gate - counted, and the policy gauge's
                // alert (RiskPolicyNoneActive) is its signal, not a line per movement.
                // The decision is CONFIDENTIAL on the row (DATA_CLASSIFICATION.md) and stays off the log: the
                // assessment's id resolves it through the analyst's audited read.
                log.warn("risk evaluation fail-safe recorded: assessment={} operation={} cause={}",
                        report.assessment().value(), operation.name(),
                        report.unavailable().map(Enum::name).orElse("NONE"));
            }
        });
        for (ReasonCode fired : report.firedRules()) {
            Counter counter = hits.getOrDefault(operation, Map.of()).get(fired);
            if (counter != null) {
                counter.increment();
            }
        }
        report.challengeSatisfied().ifPresent(satisfied -> {
            Counter counter = challenges.getOrDefault(operation, Map.of()).get(satisfied);
            if (counter != null) {
                counter.increment();
            }
        });
    }

    /**
     * A sealed assessment's alerts went unraised (`P13-TSK-013`): its raise was undone behind its savepoint - one line,
     * the assessment's id, the operation and the cause's code (never a subject, a rule or a reading, {@code INV-RSK-08}),
     * and {@code finapp.risk.alert.unraised{operation}} counted (`P13-TSK-024`, plan section 15's row).
     */
    @Override
    public void alertsUnraised(RiskAssessmentId assessment, RiskOperation operation, String cause) {
        Counter counter = unraised.get(operation);
        if (counter != null) {
            counter.increment();
        }
        log.warn("risk alerts unraised behind their savepoint: assessment={} operation={} cause={}",
                assessment.value(), operation.name(), cause);
    }
}
