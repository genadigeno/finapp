package com.finapp.platform.deadletter;

import java.io.Serial;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link DeadLetterStore} in explicit SQL (`P15-TSK-028`). Every instant comes from {@code now()}, the clock N instances share.
 *
 * <p>The window is judged here on each row: {@code COALESCE(replay_ordered_at, occurred_at) > now() - retention}. Never from
 * {@code published_at} (the fix round's F4): a publication whose acknowledgement was lost reached the broker and left no mark, so a
 * later success can postdate a delivery by days. {@code occurred_at} precedes every attempt, and a replay's order extended every
 * dedupe row from itself, so for an event passing this test every such row still exists. The instant is the writing instance's
 * clock; a few seconds' skew is small beside a window of days.
 */
public final class JdbcDeadLetterStore implements DeadLetterStore {

    private static final String EVENTS = "platform.outbox_event";
    private static final String PARKED = "platform.inbox_parked_record";
    private static final String DISCARDS = "platform.dead_letter_discard";
    private static final String DEDUPE = "platform.inbox_message";

    /** Rows of the anchor against the retention, in milliseconds so no floating point judges a boundary. */
    private static final String WITHIN_WINDOW =
            "COALESCE(replay_ordered_at, occurred_at) > now() - (? * INTERVAL '1 millisecond') AS within_window";

    static final String ABANDONED_SQL =
            "SELECT event_id, aggregate_id, event_type, attempts, dead_lettered_at, last_error FROM " + EVENTS
                    + " WHERE dead_lettered_at IS NOT NULL AND discarded_at IS NULL AND published_at IS NULL"
                    + " ORDER BY dead_lettered_at LIMIT ?";

    static final String PARKED_SQL =
            "SELECT park_id, consumer, dedupe_key, consumer_group, message_type, attempts, parked_at, last_failure FROM " + PARKED
                    + " WHERE state = 'PARKED' ORDER BY parked_at LIMIT ?";

    static final String STANDING_SQL =
            "SELECT discard_id, subject_kind, subject_id, status, proposed_by, proposed_at, proposal_reason FROM " + DISCARDS
                    + " WHERE status = 'PROPOSED' ORDER BY proposed_at LIMIT ?";

    /** Served by the partial index on parked rows, so its cost is the number of parked records, not the table. */
    static final String PARKED_BY_GROUP_SQL =
            "SELECT consumer_group, count(*) AS parked FROM " + PARKED + " WHERE state = 'PARKED' GROUP BY consumer_group";

    static final String LOCK_EVENT_SQL =
            "SELECT event_id, event_type, dead_lettered_at IS NOT NULL AS abandoned, discarded_at IS NOT NULL AS discarded, "
                    + "published_at IS NOT NULL AS published, replay_ordered_at IS NOT NULL AS replayed, " + WITHIN_WINDOW
                    + " FROM " + EVENTS + " WHERE event_id = ? FOR UPDATE";

    static final String RETURN_TO_RELAY_SQL =
            "UPDATE " + EVENTS + " SET dead_lettered_at = NULL, attempts = 0, next_attempt_at = now(), last_error = NULL"
                    + " WHERE event_id = ? AND dead_lettered_at IS NOT NULL AND discarded_at IS NULL AND published_at IS NULL";

    static final String DISCARD_EVENT_SQL =
            "UPDATE " + EVENTS + " SET discarded_at = now()"
                    + " WHERE event_id = ? AND dead_lettered_at IS NOT NULL AND discarded_at IS NULL AND published_at IS NULL";

    static final String LOCK_PARKED_SQL =
            "SELECT park_id, consumer, dedupe_key, consumer_group, message_type, state FROM " + PARKED + " WHERE park_id = ? FOR UPDATE";

    static final String RELEASE_PARKED_SQL =
            "UPDATE " + PARKED + " SET state = 'FAILING', attempts = 0, first_failed_at = now(), last_failed_at = now(), parked_at = NULL"
                    + " WHERE park_id = ? AND state = 'PARKED'";

    static final String DISCARD_PARKED_SQL =
            "UPDATE " + PARKED + " SET state = 'DISCARDED', settled_at = now() WHERE park_id = ? AND state = 'PARKED'";

    static final String HAS_STANDING_SQL =
            "SELECT 1 FROM " + DISCARDS + " WHERE subject_kind = ? AND subject_id = ? AND status = 'PROPOSED'";

    static final String INSERT_PROPOSAL_SQL =
            "INSERT INTO " + DISCARDS + " (discard_id, subject_kind, subject_id, status, proposed_by, proposed_at, proposal_reason)"
                    + " VALUES (?, ?, ?, 'PROPOSED', ?, now(), ?)";

    static final String LOCK_PROPOSAL_SQL =
            "SELECT discard_id, subject_kind, subject_id, status, proposed_by, proposed_at, proposal_reason FROM " + DISCARDS + " WHERE discard_id = ? FOR UPDATE";

    static final String DECIDE_SQL =
            "UPDATE " + DISCARDS + " SET status = ?, decided_by = ?, decided_at = now(), decision_reason = ?"
                    + " WHERE discard_id = ? AND status = 'PROPOSED'";

    static final String REPLAY_SQL =
            "SELECT event_id, " + WITHIN_WINDOW + " FROM " + EVENTS
                    + " WHERE event_type = ? AND published_at IS NOT NULL"
                    + " AND COALESCE(first_published_at, published_at) >= ? AND COALESCE(first_published_at, published_at) < ?"
                    + " ORDER BY event_id LIMIT ? FOR UPDATE";

    /**
     * Every consumer's row of each event, through the primary key: the consumers come from the table itself (a loose index scan), so
     * a handler this release no longer names, or that another replica of a rollout still runs, is not missed.
     */
    static final String EXTEND_DEDUPE_SQL =
            "WITH RECURSIVE consumers(name) AS ("
                    + " (SELECT min(consumer) FROM " + DEDUPE + ")"
                    + " UNION ALL"
                    + " SELECT (SELECT min(consumer) FROM " + DEDUPE + " WHERE consumer > name) FROM consumers WHERE name IS NOT NULL)"
                    + " UPDATE " + DEDUPE + " AS dedupe SET expires_at = GREATEST(dedupe.expires_at, now() + (? * INTERVAL '1 millisecond'))"
                    + " FROM consumers WHERE consumers.name IS NOT NULL AND dedupe.consumer = consumers.name AND dedupe.dedupe_key = ANY (?)";

    /** The other pending replayed rows of an event's aggregate: what a retry or a discard of it sets moving. */
    static final String PENDING_REPLAYS_SQL =
            "SELECT event_id, event_type, " + WITHIN_WINDOW + " FROM " + EVENTS
                    + " WHERE aggregate_id = (SELECT aggregate_id FROM " + EVENTS + " WHERE event_id = ?) AND event_id <> ?"
                    + " AND published_at IS NULL AND discarded_at IS NULL AND replay_ordered_at IS NOT NULL"
                    + " ORDER BY event_id FOR UPDATE";

    /** Back to where the replay found it: its first publication stands, and only the replay is withdrawn. */
    static final String WITHDRAW_REPLAYS_SQL =
            "UPDATE " + EVENTS + " SET published_at = first_published_at, attempts = GREATEST(attempts, 1), last_error = NULL"
                    + " WHERE event_id = ANY (?) AND published_at IS NULL AND replay_ordered_at IS NOT NULL AND first_published_at IS NOT NULL";

    static final String REQUEUE_SQL =
            "UPDATE " + EVENTS + " SET first_published_at = COALESCE(first_published_at, published_at), replay_ordered_at = now(),"
                    + " published_at = NULL, attempts = 0, next_attempt_at = now(), last_error = NULL"
                    + " WHERE event_id = ANY (?) AND published_at IS NOT NULL";

    @Override
    public Map<String, Long> parkedByGroup(Connection connection) {
        return read(() -> {
            try (PreparedStatement select = connection.prepareStatement(PARKED_BY_GROUP_SQL);
                    ResultSet rows = select.executeQuery()) {
                Map<String, Long> counts = new HashMap<>();
                while (rows.next()) {
                    counts.put(rows.getString("consumer_group"), rows.getLong("parked"));
                }
                return counts;
            }
        });
    }

    @Override
    public List<AbandonedEvent> abandoned(Connection unitOfWork, int limit) {
        return read(() -> {
            try (PreparedStatement select = unitOfWork.prepareStatement(ABANDONED_SQL)) {
                select.setInt(1, limit);
                try (ResultSet rows = select.executeQuery()) {
                    List<AbandonedEvent> out = new ArrayList<>();
                    while (rows.next()) {
                        out.add(new AbandonedEvent(rows.getObject("event_id", UUID.class), rows.getObject("aggregate_id", UUID.class),
                                rows.getString("event_type"), rows.getInt("attempts"), instant(rows, "dead_lettered_at"),
                                failureClass(rows.getString("last_error"))));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public List<ParkedRecord> parked(Connection unitOfWork, int limit) {
        return read(() -> {
            try (PreparedStatement select = unitOfWork.prepareStatement(PARKED_SQL)) {
                select.setInt(1, limit);
                try (ResultSet rows = select.executeQuery()) {
                    List<ParkedRecord> out = new ArrayList<>();
                    while (rows.next()) {
                        out.add(new ParkedRecord(rows.getObject("park_id", UUID.class), rows.getString("consumer"), rows.getString("dedupe_key"),
                                rows.getString("consumer_group"), rows.getString("message_type"), rows.getInt("attempts"), instant(rows, "parked_at"),
                                rows.getString("last_failure")));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public List<Proposal> standingProposals(Connection unitOfWork, int limit) {
        return read(() -> {
            try (PreparedStatement select = unitOfWork.prepareStatement(STANDING_SQL)) {
                select.setInt(1, limit);
                try (ResultSet rows = select.executeQuery()) {
                    List<Proposal> out = new ArrayList<>();
                    while (rows.next()) {
                        out.add(proposal(rows));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public Optional<EventRow> lockEvent(Connection unitOfWork, UUID eventId, Duration retention) {
        return read(() -> {
            try (PreparedStatement select = unitOfWork.prepareStatement(LOCK_EVENT_SQL)) {
                select.setLong(1, retention.toMillis());
                select.setObject(2, eventId);
                try (ResultSet row = select.executeQuery()) {
                    return row.next()
                            ? Optional.of(new EventRow(eventId, row.getString("event_type"), row.getBoolean("abandoned"), row.getBoolean("discarded"),
                                    row.getBoolean("published"), row.getBoolean("replayed"), row.getBoolean("within_window")))
                            : Optional.empty();
                }
            }
        });
    }

    @Override
    public void returnToRelay(Connection unitOfWork, UUID eventId) {
        update(unitOfWork, RETURN_TO_RELAY_SQL, eventId);
    }

    @Override
    public void discardEvent(Connection unitOfWork, UUID eventId) {
        update(unitOfWork, DISCARD_EVENT_SQL, eventId);
    }

    @Override
    public Optional<ParkedRow> lockParked(Connection unitOfWork, UUID parkId) {
        return read(() -> {
            try (PreparedStatement select = unitOfWork.prepareStatement(LOCK_PARKED_SQL)) {
                select.setObject(1, parkId);
                try (ResultSet row = select.executeQuery()) {
                    return row.next()
                            ? Optional.of(new ParkedRow(parkId, row.getString("consumer"), row.getString("dedupe_key"), row.getString("consumer_group"),
                                    row.getString("message_type"), row.getString("state")))
                            : Optional.empty();
                }
            }
        });
    }

    @Override
    public void releaseParked(Connection unitOfWork, UUID parkId) {
        update(unitOfWork, RELEASE_PARKED_SQL, parkId);
    }

    @Override
    public void discardParked(Connection unitOfWork, UUID parkId) {
        update(unitOfWork, DISCARD_PARKED_SQL, parkId);
    }

    @Override
    public boolean hasStandingDiscard(Connection unitOfWork, SubjectKind kind, UUID subjectId) {
        return read(() -> {
            try (PreparedStatement select = unitOfWork.prepareStatement(HAS_STANDING_SQL)) {
                select.setString(1, kind.name());
                select.setObject(2, subjectId);
                try (ResultSet row = select.executeQuery()) {
                    return row.next();
                }
            }
        });
    }

    @Override
    public void insertProposal(Connection unitOfWork, UUID discardId, SubjectKind kind, UUID subjectId, String proposedBy, String reason) {
        run(() -> {
            try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT_PROPOSAL_SQL)) {
                insert.setObject(1, discardId);
                insert.setString(2, kind.name());
                insert.setObject(3, subjectId);
                insert.setString(4, proposedBy);
                insert.setString(5, reason);
                insert.executeUpdate();
            }
        });
    }

    @Override
    public Optional<Proposal> lockProposal(Connection unitOfWork, UUID discardId) {
        return read(() -> {
            try (PreparedStatement select = unitOfWork.prepareStatement(LOCK_PROPOSAL_SQL)) {
                select.setObject(1, discardId);
                try (ResultSet row = select.executeQuery()) {
                    return row.next() ? Optional.of(proposal(row)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public void decide(Connection unitOfWork, UUID discardId, String status, String decidedBy, String reason) {
        run(() -> {
            try (PreparedStatement update = unitOfWork.prepareStatement(DECIDE_SQL)) {
                update.setString(1, status);
                update.setString(2, decidedBy);
                update.setString(3, reason);
                update.setObject(4, discardId);
                update.executeUpdate();
            }
        });
    }

    @Override
    public List<ReplayCandidate> lockForReplay(Connection unitOfWork, String eventType, Instant from, Instant to, Duration retention, int limit) {
        return read(() -> {
            try (PreparedStatement select = unitOfWork.prepareStatement(REPLAY_SQL)) {
                select.setLong(1, retention.toMillis());
                select.setString(2, eventType);
                select.setTimestamp(3, Timestamp.from(from));
                select.setTimestamp(4, Timestamp.from(to));
                select.setInt(5, limit);
                try (ResultSet rows = select.executeQuery()) {
                    List<ReplayCandidate> out = new ArrayList<>();
                    while (rows.next()) {
                        out.add(new ReplayCandidate(rows.getObject("event_id", UUID.class), rows.getBoolean("within_window")));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public void extendDedupe(Connection unitOfWork, List<UUID> eventIds, Duration retention) {
        if (eventIds.isEmpty()) {
            return;
        }
        run(() -> {
            try (PreparedStatement update = unitOfWork.prepareStatement(EXTEND_DEDUPE_SQL)) {
                update.setLong(1, retention.toMillis());
                Array keys = unitOfWork.createArrayOf("text", eventIds.stream().map(UUID::toString).toArray());
                update.setArray(2, keys);
                update.executeUpdate();
            }
        });
    }

    @Override
    public List<PendingReplay> lockPendingReplays(Connection unitOfWork, UUID eventId, Duration retention) {
        return read(() -> {
            try (PreparedStatement select = unitOfWork.prepareStatement(PENDING_REPLAYS_SQL)) {
                select.setLong(1, retention.toMillis());
                select.setObject(2, eventId);
                select.setObject(3, eventId);
                try (ResultSet rows = select.executeQuery()) {
                    List<PendingReplay> out = new ArrayList<>();
                    while (rows.next()) {
                        out.add(new PendingReplay(rows.getObject("event_id", UUID.class), rows.getString("event_type"), rows.getBoolean("within_window")));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public void withdrawReplays(Connection unitOfWork, List<UUID> eventIds) {
        if (eventIds.isEmpty()) {
            return;
        }
        run(() -> {
            try (PreparedStatement update = unitOfWork.prepareStatement(WITHDRAW_REPLAYS_SQL)) {
                update.setArray(1, unitOfWork.createArrayOf("uuid", eventIds.toArray()));
                update.executeUpdate();
            }
        });
    }

    @Override
    public void requeue(Connection unitOfWork, List<UUID> eventIds) {
        if (eventIds.isEmpty()) {
            return;
        }
        run(() -> {
            try (PreparedStatement update = unitOfWork.prepareStatement(REQUEUE_SQL)) {
                update.setArray(1, unitOfWork.createArrayOf("uuid", eventIds.toArray()));
                update.executeUpdate();
            }
        });
    }

    /** The failure's class: {@code OutboxRelay} writes {@code class: message}, and a message can name a host. */
    static String failureClass(String lastError) {
        if (lastError == null) {
            return null;
        }
        int colon = lastError.indexOf(':');
        return colon < 0 ? lastError : lastError.substring(0, colon);
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        return rows.getTimestamp(column).toInstant();
    }

    private static Proposal proposal(ResultSet rows) throws SQLException {
        return new Proposal(rows.getObject("discard_id", UUID.class), SubjectKind.valueOf(rows.getString("subject_kind")),
                rows.getObject("subject_id", UUID.class), rows.getString("status"), rows.getString("proposed_by"), instant(rows, "proposed_at"),
                rows.getString("proposal_reason"));
    }

    private static void update(Connection unitOfWork, String sql, UUID id) {
        run(() -> {
            try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
                update.setObject(1, id);
                update.executeUpdate();
            }
        });
    }

    @FunctionalInterface
    private interface Query<R> {
        R run() throws SQLException;
    }

    @FunctionalInterface
    private interface Statement {
        void run() throws SQLException;
    }

    private static <R> R read(Query<R> query) {
        try {
            return query.run();
        } catch (SQLException e) {
            throw new DeadLetterStorageException("A dead-letter statement failed", e);
        }
    }

    private static void run(Statement statement) {
        try {
            statement.run();
        } catch (SQLException e) {
            throw new DeadLetterStorageException("A dead-letter statement failed", e);
        }
    }

    /** The class only leaves this type; the driver's message can carry a host, a statement or row content. */
    public static final class DeadLetterStorageException extends RuntimeException {

        @Serial private static final long serialVersionUID = 1L;

        DeadLetterStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
