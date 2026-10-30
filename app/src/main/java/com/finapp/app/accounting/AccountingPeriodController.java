package com.finapp.app.accounting;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The accounting period doors (`P14-TSK-005`; ADR-0114 decision 3; {@code PHASE_14_PLAN.md} sections 10, 12, 13.5;
 * owner decisions C9, C12): an accountant locks a period under {@code ACCOUNTING_CLOSE_PROPOSE} - the ledger's posting
 * cutoff advanced to its end and the GL mapping version pinned, in one transaction - and proposes its unlock under the
 * same permission; a financial controller - a different person - approves the unlock under
 * {@code ACCOUNTING_CLOSE_APPROVE}. Every act keyed per principal ({@code accounting.period:<type>:<id>}). The read: the
 * latest periods under {@code ACCOUNTING_READ}. Periods are born by the system ({@code AccountingPeriods.openNext}), never
 * by a door. Reasons are RESTRICTED-PII and never served. The handlers' names are distinctive (the springdoc
 * {@code operationId} rule).
 */
@RestController
@RequestMapping(path = "/operator/accounting/periods", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AccountingPeriodController {

    @NonNull private final AccountingPeriodDesk desk;

    /** The latest periods, newest first ({@code limit} 1 to 120, default 24): status, grace, pin, a pending unlock. */
    @GetMapping
    @RequiresPermission(PermissionName.ACCOUNTING_READ)
    public List<AccountingPeriodDesk.AccountingPeriodView> listAccountingPeriods(
            @RequestParam(name = "limit", required = false) Integer limit) {
        return desk.list(limit);
    }

    /**
     * Locks an OPEN period after its grace, the previous period closed: from the commit no entry dated in it commits.
     * A second lock answers LOCKED.
     */
    @PostMapping(path = "/{periodId}/lock", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_PROPOSE)
    @RequiresIdempotencyKey
    public AccountingPeriodDesk.AccountingPeriodReceipt lockAccountingPeriod(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("periodId") String periodId,
            @Valid @RequestBody AccountingPeriodActRequest body) {
        return desk.lock(idempotencyKey, periodId, body.reason());
    }

    /** Proposes unlocking a LOCKED period - nothing moves until a different person approves; a reason is required. */
    @PostMapping(path = "/{periodId}/unlock-proposal", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_PROPOSE)
    @RequiresIdempotencyKey
    public AccountingPeriodDesk.AccountingPeriodReceipt proposeAccountingPeriodUnlock(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("periodId") String periodId,
            @Valid @RequestBody AccountingPeriodActRequest body) {
        return desk.proposeUnlock(idempotencyKey, periodId, body.reason());
    }

    /**
     * Approves a pending unlock - a different person's act: the cutoff moved back one period, the period OPEN again;
     * a reason is required.
     */
    @PostMapping(path = "/{periodId}/unlock-approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_APPROVE)
    @RequiresIdempotencyKey
    public AccountingPeriodDesk.AccountingPeriodReceipt approveAccountingPeriodUnlock(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("periodId") String periodId,
            @Valid @RequestBody AccountingPeriodActRequest body) {
        return desk.approveUnlock(idempotencyKey, periodId, body.reason());
    }

    // -----------------------------------------------------------------

    /** An act's reason - optional for the lock, required for the unlock's two acts ({@code accounting.ReasonRequired}). */
    @ClosedBody
    public record AccountingPeriodActRequest(@Size(max = 1000) String reason) {}
}
