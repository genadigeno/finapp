package com.finapp.app.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.merchant.MerchantKeyAuthenticationInterceptor;
import com.finapp.app.merchant.RequiresMerchantKey;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityId;
import com.finapp.identity.PermissionName;
import com.finapp.identity.Session;
import com.finapp.identity.SessionId;
import com.finapp.identity.SessionStatus;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/**
 * A database that cannot be read serves nothing (`P16-TSK-028`, ADR-0147; {@code PHASE_16_PLAN.md} §18 "fail-open under
 * degradation"; {@code THREAT_MODEL.md} T14).
 *
 * <p>Every check a request meets before its handler reads PostgreSQL: the session and, on an operator act, the identity's
 * roles (one transaction), and a merchant's key. While a primary is lost and before its standby is promoted, or when a
 * connection is lost in the middle of a request, each of those reads fails. What must never follow is the request served
 * without its check, or answered as a refusal that was never decided: a failure to read is neither "no session" (a database
 * blip would sign every customer out, and say the session is dead when the truth is "we cannot tell") nor "denied" (a denial
 * would be recorded, and the record would name a decision nobody made). Each case here: the real interceptor, the database
 * away; the read's own failure thrown as itself, never an {@link ApiException}; the unit of work rolled back; nothing
 * written; no session or merchant on the request; no actor left on the thread; the handler never reached.
 *
 * <p>The controls: the same interceptors serve a customer's route with the database reachable, and refuse a request that
 * presents nothing {@code 401} without reaching the database at all, which is what the experiments' security probe reads on
 * every replica while a fault is on ({@code OPERATIONS_RUNBOOK.md} section 11.62).
 *
 * <p>Hermetic: no database, no context. What a real lost connection does on a cluster is the probe's, owed under
 * {@code POSTPONED_ISSUES.md} PI-025.
 */
