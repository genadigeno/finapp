package com.finapp.app.session;

import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Turns a presented session into the acting party (`P1-TSK-016`, ADR-0030, ADR-0021).
 *
 * <h2>Nothing owned this, and eight endpoints need it</h2>
 *
 * <p>{@code PHASE_1_PLAN.md} section 7 marks eight endpoints <em>"Auth: session"</em>, and no
 * backlog item built the mechanism. The three that look like they should do not:
 * {@code P1-TSK-020} answers <em>may an actor of this kind do this?</em> and {@code P1-TSK-021}
 * <em>may this actor touch this resource?</em> — both presuppose a caller — while
 * {@code P1-TSK-027} hands a token out rather than consuming one. <em>Who is calling?</em> is a
 * third thing, sitting below both checks.
 *
 * <p>It is built here because without it this task has no deliverable at all: {@code GET
 * /v1/sessions} means <strong>my</strong> sessions.
 *
 * <h2>An interceptor, not a filter, for both of {@code P0-TSK-017}'s reasons</h2>
 *
 * <p>A filter runs before the dispatcher has chosen a handler, so it could not read
 * {@link RequiresSession} without a second, drifting copy of the routing table. And a filter runs
 * outside the exception handler, so its refusal would be the container's default page rather than
 * the error contract.
 *
 * <h2>This is the platform's first real inbound actor</h2>
 *
 * <p>ADR-0021 called {@code enterSystem()} <em>"the greppable list of places Phase 1 must
 * revisit"</em>. Every request through here establishes a scope naming the proven identity, so an
 * audit record written under it attributes the action to a person rather than to the platform.
 *
 * <h2>Every refusal is the same refusal</h2>
 *
 * <p>Absent header, wrong scheme, unknown token, revoked session, idle-expired, absolutely expired
 * — one {@code 401 api.Unauthenticated}, with no detail distinguishing them. The store folds them
 * into an empty result, so there is no branch anybody could later report on: {@code INV-IDN-07}'s
 * reasoning applied to a session rather than to a password.
 */
public final class SessionAuthenticationInterceptor implements HandlerInterceptor {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(SessionAuthenticationInterceptor.class);

    /**
     * Where the authenticated session is left for the handler.
     *
     * <p>A request attribute rather than a field, and that is not incidental: a field would be
     * shared by every concurrent request on this singleton, and
     * {@code NoProcessLocalSessionStateTest} would fail the build for it — correctly, because it
     * would also be a session cache.
     */
    public static final String CURRENT_SESSION = SessionAuthenticationInterceptor.class.getName();

    private static final String SCOPE_ATTRIBUTE = CURRENT_SESSION + ".scope";
    private static final String SCHEME = "Bearer ";

    private final SessionStore<Connection> sessions;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;
    private final Clock clock;
    private final SessionPolicy policy;
    private final com.finapp.identity.Authorization authorization;

