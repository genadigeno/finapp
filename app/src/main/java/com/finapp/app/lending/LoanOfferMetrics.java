package com.finapp.app.lending;

import com.finapp.lending.LoanOrigination;
import com.finapp.lending.LoanProduct;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code finapp.lending.offer{product, outcome}} (`P11-TSK-011`, PHASE_11_PLAN.md section 15): every origination step
 * that moved an application, every expiry and every customer decline counted once, after its transaction committed - the
 * offer born, the application declined by credit or closed undecided, the offer declined, expired, or the decline
 * refused. A step that found nothing to do, and a replayed key, count nothing. Registered eagerly for every product and
 * outcome, so a fresh instance publishes every series at zero. Never an amount, a party or an offer id (ADR-0072).
 */
public final class LoanOfferMetrics {

    /** Spelled with dots - exported as {@code finapp_lending_offer}, the plan's name. */
    public static final String OFFER = "finapp.lending.offer";

    /** The closed set of outcomes. */
    public enum Outcome {
        OFFERED,
        APPLICATION_DECLINED,
        APPLICATION_UNDECIDED,
        DECLINED,
        EXPIRED,
        NOT_ACCEPTABLE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;

    public LoanOfferMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (LoanProduct product : LoanProduct.values()) {
            for (Outcome outcome : Outcome.values()) {
                counter(product, outcome);
            }
        }
    }

    /** One act on an offer of {@code product}, with its outcome. */
    public void record(LoanProduct product, Outcome outcome) {
        counter(product, outcome).increment();
    }

    /** One committed origination step - counted only when it moved the application. */
    public void record(LoanOrigination.Outcome step) {
        Objects.requireNonNull(step, "step");
        Outcome outcome = switch (step.kind()) {
            case OFFERED -> Outcome.OFFERED;
            case DECLINED -> Outcome.APPLICATION_DECLINED;
            case CLOSED_UNDECIDED -> Outcome.APPLICATION_UNDECIDED;
            case NOTHING, WAITING -> null;
        };
        if (outcome != null) {
            record(step.product().orElseThrow(), outcome);
        }
    }

    private Counter counter(LoanProduct product, Outcome outcome) {
        return Counter.builder(OFFER)
                .description("Loan offer acts - offers born, applications declined or closed undecided by the"
                        + " origination step, offers declined, expired, and refused declines - by product and outcome")
                .tags("product", product.name(), "outcome", outcome.tag())
                .register(registry);
    }
}
