package com.finapp.payments;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import java.sql.Connection;
import java.util.List;

/**
 * The counterparty's side of a chargeback for a payment that is nobody's merchant's
 * (`P7-TSK-013`) — a wallet top-up: {@code DR the customer's wallet / CR the counterpart}, and
 * its exact inverse when the network returns the funds. {@link WalletRefundComposition}'s
 * shape at the dispute, and the fallback the merchant-bound composition hands anything without a
 * fee pin.
 *
 * <p>A wallet may go below zero this way: a customer who spent a top-up and then charged it back
 * owes the platform the difference — a receivable from the customer, recorded here and never
 * silently written off (ADR-0061 §5; collection is Phase 11's and 13's).
 */
public final class WalletDisputeComposition implements DisputeComposition<Connection> {

    @Override
    public List<JournalLine> attribute(Connection unitOfWork, ChargebackAttribution attribution) {
        return List.of(
                new JournalLine(attribution.counterparty(), Direction.DEBIT, attribution.amount()),
                new JournalLine(attribution.counterpart(), Direction.CREDIT, attribution.amount()));
    }

    @Override
    public List<JournalLine> restore(Connection unitOfWork, ChargebackAttribution attribution) {
        return List.of(
                new JournalLine(attribution.counterpart(), Direction.DEBIT, attribution.amount()),
                new JournalLine(
                        attribution.counterparty(), Direction.CREDIT, attribution.amount()));
    }
}
