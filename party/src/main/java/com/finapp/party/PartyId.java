package com.finapp.party;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link Party} — who exists.
 *
 * <p>Held by value in every other module, including {@code identity}, and never joined to across a
 * schema boundary (ADR-0029). That is what keeps the boundary something Gradle and ArchUnit can
 * see, and what stops ADR-0001's stated escape — extracting a module — from becoming a data
 * migration.
 */
public final class PartyId extends EntityId {

    private PartyId(UUID value) {
        super(value);
    }

    public static PartyId next(IdGenerator ids) {
        return new PartyId(ids.next());
    }

    public static PartyId of(String text) {
        return new PartyId(UUID.fromString(text));
    }

    public static PartyId of(UUID value) {
        return new PartyId(value);
    }
}
