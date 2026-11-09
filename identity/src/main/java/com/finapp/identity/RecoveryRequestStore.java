package com.finapp.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
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
    int cancelLiveFor(T unitOfWork, LoginIdentifier login);

    /**
     * Creates a request, but only for an identity that can actually recover.
     *
     * <p><strong>One statement, and that is the enumeration-safety property.</strong> Whether the
     * identifier names anybody, whether that identity has a <em>verified</em> channel, and whether
     * cooling-off permits another attempt are all predicates inside a single
     * {@code INSERT … SELECT}. The caller receives the same answer and pays the same work in every
     * case, so {@code POST /v1/recoveries} discloses nothing ({@code INV-IDN-07}).
     *
     * <p><strong>No instant crosses this port</strong> ({@code P15-TSK-038}, ADR-0014): the database
     * stamps {@code initiated_at} and {@code expires_at} and judges the cooling-off, all at one
     * {@code now()}. The durations are the policy; an instance's clock is not an input.
     *
     * @param lifetime how long the token stands, from the database's {@code now()}
     * @param coolingOff how long after an initiation another is refused, which bounds how often an
     *     attacker can make somebody's inbox ring
     * @return the request, or empty for every reason there could be one
     */
    Optional<RecoveryRequest> initiate(
            T unitOfWork,
            LoginIdentifier login,
            ContactChannelKind kind,
            SingleUseToken token,
            Duration lifetime,
            Duration coolingOff);

    /**
     * Spends a token, if everything about the request is still true.
     *
     * <p>Conditional on the identifier, the token, the status, the expiry <strong>and the credential
     * the request was bound to</strong> — the last being the concurrent-recovery-and-login control.
     * The row count is the outcome, so ten instances presenting one token produce one completion.
     *
     * <p>The expiry is judged and the completion stamped in the statement, on the database's clock
     * ({@code P15-TSK-038}).
     *
     * @return the request and the instant the database completed it, or empty for every reason
     */
    Optional<Consumed> consume(T unitOfWork, RecoveryRequestId id, SingleUseToken presented);

    /**
     * What spending a token answers.
     *
     * @param request the identity whose credential may now be replaced
     * @param completedAt the row's own {@code completed_at}, the database's reading: the instant the
     *     caller records beside it, so the audit record and the row carry one clock
     */
    record Consumed(RecoveryRequest request, Instant completedAt) {

        public Consumed {
            Objects.requireNonNull(request, "request must not be null");
            Objects.requireNonNull(completedAt, "completedAt must not be null");
        }
    }
}
