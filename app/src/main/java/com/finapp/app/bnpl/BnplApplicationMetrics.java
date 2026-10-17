package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplProduct;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code finapp.bnpl.application{product, outcome}} (`P12-TSK-010`, PHASE_12_PLAN.md section 15; lending's
 * {@code LoanApplicationMetrics}): every keyed application act counted once, after its transaction - a submission or a
 * withdrawal that committed, or the refusal it met. A replayed key counts nothing: the act happened once. Registered
 * eagerly for every product and outcome, so a fresh instance publishes every series at zero. Never an amount, a party, a
 * merchant, a session or an application id (ADR-0072).
 */
public final class BnplApplicationMetrics {

    /** Spelled with dots - exported as {@code finapp_bnpl_application}, the plan's name. */
    public static final String APPLICATION = "finapp.bnpl.application";

    /** The closed set of outcomes. */
    public enum Outcome {
        SUBMITTED,
        WITHDRAWN,
        APPLICATION_OPEN,
        PRODUCT_NOT_OFFERED,
        OUT_OF_RANGE,
        NOT_ELIGIBLE,
        CONSENT_REQUIRED,
        SESSION_NOT_ELIGIBLE,
        MERCHANT_NOT_ENABLED,
        NOT_WITHDRAWABLE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;

    public BnplApplicationMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (BnplProduct product : BnplProduct.values()) {
            for (Outcome outcome : Outcome.values()) {
                counter(product, outcome);
            }
        }
    }

    /** One act on an application of {@code product}, with its outcome. */
    public void record(BnplProduct product, Outcome outcome) {
        counter(product, outcome).increment();
    }

    private Counter counter(BnplProduct product, Outcome outcome) {
        return Counter.builder(APPLICATION)
                .description("BNPL application acts - submissions, withdrawals and the refusals they met - by product"
                        + " and outcome; a replayed key counts nothing")
                .tags("product", product.name(), "outcome", outcome.tag())
                .register(registry);
    }
}
