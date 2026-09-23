package com.finapp.payments;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.List;

/**
 * Phase 5's refund, unchanged (`P6-TSK-014`): {@code DR the wallet / CR clearing} — the
 * capture's inverse pair, and the whole of what a wallet top-up's refund owes.
 *
 * <p>The two lines are {@code PaymentOutcomes.applyRefund}'s own, <strong>moved rather than
 * rewritten</strong> — {@link WalletTopUpComposition}'s reasoning at the mirror seam: the
 * cheapest way to keep "byte-identical" as a promise about Phase 5's path is not to retype the
 * thing being promised.
 *
 * <p>It is also the fallback for a payment that turns out to be nobody's merchant's, which is
 * what keeps a refund working for every intent this platform has ever created.
 */
public final class WalletRefundComposition implements RefundComposition<Connection> {

    @Override
    public List<JournalLine> settle(Connection unitOfWork, RefundSettlement settlement) {
        return List.of(
                new JournalLine(settlement.debit(), Direction.DEBIT, settlement.refunded()),
                new JournalLine(settlement.clearing(), Direction.CREDIT, settlement.refunded()));
    }

    /**
     * Phase 5's hold, unchanged (`P6-TSK-015`): the wallet refund takes the gross, and the
     * customer funds all of it — so the money a provider may be about to return is unspendable
     * to the cent while it decides ({@code INV-BAL-04} doing refund duty, ADR-0048 §4).
     */
    @Override
    public Money reserve(Connection unitOfWork, RefundReservation reservation) {
        return reservation.refunded();
    }
}
