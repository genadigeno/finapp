package com.finapp.ledger;

import com.finapp.sharedkernel.money.Money;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The reversal arithmetic (`P3-TSK-016`, {@code INV-REV-02}), stated once and hermetically
 * testable: per {@code (account, direction)} pair, the requested reversal plus every prior
 * reversal never exceeds the original's total at the <em>opposite</em> direction.
 *
 * <p><strong>The domain half of a two-layer bound.</strong> This check reads committed prior
 * reversals and therefore races a concurrent one — deliberately unfixed here, because the
 * race's arbiter is `V009`'s trigger under the advisory lock on the original's identity,
 * which serializes every writer and re-judges what the winner committed. The two layers are
 * blind in different directions: this one refuses a bad command deterministically before any
 * idempotency claim is consumed and gives the hermetic tests their subject; the trigger
 * binds the writers the domain never sees and the racers this check cannot.
 *
 * <p>Sums fold through {@link Money#plus} — never a SQL {@code SUM} in the domain
 * (`P3-TSK-008`); a scale conflict between a prior reversal and the original refuses loudly
 * through {@code Money}'s own arithmetic rather than being summed across.
 */
final class ReversalBound {

    /** One pair of the bound: an account and the side the <em>reversal</em> posts on. */
    record Pair(LedgerAccountId account, Direction direction) {}

    private ReversalBound() {}

    /**
     * Refuses the requested reversal lines unless every pair mirrors the original and fits
     * what remains un-reversed.
     *
     * @param original the original entry's identifier, for messages only
     * @param originalLines the original's lines
     * @param priorReversalLines every committed reversal line already referencing the
     *     original (their direction is already the reversal's own side)
     * @param requestedLines the reversal being asked for
     * @throws OverReversalException a pair over-reverses ({@code INV-REV-02}) or mirrors no
     *     original line — amount-free, the message names the entry and the account
     */
    static void validate(
            JournalEntryId original,
            List<JournalLine> originalLines,
            List<JournalLine> priorReversalLines,
            List<JournalLine> requestedLines) {
        Objects.requireNonNull(original, "original must not be null");
        Map<Pair, Money> originalTotals = totals(originalLines);
        Map<Pair, Money> priorTotals = totals(priorReversalLines);
        Map<Pair, Money> requestedTotals = totals(requestedLines);

        for (Map.Entry<Pair, Money> requested : requestedTotals.entrySet()) {
            Pair reversalPair = requested.getKey();
            Pair originalPair =
                    new Pair(reversalPair.account(), reversalPair.direction().opposite());
            Money originalTotal = originalTotals.get(originalPair);
            if (originalTotal == null) {
                throw new OverReversalException(
                        "a reversal of entry " + original + " posts to account "
                                + reversalPair.account() + " at " + reversalPair.direction()
                                + ", and the original has no line at the opposite side -"
                                + " a reversal's lines are the original's with directions"
                                + " swapped (INV-REV-01)");
            }
            Money prior =
                    priorTotals.getOrDefault(
                            reversalPair,
                            Money.ofPersisted(
                                    0, originalTotal.currency(), originalTotal.scale()));
            // Money.plus/minus refuse cross-currency and cross-scale mixes loudly - a prior
            // reversal at a foreign scale must never be summed across (INV-MON-03/04).
            Money remaining = originalTotal.minus(prior);
            if (remaining.minus(requested.getValue()).isNegative()) {
                throw new OverReversalException(
                        "entry " + original + " would be over-reversed on account "
                                + reversalPair.account()
                                + ": the requested reversal exceeds what remains un-reversed"
                                + " (INV-REV-02)");
            }
        }
    }

    private static Map<Pair, Money> totals(List<JournalLine> lines) {
        Objects.requireNonNull(lines, "lines must not be null");
        Map<Pair, Money> totals = new HashMap<>();
        for (JournalLine line : lines) {
            totals.merge(
                    new Pair(line.account(), line.direction()), line.amount(), Money::plus);
        }
        return totals;
    }
}
