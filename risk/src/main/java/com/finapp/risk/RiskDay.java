package com.finapp.risk;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * One UTC date as the proofs read it (`P13-TSK-023`; {@code DESIGN-023} D1) - <strong>a range of identifiers</strong>.
 * Every row the proofs count is keyed by a UUIDv7 its writer minted (ADR-0013: 48 bits of milliseconds first): risk's
 * assessments, alerts and cases, the ledger's journal entries, the owners' transfers, withdrawals, payment intents and
 * cross-border payments. So the day {@code D} is {@code [positionAt(D 00:00Z), positionAt(D+1 00:00Z))} - a primary-key
 * range scan in every module, no new index - and a record's day is the day its identifier was minted. A record minted
 * within the instances' skew of midnight may fall on the other day than its counterpart; the censuses reconcile that by
 * looking the counterpart up whatever its day, so no boundary manufactures a difference.
 *
 * <p>The day is the velocity windows' {@code D1} day too: UTC midnight ({@link WindowStarts}).
 */
public record RiskDay(LocalDate date) {

    public RiskDay {
        Objects.requireNonNull(date, "date");
    }

    /** The day {@code date}. */
    public static RiskDay of(LocalDate date) {
        return new RiskDay(date);
    }

    /** The UTC day {@code instant} falls in. */
    public static RiskDay containing(Instant instant) {
        return new RiskDay(LocalDate.ofInstant(Objects.requireNonNull(instant, "instant"), ZoneOffset.UTC));
    }

    /** The day's first instant, UTC midnight. */
    public Instant start() {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** The next day's first instant - the day's exclusive end. */
    public Instant end() {
        return date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** The lowest identifier minted at the day's first millisecond - every one of the day's sorts at or after it. */
    public UUID fromId() {
        return MonitoringWatermark.positionAt(start().toEpochMilli());
    }

    /** The lowest identifier of the next day - every one of the day's sorts before it. */
    public UUID toId() {
        return MonitoringWatermark.positionAt(end().toEpochMilli());
    }

    /** True when {@code id} was minted on this day. */
    public boolean holds(UUID id) {
        Objects.requireNonNull(id, "id");
        return RiskProofs.order(id, fromId()) >= 0 && RiskProofs.order(id, toId()) < 0;
    }

    /** The day before. */
    public RiskDay previous() {
        return new RiskDay(date.minusDays(1));
    }

    @Override
    public String toString() {
        return date.toString();
    }
}
