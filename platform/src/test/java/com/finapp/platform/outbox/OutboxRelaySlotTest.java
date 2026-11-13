package com.finapp.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which aggregates a relay cycle counts against its batch, and when it calls the batch full (`P16-TSK-013`, ADR-0136).
 *
 * <p>Every instance reads the same oldest heads. One that arrives a moment after another has drained an aggregate locks it, finds
 * nothing left and - before this change - counted it, so a late instance could spend its whole batch on aggregates already published
 * and more instances added no drain ({@code CAPACITY_MODEL.md} §3.4, read from the code). Now such an aggregate takes no slot, and a
 * cycle is full only when it worked its whole batch and failed nothing, which is what lets the schedule poll again at once.
 *
 * <p>What makes ten relays safe - the advisory lock per aggregate, the read inside it, the stop at a failed or not-due row and the
 * conditional mark - is PostgreSQL's and is {@code OutboxRelayTest}'s, on the database tier ({@code
 * OutboxRelayTest#aDrainedCandidateTakesNoSlot} written beside it and owed, `POSTPONED_ISSUES.md` PI-023). This class reads the
 * relay's own counting over a scripted JDBC double that answers its four statements as the database would: the candidate list, the
 * lock, an aggregate's pending rows and the marks. Hermetic: no database, no broker.
 */
@DisplayName("a relay cycle's batch: a drained candidate takes no slot, and a cycle is full only when it worked its batch and failed nothing")
class OutboxRelaySlotTest {

    private static final Instant OCCURRED = Instant.parse("2026-11-13T09:00:00Z");
    private final IdGenerator ids = new IdGenerator(Clock.fixed(OCCURRED, ZoneOffset.UTC), new Random(13L));

    @Test
    @DisplayName("aggregates another relay drained after the candidates were read take no slot: the batch is filled from the rest")
    void aDrainedCandidateTakesNoSlot() {
        ScriptedOutbox outbox = new ScriptedOutbox();
        List<UUID> drainedElsewhere = List.of(ids.next(), ids.next(), ids.next());
        drainedElsewhere.forEach(outbox::candidateWithNothingLeft);
        List<UUID> pending = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID aggregate = ids.next();
            outbox.candidate(aggregate, 1);
            pending.add(aggregate);
        }
        RecordingPublisher publisher = new RecordingPublisher(Set.of());

        RelayPollResult result = outbox.relay(publisher, 4).pollOnce();

        assertThat(result.published()).as("four slots, four aggregates with something to publish").isEqualTo(4);
        assertThat(result.aggregatesDrained()).isEqualTo(4);
        assertThat(publisher.aggregates()).containsExactlyElementsOf(pending.subList(0, 4));
        assertThat(result.batchFull()).as("a whole batch worked and nothing failed: the schedule may poll again at once").isTrue();
        assertThat(outbox.locked()).as("the drained ones were still locked and read, never published past the lock")
                .containsAll(drainedElsewhere);
    }

    @Test
    @DisplayName("a refused lock takes no slot either, as before")
    void aRefusedLockTakesNoSlot() {
        ScriptedOutbox outbox = new ScriptedOutbox();
        UUID held = ids.next();
        outbox.candidate(held, 2);
        outbox.refuseLock(held);
        UUID free = ids.next();
        outbox.candidate(free, 2);
        RecordingPublisher publisher = new RecordingPublisher(Set.of());

        RelayPollResult result = outbox.relay(publisher, 1).pollOnce();

        assertThat(publisher.aggregates()).containsExactly(free, free);
        assertThat(result.aggregatesDrained()).isEqualTo(1);
        assertThat(result.batchFull()).isTrue();
    }

    @Test
    @DisplayName("a cycle that failed an attempt is never full, so a broker in trouble keeps the interval and each row its own backoff")
    void aFailedAttemptIsNeverFull() {
        ScriptedOutbox outbox = new ScriptedOutbox();
        UUID failing = ids.next();
        outbox.candidate(failing, 1);
        UUID healthy = ids.next();
        outbox.candidate(healthy, 1);
        RecordingPublisher publisher = new RecordingPublisher(Set.of(failing));

        RelayPollResult result = outbox.relay(publisher, 2).pollOnce();

        assertThat(result.aggregatesDrained()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.published()).isEqualTo(1);
        assertThat(result.batchFull()).as("the batch was worked, and an attempt failed").isFalse();
        assertThat(outbox.failuresMarked()).isEqualTo(1);
    }

    @Test
    @DisplayName("a cycle short of its batch is not full: the backlog is drained, and the schedule waits its interval")
    void aShortCycleIsNotFull() {
        ScriptedOutbox outbox = new ScriptedOutbox();
        outbox.candidateWithNothingLeft(ids.next());
        outbox.candidate(ids.next(), 3);
        RecordingPublisher publisher = new RecordingPublisher(Set.of());

        RelayPollResult result = outbox.relay(publisher, 4).pollOnce();

        assertThat(result.published()).isEqualTo(3);
        assertThat(result.aggregatesDrained()).isEqualTo(1);
        assertThat(result.batchFull()).isFalse();
        assertThat(RelayPollResult.idle().batchFull()).isFalse();
    }

    // ------------------------------------------------------------------------------------------------------------------------------

    /** A publisher that records each event's aggregate and fails, every time, an aggregate it was told to. */
    private record RecordingPublisher(Set<UUID> failing, List<UUID> delivered) implements EventPublisher {

        RecordingPublisher(Set<UUID> failing) {
            this(failing, new ArrayList<>());
        }

        @Override
        public void publish(PendingEvent event) {
            if (failing.contains(event.aggregateId())) {
                throw new IllegalStateException("scripted publication failure");
            }
            delivered.add(event.aggregateId());
        }

        List<UUID> aggregates() {
            return delivered;
        }
    }

    /**
     * The outbox as the relay's four statements see it: the candidates in the order the database would return them, a lock refused
     * or granted per aggregate, each aggregate's pending rows (gone once marked published), and the marks counted.
     */
    private final class ScriptedOutbox {

        private final List<UUID> candidates = new ArrayList<>();
        private final Map<UUID, List<UUID>> pending = new LinkedHashMap<>();
        private final Map<Integer, UUID> byLockKey = new HashMap<>();
        private final Set<UUID> refused = new HashSet<>();
        private final List<UUID> locked = new ArrayList<>();
        private int failures;

        void candidate(UUID aggregate, int events) {
            candidates.add(aggregate);
            byLockKey.put(aggregate.hashCode(), aggregate);
            List<UUID> rows = new ArrayList<>();
            for (int i = 0; i < events; i++) {
                rows.add(ids.next());
            }
            pending.put(aggregate, rows);
        }

        void candidateWithNothingLeft(UUID aggregate) {
            candidate(aggregate, 0);
        }

        void refuseLock(UUID aggregate) {
            refused.add(aggregate);
        }

        List<UUID> locked() {
            return locked;
        }

        int failuresMarked() {
            return failures;
        }

        OutboxRelay relay(EventPublisher publisher, int aggregatesPerPoll) {
            return new OutboxRelay(this::connection, publisher, RetryPolicy.DEFAULT, aggregatesPerPoll, 100,
                    OutboxRelay.Abandonments.NOT_RECORDED);
        }

        private Connection connection() {
            return proxy(Connection.class, (self, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> statement((String) args[0]);
                case "setAutoCommit", "commit", "rollback", "close" -> null;
                case "isClosed" -> false;
                default -> throw new UnsupportedOperationException("the double does not answer Connection." + method.getName());
            });
        }

        private PreparedStatement statement(String sql) {
            Map<Integer, Object> parameters = new HashMap<>();
            return proxy(PreparedStatement.class, (self, method, args) -> switch (method.getName()) {
                case "setInt", "setObject", "setString", "setLong" -> {
                    parameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "executeQuery" -> query(sql, parameters);
                case "executeUpdate" -> update(sql, parameters);
                case "close" -> null;
                default -> throw new UnsupportedOperationException("the double does not answer PreparedStatement." + method.getName());
            });
        }

        private ResultSet query(String sql, Map<Integer, Object> parameters) {
            List<Map<String, Object>> rows = new ArrayList<>();
            if (sql.contains("DISTINCT ON (aggregate_id)")) {
                int limit = (Integer) parameters.get(1);
                candidates.stream().limit(limit).forEach(aggregate -> rows.add(Map.of("aggregate_id", aggregate)));
            } else if (sql.contains("pg_try_advisory_xact_lock")) {
                UUID aggregate = byLockKey.get((Integer) parameters.get(2));
                boolean granted = aggregate != null && !refused.contains(aggregate);
                if (granted) {
                    locked.add(aggregate);
                }
                rows.add(Map.of("1", granted));
            } else if (sql.contains("WHERE aggregate_id = ? AND published_at IS NULL")) {
                UUID aggregate = (UUID) parameters.get(1);
                for (UUID event : pending.getOrDefault(aggregate, List.of())) {
                    rows.add(row(event, aggregate));
                }
            } else {
                throw new UnsupportedOperationException("the double does not answer: " + sql);
            }
            return results(rows);
        }

        private int update(String sql, Map<Integer, Object> parameters) {
            if (sql.contains("SET published_at = now()")) {
                UUID event = (UUID) parameters.get(1);
                pending.values().forEach(rows -> rows.remove(event));
                return 1;
            }
            if (sql.contains("last_error = ?")) {
                failures++;
                return 1;
            }
            throw new UnsupportedOperationException("the double does not answer: " + sql);
        }

        private Map<String, Object> row(UUID event, UUID aggregate) {
            Map<String, Object> row = new HashMap<>();
            row.put("event_id", event);
            row.put("event_type", "probe.Happened");
            row.put("event_version", 1);
            row.put("schema_version", 1);
            row.put("aggregate_id", aggregate);
            row.put("aggregate_type", "Probe");
            row.put("occurred_at", Timestamp.from(OCCURRED));
            row.put("producer", "probe");
            row.put("correlation_id", "corr-" + event);
            row.put("causation_id", "cause-" + event);
            row.put("payload", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            row.put("payload_media_type", "application/json");
            row.put("attempts", 0);
            row.put("due", true);
            row.put("abandoned", false);
            return row;
        }

        private ResultSet results(List<Map<String, Object>> rows) {
            int[] at = {-1};
            return proxy(ResultSet.class, (self, method, args) -> {
                String name = method.getName();
                if (name.equals("next")) {
                    at[0]++;
                    return at[0] < rows.size();
                }
                if (name.equals("close")) {
                    return null;
                }
                Object column = args != null && args.length > 0 ? args[0] : null;
                String key = column instanceof Integer index ? String.valueOf(index) : (String) column;
                Object value = rows.get(at[0]).get(key);
                return switch (name) {
                    case "getObject", "getString", "getInt", "getBoolean", "getTimestamp", "getBytes" -> value;
                    default -> throw new UnsupportedOperationException("the double does not answer ResultSet." + name);
                };
            });
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(OutboxRelaySlotTest.class.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
            switch (method.getName()) {
                case "toString":
                    return "scripted " + type.getSimpleName();
                case "hashCode":
                    return System.identityHashCode(self);
                case "equals":
                    return self == args[0];
                default:
                    return handler.invoke(self, method, args);
            }
        }));
    }
}
