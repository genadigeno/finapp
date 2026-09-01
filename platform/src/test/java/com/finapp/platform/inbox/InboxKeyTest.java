package com.finapp.platform.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What may be deduplicated on. */
class InboxKeyTest {

    @Test
    @DisplayName("a blank dedupe key is refused rather than accepted or generated")
    void blankIsRefused() {
        // Both failure modes are silent. Accepting a blank key makes every message look like the
        // same message, so the first one handled suppresses all the rest; generating one instead
        // gives every delivery a fresh key, so nothing is ever deduplicated at all.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new InboxKey("consumer", ""));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new InboxKey("consumer", "   "));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new InboxKey("", "message"));
    }

    @Test
    @DisplayName("null is refused")
    void nullIsRefused() {
        assertThatNullPointerException().isThrownBy(() -> new InboxKey(null, "message"));
        assertThatNullPointerException().isThrownBy(() -> new InboxKey("consumer", null));
    }

    @Test
    @DisplayName("a value at exactly the maximum length is accepted, and one beyond it is not")
    void theBoundIsInclusive() {
        String atLimit = "k".repeat(InboxKey.MAX_LENGTH);

        assertThat(new InboxKey(atLimit, atLimit).dedupeKey()).hasSize(InboxKey.MAX_LENGTH);

        // Asserted from both sides: an off-by-one to `>=` would silently reject a legal key,
        // and the message it belongs to would fail rather than deduplicate.
        String beyond = "k".repeat(InboxKey.MAX_LENGTH + 1);
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new InboxKey("consumer", beyond));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new InboxKey(beyond, "message"));
    }

    @Test
    @DisplayName("two keys differing only by consumer are different keys")
    void theConsumerIsPartOfIdentity() {
        // If they compared equal, one consumer's record would satisfy another's lookup - the
        // same defect the schema's composite key prevents, arriving through the value type.
        assertThat(new InboxKey("ledger", "m1")).isNotEqualTo(new InboxKey("notifier", "m1"));
        assertThat(new InboxKey("ledger", "m1")).isEqualTo(new InboxKey("ledger", "m1"));
    }
}
