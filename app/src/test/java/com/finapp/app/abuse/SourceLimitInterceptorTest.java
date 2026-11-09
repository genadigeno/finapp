package com.finapp.app.abuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.finapp.platform.abuse.SourceBucket;
import com.finapp.platform.abuse.WindowCounter;
import com.finapp.platform.abuse.WindowLimit;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.testing.ScriptedWindowConnections;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/** The door's decision, with a double for the store (`P15-TSK-022`, {@code THREAT_MODEL.md} T1, T40, T47). */
@DisplayName("a public door counts by the connection's address and fails closed (P15-TSK-022)")
class SourceLimitInterceptorTest {

    private static final String SOURCE = "198.51.100.7";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ScriptedWindowConnections store = new ScriptedWindowConnections();

    /** Stands in for a controller: only its declaration is read. */
    static class Doors {
        @SourceLimited(Door.REGISTRATION)
        public void registration() {}

        @SourceLimited(Door.AUTHENTICATION)
        public void authentication() {}

        @SourceLimited(Door.RECOVERY)
        public void recovery() {}

        @SourceLimited(Door.RECOVERY_COMPLETION)
        public void recoveryCompletion() {}

        @SourceLimited(Door.CHANNEL_VERIFICATION)
        public void channelVerification() {}

        public void undeclared() {}
    }

    private static HandlerMethod handler(String method) throws NoSuchMethodException {
        Method declared = Doors.class.getMethod(method);
        return new HandlerMethod(new Doors(), declared);
    }

    private static SourceLimits limits(int limit) {
        Map<Door, WindowLimit> perDoor = new EnumMap<>(Door.class);
        for (Door door : Door.values()) {
            perDoor.put(door, new WindowLimit(limit, Duration.ofHours(1)));
        }
        return new SourceLimits(perDoor);
    }

    private SourceLimitInterceptor interceptor(int limit) {
        return new SourceLimitInterceptor(new WindowCounter(store::open, 8), limits(limit), new SourceLimitMeters(registry));
    }

    private static MockHttpServletRequest from(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/registrations");
        request.setRemoteAddr(address);
        return request;
    }

    private double meter(String door, String outcome) {
        return registry.get("finapp.abuse.requests").tag("type", door).tag("outcome", outcome).counter().count();
    }

    @Test
    @DisplayName("a door's count is keyed on the connection's address, in the door's own scope")
    void aDoorCountsByTheConnection() throws Exception {
        assertThat(interceptor(5).preHandle(from(SOURCE), new MockHttpServletResponse(), handler("registration"))).isTrue();

        ScriptedWindowConnections.Count count = store.counts().get(0);
        assertThat(count.scope()).isEqualTo("door.registration");
        assertThat(count.key()).isEqualTo(SourceBucket.keyOf(SOURCE));
        assertThat(count.limit()).isEqualTo(5);
        assertThat(meter("registration", "admitted")).isEqualTo(1);
    }

    @Test
    @DisplayName("a forged forwarded header does not change the key: the source is the address the connection has")
    void aForgedForwardedHeaderDoesNotChangeTheKey() throws Exception {
        MockHttpServletRequest forged = from(SOURCE);
        forged.addHeader("X-Forwarded-For", "203.0.113.9");
        forged.addHeader("Forwarded", "for=203.0.113.9");
        forged.addHeader("X-Real-IP", "203.0.113.9");
        forged.addHeader("True-Client-IP", "203.0.113.9");
        forged.addHeader("CF-Connecting-IP", "203.0.113.9");

        interceptor(5).preHandle(forged, new MockHttpServletResponse(), handler("registration"));

        assertThat(store.counts().get(0).key())
                .isEqualTo(SourceBucket.keyOf(SOURCE))
                .as("the forged address would have been another bucket").isNotEqualTo(SourceBucket.keyOf("203.0.113.9"));
    }

