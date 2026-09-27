package com.finapp.payments;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The book rail's declaration (`P7-TSK-011`, ADR-0059 §6): the platform paying itself on its
 * own ledger. There is no adapter class to carry this descriptor because there is no wire —
 * no third party ever decides anything about a book payment — so the declaration stands
 * alone, and everything the shape can say, it says: {@code FINAL_ON_POSTING} (nobody else to
 * decide), no reversals of any kind, {@code BOOK_REFUND} (a compensating movement, one
 * transaction), {@code SettlementModel.NONE} with no clearing position ({@code INV-SET-01}
 * holds because nothing external ever settles — the {@link RailCapabilities} coherence rules
 * force this exact combination), no outcome deadline (there is never an outcome to wait
 * for), no disputes. No currency restriction and no ceiling: the wallet's own currency and
 * balance are the judge, under the account's lock ({@code INV-BAL-04}).
 *
 * <p>Declaration is code; liveness is routing's database fact. Nothing routes here until
 * `V019` seeds the {@code PAY_IN + WALLET} rule.
 */
public final class BookRail {

    /** The rail's name — confined to this declaration and the composition root's binding. */
    public static final PaymentRail RAIL =
            new PaymentRail(
                    RailId.of("book"),
                    1,
                    new RailCapabilities(
                            InteractionModel.BOOK,
                            RailCapabilities.Finality.FINAL_ON_POSTING,
                            Set.of(),
                            RailCapabilities.RefundMode.BOOK_REFUND,
                            RailCapabilities.SettlementModel.NONE,
                            Optional.empty(),
                            RailCapabilities.DisputeModel.NONE,
                            Optional.empty(),
                            Map.of(),
                            Optional.empty()));

    private BookRail() {}
}
