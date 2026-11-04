package com.finapp.app.accounting;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.api.DecimalText;
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
 * The prior-period adjustment's doors (`P14-TSK-013`; ADR-0114 decision 5; {@code PHASE_14_PLAN.md} sections 8.1 T-ppa, 10, 13.5; {@code INV-ACC-10}, {@code INV-REV-04}, {@code INV-AUD-04}): an accountant proposes correcting a {@code CLOSED} month
 * under {@code ACCOUNTING_ADJUST_PROPOSE}; a financial controller, a different person, reads what it decides and approves or rejects it under {@code ACCOUNTING_ADJUST_APPROVE}. Keyed per principal. The proposal's body has no date: the server dates it, and {@link ClosedBody} refuses
 * one. A controller holds no {@code ACCOUNTING_READ}, so its read of what it decides is a route here (identity {@code V025}'s partition); an accountant reads a month's corrections beside the periods, under {@code ACCOUNTING_READ}. Reasons are never logged. Operation names are distinctive (the
 * springdoc {@code operationId} rule).
 */
@RestController
@RequestMapping(path = "/operator/accounting", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class PriorPeriodAdjustmentController {

    @NonNull private final PriorPeriodAdjustmentDesk desk;

    /**
     * Proposes correcting a CLOSED period at the revision read, dated today by the server in the OPEN month holding today - nothing posts until a different person approves. The receipt names the date, and the month's other corrections.
     */
    @PostMapping(path = "/prior-period-adjustments", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_ADJUST_PROPOSE)
    @RequiresIdempotencyKey
    public PriorPeriodAdjustmentDesk.PriorPeriodAdjustmentReceipt proposePriorPeriodAdjustment(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody PriorPeriodAdjustmentRequest body) {
        return desk.propose(idempotencyKey, body.correctedPeriodId(), body.correctedRevision(), body.reason(), body.lines() == null ? List.of()
                : body.lines().stream().map(line -> new PriorPeriodAdjustmentDesk.StatedLine(line.ledgerAccountId(), line.direction(), line.amount(), line.currency())).toList());
    }

    /** The oldest prior-period adjustments standing, so one that can no longer post can be found. Audited. */
    @GetMapping("/prior-period-adjustments")
    @RequiresPermission(PermissionName.ACCOUNTING_ADJUST_APPROVE)
    public List<PriorPeriodAdjustmentDesk.PendingPriorPeriodAdjustmentView> listPendingPriorPeriodAdjustments() {
        return desk.pending();
    }

    /** One prior-period adjustment as its approver reads it: the date it posts on, the revision it corrects, its lines, the proposer's reason, the month's other corrections, whether it still stands. Audited. */
    @GetMapping("/prior-period-adjustments/{adjustmentId}")
    @RequiresPermission(PermissionName.ACCOUNTING_ADJUST_APPROVE)
    public PriorPeriodAdjustmentDesk.PriorPeriodAdjustmentView readPriorPeriodAdjustment(@PathVariable("adjustmentId") String adjustmentId) {
        return desk.read(adjustmentId);
    }

    /**
     * Approves it - a different person's act, on a MULTI_FACTOR session (`P14-TSK-022`: 403 identity.AssuranceRequired below it), naming the date, the revision and the month's posted corrections they read: the ledger's entry posts in this transaction. A reason is required.
     */
    @PostMapping(path = "/prior-period-adjustments/{adjustmentId}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_ADJUST_APPROVE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    public PriorPeriodAdjustmentDesk.PriorPeriodAdjustmentReceipt approvePriorPeriodAdjustment(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("adjustmentId") String adjustmentId,
            @Valid @RequestBody PriorPeriodApprovalRequest body) {
        return desk.approve(idempotencyKey, adjustmentId, body.postingDate(), body.correctedRevision(), body.postedCorrections(), body.reason());
    }

    /** Rejects it, the ledger's proposal with it - final, and the way on for one that can no longer post. May be the proposer's. A reason is required. */
    @PostMapping(path = "/prior-period-adjustments/{adjustmentId}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_ADJUST_APPROVE)
    @RequiresIdempotencyKey
    public PriorPeriodAdjustmentDesk.PriorPeriodAdjustmentReceipt rejectPriorPeriodAdjustment(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("adjustmentId") String adjustmentId,
            @Valid @RequestBody PriorPeriodRejectionRequest body) {
        return desk.reject(idempotencyKey, adjustmentId, body.reason());
    }

    /** Every correction of a month, whatever its status and revision - each traced to what it corrects. Audited. */
    @GetMapping("/periods/{periodId}/prior-period-adjustments")
    @RequiresPermission(PermissionName.ACCOUNTING_READ)
    public List<PriorPeriodAdjustmentDesk.PriorPeriodCorrectionView> readPriorPeriodAdjustmentsOfPeriod(@PathVariable("periodId") String periodId) {
        return desk.ofPeriod(periodId);
    }

    // -----------------------------------------------------------------

    /** The proposal: the month and revision read, the reason, the lines. No date: the server dates it. */
    @ClosedBody
    public record PriorPeriodAdjustmentRequest(
            @Size(max = 64) String correctedPeriodId,
            int correctedRevision,
            @Size(max = 1000) String reason,
            @Size(max = 100) List<@Valid @NotNull PriorPeriodLineItem> lines) {

        /** The reason and the amounts stay out of a log line: a record's generated {@code toString} would carry them. */
        @Override
        public String toString() {
            return "PriorPeriodAdjustmentRequest[" + correctedPeriodId + "#" + correctedRevision + "]";
        }
    }

    /** One line; the amount a decimal string, exact in the currency's scale. */
    @ClosedBody
    public record PriorPeriodLineItem(@Size(max = 64) String ledgerAccountId, @Size(max = 16) String direction, @Size(max = DecimalText.MAX_LENGTH) String amount, @Size(max = 8) String currency) {

        /** The amount stays out of a log line. */
        @Override
        public String toString() {
            return "PriorPeriodLineItem[" + ledgerAccountId + ", " + direction + "]";
        }
    }

    /** The approval: what the approver read, and a reason. */
    @ClosedBody
    public record PriorPeriodApprovalRequest(
            @Size(max = 16) String postingDate,
            int correctedRevision,
            @Size(max = 200) List<@NotNull @Size(max = 64) String> postedCorrections,
            @Size(max = 1000) String reason) {

        /** The reason stays out of a log line. */
        @Override
        public String toString() {
            return "PriorPeriodApprovalRequest[" + postingDate + "#" + correctedRevision + "]";
        }
    }

    /** The rejection's reason. */
    @ClosedBody
    public record PriorPeriodRejectionRequest(@Size(max = 1000) String reason) {

        /** The reason stays out of a log line. */
        @Override
        public String toString() {
            return "PriorPeriodRejectionRequest[]";
        }
    }
}
