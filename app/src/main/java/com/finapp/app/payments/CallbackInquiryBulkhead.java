package com.finapp.app.payments;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How many hinted inquiries may run at once on this instance's request threads (`P15-TSK-036`, ADR-0083). A provider inquiry
 * can take its whole timeout, and these run on the shared HTTP pool, so a burst of callbacks against a slow provider must not
 * take the threads every other route needs.
 *
 * <p>Refused means skipped, never queued: the delivery's evidence and dedupe have committed and every kind has a sweep, so an
 * inquiry not made now is made later. The count is this instance's and protects this instance's threads; it coordinates
 * nothing between instances and no correctness rests on it, which is why it is an instance field and not a {@code Semaphore}
 * ({@code NoSingleInstanceAssumptionRulesTest}'s lock rule, the {@code OperationalReportDesk} reasoning).
 *
 * <p><strong>Counted</strong> (`P16-TSK-016`, routed by `P16-TSK-018`; ADR-0137's provider-inquiry class):
 * {@code finapp.payments.callback.inquiries{outcome}}, {@code admitted} or {@code refused}, eager on every instance. A refusal was
 * one {@code INFO} line before, and the delivery is still counted {@code PROCESSED}, so a slow provider filling the bulkhead showed only
 * in the replicas' lines; now the refused share of a replica's hinted inquiries is a series. Per instance and not a count of record.
 */
public final class CallbackInquiryBulkhead {

    static final String METER = "finapp.payments.callback.inquiries";

    private final int permits;
    private final AtomicInteger running = new AtomicInteger();
    private final Counter admitted;
    private final Counter refused;

    public CallbackInquiryBulkhead(int permits, MeterRegistry registry) {
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be at least 1");
        }
        Objects.requireNonNull(registry, "registry must not be null");
        this.permits = permits;
        this.admitted = counter(registry, "admitted");
        this.refused = counter(registry, "refused");
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder(METER)
                .tag("outcome", outcome)
                .description("Hinted provider inquiries this instance admitted under its bulkhead, or refused and left to the sweep")
                .register(registry);
    }

    /** Takes a permit if one is free. A {@code true} must be paired with {@link #exit()}. */
    boolean tryEnter() {
        if (running.incrementAndGet() > permits) {
            running.decrementAndGet();
            refused.increment();
            return false;
        }
        admitted.increment();
        return true;
    }

    void exit() {
        running.decrementAndGet();
    }
}
