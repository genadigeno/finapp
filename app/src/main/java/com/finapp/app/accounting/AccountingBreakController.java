package com.finapp.app.accounting;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
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
 * The accounting break doors (`P14-TSK-010`; ADR-0117 decision 3; {@code PHASE_14_PLAN.md} sections 10, 12; owner
 * decisions C11, C12): the accountant lists a period's breaks under {@code ACCOUNTING_READ} and proposes acknowledging one
 * with a disclosure under {@code ACCOUNTING_ADJUST_PROPOSE}; a financial controller - a different person - reads one
 * break and approves its acknowledgement, or resolves it by reference to the correcting journal entry the owning domain
 * posted, under {@code ACCOUNTING_ADJUST_APPROVE} (identity {@code V025}'s partition gives the controller no
 * {@code ACCOUNTING_READ}, so it reads what it decides under its approve permission - the mapping diff's precedent).
 * Every act keyed per principal ({@code accounting.break:<type>:<id>}). Breaks are raised by the checks
 * ({@code AccountingChecks}, the close run's - `P14-TSK-011`), never by a door; nothing here posts. Reasons are
 * RESTRICTED-PII and never served; the disclosure is served. The handlers' names are distinctive (the springdoc
 * {@code operationId} rule).
 */
@RestController
@RequestMapping(path = "/operator/accounting/breaks", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AccountingBreakController {

    @NonNull private final AccountingBreakDesk desk;

    /**
     * One keyset page of a period's breaks ({@code period} a calendar month {@code YYYY-MM}; {@code status} optional;
     * {@code limit} 1 to 500, default 100; {@code after} the previous page's {@code next}), in birth order: each finding
     * with the register version it was judged by, its status and its acts. Audited {@code accounting.BreakRead}.
     */
    @GetMapping
    @RequiresPermission(PermissionName.ACCOUNTING_READ)
    public AccountingBreakDesk.AccountingBreakPage listAccountingBreaks(
            @RequestParam("period") String period,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "after", required = false) String after) {
        return desk.list(period, status, limit, after);
    }

    /**
     * One break, with its pending acknowledgement's disclosure - the controller's read of what it decides. Audited
     * {@code accounting.BreakRead}.
     */
    @GetMapping("/{breakId}")
    @RequiresPermission(PermissionName.ACCOUNTING_ADJUST_APPROVE)
    public AccountingBreakDesk.AccountingBreakView readAccountingBreak(@PathVariable("breakId") String breakId) {
        return desk.one(breakId);
    }

    /** Proposes acknowledging an OPEN break with a disclosure - nothing moves until a different person approves. */
    @PostMapping(path = "/{breakId}/acknowledgement-proposal", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_ADJUST_PROPOSE)
    @RequiresIdempotencyKey
    public AccountingBreakDesk.AccountingBreakReceipt proposeAccountingBreakAcknowledgement(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("breakId") String breakId,
            @Valid @RequestBody BreakAcknowledgementProposal body) {
        return desk.proposeAcknowledgement(idempotencyKey, breakId, body.disclosure());
    }

    /** Approves a pending acknowledgement - a different person's act: the break ACKNOWLEDGED; a reason is required. */
    @PostMapping(path = "/{breakId}/acknowledgement-approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_ADJUST_APPROVE)
    @RequiresIdempotencyKey
    public AccountingBreakDesk.AccountingBreakReceipt approveAccountingBreakAcknowledgement(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("breakId") String breakId,
            @Valid @RequestBody BreakActRequest body) {
        return desk.approveAcknowledgement(idempotencyKey, breakId, body.reason());
    }

    /**
     * Resolves an OPEN or ACKNOWLEDGED break by reference to the journal entry that corrected it - an existing entry
     * other than the one the break is about; nothing is posted. A reason is required.
     */
    @PostMapping(path = "/{breakId}/resolution", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_ADJUST_APPROVE)
    @RequiresIdempotencyKey
    public AccountingBreakDesk.AccountingBreakReceipt resolveAccountingBreak(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("breakId") String breakId,
            @Valid @RequestBody BreakResolution body) {
        return desk.resolve(idempotencyKey, breakId, body.correctingEntryId(), body.reason());
    }

    // -----------------------------------------------------------------

    /** The acknowledgement's disclosure - what the close and the statements' notes disclose; 1 to 1000 characters. */
    @ClosedBody
    public record BreakAcknowledgementProposal(@Size(max = 1000) String disclosure) {}

    /** An act's reason ({@code accounting.ReasonRequired} when missing). */
    @ClosedBody
    public record BreakActRequest(@Size(max = 1000) String reason) {}

    /** The correcting journal entry's id and the reason. */
    @ClosedBody
    public record BreakResolution(@Size(max = 64) String correctingEntryId, @Size(max = 1000) String reason) {}
}
