package com.finapp.app.authentication;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.surface.Surface;
import com.finapp.app.surface.SurfaceRefusalMeters;
import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.AuthenticationThrottle;
import com.finapp.identity.Authorization;
import com.finapp.identity.Credential;
import com.finapp.identity.CredentialAlgorithm;
import com.finapp.identity.CredentialStore;
import com.finapp.identity.CredentialType;
import com.finapp.identity.CredentialVerifier;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.Identity;
import com.finapp.identity.IdentityAuditAction;
import com.finapp.identity.IdentityAuthentication;
import com.finapp.identity.IdentityId;
import com.finapp.identity.IdentityStatus;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.LockoutPolicy;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.PasswordDeriver;
import com.finapp.identity.RawPassword;
import com.finapp.identity.RoleAssignmentStore;
import com.finapp.identity.Session;
import com.finapp.identity.SessionId;
import com.finapp.identity.SessionIssue;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStatus;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The authentication service's collaborators with no database (`P15-TSK-042`): people, their roles and credentials stood in, and
 * every statement the throttle sends, every record and event written and every credential rewrite captured. Built here and not in
 * the test class, so a hermetic suite names no {@code DataSource}, the taxonomy's database signature ({@code SteppedUpDoor}'s reason).
 */
@SuppressWarnings("try") // The Scope is used for its close side effect.
final class SignInWorld {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T09:00:00Z"), ZoneOffset.UTC);
    static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    static final DerivationParameters WEAK = new DerivationParameters(1024, 1, 1);
    static final String PASSWORD = "correct horse battery staple";


    record Person(IdentityId id, LoginIdentifier login) {}

    final Map<LoginIdentifier, Person> people = new java.util.HashMap<>();
    final Map<IdentityId, Credential> credentials = new java.util.HashMap<>();
    final java.util.Set<LoginIdentifier> staffLogins = new java.util.HashSet<>();
    final List<String> statements = new ArrayList<>();
    final List<AuditRecord> written = new ArrayList<>();
    final List<String> events = new ArrayList<>();
    final List<String> credentialWrites = new ArrayList<>();
    final AtomicInteger roleReads = new AtomicInteger();
    final AtomicInteger sessionsIssued = new AtomicInteger();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Counting deriver;
    private final AuthenticationService service;

    SignInWorld() {
        this(WEAK);
    }

    /** @param policy the parameters a new credential is derived at: above {@code WEAK}, every person's credential is outdated */
    SignInWorld(DerivationParameters policy) {
        deriver = new Counting(new Argon2PasswordDeriver(policy));
        Connection connection = recordingConnection(statements);
        CredentialVerifier verifier = new CredentialVerifier(identities(), credentialStore(), deriver, IDS, CLOCK);
        deriver.reset();
        service = new AuthenticationService(
                verifier,
                new AuthenticationThrottle(LockoutPolicy.current(), IDS, CLOCK, (uow, record) -> written.add(record)),
                new IdentityAuthentication(IDS, CLOCK, (uow, record) -> written.add(record),
                        (uow, envelope, payload, mediaType) -> events.add("event")),
                new SessionIssue(sessions(), SessionPolicy.current(), IDS, CLOCK, new SecureRandom()),
                new TransactionTemplate(new NoTransactions()),
                new OneConnection(connection),
                new SimpleMeterRegistry(),
                new Authorization(roles(), IDS, CLOCK, (uow, record) -> written.add(record)),
                new SurfaceRefusalMeters(registry));
    }

    Person staff() {
        Person person = customer();
        staffLogins.add(person.login());
        return person;
    }

    Person customer() {
        Person person = new Person(IdentityId.next(IDS),
                new LoginIdentifier("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20)));
        people.put(person.login(), person);
        credentials.put(person.id(), Credential.forPassword(IDS, CLOCK, person.id(), CredentialType.PASSWORD,
                new Argon2PasswordDeriver(WEAK), RawPassword.of(PASSWORD)));
        return person;
    }

    Optional<AuthenticatedSession> signIn(Person person, String password, Surface surface) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return service.authenticate(new AuthenticationRequest(person.login().value(), Sensitive.of(password)), null, surface);
        }
    }

    int derivations() {
        return deriver.verifications.get();
    }

    /** New derivations: an upgraded credential's, which only a right password on an outdated one buys. */
    int rederivations() {
        return deriver.derivations.get();
    }

    List<String> statementsNaming(String table) {
        return statements.stream().map(String::strip).filter(sql -> sql.contains(table)).toList();
    }

    List<String> operations() {
        return written.stream().map(record -> record.operation().code()).toList();
    }

    /** Sign-in's refusals on {@code kind}, by whether the password was the identity's ({@code credential_proven}, {@code credential_unproven}). */
    double refusals(String kind, String outcome) {
        return registry.get("finapp.identity.surface.refusal").tag("type", "authentication").tag("kind", kind).tag("outcome", outcome)
                .counter().count();
    }

