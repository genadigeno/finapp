package com.finapp.credit;

import com.finapp.platform.security.Actor;
import java.util.Optional;
import java.util.UUID;

/**
 * Whose party an acting person is (the Phase 10 to 11 transition; ADR-0089 point 9, {@code INV-CRD-11},
 * {@code INV-AUD-04}): the party behind the identity that acts on a review case, read on the caller's unit of work. An
 * underwriter is a person, and every identity on the platform belongs to a party - a customer who is also an employee
 * holds one party for both - so the review refuses any act on a case whose applicant is the actor's own party.
 * {@code credit} cannot see {@code identity}; {@code app} composes the read.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
@FunctionalInterface
public interface CreditActingParty<T> {

    /** The party {@code actor}'s identity belongs to - empty when the actor is no identity the platform knows. */
    Optional<UUID> partyOf(T unitOfWork, Actor actor);
}
