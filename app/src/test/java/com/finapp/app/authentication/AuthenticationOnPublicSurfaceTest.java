package com.finapp.app.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.finapp.app.surface.RequestSurfaces;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.platform.api.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
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
        SignInWorld world = new SignInWorld();
        SignInWorld.Person operator = world.staff();
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
        assertThat(world.refusals("public", "credential_proven")).as("the phished password is counted as one").isEqualTo(1.0);
        assertThat(world.refusals("public", "credential_unproven")).isZero();
        assertThat(world.roleReads).hasValue(1);
    }

    @Test
    @DisplayName("a right password on an outdated credential: staff on the public surface cost one derivation and rewrite nothing, so a clock cannot tell; the operator surface's sign-in upgrades it")
    void anOutdatedCredentialIsNotUpgradedFromThePublicSurface() {
        SignInWorld world = new SignInWorld(new DerivationParameters(2048, 1, 1));
        SignInWorld.Person operator = world.staff();
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
        SignInWorld world = new SignInWorld();
        SignInWorld.Person operator = world.staff();
        for (int attempt = 0; attempt < LockoutPolicy.current().threshold() + 3; attempt++) {
            assertThat(world.signIn(operator, "not the password", Surface.PUBLIC)).isEmpty();
        }
        assertThat(world.statementsNaming(LOCKOUT_TABLE))
                .as("past the threshold and beyond, not one statement reached the lockout: nobody on the internet can lock out a named operator")
                .isEmpty();
        assertThat(world.written).filteredOn(record -> record.operation() == IdentityAuditAction.PUBLIC_SURFACE_REFUSED)
                .allSatisfy(record -> assertThat(record.changeSummary()).hasValueSatisfying(
                        summary -> assertThat(summary).endsWith("credential=unproven")));
        assertThat(world.refusals("public", "credential_unproven")).isEqualTo(LockoutPolicy.current().threshold() + 3.0);
        assertThat(world.refusals("public", "credential_proven")).as("no wrong password is counted as a phished one").isZero();
    }

    @Test
    @DisplayName("a customer on the public surface is served, and a customer's wrong password is still counted against the lockout (T1 unchanged)")
    void aCustomerIsServedAndCounted() {
        SignInWorld world = new SignInWorld();
        SignInWorld.Person customer = world.customer();
        assertThat(world.signIn(customer, PASSWORD, Surface.PUBLIC)).hasValueSatisfying(
                session -> assertThat(session.assurance()).isEqualTo(AssuranceLevel.PASSWORD.name()));
        assertThat(world.sessionsIssued).hasValue(1);
        assertThat(world.roleReads).as("one read on every public attempt, whoever it names").hasValue(1);

        SignInWorld wrong = new SignInWorld();
        SignInWorld.Person another = wrong.customer();
        assertThat(wrong.signIn(another, "not the password", Surface.PUBLIC)).isEmpty();
        assertThat(wrong.statementsNaming(LOCKOUT_TABLE)).as("the failure is counted").hasSize(1);
        assertThat(wrong.statementsNaming(LOCKOUT_TABLE).get(0)).startsWith("INSERT INTO identity.authentication_failure");
        assertThat(wrong.derivations()).isEqualTo(1);
        assertThat(wrong.operations()).containsExactly("identity.AuthenticationFailed");
    }

    @Test
    @DisplayName("an identifier nobody holds costs the same read on the public surface, and is counted where it would have been")
    void anUnknownIdentifierCostsTheSameRead() {
        SignInWorld world = new SignInWorld();
        LoginIdentifier nobody = new LoginIdentifier("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        assertThat(world.signIn(new SignInWorld.Person(IdentityId.next(IDS), nobody), PASSWORD, Surface.PUBLIC)).isEmpty();
        assertThat(world.roleReads).hasValue(1);
        assertThat(world.derivations()).isEqualTo(1);
        assertThat(world.statementsNaming(LOCKOUT_TABLE)).hasSize(1);
    }

    @Test
    @DisplayName("on the operator surface staff are served and their roles are not read; where nothing is deployed, the same")
    void staffAreServedOnTheOperatorSurface() {
        for (Surface admitting : List.of(Surface.OPERATOR, Surface.SINGLE_PORT)) {
            SignInWorld world = new SignInWorld();
            SignInWorld.Person operator = world.staff();
            assertThat(world.signIn(operator, PASSWORD, admitting)).as("%s", admitting).isPresent();
            assertThat(world.roleReads).as("%s pays nothing", admitting).hasValue(0);
            assertThat(world.operations()).containsExactly("identity.AuthenticationSucceeded");
        }
    }

    @Test
    @DisplayName("behind the edge, a sign-in with no usable marker refuses staff and is recorded unmarked")
    void anUnmarkedSignInRefusesStaff() {
        SignInWorld world = new SignInWorld();
        SignInWorld.Person operator = world.staff();
        assertThat(world.signIn(operator, PASSWORD, Surface.UNMARKED)).isEmpty();
        assertThat(world.statementsNaming(LOCKOUT_TABLE)).isEmpty();
        assertThat(world.written.get(1).changeSummary()).hasValueSatisfying(summary -> assertThat(summary).contains("surface=unmarked"));
        assertThat(world.refusals("unmarked", "credential_proven")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the controller hands the service the surface its own request carries: staff marked public refused 401, marked operator signed in")
    @SuppressWarnings("try") // The Scope is used for its close side effect.
    void theControllerPassesTheRequestsSurface() {
        SignInWorld world = new SignInWorld();
        SignInWorld.Person operator = world.staff();
        AuthenticationController controller = new AuthenticationController(world.service(), new RequestSurfaces(false));
        AuthenticationRequest attempt = new AuthenticationRequest(operator.login().value(), Sensitive.of(PASSWORD));
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            assertThatThrownBy(() -> controller.authenticate(attempt, marked("public"))).isInstanceOfSatisfying(ApiException.class,
                    refused -> assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.AUTHENTICATION_FAILED));
            assertThat(world.written.get(1).changeSummary()).hasValueSatisfying(summary -> assertThat(summary).contains("surface=public"));
            assertThat(controller.authenticate(attempt, marked("operator"))).as("the same staff member, the operator edge's request")
                    .isNotNull();
        }
        assertThat(world.sessionsIssued).hasValue(1);
    }

    private static MockHttpServletRequest marked(String surface) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/authentications");
        request.addHeader(RequestSurfaces.HEADER, surface);
        return request;
    }
}
