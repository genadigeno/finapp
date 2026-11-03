package com.finapp.app.accounting;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * The close's and the reopen's doors (`P14-TSK-012`; ADR-0114; {@code PHASE_14_PLAN.md} sections 6, 10, 12, 13.5; {@code INV-ACC-09}, {@code -10}, {@code INV-AUD-04}): an accountant proposes a close on the attempts they read
 * and a reopen under {@code ACCOUNTING_CLOSE_PROPOSE}; a financial controller - a different person - reads what it decides, approves or rejects the close and approves the reopen under {@code ACCOUNTING_CLOSE_APPROVE}. Every act keyed
 * per principal. A controller holds no {@code ACCOUNTING_READ}, so its read of what it approves is a route here (identity {@code V025}'s partition; the mapping diff's precedent). Reasons are never logged. The handlers' names are
 * distinctive (the springdoc {@code operationId} rule).
 */
@RestController
@RequestMapping(path = "/operator/accounting/periods", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class PeriodCloseController {

    @NonNull private final PeriodCloseDesk desk;

    /**
     * Proposes closing a LOCKED period on the attempts the person read: the run, and per DISCLOSED attempt its check, its number and a reason. Refused unless the present run is ready, and if anything named is no longer what stands.
     */
    @PostMapping(path = "/{periodId}/close-proposal", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_PROPOSE)
    @RequiresIdempotencyKey
    public PeriodCloseDesk.PeriodCloseReceipt proposeAccountingPeriodClose(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("periodId") String periodId,
            @Valid @RequestBody CloseProposalRequest body) {
        return desk.propose(idempotencyKey, periodId, body.closeRunId(), body.reason(), body.disclosures() == null ? List.of()
                : body.disclosures().stream().map(item -> new PeriodCloseDesk.StatedDisclosure(item.check(), item.attempt(), item.reason())).toList());
    }

    /** The period's latest close proposal as its approver reads it: each attempt with its evidence, the reason per DISCLOSED attempt, whether it still stands. Audited. */
    @GetMapping("/{periodId}/close-proposal")
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_APPROVE)
    public PeriodCloseDesk.CloseProposalView readAccountingPeriodCloseProposal(@PathVariable("periodId") String periodId) {
        return desk.proposal(periodId);
    }

    /** Approves the proposal named - a different person's act: CLOSED, what it read still standing; a reason is required. */
    @PostMapping(path = "/{periodId}/close-approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_APPROVE)
    @RequiresIdempotencyKey
    public PeriodCloseDesk.PeriodCloseReceipt approveAccountingPeriodClose(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("periodId") String periodId,
            @Valid @RequestBody CloseDecisionRequest body) {
        return desk.approve(idempotencyKey, periodId, body.proposalId(), body.reason());
    }

    /** Rejects the proposal named: LOCKED again, the same lock and run; a next proposal is a new one. A reason is required. */
    @PostMapping(path = "/{periodId}/close-rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_APPROVE)
    @RequiresIdempotencyKey
    public PeriodCloseDesk.PeriodCloseReceipt rejectAccountingPeriodClose(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("periodId") String periodId,
            @Valid @RequestBody CloseDecisionRequest body) {
        return desk.reject(idempotencyKey, periodId, body.proposalId(), body.reason());
    }

    /** Proposes reopening the latest CLOSED period, the next not locked - nothing moves until a different person approves; a reason is required. */
    @PostMapping(path = "/{periodId}/reopen-proposal", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_PROPOSE)
    @RequiresIdempotencyKey
    public PeriodCloseDesk.PeriodCloseReceipt proposeAccountingPeriodReopen(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("periodId") String periodId,
            @Valid @RequestBody PeriodReopenRequest body) {
        return desk.proposeReopen(idempotencyKey, periodId, body.reason());
    }

    /**
     * Approves a pending reopen - a different person's act: the period OPEN at its next revision, the cutoff back one period, its FINAL runs SUPERSEDED and nothing deleted; a reason is required.
     */
    @PostMapping(path = "/{periodId}/reopen-approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_APPROVE)
    @RequiresIdempotencyKey
    public PeriodCloseDesk.PeriodCloseReceipt approveAccountingPeriodReopen(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("periodId") String periodId,
            @Valid @RequestBody PeriodReopenRequest body) {
        return desk.approveReopen(idempotencyKey, periodId, body.reason());
    }

    // -----------------------------------------------------------------

    /** The proposal: the run read, the act's reason, a reason per DISCLOSED attempt (at most one per check). */
    @ClosedBody
    public record CloseProposalRequest(@Size(max = 64) String closeRunId, @Size(max = 1000) String reason, @Valid @Size(max = 9) List<CloseDisclosureItem> disclosures) {}

    /** A person's reason for one attempt they read. */
    @ClosedBody
    public record CloseDisclosureItem(@Size(max = 64) String check, int attempt, @Size(max = 1000) String reason) {}

    /** A decision: the proposal read, and a reason. */
    @ClosedBody
    public record CloseDecisionRequest(@Size(max = 64) String proposalId, @Size(max = 1000) String reason) {}

    /** A reopen's act: its reason ({@code accounting.ReasonRequired} when missing). */
    @ClosedBody
    public record PeriodReopenRequest(@Size(max = 1000) String reason) {}
}
