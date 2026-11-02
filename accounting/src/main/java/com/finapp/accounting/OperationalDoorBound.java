package com.finapp.accounting;

import java.time.Duration;
import java.util.Objects;

/**
 * What the operational report door may hold of an instance (`P14-TSK-017`'s review, I-3): a read's time, and how many reads at once. An
 * operational read holds a connection of the instance's pool (8, a 5 s wait) for as long as it runs, so with the report runs' four-minute
 * generation budget and no limit, eight {@code RECONCILIATION_STATUS} reads of a long-past month would leave postings on that instance
 * nothing to borrow.
 *
 * <p><strong>Per instance, and no correctness rests on it.</strong> The permits protect one instance's pool, which is itself per instance:
 * N instances serve N times as many reads, and that is right. A read refused here changed nothing and served nothing; retried, on this
 * instance or another, it is a new answer as of its own instant. Nothing financial is coordinated by it ({@code DISTRIBUTED_EXECUTION.md}
 * section 3). Both refused at start-up when they could not bound anything.
 *
 * @param timeout one read's whole budget, and its snapshot's {@code statement_timeout} -
 *     {@code finapp.accounting.report.operational-timeout}
 * @param permits how many reads this instance answers at once, the next refused rather than queued -
 *     {@code finapp.accounting.report.operational-permits}
 */
public record OperationalDoorBound(Duration timeout, int permits) {

    /** Thirty seconds: the close checks' own statement bound ({@code finapp.accounting.checks.statement-timeout}). */
    public static final String STANDARD_TIMEOUT = "PT30S";

    /** Two: a quarter of the standard pool, so the postings that share it keep the rest. */
    public static final int STANDARD_PERMITS = 2;

    public OperationalDoorBound {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.toMillis() < 1) {
            throw new IllegalArgumentException("an operational report's time bound is at least one millisecond");
        }
        if (permits < 1) {
            throw new IllegalArgumentException("an operational report door with no permit answers nothing");
        }
    }
}
