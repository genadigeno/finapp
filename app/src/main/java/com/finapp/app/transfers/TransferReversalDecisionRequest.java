package com.finapp.app.transfers;

import com.finapp.app.administration.SuspensionRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The decider's reason on a transfer reversal proposal (`P17-TSK-031`): the reversal request's own bounds and charset, so an
 * approval's reason is what the reversal's audit record carries.
 */
public record TransferReversalDecisionRequest(
        @NotBlank
                @Size(min = SuspensionRequest.REASON_MIN, max = SuspensionRequest.REASON_MAX)
                @Pattern(regexp = SuspensionRequest.REASON_CHARSET)
                String reason) {}
