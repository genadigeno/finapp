package com.finapp.reconciliation;

import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

/**
 * The fee check's one pure seat (`P8-TSK-012`, ADR-0068 §7; `INV-MON-03`, `INV-REC-08`):
 * expected = round(rate × gross + fixed) under the pinned schedule's NAMED rounding, the
 * comparison strict — a deviation EXACTLY at the tolerance raises nothing, one minor unit
 * beyond does. Both quantities are unposted (the reported fee was expensed at acceptance,
 * `P8-TSK-009`), which is exactly why this is the one legitimate tolerance: it can never
 * absorb value already in a position.
 *
 * <p>Conservative defaults, never loosened ones: an absent schedule or an absent gross
 * (the fee's {@code ORIGINAL_REF} reached no capture — the design's F1) prices the
 * expected fee at ZERO, and an absent tolerance row reads zero — the whole reported fee
 * then stands at issue.
 */
public final class FeeCheck {

    private FeeCheck() {}

    /** One pinned `provider_fee_schedule` row: {@code rate numeric(7,6)}, named rounding. */
    public record Schedule(BigDecimal rate, long fixedMinor, int scale, RoundingMode rounding) {

        public Schedule {
            Objects.requireNonNull(rate, "rate must not be null");
            Objects.requireNonNull(rounding, "rounding must not be null");
        }
    }

    /** What the decision freezes: expected, the absolute deviation, and the breach. */
    public record Verdict(long expectedMinor, long deviationMinor, boolean beyondTolerance) {}

    public static Verdict check(
            Money reported,
            Optional<Money> gross,
            Optional<Schedule> schedule,
            long toleranceMinor) {
        Objects.requireNonNull(reported, "reported must not be null");
        Objects.requireNonNull(gross, "gross must not be null");
        Objects.requireNonNull(schedule, "schedule must not be null");

        long expected = 0L;
        if (schedule.isPresent() && reported.scale() != schedule.get().scale()) {
            // P9-TSK-003: the arithmetic below is raw minor units, so a schedule at another scale
            // would misprice by a power of ten, silently. Refused loud - the caller's savepoint
            // contains the line - never priced (INV-MON-05). The proposal door refuses such a
            // schedule before it can be stored; this is the second rank.
            throw new IllegalArgumentException(
                    "a fee schedule at scale " + schedule.get().scale() + " cannot price a fee at"
                            + " scale " + reported.scale() + " (INV-MON-05)");
        }
        if (schedule.isPresent() && gross.isPresent()) {
            Schedule terms = schedule.get();
            if (!gross.get().currency().equals(reported.currency())) {
                throw new IllegalArgumentException(
                        "a fee is judged in its gross's own currency (INV-MON-04)");
            }
            expected =
                    terms.rate()
                                    .multiply(BigDecimal.valueOf(gross.get().minorUnits()))
                                    .setScale(0, terms.rounding())
                                    .longValueExact()
                            + terms.fixedMinor();
        }
        long deviation = Math.abs(reported.minorUnits() - expected);
        // Strict: exactly at the tolerance is within it (scenario 7's boundary).
        return new Verdict(expected, deviation, deviation > toleranceMinor);
    }
}
