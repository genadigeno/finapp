package com.finapp.app.mfa;

import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
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
 * Enrolling a second factor (`P1-TSK-017`).
 *
 * <h2>Two operations, and the plan's table lists one</h2>
 *
 * <p>{@code PHASE_1_PLAN.md} §7 has a single row, {@code POST /v1/me/mfa | session | Enrolment}.
 * That is <strong>under-specified rather than wrong</strong>: the same plan requires that
 * <em>"enrolment is not complete until confirmed by a valid code"</em>, and a confirmation is a
 * second request — the customer has to go and read their authenticator in between. Recorded as a
 * plan correction rather than silently added.
 *
 * <h2>The identity is never read from the request</h2>
 *
 * <p>It comes from the session {@code P1-TSK-016}'s interceptor proved. ADR-0031 is explicit that
 * trusting an identifier out of the request <em>is</em> the defect, and here it would be the whole
 * attack: a caller who could name the identity could attach their own second factor to somebody
 * else's account.
 *
 * <h2>Why enrolment does not itself require {@code MULTI_FACTOR}</h2>
 *
 * <p>It cannot: there is no factor to satisfy such a requirement with until this endpoint has been
 * used. What protects it is that the session is <strong>proven</strong> and that a confirmed factor
 * cannot be replaced without confirming the replacement. Requiring a *fresh* password
 * re-authentication for this action is a real hardening and belongs to `P1-TSK-020`'s step-up work
 * — recorded rather than half-built here.
 */
@RestController
@RequestMapping(path = "/me/mfa", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
public class MfaController {

    private final MfaEnrolmentApplicationService enrolments;

    public MfaController(MfaEnrolmentApplicationService enrolments) {
        this.enrolments = Objects.requireNonNull(enrolments, "enrolments must not be null");
    }

    /**
     * Begins an enrolment.
     *
     * <p>The response carries the secret. <strong>This is the only time it leaves the server</strong>
     * — there is no read path that returns it, and a customer who loses it re-enrols rather than
     * retrieving it.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MfaEnrolmentStarted beginMfaEnrolment(HttpServletRequest request) {
        return enrolments
                .begin(current(request))
                .orElseThrow(
                        () ->
                                // Actionable, and that is why it is not the uniform refusal the
                                // challenge uses: the customer CAN proceed, by proving the factor
                                // they already have. Telling them so discloses nothing - they hold a
                                // proven session for their own account and already know it exists.
                                new ApiException(
                                        com.finapp.identity.IdentityErrorCode.ASSURANCE_REQUIRED,
                                        "Replacing a confirmed second factor requires it"));
    }

    /**
     * Confirms it, against a code from the customer's authenticator.
     *
     * <p>A wrong code is {@code 422} and the enrolment stays pending, so <strong>assurance is
     * unchanged</strong> — this task's acceptance criterion, at the boundary that produces it.
     *
     * <p>Every failure is the same failure: no pending enrolment, a wrong code and a lost race are
     * one response, because a caller able to tell them apart learns whether an enrolment is in
     * progress on the account.
     */
    @PostMapping("/confirmation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmMfaEnrolment(
            @Valid @RequestBody MfaConfirmationRequest body, HttpServletRequest request) {
        if (!enrolments.confirm(current(request), body.code())) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "An MFA enrolment confirmation was refused",
                    "That code did not confirm an enrolment.");
        }
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
