package com.finapp.merchant;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies one immutable {@link FeeScheduleVersion} (`P6-TSK-004`).
 *
 * <p><strong>This is the pinned policy reference</strong> — {@code INV-HIST-04}'s first
 * subject on this platform, five phases after the invariant was written. Every fee assessment
 * records this value, and because the row it names can never change, recomputing under it
 * reproduces the amount to the minor unit for as long as the record exists
 * ({@code INV-MER-03}).
 *
 * <p>An assessment that recorded the <em>schedule</em> id instead would be unreproducible the
 * moment a second version existed, which is the defect this type exists to make impossible.
 */
public final class FeeScheduleVersionId extends EntityId {

    private FeeScheduleVersionId(UUID value) {
        super(value);
    }

    public static FeeScheduleVersionId next(IdGenerator ids) {
        return new FeeScheduleVersionId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static FeeScheduleVersionId of(UUID value) {
        return new FeeScheduleVersionId(value);
    }
}
