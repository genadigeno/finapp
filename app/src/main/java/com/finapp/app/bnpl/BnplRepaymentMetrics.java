package com.finapp.app.bnpl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code finapp.bnpl.repayment{kind, outcome}} and {@code finapp.bnpl.allocation.anomaly} (`P12-TSK-018`;
 * {@code PHASE_12_PLAN.md} section 15; lending's {@code RepaymentMetrics}). A repayment on an agreement the caller owns is
 * counted once, after its transaction committed or its refusal rolled back: {@code repaid}, {@code closed} (repaid, and the
 * repayment closed the obligation), or the obligation's own refusal ({@code obligation_closed},
 * {@code insufficient_funds}). A replayed key or a repayment converged on under its key, an unknown agreement, a malformed
 * amount or a refusal about the session count nothing. The anomaly counter rises on every refused conservation - the
 * obligation's rows not explaining its due accounts, the allocation engine breaking its assertion, or the database's
 * deferred conservation refusing at commit - and alerts above zero: it is a defect, never a customer's error. Every series
 * registered eagerly with its closed tags - every repayment kind, so `-019`'s {@code auto_collection} and `-022`'s
 * {@code payoff} publish at zero from the start. Never an amount, a party, an agreement or a key (ADR-0072).
 */
public final class BnplRepaymentMetrics {

    /** Spelled with dots - exported as {@code finapp_bnpl_repayment_total}, the plan's name. */
    public static final String REPAYMENT = "finapp.bnpl.repayment";

    /** Exported as {@code finapp_bnpl_allocation_anomaly_total}; alert above 0. */
    public static final String ANOMALY = "finapp.bnpl.allocation.anomaly";

    /** The closed set of repayment kinds a series is kept for (`bnpl V010`'s CHECK, but the purchase's own). */
    public enum Kind {
        CUSTOMER,
        AUTO_COLLECTION,
        PAYOFF;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The closed set of outcomes. */
    public enum Outcome {
        REPAID,
        CLOSED,
        OBLIGATION_CLOSED,
        INSUFFICIENT_FUNDS;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;
    private final Counter anomaly;

    public BnplRepaymentMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (Kind kind : Kind.values()) {
            for (Outcome outcome : Outcome.values()) {
                counter(kind, outcome);
            }
        }
        this.anomaly = Counter.builder(ANOMALY)
                .description("Refused conservations in a BNPL allocation - the rows not explaining the ledger, or the"
                        + " engine breaking its assertion; alert above 0")
                .register(registry);
    }

    /** One repayment of {@code kind}, with its outcome. */
    public void record(Kind kind, Outcome outcome) {
        counter(kind, outcome).increment();
    }

    /** One refused conservation. */
    public void anomaly() {
        anomaly.increment();
    }

    private Counter counter(Kind kind, Outcome outcome) {
        return Counter.builder(REPAYMENT)
                .description("BNPL repayments from the wallet - made, closing, and refusals by reason - by kind and"
                        + " outcome")
                .tags("kind", kind.tag(), "outcome", outcome.tag())
                .register(registry);
    }
}
