package com.finapp.payments;

import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;

/**
 * A {@link ChargebackAccounting} for the hermetic suites whose flows never meet a dispute
 * (`P7-TSK-013`): real stores and a real {@link PostingService} over no database — the
 * {@code PaymentCaptureTest} tripwire idiom, so any touch explodes and "no dispute money moved"
 * is structural rather than asserted. Only the capability question
 * ({@link ChargebackAccounting#disputable}) and a push or book refund's failure path — which asks
 * it and stops — may run.
 */
final class UntouchedChargebacks {

    private UntouchedChargebacks() {}

    static ChargebackAccounting over(
            PaymentAttemptStore<Connection> attempts,
            PaymentIntentStore<Connection> intents,
            PaymentRails rails,
            IdGenerator ids,
            Clock clock) {
        return new ChargebackAccounting(
                new JdbcDisputeStore(),
                new JdbcRefundStore(),
                attempts,
                intents,
                rails,
                new WalletDisputeComposition(),
                new PostingService(
                        new IdempotentExecutor(
                                new JdbcIdempotencyRecordStore(),
                                clock,
                                Duration.ofDays(1),
                                Duration.ofMinutes(5)),
                        new JdbcJournalEntryStore(ids),
                        (uow, record) -> {
                            throw new AssertionError("no dispute posting in this suite");
                        },
                        new JdbcOutboxWriter(),
                        new JdbcBalanceProjection(),
                        ids,
                        clock,
                        PostingObserver.NONE),
                new ChartOfAccounts<>(new JdbcLedgerAccountStore()),
                new JdbcLedgerAccountStore(),
                (uow, record) -> {
                    throw new AssertionError("no dispute audit in this suite");
                },
                ids,
                clock,
                // The same tripwire for the stage expectations (P8-TSK-005): no dispute money
                // moves here, so no stage can open one.
                new SettlementExpectations() {
                    @Override
                    public void open(Connection unitOfWork, Opening opening) {
                        throw new AssertionError("no dispute expectation in this suite");
                    }

                    @Override
                    public void alias(Connection unitOfWork, AliasRegistration registration) {
                        throw new AssertionError("no dispute alias in this suite");
                    }

                    @Override
                    public void parked(Connection unitOfWork, ParkedValue parked) {
                        throw new AssertionError("no parking in this suite");
                    }
                });
    }
}
