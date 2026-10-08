package com.finapp.app.credit;

import com.finapp.credit.CreditDecision;
import com.finapp.credit.DecisionObserver;
import com.finapp.credit.ReasonCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * The decision's meters (`P10-TSK-016`; PHASE_10_PLAN.md section 15), counted after the commit:
 * {@code finapp.credit.decision{product, outcome, policy_version, decision_maker}} (the plan's {@code decided_by},
 * renamed: the key may not carry the letters {@code id}),
 * {@code finapp.credit.reason{product, reason_code}} and {@code finapp.credit.decision.latency{product}}. Every tag a
 * closed set: the product, the outcome, the catalogue's code, a policy's version NUMBER (never its id), and who decided
 * as a kind - {@code system} or {@code person}, never the person.
 */
public final class CreditDecisionMetrics implements DecisionObserver {

    private final MeterRegistry registry;

    public CreditDecisionMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void recorded(CreditDecision decision, int policyVersion, Duration latency) {
        String product = tag(decision.product().name());
        registry.counter("finapp.credit.decision", "product", product, "outcome", tag(decision.outcome().name()),
                        "policy_version", Integer.toString(policyVersion),
                        "decision_maker", "SYSTEM".equals(decision.decidedByType()) ? "system" : "person")
                .increment();
        for (ReasonCode reason : decision.reasons()) {
            registry.counter("finapp.credit.reason", "product", product, "reason_code", reason.code()).increment();
        }
        Timer.builder("finapp.credit.decision.latency")
                .description("From a decision request's submission to its decision")
                .tags("product", product)
                .register(registry)
                .record(latency);
    }

    private static String tag(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
