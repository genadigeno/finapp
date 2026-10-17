package com.finapp.app.bnpl;

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
 * The BNPL officer's terms doors (`P12-TSK-004`; ADR-0102 decision 4, {@code PHASE_12_PLAN.md} sections 9 and 11,
 * {@code BNPL_LIFECYCLES.md} section 4.1): BNPL product terms versions under four eyes, each act keyed per principal
 * ({@code bnpl.terms:<type>:<id>}), and the reads - the version in force at an instant, and any version by id (what the
 * second person reviews before deciding) - every route behind {@code BNPL_ADMINISTER}, held by {@code BNPL_OFFICER}.
 * Rates and fees are {@code RESTRICTED-FINANCIAL} and leave the platform only here, on the operator surface; reasons are
 * {@code RESTRICTED-PII} and never served.
 */
@RestController
@RequestMapping(path = "/operator/bnpl/terms", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class BnplTermsController {

    @NonNull private final BnplTermsDesk desk;

    /** Proposes a whole new terms version for a product - complete, judged, and frozen from this moment. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.BNPL_ADMINISTER)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public BnplTermsDesk.BnplTermsReceipt proposeBnplTerms(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody BnplTermsProposalRequest body) {
        return desk.propose(idempotencyKey, body);
    }

    /** Activates a pending version - a different person's act; retires the product's predecessor. */
    @PostMapping(path = "/{versionId}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.BNPL_ADMINISTER)
    @RequiresIdempotencyKey
    public BnplTermsDesk.BnplTermsReceipt approveBnplTerms(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("versionId") String versionId,
            @Valid @RequestBody BnplTermsDecisionRequest body) {
        return desk.approve(idempotencyKey, versionId, body.reason());
    }

    /** Rejects a pending version - anyone, the proposer's withdrawal included; a reason is required. */
    @PostMapping(path = "/{versionId}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.BNPL_ADMINISTER)
    @RequiresIdempotencyKey
    public BnplTermsDesk.BnplTermsReceipt rejectBnplTerms(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("versionId") String versionId,
            @Valid @RequestBody BnplTermsDecisionRequest body) {
        return desk.reject(idempotencyKey, versionId, body.reason());
    }

    /**
     * The product's terms in force at {@code at} (an ISO-8601 instant) - answered from the effective periods alone; with
     * no {@code at}, the version {@code ACTIVE} now.
     */
    @GetMapping
    @RequiresPermission(PermissionName.BNPL_ADMINISTER)
    public BnplTermsDesk.BnplTermsView readBnplTermsInForce(
            @RequestParam("product") String product, @RequestParam(value = "at", required = false) String at) {
        return desk.activeAt(product, at);
    }

    /** One terms version by id, in any state - the proposal a second person reviews before deciding it. */
    @GetMapping(path = "/{versionId}")
    @RequiresPermission(PermissionName.BNPL_ADMINISTER)
    public BnplTermsDesk.BnplTermsView readBnplTermsVersion(@PathVariable("versionId") String versionId) {
        return desk.version(versionId);
    }

    // -----------------------------------------------------------------

    /**
     * A whole terms version for one BNPL product. Amounts are integer minor units of {@code currency}, which must be the
     * product's; the rate a decimal fraction string ({@code "0.1999"} is 19.99 % a year, {@code "0"} for pay-in-4).
     * Pay-in-4 states {@code instalmentCount} and {@code intervalDays}; pay-in-N states {@code allowedTerms},
     * {@code minFirstPeriodDays}, {@code interestRounding}, {@code dayCount} and {@code accrualEngine} - each product its
     * own and no other's. Engines are named ({@code BNPL_SCHEDULE_ENGINE_V1}, {@code SCHEDULE_ENGINE_V1}, ...). Every value
     * is judged by the domain ({@code bnpl.TermsInvalid}, {@code bnpl.TermsNotAmortising}).
     */
    @ClosedBody
    public record BnplTermsProposalRequest(
            @NotBlank @Size(min = 1, max = 32) String product,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotBlank @Size(min = 1, max = 16) String nominalAnnualRate,
            @NotBlank @Size(min = 1, max = 16) String frequency,
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
            @NotNull Integer collectionRetryDays,
            @NotBlank @Size(min = 1, max = 16) String instalmentRounding,
            @Size(min = 1, max = 16) String interestRounding,
            @Size(min = 1, max = 16) String dayCount,
            @NotBlank @Size(min = 1, max = 16) String servicingZone,
            Integer instalmentCount,
            Integer intervalDays,
            @Size(max = 8) List<@NotNull Integer> allowedTerms,
            Integer minFirstPeriodDays,
            @NotNull Integer offerValidityMinutes,
            @NotNull Integer disputeResponseDays,
            @NotNull Long disputeFourEyesThresholdMinor,
            @NotNull Integer payoffHorizonDays,
            @NotBlank @Size(min = 1, max = 64) String scheduleEngine,
            @Size(min = 1, max = 64) String accrualEngine,
            @NotBlank @Size(min = 1, max = 64) String allocationEngine,
            @NotBlank @Size(min = 1, max = 64) String refundAllocationEngine,
            @NotBlank @Size(min = 1, max = 64) String agreementTemplateId,
            @NotNull Integer agreementTemplateVersion,
            @Size(max = 1000) String reason) {}

    /** A decision's reason - required, judged by the domain ({@code bnpl.ReasonRequired}). */
    @ClosedBody
    public record BnplTermsDecisionRequest(@Size(max = 1000) String reason) {}
}