    @Test
    @DisplayName("over the limit the door refuses with 429 and the window's Retry-After, and the handler is not entered")
    void overTheLimitIsRefused() throws Exception {
        store.retryAfter(1800);
        SourceLimitInterceptor door = interceptor(1);
        door.preHandle(from(SOURCE), new MockHttpServletResponse(), handler("registration"));

        assertThatExceptionOfType(SourceLimitRefused.class)
                .isThrownBy(() -> door.preHandle(from(SOURCE), new MockHttpServletResponse(), handler("registration")))
                .satisfies(refused -> {
                    assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.TOO_MANY_REQUESTS);
                    assertThat(refused.retryAfterSeconds()).hasValue(1800);
                });
        assertThat(meter("registration", "refused")).isEqualTo(1);
    }

    @Test
    @DisplayName("every door fails closed when the count cannot be taken: 503, the handler not entered, registration included")
    void everyDoorFailsClosed() throws Exception {
        store.failingWith("53300");
        SourceLimitInterceptor door = interceptor(5);

        for (String declared : new String[] {"registration", "authentication", "recovery", "recoveryCompletion", "channelVerification"}) {
            HandlerMethod method = handler(declared);
            assertThatExceptionOfType(SourceLimitRefused.class)
                    .as(declared)
                    .isThrownBy(() -> door.preHandle(from(SOURCE), new MockHttpServletResponse(), method))
                    .satisfies(refused -> {
                        assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.TEMPORARILY_UNAVAILABLE);
                        assertThat(refused.retryAfterSeconds()).isEmpty();
                    });
        }
        assertThat(meter("registration", "store_unavailable")).isEqualTo(1);
        assertThat(meter("channel-verification", "store_unavailable")).isEqualTo(1);
    }

    @Test
    @DisplayName("the decision reads no body, so the answer says nothing about any identifier and no credential is derived")
    void theDecisionReadsNoBody() throws Exception {
        store.retryAfter(60);
        SourceLimitInterceptor door = interceptor(1);
        door.preHandle(new NoBody(SOURCE), new MockHttpServletResponse(), handler("registration"));

        assertThatExceptionOfType(SourceLimitRefused.class)
                .isThrownBy(() -> door.preHandle(new NoBody(SOURCE), new MockHttpServletResponse(), handler("registration")));
    }

    /** A request whose body cannot be read: the door must not need it. */
    private static final class NoBody extends MockHttpServletRequest {
        NoBody(String address) {
            super("POST", "/v1/registrations");
            setRemoteAddr(address);
        }

        @Override
        public jakarta.servlet.ServletInputStream getInputStream() {
            throw new AssertionError("the door read the body");
        }

        @Override
        public java.io.BufferedReader getReader() {
            throw new AssertionError("the door read the body");
        }
    }

    @Test
    @DisplayName("a handler with no declaration is not counted, and neither is anything that is not a handler method")
    void onlyADeclaredDoorIsCounted() throws Exception {
        SourceLimitInterceptor door = interceptor(5);

        assertThat(door.preHandle(from(SOURCE), new MockHttpServletResponse(), handler("undeclared"))).isTrue();
        assertThat(door.preHandle(from(SOURCE), new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(store.counts()).isEmpty();
    }

    @Test
    @DisplayName("a door with no limit, or a window below the floor, stops the instance at start")
    void aDoorWithoutALimitDoesNotStart() {
        Map<Door, WindowLimit> perDoor = new EnumMap<>(Door.class);
        perDoor.put(Door.REGISTRATION, new WindowLimit(5, Duration.ofHours(1)));
        assertThatNullPointerException().isThrownBy(() -> new SourceLimits(perDoor));

        for (Door door : Door.values()) {
            perDoor.put(door, new WindowLimit(5, Duration.ofHours(1)));
        }
        perDoor.put(Door.AUTHENTICATION, new WindowLimit(5, Duration.ofSeconds(59)));
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceLimits(perDoor));
    }
}
