package com.finapp.merchant;

import java.util.Optional;

/**
 * Persistence port for {@link Merchant} (`P6-TSK-003`). A port on ADR-0033's recorded
 * reasoning; the unit of work is the caller's, because onboarding writes the merchant, its
 * payable ledger account, the audit record and the outbox row in one transaction.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface MerchantStore<T> {

    /** Inserts the freshly onboarded merchant. Duplicate-command protection is the caller's
     * idempotency claim, not a store concern: two merchants for one organisation are two
     * legitimate shops (`PHASE_6_PLAN.md` §4 — no one-live index, deliberately, recorded). */
    void insert(T unitOfWork, Merchant merchant);

    /** The merchant, or empty — unknown and malformed are the surface's one 404. */
    Optional<Merchant> findById(T unitOfWork, MerchantId id);

    /**
     * The merchant, locked — the administrative transitions' serialization point
     * ({@code P3-TSK-014}'s closers idiom): read {@code FOR UPDATE}, transition through the
     * aggregate, write conditionally.
     */
    Optional<Merchant> findByIdForUpdate(T unitOfWork, MerchantId id);

    /**
     * Applies {@code transitioned}'s status conditionally ({@code WHERE status = ?} on the
     * from-state) and appends the history row when the write landed. The row count converges
     * the racers: {@code false} means another writer moved the row first, and the caller
     * re-reads rather than assumes ({@code INV-CON-01}).
     */
    boolean transition(T unitOfWork, Merchant before, Merchant transitioned);
}
