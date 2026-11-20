package com.finapp.app.overview;

import com.finapp.party.CustomerReports;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.transfers.TransferReports;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An {@link OverviewReportDesk} over one given connection and no transaction manager, for {@code OverviewReportDeskTest}: the
 * desk's wiring - a data source, a template - kept here, so the unit test names neither and stays in the unit tier
 * ({@code TestTaxonomyTest}).
 */
final class OverviewReportDeskFixture {

    private OverviewReportDeskFixture() {}

    /** A desk whose every transaction runs on {@code connection}, its instance's clock fixed at {@code instanceNow}. */
    static OverviewReportDesk desk(
            CustomerReports customers,
            TransferReports transfers,
            AuditWriter<Connection> audit,
            Instant instanceNow,
            Connection connection) {
        Clock clock = Clock.fixed(instanceNow, ZoneOffset.UTC);
        return new OverviewReportDesk(customers, transfers, audit, new IdGenerator(clock, new SecureRandom()), clock,
                new TransactionTemplate(new NoTransactions()), dataSource(connection));
    }

    private static DataSource dataSource(Connection connection) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        return connection;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** No transaction manager: the template runs the callback, as one over a real database would. */
    private static final class NoTransactions implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {}

        @Override
        public void rollback(TransactionStatus status) {}
    }
}
