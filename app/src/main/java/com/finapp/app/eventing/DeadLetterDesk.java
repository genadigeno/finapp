package com.finapp.app.eventing;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.OperatorCollection;
import com.finapp.app.api.PendingDecisions;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.deadletter.DeadLetterErrorCode;
import com.finapp.platform.deadletter.DeadLetterOperations;
import com.finapp.platform.deadletter.DeadLetterStore;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.telemetry.Spans;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary of the dead-letter doors (`P15-TSK-028`): each act keyed per person ({@code deadletter:<type>:<id>}), run in one
 * transaction under its span; a refusal recorded {@code DENIED} in a transaction of its own, after the act's rolled back, so a refused
 * act leaves a record and nothing else.
 *
 * <p><strong>A key's replay is the first answer</strong>: the same key and body return the stored receipt, a timeout then a retry
 * included. What leaves a read: identifiers, types, instants and a failure's class, and a standing proposal's proposer and reason,
 * cut to the pending-decision shape's summary, which the second person has to read (`P17-TSK-040`). Never a payload, a failure's
 * message, or the reasons of decided proposals.
 */
@RequiredArgsConstructor
public final class DeadLetterDesk {

    static final String SCOPE = "deadletter:";

    private static final String ACT_RETRY = "retry";
    private static final String ACT_RELEASE = "release";
    private static final String ACT_PROPOSE = "discard-proposal";
    private static final String ACT_APPROVE = "discard-approval";
    private static final String ACT_REJECT = "discard-rejection";
    private static final String ACT_REPLAY = "replay";

    /** What a refusal names when the identifier given was not one: never the caller's text. */
    private static final String UNREADABLE_TARGET = "unreadable-identifier";

    /** An event type as the outbox holds one: {@code module.EventName}, bounded by the request. */
    private static final Pattern READABLE_EVENT_TYPE = Pattern.compile("[A-Za-z0-9._-]{1,200}");

    @NonNull private final DeadLetterOperations operations;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final DeadLetterTransactions transactions;
    @NonNull private final Spans spans;

    // ------------------------------------------------------------------ views

    /** One abandoned event: never the payload, and the failure's class rather than its message. */
    public record AbandonedView(String eventId, String aggregateId, String eventType, int attempts, String abandonedAt, String failureClass) {}

    /** One parked record. */
    public record ParkedView(String parkId, String consumer, String dedupeKey, String consumerGroup, String messageType, int attempts, String parkedAt, String failureClass) {}

    /** A page of the abandoned events (`P17-TSK-042`): {@code nextCursor} absent, not null, on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AbandonedPage(List<AbandonedView> items, String nextCursor) {}

    /** A page of the parked records (`P17-TSK-042`): {@code nextCursor} absent, not null, on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ParkedPage(List<ParkedView> items, String nextCursor) {}

    /** The three queues' collection names, inside their cursors (`P17-TSK-042`). */
    static final String ABANDONED = "deadletter.abandoned";
    static final String PARKED = "deadletter.parked";
    /** The discards' queue is a pending-decision queue (`P17-TSK-040`), its cursors that shape's collection. */
    static final String DISCARDS = "pending.deadletter.discards";

    /** A discard's kind in the pending-decision shape. */
    static final String DISCARD_KIND = "deadletter.Discard";

    static final int DEFAULT_LIMIT = 50;

    /** An act's receipt. */
    public record ActReceipt(String subject, String status) {}

    /** A replay's receipt: what it requeued. Requeued, not yet delivered. */
    public record ReplayReceipt(String eventType, String from, String to, int requeued) {}

    // ------------------------------------------------------------------ the reads (P17-TSK-042: three section 7 collections)

