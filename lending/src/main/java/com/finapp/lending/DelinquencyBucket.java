package com.finapp.lending;

/**
 * The days-past-due band of a {@code PAST_DUE} loan (`P11-TSK-002`; {@code LENDING_LIFECYCLES.md} section 5, ADR-0098,
 * built by `P11-TSK-024`).
 *
 * <p>A <strong>condition</strong>, never a lifecycle state: derived daily from billed and allocated rows, recorded as an
 * append-only change ({@code INV-LND-12}). The names carry the default bounds; the bounds a loan is judged by are the
 * ones its pinned terms version declares. Declared in ascending order of days past due. A {@code CURRENT} loan has no
 * bucket.
 */
public enum DelinquencyBucket {

    /** 1 to 29 days past due. */
    B1_1_29,

    /** 30 to 59 days past due. */
    B2_30_59,

    /** 60 to 89 days past due. */
    B3_60_89,

    /** 90 days or more past due - the default threshold (L7). */
    B4_90_PLUS
}
