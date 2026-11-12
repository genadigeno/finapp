package com.finapp.app.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The resolution sweeps' failure counters (`P16-TSK-017`; {@code THREAT_MODEL.md} section 6 item 42 (i)), counted by each sweep's
 * schedule from the tick's own tally, where its WARN lines are written:
 *
 * <ul>
 *   <li>{@code finapp.payments.sweep.tick.failed{leg}} and {@code finapp.merchant.payout.sweep.tick.failed} - a tick that failed
 *       whole, so nothing in that sweep was reached (item 42 (i) (i)). {@code PaymentSweepFailing} and
 *       {@code MerchantPayoutSweepFailing} page when it has counted in every window for fifteen minutes.
 *   <li>{@code …sweep.row.unreadable} - a row its listing could not read and skipped (an id no domain writer could mint, a raw
 *       writer's mark: item 42 (i) (ii)). Its rule pages on the first.
 *   <li>{@code …sweep.row.failed} - a row whose resolution threw, recorded and backed off. Its rule raises a ticket when rows
 *       keep failing for an hour.
 * </ul>
 *
 * <p><strong>Never the count of record.</strong> Ten instances failing one head row in the same tick each count it here; the
 * failure record ({@code payments.sweep_row_failure}, {@code merchant.sweep_row_failure}) counts it once per backoff window.
 * These are per-instance signals, summed by the rules. Registered eagerly, every leg at zero, so a fresh instance publishes a
 * healthy series and the first increment is seen. No identifier and no amount ever reaches a tag (ADR-0072): {@code leg} is
 * this class's own closed vocabulary.
 */
public final class SweepMeters {

    static final String PAYMENT_TICK_FAILED = "finapp.payments.sweep.tick.failed";
    static final String PAYMENT_ROW_UNREADABLE = "finapp.payments.sweep.row.unreadable";
    static final String PAYMENT_ROW_FAILED = "finapp.payments.sweep.row.failed";
    static final String PAYOUT_TICK_FAILED = "finapp.merchant.payout.sweep.tick.failed";
    static final String PAYOUT_ROW_UNREADABLE = "finapp.merchant.payout.sweep.row.unreadable";
    static final String PAYOUT_ROW_FAILED = "finapp.merchant.payout.sweep.row.failed";

    /** The payments module's resolution sweeps, one schedule each: the {@code leg} tag's values, lower case. */
    public enum PaymentSweep {
        /** {@code PaymentSweeper}: the dispatched and unknown attempts, the stranded authorizations and the card refunds. */
        CARD,
        WITHDRAWAL,
        DISPUTE_ANSWER,
        RETURN,
        PAY_IN
    }

    /** One sweep's three counters, as its schedule reports them. */
    public interface Sweep {

        /** The tick failed whole: nothing in this sweep was reached. */
        void tickFailed();

        /** A tick's tally: rows its listings could not read, and rows whose resolution threw. */
        void rows(int unreadable, int failed);

        /** Counts nothing: a schedule's own suites, which are not about the meters. */
        Sweep NONE = new Sweep() {
            @Override
            public void tickFailed() {}

            @Override
            public void rows(int unreadable, int failed) {}
        };
    }

    private final Map<PaymentSweep, Sweep> payments = new EnumMap<>(PaymentSweep.class);
    private final Sweep payouts;

    public SweepMeters(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        for (PaymentSweep sweep : PaymentSweep.values()) {
            String leg = sweep.name().toLowerCase(Locale.ROOT);
            payments.put(sweep, counting(
                    Counter.builder(PAYMENT_TICK_FAILED).tag("leg", leg)
                            .description("Payment resolution sweep ticks that failed whole, by sweep (P16-TSK-017): nothing in"
                                    + " that sweep was reached. Per instance; increase() and sum() aggregate")
                            .register(registry),
                    Counter.builder(PAYMENT_ROW_UNREADABLE).tag("leg", leg)
                            .description("Rows a payment sweep's listing could not read, skipped and backed off, never judged, by"
                                    + " sweep (P16-TSK-017): a raw writer's mark. Per instance; increase() and sum() aggregate")
                            .register(registry),
                    Counter.builder(PAYMENT_ROW_FAILED).tag("leg", leg)
                            .description("Rows whose payment sweep resolution threw, recorded and backed off, by sweep"
                                    + " (P16-TSK-017). Per instance, never the count of record; increase() and sum() aggregate")
                            .register(registry)));
        }
        payouts = counting(
                Counter.builder(PAYOUT_TICK_FAILED)
                        .description("Payout resolution sweep ticks that failed whole (P16-TSK-017): no payout was reached."
                                + " Per instance; increase() and sum() aggregate")
                        .register(registry),
                Counter.builder(PAYOUT_ROW_UNREADABLE)
                        .description("Payouts the sweep's listing could not read, skipped and backed off, never judged"
                                + " (P16-TSK-017): a raw writer's mark. Per instance; increase() and sum() aggregate")
                        .register(registry),
                Counter.builder(PAYOUT_ROW_FAILED)
                        .description("Payouts whose sweep resolution threw, recorded and backed off (P16-TSK-017). Per"
                                + " instance, never the count of record; increase() and sum() aggregate")
                        .register(registry));
    }

    /** The counters of one payments sweep. */
    public Sweep payments(PaymentSweep sweep) {
        return payments.get(Objects.requireNonNull(sweep, "sweep must not be null"));
    }

    /** The counters of the merchant payout sweep. */
    public Sweep payouts() {
        return payouts;
    }

    private static Sweep counting(Counter tickFailed, Counter unreadable, Counter failed) {
        return new Sweep() {
            @Override
            public void tickFailed() {
                tickFailed.increment();
            }

            /**
             * One increment per row, never {@code increment(double)}: a count stays an integer all the way to the registry
             * ({@code NoFloatingPointMoneyRulesTest}), and a tick's rows are at most its batch.
             */
            @Override
            public void rows(int unreadableRows, int failedRows) {
                for (int row = 0; row < unreadableRows; row++) {
                    unreadable.increment();
                }
                for (int row = 0; row < failedRows; row++) {
                    failed.increment();
                }
            }
        };
    }
}
