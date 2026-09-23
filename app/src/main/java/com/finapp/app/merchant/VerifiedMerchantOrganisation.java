package com.finapp.app.merchant;

import com.finapp.merchant.MerchantVerification;
import com.finapp.party.CustomerStatus;
import com.finapp.party.PartyId;
import com.finapp.party.PartyKind;
import com.finapp.party.PartyStore;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * {@link MerchantVerification} over the party store (`P6-TSK-003`).
 *
 * <p>The composition root's half of the KYB gate, because {@code merchant} cannot see
 * {@code party} (the {@code VerifiedAccountHolder} shape, with one added predicate): the party
 * must be an <strong>{@code ORGANISATION}</strong> — a person's ACTIVE customer is a KYC
 * verdict, not a KYB one, and a merchant is a business counterparty — and its <strong>live</strong>
 * customer must be {@code ACTIVE}, which {@code INV-KYC-05} makes a faithful projection of the
 * KYB decision. Consumed, never recomputed: no read of {@code kyc} anywhere on this path.
 *
 * <p><strong>Authoritative, per decision, on the caller's connection.</strong> Nothing here
 * caches (the {@code ConsentGate} discipline). A person party, a {@code PENDING} customer and
 * an absent one are the same empty answer — {@link MerchantVerification}'s own contract.
 */
@RequiredArgsConstructor
public final class VerifiedMerchantOrganisation implements MerchantVerification<Connection> {

    @NonNull private final PartyStore<Connection> parties;

    @Override
    public Optional<UUID> eligibleOrganisation(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        // Malformed equals absent (the P1-TSK-016 discipline): the identifier is the
        // operator's assertion, and one that cannot even be a PartyId - a non-v7 UUID - is
        // the same empty answer as an unknown or unverified party, never a 500. Found by
        // this task's own endpoint suite: the fold belongs HERE, where the assertion meets
        // authoritative state, not in every caller.
        PartyId party;
        try {
            party = PartyId.of(partyId);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        boolean organisation =
                parties
                        .kindOf(unitOfWork, party)
                        .filter(kind -> kind == PartyKind.ORGANISATION)
                        .isPresent();
        if (!organisation) {
            return Optional.empty();
        }
        return parties
                .findLiveCustomerFor(unitOfWork, party)
                .filter(customer -> customer.status() == CustomerStatus.ACTIVE)
                .map(customer -> customer.id().value());
    }
}
