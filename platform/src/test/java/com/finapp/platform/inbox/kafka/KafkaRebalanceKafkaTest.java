package com.finapp.platform.inbox.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxEventHandler;
import com.finapp.platform.inbox.JdbcInboxRecordStore;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.platform.outbox.BrokerConnection;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.outbox.KafkaEventPublisher;
import com.finapp.platform.outbox.OutboxRelay;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A rolling step under the cooperative assignor, on a real broker (`P15-TSK-026`; the kafka tier,
 * owed to `P15-TST-002` under C14).
 *
 * <p>Two members of one group, built from {@link KafkaEventReceiver#consumerProperties} - the
 * production assignor and timeouts, not a test's - over a four-partition topic. A second member
 * joins and then leaves without ever committing an offset, which is a replica killed between its
 * database commit and its offset commit. What must hold:
 *
 * <ul>
 *   <li><strong>Cooperative</strong>: when the second member joins, the first gives up only the
 *       partitions that move and keeps the rest; when it leaves, the first is revoked nothing and
 *       takes its partitions. The eager protocol would have revoked every partition both times.
 *   <li><strong>Correct regardless</strong>: every record the leaver applied is redelivered to the
 *       stayer - the leaver committed no offset - and the inbox answers each with a duplicate, so
 *       every event has exactly one effect and none is lost.
 * </ul>
 */
@Tag("kafka")
@DisplayName("a member joining and leaving under the cooperative assignor moves only its partitions (P15-TSK-026)")
class KafkaRebalanceKafkaTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String EFFECTS = "rebalance_effect_probe";
    private static final int PARTITIONS = 4;
    private static final int EVENTS = 40;

    private final String producerName = "rebalance" + UUID.randomUUID().toString().substring(0, 8);
    private final String consumerName = producerName + ".counting";

    private static Connection probe;

    @BeforeAll
    static void createEffectsTable() throws SQLException {
        probe = DatabaseRoles.bootstrap();
        probe.setAutoCommit(false);
        try (Statement statement = probe.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS " + EFFECTS
                            + " (id BIGSERIAL PRIMARY KEY, consumer TEXT NOT NULL, event_id TEXT NOT NULL)");
        }
        probe.commit();
    }

    @AfterAll
    static void dropEffectsTable() throws SQLException {
        if (probe != null) {
            try (Statement statement = probe.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + EFFECTS);
            }
            probe.commit();
            probe.close();
        }
    }

    @Test
    @DisplayName("the stayer keeps its partitions, takes the leaver's, and every event has one effect")
    void aLeaverMovesOnlyItsPartitionsAndNothingIsAppliedTwice() throws Exception {
        String topic = KafkaEventPublisher.topicFor(producerName);
        try (Admin admin = Admin.create(adminConfig())) {
            admin.createTopics(List.of(new NewTopic(topic, PARTITIONS, (short) 1))).all().get();
        }
        for (int i = 0; i < EVENTS / 2; i++) {
            writeEvent();
        }
        relayEverything();
        String group = "probe-rebalance-" + UUID.randomUUID();

        Member stayer = new Member(group, topic, false);
        try {
            pollUntil(() -> stayer.assigned.size() == PARTITIONS && effectCount() >= EVENTS / 2, stayer);
            Set<TopicPartition> before = new HashSet<>(stayer.assigned);
            int revocationsBeforeTheLeave;

            Member leaver = new Member(group, topic, true);
            try {
                pollUntil(
                        () -> !leaver.assigned.isEmpty()
                                && stayer.assigned.size() + leaver.assigned.size() == PARTITIONS,
                        stayer,
                        leaver);
                assertThat(stayer.revocations)
                        .as("on the join the stayer gave up only what moved, never everything")
                        .isNotEmpty()
                        .allSatisfy(revoked -> assertThat(revoked).isNotEqualTo(before));
                assertThat(stayer.assigned).as("and kept the rest through the join").isNotEmpty();
                revocationsBeforeTheLeave = stayer.revocations.size();

                // Records for both members, which the leaver applies and never acknowledges: a
                // replica killed between its database commits and its offset commits.
                for (int i = 0; i < EVENTS / 2; i++) {
                    writeEvent();
                }
                relayEverything();
                pollUntil(() -> effectCount() >= EVENTS && leaver.applied > 0, stayer, leaver);
            } finally {
                leaver.receiver.close();
            }

            pollUntil(() -> stayer.assigned.size() == PARTITIONS && stayer.duplicates > 0, stayer);
            // Settle stragglers, so an unexpected extra effect is seen rather than missed.
            for (int i = 0; i < 5; i++) {
                stayer.poll();
            }
            assertThat(stayer.revocations)
                    .as("the leave revoked nothing from the stayer: it only gained")
                    .hasSize(revocationsBeforeTheLeave);
        } finally {
            stayer.receiver.close();
        }
        assertThat(effectCount()).as("none lost").isEqualTo(EVENTS);
        assertThat(eventsAppliedTwice()).as("none applied twice").isEmpty();
    }

    // -----------------------------------------------------------------

    /** One group member: the production configuration, a listener recording what moved. */
    private final class Member {
        private final KafkaEventReceiver receiver;
        private final Set<TopicPartition> assigned = new HashSet<>();
        private final List<Set<TopicPartition>> revocations = new CopyOnWriteArrayList<>();
        private int applied;
        private int duplicates;

        Member(String group, String topic, boolean committingNothing) {
            KafkaConsumer<String, byte[]> consumer =
                    new KafkaConsumer<>(
                            KafkaEventReceiver.consumerProperties(BrokerConnection.plaintext(bootstrap()), group));
            consumer.subscribe(
                    List.of(topic),
                    new ConsumerRebalanceListener() {
                        @Override
                        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                            if (!partitions.isEmpty()) {
                                revocations.add(new HashSet<>(partitions));
                            }
                            assigned.removeAll(partitions);
                        }

                        @Override
                        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                            assigned.addAll(partitions);
                        }

                        @Override
                        public void onPartitionsLost(Collection<TopicPartition> partitions) {
                            assigned.removeAll(partitions);
                        }
                    });
            this.receiver =
                    new KafkaEventReceiver(
                            committingNothing ? committingNothing(consumer) : consumer,
                            topic,
                            DatabaseRoles::bootstrap,
                            new InboxConsumer<>(new JdbcInboxRecordStore(), Clock.systemUTC(), Duration.ofDays(14)),
                            com.finapp.platform.testing.InboxParkingDoubles.NEVER_PARKS,
                            List.of(counting(topic)),
                            Duration.ofMillis(500));
        }

        void poll() {
            ReceiverPollResult result = receiver.pollOnce();
            applied += result.processed();
            duplicates += result.duplicates();
        }
    }

    private void pollUntil(BooleanSupplier done, Member... members) {
        Instant deadline = Instant.now().plusSeconds(90);
        while (!done.getAsBoolean()) {
            assertThat(Instant.now()).as("the condition was not reached in time").isBefore(deadline);
            for (Member member : members) {
                member.poll();
            }
        }
    }

    private InboxEventHandler counting(String topic) {
        return new InboxEventHandler() {
            @Override
            public String consumerName() {
                return consumerName;
            }

            @Override
            public String topic() {
                return topic;
            }

            @Override
            public String eventType() {
                return "probe.SomethingHappened";
            }

            @Override
            public void handle(Connection unitOfWork, ReceivedEvent event) {
                try (PreparedStatement insert =
                        unitOfWork.prepareStatement(
                                "INSERT INTO " + EFFECTS + " (consumer, event_id) VALUES (?, ?)")) {
                    insert.setString(1, consumerName);
                    insert.setString(2, event.eventId().value().toString());
                    insert.executeUpdate();
                } catch (SQLException failure) {
                    throw new IllegalStateException("probe effect failed", failure);
                }
            }
        };
    }

    /** Every acknowledgement silently lost - the crash window between DB and offset commit. */
    @SuppressWarnings("unchecked")
    private static Consumer<String, byte[]> committingNothing(Consumer<String, byte[]> delegate) {
        return (Consumer<String, byte[]>)
                Proxy.newProxyInstance(
                        Consumer.class.getClassLoader(),
                        new Class<?>[] {Consumer.class},
                        (proxy, method, args) -> {
                            if (method.getName().startsWith("commitSync")
                                    || method.getName().startsWith("commitAsync")) {
                                return null;
                            }
                            try {
                                return method.invoke(delegate, args);
                            } catch (InvocationTargetException thrown) {
                                throw thrown.getCause();
                            }
                        });
    }

    private void writeEvent() throws SQLException {
        EventEnvelope envelope =
                new EventEnvelope(
                        EventId.next(IDS),
                        "probe.SomethingHappened",
                        1,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        new ProbeId(IDS.next()), // a fresh aggregate each: spread over the partitions
                        "Probe",
                        Instant.now(),
                        producerName,
                        CorrelationId.of(UUID.randomUUID().toString()),
                        CausationId.of("command-1"));
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            new JdbcOutboxWriter().write(app, envelope, "x".getBytes(StandardCharsets.UTF_8), "text/plain");
            app.commit();
        }
        try (Connection app = DatabaseRoles.application();
                PreparedStatement update =
                        app.prepareStatement(
                                "UPDATE platform.outbox_event SET next_attempt_at = now() - INTERVAL '1 minute'"
                                        + " WHERE event_id = ?")) {
            update.setObject(1, envelope.eventId().value());
            update.executeUpdate();
        }
    }

    private void relayEverything() {
        try (KafkaEventPublisher publisher =
                KafkaEventPublisher.connect(BrokerConnection.plaintext(bootstrap()), Duration.ofSeconds(10))) {
            OutboxRelay relay = new OutboxRelay(DatabaseRoles::application, publisher, OutboxRelay.Abandonments.NOT_RECORDED);
            Instant deadline = Instant.now().plusSeconds(60);
            while (relay.pollOnce().published() > 0 && Instant.now().isBefore(deadline)) {
                // Each poll publishes a bounded batch; drain until a poll publishes nothing.
            }
        }
    }

    private int effectCount() {
        try (Connection reader = DatabaseRoles.bootstrap();
                PreparedStatement select =
                        reader.prepareStatement("SELECT count(*) FROM " + EFFECTS + " WHERE consumer = ?")) {
            select.setString(1, consumerName);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("could not count effects", failure);
        }
    }

    private List<String> eventsAppliedTwice() throws SQLException {
        List<String> twice = new ArrayList<>();
        try (Connection reader = DatabaseRoles.bootstrap();
                PreparedStatement select =
                        reader.prepareStatement(
                                "SELECT event_id FROM " + EFFECTS
                                        + " WHERE consumer = ? GROUP BY event_id HAVING count(*) > 1")) {
            select.setString(1, consumerName);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    twice.add(rows.getString(1));
                }
            }
        }
        return twice;
    }

    private static Properties adminConfig() {
        Properties config = new Properties();
        config.put("bootstrap.servers", bootstrap());
        return config;
    }

    private static String bootstrap() {
        String bootstrap = System.getProperty("finapp.kafka.bootstrap");
        assertThat(bootstrap)
                .as("KafkaUnderTest publishes this for the kafka tier; its absence means the task"
                        + " did not supply the image")
                .isNotNull();
        return bootstrap;
    }

    static final class ProbeId extends EntityId {
        ProbeId(UUID value) {
            super(value);
        }
    }
}
