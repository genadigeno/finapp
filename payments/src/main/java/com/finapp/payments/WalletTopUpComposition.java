package com.finapp.payments;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import java.sql.Connection;
import java.util.List;

/**
 * The capture's original two lines, unchanged (`P5-TSK-013`'s posting, extracted by
 * `P6-TSK-005`): {@code DR SETTLEMENT_CLEARING / CR wallet}, for the customer topping up their
 * own wallet.
 *
 * <p><strong>This is a move, not a rewrite.</strong> The expression below is the one
 * {@link PaymentOutcomes} held inline before the seam existed, character for character in its
 * arithmetic — because Phase 5's path staying byte-identical is an acceptance criterion of the
 * task that introduced the seam, and the cheapest way to keep a promise like that is not to
 * retype the thing being promised.
 *
 * <p>It writes nothing and reads nothing. A wallet top-up owes no one anything on the way in:
 * the customer's money becomes the customer's balance, and ADR-0048's boundary is the whole
 * story.
 */
public final class WalletTopUpComposition implements CaptureComposition<Connection> {

    @Override
    public List<JournalLine> settle(Connection unitOfWork, CaptureSettlement settlement) {
        return List.of(
                new JournalLine(settlement.clearing(), Direction.DEBIT, settlement.captured()),
                new JournalLine(settlement.credit(), Direction.CREDIT, settlement.captured()));
    }

    /**
     * Nothing. A wallet top-up's consequence <em>is</em> the posting — the customer's money
     * became the customer's balance, and there is no commercial fact beside it to record.
     *
     * <p>An empty implementation rather than an absent one, because the port's second moment
     * is not optional: a composition that needed it and forgot would be a silent omission,
     * while this is a stated one.
     */
    @Override
    public void settled(
            Connection unitOfWork, CaptureSettlement settlement, java.util.UUID entryRef) {
        // Deliberately empty; see the javadoc.
    }
}
