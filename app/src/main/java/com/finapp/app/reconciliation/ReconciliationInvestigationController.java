package com.finapp.app.reconciliation;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
 * The investigator's doors (`P8-TSK-014`, ADR-0069 §7): break reads, the case file, the
 * trace, expectation reads and an operation's settlement status — every one under
 * {@link PermissionName#RECONCILIATION_INVESTIGATE} (the {@code RECONCILIATION_OPERATOR}
 * role; the controller's role alone is refused). Reads are not audited per serving (the
 * backlog's closed audit list); the four case-file writes each carry their own audit record.
 * Handler names are deliberately distinctive (the springdoc {@code operationId} rule).
 */
@RestController
@RequestMapping(
        path = "/operator/reconciliation",
        produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ReconciliationInvestigationController {

    @NonNull private final BreakInvestigation investigation;

    /** The assignee — an identity's identifier. */
    public record BreakAssignmentRequest(@NotNull String assigneeId) {}

    /**
     * A note's body, 1..4000 characters, screened at the domain and the database for
     * card-number and bank-account shapes. CONFIDENTIAL: never logged, evented or audited.
     */
    public record BreakNoteRequest(@NotNull String body) {}

    /** A link by identifier into the stored chain; {@code OPERATION} is {@code <kind>:<ref>}. */
    public record BreakEvidenceLinkRequest(@NotNull String targetKind, @NotNull String targetRef) {}

    /** The new type and the investigator's reason (1..1000 characters). */
    public record BreakClassificationRequest(@NotNull String type, @NotNull String reason) {}

    // ------------------------------------------------------------------ break reads

    /** The newest breaks matching the filters, bounded at 100 with {@code truncated}. */
    @GetMapping("/breaks")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public BreakInvestigation.BreakList listReconciliationBreaks(
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "source", required = false) String source,
            @RequestParam(name = "assignee", required = false) String assignee,
            @RequestParam(name = "agedOver", required = false) Integer agedOver) {
        return investigation.listBreaks(type, status, severity, source, assignee, agedOver);
    }

    /** One break's case file: history, notes, evidence links, resolutions, predecessors. */
    @GetMapping("/breaks/{id}")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public BreakInvestigation.BreakDetail viewReconciliationBreak(
            @PathVariable("id") String breakId) {
        return investigation.viewBreak(breakId);
    }

    /** The stored identifier chain from the break to the raw file and the journal entries. */
    @GetMapping("/breaks/{id}/trace")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public BreakInvestigation.TraceView traceReconciliationBreak(
            @PathVariable("id") String breakId) {
        return investigation.traceBreak(breakId);
    }

    // ------------------------------------------------------------------ the case file

    /** Assigns the break; the first assignment opens the investigation. Idempotent by state. */
    @PostMapping(path = "/breaks/{id}/assignment", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public BreakInvestigation.BreakView assignReconciliationBreak(
            @PathVariable("id") String breakId,
            @Valid @RequestBody BreakAssignmentRequest body) {
        return investigation.assign(breakId, body.assigneeId());
    }

    /** Appends a note, keyed per principal; a replay answers the same receipt. */
    @PostMapping(path = "/breaks/{id}/notes", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public BreakInvestigation.NoteReceipt addReconciliationBreakNote(
            @PathVariable("id") String breakId,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody BreakNoteRequest body) {
        return investigation.addNote(breakId, idempotencyKey, body.body());
    }

    /** Links stored evidence by identifier, verified to exist; keyed per principal. */
    @PostMapping(
            path = "/breaks/{id}/evidence-links",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public BreakInvestigation.LinkReceipt linkReconciliationBreakEvidence(
            @PathVariable("id") String breakId,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody BreakEvidenceLinkRequest body) {
        return investigation.linkEvidence(
                breakId, idempotencyKey, body.targetKind(), body.targetRef());
    }

    /** Reclassifies the break, reasoned; in OPEN and INVESTIGATING only. Idempotent by state. */
    @PostMapping(
            path = "/breaks/{id}/classification",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public BreakInvestigation.BreakView reclassifyReconciliationBreak(
            @PathVariable("id") String breakId,
            @Valid @RequestBody BreakClassificationRequest body) {
        return investigation.reclassify(breakId, body.type(), body.reason());
    }

    // ------------------------------------------------------------------ expectations

    /** The newest expectations matching the filters, bounded at 100 with {@code truncated}. */
    @GetMapping("/expectations")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public BreakInvestigation.ExpectationList listSettlementExpectations(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "overdue", required = false) Boolean overdue,
            @RequestParam(name = "source", required = false) String source) {
        return investigation.listExpectations(status, overdue, source);
    }

    /** One expectation: keys, history, allocations and the breaks standing on it. */
    @GetMapping("/expectations/{id}")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public BreakInvestigation.ExpectationDetail viewSettlementExpectation(
            @PathVariable("id") String expectationId) {
        return investigation.viewExpectation(expectationId);
    }

    /** An operation's settlement status, derived, with its identifier trail. */
    @GetMapping("/settlement-status")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public BreakInvestigation.SettlementStatusView viewOperationSettlementStatus(
            @RequestParam(name = "kind") String kind,
            @RequestParam(name = "operationRef") String operationRef) {
        return investigation.settlementStatus(kind, operationRef);
    }
}
