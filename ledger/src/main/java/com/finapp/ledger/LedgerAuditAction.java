package com.finapp.ledger;

import com.finapp.platform.audit.AuditableAction;

/**
 * What the {@code ledger} module does that must produce an audit record.
 *
 * <p>Declared per module rather than centrally: actions belong to the module that performs them,
 * and {@code platform} sits below every business module. See {@code AUDITABLE_ACTIONS.md} §2.
 *
 * <p><strong>Deliberately few.</strong> The licence is {@code P1-TSK-003}'s: declare an action
 * whose <em>design</em> is already fixed, never one a later task's design will shape. Two meet
 * that bar - a posting and a manual adjustment are named outright by {@code PHASE_3_PLAN.md} §11
 * and by {@code INV-LED-05}/{@code INV-REV-04}. Holds, reversals and account creation are absent on
 * purpose: whether each is its own action, and what its record carries, is decided by the task that
 * builds it.
 *
 * <p><strong>The audit record is not the posting, and neither substitutes.</strong> The journal
 * entry is the financial fact and already carries its actor and correlation ({@code INV-LED-05});
 * the audit record is the trail of the <em>act</em> of posting, under a different retention and
 * access regime ({@code INV-AUD-01}, ADR-0010).
 *
 * <p><strong>Nothing here is emitted yet</strong>; both constants name their owning task in
 * {@code AuditCompletenessTest.NOT_YET_EMITTED}.
 */
public enum LedgerAuditAction implements AuditableAction {

    /**
     * A balanced journal entry was posted.
     *
     * <p>No reason required: a posting is commanded by a platform flow - a transfer, a payment, a
     * reversal - whose own records carry the why, and a mandatory reason on it would produce a
     * column of the command's name ({@code AUDITABLE_ACTIONS.md} §4). What makes the record
     * defensible is attribution and correlation, which {@code INV-LED-05} already requires of the
     * entry itself. The code matches the event type the same posting publishes, as
     * {@code identity.AuthenticationSucceeded} does: one fact, named once, in two registries.
     * Emitted by {@code P3-TSK-006}.
     */
    JOURNAL_ENTRY_POSTED(
            "ledger.JournalEntryPosted",
            "A balanced journal entry was posted to the ledger; the record names the entry, never"
                    + " an amount.",
            false),

    /**
     * A person posted a manual adjusting entry.
     *
     * <p><strong>Reason required</strong>, and the invariant says so in as many words:
     * {@code INV-REV-04} - every manual adjustment records a reason code and the authorising actor.
     * An adjustment is a human choosing to move value the system would not have moved by itself,
     * which makes it the highest-risk financial action on the platform and its justification the
     * only evidence it was legitimate. Four-eyes above a threshold ({@code INV-AUD-04}) is recorded
     * debt, not implied by this flag. Emitted by {@code P3-TSK-017}.
     */
    ADJUSTMENT_POSTED(
            "ledger.AdjustmentPosted",
            "A person posted a manual adjusting entry; the reason and the authorising actor are"
                    + " recorded.",
            true);

    private final String code;
    private final String description;
    private final boolean requiresReason;

    LedgerAuditAction(String code, String description, boolean requiresReason) {
        this.code = code;
        this.description = description;
        this.requiresReason = requiresReason;
    }

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
