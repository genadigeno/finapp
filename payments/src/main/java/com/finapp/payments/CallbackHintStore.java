package com.finapp.payments;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The durable hints (`P15-TSK-036`, {@code V036}). Every bound is applied on the database's clock ({@code X-TSK-013}):
 * the permit is the database's stamp, so no instance's skew may age it.
 */
public interface CallbackHintStore<T> {

    /** Records a hint, asked about first after {@code firstAsk} unless the door's own inquiry concludes it sooner. */
    CallbackHint insert(
            T unitOfWork, CallbackHint.Kind kind, Optional<PaymentAttemptId> attempt, String askedAbout, Duration firstAsk);

    /** The unconcluded hints whose time has come, oldest due first. Lock-free: the permit arbitrates. */
    List<CallbackHint> findDue(T unitOfWork, int batchSize);

    /**
     * Stamps the permit forward, conditionally: the winner asks this tick, a losing instance skips. A wire-noise arbiter
     * among instances, never a money guard (the appliers' conditionals are).
     *
     * @return whether this call won
     */
    boolean renewPermit(T unitOfWork, CallbackHint hint, Duration retryAfter);

    /** Concludes the hint, once. A loser of the race changes nothing. */
    boolean conclude(T unitOfWork, UUID hint, HintedOutcome conclusion);

    /** Concludes as {@code EXPIRED} at most {@code batchSize} hints older than {@code giveUpAfter}. */
    int expire(T unitOfWork, Duration giveUpAfter, int batchSize);
}
