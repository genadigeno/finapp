package com.finapp.fx;

import com.finapp.platform.audit.AuditableAction;
import lombok.RequiredArgsConstructor;

/**
 * The FX module's audited acts (`P9-TSK-007`, ADR-0075 §7, {@code INV-AUD-04}): the pricing
 * policy's proposal, activation and rejection, and the availability kill switch with its enable
 * proposals. Every one is reasoned - the price and whether money may move are policy, and a
 * policy act without a stated reason is not reviewable.
 */
@RequiredArgsConstructor
public enum FxAuditAction implements AuditableAction {

    /** The owner converted between two of their wallets (`P9-TSK-009`) - a customer act. */
    FX_CONVERSION_EXECUTED(
            "fx.ConversionExecuted",
            "The owner accepted a quote and the conversion was booked: the trade, its entry"
                    + " fx-trade:<id> and the cover it wants, in one transaction.",
            false),

    /** The owner cancelled a live quote (`P9-TSK-008`) - a customer act. */
    QUOTE_CANCELLED(
            "fx.QuoteCancelled",
            "The owner cancelled an ISSUED quote before it expired; the price is released and the"
                    + " quote can never be accepted.",
            false),

    /** A whole pricing policy version was proposed - frozen from this moment. */
    PRICING_POLICY_PROPOSED(
            "fx.PricingPolicyProposed",
            "A pricing policy version was proposed by an FX_CONTROLLER, with its reason; nothing is"
                    + " priced by it until a different person activates it.",
            true),

    /** A different person activated a proposed version, retiring its predecessor. */
    PRICING_POLICY_ACTIVATED(
            "fx.PricingPolicyActivated",
            "A pricing policy version was activated by someone other than its proposer"
                    + " (INV-AUD-04), retiring its predecessor in the same transaction.",
            true),

    /** A proposed version was rejected - or withdrawn by its proposer. */
    PRICING_POLICY_REJECTED(
            "fx.PricingPolicyRejected",
            "A proposed pricing policy version was rejected, or withdrawn by its proposer; no"
                    + " policy changed.",
            true),

    /** A pair or provider was disabled - one person, at once. */
    AVAILABILITY_DISABLED(
            "fx.AvailabilityDisabled",
            "A pair or provider was disabled by one FX_CONTROLLER with a reason, at once: stopping"
                    + " money never waits for a second person.",
            true),

    /** Enabling a pair or provider was proposed. */
    AVAILABILITY_ENABLE_PROPOSED(
            "fx.AvailabilityEnableProposed",
            "Enabling a pair or provider was proposed; nothing is enabled until a different"
                    + " person approves.",
            true),

    /** A different person approved an enable proposal; the enabling fact was appended. */
    AVAILABILITY_ENABLED(
            "fx.AvailabilityEnabled",
            "An enable proposal was approved by someone other than its proposer (INV-AUD-04), and"
                    + " the enabling fact appended in the same transaction.",
            true),

    /** An enable proposal was rejected - or withdrawn by its proposer. */
    AVAILABILITY_ENABLE_REJECTED(
            "fx.AvailabilityEnableRejected",
            "An enable proposal was rejected, or withdrawn by its proposer; availability did not"
                    + " change.",
            true),

    COVER_EXECUTED(
            "fx.CoverExecuted",
            "The platform's cover executed with its provider: the execution fact, the entry"
                    + " fx-cover:<id> closing the plan's position legs and the leg expectations, in one"
                    + " transaction - written by the acting applier only.",
            false),

    COVER_REQUOTED(
            "fx.CoverRequoted",
            "A definitively rejected cover was requoted at a fresh, plausible firm quote: a new"
                    + " attempt and reference T(n+1), stored before it is sent - written by the acting"
                    + " requoter only.",
            false),

    COVER_VOIDED(
            "fx.CoverVoided",
            "A definitively rejected cover its quote no longer wants was voided; nothing was ever"
                    + " executed under it.",
            false);

    private final String code;
    private final String description;
    private final boolean requiresReason;

    @Override
    public String code() {
        return code;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean requiresReason() {
        return requiresReason;
    }
}
