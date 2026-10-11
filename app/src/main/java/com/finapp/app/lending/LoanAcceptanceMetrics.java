package com.finapp.app.lending;

import com.finapp.lending.LoanProduct;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code finapp.lending.acceptance{product, outcome}} (`P11-TSK-012`, PHASE_11_PLAN.md section 15): every acceptance of an
 * offer the caller owns counted once, after its transaction committed or its refusal rolled back - the loan opened, or
 * the offer's own refusal (moved, expired, unconsumable; a changed hash; no capital; an invalid destination). A replayed
 * key, an unknown offer and a refusal about the customer rather than the offer (assurance, standing - credit's own
 * answer) count nothing. Registered eagerly for every product and outcome, so a fresh instance publishes every series
 * at zero. Never an amount, a party, an offer or a loan id (ADR-0072).
 */
public final class LoanAcceptanceMetrics {

    /** Spelled with dots - exported as {@code finapp_lending_acceptance}, the plan's name. */
    public static final String ACCEPTANCE = "finapp.lending.acceptance";

    /** The closed set of outcomes. */
    public enum Outcome {
        ACCEPTED,
        NOT_ACCEPTABLE,
        TERMS_CHANGED,
        CAPITAL_UNAVAILABLE,
        DESTINATION_INVALID;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;

    public LoanAcceptanceMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (LoanProduct product : LoanProduct.values()) {
            for (Outcome outcome : Outcome.values()) {
                counter(product, outcome);
            }
        }
    }

    /** One acceptance of an offer of {@code product}, with its outcome. */
    public void record(LoanProduct product, Outcome outcome) {
        counter(product, outcome).increment();
    }

    private Counter counter(LoanProduct product, Outcome outcome) {
        return Counter.builder(ACCEPTANCE)
                .description("Loan offer acceptances - accounts opened, and refusals by reason - by product and outcome")
                .tags("product", product.name(), "outcome", outcome.tag())
                .register(registry);
    }
}
