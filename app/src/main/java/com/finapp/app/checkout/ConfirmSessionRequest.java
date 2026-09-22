package com.finapp.app.checkout;

import com.finapp.sharedkernel.security.Sensitive;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * The body confirming a checkout session (`P6-TSK-007`): which offer, and which of the
 * customer's own saved instruments pays for it.
 *
 * <h2>The token is in the BODY, and a build rule is why</h2>
 *
 * <p>The first design put it in the path — {@code /v1/checkout/sessions/{token}/confirmation} —
 * which is how hosted checkout usually looks, and {@code CredentialReachesNoEmittedSinkTest}
 * was right to refuse it. Its own reasoning says a secret <em>"may be sent and must never be
 * returned or put where a URL goes: in a query parameter or a header it is in every access
 * log, proxy log and browser history between here and the caller"</em>, and a path segment is
 * a URL. A bearer credential that confirms a payment does not belong in a log line.
 *
 * <p>So the token is sent the one way the platform permits a secret to travel: a request body,
 * where the idempotency fingerprint already excludes it ({@code P1-TSK-006}) and
 * {@link Sensitive} wraps it the moment it is deserialised ({@code P1-TSK-010}'s shape, at a
 * fourth credential).
 *
 * <h2>Wrapped, so nothing can print it by accident</h2>
 *
 * <p>{@code Sensitive} makes a record's generated {@code toString}, a serialiser's fallback and
 * a stray {@code log.info("{}", request)} render the mask rather than the value — the
 * default-deny redaction {@code INV-AUD-02} rests on, with no getter call needed to go wrong.
 * Bean Validation cannot see inside it, so the token is <strong>not</strong> shape-checked
 * here: one that matches nothing is refused by resolving to nothing, which is the same answer
 * an unknown session gets ({@code CheckoutSessionToken.of}'s rule).
 *
 * @param sessionToken the offer this customer is paying for. Never logged, never stored, never
 *     echoed
 * @param paymentMethodId names a {@code paymentmethods} row, never the instrument token
 *     ({@code INV-PAY-02}). Re-resolved authoritatively as the caller's own inside the command,
 *     so naming somebody else's is the same empty answer as naming one that does not exist
 */
public record ConfirmSessionRequest(
        @NotNull Sensitive<String> sessionToken, @NotNull UUID paymentMethodId) {}
