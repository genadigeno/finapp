package com.finapp.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The previous key's window (`P15-TSK-019`, ADR-0123 point 5): a second accepted key that cannot outlive the rotation that
 * needed it.
 */
@DisplayName("the previous signing key is accepted for a bounded window, and the window closes itself (P15-TSK-019)")
class PreviousKeyWindowTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final byte[] KEY = "test key material, not real one".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    @DisplayName("open until its instant, closed at it and after: nothing has to restart for it to close")
    void itClosesAtItsInstant() {
        PreviousKeyWindow window = PreviousKeyWindow.of(KEY, NOW.plus(Duration.ofHours(1)), NOW).orElseThrow();

        assertThat(window.isOpenAt(NOW)).isTrue();
        assertThat(window.isOpenAt(NOW.plus(Duration.ofMinutes(59)))).isTrue();
        assertThat(window.isOpenAt(NOW.plus(Duration.ofHours(1)))).as("the end is exclusive").isFalse();
        assertThat(window.isOpenAt(NOW.plus(Duration.ofDays(1)))).isFalse();
    }

    @Test
    @DisplayName("a window whose instant has passed opens nothing, and the replica still starts")
    void aPastInstantOpensNothing() {
        assertThat(PreviousKeyWindow.of(KEY, NOW.minusSeconds(1), NOW)).isEmpty();
        assertThat(PreviousKeyWindow.of(KEY, NOW, NOW)).as("exactly now is already closed").isEmpty();
    }

    @Test
    @DisplayName("a window further ahead than the maximum refuses: nobody can make the previous key permanent with a date")
    void aWindowTooFarAheadIsRefused() {
        assertThat(PreviousKeyWindow.of(KEY, NOW.plus(PreviousKeyWindow.MAX_AHEAD), NOW)).isPresent();

        assertThatThrownBy(() -> PreviousKeyWindow.of(KEY, NOW.plus(PreviousKeyWindow.MAX_AHEAD).plusSeconds(1), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("days ahead");
    }

    @Test
    @DisplayName("its text names the instant only, never the key")
    void itsTextHoldsNoKey() {
        PreviousKeyWindow window = PreviousKeyWindow.of(KEY, NOW.plusSeconds(60), NOW).orElseThrow();
        assertThat(window.toString()).contains(NOW.plusSeconds(60).toString()).doesNotContain("[B");
    }
}
