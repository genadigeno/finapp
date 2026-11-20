package com.finapp.app.eventing;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.api.PendingDecisions;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dead-letter doors (`P15-TSK-028`; {@code EVENT_ARCHITECTURE.md} §Handling an abandoned event; {@code OPERATIONS_RUNBOOK.md} §11.6):
 * an abandoned event and a parked record under {@code EVENT_OPERATE}, every act on a {@code MULTI_FACTOR} session, keyed and reasoned.
 * A discard is four-eyes: {@link DeadLetterDesk#approveDiscard} refuses one's own proposal. Operation names are distinctive (the springdoc
 * {@code operationId} rule). Reasons are never logged.
 */
@RestController
@RequestMapping(path = "/operator/events", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class DeadLetterController {

    @NonNull private final DeadLetterDesk desk;

    /**
     * The abandoned events, as a section 7 collection (`P17-TSK-042`; read "{@code GET /dead-letters}: the abandoned events, the
     * parked records and the discards waiting for a second person, at most 100 of each with nothing saying so" until then):
     * oldest abandonment first, the event's id breaking the tie; {@code limit} 50 unless named, 200 at most; the opaque
     * {@code cursor} of the page before. Every page audited. Never a payload.
     */
    @GetMapping("/abandoned")
    @RequiresPermission(PermissionName.EVENT_OPERATE)
    public DeadLetterDesk.AbandonedPage readAbandonedEvents(
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.abandoned(cursor, limit);
    }

    /** The parked records, as a section 7 collection (`P17-TSK-042`): oldest first, the park's id breaking the tie. Audited. */
    @GetMapping("/parked")
    @RequiresPermission(PermissionName.EVENT_OPERATE)
    public DeadLetterDesk.ParkedPage readParkedRecords(
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.parked(cursor, limit);
    }

    /**
     * The discards waiting for a second person, in the one pending-decision shape (`P17-TSK-040`, converted in place from
     * `P17-TSK-042`'s {@code DiscardPage}): {@code status} {@code PROPOSED} or unnamed, oldest proposal first, the discard's id
     * breaking the tie - each with its proposer and its reason's summary, which the second person reads. On a {@code MULTI_FACTOR}
     * session, as the approval door is. Audited.
     */
    @GetMapping("/discards")
    @RequiresPermission(PermissionName.EVENT_OPERATE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public PendingDecisions.PendingDecisionPage readStandingDiscards(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.discards(status, cursor, limit);
    }

    /** Clears an event's abandonment, so the relay takes it again. Refused while a discard stands. */
    @PostMapping(path = "/abandoned/{eventId}/retry", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.EVENT_OPERATE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    public DeadLetterDesk.ActReceipt retryAbandonedEvent(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("eventId") String eventId,
            @Valid @RequestBody ReasonRequest body) {
        return desk.retry(idempotencyKey, eventId, body.reason());
    }

    /** Returns a parked record to its consumer and republishes its event. Refused beyond the inbox's dedupe window, as a replay is. */
    @PostMapping(path = "/parked/{parkId}/release", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.EVENT_OPERATE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    public DeadLetterDesk.ActReceipt releaseParkedRecord(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("parkId") String parkId,
            @Valid @RequestBody ReasonRequest body) {
        return desk.release(idempotencyKey, parkId, body.reason());
    }

    /** Proposes that an abandoned event or a parked record will never be delivered. Nothing changes until a different person approves. */
    @PostMapping(path = "/discards", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.EVENT_OPERATE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    public DeadLetterDesk.ActReceipt proposeDeadLetterDiscard(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody DiscardRequest body) {
        return desk.proposeDiscard(idempotencyKey, body.subjectKind(), body.subjectId(), body.reason());
    }

    /** A different person accepts the gap. The row stays; a discard is a recorded decision, never a deletion. */
    @PostMapping(path = "/discards/{discardId}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.EVENT_OPERATE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    public DeadLetterDesk.ActReceipt approveDeadLetterDiscard(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("discardId") String discardId,
            @Valid @RequestBody ReasonRequest body) {
        return desk.approveDiscard(idempotencyKey, discardId, body.reason());
    }

    /** Turns a proposal down, the proposer's own included. Nothing is discarded. */
    @PostMapping(path = "/discards/{discardId}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.EVENT_OPERATE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    public DeadLetterDesk.ActReceipt rejectDeadLetterDiscard(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("discardId") String discardId,
            @Valid @RequestBody ReasonRequest body) {
        return desk.rejectDiscard(idempotencyKey, discardId, body.reason());
    }

    /** Republishes the published events of one type over a time window, all or nothing; refused whole when one is past the dedupe window. */
    @PostMapping(path = "/replays", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.EVENT_OPERATE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    public DeadLetterDesk.ReplayReceipt orderEventReplay(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody ReplayRequest body) {
        return desk.replay(idempotencyKey, body.eventType(), body.from(), body.to(), body.reason());
    }

    // -----------------------------------------------------------------

    /** Why the act is taken. */
    @ClosedBody
    public record ReasonRequest(@Size(max = 1000) String reason) {

        /** The reason stays out of a log line. */
        @Override
        public String toString() {
            return "ReasonRequest[]";
        }
    }

    /** A discard's subject and why. */
    @ClosedBody
    public record DiscardRequest(@Size(max = 32) String subjectKind, @Size(max = 64) String subjectId, @Size(max = 1000) String reason) {

        /** The reason stays out of a log line. */
        @Override
        public String toString() {
            return "DiscardRequest[" + subjectKind + "]";
        }
    }

    /** One event type, a window of first publication {@code [from, to)} as ISO-8601 instants, and why. */
    @ClosedBody
    public record ReplayRequest(@Size(max = 200) String eventType, @Size(max = 40) String from, @Size(max = 40) String to, @Size(max = 1000) String reason) {

        /** The reason stays out of a log line. */
        @Override
        public String toString() {
            return "ReplayRequest[" + eventType + "]";
        }
    }
}
