package com.finapp.ledger;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;

/**
 * Available balance for a balance-affecting decision: settled derived from postings minus the
 * standing {@code ACTIVE} holds, both from authoritative rows ({@code INV-BAL-04/-05}).
 *
 * <p><strong>Extracted from {@code HoldService} when its second caller arrived</strong>
 * (`P4-TSK-005`'s transfer execution — the {@code PostingEffect} precedent: a computation copied
 * per command drifts in exactly one of its copies, and "what can this account spend?" must have
 * one answer however many commands ask).
 *
 * <p><strong>The contract is in-lock evaluation.</strong> The caller holds
 * {@code SELECT … FOR UPDATE} on the account row ({@link LedgerAccountStore#lockForUpdate}) when
 * it calls this, so the fresh statements here read snapshots that postdate the lock grant
 * (`P2-TSK-015`): the loser of two racing decisions sees the winner's committed effect, which is
 * the whole protocol (`P3-TSK-015`, ADR-0039). Called without the lock, the answer is exactly
 * the check that passes every sequential test and loses the race — the {@code P3-TST-002} shape
 * — which is why the precondition is the first line of this contract rather than a footnote.
 *
 * <p>Settled comes from {@link BalanceDerivation} — the definition, never the projection — and
 * the holds fold through {@link Money#plus}, never a SQL {@code SUM} (`P3-TSK-008`); the empty
 * fold's zero adopts the settled number's scale (the scale-aware zero identity, `P3-TSK-004`).
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public final class AvailableBalance<T> {

    private final BalanceDerivation<T> derivation;
    private final HoldStore<T> holds;

    public AvailableBalance(BalanceDerivation<T> derivation, HoldStore<T> holds) {
        this.derivation = Objects.requireNonNull(derivation, "derivation must not be null");
        this.holds = Objects.requireNonNull(holds, "holds must not be null");
    }

    /**
     * What {@code accountId} can spend, judged under the caller's held account-row lock (the
     * contract above). Negative is a legal answer — an account can be over-reserved by design
     * decisions elsewhere — and the caller's comparison is the policy.
     */
    public Money underLock(T unitOfWork, LedgerAccountId accountId) {
        Money settled = derivation.derive(unitOfWork, accountId, AsOf.latest()).settled();
        Money standing = Money.ofPersisted(0, settled.currency(), settled.scale());
        for (Hold hold : holds.findActiveFor(unitOfWork, accountId)) {
            standing = standing.plus(hold.amount());
        }
        return settled.minus(standing);
    }
}
