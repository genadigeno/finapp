package com.finapp.app.accounting;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
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
 * The regulatory submission's doors (`P14-TSK-018`; ADR-0118; {@code PHASE_14_PLAN.md} sections 10, 13.7; {@code INV-ACC-13}, {@code INV-AUD-04}). A reference framework: no jurisdiction, channel or form here is real, and nothing here says otherwise.
 *
 * <p>A {@code REGULATORY_REPORTER} drafts from FINAL runs of a {@code CLOSED} month, validates, reads the draft and its figures, reads what stands, and withdraws, under {@code REGULATORY_REPORT_PREPARE}; sends what a second person attested, and concludes a return undelivered on the provider's
 * definite "not received", under {@code REGULATORY_REPORT_SUBMIT}, from a {@code MULTI_FACTOR} session (the {@code RiskReportController} precedent). A {@code FINANCIAL_CONTROLLER} reads what they attest and what waits on them, attests from a {@code MULTI_FACTOR} session naming the content's hash and
 * the revision they read, and refuses, under {@code REGULATORY_REPORT_APPROVE}. Each role's read is its own door: neither holds {@code ACCOUNTING_READ}, and none is granted. Each act keyed per principal. Operation names are distinctive (the springdoc {@code operationId} rule).
 */
@RestController
@RequestMapping(path = "/operator/regulatory/submissions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RegulatorySubmissionController {

    @NonNull private final RegulatorySubmissionDesk desk;

    /** Drafts a return of an obligation version in force for a CLOSED month, from its FINAL runs, each line traced. A correction names the concluded return it corrects. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.REGULATORY_REPORT_PREPARE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public RegulatorySubmissionDesk.RegulatorySubmissionReceipt draftRegulatorySubmission(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody RegulatorySubmissionDraftRequest body) {
        return desk.draft(idempotencyKey, body.obligationId(), body.periodId(), body.correctsSubmissionId(), body.reason());
    }

    /** What a preparer must act on or know of: everything live, a return rejected or undelivered with no correction, an acknowledged one whose month moved with none. Audited. */
    @GetMapping
    @RequiresPermission(PermissionName.REGULATORY_REPORT_PREPARE)
    public List<RegulatorySubmissionDesk.RegulatorySubmissionSummaryView> listStandingRegulatorySubmissions() {
        return desk.standing();
    }

    /** What this controller can decide: those validated, never their own. Audited. */
    @GetMapping("/awaiting-approval")
    @RequiresPermission(PermissionName.REGULATORY_REPORT_APPROVE)
    public List<RegulatorySubmissionDesk.RegulatorySubmissionSummaryView> listRegulatorySubmissionsAwaitingApproval() {
        return desk.awaitingApproval();
    }

    /** A return and its figures as a preparer reads it: each line traced to its run's figure, the validation, the provider's answers, whether its month moved. Audited. */
    @GetMapping("/{submissionId}")
    @RequiresPermission(PermissionName.REGULATORY_REPORT_PREPARE)
    public RegulatorySubmissionDesk.RegulatorySubmissionView readRegulatorySubmission(@PathVariable("submissionId") String submissionId) {
        return desk.read(submissionId);
    }

    /** The reference document its simulated adapter would send, saying so first. Audited. */
    @GetMapping("/{submissionId}/reference-export")
    @RequiresPermission(PermissionName.REGULATORY_REPORT_PREPARE)
    public RegulatorySubmissionDesk.RegulatoryReferenceExportView readRegulatoryReferenceExport(@PathVariable("submissionId") String submissionId) {
        return desk.referenceExport(submissionId);
    }

    /** What an approver attests: the lines, the lineage, the validation, the obligation version and the preparer's reason, under their own permission. Audited. */
    @GetMapping("/{submissionId}/attestation")
    @RequiresPermission(PermissionName.REGULATORY_REPORT_APPROVE)
    public RegulatorySubmissionDesk.RegulatorySubmissionView readRegulatorySubmissionAttestation(@PathVariable("submissionId") String submissionId) {
        return desk.attestation(submissionId);
    }

    /** Runs its rules: VALIDATED when all pass; a failure stays DRAFT, recorded once, the way on a withdrawal and a new draft. */
    @PostMapping("/{submissionId}/validation")
    @RequiresPermission(PermissionName.REGULATORY_REPORT_PREPARE)
    @RequiresIdempotencyKey
    public RegulatorySubmissionDesk.RegulatorySubmissionReceipt validateRegulatorySubmission(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("submissionId") String submissionId) {
        return desk.validate(idempotencyKey, submissionId);
    }

    /** A preparer takes back a return not yet sent. Asks nothing of the month. A reason is required. */
    @PostMapping(path = "/{submissionId}/withdrawal", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.REGULATORY_REPORT_PREPARE)
    @RequiresIdempotencyKey
    public RegulatorySubmissionDesk.RegulatorySubmissionReceipt withdrawRegulatorySubmission(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("submissionId") String submissionId,
            @Valid @RequestBody RegulatorySubmissionDecisionRequest body) {
        return desk.withdraw(idempotencyKey, submissionId, body.reason());
    }

    /** Attests a validated return - a second person, from a MULTI_FACTOR session, naming the content's hash and the revision they read. A reason is required. */
    @PostMapping(path = "/{submissionId}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.REGULATORY_REPORT_APPROVE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    public RegulatorySubmissionDesk.RegulatorySubmissionReceipt approveRegulatorySubmission(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("submissionId") String submissionId,
            @Valid @RequestBody RegulatorySubmissionAttestationRequest body) {
        return desk.approve(idempotencyKey, submissionId, body.contentSha256(), body.periodRevision(), body.reason());
    }

    /** A controller's refusal of a validated or approved return: nothing is sent. Never the provider's rejection. Asks nothing of the month. A reason is required. */
    @PostMapping(path = "/{submissionId}/refusal", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.REGULATORY_REPORT_APPROVE)
    @RequiresIdempotencyKey
    public RegulatorySubmissionDesk.RegulatorySubmissionReceipt refuseRegulatorySubmission(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("submissionId") String submissionId,
            @Valid @RequestBody RegulatorySubmissionDecisionRequest body) {
        return desk.refuse(idempotencyKey, submissionId, body.reason());
    }

    /** Sends an approved return from a MULTI_FACTOR session; {@code 202}: SUBMITTED is committed, then the platform takes it to the adapter under its id and records the answer. Never its approver. A reason is required. */
    @PostMapping(path = "/{submissionId}/submission", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.REGULATORY_REPORT_SUBMIT)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RegulatorySubmissionDesk.RegulatorySubmissionReceipt sendRegulatorySubmission(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("submissionId") String submissionId,
            @Valid @RequestBody RegulatorySubmissionAttestationRequest body) {
        return desk.submit(idempotencyKey, submissionId, body.contentSha256(), body.periodRevision(), body.reason());
    }

    /** Concludes a return undelivered, from a MULTI_FACTOR session: only on the provider's definite "not received", the resends spent or its horizon past. A reason is required. */
    @PostMapping(path = "/{submissionId}/non-delivery", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.REGULATORY_REPORT_SUBMIT)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    public RegulatorySubmissionDesk.RegulatorySubmissionReceipt declareRegulatorySubmissionUndelivered(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("submissionId") String submissionId,
            @Valid @RequestBody RegulatorySubmissionDecisionRequest body) {
        return desk.declareUndelivered(idempotencyKey, submissionId, body.reason());
    }

    // -----------------------------------------------------------------

    /** A draft: the obligation version read, the month, the return it corrects if any, and why. */
    @ClosedBody
    public record RegulatorySubmissionDraftRequest(@Size(max = 64) String obligationId, @Size(max = 64) String periodId, @Size(max = 64) String correctsSubmissionId, @Size(max = 1000) String reason) {

        /** The reason stays out of a log line. */
        @Override
        public String toString() {
            return "RegulatorySubmissionDraftRequest[" + obligationId + ", " + periodId + "]";
        }
    }

    /** What an approver or a sender read, and why. */
    @ClosedBody
    public record RegulatorySubmissionAttestationRequest(@Size(max = 64) String contentSha256, int periodRevision, @Size(max = 1000) String reason) {

        /** The reason stays out of a log line. */
        @Override
        public String toString() {
            return "RegulatorySubmissionAttestationRequest[" + contentSha256 + "#" + periodRevision + "]";
        }
    }

    /** A withdrawal, a refusal or a conclusion: why. */
    @ClosedBody
    public record RegulatorySubmissionDecisionRequest(@Size(max = 1000) String reason) {

        /** The reason stays out of a log line. */
        @Override
        public String toString() {
            return "RegulatorySubmissionDecisionRequest[]";
        }
    }
}
