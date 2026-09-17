package com.finapp.accounts;

import java.util.Optional;
import java.util.UUID;

/**
 * Storage for the customer account product (`P3-TSK-012`).
 *
 * <p>A port, on ADR-0033's recorded reasoning; the unit of work is the caller's, because opening
 * writes the product, its ledger account, the audit record and the outbox row in
 * <strong>one</strong> transaction.
 *
 * <p><strong>No status move, deliberately</strong> — `P3-TSK-014`'s close is the first act that
 * needs one and its design decides the conditional (the {@code LedgerAccountStore} precedent).
 * And no bare {@code findById}: the read by identifier is {@link #findOwnedBy}, which carries the
 * owner in its {@code WHERE} clause — `P3-TSK-013`'s ownership design, taken when the first
 * caller arrived rather than guessed before it ({@code P1-TSK-013}).
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface CustomerAccountStore<T> {

    /**
     * The result of asking for the product to exist: the account, and whether this call created
     * it — the {@code LedgerAccountStore.Creation} shape; convergence is never an error.
     */
    record Creation(CustomerAccount account, boolean created) {}

    /**
     * Inserts {@code fresh}, or converges on the customer's existing live account of the same
     * product type.
     *
     * <p><strong>The partial unique index is the arbiter</strong>
     * ({@code customer_account_one_live_per_customer_product}): "this customer's wallet" is one
     * agreement however many instances ask for it, so ten concurrent opens produce one row and
     * nine callers handed the winner's. Behind a savepoint, because the unique violation aborts
     * the transaction and the caller's other writes must survive the lost race; a pre-flight
     * {@code SELECT} is not a substitute ({@code P1-TSK-006}).
     */
    Creation openOrConverge(T unitOfWork, CustomerAccount fresh);

    /**
     * The customer's live (non-terminal) account of one product type, if it exists — the
     * converge read; the predicate is the partial index's own, so "the live account" and "the
     * row the index guards" are one question. A closed account is invisible here: closure frees
     * the slot, and a successor agreement is a new aggregate ({@code INV-LIFE-04}).
     */
    Optional<CustomerAccount> findLive(T unitOfWork, UUID customerId, ProductType productType);

    /**
     * Every agreement the customer holds or has held, oldest first (`P3-TSK-013`).
     *
     * <p><strong>Every status, deliberately</strong>: a closed agreement is still the caller's
     * history — `P3-TSK-014`'s close must be visible on the very list that showed the account
     * live, and M3.7's statements read over products whose lifecycle has ended. The customer
     * identifier is session-derived by every caller (resolved from the party, never a request's),
     * which is why it stays a raw {@code UUID}.
     */
    java.util.List<CustomerAccount> findAllFor(T unitOfWork, UUID customerId);

    /**
     * The account, if — and only if — it belongs to {@code customerId} (`P3-TSK-013`).
     *
     * <p><strong>The ownership check is the {@code WHERE} clause</strong> (ADR-0031, the
     * {@code P1-TSK-016} shape): {@code id = ? AND customer_id = ?} in one statement against
     * authoritative state, never a load-then-compare — a compare-then-act is a TOCTOU race, and
     * a method that took only the id would be satisfied just as well by an identifier read out
     * of a request, which is the defect itself. Not-yours and does-not-exist are one empty
     * answer, so the surface can make them one {@code 404} and never an oracle over other
     * people's accounts ({@code INV-IDN-07}'s reasoning).
     */
    Optional<CustomerAccount> findOwnedBy(
            T unitOfWork, CustomerAccountId accountId, UUID customerId);
}
