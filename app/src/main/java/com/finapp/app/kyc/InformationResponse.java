package com.finapp.app.kyc;

import com.finapp.kyc.CustomerAnswer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /v1/me/kyc/information-response} (`P17-TSK-126`, ADR-0155 decision 6): the customer's answer, in their
 * own words. Documents go first through {@code POST /v1/me/kyc/documents}.
 *
 * @param answer the customer's answer: 1 to 2,000 characters; RESTRICTED-PII, shown to reviewers only
 */
public record InformationResponse(@NotBlank @Size(max = CustomerAnswer.MAX_LENGTH) String answer) {}
