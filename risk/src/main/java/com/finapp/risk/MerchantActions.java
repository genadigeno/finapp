package com.finapp.risk;

import java.util.UUID;

/**
 * Merchant's door, as a risk action reaches it (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 12.8; ADR-0112) -
 * {@code app} implements this over {@code MerchantAdministration.suspend} ({@code MerchantRiskActions}), keyed
 * {@code risk.action:<id>} and audited by merchant as its own act, the platform the executor. Risk never reinstates a
 * merchant: that is the merchant administrator's own act.
 *
 * <p><strong>Reshaped by its first implementation (`P13-TSK-017`)</strong>: no unit of work (merchant's own transaction
 * - T-act's shape), the reason a code. <strong>The outcomes</strong>: {@link ActionOutcome#DONE} - this key suspended it;
 * {@link ActionOutcome#ALREADY} - it was already suspended; {@link ActionOutcome#REFUSED} - no such merchant, or closed.
 */
@FunctionalInterface
public interface MerchantActions {

    ActionOutcome suspend(UUID merchantId, String idempotencyKey, String reason);
}
