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

    /** The bounded list of a read. */
    public static final int MAX_LISTED = 100;

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

    /** What the abandoned and parked queues hold, oldest first; the read is recorded in this transaction. */
    public DeadLetters read(Connection uow, Actor actor, CorrelationId correlation) {
        List<DeadLetterStore.AbandonedEvent> abandoned = store.abandoned(uow, MAX_LISTED);
        List<DeadLetterStore.ParkedRecord> parked = store.parked(uow, MAX_LISTED);
        List<DeadLetterStore.Proposal> proposals = store.standingProposals(uow, MAX_LISTED);
        record(uow, PlatformAuditAction.DEAD_LETTERS_READ, "deadletter.queues", "all", Optional.empty(), actor, correlation,
                "abandoned=" + abandoned.size() + ", parked=" + parked.size() + ", standing=" + proposals.size(), AuditOutcome.SUCCEEDED);
        return new DeadLetters(abandoned, parked, proposals);
    }

    /** The two queues and the decisions waiting on them. */
    public record DeadLetters(
            List<DeadLetterStore.AbandonedEvent> abandoned,
            List<DeadLetterStore.ParkedRecord> parked,
            List<DeadLetterStore.Proposal> proposals) {}

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
        if (row.replayed()) {
            // A replay that was abandoned on its way out: it publishes now, not when it was ordered, so it answers to the replay's
            // rule again. An event that never reached the broker has nothing for a consumer to have deduplicated.
            if (!row.withinDedupeWindow() && !everyConsumerNaturallyIdempotent.test(row.eventType())) {
                throw refused(DeadLetterErrorCode.REPLAY_OUTSIDE_DEDUPE_WINDOW);
            }
            store.extendDedupe(uow, List.of(eventId), retention);
        }
        store.returnToRelay(uow, eventId);
        record(uow, PlatformAuditAction.OUTBOX_EVENT_RETRY_AUTHORISED, TARGET_EVENT, eventId.toString(), Optional.of(given), actor, correlation,
                "type=" + row.eventType(), AuditOutcome.SUCCEEDED);
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
        if (proposal.kind() == DeadLetterStore.SubjectKind.OUTBOX_EVENT) {
            store.discardEvent(uow, proposal.subjectId());
            action = PlatformAuditAction.OUTBOX_EVENT_DISCARDED;
            target = TARGET_EVENT;
        } else {
            store.discardParked(uow, proposal.subjectId());
            action = PlatformAuditAction.INBOX_PARKED_RECORD_DISCARDED;
            target = TARGET_PARKED;
        }
        store.decide(uow, discardId, "APPROVED", actor.id(), given);
        record(uow, action, target, proposal.subjectId().toString(), Optional.of(given), actor, correlation, "discard=" + discardId, AuditOutcome.SUCCEEDED);
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
    public void recordRefusal(Connection uow, String act, String target, String code, Optional<String> reason, Actor actor, CorrelationId correlation) {
        record(uow, PlatformAuditAction.DEAD_LETTER_ACT_REFUSED, "deadletter.act", target,
                reason.filter(given -> !given.isBlank()).map(DeadLetterOperations::bounded), actor, correlation, "act=" + act + ", refusal=" + code, AuditOutcome.DENIED);
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
