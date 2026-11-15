package com.finapp.ledger;

import com.finapp.ledger.PositionBreakdown.Breakdown;
import com.finapp.ledger.PositionBreakdown.Bucket;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fold a {@link PositionBreakdown} is made of, stated once (`P16-REM-001`): every line added to the bucket its key names, and to
 * its side's sum, both through {@link Money}.
 *
 * <p>A line and a stored bucket total are added the same way, which is what lets a commit-order checkpoint stand in for the lines below
 * its watermark: each bucket is a sum of positive amounts ({@code journal_line_amount_is_positive}), so the fold of a checkpoint's
 * totals and the lines above it is the fold of every line - the same buckets, the same sides, the same refusals at the same totals
 * ({@code INV-MON-03}, {@code INV-MON-06}). {@code BucketFoldSplitTest} holds that property; {@link JdbcPositionBreakdown} folds the
 * whole history with it, and {@link CheckpointedPositionBreakdown} a checkpoint and the lines since.
 */
final class BucketFold {

    private final Map<String, Bucket> buckets = new LinkedHashMap<>();
    private Money debits;
    private Money credits;

    BucketFold(CurrencyCode currency) {
        this.debits = Money.zero(currency);
        this.credits = Money.zero(currency);
    }

    /**
     * Folds one line's bucket, or a stored bucket's total.
     *
     * @throws com.finapp.sharedkernel.money.ScaleMismatchException if its scale is not the history's
     * @throws com.finapp.sharedkernel.money.MonetaryOverflowException if a sum leaves the representable range
     */
    void add(Bucket line) {
        Money amount = line.total();
        if (line.direction() == Direction.DEBIT) {
            debits = JournalEntry.sum(debits, amount);
        } else {
            credits = JournalEntry.sum(credits, amount);
        }
        String key = key(line);
        Bucket so = buckets.get(key);
        buckets.put(
                key,
                so == null
                        ? line
                        : new Bucket(
                                line.direction(),
                                line.counterparty(),
                                JournalEntry.sum(so.total(), amount),
                                line.reconciliationAttributed(),
                                line.namespace(),
                                line.accountingOrigin()));
    }

    /** The bucket a line belongs to: its direction, its namespace, and its origin or what it faces. */
    static String key(Bucket line) {
        return line.direction() + "|"
                + line.namespace().orElse("") + "|"
                + (line.reconciliationAttributed()
                        ? "RECONCILIATION"
                        : line.accountingOrigin()
                                ? "ACCOUNTING"
                                : line.counterparty().map(c -> c.purpose() + ":" + c.direction()).orElse("NONE"));
    }

    /** Every non-empty bucket, keyed as {@link #key} keys it. */
    Map<String, Bucket> buckets() {
        return Map.copyOf(buckets);
    }

    /**
     * The position and its buckets: the position settled from the same sides the buckets were folded into, so the buckets sum to it by
     * construction.
     *
     * @throws com.finapp.sharedkernel.money.ScaleMismatchException if the two sides were summed at different scales
     */
    Breakdown breakdown(LedgerAccountId account, NormalBalance normalBalance) {
        List<Bucket> folded = new ArrayList<>(buckets.values());
        return new Breakdown(account, BalanceDerivation.settle(normalBalance, debits, credits), folded);
    }
}
