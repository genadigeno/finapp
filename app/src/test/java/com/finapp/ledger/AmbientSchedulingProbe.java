package com.finapp.ledger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A deliberate violation of {@code nothingSchedulesAmbiently}.
 *
 * <p>A sweep with no lease. Every instance runs it, so it runs N times per period - which for an
 * accrual or a fee assessment is money created N-1 times over (INV-IDEM-02).
 */
@SuppressWarnings("unused")
public final class AmbientSchedulingProbe {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public void start() {
        scheduler.scheduleAtFixedRate(() -> {}, 0, 1, TimeUnit.HOURS);
    }
}
