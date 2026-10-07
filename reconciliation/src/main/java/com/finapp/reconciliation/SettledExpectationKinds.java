package com.finapp.reconciliation;

import java.sql.Connection;
import java.util.Set;
import java.util.UUID;

/**
 * The expectation kinds a source's evidence settles - every kind an opener on the source's position will date
 * against the source's ACTIVE rule set (the Phase 9 -> 10 transition): declared here, implemented in {@code app} over
 * settlement's source declarations. The first-version door refuses a version that dates fewer, because an opener of
 * a kind the version lacks finds no lag and rolls back the money movement that opened it - every cover execution,
 * completion or return of that kind - until a successor lands.
 */
@FunctionalInterface
public interface SettledExpectationKinds {

    /** No declared kinds: the first-version door then judges the proposal's own shape alone. */
    SettledExpectationKinds NONE = (unitOfWork, sourceId) -> Set.of();

    /** The kinds {@code sourceId}'s evidence settles; empty for a source whose kinds are not declared. */
    Set<ExpectationKind> of(Connection unitOfWork, UUID sourceId);
}
