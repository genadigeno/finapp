package com.finapp.app.lending;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.api.DecimalText;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.lending.LendingSpans;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A defaulted loan's write-off doors (`P14-TSK-021`; owner decision C6 (A); {@code INV-AUD-04}, {@code INV-REV-04}): a holder of
 * {@code LOAN_WRITE_OFF_PROPOSE} (role {@code LOAN_SERVICING_AGENT}) proposes it; a different person holding
 * {@code LOAN_WRITE_OFF_APPROVE} (role {@code LENDING_OFFICER}), who reads what they decide under that permission, approves it on a
 * {@code MULTI_FACTOR} session, naming what they read, or rejects it. Keyed per principal ({@code lending.servicing:<type>:<id>}).
 * No body names a date: the server dates it. Reasons are never logged. A write-off is an act on the books, never forgiveness.
 * Operation names are distinctive (the springdoc {@code operationId} rule).
 */
@RestController
@RequestMapping(path = "/operator/loans", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class LoanWriteOffController {

    @NonNull private final LoanWriteOffDesk desk;
    @NonNull private final LendingFlowScope flow;

    /** Proposes writing the defaulted loan off, with a reason: nothing is posted. The receipt names the figures read. */
    @PostMapping(path = "/{id}/write-offs", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LOAN_WRITE_OFF_PROPOSE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public LoanWriteOffDesk.WriteOffReceipt proposeLoanWriteOff(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody LoanWriteOffProposalRequest body) {
        return flow.doorOnLoan(LendingSpans.CORRECT, id, () -> desk.propose(id, idempotencyKey, body.reason()));
    }

    /** The oldest write-offs awaiting a decision, so one that can no longer post can be found. Audited. */
    @GetMapping("/write-offs")
    @RequiresPermission(PermissionName.LOAN_WRITE_OFF_APPROVE)
    public List<LoanWriteOffDesk.WriteOffReceipt> listPendingLoanWriteOffs() {
        return desk.pending();
    }

    /**
     * One write-off as its approver reads it: the figures it would take, the principal part, the revision, the default it stands on,
     * the proposer and their reason, the date it posts on, what the account holds now, and whether it still stands. Audited.
     */
    @GetMapping("/{id}/write-offs/{writeOffId}")
    @RequiresPermission(PermissionName.LOAN_WRITE_OFF_APPROVE)
    public LoanWriteOffDesk.WriteOffAttestation readLoanWriteOff(
            @PathVariable("id") String id, @PathVariable("writeOffId") String writeOffId) {
        return desk.read(id, writeOffId);
    }

    /**
     * Approves it - a different person's act, on a MULTI_FACTOR session, naming the figures and the revision they read: rule 14 posts
     * today in this transaction and the loan goes WRITTEN_OFF. Anything moved since the proposal is 409 lending.WriteOffStale.
     */
    @PostMapping(path = "/{id}/write-offs/{writeOffId}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LOAN_WRITE_OFF_APPROVE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    public LoanWriteOffDesk.WriteOffReceipt approveLoanWriteOff(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @PathVariable("writeOffId") String writeOffId,
            @Valid @RequestBody LoanWriteOffApprovalRequest body) {
        return flow.doorOnLoan(LendingSpans.CORRECT, id, () -> desk.approve(id, writeOffId, idempotencyKey, body));
    }

    /** Rejects it, on any account status - final, and the door out of one that can no longer post. A reason is required. */
    @PostMapping(path = "/{id}/write-offs/{writeOffId}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LOAN_WRITE_OFF_APPROVE)
    @RequiresIdempotencyKey
    public LoanWriteOffDesk.WriteOffReceipt rejectLoanWriteOff(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @PathVariable("writeOffId") String writeOffId,
            @Valid @RequestBody LoanWriteOffRejectionRequest body) {
        return flow.doorOnLoan(LendingSpans.CORRECT, id, () -> desk.reject(id, writeOffId, idempotencyKey, body.reason()));
    }

    // -----------------------------------------------------------------

    /** The proposal's reason, judged by the domain. The figures are the server's, read under the lock; no date: the server dates it. */
    @ClosedBody
    public record LoanWriteOffProposalRequest(@Size(max = 1000) String reason) {

        /** The reason stays out of a log line: a record's generated {@code toString} would carry it. */
        @Override
        public String toString() {
            return "LoanWriteOffProposalRequest[]";
        }
    }

    /** The approval: the figures and revision the approver read, each an exact decimal in the named currency, and a reason. */
    @ClosedBody
    public record LoanWriteOffApprovalRequest(
            @NotBlank @Size(max = 3) String currency,
            @NotBlank @Size(max = DecimalText.MAX_LENGTH) String principal,
            @NotBlank @Size(max = DecimalText.MAX_LENGTH) String principalDue,
            @NotBlank @Size(max = DecimalText.MAX_LENGTH) String interestAccrued,
            @NotBlank @Size(max = DecimalText.MAX_LENGTH) String interestDue,
            @NotBlank @Size(max = DecimalText.MAX_LENGTH) String feesDue,
            @NotNull Long revision,
            @Size(max = 1000) String reason) {

        /** The figures and the reason stay out of a log line. */
        @Override
        public String toString() {
            return "LoanWriteOffApprovalRequest[revision=" + revision + "]";
        }
    }

    /** The rejection's reason. */
    @ClosedBody
    public record LoanWriteOffRejectionRequest(@Size(max = 1000) String reason) {

        /** The reason stays out of a log line. */
        @Override
        public String toString() {
            return "LoanWriteOffRejectionRequest[]";
        }
    }
}
