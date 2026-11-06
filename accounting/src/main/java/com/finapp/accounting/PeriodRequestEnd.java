package com.finapp.accounting;

/**
 * How a pending unlock or reopen ends without its effect (`P14-TSK-024`; the owner's acceptance of `P14-TSK-012`'s item (d); {@code accounting V011}): each its own terminal
 * state, and neither moves anything - no cutoff, no pin, no revision, no report run. The month is then free for a new request.
 *
 * <p>{@link #REJECTED} is a second person's decision against someone else's request, under {@code ACCOUNTING_CLOSE_APPROVE}: never its proposer's ({@code INV-AUD-04}, held by
 * {@code CHECK} on the end's row and on the history). {@link #WITHDRAWN} is the proposer's own retraction, under {@code ACCOUNTING_CLOSE_PROPOSE}, and nobody else's. Kept apart so
 * that {@code REJECTED} always means two persons: a proposer holding both roles withdraws, and is refused a rejection of their own.
 */
public enum PeriodRequestEnd {
    REJECTED,
    WITHDRAWN;

    /** The act that records this end of an unlock in the period's history. */
    public AccountingPeriodStore.Act unlockAct() {
        return this == REJECTED ? AccountingPeriodStore.Act.UNLOCK_REJECTED : AccountingPeriodStore.Act.UNLOCK_WITHDRAWN;
    }

    /** The act that records this end of a reopen in the period's history. */
    public AccountingPeriodStore.Act reopenAct() {
        return this == REJECTED ? AccountingPeriodStore.Act.REOPEN_REJECTED : AccountingPeriodStore.Act.REOPEN_WITHDRAWN;
    }

    /** The reopen row's status this end leaves. */
    public PeriodReopenStatus reopenStatus() {
        return this == REJECTED ? PeriodReopenStatus.REJECTED : PeriodReopenStatus.WITHDRAWN;
    }
}
