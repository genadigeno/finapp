package com.finapp.identity;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link Credential}.
 *
 * <p>Typed like every other identifier (ADR-0013), so passing an {@code IdentityId} where a
 * credential's is required is a compile error rather than a row that points at the wrong thing.
 *
 * <p>A credential is an entity inside the Identity aggregate and is never independently
 * addressable, so this identifier appears in no URL and no API contract. It exists because the row
 * needs a primary key and because superseding one names it.
 */
public final class CredentialId extends EntityId {

    private CredentialId(UUID value) {
        super(value);
    }

    public static CredentialId next(IdGenerator ids) {
        return new CredentialId(ids.next());
    }

    public static CredentialId of(UUID value) {
        return new CredentialId(value);
    }
}
