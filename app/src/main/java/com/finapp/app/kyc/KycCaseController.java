package com.finapp.app.kyc;

import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import com.finapp.kyc.KycCase;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The person's own verification case (`P2-TSK-006`): {@code POST /v1/me/kyc} ensures it exists,
 * {@code GET /v1/me/kyc} reads its status.
 *
 * <h2>No identifier, anywhere in either request</h2>
 *
 * <p>No path variable, no query parameter, no body at all — the {@code /v1/me} shape
 * (`P1-TSK-030`): the chain is derived from the proven session ({@link KycCaseService}), so an
 * attacker has nothing to point at somebody else's case, and the POST declares no request
 * schema for the credential-sink pinned set to grow by.
 *
 * <h2>The convergence idiom: 201, however many times the request arrived</h2>
 *
 * <p>"Ensure my case exists" makes a first POST, a double-tap and a retry one intent, so all of
 * them answer the creation's own {@code 201} ({@code KycDocumentController} and
 * {@code OrganisationController}'s recorded idiom — and the {@code P1-TSK-006} trap: a
 * {@code ResponseEntity} with a hand-picked status would publish {@code "200"} alone in the
 * generated contract). What distinguishes creation is the records, not the answer: only the
 * creating call audits and announces.
 *
 * <h2>The status crosses the boundary shaped</h2>
 *
 * <p>{@code CustomerFacingCaseStatus}: a screening hit is indistinguishable from ordinary
 * processing ({@code IN_PROGRESS} covers both), and the response carries no screening
 * vocabulary at all — the task's stated security half.
 *
 * <h2>The gate's refusal becomes a client answer here</h2>
 *
 * <p>{@code ConsentNotGrantedException} propagates to {@code ApiErrorHandler} and renders as
 * {@code 409 consent.ConsentRequired} — declared by this surface, the one that shapes it
 * (`P2-TSK-019`'s deferral, honoured). The transaction rolled back, so the refusal wrote
 * nothing.
 */
@RestController
@RequestMapping(path = "/me/kyc", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class KycCaseController {

    @NonNull private final KycCaseService cases;

    /** Ensures the caller's case exists — the first consent-gated capability over HTTP. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KycCaseResponse open(HttpServletRequest request) {
        return switch (cases.open(current(request))) {
            case KycCaseService.Opening.Opened opened -> KycCaseResponse.of(opened.kycCase());
            case KycCaseService.Opening.NotResolvable ignored -> throw notResolvable();
        };
    }

    /**
     * The caller's latest case — open or decided — or an honest 404 if they never had one.
     *
     * <p>Named {@code viewCase} rather than {@code view} because the method name is the
     * published {@code operationId}: a second {@code view} made springdoc rename the KYB
     * surface's existing operation to {@code view_1} — a breaking rename of a published
     * operation, caught by the contract classifier and withdrawn rather than accepted.
     */
    @GetMapping
    public KycCaseResponse viewCase(HttpServletRequest request) {
        return switch (cases.view(current(request))) {
            case KycCaseService.View.Found found -> KycCaseResponse.of(found.kycCase());
            case KycCaseService.View.NoCase ignored ->
                    throw new ApiException(
                            PlatformErrorCode.NOT_FOUND,
                            "A case view from a customer who never had a case; POST is the"
                                    + " remedy",
                            "no verification case exists for this session");
            case KycCaseService.View.NotResolvable ignored -> throw notResolvable();
        };
    }

    // -----------------------------------------------------------------

    private static ApiException notResolvable() {
        // The MeController not-resolvable case: a data defect, never producible by
        // registration, logged as ours because a customer cannot fix it.
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "A proven session resolved to no live customer; registration should make this"
                        + " impossible",
                "no verification case exists for this session");
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        // Unreachable while the interceptor is registered and the class carries @RequiresSession.
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/me/kyc is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }

    /**
     * What both verbs publish: the shaped status, and nothing else — no case identifier
     * (nothing customer-facing consumes one, the {@code P1-TSK-006} empty-body reasoning), no
     * screening vocabulary, no timestamps a caller did not ask for.
     */
    public record KycCaseResponse(String status) {

        static KycCaseResponse of(KycCase kycCase) {
            return new KycCaseResponse(CustomerFacingCaseStatus.of(kycCase.status()));
        }
    }
}
