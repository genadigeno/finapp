package com.finapp.app.credit;

import com.finapp.credit.CreditPartyStanding;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The party facts while the platform holds none (`P10-TSK-008`; unresolved question #13): {@code party} holds a kind
 * and a name, and no module holds a date of birth or a country of residence - so both facts are answered empty, and the
 * snapshot records them {@code ABSENT} with this implementation's version. Honest rather than guessed: the policy
 * reasons about the absence (a referral or a decline, never an approval). The implementation that reads real facts is
 * a new version, so a decision frozen under this one replays identically after it.
 */
public final class PartyFactsNotHeld implements CreditPartyStanding<Connection> {

    /** This implementation's version. */
    public static final int VERSION = 1;

    @Override
    public PartyFacts facts(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(partyId, "partyId");
        return new PartyFacts(Optional.empty(), Optional.empty(), VERSION);
    }
}
