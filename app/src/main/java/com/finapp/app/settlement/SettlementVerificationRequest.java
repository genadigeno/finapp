package com.finapp.app.settlement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * `POST /v1/operator/settlement/files/{id}/verification` (`P8-TSK-022`, ADR-0066 §9): an
 * investigator's reasoned re-parse of a stored file under its recorded format version - it
 * reads the content, so every access carries a reason ({@code INV-REC-10}).
 */
public record SettlementVerificationRequest(@NotBlank @Size(min = 1, max = 1000) String reason) {}
