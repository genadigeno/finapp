package com.finapp.party;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link Customer} — a relationship a {@link Party} holds toward the platform.
 *
 * <p>Distinct from {@link PartyId}, and the type system is what keeps them distinct:
 * {@code EntityId} includes the concrete class in its identity, so a {@code PartyId} and a
 * {@code CustomerId} carrying the same UUID are never equal and neither can be passed where the
 * other is required. That is the compile-time half of ADR-0029.
 */
public final class CustomerId extends EntityId {

    private CustomerId(UUID value) {
        super(value);
    }

    public static CustomerId next(IdGenerator ids) {
        return new CustomerId(ids.next());
    }

    public static CustomerId of(String text) {
        return new CustomerId(UUID.fromString(text));
    }

    public static CustomerId of(UUID value) {
        return new CustomerId(value);
    }
}