    /** The service, for a controller built in front of it. */
    AuthenticationService service() {
        return service;
    }

    @SuppressWarnings("unchecked")
    private IdentityStore<Connection> identities() {
        return (IdentityStore<Connection>) Proxy.newProxyInstance(IdentityStore.class.getClassLoader(),
                new Class<?>[] {IdentityStore.class}, (proxy, method, args) -> {
                    Optional<Person> person = switch (method.getName()) {
                        case "findByLoginIdentifier" -> Optional.ofNullable(people.get((LoginIdentifier) args[1]));
                        case "findById" -> people.values().stream().filter(found -> found.id().equals(args[1])).findFirst();
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                    return person.map(found -> Identity.rehydrate(found.id(), UUID.randomUUID(), found.login(),
                            IdentityStatus.ACTIVE, CLOCK.instant(), CLOCK.instant()));
                });
    }

    @SuppressWarnings("unchecked")
    private CredentialStore<Connection> credentialStore() {
        return (CredentialStore<Connection>) Proxy.newProxyInstance(CredentialStore.class.getClassLoader(),
                new Class<?>[] {CredentialStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findActive" -> Optional.ofNullable(credentials.get((IdentityId) args[1]));
                    case "supersede" -> {
                        credentialWrites.add("supersede");
                        yield true;
                    }
                    case "insert" -> {
                        credentialWrites.add("insert");
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private RoleAssignmentStore<Connection> roles() {
        return (RoleAssignmentStore<Connection>) Proxy.newProxyInstance(RoleAssignmentStore.class.getClassLoader(),
                new Class<?>[] {RoleAssignmentStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("liveHolderByLogin")) {
                        roleReads.incrementAndGet();
                        LoginIdentifier login = (LoginIdentifier) args[1];
                        return staffLogins.contains(login) ? Optional.of(people.get(login).id()) : Optional.empty();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private SessionStore<Connection> sessions() {
        return (SessionStore<Connection>) Proxy.newProxyInstance(SessionStore.class.getClassLoader(),
                new Class<?>[] {SessionStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("insert")) {
                        sessionsIssued.incrementAndGet();
                        Session.Draft draft = (Session.Draft) args[1];
                        Instant now = CLOCK.instant();
                        return Session.rehydrate(SessionId.next(IDS), draft.identityId(), draft.tokenHash(),
                                AssuranceLevel.PASSWORD, SessionStatus.ACTIVE, now, now.plusSeconds(600), now.plusSeconds(3600),
                                null, null);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }


    /** Counts verifications, as {@code AuthenticationCostsTheSameDatabaseTest} does: the constructor's dummy derivation is not one. */
    private static final class Counting implements PasswordDeriver {

        private final PasswordDeriver delegate;
        private final AtomicInteger verifications = new AtomicInteger();
        private final AtomicInteger derivations = new AtomicInteger();

        Counting(PasswordDeriver delegate) {
            this.delegate = delegate;
        }

        void reset() {
            verifications.set(0);
            derivations.set(0);
        }

        @Override
        public Sensitive<String> derive(RawPassword password) {
            derivations.incrementAndGet();
            return delegate.derive(password);
        }

        @Override
        public boolean matches(RawPassword password, Sensitive<String> credentialDerivation) {
            verifications.incrementAndGet();
            return delegate.matches(password, credentialDerivation);
        }

        @Override
        public CredentialAlgorithm algorithm() {
            return delegate.algorithm();
        }

        @Override
        public DerivationParameters currentParameters() {
            return delegate.currentParameters();
        }

        @Override
        public DerivationParameters parametersOf(Sensitive<String> credentialDerivation) {
            return delegate.parametersOf(credentialDerivation);
        }
    }

    /** A connection that answers every statement with nothing, in a transaction, recording each statement it is asked to prepare. */
    private static Connection recordingConnection(List<String> statements) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAutoCommit" -> false;
                    case "prepareStatement" -> {
                        statements.add((String) args[0]);
                        yield unanswering(PreparedStatement.class);
                    }
                    default -> defaultOf(method.getReturnType());
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T unanswering(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getName().equals("executeQuery")) {
                return unanswering(ResultSet.class);
            }
            return defaultOf(method.getReturnType());
        });
    }

    private static Object defaultOf(Class<?> returned) {
        if (returned == boolean.class) {
            return false;
        }
        if (returned == int.class || returned == long.class || returned == short.class || returned == byte.class) {
            return 0;
        }
        return null;
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

    /** One connection, handed to {@code DataSourceUtils}. */
    private static final class OneConnection implements javax.sql.DataSource {

        private final Connection connection;

        OneConnection(Connection connection) {
            this.connection = connection;
        }

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
