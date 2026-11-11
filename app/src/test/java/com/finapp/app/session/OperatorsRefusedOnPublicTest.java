package com.finapp.app.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.credential.CredentialController;
import com.finapp.app.mfa.MfaChallengeController;
import com.finapp.app.mfa.MfaController;
import com.finapp.app.recovery.ContactChannelController;
import com.finapp.app.surface.OperatorsRefusedOnPublic;
import com.finapp.app.surface.RequestSurfaces;
import com.finapp.app.surface.SurfaceRefusalMeters;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityAuditAction;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.identity.Session;
import com.finapp.identity.SessionId;
import com.finapp.identity.SessionStatus;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.objenesis.SpringObjenesis;
import org.springframework.web.method.HandlerMethod;

/**
 * Staff refused at every session-bearing door on the public surface, by the real session interceptor (`P15-TSK-042`;
 * {@code THREAT_MODEL.md} T1, T3, T31).
 *
 * <p>Each door is the real controller's handler, as the dispatcher would hand it over; behind the interceptor a session at
 * {@code PASSWORD} and stated roles, with every transaction and audit record captured in order. The handler is never invoked,
 * which is the point: a refused request reaches nothing that counts a code or a password, rotates a session, begins or
 * confirms a factor, or adds a channel. What a real session row and a real {@code platform.audit_record} do is
 * {@code OperatorsRefusedOnPublicDatabaseTest}'s, owed.
 */
