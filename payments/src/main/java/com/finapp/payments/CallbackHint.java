package com.finapp.payments;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A callback the platform must still ask about, for the kinds no payment row can stand for (`P15-TSK-036`, ADR-0083,
 * {@code payments.callback_hint}). A failed inquiry about a capture leaves the row {@code CAPTURE_*}, which the sweep
 * finds; a failed inquiry about a clearing, a dispute or an execution on a concluded or unknown row leaves nothing to
 * find, and the inbox has already recorded the delivery, so without this row the notice would be lost.
 *
 * <p>Carries what to ask and of whom, never what the callback claimed: the claim rests in the evidence and decides
 * nothing. Operational state, never financial truth.
 *
 * @param askedAbout our operation reference (clearing), the PSP's dispute reference (dispute), our end-to-end reference
 *     (concluded execution) or the scheme's reference (unattributed execution)
 */
public record CallbackHint(UUID id, Kind kind, Optional<PaymentAttemptId> attempt, String askedAbout, Instant nextInquiryAt) {

    /** The kinds with no row to sweep. */
    public enum Kind {
        CARD_CLEARING,
        CARD_DISPUTE,
        INSTANT_CONCLUDED_EXECUTION,
        INSTANT_UNATTRIBUTED_EXECUTION
    }

    public CallbackHint {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(attempt, "attempt must not be null");
        Objects.requireNonNull(askedAbout, "askedAbout must not be null");
        Objects.requireNonNull(nextInquiryAt, "nextInquiryAt must not be null");
    }

    /** The attempt a card or concluded-execution hint names; only an unattributed execution names none. */
    PaymentAttemptId requireAttempt() {
        return attempt.orElseThrow(() -> new IllegalStateException("a " + kind + " hint names its attempt: V036 holds it"));
    }
}
