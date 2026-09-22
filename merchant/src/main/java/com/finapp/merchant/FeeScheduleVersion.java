package com.finapp.merchant;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * One priced version of a {@link FeeSchedule} — the thing an assessment pins
 * (`P6-TSK-004`, ADR-0050 §4/§5, {@code INV-MER-03}, {@code INV-HIST-04}).
 *
 * <h2>Immutable from birth, not "once effective"</h2>
 *
 * <p>A version is a fact the moment it is written. There is no draft state, no editable
 * window, and no status column — the schema's trigger refuses <em>every</em> {@code UPDATE}
 * and {@code DELETE} unconditionally, and the application role holds no {@code UPDATE} grant
 * on the table at all.
 *
 * <p>The alternative — editable until effective — was considered and refused. It buys only
 * what superseding already buys, and it costs a mutability window plus a trigger that must
 * reason about which column may move in which state. An immutability claim enforced by
 * {@code RAISE EXCEPTION} on every update is the one that cannot be subtly wrong.
 *
 * <h2>A mistake is corrected by a later version, and ties are legal</h2>
 *
 * <p>The version effective at instant {@code T} is the one with the greatest
 * {@code effectiveFrom <= T}, ties broken by the greater {@link #version()} number
 * ({@link #EFFECTIVE_ORDER}). Ties are permitted on purpose: an operator who schedules 2.9%
 * for 1 October and catches the error can supersede it <em>at the same instant</em> rather
 * than being forced to leave a second of wrong pricing. The superseded version stays in the
 * record as evidence that it was scheduled and replaced before it ever priced anything.
 *
 * <h2>History cannot be repriced, and the database is what says so</h2>
 *
 * <p>{@code effectiveFrom} may never precede {@code createdAt}. That is checked here and again
 * as a schema {@code CHECK} over two columns — so a version that would reprice a past capture
 * is not merely refused by a convention somebody could forget, it is a row the database will
 * not hold.
 */
public final class FeeScheduleVersion {

    /**
     * Newest-effective first: by {@code effectiveFrom} descending, then by version number
     * descending. The whole resolution rule, in one place, so the SQL {@code ORDER BY} and any
     * in-memory selection cannot drift apart.
     */
    public static final Comparator<FeeScheduleVersion> EFFECTIVE_ORDER =
            Comparator.comparing(FeeScheduleVersion::effectiveFrom)
                    .thenComparingInt(FeeScheduleVersion::version)
                    .reversed();

    /** The first version of any schedule. Version numbers are per schedule, never global. */
    public static final int FIRST_VERSION = 1;

    private final FeeScheduleVersionId id;
    private final FeeScheduleId scheduleId;
    private final int version;
    private final FeeRate rate;
    private final Money fixed;
    private final RoundingPolicy roundingPolicy;
    private final RefundFeePolicy refundFeePolicy;
    private final Instant effectiveFrom;
    private final Instant createdAt;
    private final String createdBy;

    private FeeScheduleVersion(
            FeeScheduleVersionId id,
            FeeScheduleId scheduleId,
            int version,
            FeeRate rate,
            Money fixed,
            RoundingPolicy roundingPolicy,
            RefundFeePolicy refundFeePolicy,
            Instant effectiveFrom,
            Instant createdAt,
            String createdBy) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.scheduleId = Objects.requireNonNull(scheduleId, "scheduleId must not be null");
        if (version < FIRST_VERSION) {
            throw new IllegalArgumentException(
                    "Version numbers start at " + FIRST_VERSION + ", but was: " + version);
        }
        this.version = version;
        this.rate = Objects.requireNonNull(rate, "rate must not be null");
        this.fixed = Objects.requireNonNull(fixed, "fixed must not be null");
        if (fixed.isNegative()) {
            throw new IllegalArgumentException(
                    "A fixed fee part must not be negative, but was: " + fixed);
        }
        // INV-MON-03: there is no default rounding policy anywhere on this path, and this is
        // the field that makes that true for every future assessment.
        this.roundingPolicy =
                Objects.requireNonNull(roundingPolicy, "roundingPolicy must not be null");
        this.refundFeePolicy =
                Objects.requireNonNull(refundFeePolicy, "refundFeePolicy must not be null");
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (effectiveFrom.isBefore(createdAt)) {
            throw new BackdatedFeeScheduleVersionException(effectiveFrom, createdAt);
        }
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
    }

    /**
     * A new version of {@code schedule}, created now.
     *
     * @param version the next number, minted by the caller — never here, because a value
     *     object cannot arbitrate ten instances
     * @param effectiveFrom when it starts pricing, or <strong>empty for immediately</strong>.
     *     Empty is the only way to say "now", and it exists because a caller <em>cannot</em>
     *     say it with an instant: {@code createdAt} is stamped here, after the request
     *     travelled, so any "now" the caller wrote is already past by the time it arrives and
     *     is correctly refused as a backdating. The server resolving it is exact; a tolerance
     *     window would be a fudge factor inside {@code INV-MER-03}.
     * @throws BackdatedFeeScheduleVersionException if a named {@code effectiveFrom} is in the
     *     past ({@code INV-MER-03}: change is effective forward and reprices nothing)
     * @throws FeeCurrencyMismatchException if the fixed part is not in the schedule's currency
     */
    public static FeeScheduleVersion create(
            IdGenerator ids,
            FeeSchedule schedule,
            int version,
            FeeRate rate,
            Money fixed,
            RoundingPolicy roundingPolicy,
            RefundFeePolicy refundFeePolicy,
            Optional<Instant> effectiveFrom,
            String createdBy,
            Clock clock) {
        Objects.requireNonNull(schedule, "schedule must not be null");
        Objects.requireNonNull(fixed, "fixed must not be null");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        if (!schedule.currency().equals(fixed.currency())) {
            throw new FeeCurrencyMismatchException(schedule.currency(), fixed.currency());
        }
        Instant createdAt = Instant.now(clock);
        return new FeeScheduleVersion(
                FeeScheduleVersionId.next(ids),
                schedule.id(),
                version,
                rate,
                fixed,
                roundingPolicy,
                refundFeePolicy,
                effectiveFrom.orElse(createdAt),
                createdAt,
                createdBy);
    }

    /** Rehydrates a stored version. */
    public static FeeScheduleVersion rehydrate(
            FeeScheduleVersionId id,
            FeeScheduleId scheduleId,
            int version,
            FeeRate rate,
            Money fixed,
            RoundingPolicy roundingPolicy,
            RefundFeePolicy refundFeePolicy,
            Instant effectiveFrom,
            Instant createdAt,
            String createdBy) {
        return new FeeScheduleVersion(
                id,
                scheduleId,
                version,
                rate,
                fixed,
                roundingPolicy,
                refundFeePolicy,
                effectiveFrom,
                createdAt,
                createdBy);
    }

    public FeeScheduleVersionId id() {
        return id;
    }

    public FeeScheduleId scheduleId() {
        return scheduleId;
    }

    /** Monotonic within the schedule, starting at {@link #FIRST_VERSION}. */
    public int version() {
        return version;
    }

    public FeeRate rate() {
        return rate;
    }

    /** The flat part, in the schedule's currency. */
    public Money fixed() {
        return fixed;
    }

    /** The one rounding decision in the whole computation, named ({@code INV-MON-03}). */
    public RoundingPolicy roundingPolicy() {
        return roundingPolicy;
    }

    public RefundFeePolicy refundFeePolicy() {
        return refundFeePolicy;
    }

    public Instant effectiveFrom() {
        return effectiveFrom;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String createdBy() {
        return createdBy;
    }

    /** The currency this version prices, which is its schedule's. */
    public CurrencyCode currency() {
        return fixed.currency();
    }

    /** Whether this version is effective at {@code instant} — {@code effectiveFrom} inclusive. */
    public boolean isEffectiveAt(Instant instant) {
        return !effectiveFrom.isAfter(instant);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FeeScheduleVersion other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "FeeScheduleVersion["
                + id
                + ", schedule="
                + scheduleId
                + ", v"
                + version
                + ", rate="
                + rate
                + ", fixed="
                + fixed
                + ", "
                + roundingPolicy
                + ", "
                + refundFeePolicy
                + ", from="
                + effectiveFrom
                + "]";
    }
}
