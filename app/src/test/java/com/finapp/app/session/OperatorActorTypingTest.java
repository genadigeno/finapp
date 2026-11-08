package com.finapp.app.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityAuditAction;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityId;
import com.finapp.identity.PermissionName;
import com.finapp.identity.RoleName;
import com.finapp.identity.Session;
import com.finapp.identity.SessionId;
import com.finapp.identity.SessionStatus;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Who is acting, decided per request from the server's record (`P15-TSK-021`; {@code THREAT_MODEL.md} T3, T10, T38;
 * the debt rows "every session actor is CUSTOMER" and "a refused step-up is not audited").
 *
 * <p>The real {@link SessionAuthenticationInterceptor} in front of declared handlers, with no database: a session at a
 * stated assurance, holding stated roles, stood in behind it; every audit record it writes and every transaction it
 * opens is captured in order. What a real session row and a real {@code platform.audit_record} do is
 * {@code DenyByDefaultDatabaseTest#theOperatorActorIsTyped}'s and {@code #aRefusedStepUpIsRecorded}'s, owed.
 */
@DisplayName("the operator actor and the refused step-up, at the session interceptor (P15-TSK-021)")
class OperatorActorTypingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String APPROVAL = "/v1/ledger/adjustments/{id}/approval";

    /** Handlers as the platform declares them: an operator act, a customer's act, and each behind a step-up. */
    static final class Doors {
        @RequiresPermission(PermissionName.LEDGER_ADJUST)
        public void operatorAct() {}

        @RequiresSession
        public void customerAct() {}

        @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
        @RequiresPermission(PermissionName.LEDGER_ADJUST)
        public void steppedUpOperatorAct() {}

        @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
        public void steppedUpCustomerAct() {}
    }

    @Test
    @DisplayName("an operator act by an identity holding a role is typed EMPLOYEE, from one read of its roles")
    void anOperatorActIsTypedEmployee() {
        Knock knock = new Knock(AssuranceLevel.PASSWORD, Set.of(RoleName.LEDGER_OPERATOR));
        assertThat(knock.at("operatorAct")).isTrue();
        assertThat(knock.actorWhileHandled).hasValueSatisfying(actor -> {
            assertThat(actor.type()).isEqualTo(ActorType.EMPLOYEE);
            assertThat(actor.id()).isEqualTo(knock.identity.value().toString());
        });
        assertThat(knock.roleReads).as("one read decides the population and the permission").hasValue(1);
        assertThat(knock.events).as("the roles were read in the session's own transaction").containsExactly("begin", "commit");
        assertThat(knock.written).as("a permitted act is audited by its operation, not here").isEmpty();
        assertThat(SecurityContext.current()).as("the thread is left as it was found").isEmpty();
    }

    @Test
    @DisplayName("a customer refused at an operator route is recorded CUSTOMER; staff refused beyond their role EMPLOYEE")
    void aRefusalIsTypedByWhoTried() {
        Knock customer = new Knock(AssuranceLevel.PASSWORD, Set.of());
        assertThatThrownBy(() -> customer.at("operatorAct")).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.FORBIDDEN));
        assertThat(customer.written).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(IdentityAuditAction.AUTHORIZATION_DENIED);
            assertThat(record.actor().type()).isEqualTo(ActorType.CUSTOMER);
        });

        Knock staff = new Knock(AssuranceLevel.PASSWORD, Set.of(RoleName.KYC_REVIEWER));
        assertThatThrownBy(() -> staff.at("operatorAct")).isInstanceOf(ApiException.class);
        assertThat(staff.written).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(IdentityAuditAction.AUTHORIZATION_DENIED);
            assertThat(record.actor().type()).isEqualTo(ActorType.EMPLOYEE);
        });
        assertThat(staff.roleReads).hasValue(1);
        assertThat(staff.events).as("the denial committed in its own transaction before the refusal")
                .containsExactly("begin", "commit", "begin", "write:identity.AuthorizationDenied", "commit");
        assertThat(SecurityContext.current()).isEmpty();
    }

    @Test
    @DisplayName("a role holder acting on their own account is a customer, and a customer route reads no roles")
    void aRoleHolderOnACustomerRouteIsACustomer() {
        Knock knock = new Knock(AssuranceLevel.PASSWORD, Set.of(RoleName.ADMINISTRATOR, RoleName.LEDGER_OPERATOR));
        assertThat(knock.at("customerAct")).isTrue();
        assertThat(knock.actorWhileHandled).hasValueSatisfying(actor -> assertThat(actor.type()).isEqualTo(ActorType.CUSTOMER));
        assertThat(knock.roleReads).as("the hot path pays nothing").hasValue(0);
    }

    @Test
    @DisplayName("a refused step-up at an operator act is recorded once, EMPLOYEE, before the permission is read, in its"
            + " own committed transaction, naming the route pattern and nothing else")
    void aRefusedStepUpIsRecorded() {
        Knock knock = new Knock(AssuranceLevel.PASSWORD, Set.of(RoleName.LEDGER_OPERATOR));
        assertThatThrownBy(() -> knock.at("steppedUpOperatorAct")).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED));
        assertThat(knock.written).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(IdentityAuditAction.STEP_UP_REFUSED);
            assertThat(record.actor()).isEqualTo(new Actor(knock.identity.value().toString(), ActorType.EMPLOYEE));
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.targetId()).isEqualTo(knock.identity.value().toString());
            assertThat(record.changeSummary()).contains("operation=POST " + APPROVAL);
            assertThat(record.changeSummary().orElseThrow())
                    .as("no session, code, factor or level detail")
                    .doesNotContain("PASSWORD", "MULTI_FACTOR", "Bearer", Knock.TOKEN, "/v1/ledger/adjustments/42");
        });
        assertThat(knock.events).containsExactly("begin", "commit", "begin", "write:identity.StepUpRefused", "commit");
        assertThat(knock.request.getAttribute(StepUpRefusals.RECORDED)).as("marked, so the error contract does not"
                + " record it again").isEqualTo(Boolean.TRUE);
        assertThat(SecurityContext.current()).isEmpty();
    }

    @Test
    @DisplayName("a refused step-up is recorded whatever the caller holds: a customer at a step-up door is recorded"
            + " CUSTOMER, and no denial is written beside it")
    void aRefusedStepUpPrecedesThePermission() {
        Knock operatorRouteNoRole = new Knock(AssuranceLevel.PASSWORD, Set.of());
        assertThatThrownBy(() -> operatorRouteNoRole.at("steppedUpOperatorAct")).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED));
        assertThat(operatorRouteNoRole.written).extracting(record -> record.operation().code())
                .containsExactly("identity.StepUpRefused");
        assertThat(operatorRouteNoRole.written.get(0).actor().type()).isEqualTo(ActorType.CUSTOMER);

        Knock customer = new Knock(AssuranceLevel.PASSWORD, Set.of(RoleName.ADMINISTRATOR));
        assertThatThrownBy(() -> customer.at("steppedUpCustomerAct")).isInstanceOf(ApiException.class);
        assertThat(customer.written).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(IdentityAuditAction.STEP_UP_REFUSED);
            assertThat(record.actor().type()).isEqualTo(ActorType.CUSTOMER);
        });
        assertThat(customer.roleReads).hasValue(0);
    }

    @Test
    @DisplayName("an assured session passes the step-up and writes nothing")
    void anAssuredSessionWritesNothing() {
        Knock knock = new Knock(AssuranceLevel.MULTI_FACTOR, Set.of(RoleName.LEDGER_OPERATOR));
        assertThat(knock.at("steppedUpOperatorAct")).isTrue();
        assertThat(knock.written).isEmpty();
        assertThat(knock.actorWhileHandled).hasValueSatisfying(actor -> assertThat(actor.type()).isEqualTo(ActorType.EMPLOYEE));
    }

    // -----------------------------------------------------------------

    /** One request at one handler: its session, its roles, and what it wrote. */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    private static final class Knock {

        static final String TOKEN = "a-token-of-any-shape";

        final IdentityId identity = IdentityId.next(IDS);
        final AtomicInteger roleReads = new AtomicInteger();
        final List<String> events = new ArrayList<>();
        final List<AuditRecord> written = new ArrayList<>();
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/ledger/adjustments/42/approval");
        Optional<Actor> actorWhileHandled = Optional.empty();
        private final AssuranceLevel assurance;
        private final Set<RoleName> roles;

        Knock(AssuranceLevel assurance, Set<RoleName> roles) {
            this.assurance = assurance;
            this.roles = roles;
        }

        boolean at(String handlerName) {
            Method method;
            try {
                method = Doors.class.getMethod(handlerName);
            } catch (NoSuchMethodException absent) {
                throw new IllegalArgumentException(handlerName, absent);
            }
            HandlerMethod handler = new HandlerMethod(new Doors(), method);
            Instant now = CLOCK.instant();
            Session session = Session.rehydrate(SessionId.next(IDS), identity, Sensitive.of("hash"), assurance,
                    SessionStatus.ACTIVE, now, now.plusSeconds(600), now.plusSeconds(3600), "test", null);
            SessionAuthenticationInterceptor interceptor =
                    InterceptorHarness.interceptor(session, roles, roleReads, events, written, IDS, CLOCK);
            request.addHeader("Authorization", "Bearer " + TOKEN);
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, APPROVAL);
            MockHttpServletResponse response = new MockHttpServletResponse();
            try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
                boolean through = interceptor.preHandle(request, response, handler);
                actorWhileHandled = SecurityContext.current();
                return through;
            } finally {
                interceptor.afterCompletion(request, response, handler, null);
            }
        }
    }
}
