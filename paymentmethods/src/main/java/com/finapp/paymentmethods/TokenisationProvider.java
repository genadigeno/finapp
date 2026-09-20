package com.finapp.paymentmethods;

import java.util.Objects;
import java.util.Optional;

/**
 * The tokenisation exchange: a one-time grant in, a storable instrument out (`P5-TSK-005`,
 * `INV-PAY-02` — this module IS the tokenisation boundary, in the invariant's own words).
 *
 * <h2>The contract is total, and three answers cover it</h2>
 *
 * <ul>
 *   <li>{@link Outcome#TOKENISED} — a parsed answer carrying the permanent token and the
 *       provider-sourced display metadata. <strong>The metadata comes from the provider, never
 *       the client</strong>: a client cannot lie about brand or last4 because we never accept
 *       them from it.
 *   <li>{@link Outcome#REFUSED} — the provider <em>explicitly</em> refused the grant (expired,
 *       already used, unknown). The caller's own value to renew: a 422 at the surface.
 *   <li>{@link Outcome#UNAVAILABLE} — everything else: a timeout, a 5xx, garbage, an unmapped
 *       status, a refused connection, an answer whose instrument we cannot store. The attach
 *       fails clean and <strong>never falls back to holding raw detail</strong>
 *       ({@code INV-PAY-02}'s own sentence). The {@code NOTHING_SENT} distinction the payment
 *       port draws is deliberately not drawn here: nothing was going to be stored either way,
 *       so knowledge-versus-ambiguity changes no caller's behaviour.
 * </ul>
 *
 * <p><strong>Re-exchanging one grant yields the same instrument token</strong> — the simulated
 * provider's contract (and the real-world one this simulates): it is what lets the attach's
 * natural-key convergence carry the lost-response retry, because the retried exchange lands on
 * the same (party, token) slot.
 *
 * <p>Called while <strong>no database connection is held</strong> (the `P1-TSK-026`
 * discipline): the attach checks fail-fast in one transaction, exchanges, then writes in a
 * second.
 */
public interface TokenisationProvider {

    Exchange exchange(TokenisationGrant grant);

    enum Outcome {
        TOKENISED,
        REFUSED,
        UNAVAILABLE
    }

    /** The exchange's answer; the instrument is present exactly when the outcome earned it. */
    record Exchange(Outcome outcome, Optional<TokenisedInstrument> instrument) {
        public Exchange {
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(instrument, "instrument must not be null");
            if ((outcome == Outcome.TOKENISED) != instrument.isPresent()) {
                throw new IllegalArgumentException(
                        "an exchange carries an instrument exactly when it is TOKENISED");
            }
        }

        public static Exchange tokenised(TokenisedInstrument instrument) {
            return new Exchange(Outcome.TOKENISED, Optional.of(instrument));
        }

        public static Exchange refused() {
            return new Exchange(Outcome.REFUSED, Optional.empty());
        }

        public static Exchange unavailable() {
            return new Exchange(Outcome.UNAVAILABLE, Optional.empty());
        }
    }

    /**
     * What an exchange yields: the permanent token and the provider-sourced display metadata.
     *
     * <p>A plain carrier, deliberately: the token's shape is {@link TokenReference}'s own rule
     * (the adapter maps an unusable token to {@code UNAVAILABLE}), and the display fields'
     * shapes have exactly one definition — {@link PaymentMethod}'s constructor — so the service
     * maps an unstorable answer to the unavailable refusal rather than this record restating
     * four validations that would drift.
     */
    record TokenisedInstrument(
            TokenReference token,
            String brand,
            String displaySuffix,
            int expiryMonth,
            int expiryYear) {
        public TokenisedInstrument {
            Objects.requireNonNull(token, "token must not be null");
            Objects.requireNonNull(brand, "brand must not be null");
            Objects.requireNonNull(displaySuffix, "displaySuffix must not be null");
        }
    }
}
