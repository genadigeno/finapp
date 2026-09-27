package com.finapp.payments;

import com.finapp.ledger.LedgerAccountId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence for {@link Dispute} (`P7-TSK-012`, over `V020`).
 *
 * <p>Three populations read disputes, and each read says which it serves:
 *
 * <ul>
 *   <li><strong>The notification's resolver</strong> — {@link #insert} and
 *       {@link #findForUpdate}, keyed by the network's own {@code (provider, reference)}, the
 *       signed door's attribution. Unscoped by design: the PSP names a dispute on any payment.
 *   <li><strong>The merchant</strong> — {@link #findForCounterparties} and
 *       {@link #listForCounterparties}: the tenant is the payment's credit account, and it rides
 *       every statement ({@code credit_account_id = ANY (?)}, {@code INV-MER-01}) with the
 *       accounts the caller resolved from the authenticated merchant through the ledger's
 *       owner-scoped read. {@code payments} never learns what a merchant is.
 *   <li><strong>The operator</strong> — {@link #findById} and {@link #listForIntent}, across
 *       tenants by permission, every read audited by the caller.
 * </ul>
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface DisputeStore<T> {

    /** A dispute with the payment its attempt belongs to — what every read surface shows. */
    record Found(Dispute dispute, PaymentIntentId intentId) {}

    /** One move on the dispute's append-only trail. */
    record StageChange(DisputeStage from, DisputeStage to, Instant at) {}

    /**
     * Stores a freshly opened dispute unless its {@code (provider, reference)} already names
     * one — {@code false} then, and the caller locks the standing row instead. The unique key
     * is the arbiter: ten fresh-id deliveries of one opening insert one row, the losers waiting
     * on the index until the winner commits.
     */
    boolean insert(T unitOfWork, Dispute opened);

    /** The dispute the network's reference names, locked for the rest of the transaction. */
    Optional<Dispute> findForUpdate(T unitOfWork, String provider, ProviderReference reference);

    /**
     * Moves the dispute {@code before → after}, conditional on {@code before}'s stage, with
     * its trail row — stamped {@code at}, from the caller's injected clock — in the same
     * transaction. {@code false} when another writer moved it first.
     */
    boolean transition(T unitOfWork, Dispute before, Dispute after, Instant at);

    /** The operator's read of one dispute, across tenants by permission. */
    Optional<Found> findById(T unitOfWork, DisputeId id);

    /** The operator's read of one payment's disputes, oldest first. */
    List<Found> listForIntent(T unitOfWork, PaymentIntentId intent);

    /**
     * The merchant's read of one dispute: found only when the disputed payment credited one of
     * {@code counterparties} — the tenant predicate in the statement, so another tenant's
     * dispute and an unknown one are the same absence.
     */
    Optional<Found> findForCounterparties(
            T unitOfWork, DisputeId id, Set<LedgerAccountId> counterparties);

    /** The merchant's listing: its disputes, newest first, at most {@code limit}. */
    List<Found> listForCounterparties(
            T unitOfWork, Set<LedgerAccountId> counterparties, int limit);

    /** The dispute's trail, oldest first. */
    List<StageChange> historyOf(T unitOfWork, DisputeId id);
}
