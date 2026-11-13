package com.finapp.app.shedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.finapp.app.api.ApiErrorHandler;
import com.finapp.app.session.StepUpRefusals;
import com.finapp.app.surface.RequestSurfaces;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecord;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * A shed is before the claim, and its retry is a first attempt (`P16-TSK-016`, ADR-0137; {@code INV-IDEM-01}, {@code INV-IDEM-03};
 * {@code THREAT_MODEL.md} T16, T14).
 *
 * <p>Through the real interceptors in the order the application registers them - the door, a stand-in for authentication, the deadline
 * - the real {@code ApiErrorHandler}, and the real {@link IdempotentExecutor} over a claim store in memory, under a stand-in door that
 * claims its key and makes one recorded effect, as every keyed command does (ADR-0004). What is counted is the store's rows and the
 * effects, never the answer alone: a refusal issued after the claim looks the same from outside. The rows in PostgreSQL across the spike
 * profile are E50's, owed under {@code POSTPONED_ISSUES.md} PI-025.
 */
@org.junit.jupiter.api.Tag("slice")
@DisplayName("a shed request leaves no claim and no effect, and its retry under the same key is a first attempt (P16-TSK-016)")
@SuppressWarnings("try") // The correlation scope is used for its close side effect (the established idiom).
class ShedBeforeTheClaimTest {

    private final SheddingFixtures.Pool pool = new SheddingFixtures.Pool();
    private final SheddingFixtures.MovingClock clock = new SheddingFixtures.MovingClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Claims claims = new Claims();
    private final List<String> effects = new ArrayList<>();
    private final List<String> entered = new ArrayList<>();
    private final LoadShedding shedding = new LoadShedding(pool, SheddingFixtures.defaults(), clock, SheddingFixtures.draws(true),
            new SheddingMeters(registry));
    private final IdempotentExecutor executor = new IdempotentExecutor(claims, clock, Duration.ofHours(24), Duration.ofMinutes(5));

