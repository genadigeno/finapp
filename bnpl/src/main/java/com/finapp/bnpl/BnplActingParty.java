package com.finapp.bnpl;

import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

/**
 * Whose party an acting person is (`P12-TSK-021`; ADR-0105 decision 9 - the operator's refund door refuses self-dealing;
 * lending's {@code LoanActingParty} and credit's {@code CreditActingParty}, one implementation for all three in
 * {@code app}): the party behind the identity that refunds a BNPL purchase as an operator, read on the caller's unit of
 * work. Every identity belongs to a party - an employee who is also a customer holds one party for both - so an
 * operator's refund of a purchase of their own party is refused ({@code bnpl.SelfDealingRefused}) and recorded
 * {@code FAILED}. bnpl cannot see {@code identity}.
 */
@FunctionalInterface
public interface BnplActingParty {

    /** The party {@code actor}'s identity belongs to - empty when the actor is no identity the platform knows. */
    Optional<UUID> partyOf(Connection unitOfWork, Actor actor);
}
