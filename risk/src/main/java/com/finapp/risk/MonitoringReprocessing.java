package com.finapp.risk;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.InstrumentShapes;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A reprocessing of the monitoring intake, ordered by an operator (`P13-TSK-019`; {@code PHASE_13_PLAN.md} sections 9,
 * 11, 12.7; ADR-0111 decision 4; {@code INV-RSK-09}): the watermark locked {@code FOR UPDATE} (waiting - a running batch
 * commits its advance first), judged - the point named must lie at or before the watermark, because a point ahead of it
 * would skip entries - moved back to the lowest position of the millisecond named and its permit left due now, and the
 * act recorded {@code risk.ReprocessingOrdered} with the reason the officer gave. The re-run that follows creates
 * nothing new: every monitored transaction is born once.
 *
 * <p>The reason is screened as every person-written risk reason is - required, bounded, holding no card-number or
 * bank-account shape ({@link InstrumentShapes}); the audit summary names the two positions and never the reason
 * ({@code INV-AUD-02}, {@code INV-RSK-08}).
 */
public final class MonitoringReprocessing {

    /** The audit target type - the watermark row, its id the source. */
    public static final String TARGET_TYPE = "monitoring_watermark";

    /** A reason's longest length - the platform's reason columns' bound. */
    public static final int REASON_BOUND = 1000;

    private final MonitoringWatermarkStore watermarks;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    public MonitoringReprocessing(MonitoringWatermarkStore watermarks, AuditWriter<Connection> audit, IdGenerator ids,
            Clock clock) {
        this.watermarks = Objects.requireNonNull(watermarks, "watermarks");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What was ordered: the source, the point restarted from and where the watermark stood. */
    public record Ordered(String source, long fromSequence, long previousSequence) {}

    /** No reason, a blank or unbounded one, or one holding an instrument shape. */
    public static final class ReasonRequired extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ReasonRequired(String detail) {
            super(detail);
        }
    }

    /** The point named lies ahead of the watermark (or below zero): the re-run would skip entries. */
    public static final class NotBehind extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NotBehind(String detail) {
            super(detail);
        }
    }

    /** No watermark row for the source. */
    public static final class SourceNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SourceNotFound(String detail) {
            super(detail);
        }
    }

    /**
     * Orders the re-run on {@code unitOfWork} - the door's keyed transaction.
     *
     * @throws ReasonRequired before anything is locked
     * @throws NotBehind under the lock; nothing written
     * @throws SourceNotFound under no lock; nothing written
     */
    public Ordered order(Connection unitOfWork, String source, long fromSequence, Actor actor, String reason,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String screened = reasoned(reason);
        MonitoringWatermark watermark = watermarks.lock(unitOfWork, source)
                .orElseThrow(() -> new SourceNotFound("no monitoring watermark for the source"));
        if (fromSequence < 0 || fromSequence > watermark.lastSequence()) {
            throw new NotBehind("a reprocessing restarts at or before the watermark's sequence");
        }
        watermarks.move(unitOfWork, source, MonitoringWatermark.positionAt(fromSequence), Duration.ZERO);
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                RiskAuditAction.REPROCESSING_ORDERED, TARGET_TYPE, source, Optional.of(screened), AuditOutcome.SUCCEEDED,
                correlation, Optional.of(summary(fromSequence, watermark.lastSequence()))));
        return new Ordered(source, fromSequence, watermark.lastSequence());
    }

    /** The summary: the two positions, a count's shape - never the reason, an amount or a party. */
    static String summary(long fromSequence, long previousSequence) {
        return "monitoring reprocessing ordered from sequence " + fromSequence + "; the watermark stood at "
                + previousSequence;
    }

    /**
     * {@code reason}, screened.
     *
     * @throws ReasonRequired for a missing, blank, unbounded or instrument-bearing reason
     */
    static String reasoned(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > REASON_BOUND) {
            throw new ReasonRequired("a reason of 1 to " + REASON_BOUND + " characters is required");
        }
        if (InstrumentShapes.holdsAny(reason)) {
            throw new ReasonRequired("a reason must not hold a card-number or bank-account shape");
        }
        return reason;
    }
}
