package com.finapp.app.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.customers.CustomerReadController;
import com.finapp.app.kyc.KycDocumentReadController;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityAuditAction;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.identity.Session;
import com.finapp.identity.SessionId;
import com.finapp.identity.SessionStatus;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.ActorType;
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
import org.springframework.web.servlet.HandlerMapping;

/**
 * Who may knock at the three doors of `P17-TSK-125` (the owner's D-25 decisions 1 and 2; {@code THREAT_MODEL.md} T3): the real
 * {@link SessionAuthenticationInterceptor} in front of the real handlers - a customer's name and country, a case's documents,
 * one document's content - with no database, a session at a stated assurance holding stated roles stood in behind it. The
 * negative authorization test of each door: a holder of every other read is refused {@code 403} and recorded, a customer is
 * refused, and a password-only session is refused its step-up before any permission is read. What a real session row does is
 * {@code DenyByDefaultDatabaseTest}'s shape, owed with the database tier (the owner's ruling of 2026-11-20).
 */
@DisplayName("the name-and-country read and the KYC document doors at the real session interceptor: who may knock, on what"
        + " session (P17-TSK-125)")
class KycDocumentAndIdentityDoorsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    /** The three doors: the handler, its route's pattern. */
    private enum Door {
        CUSTOMER_IDENTITY(CustomerReadController.class, "readCustomerIdentity", "/v1/operator/customers/{id}/identity"),
        CASE_DOCUMENTS(KycDocumentReadController.class, "listKycCaseDocuments", "/v1/operator/kyc/cases/{id}/documents"),
        DOCUMENT_CONTENT(KycDocumentReadController.class, "readKycDocumentContent", "/v1/operator/kyc/documents/{id}/content");

        final Class<?> controller;
        final String handler;
        final String pattern;

        Door(Class<?> controller, String handler, String pattern) {
            this.controller = controller;
            this.handler = handler;
            this.pattern = pattern;
        }
    }

    private static final Set<Door> DOCUMENT_DOORS = Set.of(Door.CASE_DOCUMENTS, Door.DOCUMENT_CONTENT);

    @Test
    @DisplayName("a KYC reviewer on a MULTI_FACTOR session passes both document doors; a support agent passes the name read")
    void eachDoorAdmitsItsHolder() {
        for (Door door : DOCUMENT_DOORS) {
            assertThat(new Knock(AssuranceLevel.MULTI_FACTOR, Set.of(RoleName.KYC_REVIEWER)).at(door)).as(door.name()).isTrue();
        }
        assertThat(new Knock(AssuranceLevel.MULTI_FACTOR, Set.of(RoleName.CUSTOMER_SUPPORT_AGENT)).at(Door.CUSTOMER_IDENTITY))
                .isTrue();
    }

    @Test
    @DisplayName("negative: every role but the reviewer is refused 403 at both document doors, and the denial recorded -"
            + " the support agent, who reads every customer, included")
    void theDocumentDoorsAreTheReviewersAlone() {
        for (Door door : DOCUMENT_DOORS) {
            for (RoleName role : RoleName.values()) {
                if (role == RoleName.KYC_REVIEWER) {
                    continue;
                }
                Knock knock = new Knock(AssuranceLevel.MULTI_FACTOR, Set.of(role));
                assertThatThrownBy(() -> knock.at(door)).as(door + " " + role).isInstanceOfSatisfying(ApiException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.FORBIDDEN));
                assertThat(knock.written).as(door + " " + role).singleElement().satisfies(record -> {
                    assertThat(record.operation()).isEqualTo(IdentityAuditAction.AUTHORIZATION_DENIED);
                    assertThat(record.actor().type()).isEqualTo(ActorType.EMPLOYEE);
                });
            }
        }
    }

    @Test
    @DisplayName("negative: a holder without CUSTOMER_READ - the KYC reviewer among them - is refused 403 at the name read, and"
            + " the denial recorded")
    void theNameReadIsCustomerReadsAlone() {
        for (RoleName role : RoleName.values()) {
            if (role.permissions().contains(com.finapp.identity.PermissionName.CUSTOMER_READ)) {
                continue;
            }
            Knock knock = new Knock(AssuranceLevel.MULTI_FACTOR, Set.of(role));
            assertThatThrownBy(() -> knock.at(Door.CUSTOMER_IDENTITY)).as(role.name())
                    .isInstanceOfSatisfying(ApiException.class,
                            refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.FORBIDDEN));
            assertThat(knock.written).as(role.name()).extracting(record -> record.operation().code())
                    .containsExactly("identity.AuthorizationDenied");
        }
    }

    @Test
    @DisplayName("negative: a customer's session - no role - is refused 403 at each door, recorded CUSTOMER")
    void aCustomerIsRefusedAtEach() {
        for (Door door : Door.values()) {
            Knock knock = new Knock(AssuranceLevel.MULTI_FACTOR, Set.of());
            assertThatThrownBy(() -> knock.at(door)).as(door.name()).isInstanceOfSatisfying(ApiException.class,
                    refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.FORBIDDEN));
            assertThat(knock.written).singleElement().satisfies(record ->
                    assertThat(record.actor().type()).isEqualTo(ActorType.CUSTOMER));
        }
    }

    @Test
    @DisplayName("negative: the very holder on a password-only session is refused its step-up at each door, before the"
            + " permission is read, and the refusal recorded")
    void aPasswordSessionIsRefusedItsStepUp() {
        for (Door door : Door.values()) {
            RoleName holder = door == Door.CUSTOMER_IDENTITY ? RoleName.CUSTOMER_SUPPORT_AGENT : RoleName.KYC_REVIEWER;
            Knock knock = new Knock(AssuranceLevel.PASSWORD, Set.of(holder));
            assertThatThrownBy(() -> knock.at(door)).as(door.name()).isInstanceOfSatisfying(ApiException.class,
                    refused -> assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED));
            assertThat(knock.written).as(door.name()).extracting(record -> record.operation().code())
                    .containsExactly("identity.StepUpRefused");
            assertThat(knock.written.get(0).changeSummary()).hasValueSatisfying(summary ->
                    assertThat(summary).contains("operation=GET " + door.pattern));
        }
    }

    // -----------------------------------------------------------------

    /** One request at one door: its session, its roles, and what it wrote. */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    private static final class Knock {

        final IdentityId identity = IdentityId.next(IDS);
        final AtomicInteger roleReads = new AtomicInteger();
        final List<String> events = new ArrayList<>();
        final List<AuditRecord> written = new ArrayList<>();
        private final AssuranceLevel assurance;
        private final Set<RoleName> roles;

        Knock(AssuranceLevel assurance, Set<RoleName> roles) {
            this.assurance = assurance;
            this.roles = roles;
        }

        boolean at(Door door) {
            Method method = Arrays.stream(door.controller.getMethods())
                    .filter(candidate -> candidate.getName().equals(door.handler))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(door.handler));
            // The real controller, built without its desk: the interceptor reads the handler's declarations, never runs it.
            HandlerMethod handler = new HandlerMethod(new SpringObjenesis().newInstance(door.controller), method);
            Instant now = CLOCK.instant();
            Session session = Session.rehydrate(SessionId.next(IDS), identity, Sensitive.of("hash"), assurance,
                    SessionStatus.ACTIVE, now, now.plusSeconds(600), now.plusSeconds(3600), "test", null);
            SessionAuthenticationInterceptor interceptor =
                    InterceptorHarness.interceptor(session, roles, roleReads, events, written, IDS, CLOCK);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", door.pattern.replace("{id}", IDS.next().toString()));
            request.addHeader("Authorization", "Bearer a-token-of-any-shape");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, door.pattern);
            MockHttpServletResponse response = new MockHttpServletResponse();
            try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
                return interceptor.preHandle(request, response, handler);
            } finally {
                interceptor.afterCompletion(request, response, handler, null);
            }
        }
    }
}