@DisplayName("staff are refused at the session doors on the public surface, before anything counts (P15-TSK-042)")
class OperatorsRefusedOnPublicTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final RequestSurfaces DEPLOYED = new RequestSurfaces(false);
    private static final RequestSurfaces NOTHING_DEPLOYED = new RequestSurfaces(true);
    private static final Set<RoleName> STAFF = Set.of(RoleName.LEDGER_OPERATOR);

    /** Every session-bearing door, read from the controllers: each handler declaring the door and needing a session. */
    private static List<Method> sessionDoors() {
        List<Method> doors = new ArrayList<>();
        for (Class<?> controller : List.of(MfaChallengeController.class, MfaController.class, CredentialController.class,
                ContactChannelController.class)) {
            Arrays.stream(controller.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(OperatorsRefusedOnPublic.class))
                    .filter(method -> !method.isAnnotationPresent(Unauthenticated.class))
                    .forEach(doors::add);
        }
        return doors;
    }

    @Test
    @DisplayName("the doors are the five that strengthen a session or what proves it: the step-up, enrolment, its confirmation, the password, a recovery channel")
    void theDoorsAreTheFive() {
        assertThat(sessionDoors()).extracting(method -> method.getAnnotation(OperatorsRefusedOnPublic.class).value().name())
                .containsExactlyInAnyOrder("MFA_CHALLENGE", "MFA_ENROLMENT", "MFA_CONFIRMATION", "CREDENTIAL_CHANGE", "CHANNEL_ADDITION");
    }

    @Test
    @DisplayName("a role-holder on the public surface is refused 401 identity.AuthenticationFailed at each, its record committed first, typed EMPLOYEE, and the handler never runs")
    void staffAreRefusedOnThePublicSurface() {
        for (Method door : sessionDoors()) {
            Knock knock = new Knock(door, STAFF, DEPLOYED).marked("public");
            assertThatThrownBy(knock::send).as("%s", door.getName()).isInstanceOfSatisfying(ApiException.class,
                    refused -> assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.AUTHENTICATION_FAILED));
            String operation = door.getAnnotation(OperatorsRefusedOnPublic.class).value().operation();
            assertThat(knock.written).as("%s", door.getName()).singleElement().satisfies(record -> {
                assertThat(record.operation()).isEqualTo(IdentityAuditAction.PUBLIC_SURFACE_REFUSED);
                assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
                assertThat(record.actor().type()).isEqualTo(ActorType.EMPLOYEE);
                assertThat(record.actor().id()).isEqualTo(knock.identity.value().toString());
                assertThat(record.targetId()).isEqualTo(knock.identity.value().toString());
                assertThat(record.changeSummary()).contains("operation=" + operation + " surface=public");
                assertThat(record.changeSummary().orElseThrow()).doesNotContain(Knock.TOKEN).doesNotContain("Bearer");
            });
            assertThat(knock.events).as("%s: the roles with the session, then the record in its own committed transaction", door.getName())
                    .containsExactly("begin", "commit", "begin", "write:identity.PublicSurfaceRefused", "commit");
            assertThat(knock.roleReads).hasValue(1);
            assertThat(knock.request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION))
                    .as("%s: no session is handed to the handler", door.getName()).isNull();
            assertThat(knock.refusals("public")).isEqualTo(1.0);
            assertThat(SecurityContext.current()).as("the thread is left as it was found").isEmpty();
        }
    }

    @Test
    @DisplayName("a customer on the public surface is served at each, one read of its roles and nothing written")
    void aCustomerIsServed() {
        for (Method door : sessionDoors()) {
            Knock knock = new Knock(door, Set.of(), DEPLOYED).marked("public");
            assertThat(knock.send()).as("%s", door.getName()).isTrue();
            assertThat(knock.written).isEmpty();
            assertThat(knock.roleReads).hasValue(1);
            assertThat(knock.actorWhileHandled).as("a customer's own act").isEqualTo(ActorType.CUSTOMER);
        }
    }

    @Test
    @DisplayName("on the operator surface staff are served at each, and their roles are not read: that surface pays nothing")
    void staffAreServedOnTheOperatorSurface() {
        for (Method door : sessionDoors()) {
            Knock knock = new Knock(door, STAFF, DEPLOYED).marked("operator");
            assertThat(knock.send()).as("%s", door.getName()).isTrue();
            assertThat(knock.written).isEmpty();
            assertThat(knock.roleReads).hasValue(0);
            assertThat(knock.actorWhileHandled).as("as before: a door is a person's own act").isEqualTo(ActorType.CUSTOMER);
        }
    }

    @Test
    @DisplayName("behind the edge, a request with no usable marker refuses staff and is recorded unmarked: missing, a forged list, two values")
    void anUnmarkedRequestRefusesStaff() {
        for (Method door : sessionDoors()) {
            for (Knock knock : List.of(new Knock(door, STAFF, DEPLOYED), new Knock(door, STAFF, DEPLOYED).marked("operator, public"),
                    new Knock(door, STAFF, DEPLOYED).marked("public").marked("operator"))) {
                assertThatThrownBy(knock::send).as("%s", door.getName()).isInstanceOf(ApiException.class);
                assertThat(knock.written).singleElement()
                        .satisfies(record -> assertThat(record.changeSummary()).hasValueSatisfying(
                                summary -> assertThat(summary).contains("surface=unmarked")));
                assertThat(knock.refusals("unmarked")).isEqualTo(1.0);
            }
        }
    }

    @Test
    @DisplayName("where nothing is deployed, staff are served without a marker, as before the edge")
    void theSinglePortServesStaff() {
        for (Method door : sessionDoors()) {
            Knock knock = new Knock(door, STAFF, NOTHING_DEPLOYED);
            assertThat(knock.send()).as("%s", door.getName()).isTrue();
            assertThat(knock.roleReads).hasValue(0);
        }
    }

    @Test
    @DisplayName("a refusal whose record cannot be written fails as the record's failure, never a 401 the audit trail lacks, and counts nothing")
    void aRefusalWithoutItsRecordIsAFailure() {
        for (Method door : sessionDoors()) {
            Knock knock = new Knock(door, STAFF, DEPLOYED).marked("public").whoseAuditFails();
            assertThatThrownBy(knock::send).as("%s", door.getName())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("audit store is unreachable");
            assertThat(knock.refusals("public")).isZero();
        }
    }

    @Test
    @DisplayName("a session route that is not a door serves staff on the public surface and reads no roles: the session list, a customer's own")
    void aRouteThatIsNotADoorIsUntouched() throws NoSuchMethodException {
        Method list = SessionController.class.getDeclaredMethod("listSessions", jakarta.servlet.http.HttpServletRequest.class);
        Knock knock = new Knock(list, STAFF, DEPLOYED).marked("public");
        assertThat(knock.send()).isTrue();
        assertThat(knock.roleReads).hasValue(0);
        assertThat(knock.written).isEmpty();
    }

    // -----------------------------------------------------------------

    /** One request at one real door: its marker, its session's roles, what it wrote and what was counted. */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    private static final class Knock {

        static final String TOKEN = "a-token-of-any-shape";

        final IdentityId identity = IdentityId.next(IDS);
        final AtomicInteger roleReads = new AtomicInteger();
        final List<String> events = new ArrayList<>();
        final List<AuditRecord> written = new ArrayList<>();
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/a-door");
        ActorType actorWhileHandled;
        private final Method door;
        private final Set<RoleName> roles;
        private final RequestSurfaces surfaces;
        private boolean auditFails;

        Knock(Method door, Set<RoleName> roles, RequestSurfaces surfaces) {
            this.door = door;
            this.roles = roles;
            this.surfaces = surfaces;
        }

        Knock marked(String word) {
            request.addHeader(RequestSurfaces.HEADER, word);
            return this;
        }

        Knock whoseAuditFails() {
            auditFails = true;
            return this;
        }

        double refusals(String kind) {
            String type = door.isAnnotationPresent(OperatorsRefusedOnPublic.class)
                    ? door.getAnnotation(OperatorsRefusedOnPublic.class).value().tag() : "none";
            var counter = registry.find("finapp.identity.surface.refusal").tag("type", type).tag("kind", kind).counter();
            return counter == null ? 0.0 : counter.count();
        }

        boolean send() {
            Object controller = new SpringObjenesis().newInstance(door.getDeclaringClass());
            HandlerMethod handler = new HandlerMethod(controller, door);
            Instant now = CLOCK.instant();
            Session session = Session.rehydrate(SessionId.next(IDS), identity, Sensitive.of("hash"), AssuranceLevel.PASSWORD,
                    SessionStatus.ACTIVE, now, now.plusSeconds(600), now.plusSeconds(3600), "test", null);
            SurfaceRefusalMeters meters = new SurfaceRefusalMeters(registry);
            SessionAuthenticationInterceptor interceptor = auditFails
                    ? InterceptorHarness.interceptorWhoseAuditFails(session, roles, surfaces, meters, IDS, CLOCK)
                    : InterceptorHarness.interceptor(session, roles, roleReads, events, written, IDS, CLOCK, surfaces, meters);
            request.addHeader("Authorization", "Bearer " + TOKEN);
            MockHttpServletResponse response = new MockHttpServletResponse();
            try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
                boolean through = interceptor.preHandle(request, response, handler);
                actorWhileHandled = SecurityContext.current().map(actor -> actor.type()).orElse(null);
                return through;
            } finally {
                interceptor.afterCompletion(request, response, handler, null);
            }
        }
    }
}
