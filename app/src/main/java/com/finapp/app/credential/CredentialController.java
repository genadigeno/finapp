package com.finapp.app.credential;

import com.finapp.app.authentication.AuthenticatedSession;
import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A logged-in person changes their own password (`P1-TSK-033`).
 *
 * <h2>The endpoint the plan declared and nobody had built</h2>
 *
 * <p>{@code PHASE_1_PLAN.md} §7 has listed {@code POST /v1/me/credential} since the phase was
 * planned; {@code P1-DOC-002}'s recount found it owned by no task — the ninth backlog defect of
 * that class in Phase 1, and the one that left a real capability gap: a person with a stolen
 * password and no verified channel could not replace their credential through the platform.
 *
 * <h2>No identifier, anywhere in the request</h2>
 *
 * <p>Like {@code /v1/me}, the identity is the proven session's and nothing in the request names a
 * party. There is no victim to name, so the ownership check ADR-0031 requires is satisfied by
 * there being nothing to trust — {@code SESSION_DERIVED}.
 *
 * <h2>{@code 201}, and it returns the new session</h2>
 *
 * <p>The change <strong>rotates</strong> the caller's session ({@code P1-TSK-015}: a password
 * change is what somebody does after suspecting theft, so the identifier they hold must be
 * replaced too), and a response that withheld the replacement would log the customer out at the
 * moment they secured their account. {@code 201} because a new session is created — the
 * {@code AuthenticatedSession} shape a login returns, reused rather than re-declared because it is
 * the same fact: a session handed to its owner in the response that created it.
 *
 * <h2>Two refusal shapes, and the distinction is actionability</h2>
 *
 * <p>A wrong current password, a lock, a lost race and a concurrently-killed session are one
 * uniform {@code 401} — the {@link com.finapp.app.mfa.MfaChallengeController} reasoning, so none is
 * readable from the response. An MFA-enrolled identity presenting a password-only session is a
 * distinct {@code identity.AssuranceRequired}, because <em>step up and retry</em> is something the
 * client can act on, and it discloses only what the caller already knows.
 */
@RestController
@RequestMapping(path = "/me/credential", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
public class CredentialController {

    private final ChangePasswordService changes;

    public CredentialController(ChangePasswordService changes) {
        this.changes = Objects.requireNonNull(changes, "changes must not be null");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AuthenticatedSession changePassword(
            @Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request) {
        return changes.change(current(request), body.currentPassword(), body.newPassword());
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        // Unreachable while the interceptor is registered and the class carries @RequiresSession.
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/me/credential is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }
}
