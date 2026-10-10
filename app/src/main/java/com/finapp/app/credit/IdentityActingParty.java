package com.finapp.app.credit;

import com.finapp.credit.CreditActingParty;
import com.finapp.identity.IdentityId;
import com.finapp.identity.IdentityStore;
import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * {@code credit}'s {@link CreditActingParty} over the identity store (the Phase 10 to 11 transition) - the composition
 * root's half, because {@code credit} cannot see {@code identity}. A session's actor is its identity
 * ({@code SessionAuthenticationInterceptor}: the actor's id is the identity's), and every identity belongs to a party;
 * an actor id that is no identity - a malformed id, or one never registered - answers empty, which the review refuses.
 * Read on the act's own connection, never cached.
 */
@RequiredArgsConstructor
public final class IdentityActingParty implements CreditActingParty<Connection> {

    @NonNull private final IdentityStore<Connection> identities;

    @Override
    public Optional<UUID> partyOf(Connection unitOfWork, Actor actor) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(actor, "actor");
        UUID identity;
        try {
            identity = UUID.fromString(actor.id());
        } catch (IllegalArgumentException notAnIdentity) {
            return Optional.empty();
        }
        return identities.findById(unitOfWork, IdentityId.of(identity)).map(found -> found.partyId());
    }
}
