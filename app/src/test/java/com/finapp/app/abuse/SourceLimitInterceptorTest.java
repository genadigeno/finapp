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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/** The door's decision, with a double for the store (`P15-TSK-022`, {@code THREAT_MODEL.md} T1, T40, T47). */
@DisplayName("a public door counts by the connection's address and fails closed (P15-TSK-022)")
class SourceLimitInterceptorTest {

    private static final String SOURCE = "198.51.100.7";

    private static final long ANY_SWEEP = Long.MAX_VALUE;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ScriptedWindowConnections store = new ScriptedWindowConnections();
    private final SourceBucket buckets = new SourceBucket("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

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

    private static Map<Door, WindowLimit> perDoor(int limit) {
        Map<Door, WindowLimit> perDoor = new EnumMap<>(Door.class);
        for (Door door : Door.values()) {
            perDoor.put(door, new WindowLimit(limit, Duration.ofHours(1)));
        }
        return perDoor;
    }

    private SourceLimitInterceptor interceptor(int limit) {
        return interceptor(limit, Map.of());
    }

    private SourceLimitInterceptor interceptor(int limit, Map<Door, Integer> siteLimits) {
        return new SourceLimitInterceptor(
                new WindowCounter(store::open, 8, Duration.ZERO),
                buckets,
                new SourceLimits(perDoor(limit), siteLimits, ANY_SWEEP),
                new SourceLimitMeters(registry));
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
        assertThat(count.key()).isEqualTo(buckets.of(SOURCE).key());
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
                .isEqualTo(buckets.of(SOURCE).key())
                .as("the forged address would have been another bucket").isNotEqualTo(buckets.of("203.0.113.9").key());
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
    @DisplayName("an IPv6 /48 is counted after its /64: the /64's limit spends none of its neighbours', and the /48's limit holds across all of them")
    void aSiteIsCountedAfterItsSource() throws Exception {
        store.retryAfter(900);
        SourceLimitInterceptor door = interceptor(2, Map.of(Door.REGISTRATION, 3));
        String a = "2001:db8:1:1::1";
        String b = "2001:db8:1:2::1";
        String site = buckets.of(a).site().orElseThrow();

        assertThat(door.preHandle(from(a), new MockHttpServletResponse(), handler("registration"))).isTrue();
        assertThat(door.preHandle(from(a), new MockHttpServletResponse(), handler("registration"))).isTrue();
        assertThatExceptionOfType(SourceLimitRefused.class)
                .as("the /64 is past its own limit")
                .isThrownBy(() -> door.preHandle(from(a), new MockHttpServletResponse(), handler("registration")));
        assertThat(store.admittedFor("door.registration.site", site)).as("that refusal spent nothing of the /48's").isEqualTo(2);

        assertThat(door.preHandle(from(b), new MockHttpServletResponse(), handler("registration"))).isTrue();
        assertThatExceptionOfType(SourceLimitRefused.class)
                .as("the /48 is spent, across every /64 in it")
                .isThrownBy(() -> door.preHandle(from(b), new MockHttpServletResponse(), handler("registration")))
                .satisfies(refused -> assertThat(refused.retryAfterSeconds()).hasValue(900));
        assertThat(store.admittedFor("door.registration.site", site)).isEqualTo(3);
    }

    @Test
    @DisplayName("an IPv4 source, and a door with no site limit, take one count")
    void onlyAnIpv6SourceOnASiteDoorTakesTwo() throws Exception {
        SourceLimitInterceptor door = interceptor(5, Map.of(Door.REGISTRATION, 50));

        door.preHandle(from(SOURCE), new MockHttpServletResponse(), handler("registration"));
        door.preHandle(from("2001:db8:1:1::1"), new MockHttpServletResponse(), handler("channelVerification"));

        assertThat(store.counts()).hasSize(2).extracting(ScriptedWindowConnections.Count::scope)
                .containsExactly("door.registration", "door.channel-verification");
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
        assertThat(meter("registration", "store_unreachable")).isEqualTo(1);
        assertThat(meter("channel-verification", "store_unreachable")).isEqualTo(1);
    }

    @Test
    @DisplayName("the cause of a 503 is a closed tag: unreachable, a refused statement, and a full instance are three series")
    void theCauseOfA503IsTagged() throws Exception {
        store.failingWith("42P01");
        SourceLimitInterceptor broken = interceptor(5);
        assertThatExceptionOfType(SourceLimitRefused.class)
                .isThrownBy(() -> broken.preHandle(from(SOURCE), new MockHttpServletResponse(), handler("registration")));
        assertThat(meter("registration", "store_error")).isEqualTo(1);
        assertThat(meter("registration", "store_unreachable")).isZero();

        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch inside = new CountDownLatch(1);
        ScriptedWindowConnections busy = new ScriptedWindowConnections().beforeEachCount(() -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        SourceLimitInterceptor full = new SourceLimitInterceptor(
                new WindowCounter(busy::open, 1, Duration.ZERO),
                buckets,
                new SourceLimits(perDoor(5), Map.of(), ANY_SWEEP),
                new SourceLimitMeters(registry));
        Thread holder = new Thread(() -> {
            try {
                full.preHandle(from(SOURCE), new MockHttpServletResponse(), handler("registration"));
            } catch (NoSuchMethodException unreachable) {
                throw new AssertionError(unreachable);
            }
        });
        holder.start();
        try {
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatExceptionOfType(SourceLimitRefused.class)
                    .isThrownBy(() -> full.preHandle(from(SOURCE), new MockHttpServletResponse(), handler("registration")))
                    .satisfies(refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.TEMPORARILY_UNAVAILABLE));
            assertThat(meter("registration", "at_capacity")).isEqualTo(1);
        } finally {
            release.countDown();
            holder.join(10_000);
        }
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
        Map<Door, WindowLimit> partial = new EnumMap<>(Door.class);
        partial.put(Door.REGISTRATION, new WindowLimit(5, Duration.ofHours(1)));
        assertThatNullPointerException().isThrownBy(() -> new SourceLimits(partial, Map.of(), ANY_SWEEP));

        Map<Door, WindowLimit> tooShort = perDoor(5);
        tooShort.put(Door.AUTHENTICATION, new WindowLimit(5, Duration.ofSeconds(59)));
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceLimits(tooShort, Map.of(), ANY_SWEEP));
    }

    @Test
    @DisplayName("windows one instance's sweep could not keep up with stop the instance at start, whatever else is configured")
    void aConfigurationTheSweepCannotHoldDoesNotStart() {
        Map<Door, WindowLimit> everyMinute = new EnumMap<>(Door.class);
        for (Door door : Door.values()) {
            everyMinute.put(door, new WindowLimit(5, Duration.ofMinutes(1)));
        }
        long oneInstancesSweepAnHour = 1_000L * 20 * 60;

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SourceLimits(everyMinute, Map.of(), oneInstancesSweepAnHour))
                .withMessageContaining("rows an hour");
    }

    @Test
    @DisplayName("a limit per site below the limit per source is a misconfiguration, refused at start")
    void aSiteLimitBelowItsSourceLimitIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceLimits(perDoor(10), Map.of(Door.REGISTRATION, 9), ANY_SWEEP));
    }
}
