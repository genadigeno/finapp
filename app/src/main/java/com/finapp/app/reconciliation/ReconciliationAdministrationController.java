package com.finapp.app.reconciliation;

import com.finapp.app.session.RecordsRefusals;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
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
 * Operating reconciliation without editing history (`P8-TSK-022`, ADR-0068 §§8-9): matching
 * policy changed forward only and under four-eyes - a rule set version proposed, activated by a
 * second controller, or rejected - and reprocessing a
 * source's residual items and requeueing a blocked run under
 * {@link PermissionName#RECONCILIATION_ADMINISTER} - the controller role, disjoint from the
 * resolver's - and replaying a run's decisions under
 * {@link PermissionName#RECONCILIATION_INVESTIGATE}. Handler names are deliberately distinctive
 * (the springdoc {@code operationId} rule, {@code OpenApiContractTest}).
 */
@RestController
@RecordsRefusals
@RequestMapping(
        path = "/operator/reconciliation",
        produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ReconciliationAdministrationController {

    @NonNull private final ReconciliationAdministrationDesk desk;

    /** A whole proposed version: a change is a NEW version, never an edit. */
    public record RuleSetProposalRequest(
            @NotBlank @Size(max = 100) String sourceCode,
            @NotBlank @Size(min = 1, max = 1000) String reason,
            @Min(0) @Max(365) int fundingLagDays,
            @Min(0) @Max(3650) int gainMinAgeDays,
            @NotNull Map<String, @NotNull @Min(0) @Max(365) Integer> lagDays,
            @NotNull @Size(min = 1, max = 64) List<@NotNull @Valid RuleRequest> rules,
            @NotNull @Size(max = 64) List<@NotNull @Valid ToleranceRequest> tolerances,
            @NotNull @Size(max = 64) List<@NotNull @Valid FeeTermsRequest> feeSchedules,
            @NotNull Map<String, @NotNull @Min(0) Long> severityThresholds) {}

    /** One matching rule of the proposed version. */
    public record RuleRequest(
            @Min(1) @Max(1000) int priority,
            @NotBlank @Size(max = 64) String lineType,
            @Size(max = 64) String keyKind,
            @Size(max = 64) String expectationKind,
            @NotBlank @Size(max = 64) String cardinality,
            boolean operationAnchored,
            @Min(0) @Max(8760) int graceHours) {}

    /** A tolerance: on a fee's pinned terms or a date - never an amount (INV-REC-08). */
    public record ToleranceRequest(
            @NotBlank @Size(max = 64) String comparison,
            @Size(max = 3) String currency,
            @Min(0) Long absoluteMinor,
            @Min(0) @Max(365) Integer days) {}

    /** A provider's pinned fee terms; the rate as a decimal string, never a float. */
    public record FeeTermsRequest(
            @NotBlank @Size(max = 64) String lineType,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotBlank @Pattern(regexp = "[0-9](\\.[0-9]{1,6})?") String rate,
            @Min(0) long fixedMinor,
            @Min(0) @Max(9) int scale,
            @NotBlank @Size(max = 16) String roundingPolicy) {}

    /** A second controller's activation, or any controller's rejection: reasoned. */
    public record RuleSetDecisionRequest(@NotBlank @Size(min = 1, max = 1000) String reason) {}

    /** A controller's reason for reprocessing a source. */
    public record ReprocessingRequest(@NotBlank @Size(min = 1, max = 1000) String reason) {}

    /** A controller's reason for requeueing a blocked run. */
    public record RunRequeueRequest(@NotBlank @Size(min = 1, max = 1000) String reason) {}

    /**
     * A source's rule set versions, each with its whole content, as a section 7 collection (`P17-TSK-122`; read "the newest
     * 20 with {@code truncated}" until then): version descending; {@code limit} 20 unless named, 50 at most; the opaque
     * {@code cursor} of the page before. The source stays required.
     */
    @GetMapping("/rule-sets")
    @RequiresPermission(PermissionName.RECONCILIATION_ADMINISTER)
    public ReconciliationAdministrationDesk.RuleSetPage listReconciliationRuleSets(
            @RequestParam("source") String source,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.ruleSets(source, cursor, limit);
    }

    /**
     * One version with its whole content (`P17-TSK-127`): what a pending {@code reconciliation.RuleSet} names, read before it
     * is decided. A read, so on a password session (the owner's D-6); unknown and malformed are one 404.
     */
    @GetMapping("/rule-sets/{id}")
    @RequiresPermission(PermissionName.RECONCILIATION_ADMINISTER)
    public ReconciliationAdministrationDesk.RuleSetVersion readReconciliationRuleSet(@PathVariable("id") String id) {
        return desk.ruleSet(id);
    }

    /** Proposes a new version: frozen at once, awaiting a different controller. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/rule-sets", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RECONCILIATION_ADMINISTER)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public ReconciliationAdministrationDesk.RuleSetReceipt proposeReconciliationRuleSet(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody RuleSetProposalRequest body) {
        return desk.proposeRuleSet(idempotencyKey, body);
    }

    /**
     * Activates a proposed version - never its proposer's act - retiring its predecessor in the
     * same transaction. A new version governs only new decisions.
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(
            path = "/rule-sets/{id}/approval",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RECONCILIATION_ADMINISTER)
    public ReconciliationAdministrationDesk.RuleSetReceipt approveReconciliationRuleSet(
            @PathVariable("id") String id, @Valid @RequestBody RuleSetDecisionRequest body) {
        return desk.approveRuleSet(id, body.reason());
    }

    /** Rejects a proposed version; its proposer may withdraw it this way. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(
            path = "/rule-sets/{id}/rejection",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RECONCILIATION_ADMINISTER)
    public ReconciliationAdministrationDesk.RuleSetReceipt rejectReconciliationRuleSet(
            @PathVariable("id") String id, @Valid @RequestBody RuleSetDecisionRequest body) {
        return desk.rejectRuleSet(id, body.reason());
    }

    /**
     * Opens a {@code REPROCESS} run over the source's residual items, pinned to the version
     * active now; the reprocess leg works it on any instance. One open per source.
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(
            path = "/sources/{code}/reprocessing",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RECONCILIATION_ADMINISTER)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReconciliationAdministrationDesk.ReprocessingReceipt requestReconciliationReprocessing(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("code") String code,
            @Valid @RequestBody ReprocessingRequest body) {
        return desk.requestReprocessing(idempotencyKey, code, body.reason());
    }

    /** {@code BLOCKED -> IN_PROGRESS}: any instance's next tick resumes the run. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/runs/{id}/requeue", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RECONCILIATION_ADMINISTER)
    public ReconciliationAdministrationDesk.RunRequeuedReceipt requeueReconciliationRun(
            @PathVariable("id") String id, @Valid @RequestBody RunRequeueRequest body) {
        return desk.requeue(id, body.reason());
    }

    /**
     * Replays every stored decision of the run under its pinned version and appends the
     * verdict - {@code IDENTICAL} or {@code DIVERGED}, items merely awaiting a rematch apart.
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/runs/{id}/replay")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public ReconciliationAdministrationDesk.RunReplayVerdict replayReconciliationRun(
            @PathVariable("id") String id) {
        return desk.replay(id);
    }
}
