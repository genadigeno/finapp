package com.finapp.risk;

import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

/**
 * Whose party an acting person is (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 11; ADR-0107 decision 3) - the port
 * every self-dealing refusal reads, declared here and implemented in {@code app} (one implementation beside lending's,
 * credit's and bnpl's acting-party ports), because risk cannot see {@code identity}.
 *
 * <p>Every identity belongs to a party - an employee who is also a customer holds one party for both - so a holder who
 * reads or works a case, approves an override or files a report on a subject of their own party is refused
 * ({@code risk.SelfDealingRefused}) and the attempt recorded {@code FAILED}; a holder who can be placed in no party is
 * refused the same way - fail closed, the Phase 12 → 13 gate's A7-6 applied forward. Read on the caller's unit of work.
 * First implemented and consumed by `-007` (the profile read; {@code app}'s {@code IdentityActingParty}, the one read
 * of whose party a person is for credit, lending, bnpl and risk); `-016`'s case doors consume it next.
 */
@FunctionalInterface
public interface RiskActingParty {

    /** The party {@code actor}'s identity belongs to - empty when the actor is no identity the platform knows. */
    Optional<UUID> partyOf(Connection unitOfWork, Actor actor);
}
