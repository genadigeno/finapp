package com.finapp.app.risk;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The risk analyst's action doors on a case (`P13-TSK-017`; {@code PHASE_13_PLAN.md} sections 9, 11, 12.8;
 * {@code RISK_LIFECYCLES.md} section 4.7; ADR-0112 decisions 2-4): a downstream action proposed on a taken case (a kind
 * the case admits, on its own party or merchant or a held movement it holds), approved by a second holder from a
 * {@code MULTI_FACTOR} session - {@code 202}: the platform then dispatches it through the owning domain's keyed door - or
 * rejected. The proposal behind {@code RISK_INVESTIGATE}, the decisions behind {@code RISK_CASE_APPROVE} (both held by
 * {@code RISK_ANALYST}: four eyes is two holders of the role), each keyed per principal ({@code risk.case:<type>:<id>})
 * and carrying the case revision the analyst's screen read. A kind the case does not admit, a target it does not hold or a
 * live twin is {@code 409 risk.ActionNotProposable}; the proposer's approval {@code 403 risk.SelfApprovalRefused} and the
 * analyst's own party's case {@code 403 risk.SelfDealingRefused} - each refusal recorded {@code FAILED}. Every answer is
 * the action with the case's revision; no customer or merchant route reads any of this ({@code INV-RSK-08}).
 */
@RestController
@RequestMapping(path = "/operator/risk/cases", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RiskActionController {

    @NonNull private final RiskActionDesk desk;

    /** Proposes a downstream action on the case - awaiting a second holder's approval. */
    @PostMapping(path = "/{caseId}/risk-actions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_INVESTIGATE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public RiskActionDesk.RiskActionView proposeRiskAction(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("caseId") String caseId,
            @Valid @RequestBody RiskActionProposalRequest body) {
        return desk.propose(idempotencyKey, caseId, body);
    }

    /**
     * Approves a standing action - a second holder's act, from a {@code MULTI_FACTOR} session; {@code 202}: the platform
     * dispatches it through the owning domain's keyed door after the approval commits, its outcome on the action.
     */
    @PostMapping(path = "/{caseId}/risk-actions/{actionId}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_CASE_APPROVE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RiskActionDesk.RiskActionView approveRiskAction(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("caseId") String caseId,
            @PathVariable("actionId") String actionId,
            @Valid @RequestBody RiskCaseController.RiskCaseDecisionRequest body) {
        return desk.approve(idempotencyKey, caseId, actionId, body);
    }

    /** Rejects a standing action - anyone, the proposer's withdrawal included; nothing is dispatched. */
    @PostMapping(path = "/{caseId}/risk-actions/{actionId}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_CASE_APPROVE)
    @RequiresIdempotencyKey
    public RiskActionDesk.RiskActionView rejectRiskAction(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("caseId") String caseId,
            @PathVariable("actionId") String actionId,
            @Valid @RequestBody RiskCaseController.RiskCaseDecisionRequest body) {
        return desk.reject(idempotencyKey, caseId, actionId, body);
    }

    // -----------------------------------------------------------------

    /**
     * An action's proposal: the case {@code revision} read (required), the {@code kind} (one of {@code ActionKind} the
     * case admits - {@code SUSPEND_IDENTITY}, {@code REINSTATE_IDENTITY}, {@code REVOKE_SESSIONS} on a party's case,
     * {@code SUSPEND_MERCHANT} on a merchant-abuse case, {@code RELEASE_HELD_WITHDRAWAL}, {@code DENY_HELD_WITHDRAWAL},
     * {@code CAPTURE_HELD_PAYMENT}, {@code VOID_HELD_PAYMENT} on a held movement the case holds), the {@code targetRef}
     * (the held movement's id; a party's or merchant's action names none - its target is the case's own subject) and the
     * reason (required, judged by the domain - {@code risk.ReasonRequired}).
     */
    @ClosedBody
    public record RiskActionProposalRequest(
            @NotNull @PositiveOrZero Integer revision,
            @NotNull @Size(max = 64) String kind,
            @Size(max = 64) String targetRef,
            @Size(max = 1000) String reason) {}
}
