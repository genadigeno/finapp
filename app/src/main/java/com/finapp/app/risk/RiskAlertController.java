package com.finapp.app.risk;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The risk analyst's alert doors (`P13-TSK-018`; {@code PHASE_13_PLAN.md} sections 9, 11; {@code RISK_LIFECYCLES.md}
 * section 4.5; ADR-0110 decision 1): the alert queue - oldest first, keyset-paged, filtered by status and kind, every
 * page audited {@code risk.AlertRead}, the analyst's own party's alerts withheld - and the suppression door over
 * `-015`'s four-eyes machine: a reasoned proposal behind {@code RISK_INVESTIGATE}, the approval of a second
 * {@code RISK_CASE_APPROVE} holder from a {@code MULTI_FACTOR} session, a rejection behind {@code RISK_CASE_APPROVE}; each
 * act keyed per principal ({@code risk.alert:<type>:<id>}). {@code SUPPRESSED} is only this door's outcome - the
 * system's dedupe is {@code ATTACHED}. No customer or merchant route reads any of this ({@code INV-RSK-08}).
 */
@RestController
@RequestMapping(path = "/operator/risk/alerts", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RiskAlertController {

    @NonNull private final RiskQueueDesk queue;
    @NonNull private final RiskAlertDesk desk;

    /**
     * A page of the alert queue: {@code status} one {@code AlertStatus} ({@code OPEN} unless named), {@code kind} one
     * {@code AlertKind}, {@code after} the last alert of the previous page, {@code limit} the page's size (20 unless
     * named, at most 50).
     */
    @GetMapping
    @RequiresPermission(PermissionName.RISK_INVESTIGATE)
    public RiskQueueDesk.RiskAlertPageView readRiskAlertQueue(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "after", required = false) String after,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return queue.alerts(status, kind, after, limit);
    }

    /** Proposes an open alert's suppression - with a reason code and a text; a second holder decides it. */
    @PostMapping(path = "/{alertId}/suppression", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_INVESTIGATE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public RiskQueueDesk.RiskAlertView proposeRiskAlertSuppression(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("alertId") String alertId,
            @Valid @RequestBody RiskSuppressionProposalRequest body) {
        return desk.propose(idempotencyKey, alertId, body);
    }

    /** Approves a standing suppression - a second holder's act, from a {@code MULTI_FACTOR} session. */
    @PostMapping(path = "/{alertId}/suppression/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_CASE_APPROVE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    public RiskQueueDesk.RiskAlertView approveRiskAlertSuppression(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("alertId") String alertId,
            @Valid @RequestBody RiskSuppressionDecisionRequest body) {
        return desk.approve(idempotencyKey, alertId, body);
    }

    /** Rejects a standing suppression - anyone, the proposer's withdrawal included; the alert stays open. */
    @PostMapping(path = "/{alertId}/suppression/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_CASE_APPROVE)
    @RequiresIdempotencyKey
    public RiskQueueDesk.RiskAlertView rejectRiskAlertSuppression(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("alertId") String alertId,
            @Valid @RequestBody RiskSuppressionDecisionRequest body) {
        return desk.reject(idempotencyKey, alertId, body);
    }

    // -----------------------------------------------------------------

    /**
     * A suppression's proposal: {@code reasonCode} one of {@code FALSE_POSITIVE}, {@code ACTIVITY_EXPLAINED},
     * {@code RULE_DEFECT}, {@code DATA_DEFECT} (deliberately no {@code DUPLICATE} - a duplicate attaches) and the
     * {@code reason}, both required and judged by the domain ({@code risk.ReasonRequired}).
     */
    @ClosedBody
    public record RiskSuppressionProposalRequest(
            @Size(max = 64) String reasonCode,
            @Size(max = 1000) String reason) {}

    /** A decision on a standing suppression: the reason, required and judged by the domain. */
    @ClosedBody
    public record RiskSuppressionDecisionRequest(@Size(max = 1000) String reason) {}
}
