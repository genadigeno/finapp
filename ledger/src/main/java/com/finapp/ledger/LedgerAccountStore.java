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
 * (`P3-TSK-006`'s command validates the account it posts to, and is its first caller —
 * `P3-TSK-005`'s lines reference accounts by foreign key, no Java read needed) — a read with no caller is dead code carrying
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

    /**
     * Every ledger account owned by {@code ownerRef}, read {@code FOR UPDATE} in a fixed order
     * (`P3-TSK-014`).
     *
     * <p><strong>The lock mode is the design.</strong> Every in-flight posting already holds
     * {@code FOR KEY SHARE} on its accounts' rows — the {@code journal_line} FK takes it, and
     * `V007`'s trigger read takes it explicitly — and {@code FOR UPDATE} is the mode that
     * conflicts with it. A plain status {@code UPDATE} would take {@code FOR NO KEY UPDATE},
     * which does <em>not</em> conflict, and the close would race the posting it must exclude.
     * So the closer locks here first, then looks (`P2-TSK-015`): the balance it derives in a
     * fresh statement includes every posting that won the lock race, and every posting that
     * lost re-judges the status the close actually left. Ordered by id — the fixed-order rule
     * that keeps two multi-account closers from deadlocking (the `P3-TSK-009` precedent).
     */
    java.util.List<LedgerAccount> lockOwnedForUpdate(T unitOfWork, UUID ownerRef);

    /**
     * One account, read {@code FOR UPDATE} by its own identifier — the balance-affecting
     * decision's serialization point (`P3-TSK-015`, ADR-0039): a hold locks here, then
     * derives from postings and standing holds in fresh statements, then acts. Same lock
     * mode, same reasoning as {@link #lockOwnedForUpdate} — {@code FOR UPDATE} is the mode
     * that conflicts with every in-flight posting's {@code FOR KEY SHARE} and with every
     * sibling decision on the same account.
     */
    Optional<LedgerAccount> lockForUpdate(T unitOfWork, LedgerAccountId accountId);

    /**
     * Moves an account {@code from} one status {@code to} another — the conditional whose row
     * count is the outcome, arriving with its first caller exactly as this interface's javadoc
     * deferred it (`P3-TSK-014`'s close). The machine's edge is in the statement
     * ({@code WHERE status = from}); the caller holds the row lock, so a zero here is an
     * invariant already broken and must be loud, never converged.
     */
    boolean moveStatus(
            T unitOfWork,
            LedgerAccountId accountId,
            LedgerAccountStatus from,
            LedgerAccountStatus to,
            java.time.Instant at);
}
