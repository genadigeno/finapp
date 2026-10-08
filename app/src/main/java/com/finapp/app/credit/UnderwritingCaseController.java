package com.finapp.app.credit;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The underwriter's doors (`P10-TSK-018`; ADR-0089 point 8, PHASE_10_PLAN.md section 9): the review queue and the acts on
 * a referral's case - every route behind {@code CREDIT_UNDERWRITE}, held by {@code UNDERWRITER} alone, and every act keyed
 * per principal ({@code credit.review:EMPLOYEE:<id>}), synchronous. The queue serves each case's basis - the normalised
 * attributes and the rules - never raw evidence, and every serving is audited.
 */
@RestController
@RequestMapping(path = "/operator/credit/review-cases", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class UnderwritingCaseController {

    @NonNull private final UnderwritingCaseDesk desk;

    /** The queue, oldest first - every status, or only {@code status}. */
    @GetMapping
    @RequiresPermission(PermissionName.CREDIT_UNDERWRITE)
    public List<UnderwritingCaseDesk.ReviewCaseView> listReviewCases(
            @RequestParam(name = "status", required = false) String status) {
        return desk.queue(status);
    }

    /** Takes an open case - of two underwriters, one; the other {@code 409 credit.CaseTaken}. */
    @PostMapping(path = "/{id}/assignment")
    @RequiresPermission(PermissionName.CREDIT_UNDERWRITE)
    @RequiresIdempotencyKey
    public UnderwritingCaseDesk.ReviewCaseReceipt assignReviewCase(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey, @PathVariable("id") String id) {
        return desk.assign(idempotencyKey, id);
    }

    /** Releases a case its holder took back to the queue. */
    @PostMapping(path = "/{id}/release")
    @RequiresPermission(PermissionName.CREDIT_UNDERWRITE)
    @RequiresIdempotencyKey
    public UnderwritingCaseDesk.ReviewCaseReceipt releaseReviewCase(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey, @PathVariable("id") String id) {
        return desk.release(idempotencyKey, id);
    }

    /**
     * The holder's decision, with reason codes and a reason: recorded, or - an approval above the product's four-eyes
     * threshold - awaiting a second underwriter.
     */
    @PostMapping(path = "/{id}/decision", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.CREDIT_UNDERWRITE)
    @RequiresIdempotencyKey
    public UnderwritingCaseDesk.ReviewCaseReceipt decideReviewCase(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody ReviewDecisionBody body) {
        return desk.decide(idempotencyKey, id, body);
    }

    /** A different underwriter approves the first decision, or refuses it with a reason. */
    @PostMapping(path = "/{id}/second-approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.CREDIT_UNDERWRITE)
    @RequiresIdempotencyKey
    public UnderwritingCaseDesk.ReviewCaseReceipt secondApproveReviewCase(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody SecondApprovalBody body) {
        return desk.secondApproval(idempotencyKey, id, body);
    }

    // -----------------------------------------------------------------

    /**
     * A person's decision: {@code APPROVED} or {@code DECLINED}; an approval's amount as an exact decimal with its currency;
     * the catalogue's reason codes and a reason, both required ({@code credit.ReasonRequired}, judged by the domain).
     */
    @ClosedBody
    public record ReviewDecisionBody(
            @NotBlank @Size(max = 16) String outcome,
            @Size(max = 32) String approvedAmount,
            @Size(max = 3) String currency,
            @Size(max = 32) List<@NotBlank @Size(max = 64) String> reasonCodes,
            @Size(max = 1000) String reason) {}

    /** {@code APPROVE} or {@code REFUSE}; a refusal's reason required ({@code credit.ReasonRequired}). */
    @ClosedBody
    public record SecondApprovalBody(@NotBlank @Size(max = 16) String decision, @Size(max = 1000) String reason) {}
}
