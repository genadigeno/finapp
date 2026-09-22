package com.finapp.checkout;

import java.util.Optional;

/**
 * Persistence port for {@link Order} (`P6-TSK-006`). The unit of work is the caller's: the order
 * is born in the completion's own transaction, with the session's move to {@code COMPLETED}.
 *
 * <p><strong>Insert and read, and there will never be a third kind.</strong> An order is
 * evidence; an {@code update} here would be a rewritten commercial fact, and the application
 * role holds no grant for one. The absent methods are the cheapest of the three enforcements —
 * `V002`'s trigger and the withheld grant are the other two.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface OrderStore<T> {

    /**
     * Records the fact.
     *
     * @throws CheckoutStorageException if this session already has an order — a second
     *     commercial fact from one purchase, refused by the unique index rather than by a
     *     read-then-write, so ten concurrent completions cannot both win
     */
    void insert(T unitOfWork, Order order);

    /** The order, or empty. */
    Optional<Order> findById(T unitOfWork, OrderId id);

    /**
     * The order a session produced, or empty when the session died unpaid — which is how an
     * expired or abandoned checkout looks from here, and why {@code Optional} rather than a
     * refusal. The platform does not manufacture commercial facts out of silence.
     */
    Optional<Order> findBySession(T unitOfWork, CheckoutSessionId sessionRef);
}
