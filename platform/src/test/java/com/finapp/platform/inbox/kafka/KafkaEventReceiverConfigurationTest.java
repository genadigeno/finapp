package com.finapp.platform.inbox.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxEventHandler;
import com.finapp.platform.inbox.JdbcInboxRecordStore;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.platform.outbox.BrokerConnection;
import com.finapp.sharedkernel.security.Sensitive;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupAuthorizationException;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.SslAuthenticationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The consumer's client configuration and its refusal translation (`P15-TSK-026`), hermetically.
 *
 * <p>The configuration is read through {@link KafkaEventReceiver#consumerProperties}, the very
 * method {@link KafkaEventReceiver#connect} builds the client from, and validated by Kafka's own
 * {@code ConsumerConfig}, which refuses a setting the chosen group protocol does not allow. What a
 * rebalance does with these settings on a real broker - the staying member keeps its partitions,
 * nothing is applied twice - is {@code KafkaRebalanceKafkaTest}'s, owed to the exit's database
 * pass.
 */
@DisplayName("the Kafka event receiver's client configuration (P15-TSK-026)")
class KafkaEventReceiverConfigurationTest {

    private static final String GROUP = "finapp.probe";

    @Test
    @DisplayName("the cooperative assignor alone, under the classic protocol pinned")
    void theAssignorIsCooperative() {
        ConsumerConfig config = parsed(BrokerConnection.plaintext("localhost:29092"));

        assertThat(config.getString(ConsumerConfig.GROUP_PROTOCOL_CONFIG))
                .as("pinned, so a changed client default cannot move the group to a protocol"
                        + " where these settings are the broker's")
                .isEqualToIgnoringCase("classic");
        assertThat(config.getList(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG))
                .as("a rolling step moves only the partitions that change hands; the eager"
                        + " default stops every member of every group")
                .containsExactly(CooperativeStickyAssignor.class.getName());
        assertThat(config.originals())
                .as("no static membership: a Deployment's pod names change on every rollout")
                .doesNotContainKey(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG);
    }

    @Test
    @DisplayName("the session and poll timeouts are bounded, and the batch fits the poll bound")
    void theTimeoutsAreBounded() {
        ConsumerConfig config = parsed(BrokerConnection.plaintext("localhost:29092"));

        assertThat(config.getInt(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG))
                .isEqualTo((int) KafkaEventReceiver.SESSION_TIMEOUT.toMillis())
                .isEqualTo(30_000);
        assertThat(config.getInt(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG))
                .as("at most a third of the session timeout")
                .isLessThanOrEqualTo(config.getInt(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG) / 3);
        assertThat(config.getInt(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG))
                .isEqualTo((int) KafkaEventReceiver.MAX_POLL_INTERVAL.toMillis())
                .isEqualTo(120_000);
        assertThat(config.getInt(ConsumerConfig.MAX_POLL_RECORDS_CONFIG))
                .isEqualTo(KafkaEventReceiver.MAX_POLL_RECORDS)
                .isEqualTo(10);
        assertThat(config.getInt(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG))
                .as("the commit's wait, which the poll budget spends")
                .isEqualTo((int) KafkaEventReceiver.API_TIMEOUT.toMillis());

        // The budget's arithmetic lives with the configuration it reads (the review fix round):
        // InboxPollBudgetTest, in app, takes the pool's wait from the committed application.yaml
        // and the claim wait from JdbcInboxRecordStore, where the first version copied 5 s and
        // 0.5 s into this test.
    }

    @Test
    @DisplayName("offsets move only by the receiver's commit, and a new group starts at the beginning")
    void theCommitDisciplineIsKept() {
        ConsumerConfig config = parsed(BrokerConnection.plaintext("localhost:29092"));

        assertThat(config.getBoolean(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isFalse();
        assertThat(config.getString(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG))
                .isEqualToIgnoringCase("earliest");
        assertThat(config.getString(ConsumerConfig.GROUP_ID_CONFIG)).isEqualTo(GROUP);
    }

    @Test
    @DisplayName("SASL_SSL reaches the consumer from the shared connection")
    void theConnectionIsTheShared() {
        Properties rendered =
                KafkaEventReceiver.consumerProperties(
                        new BrokerConnection("broker:9096", "SASL_SSL", "u", Sensitive.of("p"), ""),
                        GROUP);

        assertThat(rendered)
                .containsEntry("security.protocol", "SASL_SSL")
                .containsEntry("sasl.mechanism", "SCRAM-SHA-512")
                .containsEntry("ssl.endpoint.identification.algorithm", "https")
                .containsKey("sasl.jaas.config");
        assertThatCode(() -> new ConsumerConfig(rendered)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a refused credential or permission is translated, naming the class and nothing else")
    void aRefusalIsTranslated() {
        MockConsumer<String, byte[]> consumer = assignedConsumer();
        KafkaEventReceiver receiver = receiver(consumer);

        consumer.setPollException(
                new SaslAuthenticationException("Authentication failed for user finapp on broker-a"));
        assertThatThrownBy(receiver::pollOnce)
                .isInstanceOf(BrokerAccessRefusedException.class)
                .hasMessageContaining("SaslAuthenticationException")
                .hasMessageNotContaining("broker-a")
                .hasMessageNotContaining("finapp on");

        consumer.setPollException(new TopicAuthorizationException("not authorised"));
        assertThatThrownBy(receiver::pollOnce)
                .isInstanceOf(BrokerAccessRefusedException.class)
                .hasMessageContaining("TopicAuthorizationException");

        consumer.setPollException(new GroupAuthorizationException("not authorised"));
        assertThatThrownBy(receiver::pollOnce)
                .isInstanceOf(BrokerAccessRefusedException.class)
                .hasMessageContaining("GroupAuthorizationException");

        // The review fix round: an AuthenticationException by inheritance, but no refusal - the
        // broker's certificate fails a handshake as surely as this client's truststore, and a
        // broker-side fault translated into a refusal would fail readiness on every replica.
        consumer.setPollException(new SslAuthenticationException("SSL handshake failed"));
        assertThatThrownBy(receiver::pollOnce)
                .as("a failed TLS handshake leaves as the ordinary failure it is")
                .isInstanceOf(SslAuthenticationException.class)
                .isNotInstanceOf(BrokerAccessRefusedException.class);

        assertThat(receiver.pollOnce())
                .as("admitted again, the next poll is an ordinary empty one")
                .isEqualTo(ReceiverPollResult.NOTHING);
    }

    // -----------------------------------------------------------------

    private static ConsumerConfig parsed(BrokerConnection connection) {
        return new ConsumerConfig(KafkaEventReceiver.consumerProperties(connection, GROUP));
    }

    private static MockConsumer<String, byte[]> assignedConsumer() {
        MockConsumer<String, byte[]> consumer =
                new MockConsumer<>("earliest");
        TopicPartition partition = new TopicPartition("finapp.probe", 0);
        consumer.assign(List.of(partition));
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        return consumer;
    }

    private static KafkaEventReceiver receiver(MockConsumer<String, byte[]> consumer) {
        return new KafkaEventReceiver(
                consumer,
                "probe.group",
                () -> {
                    throw new SQLException("no record arrives, so no unit of work is opened");
                },
                new InboxConsumer<>(new JdbcInboxRecordStore(), Clock.systemUTC(), Duration.ofDays(14)),
                com.finapp.platform.testing.InboxParkingDoubles.NEVER_PARKS,
                List.of(new NoHandler()),
                Duration.ofMillis(10));
    }

    private static final class NoHandler implements InboxEventHandler {
        @Override
        public String consumerName() {
            return "probe.never";
        }

        @Override
        public String topic() {
            return "finapp.probe";
        }

        @Override
        public String eventType() {
            return "probe.Never";
        }

        @Override
        public void handle(Connection unitOfWork, ReceivedEvent event) {
            throw new AssertionError("no record arrives in these tests");
        }
    }
}
