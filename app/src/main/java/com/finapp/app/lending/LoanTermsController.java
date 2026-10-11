package com.finapp.app.lending;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * The lending officer's terms doors (`P11-TSK-005`; ADR-0092 sections 1-3, PHASE_11_PLAN.md sections 9 and 11): loan
 * product terms versions under four eyes, each act behind {@code LENDING_ADMINISTER} and keyed per principal
 * ({@code lending.terms:<type>:<id>}); and the terms in force at an instant behind {@code LENDING_INVESTIGATE} - both
 * held by {@code LENDING_OFFICER}. Rates and fees are {@code RESTRICTED-FINANCIAL} and leave the platform only here, on
 * the operator surface.
 */
@RestController
@RequestMapping(path = "/operator/lending/terms", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class LoanTermsController {

    @NonNull private final LoanTermsDesk desk;

    /** Proposes a whole new terms version for a product - complete, judged, and frozen from this moment. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LENDING_ADMINISTER)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public LoanTermsDesk.LoanTermsReceipt proposeLoanTerms(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody LoanTermsProposalRequest body) {
        return desk.propose(idempotencyKey, body);
    }

    /** Activates a pending version - a different person's act; retires the product's predecessor. */
    @PostMapping(path = "/{versionId}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LENDING_ADMINISTER)
    @RequiresIdempotencyKey
    public LoanTermsDesk.LoanTermsReceipt approveLoanTerms(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("versionId") String versionId,
            @Valid @RequestBody LoanTermsDecisionRequest body) {
        return desk.approve(idempotencyKey, versionId, body.reason());
    }

    /** Rejects a pending version - anyone, the proposer's withdrawal included; a reason is required. */
    @PostMapping(path = "/{versionId}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LENDING_ADMINISTER)
    @RequiresIdempotencyKey
    public LoanTermsDesk.LoanTermsReceipt rejectLoanTerms(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("versionId") String versionId,
            @Valid @RequestBody LoanTermsDecisionRequest body) {
        return desk.reject(idempotencyKey, versionId, body.reason());
    }

    /**
     * The product's terms in force at {@code at} (an ISO-8601 instant) - answered from the effective periods alone; with
     * no {@code at}, the version {@code ACTIVE} now.
     */
    @GetMapping
    @RequiresPermission(PermissionName.LENDING_INVESTIGATE)
    public LoanTermsDesk.LoanTermsView readLoanTerms(
            @RequestParam("product") String product, @RequestParam(value = "at", required = false) String at) {
        return desk.activeAt(product, at);
    }

    // -----------------------------------------------------------------

    /**
     * A whole terms version for one product. Amounts are integer minor units of {@code currency}, which must be the
     * product's; ratios are basis points; the rate a decimal fraction string ({@code "0.0899"} is 8.99 % a year). The
     * origination fee is {@code originationFeeMinor} or {@code originationFeeBps}, exactly one. An instalment loan
     * states {@code minFirstPeriodDays}, {@code disbursementDeadlineHours} and {@code scheduleEngineVersion}; a credit
     * line states {@code minimumPaymentFloorMinor}, {@code minimumPaymentRatioBps}, {@code paymentDueDays} and
     * {@code statementEngineVersion} - each kind its own and no other's. Every value is judged by the domain
     * ({@code lending.TermsInvalid}).
     */
    @ClosedBody
    public record LoanTermsProposalRequest(
            @NotBlank @Size(min = 1, max = 32) String product,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotBlank @Size(min = 1, max = 16) String nominalAnnualRate,
            @NotBlank @Size(min = 1, max = 16) String rateType,
            Long originationFeeMinor,
            Integer originationFeeBps,
            @NotBlank @Size(min = 1, max = 16) String originationFeeTreatment,
            @NotNull Long lateFeeMinor,
            @NotNull Integer lateFeeDay,
            @NotNull Long lateFeeCapMinor,
            @NotNull Integer graceDays,
            @NotNull @Size(max = 8) List<@NotNull Integer> delinquencyBounds,
            @NotNull Integer defaultThresholdDays,
            @NotBlank @Size(min = 1, max = 32) String allocationSequence,
            @NotNull @Size(max = 8) List<@NotBlank @Size(min = 1, max = 16) String> allocationOrder,
            @NotBlank @Size(min = 1, max = 32) String overpaymentTreatment,
            @NotNull Boolean autoCollection,
            @NotNull Boolean partialCollection,
            @NotBlank @Size(min = 1, max = 16) String instalmentRounding,
            @NotBlank @Size(min = 1, max = 16) String interestRounding,
            @NotBlank @Size(min = 1, max = 16) String dayCount,
            @NotBlank @Size(min = 1, max = 16) String servicingZone,
            @NotNull @Size(max = 28) List<@NotNull Integer> allowedDays,
            Integer minFirstPeriodDays,
            @NotNull Integer offerValidityDays,
            @NotNull Integer payoffHorizonDays,
            Integer disbursementDeadlineHours,
            Long minimumPaymentFloorMinor,
            Integer minimumPaymentRatioBps,
            Integer paymentDueDays,
            Integer scheduleEngineVersion,
            @NotNull Integer accrualEngineVersion,
            @NotNull Integer allocationEngineVersion,
            Integer statementEngineVersion,
            @NotBlank @Size(min = 1, max = 64) String agreementTemplateId,
            @NotNull Integer agreementTemplateVersion,
            @Size(max = 1000) String reason) {}

    /** A decision's reason - required, judged by the domain ({@code lending.ReasonRequired}). */
    @ClosedBody
    public record LoanTermsDecisionRequest(@Size(max = 1000) String reason) {}
}
