package com.finapp.risk;

import java.sql.Connection;
import java.util.UUID;

/**
 * A request that kyc rescreen a party (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 12.10; ADR-0107 decision 3;
 * {@code ROADMAP.md} Refinement 3) - risk asks, kyc screens: an activated screening list version enqueues one
 * rescreening permit per active party, and the sweep's rescreening leg (`-022`) requests the screening through this
 * port, which {@code app} implements over kyc's case-bound screening. Keyed by the permit, so a re-driven leg asks once;
 * the clearance comes back through {@link ScreeningClearances} and is recorded as a signal.
 */
@FunctionalInterface
public interface PartyRescreening {

    /** Request the rescreening of {@code partyId} under {@code idempotencyKey}; the outcome of the request, not the screening. */
    ActionOutcome request(Connection unitOfWork, UUID partyId, String idempotencyKey);
}
