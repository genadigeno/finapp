package com.finapp.app.payments;

import com.finapp.platform.audit.AuditRecord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /v1/payments/'{id}'/void} (`P7-TSK-004`): the operator's reasoned
 * release of an uncaptured authorization. The reason is <strong>required</strong>
 * ({@code INV-AUD-03}, the refund's precedent) — the customer's own cancellation path
 * carries none, because withdrawing one's own payment speaks for itself.
 */
public record VoidPaymentRequest(
        @NotBlank @Size(max = AuditRecord.MAX_REASON_LENGTH) String reason) {}
