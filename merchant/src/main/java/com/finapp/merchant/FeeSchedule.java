package com.finapp.merchant;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * A named commercial pricing identity (`P6-TSK-004`, ADR-0050 §5) — "Standard card",
 * "Enterprise". What a merchant is assigned to.
 *
 * <p><strong>No state machine, deliberately, and that is not laziness.</strong> Every other
 * aggregate this phase built earned a machine because it <em>changes</em>. A schedule never
 * does: its name and its currency are fixed at birth, and its <em>content</em> lives in
 * {@link FeeScheduleVersion}s that are themselves immutable. There is no status to model
 * because there is no transition to make, and modelling one would invite a writer to move it.
 *
 * <p><strong>Why the schedule carries a currency.</strong> A version's fixed part is a
 * {@link com.finapp.sharedkernel.money.Money} — 0.30 of something. A schedule that priced in
 * USD cannot price a EUR capture without a conversion, and a conversion inside a fee
 * computation is a fabricated exchange rate ({@code INV-MON-04}). So the currency belongs to
 * the schedule, assignment requires it to match the merchant's settlement currency, and
 * multi-currency settlement stays Phase 9's problem rather than becoming this type's.
 *
 * <p><strong>There is no deletion and no archival status.</strong> A schedule nobody is
 * assigned to is a schedule out of use; deleting one would orphan every assessment that pinned
 * one of its versions, and an assessment that cannot name what priced it is exactly the
 * unexplainable revenue {@code INV-MER-03} exists to prevent.
 */
public final class FeeSchedule {

    /** The bound the schema {@code CHECK} is generated from. */
    public static final int MAX_NAME_LENGTH = 200;

    private final FeeScheduleId id;
    private final String name;
    private final CurrencyCode currency;
    private final Instant createdAt;
    private final String createdBy;

    private FeeSchedule(
            FeeScheduleId id,
            String name,
            CurrencyCode currency,
            Instant createdAt,
            String createdBy) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = requireBoundedName(name);
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
    }

    /** A new schedule, created now by the acting operator. */
    public static FeeSchedule create(
            IdGenerator ids, String name, CurrencyCode currency, String createdBy, Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        return new FeeSchedule(
                FeeScheduleId.next(ids), name, currency, Instant.now(clock), createdBy);
    }

    /** Rehydrates a stored schedule. */
    public static FeeSchedule rehydrate(
            FeeScheduleId id,
            String name,
            CurrencyCode currency,
            Instant createdAt,
            String createdBy) {
        return new FeeSchedule(id, name, currency, createdAt, createdBy);
    }

    public FeeScheduleId id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** The currency every version's fixed part is in, and the one it can price. */
    public CurrencyCode currency() {
        return currency;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** The acting operator's identity ({@code INV-AUD-01}). */
    public String createdBy() {
        return createdBy;
    }

    private static String requireBoundedName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "A fee schedule name is 1 to " + MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FeeSchedule other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "FeeSchedule[" + id + ", " + name + ", " + currency + "]";
    }
}
