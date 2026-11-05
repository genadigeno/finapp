package com.finapp.app.ledger;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /v1/ledger/projection-rebuilds} (`P14-TSK-026`, ADR-0119 decision 1; ADR-0041 rule 2; {@code INV-BAL-02},
 * {@code INV-AUD-04}): the way on from {@code PROJECTION_VERIFIED}'s {@code PROJECTION_DRIFT} - a four-eyes rebuild of one
 * account's balance projection from its journal, <strong>never touching a journal line</strong>. {@code POST} proposes,
 * recording the drift observed, and writes nothing to the projection; {@code POST .../approval} by a <em>different</em>
 * operator on a {@code MULTI_FACTOR} session recomputes under the posting's locks and writes what it computed;
 * {@code POST .../rejection} declines or withdraws; {@code GET} shows the approver what they decide.
 *
 * <p><strong>{@code LEDGER_ADJUST}, the ledger operator's</strong> (least privilege by role: {@code LEDGER_OPERATOR} alone
 * holds it - an accountant who sees the drift hands the account to a ledger operator; a ledger operator closes no month).
 * The approval's {@code MULTI_FACTOR} is the platform's rule for a ledger operator's approval, the adjustment's since
 * `P14-TSK-022`. <strong>Every door keyed</strong>: a lost response replays; a key reused by another operator or for another
 * account is {@code 409 api.IdempotencyConflict}. The handlers' names are distinctive (the springdoc {@code operationId}
 * rule).
 */
@RestController
@RequestMapping(path = "/ledger/projection-rebuilds", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ProjectionRebuildController {

    @NonNull private final LedgerProjectionRebuilds rebuilds;

    /**
     * Proposes rebuilding the account's projection, recording the drift observed - refused when it agrees with its journal
     * ({@code 409 ledger.ProjectionNotDrifting}), when a rebuild of it already stands ({@code 409
     * ledger.ProjectionRebuildStanding}) or when its history or its row's scale is the migrator's ({@code 409
     * ledger.ProjectionNotRebuildable}). Nothing is written to the projection.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LEDGER_ADJUST)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public LedgerProjectionRebuilds.RebuildView proposeProjectionRebuild(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody ProjectionRebuildRequest body) {
        return rebuilds.propose(body.ledgerAccountId(), body.reason(), idempotencyKey);
    }

    /** The rebuild in full: what the proposer observed, and, once decided, what the approval found and wrote. */
    @GetMapping("/{id}")
    @RequiresPermission(PermissionName.LEDGER_ADJUST)
    public LedgerProjectionRebuilds.RebuildView readProjectionRebuild(@PathVariable("id") String id) {
        return rebuilds.view(id);
    }

    /**
     * Approves as a second person on a {@code MULTI_FACTOR} session ({@code 403 identity.AssuranceRequired} below it, before
     * anything is read), and applies it: {@code APPLIED}, or {@code UNCHANGED} when nothing was left to correct. Self-approval
     * is {@code 409 ledger.RebuildSelfApprovalRefused}, nothing written; a decided rebuild {@code 409 ledger.RebuildNotOpen};
     * the same approver's retry answers the recorded outcome.
     */
    @PostMapping(path = "/{id}/approval")
    @RequiresPermission(PermissionName.LEDGER_ADJUST)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public LedgerProjectionRebuilds.RebuildView approveProjectionRebuild(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey, @PathVariable("id") String id) {
        return rebuilds.approve(id, idempotencyKey);
    }

    /** Rejects - or, for its proposer, withdraws - a standing rebuild; a repeat converges. An approved one is a {@code 409}. */
    @PostMapping(path = "/{id}/rejection")
    @RequiresPermission(PermissionName.LEDGER_ADJUST)
    @RequiresIdempotencyKey
    public LedgerProjectionRebuilds.RebuildView rejectProjectionRebuild(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey, @PathVariable("id") String id) {
        return rebuilds.reject(id, idempotencyKey);
    }

    /**
     * The account to rebuild and why. The reason is free prose bound for the rebuild's reason column and the audit record's
     * ({@code RESTRICTED-FINANCIAL}, never logged), required and bounded by both.
     */
    @ClosedBody
    public record ProjectionRebuildRequest(
            @NotBlank @Size(min = 36, max = 36) String ledgerAccountId, @NotBlank @Size(max = 1000) String reason) {}
}
