package com.finapp.payments;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
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
