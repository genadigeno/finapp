package com.finapp.identity;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence for contact channels (`P1-TSK-023`).
 *
 * <p>Generic over the unit of work, as every other store here is: it writes on the caller's
 * connection so the channel and its audit record commit together (ADR-0033).
 *
 * @param <T> the unit of work, a JDBC {@code Connection}
 */
public interface ContactChannelStore<T> {

    /** Adds an unverified channel carrying a pending challenge. */
    void add(T unitOfWork, ContactChannel channel, SingleUseToken challenge, Instant expiresAt);

    /**
     * Spends a verification challenge.
     *
     * <p>Conditional on the challenge being live, so the row count is the outcome and ten instances
     * presenting one token produce one verification. Also conditional on the channel not already
     * being verified: a second verification would reset {@code verified_at}, and
     * {@code verifiedFor} - the input to the "recently changed channel" abuse case - would then
     * report a fresh channel as old.
     *
     * @return the channel now verified, or empty for every reason: unknown token, spent, expired, a
     *     lost race. One answer, because a caller able to tell them apart learns whether a
     *     verification is pending on an account
     */
    Optional<ContactChannel> verify(T unitOfWork, SingleUseToken presented, Instant at);

    /** The identity's verified channel of that kind, if it has one. */
    Optional<ContactChannel> findVerified(T unitOfWork, IdentityId identityId, ContactChannelKind kind);

    /**
     * A channel by identifier, scoped to its owner.
     *
     * <p>{@code identity_id} is in the <strong>statement</strong> (ADR-0031, `P1-TSK-021`): a
     * load-then-compare is a TOCTOU race and checks a copy of the truth rather than the truth.
     */
    Optional<ContactChannel> findOwned(T unitOfWork, ContactChannelId id, IdentityId owner);
}
