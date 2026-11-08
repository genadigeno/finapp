package com.finapp.app.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.api.ApiErrorHandler;
import com.finapp.identity.IdentityAuditAction;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityId;
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
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

/**
 * An operation's own refused step-up is recorded once, by the error contract (`P15-TSK-021`; the debt row "a refused
 * step-up is not audited": a payout destination, a bank-account registration, a withdrawal, a wallet payment - the
 * conditional step-ups thrown inside the handler).
 */
@DisplayName("an operation's refused step-up is recorded once by the error contract (P15-TSK-021)")
@SuppressWarnings("try") // The scopes are used for their close side effect (the established idiom).
class StepUpRefusalsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String ROUTE = "/v1/me/withdrawals";

    private final List<String> events = new ArrayList<>();
    private final List<AuditRecord> written = new ArrayList<>();
    private final ApiErrorHandler handler = new ApiErrorHandler(provider(InterceptorHarness.stepUpRefusals(events, written,
            IDS, CLOCK)));

    @Test
    @DisplayName("a conditional step-up's refusal is recorded once, against the person acting, in its own committed"
            + " transaction, and still answered 403 identity.AssuranceRequired")
    void anOperationsRefusalIsRecordedOnce() {
        IdentityId identity = IdentityId.next(IDS);
        MockHttpServletRequest request = request();
        ResponseEntity<?> answered = refuse(request, new Actor(identity.value().toString(), ActorType.CUSTOMER),
                IdentityErrorCode.ASSURANCE_REQUIRED);
        assertThat(answered.getStatusCode().value()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED.status());
        assertThat(written).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(IdentityAuditAction.STEP_UP_REFUSED);
            assertThat(record.actor()).isEqualTo(new Actor(identity.value().toString(), ActorType.CUSTOMER));
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.changeSummary()).contains("operation=POST " + ROUTE);
        });
        assertThat(events).containsExactly("begin", "write:identity.StepUpRefused", "commit");

        refuse(request, new Actor(identity.value().toString(), ActorType.CUSTOMER), IdentityErrorCode.ASSURANCE_REQUIRED);
        assertThat(written).as("the same request is never recorded twice").hasSize(1);

        refuse(request(), new Actor(identity.value().toString(), ActorType.CUSTOMER), IdentityErrorCode.ASSURANCE_REQUIRED);
        assertThat(written).as("a client's retry is a second attempt, and a second record").hasSize(2);
    }

    @Test
    @DisplayName("a refusal the interceptor recorded is not recorded again")
    void theInterceptorsRefusalIsNotRecordedTwice() {
        MockHttpServletRequest request = request();
        StepUpRefusals.markRecorded(request);
        refuse(request, new Actor(IdentityId.next(IDS).value().toString(), ActorType.EMPLOYEE),
                IdentityErrorCode.ASSURANCE_REQUIRED);
        assertThat(written).isEmpty();
    }

    @Test
    @DisplayName("no person acting records nothing: no actor, the platform, a merchant")
    void onlyAPersonsRefusalIsRecorded() {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            handler.handleApiException(new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED, "refused"), request());
        }
        refuse(request(), Actor.SYSTEM, IdentityErrorCode.ASSURANCE_REQUIRED);
        refuse(request(), new Actor(IdentityId.next(IDS).value().toString(), ActorType.MERCHANT),
                IdentityErrorCode.ASSURANCE_REQUIRED);
        assertThat(written).isEmpty();
    }

    @Test
    @DisplayName("every other refusal records nothing here: a denial is the interceptor's, and a validation failure no"
            + " privileged attempt")
    void onlyAStepUpIsRecordedHere() {
        Actor person = new Actor(IdentityId.next(IDS).value().toString(), ActorType.EMPLOYEE);
        refuse(request(), person, PlatformErrorCode.FORBIDDEN);
        refuse(request(), person, PlatformErrorCode.VALIDATION_FAILED);
        assertThat(written).isEmpty();
    }

    @Test
    @DisplayName("fail closed, as the interceptor does: a record that cannot be written answers 500, never a 403"
            + " without its record; a context without the recorder renders the contract alone")
    void aRefusalWhoseRecordFailsIsAnsweredAsAFailure() {
        ApiErrorHandler failing = new ApiErrorHandler(provider(InterceptorHarness.failingStepUpRefusals(IDS, CLOCK)));
        ApiErrorHandler without = new ApiErrorHandler(new StaticListableBeanFactory().getBeanProvider(StepUpRefusals.class));
        Actor person = new Actor(IdentityId.next(IDS).value().toString(), ActorType.CUSTOMER);
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope acting = SecurityContext.enter(person)) {
            assertThat(failing.handleApiException(new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED, "refused"),
                    request()).getStatusCode().value()).isEqualTo(PlatformErrorCode.INTERNAL_ERROR.status());
            assertThat(without.handleApiException(new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED, "refused"),
                    request()).getStatusCode().value()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED.status());
        }
    }

    private ResponseEntity<?> refuse(MockHttpServletRequest request, Actor actor,
            com.finapp.platform.api.ErrorCode code) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope acting = SecurityContext.enter(actor)) {
            return handler.handleApiException(new ApiException(code, "refused"), request);
        }
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ROUTE);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, ROUTE);
        return request;
    }

    private static ObjectProvider<StepUpRefusals> provider(StepUpRefusals recorder) {
        return new StaticListableBeanFactory(Map.of("stepUpRefusals", recorder)).getBeanProvider(StepUpRefusals.class);
    }
}
