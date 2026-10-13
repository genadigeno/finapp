package com.finapp.app.lending;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code finapp.lending.payoff{outcome}} (`P11-TSK-026`, PHASE_11_PLAN.md section 15): every payoff act on an account the
 * caller owns counted once, after its transaction committed or its refusal rolled back - a quote made ({@code quoted}), an
 * execution that closed the account ({@code paid_off}), or the account's own refusal ({@code stale} - the quote no longer
 * ends it, scenario 7's loser; {@code insufficient_funds}; {@code not_repayable} - closed, cancelled, not yet disbursed or
 * awaiting its payout). A replayed key, an execution converged on, an unknown account or quote, a malformed request and a
 * refusal about the session count nothing. {@code stale} rising beside {@code paid_off} is customers racing their own
 * repayments or collections; alone it is a quote-to-execution gap worth a look. Registered eagerly for every outcome. Never
 * an amount, a party or a loan id (ADR-0072).
 */
public final class PayoffMetrics {

    /** Spelled with dots - exported as {@code finapp_lending_payoff}, the plan's name. */
    public static final String PAYOFF = "finapp.lending.payoff";

    /** The closed set of outcomes. */
    public enum Outcome {
        QUOTED,
        PAID_OFF,
        STALE,
        INSUFFICIENT_FUNDS,
        NOT_REPAYABLE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;

    public PayoffMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (Outcome outcome : Outcome.values()) {
            counter(outcome);
        }
    }

    /** One payoff act, with its outcome. */
    public void record(Outcome outcome) {
        counter(outcome).increment();
    }

    private Counter counter(Outcome outcome) {
        return Counter.builder(PAYOFF)
                .description("Payoff quotes made, payoffs executed, and refusals by reason - by outcome")
                .tags("outcome", outcome.tag())
                .register(registry);
    }
}
