package com.finapp.merchant;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link FeeSchedule} — the stable commercial identity a merchant is assigned to
 * (`P6-TSK-004`, ADR-0050 §5).
 *
 * <p><strong>This is what is assigned; {@link FeeScheduleVersionId} is what is pinned.</strong>
 * The distinction is the whole design: assignment names the commercial relationship ("this
 * merchant is on Standard"), while the version names the arithmetic that priced one
 * assessment. Were merchants assigned to versions, every price change would mean re-assigning
 * every merchant, and the first re-assignment somebody missed would leave two merchants on
 * "the same deal" priced differently with nothing in the record saying so.
 */
public final class FeeScheduleId extends EntityId {

    private FeeScheduleId(UUID value) {
        super(value);
    }

    public static FeeScheduleId next(IdGenerator ids) {
        return new FeeScheduleId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static FeeScheduleId of(UUID value) {
        return new FeeScheduleId(value);
    }
}
