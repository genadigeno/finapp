package com.finapp.ledger;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for the chart of accounts (`P3-TSK-002`).
 *
 * <p>A port, on ADR-0033's recorded reasoning: the aggregate must not depend on a {@code Jdbc}
 * class, and the unit of work is the caller's — `P3-TSK-012` opens the product and its ledger
 * accounts in <strong>one</strong> transaction, which is only possible because every write here
 * joins the transaction of the operation performing it.
 *
 * <p><strong>Deliberately three methods.</strong> No {@code create} for operational accounts (the
 * seed migration is their one writer, `P3-TSK-003`), no status move (`P3-TSK-014`'s close is the
 * first act that needs one and its design decides the conditional), no {@code findById}
 * (`P3-TSK-005`'s postings are its first caller) — a read with no caller is dead code carrying
 * confident javadoc, the {@code P1-TSK-013} finding.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface LedgerAccountStore<T> {

    /**
     * The result of asking for an owned account to exist: the account, and whether this call
     * created it. The {@code KycCaseStore.Opening} shape — convergence is never an error.
     */
    record Creation(LedgerAccount account, boolean created) {}

    /**
     * Inserts {@code fresh}, or converges on the owner's existing account of the same purpose
     * and currency.
     *
     * <p><strong>The partial unique index is the arbiter</strong>
     * ({@code ledger_account_one_per_owner_purpose_currency}): "the customer account's GBP
     * wallet account" is one thing however many instances ask for it, so ten concurrent creates
     * produce one row and nine callers handed the winner's — a retry of "ensure the account
     * exists" is one intent (`P2-TSK-005`'s converge-don't-race, on the backlog's own answer).
     * Behind a savepoint, because the unique violation aborts the transaction and the caller's
     * other writes — the product row, the audit record — must survive the lost race; a
     * pre-flight {@code SELECT} is not a substitute ({@code P1-TSK-006}).
     *
     * <p>Owned accounts only: {@code fresh} must carry an owner, which the aggregate's one
     * factory guarantees. The operational chart is seeded by migration and has no code path.
     */
    Creation createOrConverge(T unitOfWork, LedgerAccount fresh);

    /**
     * The owner's account for one purpose in one currency, if it exists — the converge read,
     * and the read `P3-TSK-012`'s balance query starts from. The predicate is the partial
     * index's own, so "the owned account" and "the row the index guards" are one question.
     */
    Optional<LedgerAccount> findOwned(
            T unitOfWork, UUID ownerRef, AccountPurpose purpose, CurrencyCode currency);

    /**
     * The platform's own account for one purpose in one currency, if seeded — the read behind
     * {@link ChartOfAccounts#resolve} (`P3-TSK-003`). The predicate is the operational partial
     * index's own ({@code owner_ref IS NULL}), so "the operational account" and "the row the
     * index guards" are one question. Absence is a seed defect; {@code ChartOfAccounts} is
     * where that becomes loud, which is why this stays an {@code Optional} and that does not.
     */
    Optional<LedgerAccount> findOperational(
            T unitOfWork, AccountPurpose purpose, CurrencyCode currency);
}
