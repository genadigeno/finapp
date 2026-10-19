package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplRefunds;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code finapp.bnpl.refund{kind, outcome}} (`P12-TSK-021`; {@code PHASE_12_PLAN.md} section 15 - the refund rate). A
 * refund is counted once, after its transaction committed or its refusal rolled back: {@code applied}, {@code closed}
 * (applied, and it closed the obligation), or the refund's own refusal under the purchase's lock
 * ({@code exceeds_purchase}, {@code fully_refunded}, {@code not_postable}, {@code self_dealing_refused}) - {@code kind}
 * {@code full} when it asked for all that remained of the purchase, else {@code partial}. A replayed key, an unknown
 * purchase, a malformed amount, a missing reason or a key without the refund scope count nothing here (the scope's refusal
 * is its audit record). A refused conservation counts {@code finapp.bnpl.allocation.anomaly} - the one counter of the
 * module, shared with the repayment's ({@link BnplRepaymentMetrics#ANOMALY}). Every series registered eagerly with its
 * closed tags. Never an amount, a purchase, a merchant or a key (ADR-0072).
 */
public final class BnplRefundMetrics {

    /** Spelled with dots - exported as {@code finapp_bnpl_refund_total}, the plan's name. */
    public static final String REFUND = "finapp.bnpl.refund";

    /** The closed set of outcomes. */
    public enum Outcome {
        APPLIED,
        CLOSED,
        EXCEEDS_PURCHASE,
        FULLY_REFUNDED,
        NOT_POSTABLE,
        SELF_DEALING_REFUSED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;
    private final Counter anomaly;

    public BnplRefundMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (BnplRefunds.Kind kind : BnplRefunds.Kind.values()) {
            for (Outcome outcome : Outcome.values()) {
                counter(kind, outcome);
            }
        }
        this.anomaly = Counter.builder(BnplRepaymentMetrics.ANOMALY)
                .description("Refused conservations in a BNPL allocation - the rows not explaining the ledger, or the"
                        + " engine breaking its assertion; alert above 0")
                .register(registry);
    }

    /** One refund of {@code kind}, with its outcome. */
    public void record(BnplRefunds.Kind kind, Outcome outcome) {
        counter(kind, outcome).increment();
    }

    /** One refused conservation. */
    public void anomaly() {
        anomaly.increment();
    }

    private Counter counter(BnplRefunds.Kind kind, Outcome outcome) {
        return Counter.builder(REFUND)
                .description("BNPL merchant refunds - applied, closing, and refusals by reason - by kind (full or partial)"
                        + " and outcome")
                .tags("kind", kind.name().toLowerCase(Locale.ROOT), "outcome", outcome.tag())
                .register(registry);
    }
}
