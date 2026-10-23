package com.finapp.risk;

import java.sql.Connection;
import java.util.Objects;

/**
 * {@link MonitoringHints} over the monitoring watermark (`P13-TSK-019`, closing the seam `P13-TSK-005` declared;
 * {@code PHASE_13_PLAN.md} section 12.7 "events are hints that pull the watermark's permit forward; the sweep reads
 * rows"): a completed movement pulls the ledger journal's permit to now - {@link MonitoringWatermarkStore#hint}, ONE
 * conditional {@code UPDATE} in the handler's own transaction, {@code SKIP LOCKED} so a row a sweep holds is skipped
 * (it is sweeping already) and a permit already due is left - so the next tick takes its intake batch instead of
 * waiting for the idle cadence.
 *
 * <p>What the hint can never do: change what is ingested. The sweep reads the journal from the watermark whatever the
 * hints said, so a hint delivered twice, late, out of order, forged or never changes nothing ({@code INV-EVT-04},
 * {@code INV-RSK-09}); the movement itself is not read - no subject, aggregate or instant of it reaches the row. And
 * the statement cannot refuse: it names no column a {@code CHECK} or trigger can fail on (the sequence and
 * {@code advanced_at} are the database's to derive), so the only failure it can meet is the connection's, which fails
 * the delivery as any statement would and the inbox retries it - a hint lost is free.
 */
public final class WatermarkMonitoringHints implements MonitoringHints {

    private final MonitoringWatermarkStore watermarks;

    public WatermarkMonitoringHints(MonitoringWatermarkStore watermarks) {
        this.watermarks = Objects.requireNonNull(watermarks, "watermarks");
    }

    @Override
    public void movementCompleted(Connection unitOfWork, CompletedMovement movement) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(movement, "movement");
        watermarks.hint(unitOfWork, MonitoringWatermark.LEDGER_JOURNAL);
    }
}
