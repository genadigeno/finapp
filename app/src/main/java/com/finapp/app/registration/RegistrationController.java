package com.finapp.app.registration;

import com.finapp.party.PartyErrorCode;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/registrations} - the platform's first endpoint (`P1-TSK-006`).
 *
 * <h2>The response body is empty, and that is a security decision</h2>
 *
 * <p>{@code API_CONVENTIONS.md} §6 states plainly that the idempotency key <strong>is not a secret
 * and is not redacted</strong>. So anyone who has seen a key - from a proxy log, an access log, a
 * client's own logging - can replay this unauthenticated endpoint and receive whatever it returns.
 * Publishing the Party, Customer and Identity identifiers here would hand a stranger three
 * identifiers belonging to somebody else.
 *
 * <p>Nothing in Phase 1's API surface consumes them: after registering, the next call is
 * authentication, and {@code GET /v1/me} is where an identifier should come from once there is
 * somebody to authorise. The cost - a server-side integrator cannot link its record to ours until
 * it authenticates - is real and is smaller than the disclosure.
 *
 * <h2>No replay indication</h2>
 *
 * <p>A {@code 201} for a fresh registration and a {@code 201} for a replay are indistinguishable,
 * with no header and no body difference. Telling the caller which one it got would tell a replaying
 * stranger that the login identifier exists, which is exactly the oracle {@code INV-IDN-07}
 * forbids.
 *
 * <h2>The version prefix is not here</h2>
 *
 * <p>{@code /registrations}, not {@code /v1/registrations}. {@code ApiVersionConfiguration} applies
 * the prefix once in the composition root, because a prefix repeated in every mapping is one that
 * somebody eventually omits - and an unversioned route can never be changed, since there is no
 * second version to move its clients to.
 */
@com.finapp.app.session.Unauthenticated
@RestController
@RequestMapping("/registrations")
class RegistrationController {

    private final RegistrationService registrations;

    RegistrationController(RegistrationService registrations) {
        this.registrations = Objects.requireNonNull(registrations, "registrations must not be null");
    }

    /**
     * Registers a person.
     *
     * <p><strong>{@code @ResponseStatus} rather than a {@code ResponseEntity}.</strong> Both produce
     * 201 at run time; only the annotation reaches the <em>published contract</em>. springdoc reads
     * the declared status and, given a {@code ResponseEntity}, has nothing to read - so the first
     * version of this method published {@code "200": "OK"} for an endpoint that has never returned
     * 200. A generated client would have treated the real response as unexpected.
     *
     * <h3>The required password is a {@code BREAKING} change, accepted</h3>
     *
     * <p>{@code P1-TSK-026} added a required {@code password}, which breaks any client written
     * against the body this endpoint published first. The classifier says so, the diff was reviewed
     * line by line, and it was accepted rather than versioned around: nothing consumes this API,
     * and the alternative is a {@code /v2} for an endpoint whose first version was never usable -
     * you could register and then never log in (ADR-0015, and {@code P1-TSK-027}'s precedent for
     * the same judgement on {@code 204} to {@code 201}).
     *
     * <p>{@link RequiresIdempotencyKey} makes the header mandatory, enforced by an interceptor
     * <strong>before this method is entered</strong>. Registration moves no money, so
     * {@code INV-IDEM-01}'s money-moving clause is vacuous here - and it is made idempotent anyway,
     * because a retried registration that creates a second Party is a duplicate person, which is
     * expensive in a different way and unpickable later for the same reason ADR-0029 gives.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresIdempotencyKey
    void register(
            @Valid @RequestBody RegistrationRequest request,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey) {

        RegistrationService.Outcome outcome = registrations.register(request, idempotencyKey);
        if (!outcome.accepted()) {
            // One code for every reason a registration can be refused. The commonest is that the
            // login identifier is taken, and saying so would make this endpoint an
            // account-existence oracle (INV-IDN-07). No detail beyond the code's own title, so a
            // collision and an unrelated refusal are byte-identical.
            throw new ApiException(
                    PartyErrorCode.REGISTRATION_REFUSED,
                    "Registration refused; the audit record holds the attempted identifier");
        }
    }
}
