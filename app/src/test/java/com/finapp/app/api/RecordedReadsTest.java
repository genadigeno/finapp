package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.IdentityAuditAction;
import com.finapp.identity.PermissionName;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The operator's reads of customers are recorded, and an unrecorded one is never served (`P17-TSK-025`; finapp-admin-panel
 * ADR-C132 decision 5): {@link RecordedReads}, the one helper, and {@link UnrecordedReadAdvice}, the guard at the answer.
 */
@DisplayName("every operator read of a customer is recorded, and an unrecorded one is refused at the answer (P17-TSK-025)")
@SuppressWarnings("try") // The correlation and actor scopes are used for their close side effect (the established idiom).
class RecordedReadsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-19T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor OPERATOR = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);

    private final List<AuditRecord> written = new ArrayList<>();
    private final AuditWriter<Connection> writer = (unitOfWork, record) -> written.add(record);
    private final RecordedReads reads = new RecordedReads(writer, IDS, CLOCK);
    private final UnrecordedReadAdvice advice = new UnrecordedReadAdvice();

    @AfterEach
    void unbind() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("the four reads are recorded: customer, account, transfer and payment - and nothing that acts")
    void thePermissionsAreTheFourReads() {
        assertThat(RecordedReads.PERMISSIONS).containsExactlyInAnyOrder(PermissionName.CUSTOMER_READ, PermissionName.ACCOUNT_READ,
                PermissionName.TRANSFER_READ, PermissionName.PAYMENT_READ);
        assertThat(RecordedReads.PERMISSIONS)
                .as("they are the support desk's whole grant, so every read that desk makes is on the record")
                .isEqualTo(com.finapp.identity.RoleName.CUSTOMER_SUPPORT_AGENT.permissions());
    }

    @Test
    @DisplayName("a read writes its record in the caller's unit of work - the operator, the action, the target, the summary, the"
            + " correlation - and marks the request")
    void aReadIsRecordedAndTheRequestMarked() {
        MockHttpServletRequest request = inARequest();
        CorrelationId correlation = CorrelationId.generate(IDS);
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(correlation));
                SecurityContext.Scope actor = SecurityContext.enter(OPERATOR)) {
            reads.record(null, IdentityAuditAction.IDENTITY_READ, "party.Customer", "c-1", "served=1");
        }

        assertThat(written).singleElement().satisfies(record -> {
            assertThat(record.actor()).isEqualTo(OPERATOR);
            assertThat(record.operation()).isEqualTo(IdentityAuditAction.IDENTITY_READ);
            assertThat(record.targetType()).isEqualTo("party.Customer");
            assertThat(record.targetId()).isEqualTo("c-1");
            assertThat(record.changeSummary()).contains("served=1");
            assertThat(record.reason()).isEmpty();
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.correlationId()).isEqualTo(correlation);
            assertThat(record.occurredAt()).isEqualTo(CLOCK.instant());
        });
        assertThat(RecordedReads.recorded(request)).isTrue();
    }

    @Test
    @DisplayName("a read with no actor or no correlation records nothing and marks nothing: it is refused before the answer")
    void noActorNoRecord() {
        MockHttpServletRequest request = inARequest();
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            assertThatThrownBy(() -> reads.record(null, IdentityAuditAction.IDENTITY_READ, "t", "id", "s"))
                    .isInstanceOf(IllegalStateException.class);
        }
        try (SecurityContext.Scope actor = SecurityContext.enter(OPERATOR)) {
            assertThatThrownBy(() -> reads.record(null, IdentityAuditAction.IDENTITY_READ, "t", "id", "s"))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(written).isEmpty();
        assertThat(RecordedReads.recorded(request)).isFalse();
    }

    // ----------------------------------------------------------------------------------------------- the guard at the answer

    @Test
    @DisplayName("an answer under a recorded permission whose read wrote nothing is refused 500 api.InternalError, its body dropped")
    void anUnrecordedReadIsNotServed() throws Exception {
        MockHttpServletRequest request = at("customerProfile");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Object answered = answer(Map.of("customerId", "c-1"), request, response);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.getContentType()).as("the error contract's media type").isEqualTo("application/problem+json");
        assertThat(answered).isInstanceOfSatisfying(ProblemDetailBody.class, problem -> {
            assertThat(problem.code()).isEqualTo("api.InternalError");
            assertThat(problem.status()).isEqualTo(500);
        });
    }

    @Test
    @DisplayName("the same answer is served once its read was recorded")
    void aRecordedReadIsServed() throws Exception {
        MockHttpServletRequest request = at("customerProfile");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enter(OPERATOR)) {
            reads.record(null, IdentityAuditAction.IDENTITY_READ, "party.Customer", "c-1", "served=1");
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        Map<String, String> body = Map.of("customerId", "c-1");

        assertThat(answer(body, request, response)).isSameAs(body);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("each of the four, declared on the method or the class, is held; another permission, no permission, a problem"
            + " answer and an empty one pass")
    void theScopeIsTheFourPermissions() throws Exception {
        for (String handler : List.of("customerProfile", "accountBalances", "transferList", "paymentDetail")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            answer(Map.of(), at(handler), response);
            assertThat(response.getStatus()).as(handler).isEqualTo(500);
        }
        MockHttpServletResponse classLevel = new MockHttpServletResponse();
        answer(Map.of(), at(new HandlerMethod(new ReadDeskOnTheClass(), ReadDeskOnTheClass.class.getMethod("read"))),
                classLevel);
        assertThat(classLevel.getStatus()).as("declared on the class").isEqualTo(500);

        for (String handler : List.of("staffDirectory", "unguarded")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            Map<String, String> body = Map.of();
            assertThat(answer(body, at(handler), response)).as(handler).isSameAs(body);
            assertThat(response.getStatus()).as(handler).isEqualTo(200);
        }
        ProblemDetailBody problem = new ProblemDetailBody("t", "Not found", 404, "api.NotFound", "/x", null, null);
        MockHttpServletResponse refused = new MockHttpServletResponse();
        assertThat(answer(problem, at("customerProfile"), refused)).as("a problem serves nothing").isSameAs(problem);
        assertThat(answer(null, at("customerProfile"), new MockHttpServletResponse())).as("nor does no body").isNull();
    }

    @Test
    @DisplayName("a handler marked @RecordedRead is held whatever its permission - the KYC reviewers' reads (P17-TSK-029) - and"
            + " the mark is the method's, never inferred for its neighbours")
    void aMarkedReadIsHeldWhateverItsPermission() throws Exception {
        MockHttpServletResponse unrecorded = new MockHttpServletResponse();
        answer(Map.of(), at("reviewQueue"), unrecorded);
        assertThat(unrecorded.getStatus()).as("marked, under KYC_REVIEW").isEqualTo(500);

        MockHttpServletRequest request = at("reviewQueue");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enter(OPERATOR)) {
            reads.record(null, IdentityAuditAction.IDENTITY_READ, "kyc_review_queue", "open", "served=0");
        }
        MockHttpServletResponse served = new MockHttpServletResponse();
        Map<String, String> body = Map.of();
        assertThat(answer(body, request, served)).isSameAs(body);
        assertThat(served.getStatus()).isEqualTo(200);

        MockHttpServletResponse act = new MockHttpServletResponse();
        assertThat(answer(body, at("reviewAct"), act)).as("its unmarked neighbour under the same permission passes").isSameAs(body);
        assertThat(act.getStatus()).isEqualTo(200);
    }

    // -----------------------------------------------------------------------------------------------------------------

    private static MockHttpServletRequest inARequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/operator/customers/c-1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    private static MockHttpServletRequest at(String handler) throws NoSuchMethodException {
        Method method = ReadDesk.class.getMethod(handler);
        return at(new HandlerMethod(new ReadDesk(), method));
    }

    private static MockHttpServletRequest at(HandlerMethod handler) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/operator/customers/c-1");
        request.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE, handler);
        return request;
    }

    private Object answer(Object body, MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        ServletServerHttpResponse server = new ServletServerHttpResponse(response);
        Object answered = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, StringHttpMessageConverter.class,
                new ServletServerHttpRequest(request), server);
        server.flush(); // the headers reach the servlet response as the converter's write would carry them
        return answered;
    }

    /** Handlers under each kind of declaration. */
    static final class ReadDesk {
        @RequiresPermission(PermissionName.CUSTOMER_READ)
        public Optional<String> customerProfile() {
            return Optional.empty();
        }

        @RequiresPermission(PermissionName.ACCOUNT_READ)
        public Optional<String> accountBalances() {
            return Optional.empty();
        }

        @RequiresPermission(PermissionName.TRANSFER_READ)
        public Optional<String> transferList() {
            return Optional.empty();
        }

        @RequiresPermission(PermissionName.PAYMENT_READ)
        public Optional<String> paymentDetail() {
            return Optional.empty();
        }

        @RequiresPermission(PermissionName.IDENTITY_READ)
        public Optional<String> staffDirectory() {
            return Optional.empty();
        }

        public Optional<String> unguarded() {
            return Optional.empty();
        }

        @RequiresPermission(PermissionName.KYC_REVIEW)
        @RecordedRead
        public Optional<String> reviewQueue() {
            return Optional.empty();
        }

        @RequiresPermission(PermissionName.KYC_REVIEW)
        public Optional<String> reviewAct() {
            return Optional.empty();
        }
    }

    /** A desk declaring its permission once, on the class. */
    @RequiresPermission(PermissionName.ACCOUNT_READ)
    static final class ReadDeskOnTheClass {
        public Optional<String> read() {
            return Optional.empty();
        }
    }
}
