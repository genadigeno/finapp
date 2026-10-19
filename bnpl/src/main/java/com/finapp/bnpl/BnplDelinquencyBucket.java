package com.finapp.bnpl;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The days-past-due band of a past-due BNPL obligation (`P12-TSK-020`; {@code PHASE_12_PLAN.md} section 12.8, A14;
 * lending's {@code DelinquencyBucket}, ADR-0098's shape). A <strong>condition</strong>, never a lifecycle state: derived
 * daily from the billed and settled rows, recorded as an append-only change ({@code bnpl_condition_event}). The names carry
 * the default bounds; the bounds an obligation is judged by are the ones its pinned terms version declares
 * ({@link BnplTerms#delinquencyBounds} - the first 1, the last the default threshold). Declared in ascending order. A
 * {@code CURRENT} obligation has no bucket ({@link #of} answers empty at DPD 0).
 */
public enum BnplDelinquencyBucket {

    /** 1 to 29 days past due (by the default bounds). */
    B1_1_29,

    /** 30 to 59 days past due. */
    B2_30_59,

    /** 60 to 89 days past due. */
    B3_60_89,

    /** The default threshold or more (A14: 90). */
    B4_90_PLUS;

    /** The recorded name of "no bucket" - DPD 0. */
    public static final String CURRENT = "CURRENT";

    /**
     * The bucket of {@code daysPastDue} under a terms version's four ascending lower bounds: the highest bucket whose lower
     * bound DPD has reached; empty at DPD 0 ({@code CURRENT}). The same rule as {@code bnpl V013}'s
     * {@code bnpl.delinquency_bucket}.
     */
    public static Optional<BnplDelinquencyBucket> of(int daysPastDue, List<Integer> bounds) {
        Objects.requireNonNull(bounds, "bounds");
        BnplDelinquencyBucket[] buckets = values();
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

    /** {@code CURRENT} or the bucket's name, as recorded. */
    public static String nameOf(Optional<BnplDelinquencyBucket> bucket) {
        return Objects.requireNonNull(bucket, "bucket").map(Enum::name).orElse(CURRENT);
    }

    /** The recorded name back to the bucket - empty for {@code CURRENT}. */
    public static Optional<BnplDelinquencyBucket> fromRecorded(String recorded) {
        return CURRENT.equals(recorded) ? Optional.empty() : Optional.of(valueOf(recorded));
    }

    /** {@code CURRENT} and every bucket as a SQL {@code IN} list - {@code bnpl V013}'s bucket {@code CHECK}. */
    static String sqlValueList() {
        return "'" + CURRENT + "', " + Arrays.stream(values()).map(bucket -> "'" + bucket.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
