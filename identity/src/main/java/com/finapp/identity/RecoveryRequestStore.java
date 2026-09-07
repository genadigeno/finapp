package com.finapp.identity;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence for recovery requests (`P1-TSK-023`, {@code INV-IDN-06}).
 *
 * @param <T> the unit of work, a JDBC {@code Connection}
 */
public interface RecoveryRequestStore<T> {

    /**
     * Cancels whatever live request the identity behind this login identifier has.
     *
     * <p>Runs before every initiation, so a second initiation kills the first token. Without it an
     * attacker who initiated once keeps a live token while the customer initiates again and believes
     * they have fixed their account — two valid ways in, which is one more than recovery may have.
     *
     * <p><strong>Keyed on the login identifier, resolved by a subselect</strong>, for
     * {@code AuthenticationThrottle}'s reason: looking the identity up first would run one query
     * when the account is absent and two when it is present, which is a timing difference that
     * discloses existence.
     *
     * @return how many were cancelled; zero for an unknown identifier and for an identity with none
     */
    int cancelLiveFor(T unitOfWork, LoginIdentifier login, Instant at);

    /**
     * Creates a request, but only for an identity that can actually recover.
     *
     * <p><strong>One statement, and that is the enumeration-safety property.</strong> Whether the
     * identifier names anybody, whether that identity has a <em>verified</em> channel, and whether
     * cooling-off permits another attempt are all predicates inside a single
     * {@code INSERT … SELECT}. The caller receives the same answer and pays the same work in every
     * case, so {@code POST /v1/recoveries} discloses nothing ({@code INV-IDN-07}).
     *
     * @param notBefore the cooling-off boundary; an identity that initiated after this instant is
     *     refused, which bounds how often an attacker can make somebody's inbox ring
     * @return the request, or empty for every reason there could be one
     */
    Optional<RecoveryRequest> initiate(
            T unitOfWork,
            LoginIdentifier login,
            ContactChannelKind kind,
            SingleUseToken token,
            Instant at,
            Instant expiresAt,
            Instant notBefore);

    /**
     * Spends a token, if everything about the request is still true.
     *
     * <p>Conditional on the identifier, the token, the status, the expiry <strong>and the credential
     * the request was bound to</strong> — the last being the concurrent-recovery-and-login control.
     * The row count is the outcome, so ten instances presenting one token produce one completion.
     *
     * @return the identity whose credential may now be replaced, or empty for every reason
     */
    Optional<RecoveryRequest> consume(
            T unitOfWork, RecoveryRequestId id, SingleUseToken presented, Instant at);
}
