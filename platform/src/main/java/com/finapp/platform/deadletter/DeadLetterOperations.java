package com.finapp.platform.deadletter;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.AuditableAction;
import com.finapp.platform.audit.PlatformAuditAction;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The decisions behind the dead-letter doors (`P15-TSK-028`): each on the caller's transaction, audited in it.
 *
 * <p>Nothing is deleted anywhere here. Retrying returns a row to the relay; a discard is a recorded decision that leaves the row in
 * place; a replay is the relay publishing again under the same {@code event_id}, so the inbox primary key refuses what a consumer
 * already applied ({@code INV-IDEM-04}).
 *
 * <p><strong>Why a replay is refused past the dedupe window.</strong> The primary key refuses a duplicate only while the row is there,
 * so an event older than {@code finapp.inbox.retention} may have lost its row to the sweep and would be applied again. And passing that
 * test is not enough on its own: an event a moment inside the window can have its row swept before a lagging consumer reads the
 * replayed record. So the same transaction that judges the window extends every consumer's row for a full retention from now, longer
 * than the broker can still hold the record.
 */
public final class DeadLetterOperations {

    /** Replay at volume is Phase 16's. One command is one transaction, so it is bounded and refused above. */
    public static final int MAX_REPLAY_EVENTS = 1000;

    /** The most rows one page of a queue serves (`P17-TSK-042`; read "the bounded list of a read", 100, until then). */
    public static final int MAX_PAGE = 200;

    private static final int MAX_EVENT_TYPE_LENGTH = 200;
    private static final String TARGET_EVENT = "outbox.event";
    private static final String TARGET_EVENT_TYPE = "outbox.event-type";
    private static final String TARGET_PARKED = "inbox.parked-record";
    private static final String TARGET_DISCARD = "deadletter.discard";

    private final DeadLetterStore store;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;
    private final Duration retention;
    private final Predicate<String> everyConsumerNaturallyIdempotent;

