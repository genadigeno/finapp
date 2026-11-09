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
        for (String outcome : List.of("refused", "failed")) {
            assertThat(registry.find("finapp.inbox.poll.failures").tag("outcome", outcome).counter())
                    .as("thrown-poll series for outcome=%s (P15-TSK-026)", outcome)
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

        consumers.settle("risk", () -> new ReceiverPollResult(1, 0, 0, 0));
        assertThat(consumers.brokerAccessRefused())
                .as("another module's admitted poll does not clear kyc's refusal")
                .isTrue();

        consumers.settle("kyc", () -> new ReceiverPollResult(1, 0, 0, 0));
        assertThat(consumers.brokerAccessRefused())
                .as("a poll that delivered a record is admission: the broker served it")
                .isFalse();
    }

    @Test
    @DisplayName("a quiet poll is no admission: the refusal holds until enough clean polls in a row (P15-TSK-026)")
    void aQuietPollDoesNotClearARefusal() {
        // The review fix round: a refused client returns clean, empty polls between its
        // reconnection attempts, and the first version cleared the refusal on the first of them.
        InboxConsumers consumers =
                new InboxConsumers(
                        List.of(), (group, handlers) -> failConnecting(), Duration.ofMillis(1),
                        new SimpleMeterRegistry());
        consumers.settle("kyc", InboxConsumersTest::refused);

        for (int clean = 1; clean < InboxConsumers.CLEAN_POLLS_TO_ADMIT; clean++) {
            consumers.settle("kyc", () -> ReceiverPollResult.NOTHING);
            assertThat(consumers.brokerAccessRefused())
                    .as("%d quiet poll(s) after the refusal", clean)
                    .isTrue();
        }
        consumers.settle("kyc", InboxConsumersTest::failing);
        consumers.settle("kyc", () -> ReceiverPollResult.NOTHING);
        assertThat(consumers.brokerAccessRefused())
                .as("a failure in between starts the count again")
                .isTrue();
        for (int clean = 1; clean < InboxConsumers.CLEAN_POLLS_TO_ADMIT; clean++) {
            consumers.settle("kyc", () -> ReceiverPollResult.NOTHING);
        }
        assertThat(consumers.brokerAccessRefused())
                .as("%d clean polls in a row are admission", InboxConsumers.CLEAN_POLLS_TO_ADMIT)
                .isFalse();

        consumers.settle("kyc", InboxConsumersTest::refused);
        consumers.settle("kyc", () -> ReceiverPollResult.NOTHING);
        consumers.settle("kyc", InboxConsumersTest::refused);
        for (int clean = 1; clean < InboxConsumers.CLEAN_POLLS_TO_ADMIT; clean++) {
            consumers.settle("kyc", () -> ReceiverPollResult.NOTHING);
        }
        assertThat(consumers.brokerAccessRefused())
                .as("a refusal between quiet polls starts the count again")
                .isTrue();
    }

    @Test
    @DisplayName("an ordinary poll failure is not a refusal, so it fails no readiness (P15-TSK-026)")
    void aFailureIsNotARefusal() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InboxConsumers consumers =
                new InboxConsumers(
                        List.of(), (group, handlers) -> failConnecting(), Duration.ofMillis(1),
                        registry);

        assertThat(consumers.settle("kyc", InboxConsumersTest::failing))
                .as("a failed poll is logged and backed off, and yields no result to count")
                .isNull();
        ReceiverPollResult stalled = new ReceiverPollResult(0, 0, 0, 1);
        assertThat(consumers.settle("kyc", () -> stalled))
                .as("a stalled record is the loop's to count, as it always was")
                .isSameAs(stalled);

        assertThat(consumers.brokerAccessRefused()).isFalse();
        // The review fix round: a thrown poll - a broker's expired certificate among them - is
        // counted where an alert can read it, and a refusal apart from it.
        consumers.settle("kyc", InboxConsumersTest::refused);
        assertThat(registry.get("finapp.inbox.poll.failures").tag("outcome", "failed").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("finapp.inbox.poll.failures").tag("outcome", "refused").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a commit a rebalance refused has a series of its own, is no thrown poll, and its meter is eager (P15-TSK-012)")
    void aDeferredCommitIsCountedApart() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InboxConsumers consumers =
                new InboxConsumers(
                        List.of(), (group, handlers) -> failConnecting(), Duration.ofMillis(1),
                        registry);
        assertThat(registry.get(InboxConsumers.DEFERRED_COMMITS).counter().count()).isZero();

        consumers.settle("kyc", () -> new ReceiverPollResult(1, 0, 0, 0, 0, 1));

        assertThat(registry.get(InboxConsumers.DEFERRED_COMMITS).counter().count()).isEqualTo(1.0);
        assertThat(registry.get("finapp.inbox.poll.failures").tag("outcome", "failed").counter().count()).isZero();
    }

    @Test
    @DisplayName("an eviction has a series of its own, is no thrown poll and no refusal, and its meter is eager (P15-TSK-025)")
    void anEvictionIsCountedApart() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InboxConsumers consumers =
                new InboxConsumers(
                        List.of(), (group, handlers) -> failConnecting(), Duration.ofMillis(1),
                        registry);
        assertThat(registry.get(InboxConsumers.EVICTIONS).counter().count()).isZero();

        assertThat(consumers.settle("kyc", InboxConsumersTest::evicted))
                .as("the batch redelivers into the dedupe; there is nothing to count")
                .isNull();

        assertThat(registry.get(InboxConsumers.EVICTIONS).counter().count()).isEqualTo(1.0);
        assertThat(registry.get("finapp.inbox.poll.failures").tag("outcome", "failed").counter().count()).isZero();
        assertThat(consumers.brokerAccessRefused()).isFalse();
    }

    @Test
    @DisplayName("a module's lag gauge is NaN until a poll has read it, its number after, and NaN again when a poll cannot say (P15-TSK-025)")
    void theLagGaugeIsNaNWhenUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InboxConsumers consumers =
                new InboxConsumers(
                        List.of(handler("risk.signals")), (group, handlers) -> failConnecting(), Duration.ofMillis(1),
                        registry);

        assertThat(registry.get(InboxConsumers.LAG).tag("module", "risk").gauge().value()).isNaN();
        consumers.observeLag("risk", java.util.OptionalLong.of(7));
        assertThat(registry.get(InboxConsumers.LAG).tag("module", "risk").gauge().value()).isEqualTo(7.0);
        consumers.observeLag("risk", java.util.OptionalLong.empty());
        assertThat(registry.get(InboxConsumers.LAG).tag("module", "risk").gauge().value()).isNaN();
    }

    @Test
    @DisplayName("a module's consumer group is finapp.<module> (P15-TSK-026)")
    void theGroupIsNamedForTheModule() {
        assertThat(InboxConsumers.groupIdOf("kyc")).isEqualTo("finapp.kyc");
    }

    private static ReceiverPollResult evicted() {
        throw new com.finapp.platform.inbox.kafka.ConsumerEvictedException("CommitFailedException");
    }

    private static ReceiverPollResult refused() {
        throw new BrokerAccessRefusedException("SaslAuthenticationException");
    }

    private static ReceiverPollResult failing() {
        // What the receiver lets through untranslated, a failed TLS handshake among them; its
        // Kafka type is not on this module's test classpath, and the loop reads no type but the
        // refusal's.
        throw new IllegalStateException("a client failure");
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
