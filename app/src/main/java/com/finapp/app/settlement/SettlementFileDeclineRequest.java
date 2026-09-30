package com.finapp.app.settlement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * `POST /v1/operator/settlement/files/{id}/decline` (`P8-TSK-008`, moved from `P8-TSK-003`):
 * a person's reasoned refusal — the reason is bound for the file history's and the audit
 * record's reason columns ({@code INV-AUD-03}), free prose by an operator, never rendered by
 * any {@code toString}.
 */
public record SettlementFileDeclineRequest(
        @NotBlank @Size(min = 1, max = 1000) String reason) {}
