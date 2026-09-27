package com.finapp.app.merchant;

import com.finapp.ledger.JournalLine;
import com.finapp.merchant.MerchantSettlement;
import com.finapp.payments.ChargebackAttribution;
import com.finapp.payments.DisputeComposition;
import java.sql.Connection;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The join at the dispute (`P7-TSK-013`, ADR-0061 §4): {@code payments} cannot see
 * {@code merchant}, and {@code merchant} knows an intent only as a UUID it was handed — so the
 * composition root is where they meet, {@link MerchantBoundRefundComposition}'s shape one
 * lifecycle later.
 *
 * <p>Ask the merchant module first. It answers empty for a payment that is nobody's merchant's
 * — a wallet top-up — and the wallet's two lines charge the customer's wallet; for a merchant's
 * payment it checks the counterparty IS the pinned merchant's payable and answers the payable's
 * two lines. <strong>The presence of a fee pin decides</strong>, read authoritatively per
 * attribution, never a flag anybody sets. No fee lines either way: a chargeback does not return
 * the merchant's processing fee.
 */
@RequiredArgsConstructor
public final class MerchantBoundDisputeComposition implements DisputeComposition<Connection> {

    @NonNull private final MerchantSettlement settlement;
    @NonNull private final DisputeComposition<Connection> walletDispute;

    @Override
    public List<JournalLine> attribute(Connection unitOfWork, ChargebackAttribution attribution) {
        return settlement
                .chargedBack(
                        unitOfWork,
                        attribution.intent().value(),
                        attribution.counterparty(),
                        attribution.counterpart(),
                        attribution.amount())
                .orElseGet(() -> walletDispute.attribute(unitOfWork, attribution));
    }

    /** {@link #attribute}'s exact inverse, from the same side of the join. */
    @Override
    public List<JournalLine> restore(Connection unitOfWork, ChargebackAttribution attribution) {
        return settlement
                .chargebackReturned(
                        unitOfWork,
                        attribution.intent().value(),
                        attribution.counterparty(),
                        attribution.counterpart(),
                        attribution.amount())
                .orElseGet(() -> walletDispute.restore(unitOfWork, attribution));
    }
}
