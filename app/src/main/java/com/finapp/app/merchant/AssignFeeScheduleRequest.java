package com.finapp.app.merchant;

import com.finapp.platform.audit.AuditRecord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The body pointing a merchant at a fee schedule (`P6-TSK-004`).
 *
 * <p>The reason is required and bounded by {@link AuditRecord#MAX_REASON_LENGTH}
 * ({@code INV-AUD-03}): changing what a counterparty is charged is a commercial judgement
 * about that counterparty, and the record refuses to exist without the why.
 */
public record AssignFeeScheduleRequest(
        @NotNull UUID feeScheduleId,
        @NotBlank @Size(max = AuditRecord.MAX_REASON_LENGTH) String reason) {}
