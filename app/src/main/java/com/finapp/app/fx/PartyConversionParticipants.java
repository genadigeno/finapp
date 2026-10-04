package com.finapp.app.fx;

import com.finapp.fx.ConversionParticipants;
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
 * {@code fx}'s {@link ConversionParticipants} over the party projection (`P9-TSK-008`; the
 * {@code VerifiedAccountHolder} precedent): read per decision, in the caller's transaction, no
 * cache - a suspended customer is refused on their very next quote.
 */
@RequiredArgsConstructor
public final class PartyConversionParticipants implements ConversionParticipants {

    @NonNull private final PartyStore<Connection> parties;

    @Override
    public Optional<UUID> activeCustomer(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        return parties
                .findLiveCustomerFor(unitOfWork, PartyId.of(partyId))
                .filter(customer -> customer.status() == CustomerStatus.ACTIVE)
                .map(customer -> customer.id().value());
    }
}
