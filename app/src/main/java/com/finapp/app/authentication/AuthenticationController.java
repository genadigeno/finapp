package com.finapp.app.authentication;

import com.finapp.identity.IdentityErrorCode;
import com.finapp.platform.api.ApiException;
import jakarta.validation.Valid;
import java.util.Objects;
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
 * <p>{@code 204} or {@code 401 identity.AuthenticationFailed}. Unknown identity, wrong password,
 * suspended identity and an identity that has no credential yet all produce the <strong>same
 * bytes</strong> - and, because {@code P1-TSK-008} makes every one of those paths perform a full
 * Argon2id verification, the same cost. {@code INV-IDN-07} is lost through either channel, and the
 * timing one is the harder half to notice.
 *
 * <h2>Why the success is 204 and not 200</h2>
 *
 * <p>Because there is nothing to return. {@code P1-TSK-010} declares {@code Deps: P1-TSK-013} -
 * session issuance - which has not landed, so a successful authentication issues no session and no
 * token. A {@code 200} with an empty object would publish a body shape that {@code P1-TSK-013} will
 * then have to change; {@code 204} says truthfully that there is no representation, and adding one
 * later is additive rather than breaking.
 *
 * <p>The consequence is recorded rather than concealed: <strong>this endpoint currently proves a
 * password and grants nothing.</strong> {@code PHASE_1_PLAN.md} §11's M1.2 acceptance requires a
 * session, so M1.2 cannot close on this task.
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
@RestController
@RequestMapping("/authentications")
class AuthenticationController {

    private final AuthenticationService authentications;

    AuthenticationController(AuthenticationService authentications) {
        this.authentications =
                Objects.requireNonNull(authentications, "authentications must not be null");
    }

    /**
     * Authenticates a person.
     *
     * <p>{@code @ResponseStatus} rather than a {@code ResponseEntity}, because only the annotation
     * reaches the published contract - the defect {@code P1-TSK-006} found when springdoc published
     * {@code "200": "OK"} for an endpoint that has never returned 200.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void authenticate(@Valid @RequestBody AuthenticationRequest request) {
        if (authentications.authenticate(request) != AuthenticationService.Outcome.AUTHENTICATED) {
            // One code, no detail beyond its title, for every reason an authentication can fail.
            // The message below is the LOG message; ApiException keeps it separate from what a
            // client is told, which is what makes the unsafe default unreachable (`P0-TSK-024`).
            throw new ApiException(
                    IdentityErrorCode.AUTHENTICATION_FAILED,
                    "Authentication refused; the audit record holds the attempted identifier");
        }
    }
}