    /**
     * A page of the abandoned events, oldest abandonment first, the event's id breaking the tie: {@code limit}
     * {@value #DEFAULT_LIMIT} unless named, {@value DeadLetterOperations#MAX_PAGE} at most and refused above; the opaque
     * {@code cursor} of the page before. Recorded in the transaction of the read, so nothing is served unrecorded.
     */
    public AbandonedPage abandoned(String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, DeadLetterOperations.MAX_PAGE);
        Optional<DeadLetterStore.Position> after = position(ABANDONED, cursor);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        DeadLetterOperations.Page<DeadLetterStore.AbandonedEvent> page = spans.within("deadletter.read", Map.of(),
                () -> transactions.inTransaction(uow -> operations.abandoned(uow, after, size, actor, correlation)));
        return new AbandonedPage(
                page.rows().stream()
                        .map(row -> new AbandonedView(row.eventId().toString(), row.aggregateId().toString(), row.eventType(), row.attempts(),
                                row.abandonedAt().toString(), row.failureClass()))
                        .toList(),
                next(ABANDONED, page, row -> new DeadLetterStore.Position(row.abandonedAt(), row.eventId())));
    }

    /** A page of the parked records, oldest first, the park's id breaking the tie; the abandoned page's rules. Recorded. */
    public ParkedPage parked(String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, DeadLetterOperations.MAX_PAGE);
        Optional<DeadLetterStore.Position> after = position(PARKED, cursor);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        DeadLetterOperations.Page<DeadLetterStore.ParkedRecord> page = spans.within("deadletter.read", Map.of(),
                () -> transactions.inTransaction(uow -> operations.parked(uow, after, size, actor, correlation)));
        return new ParkedPage(
                page.rows().stream()
                        .map(row -> new ParkedView(row.parkId().toString(), row.consumer(), row.dedupeKey(), row.group(), row.messageType(), row.attempts(),
                                row.parkedAt().toString(), row.failureClass()))
                        .toList(),
                next(PARKED, page, row -> new DeadLetterStore.Position(row.parkedAt(), row.parkId())));
    }

    /**
     * A page of the discards awaiting a second person in the one pending-decision shape (`P17-TSK-040`, converted in place; read
     * "a {@code DiscardPage} of {@code DiscardView}s - discard id, subject kind, subject id, proposed at and the whole reason" until
     * then): oldest proposal first, the discard's id breaking the tie; {@code status} {@code PROPOSED} or unnamed - the queue holds
     * nothing else; {@code limit} 50 unless named, 200 at most. Each a {@code deadletter.Discard}, its subject the abandoned event
     * or parked record it would discard, its reason summary the proposer's reason cut. Recorded, as before.
     */
    public PendingDecisions.PendingDecisionPage discards(String status, String cursor, String limit) {
        PendingDecisions.pendingOnly(status, "PROPOSED");
        int size = PendingDecisions.limit(limit);
        Optional<DeadLetterStore.Position> after = PendingDecisions.position(DISCARDS, cursor)
                .map(position -> new DeadLetterStore.Position(position.proposedAt(), position.id()));
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        DeadLetterOperations.Page<DeadLetterStore.Proposal> page = spans.within("deadletter.read", Map.of(),
                () -> transactions.inTransaction(uow -> operations.standingDiscards(uow, after, size, actor, correlation)));
        return new PendingDecisions.PendingDecisionPage(
                page.rows().stream()
                        .map(row -> PendingDecisions.of(row.discardId(), DISCARD_KIND, row.proposedBy(), row.proposedAt(), row.reason(),
                                row.subjectId()))
                        .toList(),
                next(DISCARDS, page, row -> new DeadLetterStore.Position(row.proposedAt(), row.discardId())));
    }

    /** The row {@code cursor} names as the last one {@code collection} served, or none for the first page; anything else refused. */
    static Optional<DeadLetterStore.Position> position(String collection, String cursor) {
        return OperatorCollection.key(collection, cursor, 2).map(key -> {
            try {
                return new DeadLetterStore.Position(Instant.parse(key.get(0)), UUID.fromString(key.get(1)));
            } catch (RuntimeException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    /** The cursor to the page after {@code page}, or none when nothing follows it. */
    private static <T> String next(String collection, DeadLetterOperations.Page<T> page, Function<T, DeadLetterStore.Position> keyOf) {
        if (!page.more() || page.rows().isEmpty()) {
            return null;
        }
        DeadLetterStore.Position tail = keyOf.apply(page.rows().get(page.rows().size() - 1));
        return OperatorCollection.cursor(collection, tail.at().toString(), tail.id().toString());
    }

    // ------------------------------------------------------------------ one record of a queue (P17-TSK-124)

    /**
     * One discard as the second person reads it (`P17-TSK-124`): the subject, the status it stands in now, who proposed it, when
     * and why. Never who decided. The reason is served to whoever decides it, and never logged.
     */
    public record DiscardRecordView(String discardId, String subjectKind, String subjectId, String status, String proposedBy,
            String proposedAt, String reason) {

        @Override
        public String toString() {
            return "DiscardRecordView[" + discardId + ", " + subjectKind + ", " + status + "]";
        }
    }

    /**
     * Abandoned event {@code rawEventId}, while it is in the queue - the row its page serves, so a record's page needs no search of
     * the queue. Recorded {@code deadletter.Read} in the read's transaction; unknown, malformed or no longer abandoned: 404, nothing
     * recorded.
     */
    public AbandonedView abandonedEvent(String rawEventId) {
        UUID id = identifier(rawEventId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        DeadLetterStore.AbandonedEvent row = spans.within("deadletter.read", Map.of(), () -> transactions.inTransaction(uow ->
                operations.abandonedEvent(uow, id, actor, correlation))).orElseThrow(DeadLetterDesk::noRecord);
        return new AbandonedView(row.eventId().toString(), row.aggregateId().toString(), row.eventType(), row.attempts(),
                row.abandonedAt().toString(), row.failureClass());
    }

    /** Parked record {@code rawParkId}, while it is parked; the abandoned read's rules. */
    public ParkedView parkedRecord(String rawParkId) {
        UUID id = identifier(rawParkId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        DeadLetterStore.ParkedRecord row = spans.within("deadletter.read", Map.of(), () -> transactions.inTransaction(uow ->
                operations.parkedRecord(uow, id, actor, correlation))).orElseThrow(DeadLetterDesk::noRecord);
        return new ParkedView(row.parkId().toString(), row.consumer(), row.dedupeKey(), row.group(), row.messageType(), row.attempts(),
                row.parkedAt().toString(), row.failureClass());
    }

    /** Discard {@code rawDiscardId}, in whatever status it stands; the abandoned read's rules. */
    public DiscardRecordView discard(String rawDiscardId) {
        UUID id = identifier(rawDiscardId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        DeadLetterStore.Proposal row = spans.within("deadletter.read", Map.of(), () -> transactions.inTransaction(uow ->
                operations.discard(uow, id, actor, correlation))).orElseThrow(DeadLetterDesk::noRecord);
        return new DiscardRecordView(row.discardId().toString(), row.kind().name(), row.subjectId().toString(), row.status(),
                row.proposedBy(), row.proposedAt().toString(), row.reason());
    }

    private static ApiException noRecord() {
        return new ApiException(DeadLetterErrorCode.NOT_FOUND, "No record of the queue matches the requested identifier");
    }

    // ------------------------------------------------------------------ the acts

    public ActReceipt retry(String idempotencyKey, String rawEventId, String reason) {
        return acted(ACT_RETRY, idempotencyKey, rawEventId, reason, id -> ACT_RETRY + "|" + id + "|" + reason, (uow, id, actor, correlation) -> {
            operations.retry(uow, id, actor, reason, correlation);
            return id + "|RETURNED_TO_RELAY";
        });
    }

    public ActReceipt release(String idempotencyKey, String rawParkId, String reason) {
        return acted(ACT_RELEASE, idempotencyKey, rawParkId, reason, id -> ACT_RELEASE + "|" + id + "|" + reason, (uow, id, actor, correlation) -> {
            operations.release(uow, id, actor, reason, correlation);
            return id + "|RELEASED";
        });
    }

    public ActReceipt proposeDiscard(String idempotencyKey, String rawKind, String rawSubject, String reason) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return spans.within("deadletter." + ACT_PROPOSE, Map.of(), () -> refusalRecorded(ACT_PROPOSE, rawSubject, Optional.empty(), reason, actor, correlation, () -> {
            DeadLetterStore.SubjectKind kind = kindOf(rawKind);
            UUID subject = identifier(rawSubject);
            return receipt(keyed(actor, idempotencyKey, ACT_PROPOSE + "|" + kind + "|" + subject + "|" + reason,
                    uow -> operations.proposeDiscard(uow, kind, subject, actor, reason, correlation) + "|PROPOSED"));
        }));
    }

    public ActReceipt approveDiscard(String idempotencyKey, String rawDiscardId, String reason) {
        return acted(ACT_APPROVE, idempotencyKey, rawDiscardId, reason, id -> ACT_APPROVE + "|" + id + "|" + reason, (uow, id, actor, correlation) -> {
            operations.approveDiscard(uow, id, actor, reason, correlation);
            return id + "|APPROVED";
        });
    }

    public ActReceipt rejectDiscard(String idempotencyKey, String rawDiscardId, String reason) {
        return acted(ACT_REJECT, idempotencyKey, rawDiscardId, reason, id -> ACT_REJECT + "|" + id + "|" + reason, (uow, id, actor, correlation) -> {
            operations.rejectDiscard(uow, id, actor, reason, correlation);
            return id + "|REJECTED";
        });
    }

    public ReplayReceipt replay(String idempotencyKey, String eventType, String rawFrom, String rawTo, String reason) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        Optional<String> detail = Optional.of(replayDetail(eventType, rawFrom, rawTo));
        return spans.within("deadletter." + ACT_REPLAY, Map.of(), () -> refusalRecorded(ACT_REPLAY, null, detail, reason, actor, correlation, () -> {
            Instant from = instant(rawFrom);
            Instant to = instant(rawTo);
            String stored = keyed(actor, idempotencyKey, ACT_REPLAY + "|" + eventType + "|" + from + "|" + to + "|" + reason, uow -> {
                DeadLetterOperations.Replay replay = operations.replay(uow, eventType, from, to, actor, reason, correlation);
                return String.join("|", replay.eventType(), replay.from().toString(), replay.to().toString(), Integer.toString(replay.requeued()));
            });
            String[] fields = stored.split("\\|", -1);
            return new ReplayReceipt(fields[0], fields[1], fields[2], Integer.parseInt(fields[3]));
        }));
    }

    // ------------------------------------------------------------------ plumbing

    @FunctionalInterface
    private interface Act {
        String on(Connection unitOfWork, UUID id, Actor actor, CorrelationId correlation);
    }

    private ActReceipt acted(String act, String idempotencyKey, String rawId, String reason, Function<UUID, String> fingerprinted, Act work) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return spans.within("deadletter." + act, Map.of(), () -> refusalRecorded(act, rawId, Optional.empty(), reason, actor, correlation, () -> {
            UUID id = identifier(rawId);
            return receipt(keyed(actor, idempotencyKey, fingerprinted.apply(id), uow -> work.on(uow, id, actor, correlation)));
        }));
    }

    /** One keyed act in one transaction: a lost response replays the stored receipt, and a refusal stores nothing. */
    private String keyed(Actor actor, String idempotencyKey, String fingerprinted, Function<Connection, String> act) {
        // keyedType(), never type(): the same person's key must not change with how a request typed them (Actor#keyedType).
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow ->
                CommandResult.succeeded(StoredResponse.of(act.apply(uow).getBytes(StandardCharsets.UTF_8), "text/plain"))));
        return new String(outcome.body().orElseThrow(() -> new IllegalStateException("a dead-letter act stores its receipt")), StandardCharsets.UTF_8);
    }

    private <R> R refusalRecorded(String act, String rawTarget, Optional<String> detail, String reason, Actor actor, CorrelationId correlation, Supplier<R> work) {
        try {
            return work.get();
        } catch (ApiException refused) {
            if (refused.errorCode() instanceof DeadLetterErrorCode code) {
                transactions.inTransaction(uow -> {
                    operations.recordRefusal(uow, act, targetOf(rawTarget), code.code(), detail, Optional.ofNullable(reason), actor, correlation);
                    return null;
                });
            }
            throw refused;
        }
    }

    /** What a refused replay asked for: its type and window, each only if it is what it claims to be, so no caller's text is written. */
    private static String replayDetail(String eventType, String rawFrom, String rawTo) {
        boolean readableType = eventType != null && READABLE_EVENT_TYPE.matcher(eventType).matches();
        return "type=" + (readableType ? eventType : UNREADABLE_TARGET) + ", from=" + readableInstant(rawFrom) + ", to=" + readableInstant(rawTo);
    }

    private static String readableInstant(String raw) {
        try {
            return Instant.parse(raw).toString();
        } catch (DateTimeParseException | NullPointerException unreadable) {
            return UNREADABLE_TARGET;
        }
    }

    /** A UUID's text, or a name saying it was not one: a refused request's own text goes nowhere. */
    private static String targetOf(String rawTarget) {
        if (rawTarget == null) {
            return "replay";
        }
        try {
            return UUID.fromString(rawTarget).toString();
        } catch (IllegalArgumentException notAnIdentifier) {
            return UNREADABLE_TARGET;
        }
    }

    private static ActReceipt receipt(String stored) {
        String[] fields = stored.split("\\|", -1);
        return new ActReceipt(fields[0], fields[1]);
    }

    private static UUID identifier(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException notAnIdentifier) {
            throw new ApiException(DeadLetterErrorCode.NOT_FOUND, "A dead-letter door was given something that is not an identifier");
        }
    }

    private static DeadLetterStore.SubjectKind kindOf(String raw) {
        try {
            return DeadLetterStore.SubjectKind.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new ApiException(DeadLetterErrorCode.NOT_FOUND, "A discard names a kind of subject that does not exist");
        }
    }

    private static Instant instant(String raw) {
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException | NullPointerException unreadable) {
            throw new ApiException(DeadLetterErrorCode.REPLAY_INVALID, "A replay's window is not two instants");
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .map(Correlation::correlationId)
                .orElseThrow(() -> new IllegalStateException("every request has a correlation in scope"));
    }
}
