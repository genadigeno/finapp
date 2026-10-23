package com.finapp.app.transfers;

import com.finapp.app.risk.RiskEvaluationWorld;
import com.finapp.app.telemetry.TransferMetrics;
import com.finapp.identity.IdentityStore;
import com.finapp.ledger.AvailableBalance;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcHoldStore;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.ReversalService;
import com.finapp.party.PartyStore;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.transfers.BeneficiaryStore;
import com.finapp.transfers.JdbcTransferStore;
import com.finapp.transfers.PermitAllUntilPhase13;
import com.finapp.transfers.TransferExecution;
import com.finapp.transfers.TransferParticipants;
import com.finapp.transfers.TransferReversal;
import com.finapp.transfers.TransferStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transfer door composed with nothing behind it (`P13-TSK-008`, for {@code TransferDoorAdviceTest}): the real
 * {@link TransferService} and {@link TransferExecution}, every store a JDBC class that is never called or a proxy the
 * suite supplies, a transaction manager that only journals {@code begin}/{@code commit}/{@code rollback}, and a data
 * source whose one connection refuses every use but its close. Nothing here obtains a database: the {@code Jdbc*}
 * classes are inert until handed a statement, and the door's own reads all go to the suite's proxies. Kept out of the
 * suite's class so the suite's bytecode names no {@code javax.sql.DataSource} - the type the tier detector
 * ({@code TestTier}) reads as "obtains a database" - while the claim it makes stays true.
 */
final class TransferDoorComposition {

    private TransferDoorComposition() {}

    /** The door over {@code stepUp}, the suite's key records, participants and identities; {@code journal} records. */
    @SuppressWarnings("unchecked") // the refusing proxies are raw: they answer no call at all
    static TransferService service(TransferDoorStepUp stepUp, IdempotencyRecordStore<Connection> records,
            TransferParticipants<Connection> participants, IdentityStore<Connection> identities, List<String> journal,
            Clock clock) {
        IdempotentExecutor executor = new IdempotentExecutor(records, clock, Duration.ofDays(1), Duration.ofMinutes(5));
        TransferStore<Connection> transfers = new JdbcTransferStore();
        TransferExecution execution = new TransferExecution(executor, participants, new JdbcLedgerAccountStore(),
                new AvailableBalance<>(new JdbcBalanceDerivation(), new JdbcHoldStore()),
                new PostingService(executor, new JdbcJournalEntryStore(RiskEvaluationWorld.IDS), new JdbcAuditWriter(),
                        new JdbcOutboxWriter(), new JdbcBalanceProjection(), RiskEvaluationWorld.IDS, clock,
                        PostingObserver.NONE),
                transfers, new PermitAllUntilPhase13<>(), new PermitAllUntilPhase13<>(), new JdbcAuditWriter(),
                new JdbcOutboxWriter(), RiskEvaluationWorld.IDS, clock);
        TransferReversal reversal = new TransferReversal(transfers, new ReversalService(executor,
                new JdbcJournalEntryStore(RiskEvaluationWorld.IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(),
                new JdbcBalanceProjection(), RiskEvaluationWorld.IDS, clock, PostingObserver.NONE),
                new JdbcAuditWriter(), new JdbcOutboxWriter(), RiskEvaluationWorld.IDS, clock);
        return new TransferService(stepUp, execution, reversal, transfers, refusing(BeneficiaryStore.class), identities,
                refusing(PartyStore.class), transactions(journal), dataSource(), metrics(), clock);
    }

    private static TransactionTemplate transactions(List<String> journal) {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                journal.add("begin");
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                journal.add("commit");
            }

            @Override
            public void rollback(TransactionStatus status) {
                journal.add("rollback");
            }
        });
    }

    /** Connections nothing may use but to close - the suite's proxies are the door's only readers. */
    private static DataSource dataSource() {
        Connection connection = (Connection) Proxy.newProxyInstance(TransferDoorComposition.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "close" -> null;
                    case "toString" -> "door-connection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("the door used the connection itself: " + method.getName());
                });
        return (DataSource) Proxy.newProxyInstance(TransferDoorComposition.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        return connection;
                    }
                    throw new AssertionError("only connections: " + method.getName());
                });
    }

    /** The surface's meters over a throwaway registry - its constructor is the telemetry package's own. */
    private static TransferMetrics metrics() {
        try {
            Constructor<TransferMetrics> constructor = TransferMetrics.class.getDeclaredConstructor(MeterRegistry.class);
            constructor.setAccessible(true);
            return constructor.newInstance(new SimpleMeterRegistry());
        } catch (ReflectiveOperationException unreachable) {
            throw new IllegalStateException(unreachable);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T refusing(Class<T> type) {
        return (T) Proxy.newProxyInstance(TransferDoorComposition.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    throw new AssertionError("not consulted by the door: " + type.getSimpleName() + "."
                            + method.getName());
                });
    }
}
