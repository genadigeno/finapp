package com.finapp.risk;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

/**
 * The identities a party holds (`P13-TSK-013`; {@code PHASE_13_PLAN.md} sections 7.1 T-mon, 12.5, 12.6) - the reverse of
 * {@link RiskSubjectParties}' identity join, declared here and implemented in {@code app} over identity's store, because
 * risk cannot see identity. The profile leg judges a <em>party</em>, and the account-takeover anchors (a credential
 * change, a recovery) are signals about an <em>identity</em>; this is the join between them, read on the recompute's unit
 * of work under the profile head's lock. It returns identifiers, never another module's row.
 *
 * <p>Every status counts - a suspended identity's recovery is still evidence. Empty when the party holds none this
 * build can place: the anchors are then {@code ABSENT}, never "none happened".
 */
@FunctionalInterface
public interface RiskPartyIdentities {

    /** No identity is ever placed - the suites' default: the retrospective anchors stay {@code ABSENT}. */
    RiskPartyIdentities NONE = (unitOfWork, partyId) -> List.of();

    /** The identities {@code partyId} holds, in a stable order; at most {@link #BOUND}. */
    List<UUID> identitiesOf(Connection unitOfWork, UUID partyId);

    /** The most identities one party's reading takes - a party holds one in practice; the bound keeps the read bounded. */
    int BOUND = 16;
}
