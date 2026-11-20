package com.finapp.app.kyc;

import com.finapp.app.api.RecordedReads;
import com.finapp.kyc.DocumentAccess;
import com.finapp.kyc.DocumentContentType;
import com.finapp.kyc.DocumentStore;
import com.finapp.kyc.KycCaseStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The KYC reviewer's document desk (`P17-TSK-125`) wired over a hermetic suite's own stores, opening nothing: the real
 * {@link DocumentAccess} and {@link RecordedReads} over the suite's audit writer, a proxy source whose connection is never used,
 * and a transaction manager that records each begin and how it ended - and, when asked, fails the commit, as a database that
 * goes away between the record's insert and its commit does. A top-level helper for {@link HermeticKycServices}' reason: a
 * suite naming {@code javax.sql.DataSource} reads as a database suite.
 */
final class HermeticKycDocuments {

    /** How the transaction ends when the work returns. */
    enum Commit {
        SUCCEEDS,
        FAILS
    }

    private HermeticKycDocuments() {}

    static KycDocumentReadDesk desk(DocumentStore<Connection> documents, KycCaseStore<Connection> cases,
            AuditWriter<Connection> audit, IdGenerator ids, Clock clock, List<String> events) {
        return desk(documents, cases, audit, ids, clock, events, KycDocumentReadDesk.ALLOW_LIST, Commit.SUCCEEDS);
    }

    static KycDocumentReadDesk desk(DocumentStore<Connection> documents, KycCaseStore<Connection> cases,
            AuditWriter<Connection> audit, IdGenerator ids, Clock clock, List<String> events,
            Map<DocumentContentType, KycDocumentReadDesk.Served> allowList, Commit commit) {
        return new KycDocumentReadDesk(documents, new DocumentAccess(documents, audit, ids, clock), cases,
                new RecordedReads(audit, ids, clock), new TransactionTemplate(new RecordingTransactions(events, commit)),
                dataSource(), allowList);
    }

    /** A source whose one connection is never used: every store is the suite's, in memory. */
    private static DataSource dataSource() {
        Connection connection = (Connection) Proxy.newProxyInstance(HermeticKycDocuments.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isClosed", "getAutoCommit" -> false;
                    default -> null;
                });
        return (DataSource) Proxy.newProxyInstance(HermeticKycDocuments.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> connection;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Every unit of work runs at once; its begin and its end are recorded, and the commit fails when asked to. */
    private static final class RecordingTransactions implements PlatformTransactionManager {

        private final List<String> events;
        private final Commit commit;

        RecordingTransactions(List<String> events, Commit commit) {
            this.events = events;
            this.commit = commit;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            events.add("begin");
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            if (commit == Commit.FAILS) {
                events.add("commit-failed");
                throw new TransactionSystemException("the connection was lost before the commit was acknowledged");
            }
            events.add("commit");
        }

        @Override
        public void rollback(TransactionStatus status) {
            events.add("rollback");
        }
    }
}
