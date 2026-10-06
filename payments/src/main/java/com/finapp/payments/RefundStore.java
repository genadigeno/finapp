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
     * The refund and its latest send permit, {@code FOR UPDATE} — the one read every outcome
     * judges from (the Phase 6 → 7 transition). Resolvers that judged from an unlocked read
     * could lose their conditional to a racing resolver and report the verdict they held rather
     * than the row's truth, or lose a webhook whose delivery then counted as processed.
     */
    Optional<LockedRefund> lockForOutcome(T unitOfWork, RefundId refund);

    /**
     * A refund as its outcome transaction sees it: the row, locked, and the permit of its
     * latest send ({@code V009}) — which a refused connection is judged against.
     */
    record LockedRefund(Refund refund, Instant lastDispatchedAt) {

        public LockedRefund {
            java.util.Objects.requireNonNull(refund, "refund must not be null");
            java.util.Objects.requireNonNull(lastDispatchedAt, "lastDispatchedAt must not be null");
        }
    }

    /**
     * Commits a new send permit before a re-send of our reference ({@code V009}, ADR-0057 §4's
     * discipline): a takeover's re-drive, or the resolution sweep's re-drive of a reference the
     * provider never saw. The conditional IS the permit — a refund some resolver already moved
     * out of {@code DISPATCHED}/{@code UNKNOWN} matches no row, and then nothing may be sent.
     *
     * @return the permit as stored — which an outcome later compares with the locked row's, so
     *     it is the database's value and never this instance's clock at a finer precision — or
     *     empty when the refund is no longer resolvable. The new permit is the database's own
     *     instant, strictly forward (`X-TSK-013`): no instance's clock is an input
     */
    Optional<Instant> renewSendPermit(T unitOfWork, RefundId refund);

    /**
     * The refunds the resolution sweep asks about (the Phase 6 → 7 transition): {@code
     * DISPATCHED} whose latest send permit is at or before {@code dispatchedBefore}, and {@code
     * UNKNOWN} whose move into {@code UNKNOWN} is at or before {@code unknownBefore} — oldest
     * first, at most {@code limit}. The attempt store's {@code findSweepable}, for the refund.
     */
    List<Refund> findSweepable(
            T unitOfWork, Instant dispatchedBefore, Instant unknownBefore, int limit);

    /**
     * The RETURNS the push rail's own resolution asks about (`P7-TSK-010`): the sweepable
     * shape verbatim, partitioned to PUSH-model attempts — {@link #findSweepable} feeds the
     * card sweeper and excludes them, because a resolver that asked the wrong counterparty
     * about our reference would hear {@code UNRECOGNISED} and re-drive against facts the
     * row does not carry (the attempt sweep's partition, at the refund).
     */
    List<Refund> findSweepableReturns(
            T unitOfWork, Instant dispatchedBefore, Instant unknownBefore, int limit);

    /**
     * The refunds stuck right now — every {@code UNKNOWN}, and every {@code DISPATCHED} whose
     * latest send permit is past the sweep's own dispatched bound — and the oldest one's wait in
     * seconds (`P5-TSK-017`; widened by the Phase 6 → 7 transition to the payout's shape,
     * `P6-TSK-013`: a refund whose instance crashed mid-dispatch is stuck too, and counting only
     * {@code UNKNOWN} left it invisible whenever the sweep was down). Counts and seconds only
     * ({@code INV-AUD-02}).
     */
    PaymentAttemptStore.UnknownReading unknownReading(
            T unitOfWork, java.time.Duration dispatchedBound);

    /**
     * The refund carrying {@code dispatchKey} (`P5-TSK-016`): the takeover re-run's convergence
     * lookup. One row per key for ever — `V008`'s unique index — so a key is bound to its refund
     * for longer than the claim that first carried it: the caller converges on the row when its
     * facts match, and refuses the key when they do not. *(This read "newest first, because a key
     * can legitimately reappear after the claim's retention" until the Phase 6 → 7 transition,
     * which found that the unique index forbids exactly that, and that a takeover finding a
     * refund a webhook had already finished dispatched afresh into it.)*
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

    /**
     * What this attempt has actually had returned — the <strong>{@code COMPLETED}</strong> sum
     * (`P6-TSK-014`).
     *
     * <p><strong>Not {@link #sumNonFailedFor}, and the difference is the point.</strong> The
     * budget bound counts non-failed refunds, correctly: a refund in flight has already
     * reserved its share of what may be returned, and letting a second one reserve the same
     * money would let the pair exceed the capture. But money that has been <em>reserved</em>
     * has not <em>left</em>, and a sibling refund can still fail. Anything priced against the
     * non-failed sum would therefore return the merchant's fee for a refund that never
     * happened, with no producer for taking it back.
     *
     * <p><strong>Valid only after the caller has serialised with every other refund of the same
     * payment.</strong> At dispatch that is the command's {@code FOR UPDATE} on the attempt row.
     * At completion it is the {@code FOR UPDATE} the hold's release takes on the account the
     * refund debits ({@code HoldService#release}), which runs before this read and which every
     * completion of the same payment shares — no completion path holds the attempt lock. *(This
     * named the attempt lock for both until the Phase 6 → 7 transition's audit, which found the
     * completion's real serialisation point: moving the release after this read would let two
     * completions each price their fee share as the first.)*
     */
    Money sumCompletedFor(T unitOfWork, PaymentAttemptId attempt, CurrencyCode currency);

    /** {@code from → COMPLETED} with the provider's reference; the row count is the answer. */
    /**
     * The {@code COMPLETED} refunds with {@code id > after}, in id order, at most
     * {@code limit} — the opening-position backfill's page (`P8-TSK-007`, ADR-0067 §8;
     * the {@code pageByStatus} classification sentence, verbatim).
     */
    List<Refund> pageCompleted(T unitOfWork, java.util.UUID after, int limit);

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
