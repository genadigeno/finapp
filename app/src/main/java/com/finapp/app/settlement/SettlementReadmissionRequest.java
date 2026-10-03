package com.finapp.app.settlement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * `POST /v1/operator/settlement/files/{id}/readmission` (`P8-TSK-022`, ADR-0066 §8): a
 * controller's reasoned recovery of a file our own validation rejected (or a declined one, a
 * conflicting batch's whose conflict is gone, or an accepted file whose batch was repudiated -
 * the Phase 8 -> 9 transition, MI-2) - the reason bound for the readmission's birth
 * event and the audit record ({@code INV-AUD-03}), never rendered by any {@code toString}.
 */
public record SettlementReadmissionRequest(@NotBlank @Size(min = 1, max = 1000) String reason) {}
