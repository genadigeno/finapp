package com.finapp.accounting;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A GL mapping version's identity (`P14-TSK-003`; {@code accounting V002}'s {@code gl_mapping_version}). */
public final class GlMappingVersionId extends EntityId {

    private GlMappingVersionId(UUID value) {
        super(value);
    }

    public static GlMappingVersionId next(IdGenerator ids) {
        return new GlMappingVersionId(ids.next());
    }

    public static GlMappingVersionId of(UUID value) {
        return new GlMappingVersionId(value);
    }
}
