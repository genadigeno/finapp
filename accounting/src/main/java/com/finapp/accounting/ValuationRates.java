package com.finapp.accounting;

/**
 * fx's reference rate snapshots as a valuation rate set captures them (`P14-TSK-001`; {@code PHASE_14_PLAN.md} section
 * 13.4; ADR-0116; owner decisions C1, C7; {@code INV-ACC-11}) - the last observation at or before the period's end per
 * pair, with its source, {@code observed_at} and snapshot id (rates {@code NUMERIC(20,10)}, never floating point,
 * {@code INV-MON-01}). Never a customer quote and never an executed rate ({@code INV-FX-05} kept).
 *
 * <p><strong>Read-only.</strong> <strong>Declared empty</strong>: `P14-TSK-014` gives it its methods and {@code app} its
 * implementation.
 */
public interface ValuationRates {
}
