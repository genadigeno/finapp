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
import org.springframework.web.bind.annotation.RestController;

/**
 * The close run's doors (`P14-TSK-011`; ADR-0114 decision 4; {@code PHASE_14_PLAN.md} sections 10, 12, 13.5; {@code
 * INV-ACC-09}, {@code INV-ACC-05}): an accountant reads a period's close run under {@code ACCOUNTING_READ} - every
 * check's attempts with their evidence - and asks a check's next attempt under {@code ACCOUNTING_CLOSE_PROPOSE}, keyed
 * and reasoned. The checks themselves run on the schedule ({@code AccountingCloseSchedule}), never at a door, so a
 * re-run's answer is a {@code PENDING} attempt a worker claims. Proposal and approval are `P14-TSK-012`'s, and with
 * them a controller's read (identity {@code V025}'s partition gives the controller no {@code ACCOUNTING_READ}). The
 * handlers' names are distinctive (the springdoc {@code operationId} rule).
 */
@RestController
@RequestMapping(path = "/operator/accounting/periods", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class CloseRunController {

    @NonNull private final CloseRunDesk desk;

    /** The period's latest close run: each check's attempts, its evidence and whether a close may be proposed. Audited. */
    @GetMapping("/{periodId}/close-run")
    @RequiresPermission(PermissionName.ACCOUNTING_READ)
    public CloseRunDesk.CloseRunView readAccountingCloseRun(@PathVariable("periodId") String periodId) {
        return desk.closeRun(periodId);
    }

    /** Asks a check's next attempt - a new row, the earlier ones frozen; a reason is required. */
    @PostMapping(path = "/{periodId}/close-run/checks/{check}/rerun", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_PROPOSE)
    @RequiresIdempotencyKey
    public CloseRunDesk.CloseCheckRerunReceipt rerunAccountingCloseCheck(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("periodId") String periodId,
            @PathVariable("check") String check,
            @Valid @RequestBody CloseCheckRerunRequest body) {
        return desk.rerun(idempotencyKey, periodId, check, body.reason());
    }

    // -----------------------------------------------------------------

    /** The re-run's reason ({@code accounting.ReasonRequired} when missing). */
    @ClosedBody
    public record CloseCheckRerunRequest(@Size(max = 1000) String reason) {}
}
