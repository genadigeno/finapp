package com.finapp.platform.audit;

import lombok.RequiredArgsConstructor;

/**
 * The platform's own auditable actions.
 *
 * <p>Deliberately short. A registry populated to look substantial is worse than a small one: the
 * Phase 15 gate verifies that everything declared here is actually audited, so every entry is a
 * commitment. The platform has almost no privileged actions of its own — it holds correctness
 * primitives, not business capability — and those below are the ones that genuinely decide
 * whether a fact reaches the systems that depend on it.
 *
 * <p><strong>Emitted since `P15-TSK-028`.</strong> For most of the platform's life the three outbox
 * actions were declared and not emitted: an abandonment was a log line and its two resolutions were
 * manual {@code UPDATE}s with no record at all. The relay now records the first in the transaction
 * that abandons the row, and the operator doors of {@code EVENT_ARCHITECTURE.md} §Handling an
 * abandoned event record the others in the transaction of the act. The dead-letter doors add the
 * rest of this list.
 */
@RequiredArgsConstructor
public enum PlatformAuditAction implements AuditableAction {

    /**
     * The relay stopped trying to publish an event, and its aggregate is now blocked.
     *
     * <p>Consequential rather than routine: consumers will never receive a fact that happened,
     * and every later event for that aggregate waits behind it. No reason is required because no
     * human chose it — the attempt limit did, and the failure itself is recorded on the row.
     */
    OUTBOX_EVENT_ABANDONED(
            "outbox.EventAbandoned",
            "The relay exhausted its attempts for an event and stopped retrying, blocking its "
                    + "aggregate until an operator resolves it.",
            false),

    /**
     * An operator cleared an abandonment so the relay would try again.
     *
     * <p>A reason is required. This is a person deciding that an event which repeatedly failed
     * should be published after all, and the justification is the only evidence the decision was
     * considered rather than reflexive.
     */
    OUTBOX_EVENT_RETRY_AUTHORISED(
            "outbox.EventRetryAuthorised",
            "An operator cleared an event's abandonment and returned it to the relay's queue.",
            true),

    /**
     * An operator decided an event will never be published.
     *
     * <p>A reason is required, and this is the most consequential action the platform currently
     * has: it accepts that consumers will permanently never see a fact that occurred. The row is
     * kept rather than deleted — it is the evidence the gap exists — and this record is the
     * evidence of who accepted it.
     */
    OUTBOX_EVENT_DISCARDED(
            "outbox.EventDiscarded",
            "An operator accepted that an event will never be published, leaving a permanent gap "
                    + "in what consumers received.",
            true),

    /**
     * An operator ordered published events republished, over one event type and a time window.
     *
     * <p>A reason is required: the operator is deciding that consumers should see these again. The
     * inbox refuses what a consumer has already handled; the order is the record of who asked for it.
     */
    OUTBOX_EVENT_REPLAY_ORDERED(
            "outbox.EventReplayOrdered",
            "An operator ordered published events of one type republished over a time window; consumers' "
                    + "dedupe records absorb what they have already handled.",
            true),

    /**
     * An operator read the abandoned events and parked records, ids and failure classes only.
     *
     * <p>No reason: reading the queue is how the job begins.
     */
    DEAD_LETTERS_READ(
            "deadletter.Read",
            "An operator read the abandoned events and parked records awaiting a decision: identifiers, "
                    + "types and failure classes, never a payload.",
            false),

    /** One person proposed that an abandoned event or a parked record will never be delivered. */
    DEAD_LETTER_DISCARD_PROPOSED(
            "deadletter.DiscardProposed",
            "An operator proposed discarding an abandoned event or a parked record; nothing changes until "
                    + "a different person approves.",
            true),

    /** A proposed discard was turned down, by anyone including its proposer; nothing was discarded. */
    DEAD_LETTER_DISCARD_REJECTED(
            "deadletter.DiscardRejected",
            "A proposed discard of an abandoned event or a parked record was rejected; nothing was "
                    + "discarded.",
            true),

    /**
     * A request at a door declaring {@code RecordsRefusals} was refused or failed (`P15-TSK-039`; {@code INV-AUD-03}).
     *
     * <p>Its outcome says which: {@code DENIED} for a control refusing it, {@code FAILED} for what was attempted and did
     * not complete, a lookup that found nothing among them. Its target is the door, by route pattern. Its summary is the
     * answered code and status and the path's identifiers, never a body, a reason or anything else the caller sent. No
     * reason is demanded: "did anyone try" must be answerable for a request that gave none.
     */
    DOOR_ACT_REFUSED(
            "door.ActRefused",
            "A privileged request at an operator's or a merchant's door was refused or failed; the outcome says which, the"
                    + " summary the code it was answered.",
            false),

    /**
     * A dead-letter door refused an act: not abandoned, past the dedupe window, one's own proposal.
     *
     * <p>Its own action, with no reason demanded, because a refusal may be of a request that gave
     * none, and "did anyone try" must be answerable for those too ({@code AuditOutcome}).
     */
    DEAD_LETTER_ACT_REFUSED(
            "deadletter.ActRefused",
            "A dead-letter door refused an act; the summary names the act and the refusal's code.",
            false),

    /**
     * A consumer's record used up its bounded retries for a reason of its own and was set aside.
     *
     * <p>No reason: no human chose it, the budget did, and the failure's class is on the row.
     */
    INBOX_RECORD_PARKED(
            "inbox.RecordParked",
            "A consumer's record failed its bounded retries for a reason of its own and was parked, so "
                    + "its consumer group continued.",
            false),

    /** An operator returned a parked record to its consumer, its event republished from the outbox. */
    INBOX_PARKED_RECORD_RELEASED(
            "inbox.ParkedRecordReleased",
            "An operator released a parked record: its failure count cleared and its event republished "
                    + "from the outbox.",
            true),

    /** Two people accepted that a consumer will never apply a record; it stays, as the evidence. */
    INBOX_PARKED_RECORD_DISCARDED(
            "inbox.ParkedRecordDiscarded",
            "A second operator approved discarding a parked record: the consumer will never apply it, and "
                    + "the row is kept.",
            true);

    private final String code;
    private final String description;
    private final boolean requiresReason;

    @Override
    public String code() {
        return code;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean requiresReason() {
        return requiresReason;
    }
}
