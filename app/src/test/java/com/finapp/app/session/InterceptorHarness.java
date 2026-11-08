package com.finapp.app.session;

import com.finapp.identity.Authorization;
import com.finapp.identity.RoleAssignmentStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The session interceptor's collaborators with no database (`P15-TSK-021`): a session and roles stood in, every
 * transaction's begin and end and every audit record written captured in one ordered list. Built here and not in the
 * test classes, so a hermetic suite names no {@code DataSource}, the taxonomy's database signature
 * ({@code SteppedUpDoor}'s reason).
 */
final class InterceptorHarness {

    private InterceptorHarness() {}

    /** The real interceptor over {@code session}, its identity holding {@code roles}. */
    static SessionAuthenticationInterceptor interceptor(Session session, Set<RoleName> roles, AtomicInteger roleReads,
            List<String> events, List<AuditRecord> written, IdGenerator ids, Clock clock) {
        return new SessionAuthenticationInterceptor(sessionsAnswering(session), transactions(events), new OneConnection(),
                SessionPolicy.current(), authorization(roles, roleReads, events, written, ids, clock));
    }

    /** The error contract's recorder over the same captures. */
    static StepUpRefusals stepUpRefusals(List<String> events, List<AuditRecord> written, IdGenerator ids, Clock clock) {
        return new StepUpRefusals(transactions(events), new OneConnection(),
                authorization(Set.of(), new AtomicInteger(), events, written, ids, clock));
    }

    /** The recorder whose audit write fails - the record that cannot be written. */
    static StepUpRefusals failingStepUpRefusals(IdGenerator ids, Clock clock) {
        return new StepUpRefusals(transactions(new java.util.ArrayList<>()), new OneConnection(),
                new Authorization(roles(Set.of(), new AtomicInteger()), ids, clock, (uow, record) -> {
                    throw new IllegalStateException("the audit store is unreachable");
                }));
    }

    private static Authorization authorization(Set<RoleName> roles, AtomicInteger roleReads, List<String> events,
            List<AuditRecord> written, IdGenerator ids, Clock clock) {
        return new Authorization(roles(roles, roleReads), ids, clock, (uow, record) -> {
            events.add("write:" + record.operation().code());
            written.add(record);
        });
    }

    private static TransactionTemplate transactions(List<String> events) {
        return new TransactionTemplate(new RecordingTransactions(events));
    }

    @SuppressWarnings("unchecked")
    private static SessionStore<Connection> sessionsAnswering(Session session) {
        return (SessionStore<Connection>) Proxy.newProxyInstance(SessionStore.class.getClassLoader(),
                new Class<?>[] {SessionStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findLive" -> Optional.of(session);
                    case "touch" -> true;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static RoleAssignmentStore<Connection> roles(Set<RoleName> roles, AtomicInteger roleReads) {
        return (RoleAssignmentStore<Connection>) Proxy.newProxyInstance(RoleAssignmentStore.class.getClassLoader(),
                new Class<?>[] {RoleAssignmentStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("liveRolesOf")) {
                        roleReads.incrementAndGet();
                        return roles;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A transaction manager that manages nothing and records each begin and its end, in order. */
    private static final class RecordingTransactions extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;
        private final transient List<String> events;

        RecordingTransactions(List<String> events) {
            this.events = events;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback");
        }
    }

    /** One connection that answers nothing, handed to {@code DataSourceUtils}. */
    private static final class OneConnection implements javax.sql.DataSource {

        private final Connection connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    Class<?> returned = method.getReturnType();
                    if (returned == boolean.class) {
                        return false;
                    }
                    return returned.isPrimitive() && returned != void.class ? 0 : null;
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
