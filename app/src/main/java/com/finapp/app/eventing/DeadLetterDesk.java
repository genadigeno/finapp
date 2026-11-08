package com.finapp.app.eventing;

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
 * included. What leaves a read: identifiers, types, instants and a failure's class, and a proposal's reason, which the second person
 * has to read. Never a payload, a failure's message, a person, or the reasons of decided proposals.
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

    /** One discard waiting for a second person. The reason is served to whoever decides it, and never logged. */
    public record DiscardView(String discardId, String subjectKind, String subjectId, String proposedAt, String reason) {

        @Override
        public String toString() {
            return "DiscardView[" + discardId + ", " + subjectKind + "]";
        }
    }

    /** The queues and the decisions waiting on them, oldest first. */
    public record DeadLettersView(List<AbandonedView> abandoned, List<ParkedView> parked, List<DiscardView> standingDiscards) {}

    /** An act's receipt. */
    public record ActReceipt(String subject, String status) {}

    /** A replay's receipt: what it requeued. Requeued, not yet delivered. */
    public record ReplayReceipt(String eventType, String from, String to, int requeued) {}

    // ------------------------------------------------------------------ the read

    /** The queues. Recorded in the transaction of the read, so nothing is served unrecorded. */
    public DeadLettersView read() {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        DeadLetterOperations.DeadLetters read =
                spans.within("deadletter.read", Map.of(), () -> transactions.inTransaction(uow -> operations.read(uow, actor, correlation)));
        return new DeadLettersView(
                read.abandoned().stream()
                        .map(row -> new AbandonedView(row.eventId().toString(), row.aggregateId().toString(), row.eventType(), row.attempts(),
                                row.abandonedAt().toString(), row.failureClass()))
                        .toList(),
                read.parked().stream()
                        .map(row -> new ParkedView(row.parkId().toString(), row.consumer(), row.dedupeKey(), row.group(), row.messageType(), row.attempts(),
                                row.parkedAt().toString(), row.failureClass()))
                        .toList(),
                read.proposals().stream()
                        .map(row -> new DiscardView(row.discardId().toString(), row.kind().name(), row.subjectId().toString(), row.proposedAt().toString(), row.reason()))
                        .toList());
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
