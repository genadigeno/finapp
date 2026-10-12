package com.finapp.app.lending;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code finapp.lending.draw{outcome}} (`P11-TSK-021`, PHASE_11_PLAN.md section 15, ADR-0100's operational impact): every
 * draw on an account the caller owns counted once, after its transaction committed or its refusal rolled back - the draw
 * made, or the line's own refusal (not drawable, suspended, past the available limit, short of capital, the wallet not
 * receiving). A replayed key or a draw converged on under its key, an unknown account, a malformed amount and a refusal
 * about the customer rather than the line (assurance, standing - credit's own answer) count nothing. Registered eagerly
 * for every outcome, so a fresh instance publishes every series at zero - {@code capital_unavailable} rising is the
 * signal {@code finapp.lending.capital.headroom.low} exists to pre-empt. Never an amount, a party or a loan id (ADR-0072).
 */
public final class CreditLineDrawMetrics {

    /** Spelled with dots - exported as {@code finapp_lending_draw}, the plan's name. */
    public static final String DRAW = "finapp.lending.draw";

    /** The closed set of outcomes. */
    public enum Outcome {
        DRAWN,
        NOT_DRAWABLE,
        SUSPENDED,
        LIMIT_EXCEEDED,
        CAPITAL_UNAVAILABLE,
        WALLET_UNAVAILABLE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;

    public CreditLineDrawMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (Outcome outcome : Outcome.values()) {
            counter(outcome);
        }
    }

    /** One draw, with its outcome. */
    public void record(Outcome outcome) {
        counter(outcome).increment();
    }

    private Counter counter(Outcome outcome) {
        return Counter.builder(DRAW)
                .description("Credit line draws - made, and refusals by reason - by outcome")
                .tags("outcome", outcome.tag())
                .register(registry);
    }
}
