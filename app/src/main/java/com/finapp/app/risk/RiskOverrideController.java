package com.finapp.app.risk;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The risk officer's override doors (`P13-TSK-012`; {@code PHASE_13_PLAN.md} sections 9, 11, 12.3;
 * {@code RISK_LIFECYCLES.md} section 4.2a; ADR-0108 decision 7, C7): an override of a {@code DENY}, {@code HOLD} or
 * {@code REVIEW} decision proposed with a reason code and text; approved - by a second holder at or above the pinned
 * threshold or for any {@code DENY}, from a {@code MULTI_FACTOR} session - issuing its effect through payments' keyed
 * doors; rejected; and the assessment it overrides read with its overrides. Every route behind {@code RISK_OVERRIDE}
 * (held by {@code RISK_OFFICER}): roles are a partition, so this desk serves the assessment under its own permission and
 * the officer needs no analyst's read. Each act keyed per principal ({@code risk.override:<type>:<id>}). The proposer's
 * approval above the threshold is {@code 403 risk.SelfApprovalRefused} and the officer's own party's assessment
 * {@code 403 risk.SelfDealingRefused}, each recorded {@code FAILED}. No customer or merchant route reads any of this
 * ({@code INV-RSK-08}).
 */
@RestController
@RequestMapping(path = "/operator/risk/assessments/{assessmentId}/overrides", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RiskOverrideController {

    @NonNull private final RiskOverrideDesk desk;

    /** The assessment the officer overrides, served whole, and every override of it - audited. */
    @GetMapping
    @RequiresPermission(PermissionName.RISK_OVERRIDE)
    public RiskOverrideDesk.RiskOverriddenView readRiskOverrides(@PathVariable("assessmentId") String assessmentId) {
        return desk.read(assessmentId);
    }

    /** One override of the assessment, with the assessment - audited. */
    @GetMapping(path = "/{overrideId}")
    @RequiresPermission(PermissionName.RISK_OVERRIDE)
    public RiskOverrideDesk.RiskOverriddenView readRiskOverride(@PathVariable("assessmentId") String assessmentId,
            @PathVariable("overrideId") String overrideId) {
        return desk.readOne(assessmentId, overrideId);
    }

    /** Proposes an override of the assessment's decision - its effect and C7's four eyes fixed now. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_OVERRIDE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public RiskOverrideDesk.RiskOverrideView proposeRiskOverride(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("assessmentId") String assessmentId,
            @Valid @RequestBody RiskOverrideProposalRequest body) {
        return desk.propose(idempotencyKey, assessmentId, body);
    }

    /**
     * Approves a pending override - a second holder's act where C7 requires one - from a {@code MULTI_FACTOR} session;
     * its action is dispatched through payments' door after the approval commits, the answer naming its outcome as it
     * stands (none while the dispatch is still to be re-driven).
     */
    @PostMapping(path = "/{overrideId}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_OVERRIDE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    public RiskOverrideDesk.RiskOverrideView approveRiskOverride(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("assessmentId") String assessmentId,
            @PathVariable("overrideId") String overrideId,
            @Valid @RequestBody RiskOverrideDecisionRequest body) {
        return desk.approve(idempotencyKey, assessmentId, overrideId, body.reason());
    }

    /** Rejects a pending override - anyone, the proposer's withdrawal included; it issues nothing. */
    @PostMapping(path = "/{overrideId}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_OVERRIDE)
    @RequiresIdempotencyKey
    public RiskOverrideDesk.RiskOverrideView rejectRiskOverride(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("assessmentId") String assessmentId,
            @PathVariable("overrideId") String overrideId,
            @Valid @RequestBody RiskOverrideDecisionRequest body) {
        return desk.reject(idempotencyKey, assessmentId, overrideId, body.reason());
    }

    // -----------------------------------------------------------------

    /**
     * An override's proposal: the reason code ({@code FALSE_POSITIVE}, {@code CUSTOMER_VERIFIED},
     * {@code ACTIVITY_EXPLAINED}, {@code POLICY_EXCEPTION}, {@code RISK_CONFIRMED}) and the officer's text, both
     * required and judged by the domain ({@code risk.ReasonRequired}); the resolution - {@code PROCEED} (the default: a
     * held movement released or captured; a refused one recorded for its resubmission) or {@code REFUSE} (a held
     * movement denied or voided now).
     */
    @ClosedBody
    public record RiskOverrideProposalRequest(
            @Size(max = 64) String reasonCode,
            @Size(max = 1000) String reason,
            @Pattern(regexp = "PROCEED|REFUSE") String resolution) {}

    /** A decision's reason - required, judged by the domain ({@code risk.ReasonRequired}). */
    @ClosedBody
    public record RiskOverrideDecisionRequest(@Size(max = 1000) String reason) {}
}
