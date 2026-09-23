package com.finapp.app.merchant;

import com.finapp.ledger.JournalLine;
import com.finapp.merchant.MerchantSettlement;
import com.finapp.payments.RefundComposition;
import com.finapp.payments.RefundReservation;
import com.finapp.payments.RefundSettlement;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

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
@RequiredArgsConstructor
public final class MerchantBoundRefundComposition implements RefundComposition<Connection> {

    @NonNull private final MerchantSettlement settlement;
    @NonNull private final RefundComposition<Connection> walletRefund;

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

    /**
     * What the dispatch holds (`P6-TSK-015`, ADR-0054): the merchant module's NET when the
     * payment is a merchant's, and otherwise the wallet refund's gross — {@link #settle}'s
     * ordering exactly, so the hold and the lines always come from the same side of the join.
     */
    @Override
    public Money reserve(Connection unitOfWork, RefundReservation refund) {
        return settlement
                .reservation(
                        unitOfWork,
                        refund.intent().value(),
                        refund.debit(),
                        refund.refunded(),
                        refund.refundedBefore())
                .orElseGet(() -> walletRefund.reserve(unitOfWork, refund));
    }
}