    /** What authentication costs and may refuse: here, a clock moved by a header, and a refusal by another. */
    private final HandlerInterceptor authentication = new HandlerInterceptor() {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            String waited = request.getHeader("X-Lookup-Waited-Ms");
            if (waited != null) {
                clock.advance(Duration.ofMillis(Long.parseLong(waited)));
            }
            if (request.getHeader("X-Unauthenticated") != null) {
                throw new ApiException(PlatformErrorCode.UNAUTHENTICATED, "a stand-in refusal");
            }
            return true;
        }
    };

    private AutoCloseable correlation;

    @BeforeEach
    void enterTheRequestsScope() {
        correlation = CorrelationContext.enter(Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
    }

    @AfterEach
    void closeIt() throws Exception {
        correlation.close();
    }

    private MockMvc door(boolean nothingDeployed) {
        return MockMvcBuilders.standaloneSetup(new KeyedDoor())
                .setControllerAdvice(new ApiErrorHandler(new StaticListableBeanFactory().getBeanProvider(StepUpRefusals.class)))
                .addInterceptors(new ShedAtTheDoor(shedding, new RequestSurfaces(nothingDeployed)), authentication,
                        new ShedOnTheDeadline(shedding))
                .build();
    }

    private MockHttpServletResponse pay(String key, String... headers) throws Exception {
        var request = post("/doors/payments").header("Idempotency-Key", key);
        for (int i = 0; i < headers.length; i += 2) {
            request = request.header(headers[i], headers[i + 1]);
        }
        return door(true).perform(request).andReturn().getResponse();
    }

    private static void assertShed(MockHttpServletResponse answer) throws Exception {
        assertThat(answer.getStatus()).isEqualTo(503);
        assertThat(answer.getContentType()).startsWith("application/problem+json");
        assertThat(answer.getHeader("Retry-After")).as("the jitter's most, scripted").isEqualTo("3");
        String body = answer.getContentAsString();
        assertThat(body).contains("\"code\":\"api.Overloaded\"").doesNotContain("\"detail\"");
        // The flow's identifier is the one member that varies; without it, nothing of the load or of why: no figure but the status.
        assertThat(body.replaceAll("\"correlationId\":\"[^\"]*\"", "")).as("no figure of the load and nothing of why")
                .doesNotContain("pool").doesNotContain("permit").doesNotContain("deadline").doesNotContainPattern("[0-46-9]")
                .contains("\"status\":503");
    }

    @Test
    @DisplayName("shed on the pool: no claim, no effect, the handler never entered; the same key retried is executed once, then replayed")
    void aShedLeavesNoClaimAndItsRetryIsAFirstAttempt() throws Exception {
        pool.waiting = 7;
        assertShed(pay("k-1"));
        assertThat(claims.rows).as("no claim for a shed request").isEmpty();
        assertThat(effects).isEmpty();
        assertThat(entered).isEmpty();

        pool.waiting = 0;
        MockHttpServletResponse first = pay("k-1");
        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(first.getContentAsString()).isEqualTo("executed k-1");
        assertThat(claims.rows).containsOnlyKeys(new IdempotencyKey("test.door", "k-1"));
        assertThat(claims.rows.values()).extracting(IdempotencyRecord::state).containsExactly(IdempotencyState.COMPLETED);

        MockHttpServletResponse again = pay("k-1");
        assertThat(again.getStatus()).isEqualTo(201);
        assertThat(again.getContentAsString()).as("the stored answer, not a second run").isEqualTo("replayed k-1");
        assertThat(effects).as("one effect for the key, across the shed and two sends").containsExactly("k-1");
    }

    @Test
    @DisplayName("shed past the permits: no claim, no effect; once a permit is given back the same key is a first attempt")
    void aCommandPastItsPermitsLeavesNothing() throws Exception {
        List<LoadShedding.Admission> held = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            held.add(shedding.admit(LoadShedding.Type.COMMAND));
        }
        assertShed(pay("k-2"));
        assertThat(claims.rows).isEmpty();
        assertThat(effects).isEmpty();

        shedding.release(held.remove(0));
        assertThat(pay("k-2").getContentAsString()).isEqualTo("executed k-2");
        assertThat(effects).containsExactly("k-2");
    }

    @Test
    @DisplayName("shed on the deadline after a slow lookup: no claim, no effect, the handler never entered; the retry is a first attempt")
    void aCommandPastItsDeadlineLeavesNothing() throws Exception {
        assertShed(pay("k-3", "X-Lookup-Waited-Ms", "5001"));
        assertThat(claims.rows).isEmpty();
        assertThat(effects).isEmpty();
        assertThat(entered).isEmpty();

        assertThat(pay("k-3", "X-Lookup-Waited-Ms", "5000").getContentAsString()).as("exactly one borrow's wait left is enough")
                .isEqualTo("executed k-3");
        assertThat(effects).containsExactly("k-3");
    }

    @Test
    @DisplayName("the permit goes back on every way out: served, the handler failing, authentication refusing, shed on the deadline")
    void thePermitIsGivenBackOnEveryWayOut() throws Exception {
        assertThat(pay("k-4").getStatus()).isEqualTo(201);
        assertThat(shedding.commandsInFlight()).isZero();
        assertThat(pay("k-5", "X-Fail", "yes").getStatus()).isEqualTo(500);
        assertThat(shedding.commandsInFlight()).isZero();
        assertThat(pay("k-6", "X-Unauthenticated", "yes").getStatus()).isEqualTo(401);
        assertThat(shedding.commandsInFlight()).isZero();
        assertThat(pay("k-7", "X-Lookup-Waited-Ms", "6000").getStatus()).isEqualTo(503);
        assertThat(shedding.commandsInFlight()).isZero();
        for (int i = 0; i < 20; i++) {
            assertThat(pay("k-many-" + i).getStatus()).as("twenty in a row through five permits").isEqualTo(201);
        }
        assertThat(shedding.commandsInFlight()).isZero();
    }

    @Test
    @DisplayName("the door only refuses: an admitted request still meets authentication, and the handler is never entered past it")
    void theDoorNeverAdmitsPastAuthentication() throws Exception {
        assertThat(pay("k-8", "X-Unauthenticated", "yes").getStatus()).isEqualTo(401);
        assertThat(entered).isEmpty();
        assertThat(claims.rows).isEmpty();
        pool.waiting = 7;
        assertShed(pay("k-9", "X-Unauthenticated", "yes"));
    }

    @Test
    @DisplayName("the operator surface, a declared exemption and a read past the permits are not shed; a read on the pool is")
    void whatIsNotShed() throws Exception {
        pool.waiting = 7;
        MockHttpServletResponse operator = door(false).perform(post("/doors/payments").header("Idempotency-Key", "k-10")
                .header(RequestSurfaces.HEADER, "operator")).andReturn().getResponse();
        assertThat(operator.getStatus()).as("staff act on a shedding platform").isEqualTo(201);
        MockHttpServletResponse unmarked = door(false).perform(post("/doors/payments").header("Idempotency-Key", "k-11"))
                .andReturn().getResponse();
        assertShed(unmarked);
        assertThat(door(true).perform(post("/doors/callbacks")).andReturn().getResponse().getStatus()).isEqualTo(204);
        assertShed(door(true).perform(get("/doors/balance")).andReturn().getResponse());

        pool.waiting = 0;
        for (int i = 0; i < 5; i++) {
            shedding.admit(LoadShedding.Type.COMMAND);
        }
        assertThat(door(true).perform(get("/doors/balance")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(registry.get(SheddingMeters.NAME).tag("type", "read").tag("outcome", "pool").counter().count()).isEqualTo(1);
        assertThat(registry.get(SheddingMeters.NAME).tag("type", "command").tag("outcome", "pool").counter().count())
                .as("the operator's and the callback's requests are not counted").isEqualTo(1);
    }

    /** A keyed door as every money-moving one is: the claim, the effect and the recorded outcome in one unit of work (ADR-0004). */
    @RestController
    class KeyedDoor {

        @PostMapping("/doors/payments")
        ResponseEntity<String> pay(@RequestHeader("Idempotency-Key") String key, @RequestHeader(value = "X-Fail", required = false) String fail) {
            entered.add(key);
            if (fail != null) {
                throw new IllegalStateException("a stand-in failure inside the command");
            }
            IdempotentExecutor.ExecutionOutcome outcome = executor.execute(unitOfWork(), new IdempotencyKey("test.door", key),
                    RequestFingerprint.sha256(key.getBytes(StandardCharsets.UTF_8)), uow -> {
                        effects.add(key);
                        return CommandResult.succeeded(StoredResponse.of(key.getBytes(StandardCharsets.UTF_8), "text/plain"));
                    });
            return ResponseEntity.status(HttpStatus.CREATED).body((outcome.replayed() ? "replayed " : "executed ") + key);
        }

        @NotShed(reason = "a stand-in for a provider's delivery")
        @PostMapping("/doors/callbacks")
        ResponseEntity<Void> callback() {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/doors/balance")
        String balance() {
            return "0.00";
        }
    }

    private static Connection unitOfWork() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("the claim store is in memory: " + method.getName());
                });
    }

    /** {@code platform.idempotency_record} in memory, one row per key (the payments world's double). */
    static final class Claims implements IdempotencyRecordStore<Connection> {

        final Map<IdempotencyKey, IdempotencyRecord> rows = new LinkedHashMap<>();

        @Override
        public ClaimOutcome claim(Connection unitOfWork, IdempotencyKey key, RequestFingerprint fingerprint, CorrelationId correlationId,
                Instant now, Instant expiresAt, Duration lease) {
            if (rows.containsKey(key)) {
                return ClaimOutcome.ALREADY_CLAIMED;
            }
            rows.put(key, new IdempotencyRecord(key, fingerprint, IdempotencyState.IN_PROGRESS, StoredResponse.empty(), correlationId, now,
                    expiresAt));
            return ClaimOutcome.CLAIMED;
        }

        @Override
        public Optional<IdempotencyRecord> find(Connection unitOfWork, IdempotencyKey key) {
            return Optional.ofNullable(rows.get(key));
        }

        @Override
        public boolean complete(Connection unitOfWork, IdempotencyKey key, IdempotencyState terminalState, StoredResponse response,
                Instant completedAt) {
            IdempotencyRecord held = rows.get(key);
            if (held == null || held.state() != IdempotencyState.IN_PROGRESS) {
                return false;
            }
            rows.put(key, new IdempotencyRecord(key, held.fingerprint(), terminalState, response, held.correlationId(), held.createdAt(),
                    held.expiresAt()));
            return true;
        }

        @Override
        public boolean reclaimIfLeaseExpired(Connection unitOfWork, IdempotencyKey key, CorrelationId correlationId, Instant now,
                Instant expiresAt, Duration lease) {
            return false;
        }
    }
}
