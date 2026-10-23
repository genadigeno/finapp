package com.finapp.app.risk;

import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import com.finapp.platform.security.Actor;
import java.util.Objects;
import java.util.Optional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The acting session, as the request being served presented it (`P13-TSK-008`; {@code PHASE_13_PLAN.md} sections 12.2,
 * 12.3; ADR-0108 decision 3) - the one place a risk read learns <em>which</em> of an identity's sessions acts.
 *
 * <p><strong>Why the request, and why only as a pointer.</strong> A seam receives the caller's unit of work and its own
 * aggregate - a transfer carries no session - and an identity may hold several live sessions of different assurance, so
 * "the identity's session" is not an answer. {@link SessionAuthenticationInterceptor} leaves the session it proved on
 * the request ({@link SessionAuthenticationInterceptor#CURRENT_SESSION}), and Spring binds the request to the thread
 * serving it; this reads that binding, per call, and keeps nothing - no field, no cache, no thread-local of its own
 * ({@code NoProcessLocalSessionStateTest}, {@code NoSingleInstanceAssumptionRulesTest}). The session read here is the
 * door's snapshot: {@link IdentitySessionAssurance} re-reads it <strong>live</strong> through the caller's unit of work
 * before trusting its assurance, so a session revoked on another instance between the door and the judgement is
 * judged revoked ({@code INV-IDN-03}).
 *
 * <p><strong>Only the actor's own.</strong> The session answers only when it is the acting identity's - the interceptor
 * enters exactly that actor - so a call made under another actor (a system flow, a test) reads nothing. Outside a
 * request (a sweep, a consumer, a suite calling a command directly) there is no session at all: a {@code CHALLENGE} is
 * then unsatisfied, the safe direction.
 *
 * <p><strong>Not a device.</strong> The session's own label (identity's {@code V005} column) is a sanitised
 * {@code User-Agent}, shared by every customer on one browser build; it is not read here, and no risk request carries
 * it ({@code TransferRiskJudgement.request}: a {@code DEVICE} subject over it would aggregate strangers and serialise
 * their movements on one lock).
 */
public final class ActingSession {

    private ActingSession() {}

    /** The request's authenticated session, when a request is being served, carries one, and it is {@code actor}'s. */
    public static Optional<Session> of(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return Optional.empty();
        }
        Object presented = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION,
                RequestAttributes.SCOPE_REQUEST);
        if (presented instanceof Session session && session.identityId().value().toString().equals(actor.id())) {
            return Optional.of(session);
        }
        return Optional.empty();
    }
}
