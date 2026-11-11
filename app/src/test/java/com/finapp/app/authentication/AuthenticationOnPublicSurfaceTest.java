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
 * Sign-in refuses staff on the public surface with a wrong password's answer, after a wrong password's work, and touches nothing
 * the lockout keeps (`P15-TSK-042`; {@code THREAT_MODEL.md} T1, T3, T31; {@code INV-IDN-07}).
 *
 * <p>The real {@link AuthenticationService}, the real verifier, throttle, issuer and audit, over a connection that answers every
 * statement with nothing and records each one it was asked to prepare. So "the lockout is unmoved" is read from what was sent
 * to the database, not from a mock's say-so: on the refusal no statement names {@code identity.authentication_failure} at all,
 * neither the count, the lock's read nor its clearing. What a real row does is {@code OperatorsRefusedOnPublicDatabaseTest}'s and
 * {@code AuthenticationCostsTheSameDatabaseTest}'s, owed.
 */
@DisplayName("sign-in refuses staff on the public surface as a wrong password, and never counts it (P15-TSK-042)")
class AuthenticationOnPublicSurfaceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final DerivationParameters WEAK = new DerivationParameters(1024, 1, 1);
    private static final String PASSWORD = "correct horse battery staple";
    private static final String LOCKOUT_TABLE = "identity.authentication_failure";

    @Test
    @DisplayName("staff with their own password on the public surface: refused, one derivation, nothing counted, cleared or issued, and the record says the password was right")
    void staffWithTheirOwnPasswordAreRefused() {
        World world = new World();
        World.Person operator = world.staff();
        Optional<AuthenticatedSession> answer = world.signIn(operator, PASSWORD, Surface.PUBLIC);

        assertThat(answer).as("the wrong password's answer: nothing").isEmpty();
        assertThat(world.derivations()).as("a wrong password's work, and no more").isEqualTo(1);
        assertThat(world.statementsNaming(LOCKOUT_TABLE)).as("the lockout is neither counted, read nor cleared").isEmpty();
        assertThat(world.sessionsIssued).as("no session").hasValue(0);
        assertThat(world.operations()).containsExactly("identity.AuthenticationFailed", "identity.PublicSurfaceRefused");
        assertThat(world.events).as("the uniform event a wrong password emits, so nothing downstream can tell").containsExactly("event");
        AuditRecord refusal = world.written.get(1);
        assertThat(refusal.targetId()).isEqualTo(operator.id().value().toString());
        assertThat(refusal.actor().type()).as("no proven actor on this door").isEqualTo(ActorType.SYSTEM);
        assertThat(refusal.changeSummary()).contains("operation=POST /v1/authentications surface=public credential=proven");
        assertThat(refusal.changeSummary().orElseThrow()).doesNotContain(PASSWORD);
        assertThat(world.refusals("public")).isEqualTo(1.0);
        assertThat(world.roleReads).hasValue(1);
    }

    @Test
    @DisplayName("a right password on an outdated credential: staff on the public surface cost one derivation and rewrite nothing, so a clock cannot tell; the operator surface's sign-in upgrades it")
    void anOutdatedCredentialIsNotUpgradedFromThePublicSurface() {
        World world = new World(new DerivationParameters(2048, 1, 1));
        World.Person operator = world.staff();
        assertThat(world.signIn(operator, PASSWORD, Surface.PUBLIC)).isEmpty();
        assertThat(world.derivations()).isEqualTo(1);
        assertThat(world.rederivations()).as("no upgrade's derivation").isZero();
        assertThat(world.credentialWrites).isEmpty();
        assertThat(world.written.get(1).changeSummary()).hasValueSatisfying(summary -> assertThat(summary).endsWith("credential=proven"));

        assertThat(world.signIn(operator, PASSWORD, Surface.OPERATOR)).isPresent();
        assertThat(world.credentialWrites).as("the same password where staff are served upgrades it: the difference is real")
                .containsExactly("supersede", "insert");
        assertThat(world.rederivations()).isEqualTo(1);
    }

    @Test
    @DisplayName("staff with a wrong password on the public surface: refused the same, nothing counted, recorded unproven")
    void staffWithAWrongPasswordAreNotCounted() {
        World world = new World();
        World.Person operator = world.staff();
        for (int attempt = 0; attempt < LockoutPolicy.current().threshold() + 3; attempt++) {
            assertThat(world.signIn(operator, "not the password", Surface.PUBLIC)).isEmpty();
        }
        assertThat(world.statementsNaming(LOCKOUT_TABLE))
                .as("past the threshold and beyond, not one statement reached the lockout: nobody on the internet can lock out a named operator")
                .isEmpty();
        assertThat(world.written).filteredOn(record -> record.operation() == IdentityAuditAction.PUBLIC_SURFACE_REFUSED)
                .allSatisfy(record -> assertThat(record.changeSummary()).hasValueSatisfying(
                        summary -> assertThat(summary).endsWith("credential=unproven")));
    }

    @Test
    @DisplayName("a customer on the public surface is served, and a customer's wrong password is still counted against the lockout (T1 unchanged)")
    void aCustomerIsServedAndCounted() {
        World world = new World();
        World.Person customer = world.customer();
        assertThat(world.signIn(customer, PASSWORD, Surface.PUBLIC)).hasValueSatisfying(
                session -> assertThat(session.assurance()).isEqualTo(AssuranceLevel.PASSWORD.name()));
        assertThat(world.sessionsIssued).hasValue(1);
        assertThat(world.roleReads).as("one read on every public attempt, whoever it names").hasValue(1);

        World wrong = new World();
        World.Person another = wrong.customer();
        assertThat(wrong.signIn(another, "not the password", Surface.PUBLIC)).isEmpty();
        assertThat(wrong.statementsNaming(LOCKOUT_TABLE)).as("the failure is counted").hasSize(1);
        assertThat(wrong.statementsNaming(LOCKOUT_TABLE).get(0)).startsWith("INSERT INTO identity.authentication_failure");
        assertThat(wrong.derivations()).isEqualTo(1);
        assertThat(wrong.operations()).containsExactly("identity.AuthenticationFailed");
    }

    @Test
    @DisplayName("an identifier nobody holds costs the same read on the public surface, and is counted where it would have been")
    void anUnknownIdentifierCostsTheSameRead() {
        World world = new World();
        LoginIdentifier nobody = new LoginIdentifier("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        assertThat(world.signIn(new World.Person(IdentityId.next(IDS), nobody), PASSWORD, Surface.PUBLIC)).isEmpty();
        assertThat(world.roleReads).hasValue(1);
        assertThat(world.derivations()).isEqualTo(1);
        assertThat(world.statementsNaming(LOCKOUT_TABLE)).hasSize(1);
    }

    @Test
    @DisplayName("on the operator surface staff are served and their roles are not read; where nothing is deployed, the same")
    void staffAreServedOnTheOperatorSurface() {
        for (Surface admitting : List.of(Surface.OPERATOR, Surface.SINGLE_PORT)) {
            World world = new World();
            World.Person operator = world.staff();
            assertThat(world.signIn(operator, PASSWORD, admitting)).as("%s", admitting).isPresent();
            assertThat(world.roleReads).as("%s pays nothing", admitting).hasValue(0);
            assertThat(world.operations()).containsExactly("identity.AuthenticationSucceeded");
        }
    }

    @Test
    @DisplayName("behind the edge, a sign-in with no usable marker refuses staff and is recorded unmarked")
    void anUnmarkedSignInRefusesStaff() {
        World world = new World();
        World.Person operator = world.staff();
        assertThat(world.signIn(operator, PASSWORD, Surface.UNMARKED)).isEmpty();
        assertThat(world.statementsNaming(LOCKOUT_TABLE)).isEmpty();
        assertThat(world.written.get(1).changeSummary()).hasValueSatisfying(summary -> assertThat(summary).contains("surface=unmarked"));
        assertThat(world.refusals("unmarked")).isEqualTo(1.0);
    }

    // -----------------------------------------------------------------

    /** The service's collaborators with no database: people, their roles and credentials, and every statement and record captured. */
    @SuppressWarnings("try") // The Scope is used for its close side effect.
    private static final class World {

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

        World() {
            this(WEAK);
        }

        /** @param policy the parameters a new credential is derived at: above {@code WEAK}, every person's credential is outdated */
        World(DerivationParameters policy) {
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

        double refusals(String kind) {
            return registry.get("finapp.identity.surface.refusal").tag("type", "authentication").tag("kind", kind).counter().count();
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
