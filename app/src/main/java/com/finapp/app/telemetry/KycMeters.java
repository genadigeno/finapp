package com.finapp.app.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * The one definition of the {@code kyc} module's planned meters (`P2-TSK-020`,
 * `PHASE_2_PLAN.md` §10).
 *
 * <p>Every series here must exist on a freshly started instance with nothing configured, and
 * how that is achieved differs: {@code finapp.kyc.case} is registered by its one incrementing
 * owner, {@code MeteredKycCaseStore}, which is always present; {@code check} and
 * {@code provider.latency} are incremented by provider-conditional beans, so {@link KycMetrics}
 * — unconditional — registers them a second time at startup. Micrometer registration is
 * idempotent for an identical name and tag set, which is what makes the double registration
 * safe; what would not be safe is two inline copies of the name and tags, because the copy that
 * drifted would register a series nothing increments while the increments went to one no plan
 * and no dashboard names. So every caller builds through here, and there is nothing to drift.
 */
public final class KycMeters {

    /** {@code finapp.kyc.case} — case throughput by outcome (opened, approved, rejected). */
    public static final String CASE = "finapp.kyc.case";

    /** {@code finapp.kyc.check} — verification check outcomes, as normalised by us. */
    public static final String CHECK = "finapp.kyc.check";

    /** {@code finapp.kyc.provider.latency} — provider round-trip time (ADR-0008). */
    public static final String PROVIDER_LATENCY = "finapp.kyc.provider.latency";

    private KycMeters() {
        throw new AssertionError("not instantiable");
    }

    /**
     * The case-throughput counter for one outcome — {@code opened} at creation,
     * a terminal status's own name at decision.
     */
    public static Counter caseOutcome(MeterRegistry registry, String outcome) {
        return Counter.builder(CASE)
                .tag("outcome", outcome)
                .description(
                        "Case throughput: opened at creation, approved/rejected at decision."
                                + " Per instance - aggregate with sum(rate(...))")
                .register(registry);
    }

    /** The check-outcome counter for one normalised outcome (clear, hit, indeterminate). */
    public static Counter check(MeterRegistry registry, String outcome) {
        return Counter.builder(CHECK)
                .tag("outcome", outcome)
                .description("Verification check outcomes, as normalised by us")
                .register(registry);
    }

    /** The provider round-trip timer, refusals included (the port is total). */
    public static Timer providerLatency(MeterRegistry registry) {
        // No baseUnit - the P0-TSK-029 finding: Micrometer APPENDS it to the name. A Timer
        // carries its own `seconds` unit in the Prometheus rendering regardless.
        return Timer.builder(PROVIDER_LATENCY)
                .description("Verification provider round-trip time, refusals included")
                .register(registry);
    }
}
