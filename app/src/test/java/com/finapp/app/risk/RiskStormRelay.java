package com.finapp.app.risk;

import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxKey;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.platform.correlation.CorrelationContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.context.ApplicationContext;

/**
 * The risk storm's relay (`P13-TST-001`): no broker in the database tier (the BNPL storm's ruling - the kafka tier's
 * subject), so the storm delivers every event risk consumes from {@code platform.outbox_event} to risk's own inbox
 * handlers ({@link RiskSignalIntake}, consumer {@code risk.signals}) on an instance's own beans, through the platform's
 * own {@link InboxConsumer} - the dedupe record and the handler in one transaction, exactly as the Kafka shell drives
 * them ({@code KafkaEventReceiver#handleOne}). Under chaos each event is delivered one to three times, on either
 * instance, a batch shuffled (reordered) and a fifth of it held back a pass (delayed) - the at-least-once delivery the
 * inbox exists for. Every delivery's outcome is counted; a handler's failure is counted and the event redelivered by a
 * later pass, never dropped.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
final class RiskStormRelay {

    /** The event types risk consumes - every source's (the composition registers one handler per source). */
    static final Set<String> CONSUMED = Set.copyOf(Arrays.stream(RiskSignalIntake.Source.values())
            .map(RiskSignalIntake.Source::eventType).toList());

    /** An instance as the relay sees it: its name, its beans. */
    record Target(String name, ApplicationContext beans) {}

    private final Supplier<List<Target>> targets;
    /** Every consumed event delivered at least once and acknowledged (PROCESSED or SKIPPED_DUPLICATE). */
    final Set<String> delivered = ConcurrentHashMap.newKeySet();
    /** Events held back by a delay, delivered on the next pass. */
    private final Set<String> delayed = ConcurrentHashMap.newKeySet();
    /** Outcome -> deliveries. */
    final Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
    /** Every handler failure: the target, the event type and the exception's message - each redelivered later. */
    final List<String> failures = new java.util.concurrent.CopyOnWriteArrayList<>();

    RiskStormRelay(Supplier<List<Target>> targets) {
        this.targets = targets;
    }

    /**
     * One pass over every consumed event not yet acknowledged: each delivered (with chaos: one to three times, on random
     * instances, the batch shuffled, a fifth delayed a pass). Returns how many remain unacknowledged.
     */
    int pass(boolean chaos) throws SQLException {
        List<ReceivedEvent> pending = pending();
        if (chaos) {
            Collections.shuffle(pending, RiskStormWorld.RANDOMNESS);
        }
        List<Target> live = targets.get();
        int left = 0;
        for (ReceivedEvent event : pending) {
            String id = event.eventId().value().toString();
            if (chaos && !delayed.contains(id) && RiskStormWorld.RANDOMNESS.nextInt(5) == 0) {
                delayed.add(id);
                count("delayed a pass");
                left++;
                continue;
            }
            int copies = chaos ? 1 + RiskStormWorld.RANDOMNESS.nextInt(3) : 1;
            boolean acknowledged = false;
            for (int copy = 0; copy < copies; copy++) {
                Target target = live.get(RiskStormWorld.RANDOMNESS.nextInt(live.size()));
                acknowledged |= deliver(target, event);
            }
            if (acknowledged) {
                delivered.add(id);
            } else {
                left++;
            }
        }
        return left;
    }

    /** Delivers until every consumed event is acknowledged, or {@code passes} run out; returns what remains. */
    int drain(int passes) throws SQLException {
        int left = Integer.MAX_VALUE;
        for (int i = 0; i < passes && left > 0; i++) {
            left = pass(false);
        }
        return left;
    }

    /** One delivery of {@code event} to every risk handler for its type on {@code target}; true when acknowledged. */
    boolean deliver(Target target, ReceivedEvent event) {
        InboxConsumer<Connection> inbox = inboxOf(target);
        DataSource dataSource = target.beans().getBean(DataSource.class);
        boolean acknowledged = true;
        for (RiskSignalIntake handler : target.beans().getBeansOfType(RiskSignalIntake.class).values()) {
            if (!handler.eventType().equals(event.eventType())) {
                continue;
            }
            Correlation correlation = new Correlation(event.correlationId(),
                    CausationId.of(event.eventId().value().toString()));
            try (Connection unitOfWork = dataSource.getConnection()) {
                unitOfWork.setAutoCommit(false);
                try (CorrelationContext.Scope scope = CorrelationContext.enter(correlation)) {
                    InboxConsumer.Outcome outcome = inbox.consume(unitOfWork,
                            new InboxKey(handler.consumerName(), event.eventId().value().toString()), event.eventType(),
                            connection -> handler.handle(connection, event));
                    if (outcome == InboxConsumer.Outcome.CONTENDED) {
                        unitOfWork.rollback();
                        acknowledged = false;
                    } else {
                        unitOfWork.commit();
                    }
                    count(target.name() + " " + outcome);
                } catch (RuntimeException handlerFailed) {
                    unitOfWork.rollback();
                    acknowledged = false;
                    count(target.name() + " FAILED " + handlerFailed.getClass().getSimpleName());
                    failures.add(target.name() + " " + event.eventType() + ": " + handlerFailed);
                }
            } catch (SQLException storage) {
                acknowledged = false;
                count(target.name() + " NO_UNIT_OF_WORK " + storage.getSQLState());
            }
        }
        return acknowledged;
    }

    @SuppressWarnings("unchecked")
    private static InboxConsumer<Connection> inboxOf(Target target) {
        return target.beans().getBean(InboxConsumer.class);
    }

    private void count(String outcome) {
        outcomes.computeIfAbsent(outcome, k -> new AtomicInteger()).incrementAndGet();
    }

    /** The consumed events not yet acknowledged, in the outbox's order. */
    List<ReceivedEvent> pending() throws SQLException {
        List<ReceivedEvent> pending = new ArrayList<>();
        try (Connection owner = DatabaseRoles.migrator(); PreparedStatement read = owner.prepareStatement(
                "SELECT event_id, event_type, event_version, schema_version, aggregate_id, aggregate_type, occurred_at,"
                        + " producer, correlation_id, causation_id, payload_media_type, payload FROM platform.outbox_event e"
                        + " WHERE event_type = ANY (?) AND NOT EXISTS (SELECT 1 FROM platform.inbox_message i WHERE"
                        + " i.consumer = '" + RiskSignalIntake.CONSUMER + "' AND i.dedupe_key = e.event_id::text)"
                        + " ORDER BY event_id")) {
            read.setArray(1, owner.createArrayOf("text", CONSUMED.toArray()));
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    UUID id = row.getObject("event_id", UUID.class);
                    if (delivered.contains(id.toString())) {
                        continue;
                    }
                    pending.add(new ReceivedEvent(EventId.of(id), row.getString("event_type"),
                            row.getInt("event_version"), row.getInt("schema_version"),
                            row.getObject("aggregate_id", UUID.class), row.getString("aggregate_type"),
                            row.getTimestamp("occurred_at").toInstant(), row.getString("producer"),
                            CorrelationId.of(row.getString("correlation_id")),
                            CausationId.of(row.getString("causation_id")), row.getString("payload_media_type"),
                            row.getBytes("payload")));
                }
            }
        }
        return pending;
    }

    /** {@code event} as the outbox holds it, delivered again later - a late redelivery of an acknowledged event. */
    ReceivedEvent reread(String eventId) throws SQLException {
        try (Connection owner = DatabaseRoles.migrator(); PreparedStatement read = owner.prepareStatement(
                "SELECT event_id, event_type, event_version, schema_version, aggregate_id, aggregate_type, occurred_at,"
                        + " producer, correlation_id, causation_id, payload_media_type, payload FROM platform.outbox_event"
                        + " WHERE event_id = ?::uuid")) {
            read.setString(1, eventId);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException("no outbox event " + eventId);
                }
                return new ReceivedEvent(EventId.of(row.getObject("event_id", UUID.class)), row.getString("event_type"),
                        row.getInt("event_version"), row.getInt("schema_version"),
                        row.getObject("aggregate_id", UUID.class), row.getString("aggregate_type"),
                        row.getTimestamp("occurred_at").toInstant(), row.getString("producer"),
                        CorrelationId.of(row.getString("correlation_id")), CausationId.of(row.getString("causation_id")),
                        row.getString("payload_media_type"), row.getBytes("payload"));
            }
        }
    }

    /** A synthesized event of {@code type} (a delayed or reordered copy of a domain's shape) - never written to the outbox. */
    static ReceivedEvent synthesized(String type, UUID aggregate, String aggregateType, Instant occurredAt, String producer,
            byte[] payload) {
        return new ReceivedEvent(EventId.next(RiskStormWorld.IDS), type, 1, 1, aggregate, aggregateType, occurredAt,
                producer, CorrelationId.of(UUID.randomUUID().toString()), CausationId.of("p13-tst-001"),
                "application/json", payload);
    }
}
