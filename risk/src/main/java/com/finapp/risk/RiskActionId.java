package com.finapp.risk;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A downstream action's identity - the record in risk of an effect the owning domain performs (`P13-TSK-001`; the table
 * is `P13-TSK-017`'s, {@code risk V013}) - and its key: every attempt presents the owning domain
 * {@code risk.action:<id>} ({@link RiskActions#keyOf}). A case's action's id is its {@code ACTION_PROPOSED} investigator
 * action's; an override's action's id is the override's ({@link #ofOverride}), so every key `P13-TSK-012` issued is kept
 * across the fold.
 */
public final class RiskActionId extends EntityId {

    private RiskActionId(UUID value) {
        super(value);
    }

    public static RiskActionId next(IdGenerator ids) {
        return new RiskActionId(ids.next());
    }

    public static RiskActionId of(UUID value) {
        return new RiskActionId(value);
    }

    /** The action an approved override issued: the override's own id. */
    public static RiskActionId ofOverride(DecisionOverrideId override) {
        return new RiskActionId(override.value());
    }
}
