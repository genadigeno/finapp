package com.finapp.merchant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link PayoutReturn} (`P8-TSK-019`, ADR-0073 §1): an append-only fact —
 * {@code SELECT} and {@code INSERT} only, for every writer (merchant `V008`).
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface PayoutReturnStore<T> {

    /**
     * Records the return. Called under the payout's row lock, after the posting whose entry it
     * names, so it is uncontended; `V008`'s uniques, composite key and triggers stand beneath.
     */
    void insert(T unitOfWork, PayoutReturn returned);

    /**
     * The payout's return, if one was recorded — read under the payout's row lock by the one
     * applier that may act, so a standing return is a converge, never a second credit.
     */
    Optional<PayoutReturn> findByPayout(T unitOfWork, MerchantPayoutId payout);

    /**
     * Every recorded return, a bounded page at a time, paged by id strictly after
     * {@code after} — the opening-position backfill's walk (ADR-0067 §8, after `P8-TSK-009`'s
     * rule): the register must stay rebuildable from the books alone, and a return's
     * {@code PAYOUT_RETURN} is a completion's copy like any other.
     */
    List<PayoutReturn> page(T unitOfWork, UUID after, int limit);
}
