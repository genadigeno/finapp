package com.finapp.platform.deadletter;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The statements the dead-letter doors run (`P15-TSK-028`), every one on the caller's transaction.
 *
 * <p><strong>Lock first, then read in a new statement.</strong> A door takes the subject row {@code FOR UPDATE} before anything else,
 * and only then asks what else is true of it. Under {@code READ COMMITTED} the next statement has a fresh snapshot, so a proposal
 * committed while this door waited for the row is seen. A single conditional {@code UPDATE ... NOT EXISTS} would re-check only the
 * row it waited for, against its original snapshot, and would miss that proposal.
 */
public interface DeadLetterStore {

    /** What a discard is of. */
    enum SubjectKind {
        OUTBOX_EVENT,
        INBOX_RECORD
    }

    /** The door's view of an outbox row. */
    record EventRow(UUID eventId, String eventType, boolean abandoned, boolean discarded, boolean published, boolean replayed, boolean withinDedupeWindow) {}

    /** One abandoned event in the list: never the payload, and the failure's class rather than its message. */
    record AbandonedEvent(UUID eventId, UUID aggregateId, String eventType, int attempts, Instant abandonedAt, String failureClass) {}

    /** The door's view of a parked record. */
    record ParkedRow(UUID parkId, String consumer, String dedupeKey, String group, String messageType, String state) {}

    /** One parked record in the list. */
    record ParkedRecord(UUID parkId, String consumer, String dedupeKey, String group, String messageType, int attempts, Instant parkedAt, String failureClass) {}

    /** A discard proposal. The reason is what the second person reads; it never reaches a log line. */
    record Proposal(UUID discardId, SubjectKind kind, UUID subjectId, String status, String proposedBy, Instant proposedAt, String reason) {

        @Override
        public String toString() {
            return "Proposal[" + discardId + ", " + kind + ", " + status + "]";
        }
    }

    /** Parked records per consumer group, for the gauge. */
    java.util.Map<String, Long> parkedByGroup(Connection connection);

    /** One event a replay selected. */
    record ReplayCandidate(UUID eventId, boolean withinDedupeWindow) {}

    /** A replayed row still waiting to be published. */
    record PendingReplay(UUID eventId, String eventType, boolean withinDedupeWindow) {}

    /**
     * Where a page of one of the queues resumes (`P17-TSK-042`, `API_CONVENTIONS.md` section 7): after the row whose instant and
     * identifier these are - an event's {@code dead_lettered_at} and {@code event_id}, a record's {@code parked_at} and
     * {@code park_id}, a proposal's {@code proposed_at} and {@code discard_id}.
     */
    record Position(Instant at, UUID id) {

        public Position {
            java.util.Objects.requireNonNull(at, "at must not be null");
            java.util.Objects.requireNonNull(id, "id must not be null");
        }
    }

    /**
     * At most {@code limit} abandoned events, oldest abandonment first, the event's id breaking the tie, strictly after
     * {@code after} when one is named - a keyset page (`P17-TSK-042`; read "the oldest {@code limit}, unordered among equal
     * instants" until then).
     */
    List<AbandonedEvent> abandoned(Connection unitOfWork, Optional<Position> after, int limit);

    /** At most {@code limit} parked records, oldest first, the park's id breaking the tie, strictly after {@code after}. */
    List<ParkedRecord> parked(Connection unitOfWork, Optional<Position> after, int limit);

    /** At most {@code limit} discards awaiting a second person, oldest proposal first, the discard's id breaking the tie. */
    List<Proposal> standingProposals(Connection unitOfWork, Optional<Position> after, int limit);

    /** Locks the row. {@code withinDedupeWindow} is judged by the server against {@code retention}. */
    Optional<EventRow> lockEvent(Connection unitOfWork, UUID eventId, Duration retention);

    /** Returns an abandoned row to the relay, its attempts cleared. */
    void returnToRelay(Connection unitOfWork, UUID eventId);

    /** The row stays, and keeps {@code dead_lettered_at}; it stops being pending. */
    void discardEvent(Connection unitOfWork, UUID eventId);

    Optional<ParkedRow> lockParked(Connection unitOfWork, UUID parkId);

    /** Back to failing with its count cleared, so that the next delivery runs the handler. */
    void releaseParked(Connection unitOfWork, UUID parkId);

    void discardParked(Connection unitOfWork, UUID parkId);

    boolean hasStandingDiscard(Connection unitOfWork, SubjectKind kind, UUID subjectId);

    void insertProposal(Connection unitOfWork, UUID discardId, SubjectKind kind, UUID subjectId, String proposedBy, String reason);

    Optional<Proposal> lockProposal(Connection unitOfWork, UUID discardId);

    void decide(Connection unitOfWork, UUID discardId, String status, String decidedBy, String reason);

    /**
     * Locks every published event of the type whose first publication falls in {@code [from, to)}, and judges each against {@code
     * retention}. A row already queued has {@code published_at} NULL and is not selected.
     *
     * @param limit how many to return at most
     */
    List<ReplayCandidate> lockForReplay(Connection unitOfWork, String eventType, Instant from, Instant to, Duration retention, int limit);

    /** Keeps every consumer's dedupe row of these events for a full {@code retention} from now. */
    void extendDedupe(Connection unitOfWork, List<UUID> eventIds, Duration retention);

    /** Returns published events to the relay, keeping their first publication. */
    void requeue(Connection unitOfWork, List<UUID> eventIds);

    /** The other pending replayed rows of an event's aggregate, locked and judged: what acting on that event sets moving. */
    List<PendingReplay> lockPendingReplays(Connection unitOfWork, UUID eventId, Duration retention);

    /** Withdraws replays that can no longer be delivered safely: each row returns to its first publication, and is not republished. */
    void withdrawReplays(Connection unitOfWork, List<UUID> eventIds);
}
