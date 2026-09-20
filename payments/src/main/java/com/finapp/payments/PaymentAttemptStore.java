package com.finapp.payments;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link PaymentAttempt} ({@code P5-TSK-009}, over {@code V003}).
 *
 * <p><strong>Every outcome is a conditional transition carrying exactly its payload</strong>
 * ({@code UPDATE … WHERE id AND status = from}, row count the answer): the arbiter for the
 * outcome races ADR-0046 names — a synchronous response, a webhook and a sweeper resolving one
 * operation are the same harmless race, exactly one write landing. {@code false} is
 * convergence, never an error; the caller retains its evidence regardless
 * ({@code INV-HIST-02}).
 */
public interface PaymentAttemptStore<T> {

    void insert(T unitOfWork, PaymentAttempt attempt);

    /**
     * The intent's attempt — one in Phase 5 (ADR-0045 §4); when N arrive with Phase 7's
     * routing, the newest. Empty when nothing was ever dispatched.
     */
    Optional<PaymentAttempt> findForIntent(T unitOfWork, PaymentIntentId intent);

    /** The attempt by its own identifier — the capture command's read ({@code P5-TSK-010}). */
    Optional<PaymentAttempt> findById(T unitOfWork, PaymentAttemptId attempt);

    /**
     * The attempt, locked {@code FOR UPDATE} — the refund bound's lock-then-look
     * (`P5-TSK-015`): the sibling sum {@link RefundStore#sumNonFailedFor} reads is current
     * only under this lock, because two dispatchers summing without it is the `P2-TSK-015`
     * write-skew shape the schema trigger refuses for everyone else. Taken BEFORE the wallet
     * account's lock, always — the attempt→account order every money path shares, so the
     * refund dispatch and the capture outcome cannot deadlock each other.
     */
    Optional<PaymentAttempt> lockById(T unitOfWork, PaymentAttemptId attempt);

    /**
     * The sweeper's candidates (`P5-TSK-014`, ADR-0046 §4): attempts in a resolvable state
     * whose state age has passed its bound — {@code *_DISPATCHED} past {@code dispatchedBefore}
     * (birth for {@code AUTH_DISPATCHED}, the transition row for the capture's), and
     * {@code *_UNKNOWN} past {@code unknownBefore}. Bounded ({@code limit}) and oldest first,
     * the relay's batching posture. Stale answers are harmless: every resolution re-reads and
     * applies conditionally.
     */
    List<PaymentAttempt> findSweepable(
            T unitOfWork, Instant dispatchedBefore, Instant unknownBefore, int limit);

    /**
     * The attempt one of whose minted operation references is {@code reference} — the webhook
     * door's attribution read (`P5-TSK-012`). The identifier presented is one the platform
     * handed the provider before anything was sent ({@code INV-PAY-04}), and the HMAC is
     * verified before this read runs — the {@code SIGNED_CALLBACK} reasoning; empty is the
     * unattributable webhook {@code V005} explicitly admits.
     */
    Optional<PaymentAttempt> findByOperationReference(
            T unitOfWork, ProviderIdempotencyReference reference);

    /**
     * {@code AUTHORIZED → CAPTURE_DISPATCHED}, the capture's idempotency reference minted by
     * this act and stored before anything is sent ({@code INV-PAY-04}, ADR-0046).
     */
    boolean dispatchCapture(
            T unitOfWork, PaymentAttemptId attempt, ProviderIdempotencyReference reference);

    /** {@code from → CAPTURED}, the capture pair arriving with the transition (ADR-0048). */
    boolean capture(
            T unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            ProviderReference providerReference,
            Money capturedAmount);

    /** {@code CAPTURE_DISPATCHED → CAPTURE_UNKNOWN} — the second {@code INV-LIFE-03} state. */
    boolean markCaptureUnknown(T unitOfWork, PaymentAttemptId attempt);

    /** {@code from → AUTHORIZED}, the issuer's promise arriving with the transition. */
    boolean authorize(
            T unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            ProviderReference providerReference,
            Money authorizedAmount);

    /** {@code from → FAILED} with the mapped reason ({@code INV-PAY-03}). */
    boolean fail(
            T unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            PaymentFailureReason reason);

    /** {@code AUTH_DISPATCHED → AUTH_UNKNOWN} — ambiguity committed honestly ({@code INV-LIFE-03}). */
    boolean markAuthUnknown(T unitOfWork, PaymentAttemptId attempt);

    /** The append-only history row — actor model per {@code V006}. */
    void recordTransition(
            T unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            PaymentAttemptStatus to,
            Actor actor,
            Instant occurredAt);
}
