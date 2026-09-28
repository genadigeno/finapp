package com.finapp.app.payments;

import com.finapp.payments.DisputeEvidenceContentType;
import com.finapp.payments.DisputeEvidenceKind;
import com.finapp.platform.audit.AuditRecord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /v1/operator/disputes/'{disputeId}'/evidence} (`P7-TSK-014`): the
 * merchant's upload plus the operator's reason — evidence on behalf is a privileged act, named,
 * reasoned and audited ({@code PHASE_7_PLAN.md} §11, {@code INV-AUD-03}), and only for a payment
 * with no merchant.
 */
public record OperatorDisputeEvidenceUploadRequest(
        @NotNull DisputeEvidenceKind kind,
        @NotNull DisputeEvidenceContentType contentType,
        @NotBlank @Size(max = DisputeEvidenceUploadRequest.MAX_BASE64_LENGTH) String content,
        @NotBlank @Size(max = AuditRecord.MAX_REASON_LENGTH) String reason) {}
