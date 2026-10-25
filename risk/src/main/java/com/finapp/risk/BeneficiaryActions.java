package com.finapp.risk;

import java.util.UUID;

/**
 * Crossborder's door, as a risk action would reach it (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 12.8; ADR-0112).
 *
 * <p><strong>Unimplemented: crossborder has no block command</strong> (`P13-TSK-017`'s finding). A beneficiary reaches
 * {@code BLOCKED} only by kyc's screening outcome from {@code IN_REVIEW} ({@code Beneficiaries.screeningDecided});
 * {@code ACTIVE → BLOCKED} is no edge of {@code BeneficiaryStatus}, and the only other move is the customer's own
 * {@code revoke}. A new command in another domain was outside the task, so {@link ActionKind#BLOCK_BENEFICIARY} is
 * unavailable - never proposable, never dispatched - and this port is reshaped to T-act's shape (no unit of work, the
 * reason a code) for the owner item that builds the command (`-011`'s note: such a block would take the beneficiary
 * {@code FOR UPDATE}, the cross-border authorisation holding it {@code FOR SHARE} before its money rows - no cycle).
 */
@FunctionalInterface
public interface BeneficiaryActions {

    ActionOutcome block(UUID beneficiaryId, String idempotencyKey, String reason);
}
