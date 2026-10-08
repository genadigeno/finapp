package com.finapp.credit;

import com.finapp.sharedkernel.correlation.CorrelationId;

/**
 * The deciding step the progress hands an {@code EVALUATED} request to (`P10-TSK-016`; PHASE_10_PLAN.md section 12.7):
 * a transaction of its own, opened profile-first, after the progress step's claim transaction is released - so no
 * writer ever waits for the profile while holding the request.
 */
public interface Decider {

    /** What deciding did. */
    enum Decided {
        /** The decision was recorded and the request is {@code DECIDED}. */
        DECIDED,
        /** The evaluation refers: the request waits at {@code EVALUATED} for its case (`P10-TSK-018`). */
        REFERRED,
        /** The party's standing or a consent was lost: the request is {@code ABANDONED}, nothing decided. */
        ABANDONED,
        /** Nothing to decide - not {@code EVALUATED}, expired, or decided by another instance first. */
        NOTHING
    }

    /** Decides {@code id} as the platform, if it is due a decision. */
    Decided decide(DecisionRequestId id, CorrelationId correlation);
}
