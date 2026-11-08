package com.finapp.app.payments;

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
 */
public final class CallbackInquiryBulkhead {

    private final int permits;
    private final AtomicInteger running = new AtomicInteger();

    public CallbackInquiryBulkhead(int permits) {
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be at least 1");
        }
        this.permits = permits;
    }

    /** Takes a permit if one is free. A {@code true} must be paired with {@link #exit()}. */
    boolean tryEnter() {
        if (running.incrementAndGet() > permits) {
            running.decrementAndGet();
            return false;
        }
        return true;
    }

    void exit() {
        running.decrementAndGet();
    }
}
