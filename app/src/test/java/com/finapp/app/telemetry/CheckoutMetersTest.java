package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The conversion-age timer's own rules (`P6-TSK-013`), hermetically: eager per completion
 * outcome, a completion counted and timed together or not at all, and an age that can neither
 * go negative nor fail the capture that completed the session. That it is recorded on the
 * acting transition only is the completion site's discipline, proven in the checkout suite.
 */
@DisplayName("the checkout conversion-age timer (P6-TSK-013)")
class CheckoutMetersTest {

    private static final String CONVERSION_AGE = "finapp.checkout.conversion.age";
    private static final String SESSION = "finapp.checkout.session";

    @Test
    @DisplayName("one series per completion outcome exists at zero, and none for an ending that"
            + " is not a conversion")
    void theTimerIsEagerPerCompletion() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new CheckoutMeters(registry);

        assertThat(registry.find(CONVERSION_AGE).timers())
                .extracting(timer -> timer.getId().getTag("outcome"))
                .containsExactlyInAnyOrder("completed", "completed_late");
        assertThat(timer(registry, "completed").count()).isZero();
    }

    @Test
    @DisplayName("a conversion is counted and timed together - and the two populations apart")
    void aConversionIsCountedAndTimed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CheckoutMeters meters = new CheckoutMeters(registry);

        meters.converted(CheckoutMeters.Outcome.COMPLETED, Duration.ofSeconds(90));
        meters.converted(CheckoutMeters.Outcome.COMPLETED_LATE, Duration.ofMinutes(45));

        assertThat(registry.get(SESSION).tag("outcome", "completed").counter().count())
                .isEqualTo(1.0d);
        assertThat(timer(registry, "completed").count()).isEqualTo(1L);
        assertThat(timer(registry, "completed").totalTime(TimeUnit.SECONDS)).isEqualTo(90.0d);
        assertThat(timer(registry, "completed").max(TimeUnit.SECONDS))
                .as("the late conversion never reaches the in-window series")
                .isEqualTo(90.0d);
        assertThat(timer(registry, "completed_late").max(TimeUnit.MINUTES)).isEqualTo(45.0d);
    }

    @Test
    @DisplayName("a completion cannot be counted without its age, nor a non-completion timed")
    void aCompletionCarriesItsAge() {
        CheckoutMeters meters = new CheckoutMeters(new SimpleMeterRegistry());

        assertThatThrownBy(() -> meters.session(CheckoutMeters.Outcome.COMPLETED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> meters.session(CheckoutMeters.Outcome.COMPLETED_LATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> meters.converted(CheckoutMeters.Outcome.EXPIRED, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("two instances' clocks a moment apart record zero, never a negative age and"
            + " never a failure")
    void aNegativeAgeIsClampedAtZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CheckoutMeters meters = new CheckoutMeters(registry);

        meters.converted(CheckoutMeters.Outcome.COMPLETED, Duration.ofMillis(-400));

        assertThat(timer(registry, "completed").count()).isEqualTo(1L);
        assertThat(timer(registry, "completed").totalTime(TimeUnit.NANOSECONDS)).isZero();
    }

    private static Timer timer(SimpleMeterRegistry registry, String outcome) {
        return registry.get(CONVERSION_AGE).tag("outcome", outcome).timer();
    }
}
