package com.finapp.risk;

import java.sql.Connection;
import java.util.UUID;

/**
 * Identity's doors, as a risk action reaches them (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 12.8; ADR-0112;
 * owner decision C10) - {@code app} implements this over identity's existing administration (suspend, reinstate,
 * revoke sessions), each call keyed {@code risk.action:<id>} and audited by identity as its own act. The platform never
 * resets a credential. Dispatched by the action schedule in T-act (`-017`), on identity's own lock order, never with a
 * risk row held but the action's own.
 */
public interface IdentityActions {

    ActionOutcome suspend(Connection unitOfWork, UUID identityId, String idempotencyKey, String reason);

    ActionOutcome reinstate(Connection unitOfWork, UUID identityId, String idempotencyKey, String reason);

    ActionOutcome revokeSessions(Connection unitOfWork, UUID identityId, String idempotencyKey, String reason);
}
