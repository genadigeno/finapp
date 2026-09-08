package com.finapp.app.registration;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.CredentialAlgorithm;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.IdentityRegistration;
import com.finapp.identity.JdbcCredentialStore;
import com.finapp.identity.PasswordDeriver;
import com.finapp.identity.RawPassword;
import com.finapp.party.PartyRegistration;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A registration costs one derivation whether the login identifier is free or taken (`P1-TSK-026`).
 *
 * <h2>What this actually protects, stated precisely</h2>
 *
 * <p>The backlog calls this an enumeration control, and that is <strong>weaker than it reads</strong>
 * - which is worth saying rather than repeating. A successful registration answers {@code 201} and a
 * collision answers {@code 422}, in one round trip, so the two are already distinguishable and
 * necessarily so: an endpoint that claims a name must tell you when the name is taken. Equalising
 * the work is defence in depth here, not the control. {@code P1-TSK-006}'s real property is the
 * narrower one it asserts - a collision is indistinguishable from <em>any other refusal</em>.
 *
 * <p>The property this test does carry weight for is <strong>operational</strong>. Argon2id costs
 * ~46 ms of CPU and ~19 MiB by design (ADR-0032). {@code RegistrationService} derives
 * <em>before</em> opening the transaction, so that cost is never paid while holding one of eight
 * pooled connections (`P1-TSK-004`); moving it inside would turn a registration flood into
 * connection-timeout errors that point at a database which is perfectly healthy.
 *
 * <p>Counting is what makes both claims deterministic. {@code P1-TSK-008} recorded the reason: a
 * wall-clock assertion measures the machine, and a counter measures whether the expensive path ran.
 */
@Tag("database")
@SpringBootTest
class RegistrationCostsTheSameDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    /** Deliberately far below policy: this measures whether work happened, not how much. */
    private static final DerivationParameters WEAK = new DerivationParameters(1024, 1, 1);

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private IdempotentExecutor executor;
    @Autowired private PartyRegistration partyRegistration;

    @Test
    @DisplayName("a free login identifier and a taken one each cost exactly one derivation")
    void bothPathsDoTheSameWork() {
        String taken = someLogin();
        CountingDeriver first = new CountingDeriver(new Argon2PasswordDeriver(WEAK));
        assertThat(registerInAFlow(first, taken).accepted()).isTrue();
        assertThat(first.derivations()).isEqualTo(1);

        CountingDeriver collision = new CountingDeriver(new Argon2PasswordDeriver(WEAK));
        assertThat(registerInAFlow(collision, taken).accepted())
                .as("the identifier is taken, so this is refused")
                .isFalse();
        assertThat(collision.derivations())
                .as("a refusal that skipped the derivation would be readable from a clock")
                .isEqualTo(1);

        CountingDeriver free = new CountingDeriver(new Argon2PasswordDeriver(WEAK));
        assertThat(registerInAFlow(free, someLogin()).accepted()).isTrue();
        assertThat(free.derivations()).isEqualTo(1);
    }

    /**
     * The derivation happens before the transaction opens, asserted rather than commented.
     *
     * <p>The deriver reports whether a transaction was active when it was called. A mutation that
     * moves {@code prepare} into the command - which is the natural place a later author would put
     * it, right beside the insert it feeds - is caught here and nowhere else: every other assertion
     * in this class would still see exactly one derivation.
     */
    @Test
    @DisplayName("the derivation runs outside the transaction, never while holding a connection")
    void theDerivationIsOutsideTheTransaction() {
        CountingDeriver counting = new CountingDeriver(new Argon2PasswordDeriver(WEAK));

        assertThat(registerInAFlow(counting, someLogin()).accepted()).isTrue();

        assertThat(counting.derivedInsideATransaction())
                .as(
                        "Argon2id while holding one of eight pooled connections is how a"
                                + " registration flood becomes a database incident")
                .isFalse();
    }

    // -----------------------------------------------------------------

    /**
     * Runs one registration inside a correlation scope, as a request would.
     *
     * <p>The scope is normally established by {@code CorrelationFilter}; calling the service
     * directly has none, and the audit writer refuses to record an action it cannot join to a flow
     * rather than fabricating an identifier (`P0-TSK-014`).
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect.
    private RegistrationService.Outcome registerInAFlow(PasswordDeriver deriver, String login) {
        try (CorrelationContext.Scope ignored =
                CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return serviceWith(deriver)
                    .register(
                            new RegistrationRequest(login, "Ada Lovelace", Sensitive.of(PASSWORD)),
                            UUID.randomUUID().toString());
        }
    }

    private RegistrationService serviceWith(PasswordDeriver deriver) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new RegistrationService(
                executor,
                partyRegistration,
                new IdentityRegistration(
                        IDS,
                        CLOCK,
                        new JdbcAuditWriter(),
                        new JdbcOutboxWriter(),
                        new JdbcCredentialStore(),
                        deriver),
                template,
                dataSource,
                new SimpleMeterRegistry());
    }

    /**
     * Counts derivations, and notices whether one happened inside a transaction.
     *
     * <p>Counts {@code derive} rather than {@code matches}, which is the opposite of
     * {@code AuthenticationCostsTheSameDatabaseTest}: registration never verifies anything, and its
     * whole cost is the one derivation.
     */
    private final class CountingDeriver implements PasswordDeriver {

        private final PasswordDeriver delegate;
        private final AtomicInteger derivations = new AtomicInteger();
        private volatile boolean insideATransaction;

        CountingDeriver(PasswordDeriver delegate) {
            this.delegate = delegate;
        }

        int derivations() {
            return derivations.get();
        }

        boolean derivedInsideATransaction() {
            return insideATransaction;
        }

        @Override
        public Sensitive<String> derive(RawPassword password) {
            derivations.incrementAndGet();
            if (org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()) {
                insideATransaction = true;
            }
            return delegate.derive(password);
        }

        @Override
        public boolean matches(RawPassword password, Sensitive<String> credentialDerivation) {
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

    private static String someLogin() {
        return "ada." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
