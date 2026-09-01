package com.finapp.platform.inbox;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * What a message is deduplicated on: the consuming component plus the message's own key.
 *
 * <p><strong>Why the consumer is part of the key.</strong> One event legitimately has many
 * consumers, and each of them must process it exactly once. Deduplicating on the message alone
 * would let whichever consumer handled it first suppress every other consumer — a defect that
 * surfaces as "the notification never arrived" long afterwards, with the event visibly
 * published and no error recorded anywhere.
 *
 * <p><strong>Why this is not an {@code IdempotencyKey}.</strong> They have the same shape and
 * different meanings. An idempotency key is chosen by a <em>client</em> for a command it is
 * issuing, and a malformed one can be rejected back to that client. A dedupe key is chosen by a
 * <em>producer</em> for a message it has already sent; there is nobody to reject it to, and the
 * message will simply be redelivered. Sharing a type would invite sharing a table, and
 * {@code V007} sets out why that would be wrong.
 *
 * <p>Bounds mirror the {@code CHECK} constraints on {@code platform.inbox_message}: asserted
 * here so a caller gets a domain error rather than a constraint violation from three layers
 * down, and asserted there so the guarantee does not depend on this class being the only writer.
 */
public record InboxKey(String consumer, String dedupeKey) implements Serializable {

    @Serial private static final long serialVersionUID = 1L;

    /** Matches {@code inbox_message_consumer_bounded} and {@code _dedupe_key_bounded}. */
    public static final int MAX_LENGTH = 200;

    public InboxKey {
        consumer = required(consumer, "consumer");
        dedupeKey = required(dedupeKey, "dedupe key");
    }

    private static String required(String value, String what) {
        Objects.requireNonNull(value, what + " must not be null");
        if (value.isBlank()) {
            // A blank dedupe key would make every message look like the same message, so the
            // first one processed would suppress all the rest. Generating one instead would be
            // worse: a fresh key per delivery deduplicates nothing at all, silently.
            throw new IllegalArgumentException(what + " must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    what + " must be at most " + MAX_LENGTH + " characters but was " + value.length());
        }
        return value;
    }

    @Override
    public String toString() {
        return consumer + "/" + dedupeKey;
    }
}
