package com.finapp.lending;

import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

/**
 * Whose party an acting person is (`P11-TSK-020`; ADR-0099 point 2, {@code INV-LND-11}, the Phase 10 to 11 transition's
 * R1): the party behind the identity that acts on a loan as an operator, read on the caller's unit of work. Every identity
 * on the platform belongs to a party - an employee who is also a customer holds one party for both - so a servicing act on
 * a loan of the actor's own party is refused ({@code lending.SelfDealingRefused}). {@code lending} cannot see
 * {@code identity}; {@code app} composes the read (credit's {@code CreditActingParty}'s twin, one implementation for both).
 */
@FunctionalInterface
public interface LoanActingParty {

    /** The party {@code actor}'s identity belongs to - empty when the actor is no identity the platform knows. */
    Optional<UUID> partyOf(Connection unitOfWork, Actor actor);
}
