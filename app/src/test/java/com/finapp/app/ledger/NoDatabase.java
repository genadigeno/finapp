package com.finapp.app.ledger;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A transaction template with nothing to manage and a data source handing out one connection nobody touches
 * (`P14-TSK-026`), for a desk whose collaborators are in memory. Built here and not in a test class, so a hermetic suite
 * names no {@code DataSource}, the taxonomy's database signature ({@code SteppedUpDoor}'s reason).
 */
final class NoDatabase {

    private NoDatabase() {}

    /** A template that begins, commits and rolls back nothing. */
    static TransactionTemplate transactions() {
        return transactions(new java.util.ArrayList<>());
    }

    /**
     * A template that begins, commits and rolls back nothing, writing each of those words to {@code trail} as it happens -
     * so a suite can read which transaction a write fell in.
     */
    static TransactionTemplate transactions(java.util.List<String> trail) {
        return new TransactionTemplate(new NoTransactions(trail));
    }

    /** One connection, "closed" by {@code DataSourceUtils} and otherwise never touched. */
    static javax.sql.DataSource oneConnection() {
        return new OneConnection();
    }

    private static final class NoTransactions extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;

        private final transient java.util.List<String> trail;

        NoTransactions(java.util.List<String> trail) {
            this.trail = trail;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
            trail.add("begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            trail.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            trail.add("rollback");
        }
    }

    private static final class OneConnection implements javax.sql.DataSource {

        private final Connection connection = (Connection) Proxy.newProxyInstance(OneConnection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "close" -> null;
                    case "isClosed" -> false;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        @Override
        public Connection getConnection() {
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) {
            return connection;
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException("unwrap");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
