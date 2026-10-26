package com.finapp.app.kyc;

import com.finapp.kyc.BeneficialOwnerStore;
import com.finapp.kyc.CallbackSignature;
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.KycDecisionStore;
import com.finapp.kyc.ReviewTaskStore;
import com.finapp.kyc.VerificationProvider;
import com.finapp.party.PartyStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxRecordStore;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * kyc's services - {@link CaseAssessment}, {@link DecisionRecording}, {@link VerificationRunService},
 * {@link ProviderCallbackService} - wired over whatever stores a hermetic suite hands in (`P13-TSK-022`'s last review,
 * item 4: {@code RescreenRoutingTest}).
 *
 * <p>Their constructors take a {@code DataSource} and a {@code TransactionTemplate}; the ones built here open nothing -
 * a proxy source whose connection is never used (the stores are the suite's own, in memory) and a transaction manager
 * that commits and rolls back nothing. A top-level helper rather than a part of the suite, deliberately: {@code
 * TestTier}'s database signature reads {@code javax.sql.DataSource} anywhere in a test class's tree as "obtains a
 * database", which is true of every real use and would be false here - this keeps the suite in the tier it needs.
 */
final class HermeticKycServices {

    /** The services, wired. */
    record Services(CaseAssessment assessment, VerificationRunService runs, ProviderCallbackService callbacks) {}

    private HermeticKycServices() {}

    static Services over(KycCaseStore<Connection> cases, CheckStore<Connection> checks,
            ReviewTaskStore<Connection> tasks, BeneficialOwnerStore<Connection> owners,
            KycDecisionStore<Connection> decisions, PartyStore<Connection> parties,
            List<VerificationProvider> providers, RescreenHits hits, byte[] callbackKey, IdGenerator ids, Clock clock) {
        TransactionTemplate transactions = new TransactionTemplate(new NoTransactions());
        DataSource dataSource = dataSource();
        AuditWriter<Connection> audit = (unitOfWork, record) -> { };
        DecisionRecording recording = new DecisionRecording(cases, checks, decisions, parties, audit, ids, clock,
                transactions, dataSource, new StaticListableBeanFactory().getBeanProvider(CaseAssessment.class));
        CaseAssessment assessment = new CaseAssessment(cases, checks, tasks, owners, recording, providers, ids, clock,
                transactions, dataSource);
        StaticListableBeanFactory told = new StaticListableBeanFactory(Map.of("hits", hits));
        CheckOutcomeTrail trail = new CheckOutcomeTrail(audit, ids, clock, new SimpleMeterRegistry());
        VerificationRunService runs = new VerificationRunService(cases, checks, assessment, trail, providers, ids,
                clock, transactions, dataSource, new SimpleMeterRegistry(), told.getBeanProvider(RescreenHits.class));
        InboxConsumer<Connection> inbox = new InboxConsumer<>(
                (InboxRecordStore<Connection>) (unitOfWork, key, type, correlation, at, retention) ->
                        InboxRecordStore.RecordOutcome.RECORDED, clock, Duration.ofDays(30));
        ProviderCallbackService callbacks = new ProviderCallbackService(checks, new CallbackSignature(callbackKey),
                inbox, trail, assessment, JsonMapper.builder().build(), ids, clock, transactions, dataSource,
                told.getBeanProvider(RescreenHits.class));
        return new Services(assessment, runs, callbacks);
    }

    /** A source whose one connection is never used: every store is the suite's, in memory. */
    private static DataSource dataSource() {
        Connection connection = (Connection) Proxy.newProxyInstance(HermeticKycServices.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isClosed", "getAutoCommit" -> false;
                    default -> null;
                });
        return (DataSource) Proxy.newProxyInstance(HermeticKycServices.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> connection;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Every unit of work runs at once; nothing is rolled back - the stores are in memory. */
    private static final class NoTransactions implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // Nothing to commit.
        }

        @Override
        public void rollback(TransactionStatus status) {
            // Nothing to roll back.
        }
    }
}
