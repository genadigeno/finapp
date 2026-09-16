package com.finapp.ledger;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;

/**
 * The authoritative settled balance, computed from posted lines and nothing else
 * (`P3-TSK-008`, {@code INV-BAL-01}, {@code INV-BAL-02}).
 *
 * <h2>This is the definition, not a read model</h2>
 *
 * <p>Everything else is checked <em>against</em> this: the transactional projection
 * ({@code P3-TSK-009}) must equal it, the verification job ({@code P3-TSK-010}) recomputes it,
 * and a balance-dependent decision derives through it inside the account lock
 * ({@code P3-TSK-015}, ADR-0039). That is why the sums fold through {@link Money} rather than
 * through a SQL {@code SUM}: an aggregate computed in SQL is a second implementation of
 * monetary arithmetic outside the kernel — it would silently add minor units across scales
 * (the implicit rescale {@code INV-MON-03} forbids) and silently widen past {@code long} where
 * {@code Money} refuses ({@code INV-MON-06}). Folding through the kernel makes those refusals
 * structural. The cost — streaming every line — is the honest cost of replay-from-zero; the
 * fast path is the projection, by design (ADR-0041).
 *
 * <h2>Where {@code Direction} meets {@code NormalBalance}</h2>
 *
 * <p>A debit is not "money in" ({@code Direction}'s own javadoc has said so since
 * `P3-TSK-004`); the two vocabularies meet at {@link #settle}, the one statement of the sign
 * convention.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface BalanceDerivation<T> {

    /**
     * The settled balance of {@code account} at {@code asOf}, derived from its committed lines.
     *
     * <p>An account with no postings in range is zero <em>in its own currency</em>, never a
     * bare {@code 0} ({@code INV-MON-02}).
     *
     * @throws UnderivableBalanceException if the account does not exist, its history mixes
     *     scales within its currency, a line's currency is foreign to the account's, or a side
     *     sum leaves the representable range — a wrong number returned quietly being the one
     *     unacceptable outcome for the definition
     */
    DerivedBalance derive(T unitOfWork, LedgerAccountId account, AsOf asOf);

    /**
     * The sign convention, stated once: the settled balance is the sum of lines on the
     * account's normal side minus the sum on the opposite side.
     *
     * <p>Positive means the account has grown in its own terms; negative is a legal state
     * (see {@link DerivedBalance}). Both operands must be in one currency at one scale —
     * except that <strong>a zero side adopts the other's scale</strong>: a sum with no lines
     * has no scale of its own, only the artefact of {@code Money.zero}'s current default, and
     * an account whose whole history sits on one side at a persisted scale
     * ({@code INV-MON-05}) is not a mixed-scale history. The scale-aware zero identity
     * (`P3-TSK-004`'s recorded property), applied at the subtraction. Anything else
     * {@link Money#minus} refuses.
     */
    static Money settle(NormalBalance normalBalance, Money debits, Money credits) {
        Objects.requireNonNull(normalBalance, "normalBalance must not be null");
        Objects.requireNonNull(debits, "debits must not be null");
        Objects.requireNonNull(credits, "credits must not be null");
        Money minuend = normalBalance == NormalBalance.DEBIT ? debits : credits;
        Money subtrahend = normalBalance == NormalBalance.DEBIT ? credits : debits;
        return adoptScaleIfZero(minuend, subtrahend)
                .minus(adoptScaleIfZero(subtrahend, minuend));
    }

    private static Money adoptScaleIfZero(Money side, Money other) {
        return side.isZero() && side.scale() != other.scale()
                ? Money.ofPersisted(0, side.currency(), other.scale())
                : side;
    }
}
