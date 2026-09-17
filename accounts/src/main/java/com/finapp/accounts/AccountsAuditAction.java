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
 * <p><strong>Deliberately one.</strong> {@code accounts.AccountClosed} is absent on purpose —
 * whether closing is its own audited act and what its record carries is `P3-TSK-014`'s design.
 * Suspension has no producer at all this phase ({@link CustomerAccountStatus}), so an action
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
