package com.finapp.payments;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The platform-minted idempotency reference every provider-bound money operation presents
 * (`INV-PAY-04`, `P5-TSK-003`).
 *
 * <p>{@code INV-IDEM-01} protects the platform's own boundary; this is the same rule pointed
 * outward. Without it, the recovery path for an unknown outcome — retry, or resolution by query
 * (ADR-0046) — is itself a double-charge mechanism: a re-dispatched authorize the provider
 * cannot recognise as the same operation is a second authorization. The reference is therefore
 * in the {@link PaymentProvider} port's <em>signatures</em> rather than in a convention, so a
 * dispatch without one does not compile — and it is what {@link PaymentProvider#query} keys on,
 * because the provider's own reference may never have arrived.
 *
 * <p>Minted and persisted by the dispatching command <strong>before</strong> the provider is
 * asked (`P5-TSK-009`; the schema's {@code NOT NULL}-before-dispatch half is `P5-TSK-008`'s).
 * This type is the carrier.
 *
 * <p><strong>The charset is a design property, not pedantry.</strong> The value travels as an
 * HTTP header, appears in a URL path segment on the query, is stored durably and reaches log
 * lines — {@code [A-Za-z0-9-]} makes header injection, path escaping and forged log lines
 * structurally unreachable rather than defended (the `P0-TSK-017` idempotency-key lesson: a
 * caller-reaching identifier with no charset is a forged log line waiting). The refusal names
 * the rule and never echoes the value.
 */
public record ProviderIdempotencyReference(String value) {

    /** Bounded because the value becomes a header, a path segment and a unique column. */
    public static final int MAX_LENGTH = 64;

    private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9-]{1," + MAX_LENGTH + "}");

    public ProviderIdempotencyReference {
        Objects.requireNonNull(value, "the provider idempotency reference must not be null");
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "a provider idempotency reference must be 1-"
                            + MAX_LENGTH
                            + " characters of [A-Za-z0-9-]");
        }
    }
}
