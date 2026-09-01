package com.finapp.platform.audit;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies one audit record.
 *
 * <p>A UUIDv7 (ADR-0013), so the trail is written in roughly ascending order — the access
 * pattern an audit table has almost exclusively: append, then read by time.
 */
public final class AuditId extends EntityId {

    private AuditId(UUID value) {
        super(value);
    }

    public static AuditId next(IdGenerator generator) {
        return new AuditId(generator.next());
    }

    public static AuditId of(UUID value) {
        return new AuditId(value);
    }
}
