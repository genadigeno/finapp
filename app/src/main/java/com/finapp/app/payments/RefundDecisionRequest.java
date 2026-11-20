package com.finapp.app.payments;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The decider's reason on a refund proposal (`P17-TSK-031`): the refund request's own bound. */
public record RefundDecisionRequest(@NotBlank @Size(max = 1000) String reason) {}
