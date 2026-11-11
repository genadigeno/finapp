package com.finapp.app.ledger;

import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleAssignmentStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.Session;
import com.finapp.identity.SessionId;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStatus;
import com.finapp.identity.SessionStore;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.springframework.objenesis.SpringObjenesis;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.method.HandlerMethod;

/**
 * The real {@link SessionAuthenticationInterceptor} in front of one handler, with no database: a session at a stated assurance, holding stated roles, stood in behind it (`P14-TSK-022`). Built here and not in the test class, so a hermetic suite names no {@code DataSource}, the taxonomy's database signature
 * ({@code BnplPayoutWorld}'s reason). What it proves is what the refusal is and what it runs before; what a real session row does is {@code AdjustmentEndpointDatabaseTest}'s, owed.
 */
@SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
final class SteppedUpDoor {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private SteppedUpDoor() {}

    /** Knocks at {@code method}: {@code true} when the interceptor lets the request through, or whatever it throws. Its controller is an empty instance: a handler is never invoked here. */
    static boolean knock(Method method, AssuranceLevel assurance, Set<RoleName> roles) {
        Object controller = new SpringObjenesis().newInstance(method.getDeclaringClass());
        Instant now = CLOCK.instant();
        Session session = Session.rehydrate(SessionId.next(IDS), IdentityId.next(IDS), Sensitive.of("hash"), assurance, SessionStatus.ACTIVE, now, now.plusSeconds(600), now.plusSeconds(3600), "test", null);
        Connection connection = unanswering(Connection.class);
        SessionAuthenticationInterceptor interceptor = new SessionAuthenticationInterceptor(sessionsAnswering(session), new TransactionTemplate(new NoTransactions()), new OneConnection(connection), SessionPolicy.current(),
                new Authorization(rolesAnswering(roles), IDS, CLOCK, (uow, record) -> { }),
                // Where nothing is deployed: these doors are judged by their step-up, not by the surface (P15-TSK-042).
                new com.finapp.app.surface.RequestSurfaces(true),
                new com.finapp.app.surface.SurfaceRefusalMeters(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/knock");
        request.addHeader("Authorization", "Bearer a-token-of-any-shape");
        MockHttpServletResponse response = new MockHttpServletResponse();
        // The correlation filter's scope: a denial is audited inside it.
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return interceptor.preHandle(request, response, new HandlerMethod(controller, method));
        } finally {
            interceptor.afterCompletion(request, response, new HandlerMethod(controller, method), null);
        }
    }

    @SuppressWarnings("unchecked")
    private static SessionStore<Connection> sessionsAnswering(Session session) {
        return (SessionStore<Connection>) Proxy.newProxyInstance(SessionStore.class.getClassLoader(), new Class<?>[] {SessionStore.class}, (proxy, method, args) -> switch (method.getName()) {
            case "findLive" -> Optional.of(session);
            case "touch" -> true;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static RoleAssignmentStore<Connection> rolesAnswering(Set<RoleName> roles) {
        return (RoleAssignmentStore<Connection>) Proxy.newProxyInstance(RoleAssignmentStore.class.getClassLoader(), new Class<?>[] {RoleAssignmentStore.class}, (proxy, method, args) -> {
            if (method.getName().equals("liveRolesOf")) {
                return roles;
            }
            throw new UnsupportedOperationException(method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T unanswering(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            Class<?> returned = method.getReturnType();
            if (returned == boolean.class) {
                return false;
            }
            return returned.isPrimitive() && returned != void.class ? 0 : null;
        });
    }

    /** A transaction manager with nothing to manage. */
    private static final class NoTransactions extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }

    /** One connection, handed to {@code DataSourceUtils}. */
    private record OneConnection(Connection connection) implements javax.sql.DataSource {

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
