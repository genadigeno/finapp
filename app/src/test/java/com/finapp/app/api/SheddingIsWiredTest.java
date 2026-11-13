package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.abuse.SourceLimitInterceptor;
import com.finapp.app.api.EdgeRoutes.Tier;
import com.finapp.app.merchant.MerchantKeyAuthenticationInterceptor;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.app.shedding.LoadShedding;
import com.finapp.app.shedding.NotShed;
import com.finapp.app.shedding.ShedAtTheDoor;
import com.finapp.app.shedding.ShedOnTheDeadline;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * The shedding door as the application wires it (`P16-TSK-016`, ADR-0137), read from what it serves: the door before everything that
 * borrows a connection, the deadline after both authentications and before the per-source count, every money-moving route a command,
 * the exemptions the provider callbacks alone, and the bounds built against the real pool. Booted as {@code EveryPublicDoorIsDeclaredTest}
 * is, so the two share one context.
 */
@Tag("slice")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "finapp.fx.provider.url=http://127.0.0.1:9",
            "finapp.corridor.provider.url=http://127.0.0.1:9"
        })
@DisplayName("the shedding door is wired before every borrow and covers every money-moving route (P16-TSK-016)")
class SheddingIsWiredTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Autowired private LoadShedding shedding;
    @Autowired private org.springframework.context.ApplicationContext context;
    @Autowired private MeterRegistry registry;

    private final Map<String, HandlerMethod> served = new TreeMap<>();

    @BeforeEach
    void readTheRoutes() {
        for (Map.Entry<RequestMappingInfo, HandlerMethod> route : mappings.getHandlerMethods().entrySet()) {
            if (!route.getValue().getBeanType().getName().startsWith("com.finapp.")) {
                continue;
            }
            for (RequestMethod method : route.getKey().getMethodsCondition().getMethods()) {
                for (String path : route.getKey().getPatternValues()) {
                    served.put(method.name() + " " + path, route.getValue());
                }
            }
        }
        assertThat(served).as("the routes were read").hasSizeGreaterThan(100);
    }

    private List<Class<?>> chainOf(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (!"GET".equals(method)) {
            // The command routes consume JSON: without the type the mapping refuses before any chain is built.
            request.setContentType("application/json");
        }
        ServletRequestPathUtils.parseAndCache(request);
        HandlerExecutionChain chain = mappings.getHandler(request);
        assertThat(chain).as("%s %s was found", method, path).isNotNull();
        List<Class<?>> order = new ArrayList<>();
        for (HandlerInterceptor interceptor : chain.getInterceptorList()) {
            order.add(interceptor.getClass());
        }
        return order;
    }

    @Test
    @DisplayName("the door runs before the idempotency check, both authentications and the count; the deadline after both and before the count")
    void theDoorIsFirstAndTheDeadlineFollowsAuthentication() throws Exception {
        for (String[] route : new String[][] {{"POST", "/v1/transfers"}, {"POST", "/v1/registrations"}, {"GET", "/v1/merchant/me"}}) {
            List<Class<?>> order = chainOf(route[0], route[1]);
            int door = order.indexOf(ShedAtTheDoor.class);
            int deadline = order.indexOf(ShedOnTheDeadline.class);
            assertThat(door).as("the door on %s in %s", route[1], order).isNotNegative();
            assertThat(door).as("before every interceptor of the platform's own").isLessThan(order.indexOf(IdempotencyKeyInterceptor.class))
                    .isLessThan(order.indexOf(SessionAuthenticationInterceptor.class))
                    .isLessThan(order.indexOf(MerchantKeyAuthenticationInterceptor.class)).isLessThan(order.indexOf(SourceLimitInterceptor.class));
            assertThat(deadline).as("after both authentications and before the count, in %s", order)
                    .isGreaterThan(order.indexOf(SessionAuthenticationInterceptor.class))
                    .isGreaterThan(order.indexOf(MerchantKeyAuthenticationInterceptor.class))
                    .isLessThan(order.indexOf(SourceLimitInterceptor.class));
        }
    }

    @Test
    @DisplayName("every keyed route and every other method that is not safe is a command; reads are reads; nothing else is exempt")
    void everyMoneyMovingRouteIsACommand() {
        List<String> wrong = new ArrayList<>();
        int commands = 0;
        int keyed = 0;
        for (Map.Entry<String, HandlerMethod> route : served.entrySet()) {
            String method = route.getKey().substring(0, route.getKey().indexOf(' '));
            HandlerMethod handler = route.getValue();
            Optional<LoadShedding.Type> type = ShedAtTheDoor.classOf(handler, method);
            boolean safe = List.of("GET", "HEAD", "OPTIONS", "TRACE").contains(method);
            boolean declaresKey = handler.getMethodAnnotation(RequiresIdempotencyKey.class) != null
                    || handler.getBeanType().isAnnotationPresent(RequiresIdempotencyKey.class);
            if (type.isEmpty()) {
                if (ShedAtTheDoor.notShed(handler).map(NotShed::reason).filter(reason -> !reason.isBlank()).isEmpty()) {
                    wrong.add(route.getKey() + " is not shed and states no reason");
                }
                continue;
            }
            if (type.get() != (safe ? LoadShedding.Type.READ : LoadShedding.Type.COMMAND)) {
                wrong.add(route.getKey() + " is a " + type.get());
            }
            if (!safe && declaresKey) {
                keyed++;
            }
            if (!safe) {
                commands++;
            }
        }
        assertThat(wrong).isEmpty();
        assertThat(keyed).as("the keyed money-moving routes were seen").isGreaterThan(20);
        assertThat(commands).isGreaterThan(keyed);
    }

    @Test
    @DisplayName("the routes not shed are the edge's callback tier, and the callback tier is not shed")
    void theExemptionsAreTheProviderCallbacks() {
        List<String> wrong = new ArrayList<>();
        int exempt = 0;
        for (Map.Entry<String, HandlerMethod> route : served.entrySet()) {
            String method = route.getKey().substring(0, route.getKey().indexOf(' '));
            String path = route.getKey().substring(route.getKey().indexOf(' ') + 1);
            boolean notShed = ShedAtTheDoor.classOf(route.getValue(), method).isEmpty();
            boolean callback = EdgeRoutes.tier(path, route.getValue()) == Tier.CALLBACK;
            if (notShed != callback) {
                wrong.add(route.getKey() + " is notShed=" + notShed + ", callback=" + callback);
            }
            if (notShed) {
                exempt++;
            }
        }
        assertThat(wrong).isEmpty();
        assertThat(exempt).as("the five providers' callbacks").isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("the bounds were built against the real pool: its size and borrow wait read from it, the permits fewer, the meter eager")
    void theBoundsFitTheRealPool() throws Exception {
        HikariDataSource pool = WriterPool.of(context);
        assertThat(shedding.pool().size()).isEqualTo(pool.getMaximumPoolSize());
        assertThat(shedding.pool().borrowWait()).isEqualTo(Duration.ofMillis(pool.getConnectionTimeout()));
        assertThat(shedding.bounds().commandPermits()).as("commands alone never hold the whole pool").isLessThan(pool.getMaximumPoolSize());
        assertThat(shedding.bounds().deadline()).as("a command is left at least one borrow's wait").isGreaterThan(shedding.pool().borrowWait());
        assertThat(registry.find("finapp.shedding.requests").counters()).as("two classes by four outcomes, before any request").hasSize(8);
    }
}
