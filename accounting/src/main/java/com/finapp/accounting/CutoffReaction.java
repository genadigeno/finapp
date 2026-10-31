package com.finapp.accounting;

/**
 * What a registered rule's poster does when its posting date lies on or before the ledger's posting cutoff - in a locked
 * or closed period (`P14-TSK-006`; owner decision C3, ratified 2026-10-30, "Floor to first open date"; ADR-0114 decision
 * 6; {@code INV-ACC-03}). Every rule names one, beside its {@link DatingClass}: a rule may date into the past exactly when
 * its reaction is not {@link #NOT_REACHED}, and {@link PostingRule}'s constructor refuses a rule that says otherwise.
 */
public enum CutoffReaction {

    /**
     * Dated today ({@link DatingClass#TODAY_CLOCK}, {@link DatingClass#TODAY_DATABASE}): the cutoff never covers today, so
     * no lock reaches the rule. Were one to (a clock behind the database's), the ledger refuses it - loudly, a defect.
     */
    NOT_REACHED,

    /**
     * A back-dating system rule as it posted before C3's floor: dated as stated and refused by the ledger
     * ({@code ledger.PeriodClosed}) when a lock covers the date - the state every such rule's version 1 describes, and
     * the defect C3 removes (its poster's sweep would re-drive it forever).
     */
    REFUSED,

    /**
     * A system poster's C3 reaction: it states its economic or stored date and asks the ledger for the floor
     * ({@code PostingCommand.Dating.FLOOR_AT_FIRST_OPEN}) - posted on that date, or on the first open date when a lock
     * covers it, the value date and the key's fingerprint the stated date's. Never refused by a lock.
     */
    FLOORED_AT_FIRST_OPEN,

    /**
     * A person's adjustment's C3 reaction: its posting date is part of what the approver approves, so it is never
     * floored - dated into a locked period, the approval is refused ({@code ledger.PeriodClosed}) before anything is
     * written and the proposal stays {@code PROPOSED}, to be re-proposed in the open period or replaced by a prior-period
     * adjustment ({@code P14-TSK-013}).
     */
    REFUSED_AT_APPROVAL;

    /** True when a lock can reach a rule with this reaction - every reaction but {@link #NOT_REACHED}. */
    public boolean reachedByALock() {
        return this != NOT_REACHED;
    }
}
