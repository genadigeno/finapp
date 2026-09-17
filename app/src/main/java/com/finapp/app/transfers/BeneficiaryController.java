package com.finapp.app.transfers;

import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.transfers.BeneficiaryId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Saved transfer destinations over HTTP (`P4-TSK-007`): create under the conditional second
 * factor, list, remove.
 *
 * <h2>The step-up point</h2>
 *
 * <p>Creating a destination is where an account takeover monetises (plan §11), so the creation
 * demands {@code MULTI_FACTOR} <strong>of an identity that has a factor</strong> — a domain
 * check in {@link BeneficiaryService}, not an annotation here, because the requirement is
 * conditional on enrolment and a static annotation would lock out every password-only customer
 * (`P1-TSK-033`'s pattern). The class-level rule is {@link RequiresSession}: authentication is
 * the boundary's half, the conditional assurance the domain's.
 *
 * <h2>Ownership</h2>
 *
 * <p>The create and the list take <strong>no identifier of anything the caller owns</strong>
 * ({@code destinationAccountId} names a third party's product — the declaration's subject,
 * never a resource of the caller's). The removal takes the surface's one path identifier, and
 * its ownership lives in the store's {@code WHERE} clause: unknown, not-yours and malformed
 * are one {@code 404} (the {@code P1-TSK-016} reasoning), while the caller's own
 * already-removed row converges on {@code 204}.
 */
@RestController
@RequestMapping(path = "/beneficiaries", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
public class BeneficiaryController {

    private final BeneficiaryService beneficiaries;

    public BeneficiaryController(BeneficiaryService beneficiaries) {
        this.beneficiaries = Objects.requireNonNull(beneficiaries, "beneficiaries must not be null");
    }

    /**
     * Saves a destination, or converges on the caller's live row for it — {@code 201} either
     * way (the convergence idiom, `P2-TSK-016`): one intent, answered with the creation's own
     * status; what distinguishes creation is the records, never the answer. No idempotency
     * key: saving a destination moves no money, and the natural-key convergence is the retry
     * mechanism.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public BeneficiaryService.BeneficiaryView create(
            @Valid @RequestBody BeneficiaryCreateRequest body, HttpServletRequest request) {
        return beneficiaries.create(
                current(request), body.displayName(), body.destinationAccountId());
    }

    /** The caller's live beneficiaries, oldest first. */
    @GetMapping
    public List<BeneficiaryService.BeneficiaryView> list(HttpServletRequest request) {
        return beneficiaries.list(current(request));
    }

    /**
     * Removes the caller's saved destination — {@code 204} for the converged repeat as well as
     * the removal (a retried {@code DELETE} whose first response was lost must not read as a
     * failure; the account close's shape). The removed row survives as evidence
     * (`P4-TSK-006`).
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable("id") String id, HttpServletRequest request) {
        if (!beneficiaries.delete(current(request), parsedOrAbsent(id))) {
            throw beneficiaryNotFound();
        }
    }

    // -----------------------------------------------------------------

    /**
     * A malformed identifier is treated as an identifier that names nothing — one {@code 404}
     * with unknown and not-yours ({@code P1-TSK-016}; malformed-equals-absent).
     */
    private static BeneficiaryId parsedOrAbsent(String raw) {
        try {
            return BeneficiaryId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw beneficiaryNotFound();
        }
    }

    private static ApiException beneficiaryNotFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "No beneficiary of the caller's matches the requested identifier",
                "no such beneficiary");
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/beneficiaries is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }
}
