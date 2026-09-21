package com.finapp.app.merchant;

import com.finapp.platform.audit.AuditRecord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of every merchant standing move — suspend, reinstate, close (`P6-TSK-003`): the
 * operator's reason, required and bounded by {@link AuditRecord#MAX_REASON_LENGTH}, the
 * adjustment request's reasoning verbatim ({@code INV-AUD-03}: each of these is a judgement
 * about a counterparty, and the record refuses to exist without the why).
 */
public record MerchantStandingRequest(
        @NotBlank @Size(max = AuditRecord.MAX_REASON_LENGTH) String reason) {}
