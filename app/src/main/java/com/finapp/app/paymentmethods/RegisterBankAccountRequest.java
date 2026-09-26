package com.finapp.app.paymentmethods;

import com.finapp.sharedkernel.security.Sensitive;
import jakarta.validation.constraints.NotNull;

/**
 * The body of {@code POST /v1/me/payment-methods/bank-accounts} (`P7-TSK-007`).
 *
 * <p>The {@code AttachPaymentMethodRequest} design at the bank boundary: the customer links
 * their account <em>at the rail provider</em> and hands us only the provider's one-time grant
 * — never an account number, an international identifier or an alias, which are refused at
 * every layer they could reach ({@code INV-RAIL-03}). The grant is {@link Sensitive} so the
 * request's every rendering masks (a linking capability for its validity window); it is
 * single-use at the provider, which is why this door is keyed where the card attach is not.
 *
 * @param grant the rail provider's one-time linking grant, wrapped end to end: the
 *     deserialiser at the boundary, one unwrap onto the exchange wire
 * @param acknowledgeNoMatch the customer's explicit "register even though the payee name did
 *     not match" (ADR-0062 §2). Required exactly when the check answers {@code NO_MATCH} —
 *     absent there, the register is refused on the record with
 *     {@code paymentmethods.PayeeCheckNoMatch} — and vacuous otherwise: consent to a mismatch
 *     that did not happen is recorded nowhere
 */
public record RegisterBankAccountRequest(
        @NotNull Sensitive<String> grant, boolean acknowledgeNoMatch) {}
