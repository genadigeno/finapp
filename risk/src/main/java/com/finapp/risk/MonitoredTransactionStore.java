package com.finapp.risk;

import java.sql.Connection;
import java.util.List;

/**
 * Storage for monitored transactions (`P13-TSK-019`; {@code INV-RSK-09}) - a port (ADR-0033) with one write, the
 * born-once insert, because the table has no other kind: {@code risk V004} grants {@code SELECT, INSERT} and nothing
 * else, and refuses every edit by trigger. The unit of work is the sweep's: a batch's rows and the watermark's advance
 * commit together or not at all.
 */
public interface MonitoredTransactionStore {

    /**
     * Inserts the rows not yet born - {@code INSERT … ON CONFLICT (journal_entry_id) DO NOTHING} - and answers how many
     * were. A row already present (another sweeper's, an earlier batch's, a reprocessing's) is absorbed silently.
     */
    int insertAbsent(Connection unitOfWork, List<MonitoredTransaction> rows);

    /** How many rows are {@code UNCLASSIFIED} - the gauge {@code finapp.risk.monitoring.unclassified}. */
    long countUnclassified(Connection unitOfWork);

    /** How many rows exist - the census's left-hand figure. */
    long count(Connection unitOfWork);
}
