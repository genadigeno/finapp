package com.finapp.bnpl;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A BNPL obligation's delinquency and default condition on a business date - <strong>derived, never stored</strong>
 * (`P12-TSK-020`; {@code PHASE_12_PLAN.md} section 12.8 - Phase 11 section 12.7's definitions applied per obligation; A14;
 * lending's {@code DelinquencyState}, {@code INV-LND-12} by analogy).
 *
 * <ul>
 *   <li><strong>Past due</strong>: a billed instalment with anything unpaid at the end of its due date - due before the
 *       business date, billed (fees, interest, principal) less everything settled of it by every payer and every refund
 *       positive. A partial payment leaves it past due: allocation pays the oldest first, so DPD moves only when the oldest
 *       past-due instalment is settled in full. No tolerance: a minor unit unpaid is unpaid. An unpaid late fee keeps its
 *       instalment past due (the lending precedent).
 *   <li><strong>DPD</strong>: the business date less the oldest past-due instalment's due date; 0 when nothing is past due.
 *       A difference of dates - a missed or repeated evaluation can neither add nor lose a day; never a counter. Grace (the
 *       pinned {@code grace_days}) delays only the late fee, never DPD.
 *   <li><strong>Bucket</strong>: {@link BnplDelinquencyBucket#of} under the pinned bounds; empty = {@code CURRENT}.
 *   <li><strong>Default</strong>: set when DPD reaches the pinned threshold (A14: 90), held while anything is past due,
 *       cleared at cure - DPD back to 0 - with no probation: {@code DPD >= threshold || (previously && DPD > 0)}; the flag's
 *       memory is its recorded history ({@link BnplConditionEvent}, kind {@code DEFAULT}).
 * </ul>
 *
 * <p>The same rule as {@code bnpl V013}'s trigger, which holds every recorded row to it. Nothing here is an amount;
 * {@link #toString()} is redacted (DPD is RESTRICTED-FINANCIAL, never in a log line).
 */
public record BnplDelinquencyState(int daysPastDue, Optional<BnplDelinquencyBucket> bucket, boolean defaulted) {

    /** {@code CURRENT}: nothing past due, not defaulted. */
    public static final BnplDelinquencyState CURRENT = new BnplDelinquencyState(0, Optional.empty(), false);

    public BnplDelinquencyState {
        Objects.requireNonNull(bucket, "bucket must not be null");
        if (daysPastDue < 0) {
            throw new IllegalArgumentException("days past due are never negative");
        }
        if ((daysPastDue == 0) != bucket.isEmpty()) {
            throw new IllegalArgumentException("an obligation is CURRENT exactly when nothing is past due");
        }
        if (defaulted && daysPastDue == 0) {
            throw new IllegalArgumentException("a defaulted obligation is past due - cure clears the flag");
        }
    }

    /**
     * The condition of an obligation whose billed instalments are {@code items}, on {@code today}, under the pinned
     * {@code terms}, its default flag last recorded {@code previouslyDefaulted}.
     */
    public static BnplDelinquencyState derive(List<BnplPurchaseStore.BilledItem> items, LocalDate today, BnplTerms terms,
            boolean previouslyDefaulted) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(terms, "terms");
        Optional<LocalDate> oldest = items.stream()
                .filter(item -> item.dueDate().isBefore(today) && item.dueTotal().isPositive())
                .map(BnplPurchaseStore.BilledItem::dueDate)
                .min(LocalDate::compareTo);
        int dpd = oldest.map(due -> Math.toIntExact(ChronoUnit.DAYS.between(due, today))).orElse(0);
        boolean defaulted = dpd >= terms.defaultThresholdDays() || (previouslyDefaulted && dpd > 0);
        return new BnplDelinquencyState(dpd, BnplDelinquencyBucket.of(dpd, terms.delinquencyBounds()), defaulted);
    }

    /** Whether anything is past due. */
    public boolean pastDue() {
        return daysPastDue > 0;
    }

    /** {@code CURRENT} or the bucket's name - the value {@code bnpl V013} records. */
    public String bucketName() {
        return BnplDelinquencyBucket.nameOf(bucket);
    }

    @Override
    public String toString() {
        return "BnplDelinquencyState[redacted]";
    }
}
