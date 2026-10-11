package com.finapp.app.lending;

import com.finapp.lending.LoanProduct;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code finapp.lending.application{product, outcome}} (`P11-TSK-010`, PHASE_11_PLAN.md section 15): every keyed
 * application act counted once, after its transaction - a submission or a withdrawal that committed, or the refusal it
 * met. A replayed key counts nothing: the act happened once. Registered eagerly for every product and outcome, so a
 * fresh instance publishes every series at zero. Never an amount, a party or an application id (ADR-0072).
 */
public final class LoanApplicationMetrics {

    /** Spelled with dots - exported as {@code finapp_lending_application}, the plan's name. */
    public static final String APPLICATION = "finapp.lending.application";

    /** The closed set of outcomes. */
    public enum Outcome {
        SUBMITTED,
        WITHDRAWN,
        APPLICATION_OPEN,
        PRODUCT_NOT_OFFERED,
        OUT_OF_RANGE,
        NOT_ELIGIBLE,
        CONSENT_REQUIRED,
        NOT_WITHDRAWABLE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;

    public LoanApplicationMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (LoanProduct product : LoanProduct.values()) {
            for (Outcome outcome : Outcome.values()) {
                counter(product, outcome);
            }
        }
    }

    /** One act on an application of {@code product}, with its outcome. */
    public void record(LoanProduct product, Outcome outcome) {
        counter(product, outcome).increment();
    }

    private Counter counter(LoanProduct product, Outcome outcome) {
        return Counter.builder(APPLICATION)
                .description("Loan application acts - submissions, withdrawals and the refusals they met - by product"
                        + " and outcome; a replayed key counts nothing")
                .tags("product", product.name(), "outcome", outcome.tag())
                .register(registry);
    }
}
