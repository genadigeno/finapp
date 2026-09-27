package com.finapp.payments;

import com.finapp.ledger.JournalLine;
import java.util.List;

/**
 * Composes the counterparty's side of a chargeback (`P7-TSK-013`, ADR-0061 §4) — the
 * {@link CaptureComposition} and {@link RefundComposition} pattern at the dispute: the flow that
 * created the payment knows whose money the counterparty's account holds, and {@code payments}
 * must not. {@code payments} still decides everything the network's statement and the combined
 * bound decide — what the rail's clearing moves, how much the counterparty bears, which
 * platform account faces it — and asks this port only for the counterparty's lines.
 *
 * <h2>Why only the counterparty's part</h2>
 *
 * <p>A chargeback posts two entries: the external fact ({@code DR CHARGEBACK_RECOVERABLE / CR}
 * the rail's clearing, what the network took — payments' own knowledge, {@code INV-RAIL-04})
 * and the attribution (the counterparty charged its share out of the recoverable). This port
 * composes the second, and its mirror when the network returns the funds. It is where a
 * merchant-bound payment's lines are checked against the payment's fee pin (the counterparty
 * must BE the pinned merchant's payable), and where a dispute-fee pass-through would land —
 * recorded as out of scope, a fee-schedule extension.
 *
 * <h2>What an implementation may and may not do</h2>
 *
 * <p>It runs <strong>inside the dispute's own transaction</strong>, on the caller's
 * connection, after the stage's conditional transition has been won — so it runs once per stage
 * however many deliveries raced. It must <strong>not</strong> swallow a failure: an
 * attribution it cannot compose throws, failing the delivery's transaction loudly rather than
 * charging a guessed account. The merchant's processing fee is NOT returned by a chargeback
 * (ADR-0061 §4: the merchant loses the sale and keeps the fee it was charged for processing
 * it), so no composer adds fee lines here.
 *
 * <p>The lines must balance per currency; {@code PostingService} refuses them otherwise
 * ({@code INV-LED-01}).
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface DisputeComposition<T> {

    /**
     * The lines charging {@code attribution}'s amount to the counterparty: its account debited,
     * the counterpart credited.
     *
     * @throws RuntimeException if the attribution cannot be composed — which fails the whole
     *     delivery transaction, on purpose
     */
    List<JournalLine> attribute(T unitOfWork, ChargebackAttribution attribution);

    /**
     * The lines returning {@code attribution}'s amount to the counterparty when the network
     * returned the funds: the counterpart debited, the counterparty's account credited — the
     * exact inverse of {@link #attribute}.
     *
     * @throws RuntimeException if the restoration cannot be composed
     */
    List<JournalLine> restore(T unitOfWork, ChargebackAttribution attribution);
}
