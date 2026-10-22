package com.finapp.risk;

import java.sql.Connection;
import java.util.UUID;

/**
 * Merchant's door, as a risk action reaches it (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 12.8; ADR-0112) -
 * {@code app} implements this over {@code MerchantAdministration.suspend}, keyed and audited by merchant. Dispatched in
 * T-act (`-017`). Risk never reinstates a merchant: that is the merchant administrator's own four-eyes act.
 */
@FunctionalInterface
public interface MerchantActions {

    ActionOutcome suspend(Connection unitOfWork, UUID merchantId, String idempotencyKey, String reason);
}
