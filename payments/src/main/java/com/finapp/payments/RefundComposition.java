package com.finapp.payments;

import com.finapp.ledger.JournalLine;
import com.finapp.sharedkernel.money.Money;
import java.util.List;

/**
 * Composes the journal lines a completed refund posts (`P6-TSK-014`, ADR-0050 §6) —
 * {@link CaptureComposition}'s mirror, and the seam that was missing when the capture got one.
 *
 * <h2>Why this exists, and why it arrives a task late</h2>
 *
 * <p>`P6-TSK-005` gave the capture a composition seam and left the refund writing its own two
 * lines inline: {@code DR the credit account / CR clearing}. For a merchant-bound payment that
 * returns the gross out of the payable — <strong>the right direction</strong> — but ADR-0050's
 * consequences say the fee follows the schedule's {@code refundFeePolicy}, and nothing read
 * that attribute. It was pinned, versioned and consumed by nobody. That gap was found by
 * `P6-TSK-005`'s own completion gate and is what this port closes.
 *
 * <p>The shape is the capture's, for the capture's reason: the flow that <em>created</em> the
 * intent knows what the money owes on the way back, and {@code payments} must not. A provider
 * adapter stays fee-blind; fee vocabulary never enters this domain model ({@code INV-PAY-03}).
 *
 * <h2>One moment, not two</h2>
 *
 * <p>{@link CaptureComposition} has a second moment, because a checkout <em>order</em>
 * references the journal entry that paid for it and cannot know the identifier until after the
 * posting. A refund's consequences reference the refund, which the composer already has. So
 * there is one method here, and the asymmetry is a fact about what each flow records rather
 * than an inconsistency between two ports.
 *
 * <h2>What an implementation may and may not do</h2>
 *
 * <p>It runs <strong>inside the refund's own transaction</strong>, on the caller's connection,
 * after the conditional {@code complete} has already been won — so it runs exactly once per
 * refund however many resolvers raced, and whatever it writes commits with the money it
 * accompanies or neither exists. It may write: the records that must commit with the posting,
 * and outbox rows announcing what it composed.
 *
 * <p>It must <strong>not</strong> swallow a failure. A composition that cannot price this
 * refund has to throw, because the alternative is returning the gross while the fee is wrong
 * and recording the refund as done. There is no savepoint around it — {@code PaymentRefund}'s
 * recorded stance for the posting itself, inherited rather than re-decided.
 *
 * <p>The lines must balance per currency; {@code PostingService} refuses them otherwise
 * ({@code INV-LED-01}), which is the third rank behind the composer's arithmetic and the test
 * that asserts the entry by {@code DIRECTION:PURPOSE}.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface RefundComposition<T> {

    /**
     * The lines this refund posts.
     *
     * @throws RuntimeException if the refund cannot be composed — which fails the whole refund
     *     transaction, on purpose
     */
    List<JournalLine> settle(T unitOfWork, RefundSettlement settlement);

    /**
     * What this refund must have AVAILABLE on its debit account when it is dispatched
     * (`P6-TSK-015`, ADR-0054) — exactly what the dispatch places its hold for, so the funding
     * bound ({@code INV-BAL-04}) judges what the refund will really take rather than its gross.
     *
     * <h2>Why this is asked of the composition</h2>
     *
     * <p>Phase 5 held the gross, which is what a wallet refund takes. A merchant-bound refund
     * under a {@code RETURNED} policy takes the gross out of the payable and puts the fee share
     * back IN THE SAME ENTRY, so it takes the net — and a bound judging the gross refused a
     * full refund that would have landed the payable at exactly zero. Only the flow that will
     * write the lines knows what they take, and {@code payments} must not learn why.
     *
     * <h2>The contract</h2>
     *
     * <p>It is asked in the dispatch transaction, under the attempt's lock and before the refund
     * exists. The answer must be positive, because a hold is. It must cover whatever part of
     * {@link #settle}'s debit the account's owner has to fund <em>in every completion order
     * still possible</em> — a proportional share can depend on which sibling refunds complete
     * first, and the hold is the only thing standing between the refund and a position below
     * what was available. It MAY be more; the completion releases the difference.
     *
     * <p>Answering less than {@link #settle} will debit is the composing flow EXTENDING CREDIT
     * to the account's owner, and is permitted only where a decision says so: ADR-0054 lets a
     * merchant's payable carry the fee share the platform retained, and nothing more.
     *
     * @throws RuntimeException if the reservation cannot be determined — which fails the
     *     dispatch with nothing written
     */
    Money reserve(T unitOfWork, RefundReservation reservation);
}
