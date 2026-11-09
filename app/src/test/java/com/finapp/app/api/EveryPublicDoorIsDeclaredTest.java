package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.abuse.Door;
import com.finapp.app.abuse.NotSourceLimited;
import com.finapp.app.abuse.SourceLimitInterceptor;
import com.finapp.app.abuse.SourceLimited;
import com.finapp.app.api.EdgeRoutes.Tier;
import com.finapp.app.session.Unauthenticated;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * No route a stranger can reach is left unconsidered (`P15-TSK-022`), read from what the application serves.
 *
 * <p>The edge's door tier and the application's counted doors are the same set: a route in one and not the other is either a door with
 * no cluster-wide limit or a limit no edge tier fronts. Booted with every provider's URL, for the {@code EdgeAllowListTest} reason.
 */
@Tag("slice")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "finapp.fx.provider.url=http://127.0.0.1:9",
            "finapp.corridor.provider.url=http://127.0.0.1:9"
        })
@DisplayName("every unauthenticated route is a counted door or says why not (P15-TSK-022)")
class EveryPublicDoorIsDeclaredTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private Map<String, HandlerMethod> served;

    @BeforeEach
    void readTheRoutes() {
        served = new TreeMap<>();
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

    private static boolean limited(HandlerMethod handler) {
        return handler.getMethodAnnotation(SourceLimited.class) != null;
    }

    private static boolean exempt(HandlerMethod handler) {
        return handler.getMethodAnnotation(NotSourceLimited.class) != null
                || handler.getBeanType().isAnnotationPresent(NotSourceLimited.class);
    }

    private static boolean unauthenticated(HandlerMethod handler) {
        return handler.getMethodAnnotation(Unauthenticated.class) != null
                || handler.getBeanType().isAnnotationPresent(Unauthenticated.class);
    }

    @Test
    @DisplayName("every @Unauthenticated route declares a door or an exemption, exactly one of them")
    void everyUnauthenticatedRouteIsDeclared() {
        List<String> undeclared = new ArrayList<>();
        served.forEach((route, handler) -> {
            if (unauthenticated(handler) && limited(handler) == exempt(handler)) {
                undeclared.add(route);
            }
        });
        assertThat(undeclared).as("an unauthenticated route with no statement about its per-source limit").isEmpty();
    }

    @Test
    @DisplayName("a counted door is an edge door, and an edge door is counted; the exemptions are the provider callbacks and nothing else")
    void theDoorsAreTheEdgesDoors() {
        List<String> wrong = new ArrayList<>();
        served.forEach((route, handler) -> {
            if (!unauthenticated(handler)) {
                if (limited(handler) || exempt(handler)) {
                    wrong.add(route + " is not unauthenticated, yet declares a per-source decision");
                }
                return;
            }
            Tier tier = EdgeRoutes.tier(route.substring(route.indexOf(' ') + 1), handler);
            if (limited(handler) != (tier == Tier.DOOR)) {
                wrong.add(route + " is counted=" + limited(handler) + " on tier " + tier);
            }
            if (exempt(handler) != (tier == Tier.CALLBACK)) {
                wrong.add(route + " is exempt=" + exempt(handler) + " on tier " + tier);
            }
        });
        assertThat(wrong).isEmpty();
    }

    @Test
    @DisplayName("every door of the closed set is served, and each only once")
    void everyDoorIsServedOnce() {
        Set<Door> seen = EnumSet.noneOf(Door.class);
        served.values().stream().filter(EveryPublicDoorIsDeclaredTest::limited).distinct().forEach(handler -> {
            assertThat(seen.add(handler.getMethodAnnotation(SourceLimited.class).value()))
                    .as("a door handled by two handlers shares one limit: %s", handler)
                    .isTrue();
        });
        assertThat(seen).containsExactlyInAnyOrder(Door.values());
    }

    @Test
    @DisplayName("the count runs after the idempotency check, so a request that check refuses costs no database write")
    void theCountRunsLast() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/registrations");
        ServletRequestPathUtils.parseAndCache(request);

        HandlerExecutionChain chain = mappings.getHandler(request);

        assertThat(chain).as("the route was found").isNotNull();
        List<Class<?>> order = new ArrayList<>();
        for (HandlerInterceptor interceptor : chain.getInterceptorList()) {
            order.add(interceptor.getClass());
        }
        assertThat(order).as("the door's count is registered").contains(SourceLimitInterceptor.class);
        assertThat(order.indexOf(SourceLimitInterceptor.class))
                .as("in %s", order)
                .isGreaterThan(order.indexOf(IdempotencyKeyInterceptor.class));
        assertThat(order.indexOf(IdempotencyKeyInterceptor.class)).isGreaterThanOrEqualTo(0);
    }
}
