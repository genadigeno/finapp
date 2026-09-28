package com.finapp.payments;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Our end-to-end reference for one push operation (`P7-TSK-006`, ADR-0062 §1): minted by the
 * platform, stored before anything is sent, and presented verbatim on every send and re-send
 * of the same operation — the push wire's face of {@code INV-PAY-04}. The scheme deduplicates
 * on it, which is what makes a re-send by any instance idempotent at the rail.
 *
 * <p><strong>The 35-character bound is ISO 20022's own</strong> ({@code EndToEndId}): the one
 * limit every credit-transfer scheme inherits, so a reference legal here is legal on any
 * adapter's wire. The charset is the idempotency charset — path-segment-safe with no escaping
 * machinery, {@link ProviderIdempotencyReference}'s recorded property.
 */
public record EndToEndReference(String value) {

    public static final int MAX_LENGTH = 35;

    private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9-]{1," + MAX_LENGTH + "}");

    public EndToEndReference {
        Objects.requireNonNull(value, "value must not be null");
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "an end-to-end reference is 1.." + MAX_LENGTH
                            + " characters of [A-Za-z0-9-] (ISO 20022's EndToEndId bound);"
                            + " the presented value is not");
        }
    }
}
