package com.finapp.app.accounting;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The GL mapping doors (`P14-TSK-003`; ADR-0113 decision 3; {@code PHASE_14_PLAN.md} sections 10, 12; owner decisions
 * C2, C12): an accountant proposes a whole GL mapping version under {@code ACCOUNTING_MAPPING_PROPOSE}; a financial
 * controller - a different person - activates or rejects it under {@code ACCOUNTING_MAPPING_APPROVE}, each act keyed per
 * principal ({@code accounting.mapping:<type>:<id>}). The reads: a version whole under {@code ACCOUNTING_READ} (the
 * accountant's), and the version against the one in force with the live judgement against the ledger's chart under
 * {@code ACCOUNTING_MAPPING_APPROVE} - what the controller reviews before deciding (the grants are a partition, identity
 * {@code V025}: the controller reads what it acts on under its own permission). Reasons are RESTRICTED-PII and never
 * served. No customer or merchant route reads a mapping. The handlers' names are distinctive (the springdoc
 * {@code operationId} rule).
 */
@RestController
@RequestMapping(path = "/operator/accounting/mapping-versions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class GlMappingController {

    @NonNull private final GlMappingDesk desk;

    /** Proposes a whole new GL mapping version - coherent, and frozen from this moment. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_MAPPING_PROPOSE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public GlMappingDesk.GlMappingReceipt proposeGlMappingVersion(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody GlMappingProposalRequest body) {
        return desk.propose(idempotencyKey, body);
    }

    /**
     * Activates a pending version - a different person's act; refused unless total over the ledger's purposes and
     * classification-compatible; retires the version in force in the same transaction.
     */
    @PostMapping(path = "/{versionId}/activation", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_MAPPING_APPROVE)
    @RequiresIdempotencyKey
    public GlMappingDesk.GlMappingReceipt activateGlMappingVersion(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("versionId") String versionId,
            @Valid @RequestBody GlMappingDecisionRequest body) {
        return desk.activate(idempotencyKey, versionId, body.reason());
    }

    /** Rejects a pending version; a reason is required. */
    @PostMapping(path = "/{versionId}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_MAPPING_APPROVE)
    @RequiresIdempotencyKey
    public GlMappingDesk.GlMappingReceipt rejectGlMappingVersion(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("versionId") String versionId,
            @Valid @RequestBody GlMappingDecisionRequest body) {
        return desk.reject(idempotencyKey, versionId, body.reason());
    }

    /** One version by id, in any state: its machine row and its whole content. */
    @GetMapping(path = "/{versionId}")
    @RequiresPermission(PermissionName.ACCOUNTING_READ)
    public GlMappingDesk.GlMappingVersionView readGlMappingVersion(@PathVariable("versionId") String versionId) {
        return desk.version(versionId);
    }

    /**
     * The version against the one in force (or against {@code against}), with its judgement against the ledger's chart
     * now - what changes, and whether its activation would be refused.
     */
    @GetMapping(path = "/{versionId}/diff")
    @RequiresPermission(PermissionName.ACCOUNTING_MAPPING_APPROVE)
    public GlMappingDesk.GlMappingDiffView readGlMappingVersionDiff(
            @PathVariable("versionId") String versionId,
            @RequestParam(name = "against", required = false) String against) {
        return desk.diff(versionId, against);
    }

    // -----------------------------------------------------------------

    /**
     * A whole GL mapping version: the calendar month it takes effect from ({@code YYYY-MM}), its GL accounts (a 4 to 8
     * digit code, a name, a classification - {@code ASSET}, {@code LIABILITY}, {@code EQUITY}, {@code REVENUE},
     * {@code EXPENSE} - and a statement section admitting it) and one rule per ledger purpose and owner kind naming one
     * of those accounts; a reason. Every value is judged by the domain ({@code accounting.MappingInvalid}).
     */
    @ClosedBody
    public record GlMappingProposalRequest(
            @NotBlank @Size(min = 7, max = 7) String effectiveFromPeriod,
            @NotNull @Size(min = 1, max = 400) List<@Valid @NotNull GlMappingAccountRequest> accounts,
            @NotNull @Size(min = 1, max = 400) List<@Valid @NotNull GlMappingRuleRequest> rules,
            @Size(max = 1000) String reason) {}

    /** One GL account of the proposed chart. */
    @ClosedBody
    public record GlMappingAccountRequest(
            @NotBlank @Size(min = 4, max = 8) String code,
            @NotBlank @Size(min = 1, max = 120) String name,
            @NotBlank @Size(min = 1, max = 16) String classification,
            @NotBlank @Size(min = 1, max = 64) String section) {}

    /** One rule: a ledger purpose and its owner kind, mapped to a GL account code of the same version. */
    @ClosedBody
    public record GlMappingRuleRequest(
            @NotBlank @Size(min = 1, max = 64) String purpose,
            @NotBlank @Size(min = 1, max = 32) String ownerKind,
            @NotBlank @Size(min = 4, max = 8) String glAccountCode) {}

    /** A decision's reason - required, judged by the domain ({@code accounting.ReasonRequired}). */
    @ClosedBody
    public record GlMappingDecisionRequest(@Size(max = 1000) String reason) {}
}
