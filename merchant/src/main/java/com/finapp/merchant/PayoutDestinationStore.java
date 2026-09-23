package com.finapp.merchant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@link PayoutDestination} (`P6-TSK-011`, ADR-0033's recorded reasoning).
 * The unit of work is the caller's: a proposal commits with its idempotency claim and audit
 * record, and an effectuation commits the supersession, the effect, both history rows and the
 * audit record together.
 *
 * <p><strong>Every read that names a destination names its merchant too</strong>
 * ({@code INV-MER-01}): the pairing is judged in the statement, so another merchant's
 * destination is indistinguishable from an unknown one.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface PayoutDestinationStore<T> {

    /**
     * Inserts a freshly proposed destination.
     *
     * @throws PayoutDestinationChangePendingException the merchant already has an open change —
     *     `V006`'s one-open index arbitrated the race, and the caller's transaction is left usable
     */
    void insert(T unitOfWork, PayoutDestination proposed);

    /** The merchant's destination by identifier, or empty — unknown and foreign alike. */
    Optional<PayoutDestination> find(T unitOfWork, MerchantId merchant, PayoutDestinationId id);

    /**
     * The same, locked — the decisions' serialization point: read {@code FOR UPDATE}, judge
     * through the aggregate, write conditionally (the {@code P2-TSK-015} lock-then-look).
     */
    Optional<PayoutDestination> findForUpdate(
            T unitOfWork, MerchantId merchant, PayoutDestinationId id);

    /** The merchant's open change — proposed, or approved and cooling off — if it has one. */
    Optional<PayoutDestination> findOpen(T unitOfWork, MerchantId merchant);

    /** The merchant's effective destination, if it has one: what a payout dispatch pays to. */
    Optional<PayoutDestination> findEffective(T unitOfWork, MerchantId merchant);

    /** The same, locked — the supersession's second lock, taken after the approved row's. */
    Optional<PayoutDestination> findEffectiveForUpdate(T unitOfWork, MerchantId merchant);

    /**
     * Approved changes whose cooling-off has elapsed at {@code now}, oldest deadline first, at
     * most {@code limit} — the effectuation sweep's candidates. A candidate is a hint, never a
     * judgement: the sweep re-reads it locked and re-judges it against the clock.
     */
    List<PayoutDestination> findDue(T unitOfWork, Instant now, int limit);

    /**
     * Applies {@code after}'s lifecycle columns conditionally — {@code WHERE status = ?} on the
     * from-state, and for the approval edge also {@code proposed_by <> ?} in the statement
     * ({@code INV-AUD-04}) — and appends the history row when the write landed. {@code false}
     * means another writer moved the row first; the caller re-reads rather than assumes
     * ({@code INV-CON-01}).
     */
    boolean transition(T unitOfWork, PayoutDestination before, PayoutDestination after);

    /** Open changes across the fleet — the pending gauge's subject. */
    long countOpen(T unitOfWork);
}