@DisplayName("a database that cannot be read serves nothing: every check fails as a failure, never as a pass or a refusal (P16-TSK-028)")
class DegradedStorageFailsClosedTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    /** Handlers as the platform declares them: a customer's act, an operator act, one behind a step-up, a merchant's. */
    static final class Doors {
        @RequiresSession
        public void customerAct() {}

        @RequiresPermission(PermissionName.LEDGER_ADJUST)
        public void operatorAct() {}

        @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
        @RequiresPermission(PermissionName.LEDGER_ADJUST)
        public void steppedUpOperatorAct() {}

        @RequiresMerchantKey
        public void merchantAct() {}
    }

    @Test
    @DisplayName("with no connection to be had, a session presented at any door is neither served nor refused: the failure is thrown as itself")
    void aSessionLookupWithTheDatabaseAwayServesNothing() {
        for (String door : List.of("customerAct", "operatorAct", "steppedUpOperatorAct")) {
            Knock knock = new Knock(false);
            assertThatThrownBy(() -> knock.atSessionDoor(door)).as("%s with the database away", door)
                    .isInstanceOf(CannotGetJdbcConnectionException.class).isNotInstanceOf(ApiException.class);
            knock.assertNothingFollowed(door);
        }
    }

    @Test
    @DisplayName("a session found and its roles then unreadable: the operator act is neither served nor denied, and no denial is recorded")
    void anOperatorActWhoseRolesCannotBeReadServesNothing() {
        for (String door : List.of("operatorAct", "steppedUpOperatorAct")) {
            Knock knock = new Knock(true);
            assertThatThrownBy(() -> knock.atSessionDoor(door)).as("%s with its roles unreadable", door)
                    .isInstanceOf(DataAccessResourceFailureException.class).isNotInstanceOf(ApiException.class)
                    .hasMessageContaining("lost in the middle of the request");
            knock.assertNothingFollowed(door);
        }
    }

    @Test
    @DisplayName("the control: the same interceptor, the database reachable, serves a customer's route, which reads no roles")
    void theSameInterceptorServesWhenTheDatabaseAnswers() {
        Knock knock = new Knock(true);
        assertThat(knock.atSessionDoor("customerAct")).isTrue();
        assertThat(knock.request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION)).isNotNull();
        assertThat(knock.actorWhileHandled).isEqualTo(ActorType.CUSTOMER);
        assertThat(knock.events).containsExactly("begin", "commit");
        assertThat(SecurityContext.current()).as("the thread is left as it was found").isEmpty();
    }

    @Test
    @DisplayName("with no connection to be had, a merchant's key is neither served nor refused; reachable, an unknown key is refused 401")
    void aMerchantKeyLookupWithTheDatabaseAwayServesNothing() {
        Knock away = new Knock(false);
        assertThatThrownBy(() -> away.atMerchantDoor(true)).isInstanceOf(CannotGetJdbcConnectionException.class)
                .isNotInstanceOf(ApiException.class);
        assertThat(away.request.getAttribute(MerchantKeyAuthenticationInterceptor.CURRENT_MERCHANT)).isNull();
        assertThat(away.lookups).as("the store was never reached").hasValue(0);
        assertThat(away.events).containsExactly("begin", "rollback");
        assertThat(away.handled).isFalse();
        assertThat(SecurityContext.current()).isEmpty();

        Knock reachable = new Knock(true);
        assertThatThrownBy(() -> reachable.atMerchantDoor(true)).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.UNAUTHENTICATED));
        assertThat(reachable.lookups).as("the control's refusal is the store's answer").hasValue(1);
        assertThat(reachable.handled).isFalse();
    }

    @Test
    @DisplayName("a request presenting nothing is refused 401 at every door without the database: what the probe reads during an outage")
    void presentingNothingIsRefusedWithoutTheDatabase() {
        for (String door : List.of("customerAct", "operatorAct", "steppedUpOperatorAct")) {
            Knock knock = new Knock(false);
            knock.presentNothing = true;
            assertThatThrownBy(() -> knock.atSessionDoor(door)).as("%s presenting nothing", door)
                    .isInstanceOfSatisfying(ApiException.class,
                            refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.UNAUTHENTICATED));
            assertThat(knock.events).as("no unit of work was opened for it").isEmpty();
            knock.assertNothingFollowed(door);
        }
        Knock merchant = new Knock(false);
        assertThatThrownBy(() -> merchant.atMerchantDoor(false)).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.UNAUTHENTICATED));
        assertThat(merchant.events).isEmpty();
        assertThat(merchant.lookups).hasValue(0);
    }

    // -----------------------------------------------------------------

    /** One request at one door over a database that is away ({@code reachable} false) or loses its connection mid-request. */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    private static final class Knock {

        final IdentityId identity = IdentityId.next(IDS);
        final List<String> events = new ArrayList<>();
        final List<AuditRecord> written = new ArrayList<>();
        final AtomicInteger lookups = new AtomicInteger();
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/a-protected-route");
        private final boolean reachable;
        boolean presentNothing;
        boolean handled;
        ActorType actorWhileHandled;

        Knock(boolean reachable) {
            this.reachable = reachable;
        }

        boolean atSessionDoor(String handlerName) {
            HandlerMethod handler = handler(handlerName);
            Instant now = CLOCK.instant();
            Session session = Session.rehydrate(SessionId.next(IDS), identity, Sensitive.of("hash"), AssuranceLevel.MULTI_FACTOR,
                    SessionStatus.ACTIVE, now, now.plusSeconds(600), now.plusSeconds(3600), "test", null);
            SessionAuthenticationInterceptor interceptor =
                    InterceptorHarness.interceptorOverADatabaseAway(session, reachable, events, written, IDS, CLOCK);
            if (!presentNothing) {
                request.addHeader("Authorization", "Bearer a-token-of-any-shape");
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            // Inside a correlation scope, as every request is: a denial or a refusal would then be written, so a failure misread as
            // one shows as its record, not as a missing scope.
            try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
                handled = interceptor.preHandle(request, response, handler);
                actorWhileHandled = SecurityContext.current().map(actor -> actor.type()).orElse(null);
                return handled;
            } finally {
                interceptor.afterCompletion(request, response, handler, null);
            }
        }

        boolean atMerchantDoor(boolean presentAKey) {
            HandlerMethod handler = handler("merchantAct");
            MerchantKeyAuthenticationInterceptor interceptor =
                    InterceptorHarness.merchantDoorOverADatabaseAway(reachable, events, lookups);
            if (presentAKey) {
                // A key id of the platform's own shape (a version-7 UUID): any other is refused as malformed before the store is
                // asked, which would show nothing about the store being away.
                request.addHeader("Authorization", "Bearer " + IDS.next() + ".a-secret-of-any-shape");
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
                handled = interceptor.preHandle(request, response, handler);
                return handled;
            } finally {
                interceptor.afterCompletion(request, response, handler, null);
            }
        }

        void assertNothingFollowed(String door) {
            assertThat(handled).as("%s: the handler was never reached", door).isFalse();
            assertThat(written).as("%s: nothing recorded - no refusal was decided", door).isEmpty();
            assertThat(events).as("%s: no unit of work committed", door).doesNotContain("commit");
            assertThat(request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION)).as("%s: no session on the request", door)
                    .isNull();
            assertThat(SecurityContext.current()).as("%s: no actor left on the thread", door).isEmpty();
        }

        private static HandlerMethod handler(String name) {
            Method method;
            try {
                method = Doors.class.getMethod(name);
            } catch (NoSuchMethodException absent) {
                throw new IllegalArgumentException(name, absent);
            }
            return new HandlerMethod(new Doors(), method);
        }
    }
}
