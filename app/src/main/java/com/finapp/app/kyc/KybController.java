package com.finapp.app.kyc;

import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import com.finapp.kyc.BeneficialOwnerStore;
import com.finapp.kyc.ControlRole;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycErrorCode;
import com.finapp.party.PartyId;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The acting person's KYB surface (`P2-TSK-016`): the graph, and growing it.
 *
 * <h2>No identifier names a resource, anywhere</h2>
 *
 * <p>The {@code /v1/me} shape: the organisation, its customer and its case are derived from the
 * proven session ({@link KybService}), so a stranger cannot declare owners onto another's case —
 * they have nothing to name it with, and their own chain resolves to a 404. The one
 * request-supplied identifier, {@code ownerPartyId}, names the declaration's <em>subject</em>,
 * never the resource acted on.
 *
 * <h2>The view is shaped, and the shaping is the security control</h2>
 *
 * <p>{@code IN_REVIEW} renders as {@code IN_PROGRESS} — a screening hit on the organisation must
 * be indistinguishable from ordinary processing (tipping-off; the plan's §6 rule, {@code
 * INV-IDN-07}'s reasoning) — and an owner's verification appears only as a {@code pending}
 * boolean: never its status, never its outcome, never its case identifier. The organisation
 * learns which declarations still block readiness, and nothing about anybody's screening.
 *
 * <h2>Owner-ineligibility is one refusal</h2>
 *
 * <p>Unknown party, organisation party and unregistered person are one {@code 422
 * kyc.OwnerNotEligible} — distinguishing them would make this endpoint an oracle over third
 * parties' registrations. A malformed or non-v7 {@code ownerPartyId} lands on the same refusal,
 * the malformed-equals-absent rule (`P1-TSK-016`), because a value that can name nobody is just
 * another party that cannot be declared.
 */
