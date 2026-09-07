package com.finapp.app.mfa;

import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.Session;
import com.finapp.platform.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proving a second factor (`P1-TSK-018`).
 *
 * <h2>The session it consumes is a partial one</h2>
 *
 * <p>{@code PHASE_1_PLAN.md} §7 marks this endpoint <em>"partial session"</em>: the caller has
 * proven a password and holds a {@code PASSWORD} session, and this is where they prove the second
 * factor. It is the one endpoint whose <em>input</em> is deliberately a session that would be
 * refused by anything requiring {@code MULTI_FACTOR}.
 *
 * <h2>The response carries a new token, because elevation rotates</h2>
 *
 * <p>Elevating in place would let an identifier stolen <em>before</em> the step-up become elevated
 * behind the legitimate user's back ({@code P1-TSK-015}). So the presented session is revoked and a
 * new one issued — and a response that withheld the replacement would log the customer out at the
 * moment they proved a second factor.
 *
 * <h2>One refusal for every reason</h2>
 *
 * <p>No factor, a wrong code, a replayed code, a lock, a lost race: one {@code 401}. A caller able
 * to tell them apart learns the state of an account's defences — whether MFA is enrolled, whether
 * the account is locked, whether somebody else is elevating it right now. The reasons live in the
 * audit trail, which is where an investigator needs them and a client does not.
 *
 * <p>{@code 401} rather than {@code 422}: the code <em>is</em> a credential, and failing to present
 * a valid one is an authentication failure. {@code identity.AuthenticationFailed} is the same code
 * {@code POST /v1/authentications} returns, deliberately — both mean <em>"you did not prove what
 * you claimed"</em>.
 */
@RestController
@RequestMapping(path = "/authentications/mfa", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
public class MfaChallengeController {

    private final MfaChallengeApplicationService challenges;

    public MfaChallengeController(MfaChallengeApplicationService challenges) {
        this.challenges = Objects.requireNonNull(challenges, "challenges must not be null");
    }

    @PostMapping
    public ElevatedSession elevateAssurance(
            @Valid @RequestBody MfaConfirmationRequest body, HttpServletRequest request) {
        return challenges
                .elevate(current(request), body.code())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        IdentityErrorCode.AUTHENTICATION_FAILED,
                                        "An MFA challenge was refused"));
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: the handler is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }
}
