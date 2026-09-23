package com.finapp.merchant;

import com.finapp.sharedkernel.id.IdGenerator;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Our minted idempotency reference for one payout at the payout provider ({@code INV-PAY-04}'s
 * discipline pointed outward, ADR-0051 §2): {@code pyo-<uuid>}, committed in the dispatch
 * transaction <strong>before</strong> anything is sent, presented on every send and every query,
 * so a retry, a takeover and the resolution sweep all name the same operation and the provider
 * can never pay it twice.
 */
public record PayoutReference(String value) {

    /** Mirrors `V007`'s {@code merchant_payout_reference_shape}. */
    public static final String REGEX = "[A-Za-z0-9-]{1,64}";

    private static final Pattern SHAPE = Pattern.compile(REGEX);

    public PayoutReference {
        Objects.requireNonNull(value, "the payout reference must not be null");
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("a payout reference must match " + REGEX);
        }
    }

    /** Mints a fresh reference. */
    public static PayoutReference mint(IdGenerator ids) {
        return new PayoutReference("pyo-" + ids.next());
    }

    @Override
    public String toString() {
        return value;
    }
}
