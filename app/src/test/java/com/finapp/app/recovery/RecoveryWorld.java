package com.finapp.app.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.surface.Surface;
import com.finapp.app.surface.SurfaceRefusalMeters;
import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.Authorization;
import com.finapp.identity.ContactChannelService;
import com.finapp.identity.ContactChannelStore;
import com.finapp.identity.CredentialStore;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.IdentityAuditAction;
import com.finapp.identity.IdentityId;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.RawPassword;
import com.finapp.identity.RecoveryRequestId;
import com.finapp.identity.RecoveryRequestStore;
import com.finapp.identity.RecoveryService;
import com.finapp.identity.RoleAssignmentStore;
import com.finapp.identity.SessionStore;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The recovery service's collaborators with no database (`P15-TSK-042`): a recovery store recording every call, stood-in roles, and every
 * record written. Built here and not in the test class, so a hermetic suite names no {@code DataSource}, the taxonomy's database signature
 * ({@code SteppedUpDoor}'s reason).
 */
@SuppressWarnings("try") // The Scope is used for its close side effect.
final class RecoveryWorld {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T09:00:00Z"), ZoneOffset.UTC);
    static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    final IdentityId holder = IdentityId.next(IDS);
    final List<String> recoveryCalls = new ArrayList<>();
    final List<AuditRecord> written = new ArrayList<>();
    final AtomicInteger roleReads = new AtomicInteger();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final boolean staff;
    private final RecoveryApplicationService service;

    RecoveryWorld(boolean staff) {
        this.staff = staff;
        CredentialStore<Connection> noCredentials = unused(CredentialStore.class);
        SessionStore<Connection> noSessions = unused(SessionStore.class);
        ContactChannelStore<Connection> noChannels = unused(ContactChannelStore.class);
        RecoveryService recoveries = new RecoveryService(recoveryStore(), noCredentials, noSessions,
                new Argon2PasswordDeriver(new DerivationParameters(1024, 1, 1)), IDS,
                new SecureRandom(), (uow, record) -> written.add(record), (uow, envelope, payload, mediaType) -> { });
        ContactChannelService channels = new ContactChannelService(noChannels, IDS, CLOCK,
                new SecureRandom(), (uow, record) -> written.add(record));
        service = new RecoveryApplicationService(recoveries, channels, new TransactionTemplate(new NoTransactions()),
                new NoConnections(), new SimpleMeterRegistry(),
                new Authorization(roles(), IDS, CLOCK, (uow, record) -> written.add(record)),
                new SurfaceRefusalMeters(registry));
    }

    void initiate(Surface surface) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            service.initiate(new LoginIdentifier("someone"), surface);
        }
    }

    boolean complete(Surface surface) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return service.complete(RecoveryRequestId.next(IDS), Sensitive.of("a-token"),
                    RawPassword.of("a new password of length"), surface);
        }
    }

    double refusals(String type, String kind) {
        return registry.get("finapp.identity.surface.refusal").tag("type", type).tag("kind", kind).tag("outcome", "refused").counter()
                .count();
    }

    /** The service, for a controller built in front of it. */
    RecoveryApplicationService service() {
        return service;
    }

    /** Runs {@code work} inside a correlation scope, as the correlation filter would. */
    void inAFlow(Runnable work) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            work.run();
        }
    }

    @SuppressWarnings("unchecked")
    private RecoveryRequestStore<Connection> recoveryStore() {
        return (RecoveryRequestStore<Connection>) Proxy.newProxyInstance(RecoveryRequestStore.class.getClassLoader(),
                new Class<?>[] {RecoveryRequestStore.class}, (proxy, method, args) -> {
                    recoveryCalls.add(method.getName());
                    return switch (method.getName()) {
                        case "cancelLiveFor" -> 0;
                        case "initiate", "consume" -> Optional.empty();
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    @SuppressWarnings("unchecked")
    private RoleAssignmentStore<Connection> roles() {
        return (RoleAssignmentStore<Connection>) Proxy.newProxyInstance(RoleAssignmentStore.class.getClassLoader(),
                new Class<?>[] {RoleAssignmentStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "liveHolderByLogin", "liveHolderOfRecovery" -> {
                        roleReads.incrementAndGet();
                        yield staff ? Optional.of(holder) : Optional.empty();
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A store nothing here may touch: any call is a failure. Typed by the caller's declaration. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> T unused(Class type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            throw new UnsupportedOperationException(type.getSimpleName() + "." + method.getName());
        });
    }

    /** A transaction manager that manages nothing. */
    private static final class NoTransactions extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }

    /** A source whose one connection answers nothing: every statement here goes through a stood-in store. */
    private static final class NoConnections implements javax.sql.DataSource {

        private final Connection connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);

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
