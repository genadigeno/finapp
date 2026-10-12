package com.finapp.app.lending;

import com.finapp.lending.RepaymentKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code finapp.lending.repayment{kind, outcome}} and {@code finapp.lending.allocation.anomaly} (`P11-TSK-018`,
 * PHASE_11_PLAN.md section 15). A repayment on an account the caller owns is counted once, after its transaction committed
 * or its refusal rolled back: {@code repaid}, {@code closed} (repaid, and the repayment closed the loan), or the loan's own
 * refusal ({@code not_repayable}, {@code insufficient_funds}). A replayed key or a repayment converged on under its key, an
 * unknown account, a malformed amount or a refusal about the session count nothing. The anomaly counter rises on every
 * refused conservation - the loan's rows not explaining its due accounts, or the allocation engine breaking its own
 * assertion - and alerts above zero: it is a defect, never a customer's error. Every series registered eagerly with its
 * closed tags (every repayment kind, so `-019`'s and `-026`'s publish at zero from the start). Never an amount, a party or
 * a loan id (ADR-0072).
 */
public final class RepaymentMetrics {

    /** Spelled with dots - exported as {@code finapp_lending_repayment}, the plan's name. */
    public static final String REPAYMENT = "finapp.lending.repayment";

    /** Exported as {@code finapp_lending_allocation_anomaly_total}; alert above 0. */
    public static final String ANOMALY = "finapp.lending.allocation.anomaly";

    /** The closed set of outcomes. */
    public enum Outcome {
        REPAID,
        CLOSED,
        NOT_REPAYABLE,
        INSUFFICIENT_FUNDS;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;
    private final Counter anomaly;

    public RepaymentMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (RepaymentKind kind : RepaymentKind.values()) {
            for (Outcome outcome : Outcome.values()) {
                counter(kind, outcome);
            }
        }
        this.anomaly = Counter.builder(ANOMALY)
                .description("Refused conservations in an allocation - the rows not explaining the ledger, or the engine"
                        + " breaking its assertion; alert above 0")
                .register(registry);
    }

    /** One repayment of {@code kind}, with its outcome. */
    public void record(RepaymentKind kind, Outcome outcome) {
        counter(kind, outcome).increment();
    }

    /** One refused conservation. */
    public void anomaly() {
        anomaly.increment();
    }

    private Counter counter(RepaymentKind kind, Outcome outcome) {
        return Counter.builder(REPAYMENT)
                .description("Repayments from the wallet - made, closing, and refusals by reason - by kind and outcome")
                .tags("kind", kind.name().toLowerCase(Locale.ROOT), "outcome", outcome.tag())
                .register(registry);
    }
}
