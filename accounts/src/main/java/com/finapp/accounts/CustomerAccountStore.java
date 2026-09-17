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
 * <p><strong>Deliberately two methods.</strong> No status move — `P3-TSK-014`'s close is the
 * first act that needs one and its design decides the conditional (the {@code LedgerAccountStore}
 * precedent); no {@code findById} — `P3-TSK-013`'s ownership design decides what a read by
 * identifier must carry in its {@code WHERE} clause, and a read with no caller is dead code
 * carrying confident javadoc ({@code P1-TSK-013}).
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
}
