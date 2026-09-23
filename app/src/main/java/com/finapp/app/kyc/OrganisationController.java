package com.finapp.app.kyc;

import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import com.finapp.party.PartyName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registering the organisation the caller acts for (`P2-TSK-016`).
 *
 * <h2>Who may act for an organisation — the answer, at the boundary</h2>
 *
 * <p>An organisation holds no Identity and cannot log in, so the question the ADR-0031 split
 * trigger names has to be answered by a recorded fact. Phase 2's deliberate minimum: <strong>the
 * person who registers the organisation is its one acting person</strong> — recorded in
 * {@code party.organisation_registrant} by this endpoint, checked by every {@code /v1/me/kyb}
 * read and write through a statement-level predicate. Delegated access, multiple representatives
 * and registrant replacement are Phase 6+'s, recorded rather than implied.
 *
 * <h2>One 201, however many times the request arrived — and a 409 for a different one</h2>
 *
 * <p>"Register my organisation" twice is one intent, and the total one-per-registrant index is
 * the arbiter: a repeat with the same name converges on the same identifiers and <strong>replays
 * the original 201</strong> — the platform's convergence idiom (`P2-TSK-008`'s documents
 * endpoint, and the idempotency replay itself), and what keeps this endpoint at one success
 * code, which is the only kind the test-scope contract generator can publish honestly. What
 * distinguishes creation is not the status but the records: only the creating path audits and
 * announces. A repeat with a <em>different</em> name is refused (409) rather than silently
 * answered with the first organisation — the {@code INV-IDEM-03} shape. No idempotency key:
 * convergence keyed on the registrant is the stronger mechanism, because the client cannot
 * lose it.
 */
@RestController
@RequestMapping(path = "/me/organisations", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class OrganisationController {

    @NonNull private final KybService kyb;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public OrganisationResponse register(
            @Valid @RequestBody OrganisationRegistrationRequest body, HttpServletRequest request) {
        KybService.Registration outcome =
                kyb.register(current(request), partyName(body.name()))
                        .orElseThrow(OrganisationController::notResolvable);
        return switch (outcome) {
            case KybService.Registration.Registered created ->
                    new OrganisationResponse(
                            created.organisation().value().toString(),
                            created.customer().value().toString());
            case KybService.Registration.Converged existing ->
                    // The original answer, replayed - see the class javadoc.
                    new OrganisationResponse(
                            existing.organisation().value().toString(),
                            existing.customer().value().toString());
            case KybService.Registration.NameConflict ignored ->
                    // The caller's OWN organisation; the stored name is deliberately not
                    // echoed - the conflict says "different", never "different from what".
                    throw new ApiException(
                            PlatformErrorCode.CONFLICT,
                            "An organisation registration repeated under a different name",
                            "you already registered an organisation under a different name");
        };
    }

    // -----------------------------------------------------------------

    /** The `P1-TSK-026` mapping: the client detail is written here, never passed through. */
    private static PartyName partyName(String name) {
        try {
            return new PartyName(name);
        } catch (IllegalArgumentException refused) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "An organisation registration carried a name PartyName refuses",
                    "name must be printable text of at most 200 characters");
        }
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No proven session on a request that reached a @RequiresSession handler");
    }

    private static ApiException notResolvable() {
        // The MeController shape: a proven session resolving to no Identity is a data defect,
        // never producible by registration - logged as ours, because a caller cannot fix it.
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "A proven session resolved to no identity; registration should make this"
                        + " impossible");
    }

    /**
     * The body. The name bounds mirror {@code PartyName}, {@code ProfileUpdateRequest}'s
     * reasoning verbatim: a boundary wider than the domain turns a caller's input into our 500,
     * and one narrower is a limit nobody chose.
     *
     * @param name the organisation's name. {@code RESTRICTED-PII} column content
     */
    public record OrganisationRegistrationRequest(
            @NotBlank
                    @Size(min = 1, max = 200)
                    @Pattern(regexp = "[^\\p{Cc}\\p{Cf}\\p{Cs}\\p{Co}\\p{Cn}]+")
                    String name) {}

    /**
     * The caller's own organisation's identifiers — theirs to hold, unlike registration's empty
     * body, whose stranger-replay reasoning does not apply to an authenticated owner.
     */
    public record OrganisationResponse(String organisationId, String customerId) {}
}
