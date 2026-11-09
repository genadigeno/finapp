package com.finapp.app.abuse;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.abuse.SourceBucket;
import com.finapp.platform.abuse.WindowCounter;
import com.finapp.platform.abuse.WindowLimit;
import com.finapp.platform.testing.ScriptedWindowConnections;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/**
 * Three instances, each built as the application builds one, sharing nothing but the store (`P15-TSK-022`).
 *
 * <p>The hermetic half of the counted case: a door counting in its instance's memory admits three times the limit here. That
 * PostgreSQL's arbitration really is exactly the limit is {@code WindowCounterDatabaseTest} and {@code SourceLimitClusterDatabaseTest},
 * owed to the exit's pass (C14).
 */
@DisplayName("three instances, one shared store: exactly the limit admitted (P15-TSK-022)")
class SourceLimitAcrossInstancesTest {

    private static final int INSTANCES = 3;
    private static final int LIMIT = 10;
    private static final int ATTEMPTS_EACH = 12;
    private static final String SOURCE = "198.51.100.7";

    static class Registration {
        @SourceLimited(Door.REGISTRATION)
        public void register() {}
    }

    @Test
    @DisplayName("a burst across three instances admits exactly the limit, not the limit times the instance count")
    void exactlyTheLimitAcrossInstances() throws Exception {
        Method declared = Registration.class.getMethod("register");
        HandlerMethod door = new HandlerMethod(new Registration(), declared);
        ScriptedWindowConnections shared = new ScriptedWindowConnections();
        List<SourceLimitInterceptor> instances = new ArrayList<>();
        for (int i = 0; i < INSTANCES; i++) {
            Map<Door, WindowLimit> limits = new EnumMap<>(Door.class);
            for (Door each : Door.values()) {
                limits.put(each, new WindowLimit(LIMIT, Duration.ofHours(1)));
            }
            // Each instance is its own counter, its own limits and its own meters: nothing but the store is shared.
            instances.add(new SourceLimitInterceptor(
                    new WindowCounter(shared::open, 64), new SourceLimits(limits), new SourceLimitMeters(new SimpleMeterRegistry())));
        }

        ExecutorService threads = Executors.newFixedThreadPool(INSTANCES * ATTEMPTS_EACH);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (SourceLimitInterceptor instance : instances) {
                for (int i = 0; i < ATTEMPTS_EACH; i++) {
                    Callable<Boolean> attempt = () -> {
                        start.await();
                        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/registrations");
                        request.setRemoteAddr(SOURCE);
                        try {
                            return instance.preHandle(request, new MockHttpServletResponse(), door);
                        } catch (SourceLimitRefused refused) {
                            return false;
                        }
                    };
                    attempts.add(threads.submit(attempt));
                }
            }
            start.countDown();
            int admitted = 0;
            int refused = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get()) {
                    admitted++;
                } else {
                    refused++;
                }
            }
            assertThat(admitted).as("exactly the limit across every instance").isEqualTo(LIMIT);
            assertThat(refused).isEqualTo(INSTANCES * ATTEMPTS_EACH - LIMIT);
            assertThat(shared.admittedFor("door.registration", SourceBucket.keyOf(SOURCE))).isEqualTo(LIMIT);
        } finally {
            threads.shutdownNow();
        }
    }
}
