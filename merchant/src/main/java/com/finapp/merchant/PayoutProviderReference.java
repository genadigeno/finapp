package com.finapp.merchant;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The payout provider's own identifier for a payout it accepted — the second reconciliation
 * handle beside {@link PayoutReference} (`PHASE_6_PLAN.md` §12), present exactly when the
 * payout is {@code COMPLETED}.
 */
public record PayoutProviderReference(String value) {

    /** Mirrors `V007`'s {@code merchant_payout_provider_reference_shape}. */
    public static final String REGEX = "[A-Za-z0-9_.:-]{1,128}";

    private static final Pattern SHAPE = Pattern.compile(REGEX);

    public PayoutProviderReference {
        Objects.requireNonNull(value, "the provider reference must not be null");
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("a provider payout reference must match " + REGEX);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