    /**
     * @param retention {@code InboxConsumer#retention()}: the same value that dated every dedupe row, not a copy of it
     * @param everyConsumerNaturallyIdempotent whether every handler of a type carries the catalogue's mark; false for a type no
     *     handler consumes
     */
    public DeadLetterOperations(
            DeadLetterStore store,
            AuditWriter<Connection> audit,
            IdGenerator ids,
            Clock clock,
            Duration retention,
            Predicate<String> everyConsumerNaturallyIdempotent) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.retention = Objects.requireNonNull(retention, "retention must not be null");
        this.everyConsumerNaturallyIdempotent =
                Objects.requireNonNull(everyConsumerNaturallyIdempotent, "everyConsumerNaturallyIdempotent must not be null");
    }

    /**
     * A page of the abandoned events, oldest first, strictly after {@code after} when one is named (`P17-TSK-042`); the page is
     * recorded in this transaction, every one of them.
     */
    public Page<DeadLetterStore.AbandonedEvent> abandoned(
            Connection uow, Optional<DeadLetterStore.Position> after, int limit, Actor actor, CorrelationId correlation) {
        return page(uow, "abandoned", after, limit, actor, correlation, size -> store.abandoned(uow, after, size));
    }

    /** A page of the parked records, oldest first, strictly after {@code after}; recorded in this transaction. */
    public Page<DeadLetterStore.ParkedRecord> parked(
            Connection uow, Optional<DeadLetterStore.Position> after, int limit, Actor actor, CorrelationId correlation) {
        return page(uow, "parked", after, limit, actor, correlation, size -> store.parked(uow, after, size));
    }

    /** A page of the discards awaiting a second person, oldest proposal first, strictly after {@code after}; recorded. */
    public Page<DeadLetterStore.Proposal> standingDiscards(
            Connection uow, Optional<DeadLetterStore.Position> after, int limit, Actor actor, CorrelationId correlation) {
        return page(uow, "discards", after, limit, actor, correlation, size -> store.standingProposals(uow, after, size));
    }

    /**
     * One abandoned event by its id, while it is in the abandoned queue (`P17-TSK-124`): recorded {@code deadletter.Read} against
     * the event, in this transaction, when one is served; nothing recorded when none is.
     */
    public Optional<DeadLetterStore.AbandonedEvent> abandonedEvent(Connection uow, UUID eventId, Actor actor, CorrelationId correlation) {
        return one(uow, store.abandonedEvent(uow, eventId), TARGET_EVENT, eventId, "abandoned", actor, correlation);
    }

    /** One parked record by its id, while it is parked (`P17-TSK-124`); recorded against the record when one is served. */
    public Optional<DeadLetterStore.ParkedRecord> parkedRecord(Connection uow, UUID parkId, Actor actor, CorrelationId correlation) {
        return one(uow, store.parkedRecord(uow, parkId), TARGET_PARKED, parkId, "parked", actor, correlation);
    }

    /** One discard by its id, in whatever status it stands (`P17-TSK-124`); recorded against the discard when one is served. */
    public Optional<DeadLetterStore.Proposal> discard(Connection uow, UUID discardId, Actor actor, CorrelationId correlation) {
        return one(uow, store.discard(uow, discardId), TARGET_DISCARD, discardId, "discards", actor, correlation);
    }

    private <T> Optional<T> one(Connection uow, Optional<T> found, String targetType, UUID id, String queue, Actor actor,
            CorrelationId correlation) {
        found.ifPresent(row -> record(uow, PlatformAuditAction.DEAD_LETTERS_READ, targetType, id.toString(), Optional.empty(), actor,
                correlation, "queue=" + queue + ", served=1", AuditOutcome.SUCCEEDED));
        return found;
    }

    /** A page of one queue: the rows served, and whether more follow the last of them. */
    public record Page<T>(List<T> rows, boolean more) {

        public Page {
            rows = List.copyOf(rows);
        }
    }

    /**
     * One queue's page: one row read past {@code limit} to learn whether more follow, never served nor counted; the read
     * recorded {@code deadletter.Read} against the queue it was, with what was served (`P17-TSK-042`; read "one record of the
     * three queues together, target {@code all}" until then).
     */
    private <T> Page<T> page(Connection uow, String queue, Optional<DeadLetterStore.Position> after, int limit, Actor actor,
            CorrelationId correlation, java.util.function.IntFunction<List<T>> read) {
        Objects.requireNonNull(after, "after must not be null");
        if (limit < 1 || limit > MAX_PAGE) {
            throw new IllegalArgumentException("a page of a dead-letter queue is 1 to " + MAX_PAGE + " rows: " + limit);
        }
        List<T> found = read.apply(limit + 1);
        boolean more = found.size() > limit;
        List<T> served = more ? found.subList(0, limit) : found;
        record(uow, PlatformAuditAction.DEAD_LETTERS_READ, "deadletter.queues", queue, Optional.empty(), actor, correlation,
                "served=" + served.size() + ", continued=" + after.isPresent() + ", more=" + more, AuditOutcome.SUCCEEDED);
        return new Page<>(served, more);
    }

    /** Clears an abandonment: the relay takes the row again, in its order, under the row lock's arbitration. */
    public void retry(Connection uow, UUID eventId, Actor actor, String reason, CorrelationId correlation) {
        String given = required(reason);
        DeadLetterStore.EventRow row = store.lockEvent(uow, eventId, retention).orElseThrow(DeadLetterOperations::notFound);
        if (!row.abandoned() || row.discarded()) {
            throw refused(DeadLetterErrorCode.NOT_ABANDONED);
        }
        if (store.hasStandingDiscard(uow, DeadLetterStore.SubjectKind.OUTBOX_EVENT, eventId)) {
            throw refused(DeadLetterErrorCode.DISCARD_STANDING);
        }
        // Judged whether or not it was ever replayed: an earlier attempt whose acknowledgement was lost reached the broker and left no
        // mark, so nothing can prove a consumer never applied this event. Fail-closed at the window (THREAT_MODEL.md section 6, item 17).
        if (!row.withinDedupeWindow() && !everyConsumerNaturallyIdempotent.test(row.eventType())) {
            throw refused(DeadLetterErrorCode.RETRY_OUTSIDE_DEDUPE_WINDOW);
        }
        store.extendDedupe(uow, List.of(eventId), retention);
        int withdrawn = settleReplaysBehind(uow, eventId);
        store.returnToRelay(uow, eventId);
        record(uow, PlatformAuditAction.OUTBOX_EVENT_RETRY_AUTHORISED, TARGET_EVENT, eventId.toString(), Optional.of(given), actor, correlation,
                "type=" + row.eventType() + ", replaysWithdrawn=" + withdrawn, AuditOutcome.SUCCEEDED);
    }

    /**
     * What this act sets moving: the aggregate's other pending replayed rows, which publish when the row blocking them goes, however
     * long after they were ordered. Each answers to the replay's rule again. Inside the window its dedupe rows are extended; past it,
     * it is withdrawn rather than refused, because refusing would leave the aggregate with no door at all: such a row is not abandoned,
     * so it cannot be discarded, and it would hold the retry or the discard of the row in front of it for ever.
     *
     * @return how many replays were withdrawn, for the act's record
     */
    private int settleReplaysBehind(Connection uow, UUID eventId) {
        List<DeadLetterStore.PendingReplay> pending = store.lockPendingReplays(uow, eventId, retention);
        List<UUID> keep = new ArrayList<>();
        List<UUID> withdraw = new ArrayList<>();
        for (DeadLetterStore.PendingReplay replay : pending) {
            (replay.withinDedupeWindow() || everyConsumerNaturallyIdempotent.test(replay.eventType()) ? keep : withdraw).add(replay.eventId());
        }
        store.extendDedupe(uow, keep, retention);
        store.withdrawReplays(uow, withdraw);
        return withdraw.size();
    }

    /**
     * Returns a parked record to its consumer and republishes its event, the single-record replay. Refused for what a replay would
     * be refused for; and for a record with no outbox event, whose only way on is a discard.
     */
    public void release(Connection uow, UUID parkId, Actor actor, String reason, CorrelationId correlation) {
        String given = required(reason);
        DeadLetterStore.ParkedRow row = store.lockParked(uow, parkId).orElseThrow(DeadLetterOperations::notFound);
        if (!"PARKED".equals(row.state())) {
            throw refused(DeadLetterErrorCode.NOT_PARKED);
        }
        if (store.hasStandingDiscard(uow, DeadLetterStore.SubjectKind.INBOX_RECORD, parkId)) {
            throw refused(DeadLetterErrorCode.DISCARD_STANDING);
        }
        UUID eventId = eventIdOf(row.dedupeKey()).orElseThrow(() -> refused(DeadLetterErrorCode.NOT_RELEASABLE));
        DeadLetterStore.EventRow event = store.lockEvent(uow, eventId, retention)
                .filter(DeadLetterStore.EventRow::published)
                .orElseThrow(() -> refused(DeadLetterErrorCode.NOT_RELEASABLE));
        if (!event.withinDedupeWindow() && !everyConsumerNaturallyIdempotent.test(event.eventType())) {
            throw refused(DeadLetterErrorCode.REPLAY_OUTSIDE_DEDUPE_WINDOW);
        }
        store.extendDedupe(uow, List.of(eventId), retention);
        store.requeue(uow, List.of(eventId));
        store.releaseParked(uow, parkId);
        record(uow, PlatformAuditAction.INBOX_PARKED_RECORD_RELEASED, TARGET_PARKED, parkId.toString(), Optional.of(given), actor, correlation,
                "consumer=" + row.consumer() + ", event=" + eventId + ", withinWindow=" + event.withinDedupeWindow(), AuditOutcome.SUCCEEDED);
    }

    /** One person's half of a discard: nothing changes until a different person approves. */
    public UUID proposeDiscard(Connection uow, DeadLetterStore.SubjectKind kind, UUID subjectId, Actor actor, String reason, CorrelationId correlation) {
        String given = required(reason);
        requireDiscardable(uow, kind, subjectId, DeadLetterErrorCode.NOT_ABANDONED, DeadLetterErrorCode.NOT_PARKED);
        if (store.hasStandingDiscard(uow, kind, subjectId)) {
            throw refused(DeadLetterErrorCode.DISCARD_STANDING);
        }
        UUID discardId = ids.next();
        store.insertProposal(uow, discardId, kind, subjectId, actor.id(), given);
        record(uow, PlatformAuditAction.DEAD_LETTER_DISCARD_PROPOSED, TARGET_DISCARD, discardId.toString(), Optional.of(given), actor, correlation,
                "kind=" + kind + ", subject=" + subjectId, AuditOutcome.SUCCEEDED);
        return discardId;
    }

    /**
     * The second person: never the proposer. Compared by the person's identifier alone, because the same person can act as a customer
     * and as an operator, and an actor's type is decided per request.
     */
    public void approveDiscard(Connection uow, UUID discardId, Actor actor, String reason, CorrelationId correlation) {
        String given = required(reason);
        DeadLetterStore.Proposal proposal = standing(uow, discardId);
        if (actor.id().equals(proposal.proposedBy())) {
            throw refused(DeadLetterErrorCode.SELF_APPROVAL_REFUSED);
        }
        requireDiscardable(uow, proposal.kind(), proposal.subjectId(), DeadLetterErrorCode.DISCARD_NOT_STANDING, DeadLetterErrorCode.DISCARD_NOT_STANDING);
        AuditableAction action;
        String target;
        int withdrawn = 0;
        if (proposal.kind() == DeadLetterStore.SubjectKind.OUTBOX_EVENT) {
            store.discardEvent(uow, proposal.subjectId());
            withdrawn = settleReplaysBehind(uow, proposal.subjectId());
            action = PlatformAuditAction.OUTBOX_EVENT_DISCARDED;
            target = TARGET_EVENT;
        } else {
            store.discardParked(uow, proposal.subjectId());
            action = PlatformAuditAction.INBOX_PARKED_RECORD_DISCARDED;
            target = TARGET_PARKED;
        }
        store.decide(uow, discardId, "APPROVED", actor.id(), given);
        record(uow, action, target, proposal.subjectId().toString(), Optional.of(given), actor, correlation,
                "discard=" + discardId + ", replaysWithdrawn=" + withdrawn, AuditOutcome.SUCCEEDED);
    }

    /** A proposal turned down, by anyone, its proposer included: nothing is discarded. */
    public void rejectDiscard(Connection uow, UUID discardId, Actor actor, String reason, CorrelationId correlation) {
        String given = required(reason);
        DeadLetterStore.Proposal proposal = standing(uow, discardId);
        store.decide(uow, discardId, "REJECTED", actor.id(), given);
        record(uow, PlatformAuditAction.DEAD_LETTER_DISCARD_REJECTED, TARGET_DISCARD, discardId.toString(), Optional.of(given), actor, correlation,
                "kind=" + proposal.kind() + ", subject=" + proposal.subjectId(), AuditOutcome.SUCCEEDED);
    }

    /** What a replay did. */
    public record Replay(String eventType, Instant from, Instant to, int requeued) {}

    /**
     * Requeues every published event of one type over a time window, all or nothing. A handler added in a release is served by this
     * over its rollout and rollback windows; a group stopped past topic retention is served by it too.
     */
    public Replay replay(Connection uow, String eventType, Instant from, Instant to, Actor actor, String reason, CorrelationId correlation) {
        String given = required(reason);
        if (eventType == null || eventType.isBlank() || eventType.length() > MAX_EVENT_TYPE_LENGTH || from == null || to == null || !from.isBefore(to)) {
            throw refused(DeadLetterErrorCode.REPLAY_INVALID);
        }
        List<DeadLetterStore.ReplayCandidate> selected = store.lockForReplay(uow, eventType, from, to, retention, MAX_REPLAY_EVENTS + 1);
        if (selected.size() > MAX_REPLAY_EVENTS) {
            throw refused(DeadLetterErrorCode.REPLAY_TOO_LARGE);
        }
        boolean withinWindow = selected.stream().allMatch(DeadLetterStore.ReplayCandidate::withinDedupeWindow);
        if (!withinWindow && !everyConsumerNaturallyIdempotent.test(eventType)) {
            throw refused(DeadLetterErrorCode.REPLAY_OUTSIDE_DEDUPE_WINDOW);
        }
        List<UUID> requeued = selected.stream().map(DeadLetterStore.ReplayCandidate::eventId).toList();
        store.extendDedupe(uow, requeued, retention);
        store.requeue(uow, requeued);
        record(uow, PlatformAuditAction.OUTBOX_EVENT_REPLAY_ORDERED, TARGET_EVENT_TYPE, eventType, Optional.of(given), actor, correlation,
                "from=" + from + ", to=" + to + ", requeued=" + requeued.size() + ", withinWindow=" + withinWindow, AuditOutcome.SUCCEEDED);
        return new Replay(eventType, from, to, requeued.size());
    }

    /**
     * Records a refused act, in a transaction of its own after the act's rolled back: a refusal leaves nothing else behind, and "did
     * anyone try" must still be answerable.
     */
    public void recordRefusal(
            Connection uow, String act, String target, String code, Optional<String> detail, Optional<String> reason, Actor actor, CorrelationId correlation) {
        record(uow, PlatformAuditAction.DEAD_LETTER_ACT_REFUSED, "deadletter.act", target,
                reason.filter(given -> !given.isBlank()).map(DeadLetterOperations::bounded), actor, correlation,
                "act=" + act + ", refusal=" + code + detail.map(what -> ", " + what).orElse(""), AuditOutcome.DENIED);
    }

    private DeadLetterStore.Proposal standing(Connection uow, UUID discardId) {
        DeadLetterStore.Proposal proposal = store.lockProposal(uow, discardId).orElseThrow(DeadLetterOperations::notFound);
        if (!"PROPOSED".equals(proposal.status())) {
            throw refused(DeadLetterErrorCode.DISCARD_NOT_STANDING);
        }
        return proposal;
    }

    private void requireDiscardable(
            Connection uow, DeadLetterStore.SubjectKind kind, UUID subjectId, DeadLetterErrorCode notAbandoned, DeadLetterErrorCode notParked) {
        if (kind == DeadLetterStore.SubjectKind.OUTBOX_EVENT) {
            DeadLetterStore.EventRow row = store.lockEvent(uow, subjectId, retention).orElseThrow(DeadLetterOperations::notFound);
            if (!row.abandoned() || row.discarded()) {
                throw refused(notAbandoned);
            }
        } else {
            DeadLetterStore.ParkedRow row = store.lockParked(uow, subjectId).orElseThrow(DeadLetterOperations::notFound);
            if (!"PARKED".equals(row.state())) {
                throw refused(notParked);
            }
        }
    }

    /** A platform event's dedupe key is its {@code event_id}; a malformed record's or a webhook's is not. */
    private static Optional<UUID> eventIdOf(String dedupeKey) {
        try {
            return Optional.of(UUID.fromString(dedupeKey));
        } catch (IllegalArgumentException notAnEvent) {
            return Optional.empty();
        }
    }

    private void record(
            Connection uow, AuditableAction action, String targetType, String targetId, Optional<String> reason,
            Actor actor, CorrelationId correlation, String summary, AuditOutcome outcome) {
        audit.append(uow, new AuditRecord(
                AuditId.next(ids), actor, clock.instant(), action, targetType, targetId, reason, outcome, correlation, Optional.of(summary)));
    }

    private static String required(String reason) {
        if (reason == null || reason.isBlank()) {
            throw refused(DeadLetterErrorCode.REASON_REQUIRED);
        }
        return bounded(reason);
    }

    private static String bounded(String reason) {
        String stripped = reason.strip();
        return stripped.length() <= AuditRecord.MAX_REASON_LENGTH ? stripped : stripped.substring(0, AuditRecord.MAX_REASON_LENGTH);
    }

    private static ApiException refused(DeadLetterErrorCode code) {
        return new ApiException(code, "A dead-letter act was refused: " + code.code());
    }

    private static ApiException notFound() {
        return refused(DeadLetterErrorCode.NOT_FOUND);
    }
}
