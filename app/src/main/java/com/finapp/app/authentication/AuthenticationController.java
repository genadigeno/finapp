package com.finapp.app.authentication;

import com.finapp.identity.DeviceDescription;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.platform.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/authentications} (`P1-TSK-010`).
 *
 * <h2>Two outcomes, and only two</h2>
 *
 * <p>{@code 201} with a session, or {@code 401 identity.AuthenticationFailed}. Unknown identity,
 * wrong password, suspended identity and an identity that has no credential yet all produce the
 * <strong>same bytes</strong> - and, because {@code P1-TSK-008} makes every one of those paths
 * perform a full Argon2id verification, the same cost. {@code INV-IDN-07} is lost through either
 * channel, and the timing one is the harder half to notice.
 *
 * <p>The <strong>failure</strong> shape is what that invariant is about, and it is untouched by
 * {@code P1-TSK-027}: {@code everyFailureLooksTheSame} still compares the four causes to each
 * other. A body on the success path discloses nothing, because a caller who authenticated
 * successfully already knows they did.
 *
 * <h2>201, and it used to be 204</h2>
 *
 * <p>{@code P1-TSK-010} returned {@code 204} truthfully: its declared dependency - session issuance
 * - sat in the next milestone, so there was no representation to return. The Phase 1 review found
 * what that left behind: <strong>no production path issued a first session at all</strong>, so the
 * eight endpoints the plan marks <em>"Auth: session"</em> were unreachable by any real client, and
 * criterion 1 failed. This is that remediation, and it closes M1.2, whose stated acceptance is
 * <em>"an identity authenticates and receives a session"</em>.
 *
 * <p>{@code 201} rather than {@code 200} because a session is <strong>created</strong> - and it is
 * the same status {@code POST /v1/registrations} uses for the same reason. No {@code Location}
 * header: the created resource is {@code /v1/sessions/{id}}, and publishing that identifier to a
 * caller who was refused nothing would be harmless, while publishing it in a header that proxies
 * and access logs record would put a live session's identifier where {@code INV-AUD-02} says it
 * must not go.
 *
 * <p><strong>The contract change is breaking and is accepted with the reasoning recorded.</strong>
 * A client written against {@code 204} would treat {@code 201} as unexpected. There is no such
 * client - nothing consumes this API - and the alternative is a {@code /v2} for an endpoint whose
 * first version was never usable. {@code ADR-0015} allows exactly this judgement: the classifier
 * labels it, a human reads it, and the reason lives here rather than in a commit message.
 *
 * <h2>No idempotency key</h2>
 *
 * <p>Unlike registration, and not because the money-moving clause is vacuous. An idempotency key is
 * explicitly <em>not a secret</em> ({@code API_CONVENTIONS.md} §6), so a stored success keyed on one
 * would let anybody who saw the key replay a <em>successful authentication</em>. The mechanism that
 * makes registration safe would make this an authentication bypass.
 *
 * <h2>The version prefix is not here</h2>
 *
 * <p>{@code /authentications}, not {@code /v1/authentications}: {@code ApiVersionConfiguration}
 * applies the prefix once in the composition root.
 */
@com.finapp.app.session.Unauthenticated
@RestController
// `produces` is explicit, because springdoc publishes `*/*` without it - the defect
// `P1-TSK-016`'s gate found on the session endpoints, where a generated client would be
// told the response could be anything.
@RequestMapping(
        path = "/authentications",
        produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class AuthenticationController {

    @NonNull private final AuthenticationService authentications;

    /**
     * Authenticates a person and issues their session.
     *
     * <p>{@code @ResponseStatus} rather than a {@code ResponseEntity}, because only the annotation
     * reaches the published contract - the defect {@code P1-TSK-006} found when springdoc published
     * {@code "200": "OK"} for an endpoint that has never returned 200.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AuthenticatedSession authenticate(
            @Valid @RequestBody AuthenticationRequest request, HttpServletRequest http) {
        return authentications
                .authenticate(request, device(http))
                .orElseThrow(
                        () ->
                                // One code, no detail beyond its title, for every reason an
                                // authentication can fail. The message below is the LOG message;
                                // ApiException keeps it separate from what a client is told, which
                                // is what makes the unsafe default unreachable (`P0-TSK-024`).
                                new ApiException(
                                        IdentityErrorCode.AUTHENTICATION_FAILED,
                                        "Authentication refused; the audit record holds the"
                                                + " attempted identifier"));
    }

    /**
     * The device label, sanitised and never a reason to refuse.
     *
     * <p>{@code DeviceDescription.fromUserAgent} has had no production caller since
     * {@code P1-TSK-016} wrote it - this is it, and it is what makes {@code GET /v1/sessions} show
     * a person something they recognise rather than a column of nulls.
     *
     * <p>It <strong>sanitises rather than rejects</strong>, which is the opposite of what
     * {@code PartyName} does and deliberately so: a display name is the person's own data, so
     * refusing it tells them to fix something they chose, while a {@code User-Agent} is a header
     * they did not choose and cannot edit. Letting an unscored convenience label refuse an
     * authentication would invert its importance completely.
     */
    private static DeviceDescription device(HttpServletRequest http) {
        return DeviceDescription.fromUserAgent(http.getHeader("User-Agent")).orElse(null);
    }
}
