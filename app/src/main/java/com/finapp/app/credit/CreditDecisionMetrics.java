package com.finapp.app.credit;

import com.finapp.app.telemetry.CreditObjectives;
import com.finapp.credit.CreditDecision;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.DecisionObserver;
import com.finapp.credit.DecisionOutcome;
import com.finapp.credit.ReasonCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * The decision's meters (`P10-TSK-016`; PHASE_10_PLAN.md section 15), counted after the commit:
 * {@code finapp.credit.decision{product, outcome, policy_version, decision_maker}} (the plan's {@code decided_by},
 * renamed: the key may not carry the letters {@code id}),
 * {@code finapp.credit.reason{product, reason_code}} and {@code finapp.credit.decision.latency{product, decision_maker}}.
 * Every tag a closed set: the product, the outcome, the catalogue's code, a policy's version NUMBER (never its id), and
 * who decided as a kind - {@code system} or {@code person}, never the person.
 *
 * <p><strong>Published from startup</strong> (`P10-TSK-020`, the acceptance's "a fresh instance publishes every row"):
 * every product's reasons and latencies, and the decision counter for every product, outcome and maker at
 * {@code policy_version="0"} - the {@code finapp.credit.policy.active} gauge's own word for "no version": numbers start
 * at 1, so that series is never incremented and only gives the series, and {@code increase()}, a baseline. The latency
 * is tagged by maker too: a person's decision waits for an underwriter, and only the platform's is held to
 * {@link CreditObjectives#DECISION_LATENCY_P99}, whose boundary is one of the timer's buckets, so the alert reads it
 * exactly. Never an amount, a score or a party (`INV-CRD-02`, `CreditTelemetryCarriesNoFigureTest`).
 */
public final class CreditDecisionMetrics implements DecisionObserver {

    public static final String DECISION = "finapp.credit.decision";
    public static final String REASON = "finapp.credit.reason";
    public static final String LATENCY = "finapp.credit.decision.latency";

    /** The version tag of the never-incremented baseline series - no policy version is 0. */
    static final String NO_VERSION = "0";

    private final MeterRegistry registry;

    public CreditDecisionMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (CreditProduct product : CreditProduct.values()) {
            for (String maker : new String[] {"system", "person"}) {
                latency(tag(product.name()), maker);
                for (DecisionOutcome outcome : DecisionOutcome.values()) {
                    decision(tag(product.name()), tag(outcome.name()), NO_VERSION, maker);
                }
            }
            for (ReasonCode reason : ReasonCode.values()) {
                reason(tag(product.name()), reason);
            }
        }
    }

    @Override
    public void recorded(CreditDecision decision, int policyVersion, Duration latency) {
        String product = tag(decision.product().name());
        String maker = "SYSTEM".equals(decision.decidedByType()) ? "system" : "person";
        decision(product, tag(decision.outcome().name()), Integer.toString(policyVersion), maker).increment();
        for (ReasonCode reason : decision.reasons()) {
            reason(product, reason).increment();
        }
        latency(product, maker).record(latency);
    }

    private Counter decision(String product, String outcome, String policyVersion, String maker) {
        return Counter.builder(DECISION)
                .description("Credit decisions recorded, by product, outcome, pinned policy version and who decided")
                .tags("product", product, "outcome", outcome, "policy_version", policyVersion, "decision_maker", maker)
                .register(registry);
    }

    private Counter reason(String product, ReasonCode reason) {
        return Counter.builder(REASON)
                .description("Reason codes carried by recorded credit decisions, by product and catalogue code")
                .tags("product", product, "reason_code", reason.code())
                .register(registry);
    }

    private Timer latency(String product, String maker) {
        return Timer.builder(LATENCY)
                .description("From a decision request's submission to its decision; the platform's held to a p99 of "
                        + CreditObjectives.DECISION_LATENCY_P99.toMinutes() + " minutes (alerted)")
                .tags("product", product, "decision_maker", maker)
                .serviceLevelObjectives(CreditObjectives.latencyBuckets())
                .register(registry);
    }

    private static String tag(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
