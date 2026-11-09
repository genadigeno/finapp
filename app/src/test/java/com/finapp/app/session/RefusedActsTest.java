package com.finapp.app.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.finapp.app.api.ApiErrorHandler;
import com.finapp.app.api.RefusedActAdvice;
import com.finapp.app.merchant.MerchantKeyAuthenticationInterceptor;
import com.finapp.app.merchant.RequiresMerchantKey;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.merchant.AuthenticatedMerchant;
import com.finapp.merchant.MerchantApiKeyId;
import com.finapp.merchant.MerchantId;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.ErrorCode;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.PlatformAuditAction;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.reconciliation.ReconciliationErrorCode;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * What the declaration does at each kind of refusal (`P15-TSK-039`), through the real error contract: the real
 * {@code ApiErrorHandler} and {@code RefusedActAdvice} over a stand-in door, with the audit store a list. Whether a real
 * door declares it is {@code RefusedActsAreRecordedTest}'s; the row in PostgreSQL is
 * {@code FxAdministrationEndpointDatabaseTest}'s and {@code IdentityAdministrationDatabaseTest}'s, owed (C14).
 */
@org.junit.jupiter.api.Tag("slice")
@DisplayName("a declared door's refused and failed acts are recorded with their outcome (P15-TSK-039)")
@SuppressWarnings("try") // The scopes are used for their close side effect (the established idiom).
class RefusedActsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String RESOLUTION = "019a0000-0000-7000-8000-000000000042";
    private static final Actor OPERATOR = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);

    private final List<String> events = new ArrayList<>();
    private final List<AuditRecord> written = new ArrayList<>();

    private AutoCloseable correlation;
    private AutoCloseable actor;

    @BeforeEach
    void enterTheRequestsScopes() {
        correlation = CorrelationContext.enter(Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
        actor = SecurityContext.enter(OPERATOR);
    }

    @AfterEach
    void closeTheRequestsScopes() throws Exception {
        if (actor != null) {
            actor.close();
        }
        correlation.close();
    }

    private MockMvc door(RefusedActs recorder) {
        return door(recorder, new HandlerInterceptor() {});
    }

    private MockMvc door(RefusedActs recorder, HandlerInterceptor interceptor) {
        return MockMvcBuilders.standaloneSetup(new Door())
                .setControllerAdvice(new ApiErrorHandler(absent(StepUpRefusals.class)), new RefusedActAdvice(present(recorder)))
                .addInterceptors(interceptor)
                .build();
    }

    private RefusedActs recorder() {
        return InterceptorHarness.refusedActs(events, written, IDS, CLOCK);
    }

    private MockHttpServletResponse approve(String code) throws Exception {
        return door(recorder()).perform(post("/doors/" + RESOLUTION + "/approval").header("X-Refuse", code)).andReturn().getResponse();
    }

    @Test
    @DisplayName("a refused self-approval is recorded DENIED, against the actor, in its own committed transaction, and still"
            + " answered its 403")
    void aRefusedSelfApprovalIsRecorded() throws Exception {
        MockHttpServletResponse answered = approve("self");

        assertThat(answered.getStatus()).isEqualTo(ReconciliationErrorCode.SELF_APPROVAL_REFUSED.status());
        assertThat(answered.getContentAsString()).contains(ReconciliationErrorCode.SELF_APPROVAL_REFUSED.code());
        assertThat(written).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(PlatformAuditAction.DOOR_ACT_REFUSED);
            assertThat(record.actor()).isEqualTo(OPERATOR);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.DENIED);
            assertThat(record.targetType()).isEqualTo(RefusedActs.AUDIT_TARGET_TYPE);
            assertThat(record.targetId()).isEqualTo("POST /doors/{id}/approval");
            assertThat(record.changeSummary()).contains("code=reconciliation.SelfApprovalRefused, status="
                    + ReconciliationErrorCode.SELF_APPROVAL_REFUSED.status() + ", id=" + RESOLUTION);
        });
        assertThat(events).containsExactly("begin", "write:door.ActRefused", "commit");
    }

    @Test
    @DisplayName("a stale echo and a refused kind are recorded DENIED, each with its own code")
    void aStaleEchoAndARefusedKindAreRecorded() throws Exception {
        approve("stale");
        approve("kind");

        assertThat(written).hasSize(2).allSatisfy(record -> assertThat(record.outcome()).isEqualTo(AuditOutcome.DENIED));
        assertThat(written.get(0).changeSummary().orElseThrow()).contains("code=reconciliation.ResolutionStale");
        assertThat(written.get(1).changeSummary().orElseThrow()).contains("code=reconciliation.ResolutionKindNotAllowed");
    }

    @Test
    @DisplayName("a not-found lookup is recorded FAILED, naming the id looked for")
    void aNotFoundLookupIsRecorded() throws Exception {
        MockHttpServletResponse answered = approve("notfound");

        assertThat(answered.getStatus()).isEqualTo(404);
        assertThat(written).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.changeSummary()).hasValueSatisfying(summary -> assertThat(summary).contains("id=" + RESOLUTION));
        });
    }

    @Test
    @DisplayName("an accident is recorded FAILED with the contract's code, never the exception's message")
    void anAccidentIsRecorded() throws Exception {
        MockHttpServletResponse answered = approve("accident");

        assertThat(answered.getStatus()).isEqualTo(500);
        assertThat(written).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.changeSummary()).hasValue("code=" + PlatformErrorCode.INTERNAL_ERROR.code() + ", status=500, id=" + RESOLUTION);
        });
    }

    @Test
    @DisplayName("a body refused before the handler runs is one more refusal: recorded DENIED, none of the body written")
    void aValidationFailureIsRecorded() throws Exception {
        MockHttpServletResponse answered = door(recorder())
                .perform(post("/doors/" + RESOLUTION + "/note").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}"))
                .andReturn()
                .getResponse();

        assertThat(answered.getStatus()).isEqualTo(422);
        assertThat(written).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.DENIED);
            assertThat(record.reason()).isEmpty();
            assertThat(record.changeSummary()).hasValue("code=" + PlatformErrorCode.VALIDATION_FAILED.code() + ", status=422, id=" + RESOLUTION);
        });
    }

    @Test
    @DisplayName("only a path value that is a UUID is written: anything else in a path is the caller's text")
    void aPathValueIsWrittenOnlyWhenItIsAnId() throws Exception {
        String typedIntoThePath = "4111111111111111";
        door(recorder()).perform(post("/doors/" + typedIntoThePath + "/approval").header("X-Refuse", "notfound")).andReturn();

        assertThat(written).singleElement().satisfies(record -> {
            assertThat(record.targetId()).isEqualTo("POST /doors/{id}/approval");
            assertThat(record.changeSummary()).hasValueSatisfying(summary -> {
                assertThat(summary).contains("id=(not an id)");
                assertThat(summary).doesNotContain(typedIntoThePath);
            });
            assertThat(record.reason()).isEmpty();
        });
    }

    @Test
    @DisplayName("a named code is written in its closed shape: a refused kill-switch act says which switch")
    void aNamedCodeIsWrittenInItsClosedShape() throws Exception {
        for (String[] door : List.of(
                new String[] {"/switches/pairs/EUR-USD", "pair=EUR-USD"},
                new String[] {"/switches/corridors/EUR-USD-US", "corridor=EUR-USD-US"},
                new String[] {"/switches/providers/fx-sim-a", "code=fx-sim-a"})) {
            written.clear();
            door(recorder()).perform(post(door[0])).andReturn();
            assertThat(written).singleElement().satisfies(record -> assertThat(record.changeSummary())
                    .hasValueSatisfying(summary -> assertThat(summary).endsWith(door[1])));
        }
    }

    @Test
    @DisplayName("caller text outside a named code's shape does not reach the record")
    void textOutsideACodesShapeIsNotWritten() throws Exception {
        for (String[] door : List.of(
                new String[] {"/switches/pairs/eur-usd", "eur-usd", "pair=(not an id)"},
                new String[] {"/switches/pairs/4111111111111111", "4111111111111111", "pair=(not an id)"},
                new String[] {"/switches/corridors/EUR-USD-USA", "EUR-USD-USA", "corridor=(not an id)"},
                new String[] {"/switches/providers/" + "x4111111111111111", "x4111111111111111", "code=(not an id)"},
                new String[] {"/switches/providers/Ada-Lovelace", "Ada-Lovelace", "code=(not an id)"},
                new String[] {"/switches/providers/" + "a".repeat(33), "a".repeat(33), "code=(not an id)"})) {
            written.clear();
            door(recorder()).perform(post(door[0])).andReturn();
            assertThat(written).singleElement().satisfies(record -> assertThat(record.changeSummary())
                    .hasValueSatisfying(summary -> {
                        assertThat(summary).endsWith(door[2]);
                        assertThat(summary).doesNotContain(door[1]);
                    }));
        }
    }

    @Test
    @DisplayName("the same request's retry while its first flight runs is no refusal, and leaves no row")
    void anIdempotentRetryInProgressIsNotARefusal() throws Exception {
        MockHttpServletResponse answered = approve("inprogress");

        assertThat(answered.getStatus()).isEqualTo(409);
        assertThat(answered.getContentAsString()).contains(PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS.code());
        assertThat(written).isEmpty();
    }

    @Test
    @DisplayName("in the real wiring the record's transaction is its own: the session's template, REQUIRES_NEW, handed to the recorder")
    void theRecordsTransactionIsItsOwn() throws Exception {
        TransactionTemplate template = InterceptorHarness.theSessionsTemplate();
        assertThat(template.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Field transactions = RefusedActs.class.getDeclaredField("transactions");
        transactions.setAccessible(true);
        assertThat(transactions.get(InterceptorHarness.wiredRefusedActs(template, IDS, CLOCK))).isSameAs(template);

        Method bean = java.util.Arrays.stream(SessionBeans.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("refusedActs"))
                .findFirst()
                .orElseThrow();
        assertThat(bean.getParameters()[0].getName())
                .as("several templates exist; this name is what binds the bean to the session's")
                .isEqualTo("sessionTransactions");
    }

    @Test
    @DisplayName("a merchant's refused act names the API key that made it, by its public id, and never the secret")
    void aMerchantsRefusalNamesItsKey() throws Exception {
        actor.close();
        actor = SecurityContext.enter(new Actor(UUID.randomUUID().toString(), ActorType.MERCHANT));
        MerchantApiKeyId key = MerchantApiKeyId.next(IDS);
        HandlerInterceptor authenticated = new HandlerInterceptor() {
            @Override
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, Object handler) {
                request.setAttribute(MerchantKeyAuthenticationInterceptor.CURRENT_MERCHANT,
                        new AuthenticatedMerchant(MerchantId.next(IDS), key));
                return true;
            }
        };

        door(recorder(), authenticated).perform(get("/merchant-doors/" + RESOLUTION).header("Authorization", "Bearer " + key.value() + ".the-secret"))
                .andReturn();

        assertThat(written).singleElement().satisfies(record -> {
            assertThat(record.actor().type()).isEqualTo(ActorType.MERCHANT);
            assertThat(record.changeSummary()).hasValueSatisfying(summary -> {
                assertThat(summary).contains("apiKey=" + key.value());
                assertThat(summary).doesNotContain("the-secret");
            });
        });
    }

    @Test
    @DisplayName("a customer's door, undeclared, records nothing here")
    void anUndeclaredDoorRecordsNothing() throws Exception {
        MockHttpServletResponse answered = door(recorder()).perform(post("/customer-doors").header("X-Refuse", "self")).andReturn().getResponse();

        assertThat(answered.getStatus()).isEqualTo(ReconciliationErrorCode.SELF_APPROVAL_REFUSED.status());
        assertThat(written).isEmpty();
    }

    @Test
    @DisplayName("a refused step-up has its own record, and no second one here")
    void aRefusedStepUpIsNotRecordedTwice() throws Exception {
        MockHttpServletResponse answered = approve("stepup");

        assertThat(answered.getStatus()).isEqualTo(403);
        assertThat(answered.getContentAsString()).contains(IdentityErrorCode.ASSURANCE_REQUIRED.code());
        assertThat(written).isEmpty();
    }

    @Test
    @DisplayName("nobody proven acting, nothing recorded: a refusal never names an unproven actor")
    void noActorNoRecord() throws Exception {
        actor.close();
        actor = null;

        MockHttpServletResponse answered = approve("self");

        assertThat(answered.getStatus()).isEqualTo(ReconciliationErrorCode.SELF_APPROVAL_REFUSED.status());
        assertThat(written).isEmpty();
    }

    @Test
    @DisplayName("the record that cannot be written is answered 500, on every refusal kind: never a refusal without its record")
    void aRefusalWhoseRecordCannotBeWrittenIsAFailure() throws Exception {
        for (String kind : List.of("self", "stale", "notfound", "accident")) {
            MockHttpServletResponse answered = door(InterceptorHarness.failingRefusedActs(IDS, CLOCK))
                    .perform(post("/doors/" + RESOLUTION + "/approval").header("X-Refuse", kind))
                    .andReturn()
                    .getResponse();

            assertThat(answered.getStatus()).as(kind).isEqualTo(500);
            assertThat(answered.getContentAsString()).as(kind).contains(PlatformErrorCode.INTERNAL_ERROR.code());
        }
    }

    @Test
    @DisplayName("once per refused request, and a client's retry is a second attempt and a second record")
    void aRetryIsASecondRecord() throws Exception {
        approve("self");
        approve("self");

        assertThat(written).hasSize(2);
    }

    private static <T> ObjectProvider<T> present(T bean) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean(bean.getClass().getName(), bean);
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) bean.getClass();
        return factory.getBeanProvider(type);
    }

    private static <T> ObjectProvider<T> absent(Class<T> type) {
        return new StaticListableBeanFactory().getBeanProvider(type);
    }

    /** A door that refuses however it is asked to; each throw is a real path a real door has. */
    @RestController
    static class Door {

        @PostMapping("/doors/{id}/approval")
        @RecordsRefusals
        public void approve(@PathVariable("id") String id, @RequestHeader("X-Refuse") String kind) {
            throw refusal(kind);
        }

        @PostMapping("/doors/{id}/note")
        @RecordsRefusals
        public void note(@PathVariable("id") String id, @Valid @RequestBody Note body) {}

        @GetMapping("/merchant-doors/{id}")
        @RequiresMerchantKey
        @RecordsRefusals
        public void merchantRead(@PathVariable("id") String id) {
            throw new ApiException(PlatformErrorCode.NOT_FOUND, "a merchant's unknown dispute");
        }

        @PostMapping("/switches/pairs/{pair}")
        @RecordsRefusals
        public void pair(@PathVariable("pair") String pair) {
            throw refusal("notfound");
        }

        @PostMapping("/switches/corridors/{corridor}")
        @RecordsRefusals
        public void corridor(@PathVariable("corridor") String corridor) {
            throw refusal("notfound");
        }

        @PostMapping("/switches/providers/{code}")
        @RecordsRefusals
        public void provider(@PathVariable("code") String code) {
            throw refusal("notfound");
        }

        @PostMapping("/customer-doors")
        public void customer(@RequestHeader("X-Refuse") String kind) {
            throw refusal(kind);
        }

        private static RuntimeException refusal(String kind) {
            return switch (kind) {
                case "self" -> api(ReconciliationErrorCode.SELF_APPROVAL_REFUSED);
                case "stale" -> api(ReconciliationErrorCode.RESOLUTION_STALE);
                case "kind" -> api(ReconciliationErrorCode.RESOLUTION_KIND_NOT_ALLOWED);
                case "notfound" -> api(PlatformErrorCode.NOT_FOUND);
                case "stepup" -> api(IdentityErrorCode.ASSURANCE_REQUIRED);
                case "inprogress" -> api(PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS);
                default -> new IllegalStateException("the database said 4111111111111111 was wrong");
            };
        }

        private static ApiException api(ErrorCode code) {
            return new ApiException(code, "a door refused it", "detail the wire may carry");
        }
    }

    /** A body with a constraint the framework refuses before the handler runs. */
    record Note(@NotBlank String reason) {}
}
