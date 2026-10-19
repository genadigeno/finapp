package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplProduct;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code finapp.bnpl.payoff{product, outcome}} (`P12-TSK-022`; {@code PHASE_12_PLAN.md} section 15; lending's
 * {@code PayoffMetrics}). A payoff act on an agreement the caller owns is counted once, after its transaction committed or
 * its refusal rolled back: {@code quoted}, {@code paid_off}, or the obligation's own refusal ({@code stale},
 * {@code insufficient_funds}, {@code obligation_closed}). A replayed key or an act converged on under its key, an unknown
 * agreement or quote, a malformed body or a refusal about the session counts nothing. Every series registered eagerly
 * with its closed tags - both products, every outcome. Never an amount, a party, an agreement, a quote or a key
 * (ADR-0072).
 */
public final class BnplPayoffMetrics {

    /** Spelled with dots - exported as {@code finapp_bnpl_payoff_total}, the plan's name. */
    public static final String PAYOFF = "finapp.bnpl.payoff";

    /** The closed set of outcomes. */
    public enum Outcome {
        QUOTED,
        PAID_OFF,
        STALE,
        INSUFFICIENT_FUNDS,
        OBLIGATION_CLOSED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;

    public BnplPayoffMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (BnplProduct product : BnplProduct.values()) {
            for (Outcome outcome : Outcome.values()) {
                counter(product, outcome);
            }
        }
    }

    /** One payoff act on {@code product}'s agreement, with its outcome. */
    public void record(BnplProduct product, Outcome outcome) {
        counter(Objects.requireNonNull(product, "product"), Objects.requireNonNull(outcome, "outcome")).increment();
    }

    private Counter counter(BnplProduct product, Outcome outcome) {
        return Counter.builder(PAYOFF)
                .description("BNPL payoff quotes and executions - quoted, paid off, and refusals by reason - by product"
                        + " and outcome")
                .tags("product", product.name(), "outcome", outcome.tag())
                .register(registry);
    }
}
