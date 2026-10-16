package com.finapp.lending;

import com.finapp.sharedkernel.id.EntityId;
import java.util.UUID;

/**
 * A billed item's identity - an instalment's billing or a line's statement - as the allocation engine names it
 * (`P11-TSK-008`; ADR-0095 point 1: every allocation line names a billed item or the overflow target). The rows it
 * identifies are born by billing ({@code P11-TSK-017}) and the statement ({@code P11-TSK-022}); the item's kind follows
 * from the loan's.
 */
public final class DueItemId extends EntityId {

    private DueItemId(UUID value) {
        super(value);
    }

    public static DueItemId of(UUID value) {
        return new DueItemId(value);
    }
}
