package com.finapp.identity;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A recovery request's identifier (`P1-TSK-023`).
 *
 * <p><strong>Not the token.</strong> The token is {@link SingleUseToken} - secret, random and stored
 * only as a hash. This identifier appears in the path of {@code POST /v1/recoveries/{id}/completion}
 * and in the audit trail, so it must carry nothing an attacker can use: holding it without the token
 * achieves nothing at all. {@code SessionId}'s reasoning, applied to a shorter-lived thing.
 */
public final class RecoveryRequestId extends EntityId {

    private RecoveryRequestId(UUID value) {
        super(value);
    }

    public static RecoveryRequestId next(IdGenerator ids) {
        return new RecoveryRequestId(ids.next());
    }

    public static RecoveryRequestId of(UUID value) {
        return new RecoveryRequestId(value);
    }
}
