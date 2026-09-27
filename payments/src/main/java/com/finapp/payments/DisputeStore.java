package com.finapp.payments;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence for {@link Dispute} (`P7-TSK-012`, over `V020`; the attribution and the fee over
 * `V021`, `P7-TSK-013`).
 *
 * <p>Four populations read disputes, and each read says which it serves:
 *
 * <ul>
 *   <li><strong>The notification's resolver</strong> — {@link #insert} and
 *       {@link #findForUpdate}, keyed by the network's own {@code (provider, reference)}, the
 *       signed door's attribution. Unscoped by design: the PSP names a dispute on any payment.
 *   <li><strong>The combined bound</strong> — {@link #attributedStanding} and
 *       {@link #standingOn}, keyed by the contested attempt and valid only under that attempt's
 *       row lock (the lock-then-look contract both money paths keep, `INV-DSP-01`).
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
     * transaction. The chargeback, its attribution and the fee ride the statement: `V021` lets
     * each move only as the aggregate does. {@code false} when another writer moved it first.
     */
    boolean transition(T unitOfWork, Dispute before, Dispute after, Instant at);

    /**
     * Writes {@code after}'s attribution — a re-attribution of {@code before}'s excess
     * (`P7-TSK-013`, ADR-0061 §3) — conditional on the stage and on the shares {@code before}
     * read: {@code false} when either moved since. No trail row: the stage did not move; the
     * caller audits the act and posts its entry.
     */
    boolean reattribute(T unitOfWork, Dispute before, Dispute after);

    /**
     * Records {@code after}'s dispute fee, conditional on none being recorded yet — the fee
     * moves only {@code NULL → value} (`V021`). {@code false} when a fee was recorded first.
     */
    boolean recordFee(T unitOfWork, Dispute before, Dispute after);

    /**
     * Records {@code after}'s respond-by deadline, conditional on none being recorded yet — the
     * deadline moves only {@code NULL → value} (`V022`, `P7-TSK-014`). {@code false} when one was
     * recorded first.
     */
    boolean recordRespondBy(T unitOfWork, Dispute before, Dispute after);

    /**
     * The dispute, LOCKED for a responder acting across tenants by permission — the operator
     * (`P7-TSK-014`). The caller holds the dispute's attempt lock FIRST: every writer that locks a
     * dispute row takes its attempt before it, the order every delivery keeps.
     */
    Optional<Found> lockForResponder(T unitOfWork, DisputeId id);

    /**
     * The dispute, LOCKED for a counterparty's responder: found only when the disputed payment
     * credited one of {@code counterparties} — the tenant predicate IN THE LOCKING STATEMENT
     * ({@code INV-MER-01}). The attempt-first rule of {@link #lockForResponder} holds.
     */
    Optional<Found> lockForCounterparties(
            T unitOfWork, DisputeId id, Set<LedgerAccountId> counterparties);

    /**
     * How many chargebacks still await an answer the PSP took, with a recorded respond-by
     * deadline at or before {@code horizon} — near, or already missed (`P7-TSK-014`: the
     * {@code finapp.payments.dispute.deadline.near} alarm, never a decision).
     */
    long countDeadlinesNear(T unitOfWork, Instant horizon);

    /**
     * What the chargebacks STANDING on {@code attempt} attribute to its counterparty, posted or
     * parked — the combined bound's second term (`INV-DSP-01`). Valid only under the attempt's
     * row lock; zero in {@code currency} when none stands.
     */
    Money attributedStanding(T unitOfWork, PaymentAttemptId attempt, CurrencyCode currency);

    /**
     * The chargebacks standing on {@code attempt}, oldest first, each LOCKED — the order a failed
     * refund's share is re-attributed in. Valid only under the attempt's row lock.
     */
    List<Dispute> standingOn(T unitOfWork, PaymentAttemptId attempt);

    /**
     * Whether a chargeback whose share was posted to {@code account} may still be RETURNED — a
     * win would credit the account back — so the account must not close (`P7-TSK-013`; the
     * answer account closing asks through its {@code PendingCredits} port, under the account's
     * lock).
     */
    boolean anyRestorableTo(T unitOfWork, LedgerAccountId account);

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
