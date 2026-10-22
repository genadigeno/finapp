package com.finapp.risk;

import java.sql.Connection;
import java.util.UUID;

/**
 * Crossborder's door, as a risk action reaches it (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 12.8; ADR-0112) -
 * {@code app} implements this over the beneficiary's existing block, keyed and audited by crossborder. Dispatched in
 * T-act (`-017`).
 */
@FunctionalInterface
public interface BeneficiaryActions {

    ActionOutcome block(Connection unitOfWork, UUID beneficiaryId, String idempotencyKey, String reason);
}
