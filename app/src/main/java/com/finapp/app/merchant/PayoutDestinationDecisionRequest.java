package com.finapp.app.merchant;

import com.finapp.platform.audit.AuditRecord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of every payout destination decision — approve, reject, withdraw (`P6-TSK-011`): the
 * operator's reason, required and bounded by {@link AuditRecord#MAX_REASON_LENGTH}, the
 * {@code MerchantStandingRequest} reasoning ({@code INV-AUD-03}).
 */
public record PayoutDestinationDecisionRequest(
        @NotBlank @Size(max = AuditRecord.MAX_REASON_LENGTH) String reason) {}
