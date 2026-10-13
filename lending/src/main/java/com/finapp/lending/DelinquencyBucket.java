package com.finapp.lending;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The days-past-due band of a {@code PAST_DUE} loan (`P11-TSK-002`; {@code LENDING_LIFECYCLES.md} section 5, ADR-0098,
 * built by `P11-TSK-024`).
 *
 * <p>A <strong>condition</strong>, never a lifecycle state: derived daily from billed and allocated rows, recorded as an
 * append-only change ({@code INV-LND-12}). The names carry the default bounds; the bounds a loan is judged by are the
 * ones its pinned terms version declares. Declared in ascending order of days past due. A {@code CURRENT} loan has no
 * bucket ({@link #of} answers empty at DPD 0).
 */
public enum DelinquencyBucket {

    /** 1 to 29 days past due. */
    B1_1_29,

    /** 30 to 59 days past due. */
    B2_30_59,

    /** 60 to 89 days past due. */
    B3_60_89,

    /** 90 days or more past due - the default threshold (L7). */
    B4_90_PLUS;

    /**
     * The bucket of {@code daysPastDue} under a terms version's four ascending lower bounds ({@link LoanTerms#delinquencyBounds}
     * - the first 1, the last the default threshold): the highest bucket whose lower bound DPD has reached; empty at DPD 0
     * ({@code CURRENT}). The same rule as {@code lending V020}'s {@code lending.delinquency_bucket}.
     */
    public static Optional<DelinquencyBucket> of(int daysPastDue, List<Integer> bounds) {
        Objects.requireNonNull(bounds, "bounds");
        DelinquencyBucket[] buckets = values();
        if (bounds.size() != buckets.length) {
            throw new IllegalArgumentException("one lower bound per bucket");
        }
        if (daysPastDue < 0) {
            throw new IllegalArgumentException("days past due are never negative");
        }
        if (daysPastDue == 0) {
            return Optional.empty();
        }
        for (int i = buckets.length - 1; i > 0; i--) {
            if (daysPastDue >= bounds.get(i)) {
                return Optional.of(buckets[i]);
            }
        }
        return Optional.of(buckets[0]);
    }
}
