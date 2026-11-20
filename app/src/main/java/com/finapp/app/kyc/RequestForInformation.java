package com.finapp.app.kyc;

import com.finapp.kyc.CustomerRequestText;
import com.finapp.kyc.InformationRequestReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /v1/kyc/cases/&#123;id&#125;/information-request} (`P17-TSK-126`, ADR-0155 decision 3).
 *
 * <p>The bounds are declared here for the contract and held again by the domain: {@link CustomerRequestText#of} is the
 * tipping-off control, and a text it refuses is {@code 422 kyc.RequestTextRefused} naming the rule.
 *
 * @param reasonCode why the reviewer asks, named for what the customer must supply; internal, never shown to the customer
 * @param requestText what the customer reads: no screening, list, suspicion or investigation vocabulary, no identifier of the
 *     platform's, no link
 */
public record RequestForInformation(
        @NotNull InformationRequestReason reasonCode,
        @NotNull @Size(min = CustomerRequestText.MIN_LENGTH, max = CustomerRequestText.MAX_LENGTH) String requestText) {}
