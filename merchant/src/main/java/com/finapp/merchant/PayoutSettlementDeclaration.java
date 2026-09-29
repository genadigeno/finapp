package com.finapp.merchant;

import com.finapp.ledger.AccountPurpose;

/**
 * Where the payout's money-in-flight sits, declared once (`P8-TSK-002`, ADR-0064,
 * {@code INV-SET-05}).
 *
 * <p>The payment rails each declare their clearing position in {@code RailCapabilities}, and
 * every posting reads it off the declaration ({@code INV-RAIL-04}). The payout is not a rail —
 * it dispatches through {@code PayoutProvider} (ADR-0057) — so until Phase 8 its clearing
 * position lived as a bare literal inside {@link MerchantPayoutOutcomes}. Settlement's source
 * register composes "which position does this counterparty's evidence discharge" from each
 * counterparty's own declaration, so the payout gains one: this constant is what
 * {@code MerchantPayoutOutcomes} posts to and what the payout report's settlement source reads
 * (`MODULE_ARCHITECTURE.md` §Merchant, Phase 8), one definition for the poster and the
 * reconciler alike. Still {@code PAYOUT_CLEARING}; no behaviour change.
 */
public final class PayoutSettlementDeclaration {

    /** The position every payout's completion posts against, and its evidence discharges. */
    public static final AccountPurpose CLEARING_PURPOSE = AccountPurpose.PAYOUT_CLEARING;

    private PayoutSettlementDeclaration() {}
}
