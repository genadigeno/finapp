package com.finapp.app.risk;

import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.risk.ActiveParties;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ActiveParties} over party's store (`P13-TSK-022`; {@code DESIGN-022} D11) - the parties whose customer
 * relationship is {@code ACTIVE}, a keyset page by ascending party id ({@link PartyStore#activeCustomerPartiesAfter}),
 * read on the enqueue's own connection under its claim; identifiers only leave. {@code SUSPENDED} relationships are not
 * {@code ACTIVE} and are not rescreened (owner item O5).
 */
public final class PartyActiveParties implements ActiveParties {

    private final PartyStore<Connection> parties;

    public PartyActiveParties(PartyStore<Connection> parties) {
        this.parties = Objects.requireNonNull(parties, "parties");
    }

    @Override
    public List<UUID> after(Connection unitOfWork, Optional<UUID> after, int limit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(after, "after");
        if (limit < 1 || limit > BOUND) {
            throw new IllegalArgumentException("a page of active parties is 1.." + BOUND + " rows: " + limit);
        }
        return parties.activeCustomerPartiesAfter(unitOfWork, after.map(PartyId::of), limit).stream()
                .map(PartyId::value).toList();
    }
}
