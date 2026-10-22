package com.finapp.risk;

import java.sql.Connection;
import java.util.List;

/**
 * The ledger's journal as the monitoring intake reads it (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 12.7;
 * ADR-0111 decision 1; {@code INV-RSK-09}) - the one financial truth, read through a port {@code app} implements over
 * the ledger's journal entry store ordered by its sequence, because risk has no edge to {@code ledger} and must never
 * gain one. Events are hints that pull the sweep's permit forward; the sweep reads rows through this port.
 *
 * <p>The intake (`-019`) claims the watermark {@code FOR UPDATE SKIP LOCKED}, reads the entries after it in sequence
 * order, classifies each by its posting-key namespace into a monitored transaction born once per journal entry
 * ({@code UNIQUE (journal_entry_id)}), records an unknown namespace as {@code UNCLASSIFIED} and alerted, and advances
 * the watermark - all in the sweep's own transaction, never a money transaction. A read returns bounded facts: the
 * entry's sequence, identifier, posting key, posting instant and lines; never a balance, never a row of the ledger's.
 * The shape is `-019`'s to refine; an interface with no implementation is free to change.
 */
public interface MonitoredLedger {

    /** The journal's head: the highest sequence posted - what {@code finapp.risk.monitoring.lag} measures against. */
    long headSequence(Connection unitOfWork);

    /** At most {@code limit} entries with a sequence above {@code afterSequence}, ascending. Empty at the head. */
    List<MonitoredEntry> entriesAfter(Connection unitOfWork, long afterSequence, int limit);
}
