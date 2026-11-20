package com.finapp.app.merchant;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.api.PendingDecisions;
import com.finapp.app.api.RecordedRead;
import com.finapp.app.session.RecordsRefusals;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.platform.audit.AuditRecord;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
 * The second person's side of the fee schedule's four eyes (`P17-TSK-123`; ADR-0154's shape, finapp-admin-panel ADR-C135
 * decision 3, ADR-0125 decision 4): a holder of {@code FEE_SCHEDULE_APPROVE} - {@code FINANCIAL_CONTROLLER} - who did not
 * propose it approves a fee schedule version, which mints it, or a merchant's fee assignment, which moves the pointer, each
 * in the decision's transaction; or rejects, and nothing changes. Each decision on a {@code MULTI_FACTOR} session, keyed,
 * reasoned; refusals recorded.
 *
 * <p>Each kind's proposals awaiting a decision are a queue in `P17-TSK-040`'s one pending-decision shape (finapp-admin-panel
 * ADR-C133 decision 3) - {@code /operator/pending-decisions/merchant/fee-schedule-versions} and
 * {@code .../merchant/fee-assignments} - on the approve door's factor, and one proposal of any kind (the BNPL fee
 * assignments are read at `P17-TSK-124`'s door beside their own) is read by its identifier with the schedule it names,
 * recorded and on the factor as `P17-TSK-124`'s reads are: the approver holds no {@code FEE_ADMINISTER}, and four eyes that
 * cannot see the price decide nothing. The queues are unrecorded, configuration.
 */
@RestController
@RequestMapping(path = "/operator", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class FeeProposalController {

    @NonNull private final FeeScheduleOperations feeSchedules;

    /** The fee schedule version proposals awaiting a decision, oldest first: `P17-TSK-040`'s pending-decision shape. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @GetMapping("/pending-decisions/merchant/fee-schedule-versions")
    @RequiresPermission(PermissionName.FEE_SCHEDULE_APPROVE)
    public PendingDecisions.PendingDecisionPage listPendingFeeScheduleVersions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return feeSchedules.pendingVersions(status, cursor, limit);
    }

    /** The merchant fee assignment proposals awaiting a decision, oldest first: the same shape. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @GetMapping("/pending-decisions/merchant/fee-assignments")
    @RequiresPermission(PermissionName.FEE_SCHEDULE_APPROVE)
    public PendingDecisions.PendingDecisionPage listPendingFeeAssignments(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return feeSchedules.pendingAssignments(status, cursor, limit);
    }

    /**
     * One fee schedule version or merchant fee assignment proposal, with the schedule it names - every serving recorded
     * ({@code merchant.FeeProposalRead}, `P17-TSK-124`'s pattern), on the decision's own factor. Unknown and malformed are
     * one 404.
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @GetMapping("/fee-schedule-proposals/{id}")
    @RequiresPermission(PermissionName.FEE_SCHEDULE_APPROVE)
    @RecordedRead
    public FeeScheduleOperations.FeeProposalView viewFeeScheduleProposal(@PathVariable("id") String id) {
        return feeSchedules.proposal(id);
    }

    /** Approves a fee schedule version proposal the caller did not propose: the version is minted. */
    @RecordsRefusals
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/fee-schedule-version-proposals/{id}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FEE_SCHEDULE_APPROVE)
    @RequiresIdempotencyKey
    public FeeScheduleOperations.FeeScheduleVersionProposalReceipt approveFeeScheduleVersion(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody FeeProposalDecisionRequest body) {
        return feeSchedules.approveVersion(idempotencyKey, id, body.reason());
    }

    /** Rejects a fee schedule version proposal the caller did not propose: nothing is minted. */
    @RecordsRefusals
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/fee-schedule-version-proposals/{id}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FEE_SCHEDULE_APPROVE)
    @RequiresIdempotencyKey
    public FeeScheduleOperations.FeeScheduleVersionProposalReceipt rejectFeeScheduleVersion(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody FeeProposalDecisionRequest body) {
        return feeSchedules.rejectVersion(idempotencyKey, id, body.reason());
    }

    /** Approves a merchant fee assignment proposal the caller did not propose: the pointer moves. */
    @RecordsRefusals
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/fee-assignment-proposals/{id}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FEE_SCHEDULE_APPROVE)
    @RequiresIdempotencyKey
    public FeeScheduleOperations.MerchantFeeScheduleView approveFeeAssignment(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody FeeProposalDecisionRequest body) {
        return feeSchedules.approveAssignment(idempotencyKey, id, body.reason());
    }

    /** Rejects a merchant fee assignment proposal the caller did not propose: nothing changes. */
    @RecordsRefusals
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/fee-assignment-proposals/{id}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FEE_SCHEDULE_APPROVE)
    @RequiresIdempotencyKey
    public FeeScheduleOperations.MerchantFeeScheduleView rejectFeeAssignment(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody FeeProposalDecisionRequest body) {
        return feeSchedules.rejectAssignment(idempotencyKey, id, body.reason());
    }

    /** The decider's reason on a fee proposal. */
    @ClosedBody
    public record FeeProposalDecisionRequest(@NotBlank @Size(max = AuditRecord.MAX_REASON_LENGTH) String reason) {}
}
