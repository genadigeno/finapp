package com.finapp.app.payments;

import com.finapp.platform.audit.AuditRecord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of an operator's dispute answer — {@code .../representment} or
 * {@code .../acceptance} (`P7-TSK-014`): the reason, required and bounded, because contesting or
 * conceding a chargeback on behalf is a privileged act ({@code PHASE_7_PLAN.md} §11,
 * {@code INV-AUD-03}). The merchant's own routes take no body: the dispute and the route say it
 * all, and the evidence is the dispute's.
 */
public record OperatorDisputeResponseRequest(
        @NotBlank @Size(max = AuditRecord.MAX_REASON_LENGTH) String reason) {}