@RestController
@RequestMapping(path = "/me/kyb", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
public class KybController {

    private final KybService kyb;

    public KybController(KybService kyb) {
        this.kyb = Objects.requireNonNull(kyb, "kyb must not be null");
    }

    /** The acting person's view of their organisation's case and ownership graph. */
    @GetMapping
    public KybCaseResponse view(HttpServletRequest request) {
        return kyb.view(current(request))
                .map(KybCaseResponse::of)
                .orElseThrow(KybController::noOrganisation);
    }

    /** Declares a beneficial owner onto the caller's organisation's case. */
    @PostMapping(path = "/owners", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public void declareOwner(
            @Valid @RequestBody OwnerDeclarationRequest body, HttpServletRequest request) {
        if (body.stakeBasisPoints() == null && body.controlRole() == null) {
            // The qualification invariant, told at the boundary rather than thrown from the
            // aggregate as a 500: an owner with neither a stake nor a control role is not one.
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "An owner declaration carried neither a stake nor a control role",
                    "either stakeBasisPoints or controlRole is required");
        }
        OwnerDeclaration.Declaration outcome =
                kyb.declareOwner(
                                current(request),
                                ownerParty(body.ownerPartyId()),
                                body.stakeBasisPoints() == null
                                        ? OptionalInt.empty()
                                        : OptionalInt.of(body.stakeBasisPoints()),
                                controlRole(body.controlRole()))
                        .orElseThrow(KybController::noOrganisation);
        switch (outcome) {
            case DECLARED -> {
                // The only path that returns.
            }
            case ALREADY_DECLARED ->
                    throw new ApiException(
                            KycErrorCode.OWNER_ALREADY_DECLARED,
                            "An owner declaration repeated for a party already on the graph");
            case CASE_NOT_ACCEPTING_OWNERS ->
                    throw new ApiException(
                            KycErrorCode.CASE_NOT_ACCEPTING_OWNERS,
                            "An owner declaration arrived after the case's owner set froze");
            case STAKE_EXCEEDS_WHOLE ->
                    throw new ApiException(
                            KycErrorCode.STAKE_EXCEEDS_WHOLE,
                            "An owner declaration would take the declared stakes past the whole");
            case OWNER_NOT_A_NATURAL_PERSON, OWNER_NOT_VERIFIABLE ->
                    // One refusal for every cause - see the class javadoc.
                    throw notEligible();
            case NOT_FOUND, NOT_A_KYB_CASE ->
                    // Unreachable through the derived chain: the case was just read off the
                    // organisation customer this transaction resolved, and an organisation's
                    // case is KYB by construction (the composite FK). A defect, logged as ours.
                    throw new ApiException(
                            PlatformErrorCode.NOT_FOUND,
                            "A derived organisation case was missing or not KYB - the chain"
                                    + " should make this impossible");
        }
    }

    // -----------------------------------------------------------------

    private static PartyId ownerParty(String raw) {
        try {
            return PartyId.of(raw);
        } catch (IllegalArgumentException malformed) {
            // Malformed and ineligible are one answer: a value that names nobody is just
            // another party that cannot be declared (the malformed-equals-absent rule).
            throw notEligible();
        }
    }

    private static Optional<ControlRole> controlRole(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ControlRole.valueOf(raw));
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "An owner declaration carried an unknown control role",
                    "controlRole must be one of DIRECTOR, SENIOR_MANAGING_OFFICIAL, TRUSTEE");
        }
    }

    private static ApiException notEligible() {
        return new ApiException(
                KycErrorCode.OWNER_NOT_ELIGIBLE,
                "An owner declaration named a party that cannot be declared");
    }

    private static ApiException noOrganisation() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "A KYB request from a session whose party registered no organisation");
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No proven session on a request that reached a @RequiresSession handler");
    }

    /**
     * The declaration body.
     *
     * @param ownerPartyId the party being declared — the request's one identifier, and it names
     *     the declaration's subject, never a resource of the caller's
     * @param stakeBasisPoints the ownership stake in basis points, if equity is the
     *     qualification
     * @param controlRole the control qualification, if control is
     */
    public record OwnerDeclarationRequest(
            @NotBlank String ownerPartyId,
            @Min(1) @Max(10_000) Integer stakeBasisPoints,
            String controlRole) {}

    /** The shaped view — see the class javadoc for what is deliberately absent. */
    public record KybCaseResponse(String status, List<OwnerView> owners) {

        static KybCaseResponse of(KybService.KybFile file) {
            return new KybCaseResponse(
                    shaped(file.kybCase().status()),
                    file.owners().stream().map(OwnerView::of).toList());
        }

        /**
         * The customer-facing status vocabulary. {@code IN_PROGRESS} covers checks <em>and</em>
         * review, deliberately: which of the two a case is in is exactly what tipping-off
         * forbids disclosing.
         */
        private static String shaped(KycCaseStatus status) {
            return switch (status) {
                case OPEN -> "OPEN";
                case CHECKS_IN_PROGRESS, IN_REVIEW -> "IN_PROGRESS";
                case READY_FOR_DECISION -> "PENDING_DECISION";
                case APPROVED -> "APPROVED";
                case REJECTED -> "REJECTED";
            };
        }
    }

    /** An owner as the organisation may see it: the declaration, and a pending boolean. */
    public record OwnerView(
            String ownerPartyId,
            Integer stakeBasisPoints,
            String controlRole,
            Instant declaredAt,
            boolean verificationPending) {

        static OwnerView of(BeneficialOwnerStore.DeclaredOwner declared) {
            return new OwnerView(
                    declared.owner().ownerPartyId().toString(),
                    declared.owner().stakeBasisPoints().isPresent()
                            ? declared.owner().stakeBasisPoints().getAsInt()
                            : null,
                    declared.owner().controlRole().map(Enum::name).orElse(null),
                    declared.owner().declaredAt(),
                    // Pending = not yet ANSWERED. The boolean is the whole disclosure: never
                    // the status, never the outcome, never the verification case identifier.
                    !declared.verificationStatus().isTerminal());
        }
    }
}
