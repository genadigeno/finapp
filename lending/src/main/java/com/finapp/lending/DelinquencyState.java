package com.finapp.lending;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A loan's delinquency and default condition on a business date - <strong>derived, never stored</strong> (`P11-TSK-024`;
 * ADR-0098 point 1, {@code PHASE_11_PLAN.md} section 12.7, {@code INV-LND-12}).
 *
 * <ul>
 *   <li><strong>Past due</strong>: a billed item - an instalment's billing, a line's statement (its minimum payment) - with
 *       anything unpaid at the end of its due date: due before the business date, billed less every payer's allocations
 *       positive. A partial payment leaves the item past due: allocation pays the oldest item first, and DPD moves only
 *       when the oldest past-due item is paid in full - to the next oldest one's age, or to 0. No tolerance: a minor unit
 *       unpaid is unpaid.
 *   <li><strong>DPD</strong>: the business date less the oldest past-due item's due date; 0 when nothing is past due. A
 *       difference of dates - a missed or repeated evaluation can neither add nor lose a day. Grace (the pinned
 *       {@code grace_days}) delays only the late fee (`-025`), never DPD.
 *   <li><strong>Bucket</strong>: {@link DelinquencyBucket#of} under the pinned bounds; empty = {@code CURRENT}.
 *   <li><strong>Default</strong>: set when DPD reaches the pinned threshold (L7: 90), held while anything is past due,
 *       cleared at cure - DPD back to 0 - with no probation (A8): {@code DPD >= threshold || (previously && DPD > 0)}. The
 *       one figure that needs a memory is the flag's, and its memory is the recorded history ({@link LoanConditionEvent},
 *       kind {@code DEFAULT}) - a flag the daily evaluation set and only a cure clears.
 * </ul>
 *
 * <p>The same rule as {@code lending V020}'s trigger, which holds every recorded row to it. Nothing here is an amount.
 */
public record DelinquencyState(int daysPastDue, Optional<DelinquencyBucket> bucket, boolean defaulted) {

    /** {@code CURRENT}: nothing past due, not defaulted. */
    public static final DelinquencyState CURRENT = new DelinquencyState(0, Optional.empty(), false);

    public DelinquencyState {
        Objects.requireNonNull(bucket, "bucket must not be null");
        if (daysPastDue < 0) {
            throw new IllegalArgumentException("days past due are never negative");
        }
        if ((daysPastDue == 0) != bucket.isEmpty()) {
            throw new IllegalArgumentException("a loan is CURRENT exactly when nothing is past due");
        }
        if (defaulted && daysPastDue == 0) {
            throw new IllegalArgumentException("a defaulted loan is past due - cure clears the flag (A8)");
        }
    }

    /**
     * The condition of a loan whose billed items are {@code items} (what each still has due), on {@code today}, under the
     * pinned {@code terms}, its default flag last recorded {@code previouslyDefaulted}.
     */
    public static DelinquencyState derive(
            List<DueState.DueItem> items, LocalDate today, LoanTerms terms, boolean previouslyDefaulted) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(terms, "terms");
        Optional<LocalDate> oldest = items.stream()
                .filter(item -> item.dueDate().isBefore(today) && item.total().isPositive())
                .map(DueState.DueItem::dueDate)
                .min(LocalDate::compareTo);
        int dpd = oldest.map(due -> Math.toIntExact(ChronoUnit.DAYS.between(due, today))).orElse(0);
        boolean defaulted = dpd >= terms.defaultThresholdDays() || (previouslyDefaulted && dpd > 0);
        return new DelinquencyState(dpd, DelinquencyBucket.of(dpd, terms.delinquencyBounds()), defaulted);
    }

    /** Whether anything is past due. */
    public boolean pastDue() {
        return daysPastDue > 0;
    }

    /** {@code CURRENT} or the bucket's name - the value {@code lending V020} records. */
    public String bucketName() {
        return bucket.map(Enum::name).orElse("CURRENT");
    }

    /**
     * Whether a credit line in this condition takes no draw (A25, ADR-0098 point 5): anything past due, or defaulted.
     * Under A8 a defaulted loan is always past due, so the second arm adds nothing today - kept so a probation (a flag held
     * after cure) would suspend draws without a second change.
     */
    public boolean suspendsDraws() {
        return pastDue() || defaulted;
    }

    @Override
    public String toString() {
        return "DelinquencyState[redacted]";
    }
}
