package com.finapp.merchant;

import java.util.Objects;
import java.util.Optional;

/**
 * The destination exchange: a one-time grant in, a storable reference out (`P6-TSK-011`,
 * ADR-0056 §5) — the {@code TokenisationProvider} contract restated for bank data. This port is
 * the boundary that keeps a merchant's bank details out of the platform.
 *
 * <h2>The contract is total, and three answers cover it</h2>
 *
 * <ul>
 *   <li>{@link Outcome#TOKENISED} — a parsed answer carrying the permanent reference and the
 *       provider-sourced display suffix. <strong>The suffix comes from the provider, never the
 *       client</strong>: nobody can make an approver read the wrong last four digits.
 *   <li>{@link Outcome#REFUSED} — the provider <em>explicitly</em> refused the grant (expired,
 *       already used, unknown). The caller's own value to renew: a {@code 422} at the surface.
 *   <li>{@link Outcome#UNAVAILABLE} — everything else: a timeout, a {@code 5xx}, garbage, an
 *       unmapped status, a refused connection, an answer whose reference or suffix we cannot
 *       store. The proposal fails clean and <strong>never falls back to holding raw detail</strong>.
 * </ul>
 *
 * <p><strong>Re-exchanging one grant yields the same reference</strong> — the simulated
 * provider's contract, and one a real adapter must honour: it is what lets a proposal's
 * lost-response retry, which exchanges again before its idempotency claim replays, converge on
 * the recorded proposal.
 *
 * <p>Called while <strong>no database connection is held</strong> (the {@code P1-TSK-026}
 * discipline): the proposal checks fail-fast in one transaction, exchanges, then writes in a
 * second.
 */
public interface PayoutDestinationTokenisation {

    Exchange exchange(PayoutDestinationGrant grant);

    enum Outcome {
        TOKENISED,
        REFUSED,
        UNAVAILABLE
    }

    /** The exchange's answer; the destination is present exactly when the outcome earned it. */
    record Exchange(Outcome outcome, Optional<TokenisedDestination> destination) {

        public Exchange {
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            if ((outcome == Outcome.TOKENISED) != destination.isPresent()) {
                throw new IllegalArgumentException(
                        "an exchange carries a destination exactly when it is TOKENISED");
            }
        }

        public static Exchange tokenised(TokenisedDestination destination) {
            return new Exchange(Outcome.TOKENISED, Optional.of(destination));
        }

        public static Exchange refused() {
            return new Exchange(Outcome.REFUSED, Optional.empty());
        }

        public static Exchange unavailable() {
            return new Exchange(Outcome.UNAVAILABLE, Optional.empty());
        }
    }

    /**
     * What an exchange yields: the permanent reference and the provider-sourced suffix. The
     * suffix's shape is judged here, so a provider answer we could not store never counts as
     * tokenised.
     */
    record TokenisedDestination(PayoutDestinationReference reference, String displaySuffix) {

        public TokenisedDestination {
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(displaySuffix, "displaySuffix must not be null");
            if (!displaySuffix.matches(PayoutDestination.DISPLAY_SUFFIX_REGEX)) {
                throw new IllegalArgumentException(
                        "a display suffix must be four characters of [0-9A-Z]");
            }
        }
    }
}
