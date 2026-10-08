package com.finapp.app.credit;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The credit policy administrator's doors (`P10-TSK-012`; ADR-0086 sections 4-6, PHASE_10_PLAN.md section 11): policy
 * versions under four eyes, each act behind {@code CREDIT_POLICY_ADMINISTER} and keyed per principal; and the policy in
 * force at an instant behind {@code CREDIT_INVESTIGATE} - both held by {@code CREDIT_POLICY_OFFICER}. Thresholds are
 * {@code CONFIDENTIAL} and leave the platform only here, on the operator surface.
 */
@RestController
@RequestMapping(path = "/operator/credit/policies", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class CreditPolicyController {

    @NonNull private final CreditPolicyDesk desk;

    /** Proposes a whole new version for a product - complete, its rules born with it and frozen. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.CREDIT_POLICY_ADMINISTER)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public CreditPolicyDesk.CreditPolicyReceipt proposeCreditPolicy(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody PolicyProposalRequest body) {
        return desk.propose(idempotencyKey, body);
    }

    /** Activates a pending version - a different person's act; retires the product's predecessor. */
    @PostMapping(path = "/{versionId}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.CREDIT_POLICY_ADMINISTER)
    @RequiresIdempotencyKey
    public CreditPolicyDesk.CreditPolicyReceipt approveCreditPolicy(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("versionId") String versionId,
            @Valid @RequestBody PolicyDecisionRequest body) {
        return desk.approve(idempotencyKey, versionId, body.reason());
    }

    /** Rejects a pending version - anyone, the proposer's withdrawal included; a reason is required. */
    @PostMapping(path = "/{versionId}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.CREDIT_POLICY_ADMINISTER)
    @RequiresIdempotencyKey
    public CreditPolicyDesk.CreditPolicyReceipt rejectCreditPolicy(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("versionId") String versionId,
            @Valid @RequestBody PolicyDecisionRequest body) {
        return desk.reject(idempotencyKey, versionId, body.reason());
    }

    /**
     * The product's policy in force at {@code at} (an ISO-8601 instant) - answered from the effective periods alone; with
     * no {@code at}, the version {@code ACTIVE} now.
     */
    @GetMapping
    @RequiresPermission(PermissionName.CREDIT_INVESTIGATE)
    public CreditPolicyDesk.CreditPolicyView readCreditPolicy(
            @RequestParam("product") String product, @RequestParam(value = "at", required = false) String at) {
        return desk.activeAt(product, at);
    }

    // -----------------------------------------------------------------

    /**
     * A whole policy: the product and its parameters - every amount integer minor units of {@code currency}, which must
     * be the product's, every rate basis points - the source kinds it reads with their maximum data age, and its rules
     * in order, with the proposer's reason.
     */
    @ClosedBody
    public record PolicyProposalRequest(
            @NotBlank @Size(min = 1, max = 32) String product,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotNull Integer assessmentRateBps,
            @NotNull Long minimumDisposableMinor,
            @NotNull Integer minimumPaymentRatioBps,
            @NotNull Long maximumExposureMinor,
            @NotNull @Size(max = 8) List<@Valid @NotNull CreditPolicySourceRequest> sources,
            @NotBlank @Size(min = 1, max = 16) String unavailableFallback,
            @NotNull Long autoApprovalCeilingMinor,
            @NotEmpty @Size(max = 100) List<@Valid @NotNull CreditPolicyRuleRequest> rules,
            @Size(max = 1000) String reason) {}

    /** A source kind the policy reads, and the oldest data it accepts from it. */
    @ClosedBody
    public record CreditPolicySourceRequest(
            @NotBlank @Size(min = 1, max = 32) String kind, @NotNull Integer maximumDataAgeSeconds) {}

    /**
     * One rule: what it reads ({@code ATTRIBUTE} or {@code FIGURE} and the name), the operator, at most one operand -
     * an integer, money in minor units, a boolean, or codes - the effect, a {@code CAP_AMOUNT}'s ceiling in minor
     * units, and the catalogued reason code.
     */
    @ClosedBody
    public record CreditPolicyRuleRequest(
            @NotBlank @Size(min = 1, max = 64) String ruleCode,
            @NotBlank @Size(min = 1, max = 16) String subjectKind,
            @NotBlank @Size(min = 1, max = 64) String subject,
            @NotBlank @Size(min = 1, max = 16) String operator,
            Long integerOperand,
            Long moneyOperandMinor,
            Boolean booleanOperand,
            @Size(max = 64) List<@NotBlank @Size(min = 1, max = 64) String> codesOperand,
            @NotBlank @Size(min = 1, max = 16) String effect,
            Long capMinor,
            @NotBlank @Size(min = 1, max = 64) String reasonCode) {}

    /** A decision's reason - required, judged by the domain ({@code credit.ReasonRequired}). */
    @ClosedBody
    public record PolicyDecisionRequest(@Size(max = 1000) String reason) {}
}
