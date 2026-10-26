package com.finapp.risk;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The ledger's journal as the monitoring intake reads it (`P13-TSK-001`; first implemented and reshaped by
 * `P13-TSK-019`; {@code PHASE_13_PLAN.md} section 12.7; ADR-0111 decision 1; {@code INV-RSK-09}) - the one financial
 * truth, read through a port {@code app} implements over the ledger's journal entry store, because risk has no edge to
 * {@code ledger} and must never gain one. Events are hints that pull the sweep's permit forward; the sweep reads rows
 * through this port.
 *
 * <p><strong>What the read is ordered by.</strong> The journal has no sequence column; its entry id is a UUIDv7 whose
 * first 48 bits are the minting millisecond and whose primary key index is the journal's only ordered access path
 * (ADR-0013). So the intake reads by id as a keyset, and only entries minted before {@code clock_timestamp() - settleHorizon}
 * on the <em>database</em> clock - the settle horizon that covers a posting's transaction time and the instances'
 * clock skew, so an entry still in flight is not overtaken by a later-minted one that committed first ({@code DESIGN-019}
 * section 0). The port's implementation holds no statement but {@code SELECT}; it never locks a journal row and never
 * reads a balance.
 */
public interface MonitoredLedger {

    /**
     * At most {@code limit} entries whose id sorts after {@code afterEntryId} and whose minting millisecond lies before
     * {@code clock_timestamp() - settleHorizon}, ascending by id, each with its lines and their accounts' owners by value.
     * Empty at the head.
     */
    List<MonitoredEntry> entriesAfter(Connection unitOfWork, UUID afterEntryId, Duration settleHorizon, int limit);

    /**
     * How many entries sort after {@code afterEntryId}, counted up to {@code cap} - the lag {@code finapp.risk.monitoring.lag}
     * reports; saturating, because a gauge behind a year's outage must not scan a year's journal.
     */
    long entriesBeyond(Connection unitOfWork, UUID afterEntryId, long cap);

    /**
     * The ids of every journal entry in {@code [fromInclusive, toExclusive)}, ascending - one day of the journal by
     * sequence, the monitoring census's side of the ledger (`P13-TSK-023`; {@code INV-RSK-09}): a primary-key range, no
     * lock, no line read.
     */
    List<UUID> entryIds(Connection snapshot, UUID fromInclusive, UUID toExclusive);
}
