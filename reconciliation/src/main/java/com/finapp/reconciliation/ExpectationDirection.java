package com.finapp.reconciliation;

/**
 * Which way the expected settlement moves value (`P8-TSK-004`, ADR-0067 §3).
 *
 * <p><strong>Derived from the clearing journal line, never chosen</strong>: a DEBIT line on
 * the position is {@code INBOUND} (the counterparty owes us — a capture grows the clearing),
 * a CREDIT line {@code OUTBOUND} (we owe the counterparty — a refund shrinks it). The
 * position proof's sign is therefore the ledger's own, and an applier cannot mis-declare a
 * direction its own posting contradicts.
 *
 * <p>Deliberately not the ledger's {@code Direction}: DEBIT/CREDIT is an accounting side,
 * this is a settlement flow — one is derived from the other exactly once, in the recorder.
 */
public enum ExpectationDirection {
    INBOUND,
    OUTBOUND
}
