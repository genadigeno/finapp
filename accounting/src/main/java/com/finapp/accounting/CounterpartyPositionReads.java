package com.finapp.accounting;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The journal's lines on {@code COUNTERPARTY}-owned accounts, per counterparty (`P14-TSK-017`'s review, I-1; {@code INV-RAIL-04}: no
 * clearing account nets two counterparties; ledger {@code V025}: "NOTHING NETS") - a port beside {@link TrialBalanceReads}, whose
 * statements carry no counterparty and stay as they are, so a trial balance's bytes do not move. A report that shows such a purpose reads
 * both on one snapshot: {@code fx-sim-a} at +1,000 EUR and {@code fx-sim-b} at -1,000 are two rows, never a position of 0.
 *
 * <p>Each aggregate's {@code counterparty} is the ledger's {@code counterparty.code}; it is empty only when an account's owner reference
 * names no counterparty row, and the report refuses that rather than folding it. Read-only, on the caller's snapshot ({@code INV-ACC-14}),
 * single {@code SELECT}s; no reason, actor or entry reference is ever selected. The cut is {@link TrialBalanceReads}'s: only entries
 * created at or before {@code cut} when one is given.
 */
public interface CounterpartyPositionReads {

    /** Every line on a {@code COUNTERPARTY}-owned account dated before {@code before}, from inception, per counterparty. */
    List<JournalReads.Aggregate> before(Connection snapshot, LocalDate before, Optional<Instant> cut);

    /** Every line on a {@code COUNTERPARTY}-owned account dated in {@code range}, per counterparty. */
    List<JournalReads.Aggregate> within(Connection snapshot, JournalReads.Range range, Optional<Instant> cut);
}
