package com.finapp.platform.inbox.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxEventHandler;
import com.finapp.platform.inbox.JdbcInboxRecordStore;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.platform.outbox.EventPublisher;
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
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The first consumer path, end to end (`P2-TSK-002`): outbox → relay → real broker → receiver →
 * inbox → effect, with the effect counted in a side-effect table ({@code P0-TSK-021}'s idiom)
 * rather than inferred from any component's return value.
 *
 * <p>The acceptance criterion, demonstrated rather than described: <strong>at-least-once
 * transport, exactly-once effect</strong> — under a duplicate on the wire, under a crash between
 * the database commit and the offset commit, under a rebalance, and under a handler failure
 * whose rollback must take the dedupe record with it.
 */
@Tag("kafka")
@DisplayName("Kafka records reach the inbox and effect once (P2-TSK-002)")
class KafkaInboxDeliveryKafkaTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String OUTBOX = "platform.outbox_event";
    private static final String EFFECTS = "inbox_shell_effect_probe";

    /** Each test writes under its own producer name, so topics and groups cannot collide. */
    private final String producerName = "shell" + UUID.randomUUID().toString().substring(0, 8);

    private static Connection probe;

    @BeforeAll
    static void createEffectsTable() throws SQLException {
        probe = DatabaseRoles.bootstrap();
        probe.setAutoCommit(false);
        try (Statement statement = probe.createStatement()) {
            // An ordinary table, not TEMPORARY: the receiver's connections are their own
            // sessions and a temp table would be invisible to them (the P0-TSK-016 trap).
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS " + EFFECTS
                            + " (id BIGSERIAL PRIMARY KEY, consumer TEXT NOT NULL,"
                            + " event_id TEXT NOT NULL, payload TEXT NOT NULL)");
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
    @DisplayName("an outbox event reaches a handler once - bytes verbatim, correlation on the row")
    void anOutboxEventEffectsOnce() throws Exception {
        EventEnvelope envelope = publishOne("the-fact-π-verbatim");
        AtomicReference<ReceivedEvent> seen = new AtomicReference<>();
        String consumerName = producerName + ".primary";

        try (KafkaEventReceiver receiver = realReceiver(group(), recording(consumerName, seen))) {
            pollUntil(receiver, () -> effectCount(consumerName) >= 1);
        }

        assertThat(effectCount(consumerName)).isEqualTo(1);
        ReceivedEvent event = seen.get();
        assertThat(event.eventId()).isEqualTo(envelope.eventId());
        assertThat(event.eventType()).isEqualTo("probe.SomethingHappened");
        assertThat(event.aggregateId()).isEqualTo(envelope.aggregateId().value());
        assertThat(event.correlationId()).isEqualTo(envelope.correlationId());
        assertThat(event.payload())
                .as("what the producing transaction committed, through the whole pipe")
                .isEqualTo("the-fact-π-verbatim".getBytes(StandardCharsets.UTF_8));

        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT correlation_id FROM platform.inbox_message"
                                        + " WHERE consumer = ? AND dedupe_key = ?")) {
            select.setString(1, consumerName);
            select.setString(2, envelope.eventId().value().toString());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).as("the dedupe record exists").isTrue();
                assertThat(row.getString(1))
                        .as("the effect is traceable to the producing flow (P0-TST-003)")
                        .isEqualTo(envelope.correlationId().value());
            }
        }
    }

    @Test
    @DisplayName("a duplicate on the wire - the relay's own crash duplicate - is one effect")
    void aWireDuplicateIsOneEffect() throws Exception {
        EventEnvelope envelope = writeEvent("delivered-twice");
        try (KafkaEventPublisher real = realPublisher()) {
            // P2-TSK-001's demonstrated at-least-once window, replayed here on purpose: publish
            // for real, die before the mark, republish. Two records, one eventId.
            EventPublisher publishThenCrash =
                    event -> {
                        real.publish(event);
                        throw new IllegalStateException("crashed after the acknowledgement");
                    };
            assertThat(relay(publishThenCrash).pollOnce().failed()).isEqualTo(1);
            backDate(envelope.eventId());
            assertThat(relay(real).pollOnce().published()).isEqualTo(1);
        }
        String consumerName = producerName + ".dedupe";

        Totals totals;
        try (KafkaEventReceiver receiver =
                realReceiver(group(), counting(consumerName))) {
            totals =
                    pollUntil(
                            receiver,
                            () -> false,
                            totalsSoFar ->
                                    totalsSoFar.processed >= 1 && totalsSoFar.duplicates >= 1);
        }

        assertThat(totals.processed).isEqualTo(1);
        assertThat(totals.duplicates)
                .as("the duplicate arrived and died in the inbox, exactly as designed")
                .isGreaterThanOrEqualTo(1);
        assertThat(effectCount(consumerName)).isEqualTo(1);
    }

    @Test
    @DisplayName("a crash between the effect's commit and the offset commit redelivers into the dedupe")
    void aCrashBeforeTheOffsetCommitRedelivers() throws Exception {
        EventEnvelope envelope = publishOne("survives-the-crash");
        String consumerName = producerName + ".crash";
        String group = group();

        // Instance one: the database transaction commits, and then the instance "dies" before
        // the offset commit - modelled by a consumer whose acknowledgements never happen, which
        // is byte-for-byte what the broker sees from a real crash in that window.
        Consumer<String, byte[]> unacknowledging = committingNothing(rawConsumer(group));
        unacknowledging.subscribe(List.of(topic()));
        try (KafkaEventReceiver crashed =
                new KafkaEventReceiver(
                        unacknowledging,
                        DatabaseRoles::bootstrap,
                        inbox(),
                        List.of(counting(consumerName)),
                        Duration.ofSeconds(1))) {
            pollUntil(crashed, () -> effectCount(consumerName) >= 1);
        }
        assertThat(effectCount(consumerName)).isEqualTo(1);

        // Instance two, same group: no offset was ever committed, so the broker redelivers
        // from the beginning - and the redelivery must be absorbed, not re-applied.
        Totals totals;
        try (KafkaEventReceiver restarted = realReceiver(group, counting(consumerName))) {
            totals =
                    pollUntil(
                            restarted, () -> false, totalsSoFar -> totalsSoFar.duplicates >= 1);
        }

        assertThat(totals.duplicates).isGreaterThanOrEqualTo(1);
        assertThat(totals.processed).as("nothing was re-applied").isZero();
        assertThat(effectCount(consumerName))
                .as("exactly one effect for %s, however many deliveries", envelope.eventId())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("two consumers joining across a rebalance still effect once per record")
    void aRebalanceEffectsOncePerRecord() throws Exception {
        String consumerName = producerName + ".rebalance";
        String group = group();
        publishOne("before-the-rebalance-1");
        publishOne("before-the-rebalance-2");

        try (KafkaEventReceiver first = realReceiver(group, counting(consumerName))) {
            pollUntil(first, () -> effectCount(consumerName) >= 2);

            // A second member joins mid-stream: the group rebalances, the partition may move,
            // and whatever was in flight redelivers to whoever owns it afterwards.
            try (KafkaEventReceiver second = realReceiver(group, counting(consumerName))) {
                publishOne("after-the-rebalance-3");
                publishOne("after-the-rebalance-4");

                Instant deadline = Instant.now().plusSeconds(60);
                while (effectCount(consumerName) < 4 && Instant.now().isBefore(deadline)) {
                    first.pollOnce();
                    second.pollOnce();
                }
                // Settle any straggling redeliveries, so the exactly-once assertion below can
                // see an unexpected extra effect rather than stopping at the count it hoped for.
                first.pollOnce();
                second.pollOnce();
            }
        }

        assertThat(effectCount(consumerName)).isEqualTo(4);
        try (Connection reader = DatabaseRoles.bootstrap();
                PreparedStatement select =
                        reader.prepareStatement(
                                "SELECT event_id FROM " + EFFECTS
                                        + " WHERE consumer = ? GROUP BY event_id"
                                        + " HAVING count(*) > 1")) {
            select.setString(1, consumerName);
            try (ResultSet duplicated = select.executeQuery()) {
                assertThat(duplicated.next())
                        .as("no record effected twice, whoever owned the partition when")
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("a handler failure rolls back the dedupe record, so the redelivery retries")
    void aHandlerFailureRetriesToExactlyOneEffect() throws Exception {
        publishOne("worth-retrying");
        String consumerName = producerName + ".flaky";
        AtomicInteger attempts = new AtomicInteger();
        InboxEventHandler flaky =
                handler(
                        consumerName,
                        (connection, event) -> {
                            if (attempts.incrementAndGet() == 1) {
                                throw new IllegalStateException("the first attempt fails");
                            }
                            recordEffect(connection, consumerName, event);
                        });

        Totals totals;
        try (KafkaEventReceiver receiver = realReceiver(group(), flaky)) {
            totals = pollUntil(receiver, () -> effectCount(consumerName) >= 1);
        }

        assertThat(attempts.get())
                .as("the rollback took the dedupe record with it, so the retry really ran")
                .isGreaterThanOrEqualTo(2);
        assertThat(totals.failed).isGreaterThanOrEqualTo(1);
        assertThat(effectCount(consumerName)).isEqualTo(1);
    }

    // -----------------------------------------------------------------

    static final class ProbeId extends EntityId {
        ProbeId(UUID value) {
            super(value);
        }
    }

    private String topic() {
        return "finapp." + producerName;
    }

    private static String group() {
        return "probe-group-" + UUID.randomUUID();
    }

    private static InboxConsumer<Connection> inbox() {
        return new InboxConsumer<>(new JdbcInboxRecordStore(), Clock.systemUTC(), Duration.ofDays(14));
    }

    private KafkaEventReceiver realReceiver(String group, InboxEventHandler... handlers)
            throws SQLException {
        return KafkaEventReceiver.connect(
                bootstrap(),
                "PLAINTEXT",
                group,
                DatabaseRoles::bootstrap,
                inbox(),
                List.of(handlers),
                Duration.ofSeconds(1));
    }

    private interface Reaction {
        void react(Connection connection, ReceivedEvent event) throws SQLException;
    }

    private InboxEventHandler handler(String consumerName, Reaction reaction) {
        String topic = topic();
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
                try {
                    reaction.react(unitOfWork, event);
                } catch (SQLException failure) {
                    throw new IllegalStateException("probe effect failed", failure);
                }
            }
        };
    }

    private InboxEventHandler counting(String consumerName) {
        return handler(consumerName, (connection, event) -> recordEffect(connection, consumerName, event));
    }

    private InboxEventHandler recording(String consumerName, AtomicReference<ReceivedEvent> seen) {
        return handler(
                consumerName,
                (connection, event) -> {
                    seen.set(event);
                    recordEffect(connection, consumerName, event);
                });
    }

    private static void recordEffect(Connection unitOfWork, String consumerName, ReceivedEvent event)
            throws SQLException {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + EFFECTS + " (consumer, event_id, payload) VALUES (?, ?, ?)")) {
            insert.setString(1, consumerName);
            insert.setString(2, event.eventId().value().toString());
            insert.setString(3, new String(event.payload(), StandardCharsets.UTF_8));
            insert.executeUpdate();
        }
    }

    private static int effectCount(String consumerName) {
        // The bootstrap role, because the probe table is bootstrap-owned and the application
        // role holds no grant on it - this test makes no privilege claim (P0-TSK-021 does).
        try (Connection reader = DatabaseRoles.bootstrap();
                PreparedStatement select =
                        reader.prepareStatement(
                                "SELECT count(*) FROM " + EFFECTS + " WHERE consumer = ?")) {
            select.setString(1, consumerName);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("could not count effects", failure);
        }
    }

    private record Totals(int processed, int duplicates, int contended, int failed) {}

    private interface TotalsPredicate {
        boolean satisfiedBy(Totals totals);
    }

    private static Totals pollUntil(KafkaEventReceiver receiver, BooleanSupplier done) {
        return pollUntil(receiver, done, totals -> false);
    }

    /** Polls until either condition holds or the generous bound trips - never a fixed count. */
    private static Totals pollUntil(
            KafkaEventReceiver receiver, BooleanSupplier done, TotalsPredicate enough) {
        int processed = 0;
        int duplicates = 0;
        int contended = 0;
        int failed = 0;
        Instant deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)) {
            Totals totals = new Totals(processed, duplicates, contended, failed);
            if (done.getAsBoolean() || enough.satisfiedBy(totals)) {
                break;
            }
            ReceiverPollResult result = receiver.pollOnce();
            processed += result.processed();
            duplicates += result.duplicates();
            contended += result.contended();
            failed += result.failed();
        }
        return new Totals(processed, duplicates, contended, failed);
    }

    // ----------------------------------------------------------------- producing side

    private EventEnvelope publishOne(String payload) throws SQLException {
        EventEnvelope envelope = writeEvent(payload);
        try (KafkaEventPublisher publisher = realPublisher()) {
            assertThat(relay(publisher).pollOnce().published()).isEqualTo(1);
        }
        return envelope;
    }

    private static OutboxRelay relay(EventPublisher publisher) {
        return new OutboxRelay(DatabaseRoles::application, publisher);
    }

    private static KafkaEventPublisher realPublisher() {
        return KafkaEventPublisher.connect(bootstrap(), "PLAINTEXT", Duration.ofSeconds(10));
    }

    private static String bootstrap() {
        String bootstrap = System.getProperty("finapp.kafka.bootstrap");
        assertThat(bootstrap)
                .as("KafkaUnderTest publishes this for the kafka tier; its absence means the"
                        + " task did not supply the image")
                .isNotNull();
        return bootstrap;
    }

    private KafkaConsumer<String, byte[]> rawConsumer(String group) {
        Properties config = new Properties();
        config.put("bootstrap.servers", bootstrap());
        config.put("group.id", group);
        config.put("enable.auto.commit", "false");
        config.put("auto.offset.reset", "earliest");
        config.put(
                "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        config.put(
                "value.deserializer",
                "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        return new KafkaConsumer<>(config);
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
                                return args == null
                                        ? method.invoke(delegate)
                                        : method.invoke(delegate, args);
                            } catch (InvocationTargetException real) {
                                throw real.getCause();
                            }
                        });
    }

    private EventEnvelope writeEvent(String payload) throws SQLException {
        EventEnvelope envelope =
                new EventEnvelope(
                        EventId.next(IDS),
                        "probe.SomethingHappened",
                        1,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        new ProbeId(IDS.next()),
                        "Probe",
                        Instant.now(),
                        producerName,
                        CorrelationId.of(UUID.randomUUID().toString()),
                        CausationId.of("command-1"));
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            new JdbcOutboxWriter()
                    .write(app, envelope, payload.getBytes(StandardCharsets.UTF_8), "text/plain");
            app.commit();
        }
        backDate(envelope.eventId());
        return envelope;
    }

    /** See {@code OutboxRelayTest.backDate}: the local server clock steps backwards. */
    private static void backDate(EventId eventId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement update =
                        app.prepareStatement(
                                "UPDATE " + OUTBOX
                                        + " SET next_attempt_at = now() - INTERVAL '1 minute'"
                                        + " WHERE event_id = ?")) {
            update.setObject(1, eventId.value());
            update.executeUpdate();
        }
    }
}
