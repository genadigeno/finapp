package com.finapp.identity;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A contact channel's identifier (`P1-TSK-023`). */
public final class ContactChannelId extends EntityId {

    private ContactChannelId(UUID value) {
        super(value);
    }

    public static ContactChannelId next(IdGenerator ids) {
        return new ContactChannelId(ids.next());
    }

    public static ContactChannelId of(UUID value) {
        return new ContactChannelId(value);
    }
}
