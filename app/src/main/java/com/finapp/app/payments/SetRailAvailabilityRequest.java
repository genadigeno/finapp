package com.finapp.app.payments;

import com.finapp.platform.audit.AuditRecord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body recording a rail as in or out of service (`P7-TSK-003`, ADR-0060 §4) — the
 * operator's fact every instance routes by. The reason is required either way
 * ({@code INV-AUD-03}): why a rail stopped, and why it was trusted again, are both what an
 * incident review reads.
 */
public record SetRailAvailabilityRequest(
        @NotNull Boolean available,
        @NotBlank @Size(max = AuditRecord.MAX_REASON_LENGTH) String reason) {}
