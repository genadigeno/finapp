package com.finapp.platform.abuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The window arithmetic a limit and an answer can state, with no database (`P15-TSK-022`). */
@DisplayName("a window limit and its answer (P15-TSK-022)")
class WindowLimitTest {

    @Test
    @DisplayName("a limit needs at least one request and a whole number of seconds, at least one")
    void aLimitIsValidated() {
        assertThat(new WindowLimit(1, Duration.ofSeconds(1)).windowSeconds()).isEqualTo(1);
        assertThat(new WindowLimit(10, Duration.ofHours(1)).windowSeconds()).isEqualTo(3600);

        assertThatIllegalArgumentException().isThrownBy(() -> new WindowLimit(0, Duration.ofMinutes(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> new WindowLimit(1, Duration.ofMillis(999)));
        assertThatIllegalArgumentException().isThrownBy(() -> new WindowLimit(1, Duration.ofMillis(1500)));
        assertThatIllegalArgumentException().isThrownBy(() -> new WindowLimit(1, Duration.ZERO));
    }

    @Test
    @DisplayName("Retry-After is at least one second: zero would mean retry at once")
    void anAnswerPromisesAtLeastASecond() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Admission(false, 0));
        assertThat(new Admission(false, 1).retryAfterSeconds()).isEqualTo(1);
    }
}
