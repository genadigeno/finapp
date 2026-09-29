package com.finapp.app.settlement;

import com.finapp.platform.audit.AuditRecord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /v1/operator/settlement/files/'{id}'/content-reads} (`P8-TSK-003`,
 * `INV-REC-10`): the investigator's reason, required because the audited unit is the access —
 * one record per read, and an unreasoned access is not recordable.
 */
public record SettlementContentReadRequest(
        @NotBlank @Size(max = AuditRecord.MAX_REASON_LENGTH) String reason) {}
