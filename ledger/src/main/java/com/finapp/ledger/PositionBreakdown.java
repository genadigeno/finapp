package com.finapp.ledger;

import com.finapp.sharedkernel.money.Money;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One account's committed lines, totalled by direction and by <strong>how the same entry
 * treated named counterparty purposes</strong> (`P6-TSK-010`; a precedence list since
 * `P6-TSK-012`, when a payout's {@code PAYOUT_CLEARING} joined a capture's and a refund's
 * {@code SETTLEMENT_CLEARING}) — a generic read in the
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
     * How a line's entry touched one of the named counterparty purposes: which purpose, and in
     * which direction (`P6-TSK-012` widened the read from one purpose to a precedence list, so
     * a payable can tell a payout's {@code PAYOUT_CLEARING} from a capture's
     * {@code SETTLEMENT_CLEARING} in the same snapshot).
     */
    record Counterparty(AccountPurpose purpose, Direction direction) {

        public Counterparty {
            Objects.requireNonNull(purpose, "purpose must not be null");
            Objects.requireNonNull(direction, "direction must not be null");
        }
    }

    /**
     * The total of the account's lines in one direction, from entries that touched a named
     * counterparty purpose one way — or touched none of them ({@code counterparty} empty).
     *
     * <p>{@code reconciliationAttributed} (`P8-TSK-015`, ADR-0071 §2, ADR-0073 §6's origin
     * rule): the lines belong to an {@code ADJUSTMENT} entry whose proposal is of origin
     * {@code RECONCILIATION} — an approved break resolution's attribution. Classified FIRST,
     * whatever the entry faces, so its counterparty is empty: a transfer of an OUTBOUND clearing
     * remainder faces the clearing and would otherwise read as a capture.
     *
     * <p>{@code namespace} (`P12-TSK-016`, the origin rule's sibling): the lines belong to a
     * {@code POSTING} whose command's idempotency key begins with one of the posting-key
     * namespaces the caller named - the first in the caller's order. Reported BESIDE the
     * counterparty, never instead of it: the ledger states both facts in its own vocabulary (a
     * key it recorded; a purpose and a direction) and the caller, which composed the entry
     * shapes, decides which one explains the line. A merchant's BNPL refund credits the
     * customer's wallet exactly as a wallet-paid capture's refund does, so only the key can tell
     * the two apart.
     */
    record Bucket(
            Direction direction,
            Optional<Counterparty> counterparty,
            Money total,
            boolean reconciliationAttributed,
            Optional<String> namespace) {

        public Bucket {
            Objects.requireNonNull(direction, "direction must not be null");
            Objects.requireNonNull(counterparty, "counterparty must not be null");
            Objects.requireNonNull(total, "total must not be null");
            Objects.requireNonNull(namespace, "namespace must not be null");
            if (reconciliationAttributed && counterparty.isPresent()) {
                throw new IllegalArgumentException(
                        "an attribution is classified by its origin, never by its counterparty");
            }
            if (reconciliationAttributed && namespace.isPresent()) {
                throw new IllegalArgumentException(
                        "an attribution is an ADJUSTMENT, never a posting under a namespace");
            }
        }

        /** A bucket no posting-key namespace was asked of - the shape before `P12-TSK-016`. */
        public Bucket(
                Direction direction,
                Optional<Counterparty> counterparty,
                Money total,
                boolean reconciliationAttributed) {
            this(direction, counterparty, total, reconciliationAttributed, Optional.empty());
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
     * Breaks {@code account}'s committed position down by how each line's entry treated the
     * named counterparty purposes. A line is labelled with the <strong>first</strong> purpose in
     * {@code precedence} that its entry touched — an order the caller states, so the answer is
     * deterministic even for an entry touching several — and every line is folded exactly once
     * whatever its label, so the precedence can move a line between buckets but never change
     * the position.
     *
     * @param precedence the counterparty purposes, most significant first; non-empty, distinct
     * @throws UnderivableBalanceException if the account does not exist or its history cannot
     *     be folded — the derivation's own regime: an underivable history refuses, never renders
     */
    default Breakdown breakdown(
            T unitOfWork, LedgerAccountId account, List<AccountPurpose> precedence) {
        return breakdown(unitOfWork, account, precedence, List.of());
    }

    /**
     * {@link #breakdown(Object, LedgerAccountId, List)}, each line also labelled with the first
     * of {@code namespaces} its entry's posting key begins with (`P12-TSK-016`). A namespace is
     * a key prefix as the posting command carried it ({@code "bnpl.financing:"}), matched only
     * against entries {@link PostingService} wrote - a reversal and an adjustment are keyed by
     * their own services and never carry a poster's namespace. Every line is still folded
     * exactly once, so the namespaces, like the precedence, can move a line between buckets but
     * never change the position.
     *
     * @param namespaces posting-key prefixes, most significant first; distinct and non-blank,
     *     empty asking for none
     */
    Breakdown breakdown(
            T unitOfWork,
            LedgerAccountId account,
            List<AccountPurpose> precedence,
            List<String> namespaces);

    /**
     * The guard every implementation applies to {@code namespaces}: distinct, non-blank.
     *
     * @return the namespaces, copied
     */
    static List<String> checkedNamespaces(List<String> namespaces) {
        Objects.requireNonNull(namespaces, "namespaces must not be null");
        List<String> copied = List.copyOf(namespaces);
        if (copied.stream().anyMatch(String::isBlank) || java.util.Set.copyOf(copied).size() != copied.size()) {
            throw new IllegalArgumentException(
                    "the posting-key namespaces must be distinct and non-blank: " + copied);
        }
        return copied;
    }
}
