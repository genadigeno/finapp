package com.finapp.app.session;

import com.finapp.identity.Session;
import com.finapp.identity.SessionId;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a person can see and end their own sessions (`P1-TSK-016`).
 *
 * <h2>Ownership is never read from the request, and that is the whole task</h2>
 *
 * <p>The owner comes from {@link SessionAuthenticationInterceptor}, which proved it against the
 * database. It is never a path variable, a query parameter or a body field, because ADR-0031 is
 * explicit that trusting an identifier out of the request <em>is</em> the defect: a caller who can
 * name the owner can name somebody else.
 *
 * <p>The identifier in {@code DELETE /v1/sessions/&#123;id&#125;} is the <em>resource</em>, not the
 * owner. It is passed to a statement whose {@code WHERE} clause carries the proven identity beside
 * it, so a session belonging to somebody else matches nothing.
 *
 * <h2>Not yours and does not exist are the same answer</h2>
 *
 * <p>Both are {@code 404}. Reporting {@code 403} for the first would confirm that a session
 * identifier belongs to <em>somebody</em>, which turns this endpoint into an oracle over other
 * people's sessions — {@code INV-IDN-07}'s reasoning, which is about a response shape rather than
 * about passwords.
 *
 * <h2>Not idempotency-keyed</h2>
 *
 * <p>Phase 1 moves no money, so {@code INV-IDEM-01} is vacuous here. More decisively, a stored
 * replayable outcome keyed on a value that {@code API_CONVENTIONS.md} section 6 states is
 * <strong>not a secret</strong> is the hazard {@code P1-TSK-010} refused. Revocation is idempotent
 * in effect anyway: revoking twice ends one session and reports 404 the second time.
 */
@RestController
@RequestMapping(path = "/sessions", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class SessionController {

    @NonNull private final SessionQueries sessions;

    /** Every live session of the authenticated identity, newest first. */
    @GetMapping
    public List<SessionSummary> listSessions(HttpServletRequest request) {
        Session current = current(request);
        return sessions.listOwnedBy(current);
    }

    /**
     * Ends one of the authenticated identity's sessions.
     *
     * <p>Ending your own current session through this path is permitted and behaves exactly as
     * {@link #logout} does. Refusing it would be a rule with no safety behind it, and a client that
     * knows its own session identifier is not doing anything it cannot do at {@code /current}.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSession(@PathVariable String id, HttpServletRequest request) {
        Session current = current(request);
        SessionId target = parse(id);
        if (!sessions.revokeOwnedBy(current, target)) {
            // Deliberately the same answer for "no such session" and "not yours".
            throw new ApiException(
                    PlatformErrorCode.NOT_FOUND, "No live session of this identity matched");
        }
    }

    /** Ends the session that made this request. */
    @DeleteMapping("/current")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        Session current = current(request);
        // The result is deliberately ignored. A session that has just been revoked concurrently is
        // gone, which is what the caller asked for - reporting 404 for a successful logout would be
        // telling somebody their logout failed when they are, in fact, logged out.
        sessions.revokeOwnedBy(current, current.id());
    }

    // -----------------------------------------------------------------

    /**
     * A malformed identifier is {@code 404}, not {@code 400}.
     *
     * <p>The distinction would otherwise be free information: {@code 400} for a value that is not a
     * UUID and {@code 404} for one that is tells a caller which shapes are worth trying. It is also
     * honest — a value that cannot name a session names no session.
     */
    private static SessionId parse(String id) {
        try {
            return SessionId.of(java.util.UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    PlatformErrorCode.NOT_FOUND, "A malformed session identifier was presented");
        }
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        // Unreachable while the interceptor is registered and the class is annotated. It is a
        // refusal rather than an assumption because the alternative - proceeding without a proven
        // owner - is the one failure this endpoint must never have.
        throw new IllegalStateException(
                "No authenticated session on the request: the handler is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }
}
