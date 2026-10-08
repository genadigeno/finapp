package com.finapp.app.credit;

import com.finapp.credit.CreditPartyStanding;
import com.finapp.party.CustomerStatus;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * {@code credit}'s {@link CreditPartyStanding} over the party store (`P10-TSK-008`, `P10-TSK-014`) - the composition
 * root's half, because {@code credit} cannot see {@code party}.
 *
 * <p><strong>The standing</strong> is the {@code VerifiedAccountHolder} gate verbatim: the party's <em>live</em> customer,
 * {@code ACTIVE} - which {@code INV-KYC-05} makes a faithful projection of KYC's approval, so no read of {@code kyc}
 * happens here. Authoritative, per decision, on the caller's connection, never cached: a suspension on another instance
 * refuses this one's very next read. A {@code PENDING}, {@code SUSPENDED}, closed or absent customer is the same answer.
 *
 * <p><strong>The facts</strong> while the platform holds none (unresolved question #13): {@code party} holds a kind and a
 * name, and no module holds a date of birth or a country of residence - so both are answered empty, and the snapshot
 * records them {@code ABSENT} with this implementation's version. Honest rather than guessed: the policy reasons about
 * the absence. The implementation that reads real facts is a new version, so a decision frozen under this one replays
 * identically after it.
 */
@RequiredArgsConstructor
public final class PartyCreditStanding implements CreditPartyStanding<Connection> {

    /** This implementation's facts version. */
    public static final int VERSION = 1;

    @NonNull private final PartyStore<Connection> parties;

    @Override
    public PartyFacts facts(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(partyId, "partyId");
        return new PartyFacts(Optional.empty(), Optional.empty(), VERSION);
    }

    @Override
    public boolean inGoodStanding(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        return parties.findLiveCustomerFor(unitOfWork, PartyId.of(partyId))
                .filter(customer -> customer.status() == CustomerStatus.ACTIVE)
                .isPresent();
    }
}
