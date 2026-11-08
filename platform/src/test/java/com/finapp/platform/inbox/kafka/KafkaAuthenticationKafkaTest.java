package com.finapp.platform.inbox.kafka;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * SCRAM-SHA-512 against a real broker's SASL listener (`P15-TSK-026`; the kafka tier, owed to
 * `P15-TST-002` under C14).
 *
 * <p>A broker of its own, because the shared one ({@code KafkaUnderTest}) serves loopback
 * {@code PLAINTEXT} to every other kafka-tier suite. Its host-facing listener is switched to
 * {@code SASL_PLAINTEXT} with SCRAM-SHA-512, and the SCRAM user is created through the
 * container-internal listener with the image's own tool - a throwaway credential generated for the
 * run, never written anywhere. TLS is not exercised here: a real TLS handshake needs a CA, which
 * {@code P15-TSK-012}'s {@code kind} rehearsal provides; what this proves is the JAAS line
 * {@link BrokerConnection} renders, and the receiver's translation of a refusal.
 *
 * <p><strong>Unrun when written</strong> (the standing C14 deviation): the listener override relies
 * on the image's environment-to-property mapping ({@code ___} for a hyphen) and on the container's
 * readiness wait not dialling the overridden listener without SASL. The exit's database pass runs
 * it first.
 */
@Tag("kafka")
@DisplayName("a SCRAM credential is admitted, and a wrong one refused, by a real broker (P15-TSK-026)")
class KafkaAuthenticationKafkaTest {

    private static final String USER = "finapp-probe";
    private static final String PASSWORD = "scram-" + UUID.randomUUID();

    private static KafkaContainer broker;

    @BeforeAll
    static void startASaslBroker() throws Exception {
        String image = System.getProperty("finapp.kafka.image");
        assertThat(image)
                .as("the kafkaTest task supplies the image from the version catalog")
                .isNotNull();
        broker =
                new KafkaContainer(DockerImageName.parse(image))
                        // The host-facing listener (named PLAINTEXT by the container) becomes a
                        // SASL listener; the container-internal BROKER listener stays PLAINTEXT
                        // for the broker's own traffic and for creating the user below.
                        .withEnv(
                                "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                                "BROKER:PLAINTEXT,PLAINTEXT:SASL_PLAINTEXT,CONTROLLER:PLAINTEXT")
                        .withEnv("KAFKA_SASL_ENABLED_MECHANISMS", BrokerConnection.SASL_MECHANISM)
                        .withEnv(
                                "KAFKA_LISTENER_NAME_PLAINTEXT_SASL_ENABLED_MECHANISMS",
                                BrokerConnection.SASL_MECHANISM)
                        .withEnv(
                                "KAFKA_LISTENER_NAME_PLAINTEXT_SCRAM___SHA___512_SASL_JAAS_CONFIG",
                                "org.apache.kafka.common.security.scram.ScramLoginModule required;");
        broker.start();
        ExecResult created =
                broker.execInContainer(
                        "/opt/kafka/bin/kafka-configs.sh",
                        "--bootstrap-server",
                        "localhost:9093",
                        "--alter",
                        "--add-config",
                        "SCRAM-SHA-512=[password=" + PASSWORD + "]",
                        "--entity-type",
                        "users",
                        "--entity-name",
                        USER);
        assertThat(created.getExitCode()).as(created.getStderr()).isZero();
    }

    @AfterAll
    static void stop() {
        if (broker != null) {
            broker.stop();
        }
    }

    @Test
    @DisplayName("the right credential is admitted: the receiver polls without refusal")
    void theRightCredentialIsAdmitted() throws Exception {
        BrokerConnection connection =
                new BrokerConnection(
                        broker.getBootstrapServers(), "SASL_PLAINTEXT", USER, Sensitive.of(PASSWORD), "");
        String topic = "finapp.authprobe" + UUID.randomUUID().toString().substring(0, 8);
        try (Admin admin = Admin.create(connection.clientProperties())) {
            // An admin call that only an authenticated client completes.
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
        }

        try (KafkaEventReceiver receiver = receiver(connection, topic)) {
            Instant deadline = Instant.now().plusSeconds(20);
            while (Instant.now().isBefore(deadline)) {
                assertThat(receiver.pollOnce()).isEqualTo(ReceiverPollResult.NOTHING);
            }
        }
    }

    @Test
    @DisplayName("a wrong password is refused, and the receiver says so without the client's message")
    void aWrongPasswordIsRefused() {
        BrokerConnection wrong =
                new BrokerConnection(
                        broker.getBootstrapServers(),
                        "SASL_PLAINTEXT",
                        USER,
                        Sensitive.of(PASSWORD + "-not"),
                        "");

        BrokerAccessRefusedException refused = null;
        try (KafkaEventReceiver receiver = receiver(wrong, "finapp.authprobe-refused")) {
            Instant deadline = Instant.now().plusSeconds(60);
            while (refused == null && Instant.now().isBefore(deadline)) {
                try {
                    receiver.pollOnce();
                } catch (BrokerAccessRefusedException thrown) {
                    refused = thrown;
                }
            }
        }

        assertThat(refused).as("the broker refused the credential within the bound").isNotNull();
        assertThat(refused.getMessage())
                .contains("SaslAuthenticationException")
                .doesNotContain(USER)
                .doesNotContain(PASSWORD);
    }

    // -----------------------------------------------------------------

    private static KafkaEventReceiver receiver(BrokerConnection connection, String topic) {
        return KafkaEventReceiver.connect(
                connection,
                "probe-auth-" + UUID.randomUUID(),
                () -> {
                    throw new SQLException("no record is produced in this suite");
                },
                new InboxConsumer<>(new JdbcInboxRecordStore(), Clock.systemUTC(), Duration.ofDays(14)),
                List.of(new NoHandler(topic)),
                Duration.ofMillis(500));
    }

    private record NoHandler(String topic) implements InboxEventHandler {
        @Override
        public String consumerName() {
            return "probe.auth";
        }

        @Override
        public String eventType() {
            return "probe.Never";
        }

        @Override
        public void handle(Connection unitOfWork, ReceivedEvent event) {
            throw new AssertionError("no record is produced in this suite");
        }
    }
}
