package com.finapp.accounting;

/**
 * The jurisdiction adapter a regulatory submission is sent through (`P14-TSK-001`; {@code PHASE_14_PLAN.md} section
 * 13.7; ADR-0118; owner decision C4) - simulated in Phase 14, its export format a reference document; called outside any
 * transaction with {@code SUBMITTED} committed first, and its acknowledgement or rejection read back by an inquiry leg.
 * An unknown answer is an inquiry, never a re-submit. No legal obligation is asserted and nothing claims compliance.
 *
 * <p><strong>A writer port</strong> (it sends to an external party) - so {@code ReportingNeverWritesTest} refuses it to
 * every reporting class. <strong>Declared empty</strong>: `P14-TSK-018` gives it its methods and {@code app} its
 * simulated adapter.
 */
public interface RegulatoryReportingProvider {
}
