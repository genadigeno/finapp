package com.finapp.app.api;

/**
 * What a customer's statement and a merchant's report call an adjustment (`P14-TSK-022`, the owner's ruling (s), 2026-11-04): <strong>one label, whatever its origin</strong>, set where each is rendered. An
 * adjustment's {@code reference} is an operator's free text ({@code MANUAL}), a resolution's bare id ({@code RECONCILIATION}) or an accounting constant ({@code ACCOUNTING}), and none of those is theirs to read, so
 * neither surface serves it. A line is an adjustment by its entry's type, {@code ADJUSTMENT}: every origin is one, and no other entry is. A write-off is not: it is a {@code POSTING}, with no line on a wallet or a
 * payable. Operators' surfaces keep the reference.
 */
public final class StatementLabels {

    /** The one label, the owner's word. */
    public static final String ACCOUNT_ADJUSTMENT = "Account adjustment";

    private StatementLabels() {}
}
