package com.finapp.merchant;

import com.finapp.sharedkernel.security.Sensitive;
import java.util.Objects;

/**
 * The one-time grant a destination proposal exchanges for a stored reference (`P6-TSK-011`,
 * ADR-0056 §5) — the {@code TokenisationGrant} precedent, restated for bank data.
 *
 * <p>The operator's client tokenises the merchant's bank details <em>outside</em> the platform
 * and hands us only this grant; the exchange turns it into the permanent
 * {@link PayoutDestinationReference} plus provider-sourced display metadata. It travels wrapped
 * end to end — the {@code Sensitive} deserialiser at the boundary, this type in the domain, one
 * unwrap at the wire — because a grant in a log is a way to a merchant's bank account for its
 * validity window.
 *
 * <p><strong>The bank-detail refusal is the surface's own control</strong>: the proposal is the
 * one place a careless client could send an account number where a grant belongs, and this
 * constructor is where that is refused ({@link BankDetailShapes}).
 */
public record PayoutDestinationGrant(Sensitive<String> secret) {

    public PayoutDestinationGrant {
        Objects.requireNonNull(secret, "the destination grant must not be null");
        String value = secret.expose();
        Objects.requireNonNull(value, "the destination grant must not be null");
        BankDetailShapes.requireTokenNotBankDetail(value, "a destination grant");
    }

    /** The bare grant, for the exchange wire and nowhere else. */
    public String expose() {
        return secret.expose();
    }
}
