package com.finapp.app.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.inbox.InboxEventHandler;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.platform.inbox.kafka.BrokerAccessRefusedException;
import com.finapp.platform.inbox.kafka.ReceiverPollResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The lifecycle wrapper's own decisions (`P2-TSK-002`): eager meters, per-module grouping, and
 * that an instance with nothing to consume starts nothing. The loop's substance — offsets after
 * effects, seek-back, dedupe — is {@code KafkaEventReceiverTest}'s and the kafka tier's subject.
 */
@DisplayName("the inbox consumer lifecycle (P2-TSK-002)")
class InboxConsumersTest {

    @Test
    @DisplayName("every outcome series exists at zero before any record has ever arrived")
    void theCountersAreEager() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new InboxConsumers(List.of(), (group, handlers) -> failConnecting(), Duration.ofSeconds(1), registry);

        // P1-TSK-029's rule: an alert on a rising duplicate or contention rate must have a
        // series to evaluate on a freshly started instance. This pays the inbox-metrics debt
        // row, whose recorded trigger was "the first live consumer".
        for (String outcome : List.of("processed", "duplicate", "contended", "failed")) {
            assertThat(
                            registry.find("finapp.inbox.consumption")
                                    .tag("outcome", outcome)
                                    .counter())
                    .as("series for outcome=%s", outcome)
                    .isNotNull()
                    .satisfies(counter -> assertThat(counter.count()).isZero());
        }
    }

    @Test
    @DisplayName("with no handlers registered, nothing connects and nothing starts")
    void noHandlersMeansNoLoops() {
        InboxConsumers consumers =
                new InboxConsumers(
                        List.of(),
                        (group, handlers) -> failConnecting(),
                        Duration.ofSeconds(1),
                        new SimpleMeterRegistry());

        consumers.start();
        consumers.stop();
        // The factory throwing is the assertion: a consumer with no handlers would commit
        // offsets past facts nobody reacted to, so it must never be built.
    }

    @Test
    @DisplayName("handlers group into one consumer group per consuming module")
    void handlersGroupByModule() {
        Map<String, List<InboxEventHandler>> groups =
                InboxConsumers.byModule(
                        List.of(
                                handler("kyc.caseOpening"),
                                handler("kyc.screening"),
                                handler("party.projection")));

        assertThat(groups.keySet())
                .as("the module segment of consumerName is the group key")
                .containsExactlyInAnyOrder("kyc", "party");
        assertThat(groups.get("kyc")).hasSize(2);
        assertThat(groups.get("party")).hasSize(1);
    }

    @Test
    @DisplayName("a refused poll marks the instance refused until a poll is admitted (P15-TSK-026)")
    void aRefusalIsHeldUntilAPollIsAdmitted() {
        InboxConsumers consumers =
                new InboxConsumers(
                        List.of(), (group, handlers) -> failConnecting(), Duration.ofMillis(1),
                        new SimpleMeterRegistry());
        assertThat(consumers.brokerAccessRefused()).as("nothing has polled").isFalse();

        consumers.settle("kyc", InboxConsumersTest::refused);
        assertThat(consumers.brokerAccessRefused()).as("kyc's loop was refused").isTrue();

        consumers.settle("risk", () -> ReceiverPollResult.NOTHING);
        assertThat(consumers.brokerAccessRefused())
                .as("another module's admitted poll does not clear kyc's refusal")
                .isTrue();

        consumers.settle("kyc", () -> new ReceiverPollResult(1, 0, 0, 0));
        assertThat(consumers.brokerAccessRefused())
                .as("kyc's next admitted poll clears it")
                .isFalse();
    }

    @Test
    @DisplayName("an ordinary poll failure is not a refusal, so it fails no readiness (P15-TSK-026)")
    void aFailureIsNotARefusal() {
        InboxConsumers consumers =
                new InboxConsumers(
                        List.of(), (group, handlers) -> failConnecting(), Duration.ofMillis(1),
                        new SimpleMeterRegistry());

        assertThat(
                        consumers.settle(
                                "kyc",
                                () -> {
                                    throw new IllegalStateException("a client failure");
                                }))
                .as("a failed poll is logged and backed off, and yields no result to count")
                .isNull();
        ReceiverPollResult stalled = new ReceiverPollResult(0, 0, 0, 1);
        assertThat(consumers.settle("kyc", () -> stalled))
                .as("a stalled record is the loop's to count, as it always was")
                .isSameAs(stalled);

        assertThat(consumers.brokerAccessRefused()).isFalse();
    }

    @Test
    @DisplayName("a module's consumer group is finapp.<module> (P15-TSK-026)")
    void theGroupIsNamedForTheModule() {
        assertThat(InboxConsumers.groupIdOf("kyc")).isEqualTo("finapp.kyc");
    }

    private static ReceiverPollResult refused() {
        throw new BrokerAccessRefusedException("SaslAuthenticationException");
    }

    private static com.finapp.platform.inbox.kafka.KafkaEventReceiver failConnecting() {
        throw new AssertionError("nothing should connect in this test");
    }

    private static InboxEventHandler handler(String consumerName) {
        return new InboxEventHandler() {
            @Override
            public String consumerName() {
                return consumerName;
            }

            @Override
            public String topic() {
                return "finapp.probe";
            }

            @Override
            public String eventType() {
                return "probe.SomethingHappened";
            }

            @Override
            public void handle(Connection unitOfWork, ReceivedEvent event) {
                // Never runs here.
            }
        };
    }
}
