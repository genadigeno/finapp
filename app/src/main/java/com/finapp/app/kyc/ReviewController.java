package com.finapp.app.kyc;

import com.finapp.app.session.RequiresPermission;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.IdentityId;
import com.finapp.identity.PermissionName;
import com.finapp.identity.Session;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.ReviewTask;
import com.finapp.kyc.ReviewTaskId;
import com.finapp.kyc.VerificationCheck;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reviewer endpoints (`P2-TSK-012`, plan §7): the phase's privileged surface.
 *
 * <h2>Both behind {@code @RequiresPermission(KYC_REVIEW)}, and the not-self rule is inapplicable</h2>
 *
 * <p>The subject is a <em>case</em>, not a person's identity row — the plan says so in as many
 * words — so unlike the administrative endpoints there is no self-loop to refuse: a reviewer
 * cannot be a KYC case. What replaces the ownership predicate is the {@code ADMINISTERED}
 * shape ({@code OwnershipIsScopedTest} carries the entries): the permission at the boundary,
 * the audited read ({@code kyc.CaseRead} — the reviewer is the insider surface), and every
 * write being a conditional transition whose losing branch changes nothing.
 *
 * <h2>Existence is disclosed, deliberately</h2>
 *
 * <p>A {@code 404} for a case that does not exist tells a <strong>proven reviewer</strong> it
 * does not exist — the `P1-TSK-028` reasoning: {@code INV-IDN-07} governs what a stranger can
 * learn, and refusing to tell a reviewer whether a case exists would make the surface unusable
 * and protect nobody. A malformed identifier, an unknown one, and another case's task are one
 * uniform 404: the composite URL names a resource that does not exist.
 *
 * <h2>What the read returns — references, never content</h2>
 *
 * <p>Checks and tasks are identifiers, types, statuses and the resolutions reviewers themselves
 * wrote. Screening evidence and document content stay behind their encrypted stores; the plan
 * declares no content endpoint for reviewers, and none is invented here (the `P1-TSK-028`
 * don't-invent-surfaces rule) — {@code DocumentAccess} keeps its audited path with no HTTP
 * caller, recorded as the remainder it is.
 */
@RestController
@RequestMapping(path = "/kyc/cases", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ReviewController {

    @NonNull private final ReviewService reviews;
    @NonNull private final DecisionRecording decisions;

    /** The reviewer's view of one case. Reading it is on the record ({@code kyc.CaseRead}). */
    @GetMapping("/{id}")
    @RequiresPermission(PermissionName.KYC_REVIEW)
    public CaseFileResponse readCase(@PathVariable String id) {
        ReviewService.CaseFile file =
                reviews.readCase(caseId(id))
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                PlatformErrorCode.NOT_FOUND,
                                                "No KYC case matched the given identifier"));
        return CaseFileResponse.of(file);
    }

    /**
     * Resolves one review task: the judgement a non-clean check owed a person
     * ({@code INV-KYC-04}), with its reason, audibly, exactly once.
     */
    @PostMapping("/{id}/reviews/{taskId}/resolution")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(PermissionName.KYC_REVIEW)
    public void resolve(
            @PathVariable String id,
            @PathVariable String taskId,
            @Valid @RequestBody ResolutionRequest body,
            HttpServletRequest request) {

        ReviewService.Resolution outcome =
                reviews.resolve(caseId(id), taskId(taskId), reviewer(request), body.reason());

        switch (outcome) {
            case RESOLVED -> {
                // The only path that returns.
            }
            case ALREADY_RESOLVED ->
                    // 409, not 204: reporting success would tell this reviewer their judgement
                    // was recorded when somebody else's was - and the difference matters when
                    // two people are working one queue (the NOT_ACTIVE suspension reasoning).
                    // The original resolution is untouched; a wrong one is a new review event,
                    // never an edit (INV-LIFE-04).
                    throw new ApiException(
                            PlatformErrorCode.CONFLICT,
                            "The review task was already resolved when this resolution arrived",
                            "this task is already resolved");
            case NOT_FOUND ->
                    throw new ApiException(
                            PlatformErrorCode.NOT_FOUND,
                            "No review task matched the given case and task identifiers");
        }
    }

    /**
     * Records the one decision this case will ever get (`P2-TSK-013`, {@code INV-KYC-02}).
     *
     * <p>The two 409 details are named for what is checked, not the commonest cause (the
     * {@code NOT_ACTIVE} lesson): a terminal case is <em>already decided</em> — a retry after a
     * lost response lands here, honestly — while a case still in checks or review is <em>not
     * ready</em>, and those call for different reviewer behaviour (stop, versus wait).
     */
    @PostMapping("/{id}/decision")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(PermissionName.KYC_REVIEW)
    public void decide(
            @PathVariable String id,
            @Valid @RequestBody DecisionRequest body,
            HttpServletRequest request) {

        DecisionRecording.Recording outcome =
                decisions.byReviewer(
                        caseId(id), reviewer(request), body.outcome(), body.reason());

        switch (outcome) {
            case RECORDED -> {
                // The only path that returns.
            }
            case ALREADY_DECIDED ->
                    // The original decision is untouched; a wrong one is a NEW case, never an
                    // edit (INV-LIFE-04, INV-KYC-02).
                    throw new ApiException(
                            PlatformErrorCode.CONFLICT,
                            "The case was already decided when this decision arrived",
                            "this case is already decided");
            case NOT_READY ->
                    throw new ApiException(
                            PlatformErrorCode.CONFLICT,
                            "The case is not awaiting a decision",
                            "this case is not ready for a decision");
            case NOT_FOUND ->
                    throw new ApiException(
                            PlatformErrorCode.NOT_FOUND,
                            "No KYC case matched the given identifier");
        }
    }

    // -----------------------------------------------------------------

    /** The reviewer's response shape: the case flat, its checks, tasks and owners as references. */
    public record CaseFileResponse(
            UUID id,
            UUID customerId,
            String status,
            String policyVersion,
            Instant openedAt,
            List<CheckView> checks,
            List<TaskView> reviewTasks,
            List<OwnerView> owners) {

        static CaseFileResponse of(ReviewService.CaseFile file) {
            return new CaseFileResponse(
                    file.kycCase().id().value(),
                    file.kycCase().customerId(),
                    file.kycCase().status().name(),
                    file.kycCase().policyVersion().value(),
                    file.kycCase().openedAt(),
                    file.checks().stream().map(CheckView::of).toList(),
                    file.tasks().stream().map(TaskView::of).toList(),
                    file.owners().stream().map(OwnerView::of).toList());
        }
    }

    /**
     * A declared owner, whole (`P2-TSK-016`): the reviewer sees the verification case and its
     * real status, because the owner rows are the KYB decision's evidence ({@code INV-KYC-02})
     * and the reviewer is the person who must defend it — the shaping the acting person's view
     * applies would blind exactly the reader it exists to inform.
     */
    public record OwnerView(
            UUID ownerPartyId,
            Integer stakeBasisPoints,
            String controlRole,
            Instant declaredAt,
            UUID verificationCaseId,
            String verificationStatus) {
        static OwnerView of(com.finapp.kyc.BeneficialOwnerStore.DeclaredOwner declared) {
            return new OwnerView(
                    declared.owner().ownerPartyId(),
                    declared.owner().stakeBasisPoints().isPresent()
                            ? declared.owner().stakeBasisPoints().getAsInt()
                            : null,
                    declared.owner().controlRole().map(Enum::name).orElse(null),
                    declared.owner().declaredAt(),
                    declared.owner().verificationCaseId().value(),
                    declared.verificationStatus().name());
        }
    }

    /** A check as a reference: the id IS the evidence reference; content stays encrypted. */
    public record CheckView(UUID id, String type, String status, Instant requestedAt) {
        static CheckView of(VerificationCheck check) {
            return new CheckView(
                    check.id().value(),
                    check.type().name(),
                    check.status().name(),
                    check.requestedAt());
        }
    }

    /** A task with its resolution — prose reviewers themselves wrote, shown only to reviewers. */
    public record TaskView(
            UUID id,
            UUID checkId,
            String status,
            Instant openedAt,
            UUID resolvedBy,
            Instant resolvedAt,
            String resolutionReason) {
        static TaskView of(ReviewTask task) {
            return new TaskView(
                    task.id().value(),
                    task.checkId().value(),
                    task.status().name(),
                    task.openedAt(),
                    task.resolution().map(ReviewTask.Resolution::resolvedBy).orElse(null),
                    task.resolution().map(ReviewTask.Resolution::resolvedAt).orElse(null),
                    task.resolution().map(ReviewTask.Resolution::reason).orElse(null));
        }
    }

    // -----------------------------------------------------------------

    private static KycCaseId caseId(String id) {
        try {
            return KycCaseId.of(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            // Malformed and absent are one answer (a v4 lands here too - EntityId validates
            // UUIDv7, the recurring trap answered at the boundary).
            throw new ApiException(
                    PlatformErrorCode.NOT_FOUND, "A malformed case identifier was presented");
        }
    }

    private static ReviewTaskId taskId(String id) {
        try {
            return ReviewTaskId.of(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    PlatformErrorCode.NOT_FOUND, "A malformed task identifier was presented");
        }
    }

    private static IdentityId reviewer(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated.identityId();
        }
        // Unreachable while the interceptor is registered and @RequiresPermission implies
        // @RequiresSession. A refusal rather than an assumption, because a resolution without a
        // proven reviewer would attribute the judgement to nobody - permanently (INV-HIST-03).
        throw new IllegalStateException(
                "No authenticated session on the request: a reviewer handler is reachable"
                        + " without SessionAuthenticationInterceptor having run");
    }
}
