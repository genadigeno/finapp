package com.finapp.accounting;

import com.finapp.accounting.reporting.PinnedMapping;
import java.sql.Connection;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;

/**
 * Which GL mapping version a calendar month is read under (`P14-TSK-007`; {@code INV-ACC-06}, {@code INV-ACC-07},
 * {@code PHASE_14_PLAN.md} section 6): the version its accounting period <strong>pins</strong> once the period is no
 * longer {@code OPEN} - the lock pinned the version then in force, and no later activation can reach a locked month
 * ({@code MappingRetroactive}) - and otherwise the version in force for the month (an open period, or a month no period
 * has reached). None is {@link NoActiveMapping}: until the first version is activated under four eyes the GL is not
 * readable at all, which is the production state after deployment ({@code PHASE_14_PLAN.md} section 1.1).
 *
 * <p>Plain reads on the caller's snapshot - no {@code FOR SHARE}, no advisory key - so it runs inside a
 * {@code REPEATABLE READ READ ONLY} transaction and never waits on a lock or an activation.
 */
public final class GlPins {

    /** No mapping version is pinned by, or in force for, the month - the GL cannot be read for it. */
    public static final class NoActiveMapping extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public NoActiveMapping(String detail) {
            super(detail);
        }
    }

    /** One month's accounting period as the GL needs it: its status and the version it pins, if any. */
    public record PeriodPin(PeriodStatus status, Optional<GlMappingVersionId> pinned) {

        public PeriodPin {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(pinned, "pinned");
        }
    }

    /** The accounting period of a month, read plainly on the caller's snapshot ({@link JdbcPeriodPins}). */
    @FunctionalInterface
    public interface PeriodPins {
        Optional<PeriodPin> of(Connection snapshot, YearMonth month);
    }

    private final GlMappingStore mappings;
    private final PeriodPins periods;

    public GlPins(GlMappingStore mappings, PeriodPins periods) {
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.periods = Objects.requireNonNull(periods, "periods");
    }

    /** The version {@code month} is read under, with its content. */
    public PinnedMapping forPeriod(Connection snapshot, YearMonth month) {
        Objects.requireNonNull(month, "month");
        Optional<PeriodPin> period = periods.of(snapshot, month);
        GlMappingStore.VersionRow row;
        if (period.isPresent() && period.get().status() != PeriodStatus.OPEN) {
            GlMappingVersionId pinned = period.get().pinned().orElseThrow(() -> new NoActiveMapping(
                    "accounting period " + month + " locked with no GL mapping version in force; none can apply to it"));
            row = mappings.find(snapshot, pinned).orElseThrow(() -> new IllegalStateException(
                    "accounting period " + month + " pins a mapping version the store does not hold"));
        } else {
            row = mappings.inForceFor(snapshot, month).orElseThrow(() -> new NoActiveMapping(
                    "no GL mapping version is in force for " + month));
        }
        return new PinnedMapping(month, period.map(PeriodPin::status), row.id(), row.version(),
                mappings.content(snapshot, row.id()));
    }
}
