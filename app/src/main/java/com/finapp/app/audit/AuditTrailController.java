package com.finapp.app.audit;

import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit trail, read (`P17-TSK-017`, gap G-20; finapp-admin-panel ADR-C134): under {@code AUDIT_READ} - the {@code AUDITOR}'s
 * alone - on a {@code MULTI_FACTOR} session, and every read recorded. Two reads and nothing else: no door updates or deletes a
 * record. A target's history - a detail page's History tab - is the list with {@code targetType} and {@code targetId}.
 */
@RestController
@RequestMapping(path = "/operator/audit-records", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AuditTrailController {

    @NonNull private final AuditTrailService trail;

    /**
     * A §7 collection of the records of a required window - {@code from} and {@code to}, instants, {@code [from, to)}, thirty-one
     * days at most - newest first by {@code occurredAt}, the id breaking the tie; {@code limit} 50 unless named, 200 at most; the
     * opaque {@code cursor} of the page before. Exact-match filters: {@code actor}, {@code operation}, {@code targetType},
     * {@code targetId}, {@code correlationId}, {@code outcome}. A record committed after the walk passed its stamp is in no later
     * page of that walk, and a fresh walk shows it.
     */
    @GetMapping
    @RequiresPermission(PermissionName.AUDIT_READ)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public AuditTrailService.AuditRecordPage listAuditRecords(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "actor", required = false) String actor,
            @RequestParam(name = "operation", required = false) String operation,
            @RequestParam(name = "targetType", required = false) String targetType,
            @RequestParam(name = "targetId", required = false) String targetId,
            @RequestParam(name = "correlationId", required = false) String correlationId,
            @RequestParam(name = "outcome", required = false) String outcome,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return trail.page(from, to, actor, operation, targetType, targetId, correlationId, outcome, cursor, limit);
    }

    /** One record, in full. */
    @GetMapping("/{id}")
    @RequiresPermission(PermissionName.AUDIT_READ)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public AuditTrailService.AuditRecordView readAuditRecord(@PathVariable("id") String auditId) {
        return trail.record(auditId);
    }
}
