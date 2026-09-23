package com.finapp.platform.audit;

import lombok.RequiredArgsConstructor;

/**
 * The platform's own auditable actions.
 *
 * <p>Deliberately short. A registry populated to look substantial is worse than a small one: the
 * Phase 15 gate verifies that everything declared here is actually audited, so every entry is a
 * commitment. The platform has almost no privileged actions of its own — it holds correctness
 * primitives, not business capability — and the three below are the ones that genuinely decide
 * whether a fact reaches the systems that depend on it.
 *
 * <p><strong>These are declared but not yet emitted.</strong> Two of them describe the manual
 * procedure in {@code EVENT_ARCHITECTURE.md} §Handling an abandoned event, which is performed
 * today with raw SQL and no audit record at all; the third is a relay decision currently visible
 * only as a log line, and ADR-0010 is explicit that logs are not an audit trail. Declaring them
 * is the point of a completeness registry — it is the list Phase 15 checks reality against, so
 * an action that must be audited belongs here whether or not the code emitting it exists yet.
 * The gap is recorded in {@code CURRENT_STATE.md}.
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
