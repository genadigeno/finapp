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
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.lang.annotation.Annotation;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every act on the operator surface asks for a second factor, and every role that can act is refused to an identity with none
 * (`P17-TSK-102`; the owner's D-6; finapp-admin-panel ADR-C131 decision 3; {@code THREAT_MODEL.md} T50, T53).
 *
 * <h2>Two halves, and either alone is hollow</h2>
 *
 * <p><strong>The act.</strong> Every {@code POST}, {@code PUT}, {@code PATCH} or {@code DELETE} handler that declares
 * {@link RequiresPermission} - an operator's act, wherever its path lives - declares {@link RequiresAssurance} at
 * {@code MULTI_FACTOR} or above, so a stolen password-level session acts on nothing. <strong>The grant.</strong> A password alone
 * may enrol an identity's <em>first</em> factor, so a holder who never enrolled one would still fall to a stolen password: the thief
 * enrols a factor of their own and steps up. Hence every role holding a permission that guards an act is refused to a factorless
 * identity ({@code RoleName.requiresEnrolledFactor}, from {@code PermissionName.guardsAnAct}). The domain decides the grant without
 * seeing a route, so this guard holds its answer to the routes', both ways.
 *
 * <h2>The routes come from the dispatcher, and the refusal from the real interceptor</h2>
 *
 * <p>{@code EveryEndpointDeclaresARuleTest}'s reason: the mapping that serves requests is asked, never a list. And the declaration
 * is not taken on trust - each act is knocked on by the real {@link SessionAuthenticationInterceptor} (the
 * {@link InterceptorHarness}, no database) with a password session holding a role that has the act's permission: refused
 * {@code 403 identity.AssuranceRequired}, its refusal recorded, before the handler; and at {@code MULTI_FACTOR} let through.
 */
@Tag("slice")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("every operator act asks for a second factor, and every role that can act needs one at grant (P17-TSK-102)")
@SuppressWarnings("try") // The correlation scope is used for its close side effect (the established idiom).
class EveryOperatorActRequiresAFactorTest {

    /**
     * Acts that may take a lesser session, by {@code Controller#method}, each with its reason. <strong>Empty</strong>: the owner's
     * D-6 is every act. A read sent as a {@code POST} to keep a value out of the URL (a lookup by login identifier) is the one
     * kind of entry this could ever hold, and it would say so.
     */
    static final Map<String, String> EXEMPT = Map.of();

    private static final Set<RequestMethod> UNSAFE =
            EnumSet.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    /** The MVC mapping, by name: actuator registers a second one ({@code EveryEndpointDeclaresARuleTest}'s note). */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    /** One handler of the application, as the dispatcher serves it. */
    private record Handler(HandlerMethod method, Set<RequestMethod> verbs, String pattern) {

        String name() {
            return method.getBeanType().getSimpleName() + "#" + method.getMethod().getName();
        }

        boolean unsafe() {
            return verbs.stream().anyMatch(UNSAFE::contains);
        }

        PermissionName permission() {
            RequiresPermission declared = annotation(method, RequiresPermission.class);
            return declared == null ? null : declared.value();
        }

        boolean act() {
            return unsafe() && permission() != null;
        }

        @Override
        public String toString() {
            return name() + " " + verbs + " " + pattern;
        }
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("every act - an unsafe handler declaring a permission - declares MULTI_FACTOR or above")
    void everyActDeclaresAFactor() {
        List<String> undeclared = acts().stream()
                .filter(act -> !EXEMPT.containsKey(act.name()))
                .filter(act -> {
                    RequiresAssurance declared = annotation(act.method(), RequiresAssurance.class);
                    return declared == null || !declared.value().atLeast(AssuranceLevel.MULTI_FACTOR);
                })
                .map(Handler::toString)
                .sorted()
                .toList();
        assertThat(undeclared)
                .as("an operator act a password-level session can perform (the owner's D-6). Annotate it"
                        + " @RequiresAssurance(AssuranceLevel.MULTI_FACTOR), or name it in EXEMPT with the reason it is no act")
                .isEmpty();
    }

    @Test
    @DisplayName("the guard is not vacuous: it sees the operator's acts across the modules")
    void theGuardSeesRealActs() {
        Set<String> controllers = acts().stream().map(act -> act.method().getBeanType().getSimpleName())
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(acts()).as("acts seen").hasSizeGreaterThan(150);
        assertThat(controllers).contains("IdentityAdministrationController", "SettlementOperationsController",
                "ReconciliationResolutionController", "LoanServicingController", "PeriodCloseController", "RiskCaseController",
                "FxAdministrationController", "DeadLetterController");
    }

    @Test
    @DisplayName("every exemption names an act this application serves, and gives a reason")
    void theExemptionsAreRealActs() {
        Set<String> names = acts().stream().map(Handler::name).collect(Collectors.toSet());
        EXEMPT.forEach((name, reason) -> {
            assertThat(names).as("an exemption for a handler that is not an act").contains(name);
            assertThat(reason).as(name).isNotBlank();
        });
    }

    @Test
    @DisplayName("at every act a password session holding the permission is refused 403 identity.AssuranceRequired, its refusal"
            + " recorded first and the permission never read; at MULTI_FACTOR the same session is let through")
    void aPasswordSessionIsRefusedAtEveryAct() {
        List<Handler> acts = acts().stream().filter(act -> !EXEMPT.containsKey(act.name())).toList();
        for (Handler act : acts) {
            RoleName holder = holderOf(act.permission());

            Knock password = new Knock(act, AssuranceLevel.PASSWORD, holder);
            assertThatThrownBy(password::send).as("%s", act).isInstanceOfSatisfying(ApiException.class,
                    refused -> assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED));
            assertThat(password.written).as("%s: the refusal recorded", act)
                    .extracting(record -> record.operation().code())
                    .containsExactly(IdentityAuditAction.STEP_UP_REFUSED.code());

            Knock stepped = new Knock(act, AssuranceLevel.MULTI_FACTOR, holder);
            assertThat(stepped.send()).as("%s: a stepped-up holder is let through", act).isTrue();
            assertThat(stepped.written).as("%s", act).isEmpty();
        }
        assertThat(acts).as("knocked on").hasSizeGreaterThan(150);
    }

    @Test
    @DisplayName("a permission guards an act in the domain exactly when it guards an act on a route; one guarding no route counts"
            + " as an act, so the domain fails towards a factor")
    void everyPermissionSaysWhetherItGuardsAnAct() {
        Map<PermissionName, Boolean> guardsAnActOnARoute = new EnumMap<>(PermissionName.class);
        for (Handler handler : handlers()) {
            PermissionName permission = handler.permission();
            if (permission != null) {
                boolean act = handler.act() && !EXEMPT.containsKey(handler.name());
                guardsAnActOnARoute.merge(permission, act, Boolean::logicalOr);
            }
        }
        for (PermissionName permission : PermissionName.values()) {
            boolean onTheRoutes = guardsAnActOnARoute.getOrDefault(permission, true);
            assertThat(permission.guardsAnAct())
                    .as("%s: PermissionName.guardsAnAct says %s, and the routes say it %s", permission,
                            permission.guardsAnAct(), onTheRoutes ? "guards an act (or no route at all)" : "only reads")
                    .isEqualTo(onTheRoutes);
        }
    }

    @Test
    @DisplayName("every role holding a permission that guards an act is refused to an identity with no factor")
    void everyRoleThatCanActNeedsAFactor() {
        for (RoleName role : RoleName.values()) {
            boolean canAct = role.permissions().stream().anyMatch(PermissionName::guardsAnAct);
            assertThat(role.requiresEnrolledFactor()).as(role.name()).isEqualTo(canAct);
        }
    }

    // -----------------------------------------------------------------

    private List<Handler> handlers() {
        List<Handler> handlers = new ArrayList<>();
        mappings.getHandlerMethods().forEach((RequestMappingInfo info, HandlerMethod method) -> {
            if (!method.getBeanType().getName().startsWith("com.finapp.")) {
                return;
            }
            Set<RequestMethod> verbs = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                handlers.add(new Handler(method, verbs, pattern));
            }
        });
        return handlers;
    }

    private List<Handler> acts() {
        return handlers().stream().filter(Handler::act).toList();
    }

    /** The first role, in declaration order, granted {@code permission}. */
    private static RoleName holderOf(PermissionName permission) {
        return Arrays.stream(RoleName.values()).filter(role -> role.permissions().contains(permission)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No role holds " + permission));
    }

    /** Read from the method first and then the class, as the interceptor reads every rule. */
    private static <A extends Annotation> A annotation(HandlerMethod method, Class<A> type) {
        A declared = method.getMethodAnnotation(type);
        return declared != null ? declared : method.getBeanType().getAnnotation(type);
    }

    /** One request at one act, through the real interceptor, a session at {@code assurance} holding {@code role}. */
    private static final class Knock {

        final List<String> events = new ArrayList<>();
        final List<AuditRecord> written = new ArrayList<>();
        private final Handler act;
        private final AssuranceLevel assurance;
        private final RoleName role;

        Knock(Handler act, AssuranceLevel assurance, RoleName role) {
            this.act = act;
            this.assurance = assurance;
            this.role = role;
        }

        boolean send() {
            Instant now = CLOCK.instant();
            Session session = Session.rehydrate(SessionId.next(IDS), IdentityId.next(IDS), Sensitive.of("hash"), assurance,
                    SessionStatus.ACTIVE, now, now.plusSeconds(600), now.plusSeconds(3600), "test", null);
            SessionAuthenticationInterceptor interceptor = InterceptorHarness.interceptor(session, Set.of(role),
                    new AtomicInteger(), events, written, IDS, CLOCK);
            String verb = act.verbs().stream().filter(UNSAFE::contains).findFirst().orElseThrow().name();
            MockHttpServletRequest request = new MockHttpServletRequest(verb, act.pattern().replaceAll("\\{[^}]+}", "42"));
            request.addHeader("Authorization", "Bearer a-token-of-any-shape");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, act.pattern());
            MockHttpServletResponse response = new MockHttpServletResponse();
            try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
                return interceptor.preHandle(request, response, act.method());
            } finally {
                interceptor.afterCompletion(request, response, act.method(), null);
            }
        }
    }
}
