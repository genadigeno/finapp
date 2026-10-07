package com.finapp.credit;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A bureau pull (`P10-TSK-005`): our reference, the subject and the product.
 *
 * <p><strong>Our reference is the provider's idempotency key</strong>: a repeat under the same
 * reference - a retry after a lost response, ten instances asking at once - answers the first pull
 * and is never counted again. The subject is an opaque reference the adapter resolves to the
 * party's identifying facts, so those facts cross only the adapter; the product fixes the currency
 * every money attribute must be in.
 *
 * @param reference our reference, a platform-minted token
 * @param subjectReference the subject, opaque to everything but the adapter's resolver
 * @param product the product the decision is for
 */
public record BureauRequest(String reference, String subjectReference, CreditProduct product) {

    /** A platform-minted reference. */
    public static final Pattern REFERENCE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$");

    public BureauRequest {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(subjectReference, "subjectReference");
        Objects.requireNonNull(product, "product");
        if (!REFERENCE.matcher(reference).matches()) {
            throw new IllegalArgumentException("not a platform reference");
        }
        if (subjectReference.isBlank()) {
            throw new IllegalArgumentException("a pull names its subject");
        }
    }
}
