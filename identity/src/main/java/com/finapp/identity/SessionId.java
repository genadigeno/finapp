package com.finapp.identity;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A session's aggregate identifier (`P1-TSK-013`).
 *
 * <p><strong>Not the token.</strong> This is the identifier a foreign key, a log line and an audit
 * record use; it is not secret and is never presented by a client. The thing the client holds is
 * {@link SessionToken}, which is secret, random, and stored only as a hash.
 *
 * <p>Two identifiers for one aggregate looks like duplication and is the opposite. A UUIDv7 encodes
 * its creation time — that is structure a client can read, and ADR-0030 requires the presented value
 * to have none. Using one value for both jobs would mean either a token that leaks when it was
 * issued, or a primary key that cannot be written down anywhere.
 */
public final class SessionId extends EntityId {

    private SessionId(UUID value) {
        super(value);
    }

    public static SessionId next(IdGenerator ids) {
        return new SessionId(ids.next());
    }

    public static SessionId of(UUID value) {
        return new SessionId(value);
    }
}
