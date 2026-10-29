package com.finapp.accounting;

/**
 * The product subledgers' existing proofs as close checks read them (`P14-TSK-001`; {@code PHASE_14_PLAN.md} sections
 * 3, 13.3; ADR-0117; {@code INV-ACC-12}) - lending's subledger and replay proofs, BNPL's three-way and subledger proofs,
 * fx's books proof, reconciliation's position proof, the merchant payable drill-down and the ledger's projection
 * verification: their verdicts and evidence, <strong>never re-implemented here</strong>. A finding becomes a
 * {@link BreakType#SOURCE_DISCREPANCY} or {@link BreakType#SOURCE_DELAYED} break, never a correction.
 *
 * <p><strong>Read-only.</strong> <strong>Declared empty</strong>: `P14-TSK-010` gives it its methods and {@code app} its
 * implementation.
 */
public interface SourceProofs {
}
