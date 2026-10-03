package com.finapp.app.settlement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * {@code POST /v1/operator/settlement/sources/{code}/fetch}'s body (`P8-TSK-021`): which report
 * to pull now — an ISO business date for a daily report, or a scheme cycle token.
 *
 * @param businessKey the {@code settlement.pull_permit} key's own shape
 */
public record SettlementFetchRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,63}") String businessKey) {}
