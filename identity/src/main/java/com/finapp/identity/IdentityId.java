package com.finapp.identity;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies an {@link Identity} — a means by which someone proves they are present.
 *
 * <p>Never a {@code PartyId} and never a {@code CustomerId}. One Party may hold several
 * Identities — a retired login and its replacement, or a person who is also staff — so an
 * identifier that served for both would make the second one unrepresentable.
 */
public final class IdentityId extends EntityId {

    private IdentityId(UUID value) {
        super(value);
    }

    public static IdentityId next(IdGenerator ids) {
        return new IdentityId(ids.next());
    }

    public static IdentityId of(String text) {
        return new IdentityId(UUID.fromString(text));
    }

    public static IdentityId of(UUID value) {
        return new IdentityId(value);
    }
}
