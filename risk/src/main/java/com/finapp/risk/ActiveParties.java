package com.finapp.risk;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The parties a list version's activation rescreens (`P13-TSK-022`, added here - not among `P13-TSK-001`'s ports;
 * {@code PHASE_13_PLAN.md} section 12.10): every party whose customer relationship is {@code ACTIVE} or
 * {@code PENDING} (an onboarding party too - the review's MINOR 9), read in bounded pages by ascending party id after a
 * cursor, on the caller's unit of work - the enqueue's claim, so the page and the
 * permits it bears commit together. {@code app} implements it over party's store; risk sees identifiers only.
 */
@FunctionalInterface
public interface ActiveParties {

    /** The largest page one read returns. */
    int BOUND = 1000;

    /**
     * Up to {@code limit} ({@code 1..BOUND}) parties with an {@code ACTIVE} or {@code PENDING} customer relationship,
     * ascending by id, strictly after {@code after} when present.
     */
    List<UUID> after(Connection unitOfWork, Optional<UUID> after, int limit);
}
