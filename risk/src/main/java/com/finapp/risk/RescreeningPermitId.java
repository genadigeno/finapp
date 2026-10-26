package com.finapp.risk;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A rescreening permit's identity (`P13-TSK-022`; {@code risk V017}) - the born-once key of the clearance signal it
 * records ({@code risk_signal.source_event_id}) and the key of its request to kyc ({@code risk.rescreen:<id>}).
 */
public final class RescreeningPermitId extends EntityId {

    private RescreeningPermitId(UUID value) {
        super(value);
    }

    public static RescreeningPermitId next(IdGenerator ids) {
        return new RescreeningPermitId(ids.next());
    }

    public static RescreeningPermitId of(UUID value) {
        return new RescreeningPermitId(value);
    }
}
