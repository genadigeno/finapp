package com.finapp.app.paymentmethods;

import com.finapp.sharedkernel.security.Sensitive;
import jakarta.validation.constraints.NotNull;

/**
 * The body of {@code POST /v1/me/payment-methods} (`P5-TSK-005`).
 *
 * <p>One field, and it is the whole design: the client tokenises the card <em>outside</em> the
 * platform and hands us only the provider's one-time grant — never the number, never display
 * metadata (which comes from the provider, so a client cannot lie about brand or last4). The
 * field is {@link Sensitive} so the request's every rendering masks (the
 * {@code AuthenticationRequest} precedent — a record's generated {@code toString} prints every
 * component, and this one is a chargeable-instrument reference for its validity window); the
 * shape rule — including the refusal of card-number-shaped values, {@code INV-PAY-02} at the
 * surface — is {@code TokenisationGrant}'s, the one definition, mapped to a 422 naming the
 * field by the service.
 */
public record AttachPaymentMethodRequest(@NotNull Sensitive<String> clientToken) {}
