package com.finapp.accounts;

import com.finapp.platform.audit.AuditableAction;

/**
 * What the {@code accounts} module does that must produce an audit record.
 *
 * <p>Declared per module rather than centrally, {@code AUDITABLE_ACTIONS.md} §2's rule — and
 * declared <strong>with the aggregate whose design fixes its meaning</strong> rather than with
 * the module skeleton, which is `P3-TSK-011`'s recorded decision applying the
 * {@code kyc.CaseOpened}/{@code P2-TSK-005} precedent: `P3-TSK-011` shipped no enum because no
 * accounts action's design was fixed yet; this task fixes opening's.
 *
 * <p><strong>Deliberately two.</strong> {@code ACCOUNT_CLOSED} arrived with `P3-TSK-014`, the
 * task whose design fixed it — exactly as the absence this javadoc used to record predicted.
 * Suspension still has no producer this phase ({@link CustomerAccountStatus}), so an action
 * for it would be vocabulary with no decision behind it.
 */
public enum AccountsAuditAction implements AuditableAction {

    /**
     * A customer account product was opened.
     *
     * <p>No reason required: opening is a person's own act on their own relationship (the
     * consent-action reasoning, {@code AUDITABLE_ACTIONS.md} §4) — a mandatory justification
     * would produce a column of {@code "wanted an account"}. The code matches the event type
     * the same opening publishes, as {@code ledger.JournalEntryPosted} does: one fact, named
     * once, in two registries. Emitted by {@link AccountOpening} in the opening transaction;
     * only the <em>creating</em> call records — a converged retry is not a second act.
     */
    ACCOUNT_OPENED(
            "accounts.AccountOpened",
            "A customer account product was opened; the record names the account, the product"
                    + " type and the customer, never a balance.",
            false),

    /**
     * A customer account product was closed.
     *
     * <p>No reason required: closing is a person's own act on their own agreement, and a
     * demanded justification at the moment of exit is pressure applied exactly where none may
     * exist (the consent-withdrawal reasoning, {@code AUDITABLE_ACTIONS.md} §4). The control
     * is the zero-balance precondition, not a paragraph. The code matches the event type the
     * same closing publishes; emitted by {@link AccountClosing} in the closing transaction, by
     * the closing call only — a converged repeat is not a second act.
     */
    ACCOUNT_CLOSED(
            "accounts.AccountClosed",
            "A customer account product was closed; the agreement ended, the accounting history"
                    + " did not (INV-HIST-01).",
            false);

    private final String code;
    private final String description;
    private final boolean requiresReason;

    AccountsAuditAction(String code, String description, boolean requiresReason) {
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
