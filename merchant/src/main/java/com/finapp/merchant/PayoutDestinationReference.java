package com.finapp.merchant;

import com.finapp.sharedkernel.security.Sensitive;
import java.util.Objects;

/**
 * The opaque reference a payout destination is paid to (`P6-TSK-011`, ADR-0056 §5): what the
 * provider's exchange returned for a grant, resolving at the provider to a merchant's bank
 * account.
 *
 * <p>The {@code TokenReference} mechanism restated, <strong>not imported</strong> — `merchant`
 * sees no business sibling that owns one, so the wrapped-token discipline is restated here the
 * way {@code paymentmethods} restated it: the mechanism is the platform's, the class is this
 * boundary's own.
 *
 * <p><strong>The component is named {@code secret}</strong> so {@code secretsAreWrapped} holds
 * the wrapping for ever, and {@link #expose()} is the one way the value comes off — registered in
 * {@code SecretsAreUnwrappedInOnePlaceTest}, the store writing its column being the one
 * production caller this task ships. A reference is never logged, never in an event payload and
 * never in an API response ({@code RESTRICTED-PII}, the {@code payment_method.token_reference}
 * reasoning).
 *
 * <p>A value shaped like a bank account number is refused ({@link BankDetailShapes}), and `V006`
 * carries the same rule at {@code DB-CONSTRAINT} rank: a raw account number cannot be stored by
 * any writer.
 */
public record PayoutDestinationReference(Sensitive<String> secret) {

    /** Matches `V006`'s bound, so a value that constructs here always stores. */
    public static final int MAX_LENGTH = BankDetailShapes.MAX_LENGTH;

    public PayoutDestinationReference {
        Objects.requireNonNull(secret, "the destination reference must not be null");
        String value = secret.expose();
        Objects.requireNonNull(value, "the destination reference must not be null");
        BankDetailShapes.requireTokenNotBankDetail(value, "a destination reference");
    }

    public static PayoutDestinationReference of(String value) {
        return new PayoutDestinationReference(Sensitive.of(value));
    }

    /** The bare reference, for the store's column and nowhere else. */
    public String expose() {
        return secret.expose();
    }
}
