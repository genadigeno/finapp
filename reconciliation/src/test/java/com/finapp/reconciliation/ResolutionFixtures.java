package com.finapp.reconciliation;

import com.finapp.ledger.AdjustmentService;
import com.finapp.ledger.JdbcAdjustmentProposalStore;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.PostingObserver;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Duration;

/**
 * The real ledger and resolution wiring the database suites share (`P8-TSK-015`): the
 * evidence writer withdraws a pending proposal through the ledger's owned door, so every suite
 * that closes breaks by evidence builds it over the real {@link AdjustmentService}.
 */
final class ResolutionFixtures {

    private ResolutionFixtures() {}

    static AdjustmentService adjustments(IdGenerator ids, Clock clock) {
        return new AdjustmentService(
                new IdempotentExecutor(
                        new JdbcIdempotencyRecordStore(),
                        clock,
                        Duration.ofDays(1),
                        Duration.ofMinutes(5)),
                new JdbcJournalEntryStore(ids),
                new JdbcAdjustmentProposalStore(),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                new JdbcBalanceProjection(),
                ids,
                clock,
                PostingObserver.NONE,
                new JdbcLedgerAccountStore());
    }

    static JdbcResolutions resolutions(IdGenerator ids, Clock clock) {
        return new JdbcResolutions(
                new JdbcOutboxWriter(),
                new JdbcAuditWriter(),
                ids,
                new JdbcResolutionStore(),
                adjustments(ids, clock),
                ReconciliationTelemetry.NONE);
    }
}
