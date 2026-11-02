package com.finapp.app.accounting;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operational report door (`P14-TSK-017`; ADR-0115 As built; {@code PHASE_14_PLAN.md} sections 10, 12, 13.6): an accountant
 * reads {@code RECONCILIATION_STATUS} or {@code CLOSE_STATUS} for a period under {@code ACCOUNTING_READ} - generated at the read on a
 * read-only snapshot, stored nowhere, labelled {@code OPERATIONAL}, every read audited. These two read what keeps moving after a lock,
 * so they are never a report run: {@code POST .../report-runs} refuses them, and this door refuses every other definition. The content
 * is {@code CONFIDENTIAL}. The handler's name is distinctive (the springdoc {@code operationId} rule).
 */
@RestController
@RequestMapping(path = "/operator/accounting/periods", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class OperationalReportController {

    @NonNull private final OperationalReportDesk desk;

    /** An operational report for a period: its content, verifiable against its sha256, as of the database's instant. Audited. */
    @GetMapping("/{periodId}/operational-reports/{definition}")
    @RequiresPermission(PermissionName.ACCOUNTING_READ)
    public OperationalReportDesk.OperationalReportView readOperationalReport(
            @PathVariable("periodId") String periodId, @PathVariable("definition") String definition) {
        return desk.read(periodId, definition);
    }
}
