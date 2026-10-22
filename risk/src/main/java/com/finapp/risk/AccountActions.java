package com.finapp.risk;

import java.sql.Connection;
import java.util.UUID;

/**
 * Accounts' doors, as a risk action reaches them (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 12.8; ADR-0112) -
 * {@code app} implements this over accounts' existing suspension and reinstatement, keyed and audited by accounts.
 * Dispatched in T-act (`-017`).
 */
public interface AccountActions {

    ActionOutcome suspend(Connection unitOfWork, UUID accountId, String idempotencyKey, String reason);

    ActionOutcome reinstate(Connection unitOfWork, UUID accountId, String idempotencyKey, String reason);
}
