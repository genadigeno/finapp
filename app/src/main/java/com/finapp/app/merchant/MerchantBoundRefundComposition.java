package com.finapp.app.merchant;

import com.finapp.ledger.JournalLine;
import com.finapp.merchant.MerchantSettlement;
import com.finapp.payments.RefundComposition;
import com.finapp.payments.RefundSettlement;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;

/**
 * The join (`P6-TSK-014`): {@code payments} cannot see {@code merchant}, and {@code merchant}
 * knows an intent only as a UUID it was handed — so the composition root is where they meet.
 * {@link MerchantBoundCaptureComposition}'s shape at the reversal, one task later.
 *
 * <p>Ask the merchant module first. It answers empty for a payment that is nobody's merchant's
 * — a wallet top-up — and the wallet refund composes Phase 5's two lines, byte-identical. That
 * ordering is the whole design: <strong>the presence of a fee pin is what decides</strong>,
 * read authoritatively per refund, never a flag anybody sets.
 */
public final class MerchantBoundRefundComposition implements RefundComposition<Connection> {

    private final MerchantSettlement settlement;
    private final RefundComposition<Connection> walletRefund;

    public MerchantBoundRefundComposition(
            MerchantSettlement settlement, RefundComposition<Connection> walletRefund) {
        this.settlement = Objects.requireNonNull(settlement, "settlement must not be null");
        this.walletRefund = Objects.requireNonNull(walletRefund, "walletRefund must not be null");
    }

    @Override
    public List<JournalLine> settle(Connection unitOfWork, RefundSettlement refund) {
        return settlement
                .refund(
                        unitOfWork,
                        refund.intent().value(),
                        refund.clearing(),
                        refund.debit(),
                        refund.refunded(),
                        refund.refundedBefore(),
                        refund.correlation(),
                        refund.at())
                .orElseGet(() -> walletRefund.settle(unitOfWork, refund));
    }
}
