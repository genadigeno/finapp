package com.finapp.app.payments;

import com.finapp.app.risk.RiskEvaluationWorld;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcHoldStore;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.payments.JdbcSchemeExecutionClaimStore;
import com.finapp.payments.JdbcWithdrawalStore;
import com.finapp.payments.PaymentRails;
import com.finapp.payments.ProviderEvidenceStore;
import com.finapp.payments.RailOperations;
import com.finapp.payments.RailOutcomeObserver;
import com.finapp.payments.RoutingStore;
import com.finapp.payments.SettlementExpectations;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.payments.TransactionRunner;
import com.finapp.payments.WithdrawalOutcomes;
import com.finapp.payments.Withdrawals;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The withdrawal engine composed with nothing behind it (`P13-TSK-009`, for {@code WithdrawalDoorAdviceTest}): the real
 * {@link Withdrawals} over inert {@code Jdbc*} classes and refusing proxies, whose transaction runner raises the suite's
 * own marker the moment the command opens a transaction - so "the command ran" is observable and nothing behind it
 * ever is. Nothing here obtains a database ({@code TransferDoorComposition}'s stance).
 */
final class WithdrawalDoorComposition {

    private WithdrawalDoorComposition() {}

    /** The engine whose every transaction raises {@code reached}'s exception. */
    static Withdrawals engine(Supplier<RuntimeException> reached) {
        Clock clock = RiskEvaluationWorld.CLOCK;
        IdempotentExecutor executor = new IdempotentExecutor(new JdbcIdempotencyRecordStore(), clock,
                Duration.ofDays(1), Duration.ofMinutes(5));
        HoldService holds = new HoldService(new JdbcLedgerAccountStore(), new JdbcBalanceDerivation(),
                new JdbcHoldStore(), new JdbcBalanceProjection(), new JdbcAuditWriter(), new JdbcOutboxWriter(),
                RiskEvaluationWorld.IDS, clock);
        PaymentRails rails = PaymentRails.of(List.of(SimulatedInstantSchemeAdapter.RAIL));
        WithdrawalOutcomes outcomes = new WithdrawalOutcomes(new JdbcWithdrawalStore(), holds,
                new PostingService(executor, new JdbcJournalEntryStore(RiskEvaluationWorld.IDS), new JdbcAuditWriter(),
                        new JdbcOutboxWriter(), new JdbcBalanceProjection(), RiskEvaluationWorld.IDS, clock,
                        PostingObserver.NONE),
                new ChartOfAccounts<>(new JdbcLedgerAccountStore()), new JdbcAuditWriter(), new JdbcOutboxWriter(),
                RiskEvaluationWorld.IDS, clock, RailOutcomeObserver.NONE, rails, new JdbcSchemeExecutionClaimStore(),
                refusing(SettlementExpectations.class));
        TransactionRunner never = new TransactionRunner() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                throw reached.get();
            }
        };
        return new Withdrawals(new JdbcWithdrawalStore(), outcomes, holds, refusing(RoutingStore.class), rails,
                RailOperations.none(), refusing(ProviderEvidenceStore.class), executor, new JdbcAuditWriter(),
                RiskEvaluationWorld.IDS, clock, never, (unitOfWork, judged) -> {
                    throw new AssertionError("the command never runs here");
                });
    }

    /** The resolution sweep over the same inert collaborators, every transaction through {@code runner}. */
    static com.finapp.payments.WithdrawalResolution resolution(TransactionRunner runner) {
        Clock clock = RiskEvaluationWorld.CLOCK;
        IdempotentExecutor executor = new IdempotentExecutor(new JdbcIdempotencyRecordStore(), clock,
                Duration.ofDays(1), Duration.ofMinutes(5));
        HoldService holds = new HoldService(new JdbcLedgerAccountStore(), new JdbcBalanceDerivation(),
                new JdbcHoldStore(), new JdbcBalanceProjection(), new JdbcAuditWriter(), new JdbcOutboxWriter(),
                RiskEvaluationWorld.IDS, clock);
        PaymentRails rails = PaymentRails.of(List.of(SimulatedInstantSchemeAdapter.RAIL));
        WithdrawalOutcomes outcomes = new WithdrawalOutcomes(new JdbcWithdrawalStore(), holds,
                new PostingService(executor, new JdbcJournalEntryStore(RiskEvaluationWorld.IDS), new JdbcAuditWriter(),
                        new JdbcOutboxWriter(), new JdbcBalanceProjection(), RiskEvaluationWorld.IDS, clock,
                        PostingObserver.NONE),
                new ChartOfAccounts<>(new JdbcLedgerAccountStore()), new JdbcAuditWriter(), new JdbcOutboxWriter(),
                RiskEvaluationWorld.IDS, clock, RailOutcomeObserver.NONE, rails, new JdbcSchemeExecutionClaimStore(),
                refusing(SettlementExpectations.class));
        return new com.finapp.payments.WithdrawalResolution(new JdbcWithdrawalStore(), outcomes,
                refusing(com.finapp.payments.PushRail.class), rails, SimulatedInstantSchemeAdapter.RAIL.id(),
                refusing(ProviderEvidenceStore.class),
                new com.finapp.payments.WithdrawalResolution.Config(Duration.ofMinutes(10), Duration.ofMinutes(2),
                        Duration.ofMinutes(5), 25),
                RiskEvaluationWorld.IDS, clock, runner);
    }

    /** An {@code ObjectProvider} answering {@code engine}. */
    @SuppressWarnings("unchecked")
    static ObjectProvider<Withdrawals> provider(Withdrawals engine) {
        return (ObjectProvider<Withdrawals>) Proxy.newProxyInstance(WithdrawalDoorComposition.class.getClassLoader(),
                new Class<?>[] {ObjectProvider.class}, (proxy, invoked, args) -> switch (invoked.getName()) {
                    case "getIfAvailable", "getObject" -> engine;
                    default -> throw new AssertionError("the door asks the provider for the engine only: "
                            + invoked.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T refusing(Class<?> type) {
        return (T) Proxy.newProxyInstance(WithdrawalDoorComposition.class.getClassLoader(), new Class<?>[] {type},
                (proxy, invoked, args) -> {
                    if (invoked.getName().equals("toString")) {
                        return type.getSimpleName();
                    }
                    throw new AssertionError("the engine never reaches " + type.getSimpleName() + "."
                            + invoked.getName());
                });
    }
}
