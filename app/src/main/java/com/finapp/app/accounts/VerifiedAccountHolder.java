package com.finapp.app.accounts;

import com.finapp.accounts.AccountHolderVerification;
import com.finapp.party.CustomerStatus;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link AccountHolderVerification} over the party store (`P3-TSK-012`).
 *
 * <p>The composition root's half of the gate, because {@code accounts} cannot see {@code party}
 * (the {@code CaseKindResolver} shape): the party's <strong>live</strong> customer — the row the
 * one-live-relationship index guards — filtered to {@code ACTIVE}, which {@code INV-KYC-05}
 * makes a faithful projection of the KYC decision. Consumed, never recomputed: no read of
 * {@code kyc} anywhere on this path.
 *
 * <p><strong>Authoritative, per decision, on the caller's connection.</strong> Nothing here
 * caches: a customer closed on another instance is refused by this one's very next opening
 * (the {@code ConsentGate} discipline — an eventually-refused holder is an unrefused holder).
 * A {@code PENDING} customer and an absent one are the same empty answer, which is
 * {@link AccountHolderVerification}'s own contract.
 *
 * <p>No bean, deliberately: `P3-TSK-013`'s endpoint is the first wiring, and unconsumed wiring
 * is the {@code P1-TSK-007} licence's subject. The class exists in production code because the
 * gate it implements is production behaviour the database tests drive as the application will.
 */
public final class VerifiedAccountHolder implements AccountHolderVerification<Connection> {

    private final PartyStore<Connection> parties;

    public VerifiedAccountHolder(PartyStore<Connection> parties) {
        this.parties = Objects.requireNonNull(parties, "parties must not be null");
    }

    @Override
    public Optional<UUID> eligibleCustomer(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        return parties
                .findLiveCustomerFor(unitOfWork, PartyId.of(partyId))
                .filter(customer -> customer.status() == CustomerStatus.ACTIVE)
                .map(customer -> customer.id().value());
    }
}
