package com.finapp.ledger;

import com.finapp.sharedkernel.money.Money;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One account's committed lines, totalled by direction and by <strong>how the same entry
 * treated a named counterparty purpose</strong> (`P6-TSK-010`) — a generic read in the
 * ledger's own vocabulary, so that a caller can explain a position without the ledger learning
 * the caller's words.
 *
 * <h2>Why this exists, and what it deliberately does not know</h2>
 *
 * <p>A merchant's payable is one account, and its position alone says nothing about <em>why</em>
 * it is what it is. The drill-down a merchant needs — captured, fees, refunded, fees returned —
 * is a classification of the account's lines by the <em>shape of the entry</em> each belongs to:
 * a capture debits {@code SETTLEMENT_CLEARING}, a refund credits it. That fact is expressible
 * entirely in ledger terms (a direction, a purpose), and this port expresses nothing else. It
 * names no fee and no merchant; the module that composes those entry shapes is the one that
 * reads the buckets back, which is where the meaning belongs.
 *
 * <h2>One statement, so the buckets and the position cannot disagree</h2>
 *
 * <p>Under {@code READ COMMITTED} every statement sees its own snapshot. If the position and the
 * buckets were read separately, a posting committed between them would make the buckets fail to
 * sum to the position — silently, and only under traffic, which is the hardest way for a figure
 * to be wrong. So every line is read by ONE statement, and the position is folded from the same
 * buckets it is reported beside.
 *
 * <h2>Folded through {@link Money}, never a SQL {@code SUM}</h2>
 *
 * <p>{@link BalanceDerivation}'s recorded rule, and this is the same definition read a second way:
 * a SQL aggregate is a second implementation of monetary arithmetic outside the kernel, adding
 * minor units across scales ({@code INV-MON-03}) and widening past {@code long}
 * ({@code INV-MON-06}) where {@code Money} refuses. The lines stream and fold, and an
 * underivable history refuses loudly.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface PositionBreakdown<T> {

    /**
     * The total of the account's lines in one direction, from entries that treated the
     * counterparty purpose one way — or did not touch it at all ({@code counterparty} empty).
     */
    record Bucket(Direction direction, Optional<Direction> counterparty, Money total) {

        public Bucket {
            Objects.requireNonNull(direction, "direction must not be null");
            Objects.requireNonNull(counterparty, "counterparty must not be null");
            Objects.requireNonNull(total, "total must not be null");
        }
    }

    /**
     * The account's position and the buckets it is made of, from one snapshot.
     *
     * @param position the settled balance, signed by the account's normal balance — the same
     *     convention as {@link DerivedBalance}, stated once in {@link BalanceDerivation#settle}
     * @param buckets every non-empty bucket; their signed sum IS the position, by construction
     */
    record Breakdown(LedgerAccountId account, Money position, List<Bucket> buckets) {

        public Breakdown {
            Objects.requireNonNull(account, "account must not be null");
            Objects.requireNonNull(position, "position must not be null");
            buckets = List.copyOf(Objects.requireNonNull(buckets, "buckets must not be null"));
        }
    }

    /**
     * Breaks {@code account}'s committed position down by how each line's entry treated
     * {@code counterparty}.
     *
     * @throws UnderivableBalanceException if the account does not exist or its history cannot
     *     be folded — the derivation's own regime: an underivable history refuses, never renders
     */
    Breakdown breakdown(T unitOfWork, LedgerAccountId account, AccountPurpose counterparty);
}
