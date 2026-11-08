package com.finapp.platform.deadletter;

import com.finapp.platform.api.ErrorCode;
import lombok.RequiredArgsConstructor;

/** The refusals of the dead-letter doors (`P15-TSK-028`). */
@RequiredArgsConstructor
public enum DeadLetterErrorCode implements ErrorCode {

    /** No abandoned event, parked record or discard answers the identifier. */
    NOT_FOUND("deadletter.NotFound", 404, "No dead letter matches the requested identifier."),

    /** A reason is required on every act. */
    REASON_REQUIRED("deadletter.ReasonRequired", 422, "A reason is required."),

    /** The event is not abandoned, or has already been discarded. */
    NOT_ABANDONED("deadletter.NotAbandoned", 409, "The event is not abandoned."),

    /** The record is not parked. */
    NOT_PARKED("deadletter.NotParked", 409, "The record is not parked."),

    /** A proposal stands: decide it before acting on the subject. */
    DISCARD_STANDING("deadletter.DiscardStanding", 409, "A proposed discard already stands."),

    /** The proposal has been decided, or its subject has moved on. */
    DISCARD_NOT_STANDING("deadletter.DiscardNotStanding", 409, "The proposed discard no longer stands."),

    /** One's own proposal; the discard needs a second person. */
    SELF_APPROVAL_REFUSED("deadletter.SelfApprovalRefused", 403, "A discard needs a second person."),

    /** A selected event is past the inbox's dedupe window and not every consumer of its type is naturally idempotent. */
    REPLAY_OUTSIDE_DEDUPE_WINDOW("deadletter.ReplayOutsideDedupeWindow", 409,
            "An event in this replay is older than the inbox's dedupe window."),

    /**
     * The abandoned event is at or past the inbox's dedupe window. An earlier attempt whose acknowledgement was lost may have reached
     * the broker, so a consumer may have applied it and lost its record of having done so.
     */
    RETRY_OUTSIDE_DEDUPE_WINDOW("deadletter.RetryOutsideDedupeWindow", 409,
            "The abandoned event is older than the inbox's dedupe window."),

    /** More events match than one command may requeue. */
    REPLAY_TOO_LARGE("deadletter.ReplayTooLarge", 422, "The replay selects too many events; narrow its window."),

    /** The replay's type or time window is not usable. */
    REPLAY_INVALID("deadletter.ReplayInvalid", 422, "The replay's event type or time window is not valid."),

    /** The record cannot be re-driven: it never had an outbox event, or that event is no longer published there. */
    NOT_RELEASABLE("deadletter.NotReleasable", 409, "The parked record cannot be republished from the outbox.");

    private final String code;
    private final int status;
    private final String title;

    @Override
    public String code() {
        return code;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
