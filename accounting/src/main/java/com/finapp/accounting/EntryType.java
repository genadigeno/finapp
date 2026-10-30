package com.finapp.accounting;

/**
 * The ledger door a registered posting rule writes through (`P14-TSK-002`; ADR-0117 decision 1) - named exactly as the
 * ledger's own {@code JournalEntryType}, which {@code app} holds equal because {@code accounting} has no ledger edge
 * (ADR-0113). A rule is keyed by its type and its namespace: the same key namespace means a different economic event
 * through a different door ({@code transfer:} under {@link #POSTING} is the transfer, under {@link #REVERSAL} its
 * reversal).
 */
public enum EntryType {

    /** {@code PostingService.post} - scope {@code ledger.post}; the namespace is the posting key's. */
    POSTING,
    /** {@code ReversalService.reverse} - scope {@code ledger.reverse}; the namespace is the reversal command key's. */
    REVERSAL,
    /**
     * {@code AdjustmentService}'s approval - every entry's scope {@code ledger.adjust.approve:<proposal>}, so the rule is
     * keyed by the proposal's <strong>origin</strong> ({@code MANUAL}, {@code RECONCILIATION}) instead of a key.
     */
    ADJUSTMENT;

    /** True when the rule's namespace is a posting-key prefix ({@code a.b-c:}); false when it is an adjustment origin. */
    public boolean keyedByNamespace() {
        return this != ADJUSTMENT;
    }
}
