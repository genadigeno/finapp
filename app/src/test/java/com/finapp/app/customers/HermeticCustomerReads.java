package com.finapp.app.customers;

import com.finapp.accounts.CustomerAccountStore;
import com.finapp.app.api.RecordedReads;
import com.finapp.consent.ConsentStore;
import com.finapp.identity.IdentityStore;
import com.finapp.kyc.KycCaseStore;
import com.finapp.party.PartyStore;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The customer reads' desk (`P17-TSK-125`) over whatever party store a hermetic suite hands in, opening nothing: a proxy source
 * whose connection is never used, a transaction manager that records each begin and its end, and every other module's store a
 * stand-in that refuses to be called. A top-level helper rather than part of the suite, for {@code HermeticKycServices}' reason:
 * {@code TestTier}'s database signature reads {@code javax.sql.DataSource} anywhere in a test class's tree.
 */
final class HermeticCustomerReads {

    private HermeticCustomerReads() {}

    /** The desk over {@code parties}, each transaction's begin and end appended to {@code events}. */
    static CustomerReadDesk desk(PartyStore<Connection> parties, RecordedReads recordedReads, List<String> events) {
        return new CustomerReadDesk(parties, refusing(IdentityStore.class), refusing(KycCaseStore.class),
                refusing(CustomerAccountStore.class), refusing(ConsentStore.class), recordedReads,
                new TransactionTemplate(new RecordingTransactions(events)), dataSource());
    }

    /** A store no read of this suite may reach: any call is a failure the suite sees. */
    @SuppressWarnings("unchecked")
    static <T> T refusing(Class<?> type) {
        return (T) Proxy.newProxyInstance(HermeticCustomerReads.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "refusing " + type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(type.getSimpleName() + "." + method.getName());
                });
    }

    /** A source whose one connection is never used: every store is the suite's, in memory. */
    private static DataSource dataSource() {
        Connection connection = (Connection) Proxy.newProxyInstance(HermeticCustomerReads.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isClosed", "getAutoCommit" -> false;
                    default -> null;
                });
        return (DataSource) Proxy.newProxyInstance(HermeticCustomerReads.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> connection;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Every unit of work runs at once; its begin and its end are recorded, nothing is committed. */
    private static final class RecordingTransactions implements PlatformTransactionManager {

        private final List<String> events;

        RecordingTransactions(List<String> events) {
            this.events = events;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            events.add("begin");
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            events.add("commit");
        }

        @Override
        public void rollback(TransactionStatus status) {
            events.add("rollback");
        }
    }
}
