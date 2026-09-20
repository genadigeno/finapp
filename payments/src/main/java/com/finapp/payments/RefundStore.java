package com.finapp.payments;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link Refund} (`P5-TSK-015`, over `V004`).
 *
 * <p>The attempt-store discipline verbatim: every outcome is a conditional transition whose
 * row count is the answer — the arbiter for a synchronous response, a webhook and any future
 * resolver racing on one refund — with `V004`'s transition trigger as the layer beneath, and
 * the sum bound judged in the {@code BEFORE INSERT} trigger under advisory-lock namespace 3
 * for every writer this store never sees ({@code INV-PAY-05}'s concurrent half).
 */
public interface RefundStore<T> {

    /**
     * @param dispatchKey the idempotency claim whose Tx1 creates this row (`V008`,
     *     `P5-TSK-016`) — the takeover convergence's natural key, never null for new rows
     */
    void insert(T unitOfWork, Refund refund, String dispatchKey);

    Optional<Refund> findById(T unitOfWork, RefundId refund);

    /**
     * The refunds sitting {@code UNKNOWN} right now, and the oldest one's age in seconds
     * (`P5-TSK-017`) — the attempt store's reading, for the machine that strands money
     * behind a standing hold. Counts and seconds only ({@code INV-AUD-02}).
     */
    PaymentAttemptStore.UnknownReading unknownReading(T unitOfWork);

    /**
     * The newest refund carrying {@code dispatchKey} (`P5-TSK-016`): the takeover re-run's
     * convergence lookup. Newest first because a key can legitimately reappear after the
     * claim's retention has swept it — the caller judges the row's facts before converging.
     */
    Optional<Refund> findByDispatchKey(T unitOfWork, String dispatchKey);

    /**
     * The refund whose stored reference — ours minted at dispatch, or the provider's from a
     * committed completion — matches (`INV-PAY-04`, both columns; the attempt-store shape
     * verbatim). The webhook door's attribution read (`SIGNED_CALLBACK`): verification runs
     * before it, and the reachable writes are the refund's own conditional edges.
     */
    Optional<Refund> findByOperationReference(
            T unitOfWork, ProviderIdempotencyReference reference);

    /** The attempt's refunds, oldest first — `P5-TSK-016`'s derived-totals read arrives here. */
    List<Refund> listFor(T unitOfWork, PaymentAttemptId attempt);

    /**
     * The sum of this attempt's non-{@code FAILED} refunds in {@code currency} — the
     * {@code alreadyRefunded} input {@link Refund#create} demands, and the read the
     * lock-then-look contract is about: <strong>call it only under the command's
     * {@code FOR UPDATE} on the attempt row</strong> ({@code P5-TSK-015}), because two
     * dispatchers reading sums without the lock is the `P2-TSK-015` write-skew shape the
     * schema trigger exists to refuse for everyone else.
     */
    Money sumNonFailedFor(T unitOfWork, PaymentAttemptId attempt, CurrencyCode currency);

    /** {@code from → COMPLETED} with the provider's reference; the row count is the answer. */
    boolean complete(
            T unitOfWork, RefundId refund, RefundStatus from, ProviderReference providerReference);

    /** {@code from → FAILED}; the freed budget is the sum bound's own arithmetic. */
    boolean fail(T unitOfWork, RefundId refund, RefundStatus from);

    /** {@code DISPATCHED → UNKNOWN} ({@code INV-LIFE-03}); the hold stands with it. */
    boolean markUnknown(T unitOfWork, RefundId refund);

    /** Appends the transition to {@code refund_event} — append-only, server-ordered. */
    void recordTransition(
            T unitOfWork,
            RefundId refund,
            RefundStatus from,
            RefundStatus to,
            Actor actor,
            Instant occurredAt);
}
