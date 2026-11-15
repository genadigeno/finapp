package com.finapp.app.session;

import com.finapp.identity.Authorization;
import com.finapp.identity.Session;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who the signed-in operator is, what they hold, and how strongly their session is proven (`X-TSK-019`; the console's
 * {@code P17-TSK-003}, owner decision D-12 of 2026-11-13).
 *
 * <p>{@code GET /v1/me} is a customer's profile and sign-in carries no roles, so the console had no way to learn which
 * modules to show. This is that answer, and nothing else: no login identifier, no name, no other identity.
 *
 * <h2>The operator surface only, any role, no step-up</h2>
 *
 * <p>Under {@code /v1/operator}, so the edge allow-list derived from the handler mapping serves it on the internal host and
 * never on the public one. {@link RequiresAnyRole}: a session whose identity holds no live role is refused
 * {@code 403 api.Forbidden}, recorded; no session is {@code 401}. No {@link RequiresAssurance}: the console asks this right
 * after a password sign-in to decide whether to prompt for the second factor, so it reports the assurance instead of
 * demanding one. A suspended identity never reaches it: suspension ends every session it held ({@code P1-TSK-028}).
 *
 * <h2>The same read that admitted the request</h2>
 *
 * <p>The roles are the ones the interceptor read with the session, in the session's transaction, to decide whether this
 * request may run ({@link SessionAuthenticationInterceptor#CURRENT_STANDING}) - not a second read that could disagree with
 * the first. The permissions are those roles' grants from {@code RoleName}, which is code. Nothing is cached.
 *
 * <h2>Not audited</h2>
 *
 * <p>A person reading what they themselves may do changes nothing and discloses nothing they do not already hold; every act
 * those permissions allow is recorded where it happens. The refusal is recorded ({@code identity.AuthorizationDenied},
 * {@code requirement=ANY_ROLE}). {@code AUDITABLE_ACTIONS.md} §3 states this.
 */
@RestController
@RequestMapping(path = "/operator/me", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class OperatorSelfController {

    @NonNull private final OperatorSelfQueries queries;

    /** The caller's own identity, roles, effective permissions and session. */
    @GetMapping
    @RequiresAnyRole
    public OperatorSelf describeSelf(HttpServletRequest request) {
        return queries.describe(current(request), standing(request));
    }

    /**
     * What {@code GET /v1/operator/me} publishes.
     *
     * <p>Every member is always present: none can be absent for a live operator session, so none is ever {@code null}.
     *
     * @param identityId the caller's identity
     * @param roles the live roles held, by name, sorted
     * @param permissions the union of those roles' permissions, by name, sorted
     * @param assurance the session's level: {@code PASSWORD}, {@code MULTI_FACTOR} or {@code STRONG}
     * @param factorEnrolled whether the identity has a confirmed second factor to step up with
     * @param sessionIdleExpiresAt when the session ends unless used again, as the database extended it for this request
     * @param sessionAbsoluteExpiresAt when the session ends whatever happens
     */
    public record OperatorSelf(
            String identityId,
            List<String> roles,
            List<String> permissions,
            String assurance,
            boolean factorEnrolled,
            Instant sessionIdleExpiresAt,
            Instant sessionAbsoluteExpiresAt) {}

    // -----------------------------------------------------------------

    private static Session current(HttpServletRequest request) {
        if (request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION) instanceof Session authenticated) {
            return authenticated;
        }
        // Unreachable while the interceptor is registered and the handler declares @RequiresAnyRole: a refusal rather than
        // an assumption, because describing nobody is the one answer this endpoint must never give.
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/operator/me is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }

    private static Authorization.Standing standing(HttpServletRequest request) {
        if (request.getAttribute(SessionAuthenticationInterceptor.CURRENT_STANDING)
                instanceof Authorization.Standing read) {
            return read;
        }
        throw new IllegalStateException(
                "No standing on the request: /v1/operator/me is reachable without the interceptor having read the roles");
    }
}
