package com.finapp.app.accounting;

import com.finapp.accounting.PriorPeriodAdjustments;
import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
 * and a reopen under {@code ACCOUNTING_CLOSE_PROPOSE}; a financial controller - a different person - reads what it decides, approves or rejects the close and approves the reopen under {@code ACCOUNTING_CLOSE_APPROVE}, each approval from a
 * {@code MULTI_FACTOR} session (`P14-TSK-024`, the owner's acceptance: a close freezes the month and a reopen moves the ledger's cutoff and supersedes FINAL runs). A pending reopen ends without its effect (`P14-TSK-024`): rejected by a different
 * person under {@code ACCOUNTING_CLOSE_APPROVE}, or withdrawn by its proposer under {@code ACCOUNTING_CLOSE_PROPOSE}, each from a session of any assurance, since an end moves nothing. Each act on a pending reopen names it by {@code reopenId}; the
 * close's decisions name their proposal. Every act keyed per principal. A controller holds no {@code ACCOUNTING_READ}, so its read of what it approves is a route here (identity {@code V025}'s partition; the mapping diff's precedent). Reasons are never logged. The handlers' names are
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

    /**
     * The period's latest close proposal as its approver reads it: each attempt with its evidence, when it concluded (concludedAt, the database's clock: nothing re-judges it at the approval) and the reason per DISCLOSED attempt, whether it still stands, and the
     * adjustments posted against the month, which a reopen's approver names. On a pending reopen (reopenPending), the returns it would overtake (overtakenReturns, overtakenReturnsTotal, overtakenReturnsNote), read here holding nothing: information, which the approval's
     * receipt reads again and may differ from; null when no reopen is pending. Audited.
     */
    @GetMapping("/{periodId}/close-proposal")
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_APPROVE)
    public PeriodCloseDesk.CloseProposalView readAccountingPeriodCloseProposal(@PathVariable("periodId") String periodId) {
        return desk.proposal(periodId);
    }

    /**
     * A period's pending requests as the controller reads them before deciding one: an unlock pending and when it was proposed (unlockProposedAt), a reopen pending and its id (reopenId) - what the approval, the rejection
     * and the withdrawal name. No person, no reason. A controller holds no ACCOUNTING_READ, so the period list is not theirs.
     */
    @GetMapping("/{periodId}/requests")
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_APPROVE)
    public PeriodCloseDesk.PeriodRequestsView readAccountingPeriodRequests(@PathVariable("periodId") String periodId) {
        return desk.requests(periodId);
    }

    /** Approves the proposal named - a different person's act, from a MULTI_FACTOR session (403 identity.AssuranceRequired below it): CLOSED, what it read still standing; a reason is required. */
    @PostMapping(path = "/{periodId}/close-approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_APPROVE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
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

    /**
     * Proposes reopening the latest CLOSED period, the next not locked, naming the adjustments posted against it (read at GET .../prior-period-adjustments of the period) - nothing moves until a different person approves; a reason is required. Naming other than what is posted now is 409
     * accounting.PriorPeriodAdjustmentStale. The receipt lists the returns the reopen would overtake, as read at this act (overtakenReturns, overtakenReturnsTotal, overtakenReturnsNote): information, named back by nobody; the approval's receipt reads its own.
     */
    @PostMapping(path = "/{periodId}/reopen-proposal", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_PROPOSE)
    @RequiresIdempotencyKey
    public PeriodCloseDesk.PeriodCloseReceipt proposeAccountingPeriodReopen(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("periodId") String periodId,
            @Valid @RequestBody PeriodReopenRequest body) {
        return desk.proposeReopen(idempotencyKey, periodId, body.postedAdjustments(), body.reason());
    }

    /**
     * Approves the pending reopen named (reopenId of GET .../close-proposal) - a different person's act, from a MULTI_FACTOR session (403 identity.AssuranceRequired below it), naming the adjustments posted against the month (priorPeriodAdjustments of GET .../close-proposal, under this same
     * permission): the period OPEN at its next revision, the cutoff back one period, its FINAL runs SUPERSEDED and nothing deleted; a reason is required. Another reopen pending is 409 accounting.RequestStale, recorded. Naming other than what is posted now is 409
     * accounting.PriorPeriodAdjustmentStale: an adjustment may have posted since the proposal. The receipt lists the returns this reopen overtook, as read at this act (overtakenReturns, overtakenReturnsTotal, overtakenReturnsNote): information, it refused nothing.
     */
    @PostMapping(path = "/{periodId}/reopen-approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_APPROVE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    public PeriodCloseDesk.PeriodCloseReceipt approveAccountingPeriodReopen(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("periodId") String periodId,
            @Valid @RequestBody ReopenApprovalRequest body) {
        return desk.approveReopen(idempotencyKey, periodId, body.reopenId(), body.postedAdjustments(), body.reason());
    }

    /**
     * Rejects the pending reopen named (reopenId) - a person other than its proposer: the reopen REJECTED, the period still CLOSED at its revision, nothing superseded and the cutoff unmoved; a new reopen may be proposed. A reason is required. The proposer is
     * refused 403 accounting.SelfApprovalRefused and withdraws instead. Another reopen pending is 409 accounting.RequestStale; with none pending, 409 accounting.PeriodStale says whether the one named was approved, rejected or withdrawn.
     */
    @PostMapping(path = "/{periodId}/reopen-rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_APPROVE)
    @RequiresIdempotencyKey
    public PeriodCloseDesk.PeriodCloseReceipt rejectAccountingPeriodReopen(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("periodId") String periodId,
            @Valid @RequestBody ReopenEndRequest body) {
        return desk.rejectReopen(idempotencyKey, periodId, body.reopenId(), body.reason());
    }

    /**
     * Withdraws the pending reopen named (reopenId, as GET .../periods lists it) - its proposer alone: the reopen WITHDRAWN, the period still CLOSED at its revision, nothing superseded and the cutoff unmoved; a new reopen may be proposed. A reason is required.
     * Anyone else is refused 403 accounting.NotTheProposer, recorded. Another reopen pending is 409 accounting.RequestStale; with none pending, 409 accounting.PeriodStale says whether the one named was approved, rejected or withdrawn.
     */
    @PostMapping(path = "/{periodId}/reopen-withdrawal", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_PROPOSE)
    @RequiresIdempotencyKey
    public PeriodCloseDesk.PeriodCloseReceipt withdrawAccountingPeriodReopen(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("periodId") String periodId,
            @Valid @RequestBody ReopenEndRequest body) {
        return desk.withdrawReopen(idempotencyKey, periodId, body.reopenId(), body.reason());
    }

    // -----------------------------------------------------------------

    /** The proposal: the run read, the act's reason, a reason per DISCLOSED attempt (at most one per check). */
    @ClosedBody
    public record CloseProposalRequest(@Size(max = 64) String closeRunId, @Size(max = 1000) String reason, @Size(max = 9) List<@Valid @NotNull CloseDisclosureItem> disclosures) {

        /** Every reason it holds stays out of a log line: a record's generated {@code toString} would carry them. */
        @Override
        public String toString() {
            return "CloseProposalRequest[" + closeRunId + "]";
        }
    }

    /** A person's reason for one attempt they read. */
    @ClosedBody
    public record CloseDisclosureItem(@Size(max = 64) String check, int attempt, @Size(max = 1000) String reason) {

        /** The reason stays out of a log line: a record's generated {@code toString} would carry it. */
        @Override
        public String toString() {
            return "CloseDisclosureItem[" + check + "#" + attempt + "]";
        }
    }

    /** A decision: the proposal read, and a reason. */
    @ClosedBody
    public record CloseDecisionRequest(@Size(max = 64) String proposalId, @Size(max = 1000) String reason) {

        /** The reason stays out of a log line: a record's generated {@code toString} would carry it. */
        @Override
        public String toString() {
            return "CloseDecisionRequest[" + proposalId + "]";
        }
    }

    /** A reopen's end (`P14-TSK-024`): the reopen read, by its id, and a reason ({@code accounting.ReasonRequired} when missing). */
    @ClosedBody
    public record ReopenEndRequest(@NotNull @Size(max = 64) String reopenId, @Size(max = 1000) String reason) {

        /** The reason stays out of a log line: a record's generated {@code toString} would carry it. */
        @Override
        public String toString() {
            return "ReopenEndRequest[" + reopenId + "]";
        }
    }

    /**
     * A reopen's approval (`P14-TSK-024`): the reopen read, by its id; its reason; and the adjustments posted against the month that the person read, as {@link PeriodReopenRequest} names them and bounded
     * alike.
     */
    @ClosedBody
    public record ReopenApprovalRequest(@NotNull @Size(max = 64) String reopenId, @Size(max = 1000) String reason,
            @Size(max = PriorPeriodAdjustments.MAX_POSTED_CORRECTIONS) List<@NotNull @Size(max = 64) String> postedAdjustments) {

        /** The reason stays out of a log line: a record's generated {@code toString} would carry it. */
        @Override
        public String toString() {
            return "ReopenApprovalRequest[" + reopenId + "]";
        }
    }

    /**
     * A reopen's proposal: its reason ({@code accounting.ReasonRequired} when missing) and the adjustments posted against the month that the person read. Absent names none; naming other than what is posted now is refused, a month with none included. Its bound is the most a
     * month can hold, one above what an adjustment's approval may name ({@link PriorPeriodAdjustments#MAX_POSTED_CORRECTIONS} says why): a smaller one would leave a full month unable to reopen.
     */
    @ClosedBody
    public record PeriodReopenRequest(@Size(max = 1000) String reason, @Size(max = PriorPeriodAdjustments.MAX_POSTED_CORRECTIONS) List<@NotNull @Size(max = 64) String> postedAdjustments) {

        /** The reason stays out of a log line: a record's generated {@code toString} would carry it. */
        @Override
        public String toString() {
            return "PeriodReopenRequest[]";
        }
    }
}
