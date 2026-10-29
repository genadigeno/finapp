package com.finapp.accounting;

/**
 * The one door through which accounting writes the journal (`P14-TSK-001`; {@code PHASE_14_PLAN.md} sections 4.2, 13.5;
 * ADR-0113 decision 4; {@code INV-ACC-10}, {@code -14}) - a prior-period adjustment posted as the ledger's own
 * {@code ACCOUNTING}-origin adjustment ({@code AdjustmentService.proposeOwned} / {@code approveOwned}, ledger
 * {@code V030}), four eyes, dated today in an open period, referencing the closed period it corrects. Never a free
 * posting, never a balance write.
 *
 * <p><strong>A writer port</strong> - so {@code ReportingNeverWritesTest} refuses it to every reporting class.
 * <strong>Declared empty</strong>: `P14-TSK-013` gives it its methods and {@code app} its implementation.
 */
public interface PriorPeriodPostings {
}
