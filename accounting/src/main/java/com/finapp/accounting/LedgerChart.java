package com.finapp.accounting;

/**
 * The ledger's chart as a GL mapping version is checked against it (`P14-TSK-001`; {@code PHASE_14_PLAN.md} section 4.2;
 * ADR-0113 decision 3; {@code INV-ACC-06}, {@code -07}) - the account purposes, each purpose's ledger account type, the
 * owner kinds and counterparties that exist - so an activation can refuse a version that is not total over the purposes
 * or not classification-compatible ({@link GlClassification} names = the ledger's account type names). Purposes travel
 * as strings: {@code accounting} has no ledger edge.
 *
 * <p><strong>Read-only</strong> ({@code SELECT} only). <strong>Declared empty</strong>: `P14-TSK-003` gives it its
 * methods and {@code app} its implementation.
 */
public interface LedgerChart {
}
