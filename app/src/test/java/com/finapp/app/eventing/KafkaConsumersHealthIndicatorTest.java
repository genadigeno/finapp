package com.finapp.app.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.inbox.kafka.BrokerAccessRefusedException;
import com.finapp.platform.inbox.kafka.ReceiverPollResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

/**
 * The consumers' readiness contributor (`P15-TSK-026`): DOWN exactly while the broker refuses a
 * loop, from the consumers' own record of it. Whether readiness - and not liveness - reads it is
 * {@code KafkaConsumersReadinessTest}'s question.
 */
@DisplayName("the consumers' readiness contributor (P15-TSK-026)")
class KafkaConsumersHealthIndicatorTest {

    @Test
    @DisplayName("a refused loop is DOWN, and an admitted poll brings it back UP")
    void aRefusalIsDown() {
        InboxConsumers consumers =
                new InboxConsumers(
                        List.of(),
                        (group, handlers) -> {
                            throw new AssertionError("nothing connects here");
                        },
                        Duration.ofMillis(1),
                        new SimpleMeterRegistry());
        KafkaConsumersHealthIndicator indicator =
                new KafkaConsumersHealthIndicator(consumers::brokerAccessRefused);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

        consumers.settle(
                "risk",
                () -> {
                    throw new BrokerAccessRefusedException("SaslAuthenticationException");
                });
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails())
                .as("a status and nothing else: the log line names the group")
                .isEmpty();

        consumers.settle("risk", () -> ReceiverPollResult.NOTHING);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("an instance whose consumers are switched off has nothing to refuse, and is UP")
    void noConsumersIsUp() {
        assertThat(new KafkaConsumersHealthIndicator(() -> false).health().getStatus())
                .isEqualTo(Status.UP);
    }
}