    public SessionAuthenticationInterceptor(
            SessionStore<Connection> sessions,
            TransactionTemplate transactions,
            DataSource dataSource,
            Clock clock,
            SessionPolicy policy,
            com.finapp.identity.Authorization authorization) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.authorization =
                Objects.requireNonNull(authorization, "authorization must not be null");
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!governed(handler)) {
            return true;
        }
        HandlerMethod handlerMethod = (HandlerMethod) handler;
        if (annotation(handlerMethod, Unauthenticated.class) != null) {
            return true;
        }
        refuseIfUndeclared(handlerMethod);

        // Thrown, not returned: this must reach the error contract, and there is nothing to commit
        // on this path. The opposite choice from P1-TSK-010, where the refusal is RETURNED because
        // a failed authentication writes an audit record that throwing would roll back. Presenting
        // a dead session is not an authentication attempt against a credential.
        Session session =
                presentedToken(request)
                        .flatMap(this::authenticate)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                PlatformErrorCode.UNAUTHENTICATED,
                                                "No live session was presented"));

        requireAssurance(handler, session);

        request.setAttribute(CURRENT_SESSION, session);
        request.setAttribute(
                SCOPE_ATTRIBUTE,
                SecurityContext.enter(
                        new Actor(session.identityId().value().toString(), ActorType.CUSTOMER)));

        // AFTER the scope is established, deliberately: a denial is audited, and an audit
        // record needs an actor. Checking first would record the refusal as the platform's own
        // action, which is precisely the wrong party (P0-TSK-032).
        requirePermission(handlerMethod, session);
        return true;
    }

    /**
     * Closes the scope, whatever happened.
     *
     * <p>{@code afterCompletion} rather than {@code postHandle}, because the latter is skipped when
     * the handler throws — and a scope left open on a pooled worker is the leak {@code P0-TSK-032}
     * built {@code SecurityContext} to avoid: the next unrelated request on that thread would
     * inherit this customer's identity.
     */
    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Object scope = request.getAttribute(SCOPE_ATTRIBUTE);
        if (scope instanceof SecurityContext.Scope open) {
            open.close();
        }
    }

    // -----------------------------------------------------------------

    /**
     * Looks the session up and extends its idle bound.
     *
     * <p><strong>The lookup is authoritative; the touch is best-effort.</strong> A touch that loses
     * to a concurrent revoke does not fail the request: it authenticated against a session that was
     * live at the moment it looked, and the revoke wins on the <em>next</em> request — which is
     * exactly what {@code INV-IDN-03} says.
     *
     * <p>Its own short transaction, so the extension is not lost when a handler rolls back. A
     * session was presented and used whether or not the work it asked for succeeded.
     *
     * <p><strong>Fails closed.</strong> A storage failure propagates: no session, no actor, no
     * request served. Authenticating against an unreadable database is the one outcome worse than
     * an outage.
     */
    private Optional<Session> authenticate(SessionToken token) {
        Instant at = Instant.now(clock);
        return Optional.ofNullable(
                transactions.execute(
                        status -> {
                            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                            try {
                                Optional<Session> live = sessions.findLive(unitOfWork, token, at);
                                live.ifPresent(
                                        session ->
                                                sessions.touch(
                                                        unitOfWork, session.id(), at, policy));
                                return live.orElse(null);
                            } finally {
                                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                            }
                        }));
    }

    /**
     * The presented value, or nothing.
     *
     * <p>{@code Authorization: Bearer}, never a query parameter: a query string reaches access
     * logs, proxies and browser history, and this value <em>is</em> the session. That is the
     * property {@code CredentialReachesNoEmittedSinkTest} enforces on the published contract.
     *
     * <p>A malformed header yields empty rather than its own error, so a client learns nothing from
     * the difference between "you sent nonsense" and "your session is gone".
     */
    private static Optional<SessionToken> presentedToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(SCHEME)) {
            return Optional.empty();
        }
        String presented = header.substring(SCHEME.length()).strip();
        return presented.isEmpty() ? Optional.empty() : Optional.of(SessionToken.of(presented));
    }

    /**
     * Refuses a session that is not assured enough for the handler (`P1-TSK-018`).
     *
     * <p><strong>Checked here rather than in each handler</strong>, for the reason
     * {@code P0-TSK-017} gave for the idempotency interceptor: a check every handler must remember
     * is a check one of them eventually forgets, and the one that forgets is the one that matters.
     *
     * <p>{@code atLeast}, not equality: a {@code STRONG} session satisfies a {@code MULTI_FACTOR}
     * requirement. Refusing a session that is <em>more</em> assured than asked for is the kind of
     * rule people work around.
     *
     * <p>A distinct error code rather than a bare 403 — a client must be able to tell
     * <em>"you may never do this"</em> from <em>"step up and retry"</em>, which are different
     * actions.
     */
    private static void requireAssurance(Object handler, Session session) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return;
        }
        RequiresAssurance declared = handlerMethod.getMethodAnnotation(RequiresAssurance.class);
        if (declared == null) {
            declared = handlerMethod.getBeanType().getAnnotation(RequiresAssurance.class);
        }
        if (declared == null) {
            return;
        }
        if (!session.assurance().atLeast(declared.value())) {
            throw new ApiException(
                    com.finapp.identity.IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A session at " + session.assurance() + " was presented where "
                            + declared.value() + " is required");
        }
    }

    /**
     * Whether this rule governs the handler at all.
     *
     * <p><strong>Only handlers in {@code com.finapp}.</strong> Actuator's handler methods carry no
     * declaration and never will, because they are not our code, so refusing them would break health
     * and readiness. Excluding by bean-type <em>package</em> states that boundary rather than listing
     * paths, which is the list-of-one defect this repository has met four times.
     *
     * <p>That the actuator surface is unauthenticated is already recorded debt, not a hole this
     * opens.
     */
    private static boolean governed(Object handler) {
        return handler instanceof HandlerMethod handlerMethod
                && handlerMethod.getBeanType().getName().startsWith("com.finapp.");
    }

    /**
     * Refuses a handler that declares nothing. <strong>This is deny-by-default.</strong>
     *
     * <p>ADR-0031: <em>"an operation with no declared permission is refused, not permitted. A rule's
     * absence is never a grant"</em> ({@code INV-IDN-04}). Before this, a handler that forgot
     * {@code @RequiresSession} was simply reachable, and {@code P1-TSK-016} recorded that it failed
     * closed only <em>by accident</em> - throwing at {@code SecurityContext.require()} deep inside
     * the handler, which is a 500 standing in for a security decision.
     *
     * <p>{@code 403} rather than {@code 500}: the caller genuinely may not do it, because nobody
     * may. A 500 would be equally true and would invite a retry storm against an endpoint that can
     * never succeed. Logged at <strong>error</strong> naming the handler, because it is a deployment
     * defect and only the operator can fix it.
     */
    private static void refuseIfUndeclared(HandlerMethod handlerMethod) {
        if (annotation(handlerMethod, RequiresSession.class) != null
                || annotation(handlerMethod, RequiresAssurance.class) != null
                || annotation(handlerMethod, RequiresPermission.class) != null) {
            return;
        }
        LOGGER.error(
                "Refusing {}: it declares no authorization rule, and a rule's absence is never a"
                    + " grant (ADR-0031, INV-IDN-04). Annotate it with @Unauthenticated,"
                    + " @RequiresSession, @RequiresAssurance or @RequiresPermission.",
                handlerMethod.getBeanType().getName() + "." + handlerMethod.getMethod().getName());
        throw new ApiException(
                PlatformErrorCode.FORBIDDEN, "A handler declared no authorization rule");
    }

    /**
     * Refuses a session whose identity does not hold the declared permission.
     *
     * <p>Resolved from authoritative state on this request, never from the session: a role stamped
     * at login survives its own revocation until the session expires, and <em>"remove their access
     * now"</em> would become a promise the architecture cannot keep - {@code INV-IDN-03}'s reasoning
     * applied to authorization.
     *
     * <p><strong>{@code api.Forbidden}, deliberately not a distinct code.</strong> Unlike
     * {@code identity.AssuranceRequired} this is not actionable: a client cannot grant itself a
     * role, so a special code would imply a remedy that does not exist.
     */
    private void requirePermission(HandlerMethod handlerMethod, Session session) {
        RequiresPermission declared = annotation(handlerMethod, RequiresPermission.class);
        if (declared == null) {
            return;
        }
        boolean permitted =
                Boolean.TRUE.equals(
                        transactions.execute(
                                status -> {
                                    Connection unitOfWork =
                                            DataSourceUtils.getConnection(dataSource);
                                    try {
                                        if (authorization.permits(
                                                unitOfWork,
                                                session.identityId(),
                                                declared.value())) {
                                            return true;
                                        }
                                        // Audited inside the transaction that read the roles: a
                                        // refused privileged attempt is the only trace an attacker
                                        // leaves, because an accepted one is audited by the
                                        // operation and a refused one has no operation to do it
                                        // (INV-AUD-03).
                                        authorization.recordDenial(
                                                unitOfWork, session.identityId(), declared.value());
                                        return false;
                                    } finally {
                                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                                    }
                                }));
        if (!permitted) {
            throw new ApiException(
                    PlatformErrorCode.FORBIDDEN,
                    "A session without " + declared.value() + " was presented");
        }
    }

    /** Method first, then the declaring class - the {@code RequiresIdempotencyKey} idiom. */
    private static <A extends java.lang.annotation.Annotation> A annotation(
            HandlerMethod handlerMethod, Class<A> type) {
        A onMethod = handlerMethod.getMethodAnnotation(type);
        return onMethod != null ? onMethod : handlerMethod.getBeanType().getAnnotation(type);
    }

    private static boolean requiresSession(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return false;
        }
        // @RequiresAssurance implies @RequiresSession: there is no assurance without a session to
        // carry it, and a handler that declared only the level would otherwise be UNAUTHENTICATED -
        // the exact inversion of what its author asked for, and silent.
        return handlerMethod.getMethodAnnotation(RequiresSession.class) != null
                || handlerMethod.getBeanType().isAnnotationPresent(RequiresSession.class)
                || handlerMethod.getMethodAnnotation(RequiresAssurance.class) != null
                || handlerMethod.getBeanType().isAnnotationPresent(RequiresAssurance.class);
    }
}
