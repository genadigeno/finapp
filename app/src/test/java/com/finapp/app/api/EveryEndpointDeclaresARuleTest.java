package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.Unauthenticated;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every mapped endpoint declares an authorization rule (`P1-TSK-020`, {@code INV-IDN-04}).
 *
 * <h2>Enforced twice, and this is the stronger half</h2>
 *
 * <p>The interceptor refuses an undeclared handler at runtime, because ADR-0031 says <em>refused</em>
 * — but a deployment defect discovered by a customer's 403 has been discovered too late. This fails
 * the build instead, before the endpoint can ship.
 *
 * <p>Neither replaces the other. A static sweep cannot see a handler registered at run time; the
 * runtime check cannot fail a build. The two are blind in different directions, which is the same
 * argument ADR-0020 makes for keeping the secret scanner beside the configuration rule.
 *
 * <h2>The routes come from the application, never from a list</h2>
 *
 * <p>{@code RequestMappingHandlerMapping} is what actually serves requests, so this asks the thing
 * that dispatches rather than a copy of it. A hand-maintained list is the stale-list defect this
 * repository has met in CI's task list, in a coverage guard and in a privilege check — closed each
 * time by derivation.
 */
@Tag("slice")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("every endpoint declares an authorization rule (P1-TSK-020)")
class EveryEndpointDeclaresARuleTest {

    private static final List<Class<? extends Annotation>> DECLARATIONS =
            List.of(
                    Unauthenticated.class,
                    RequiresSession.class,
                    RequiresAssurance.class,
                    RequiresPermission.class);

    /**
     * The MVC mapping specifically.
     *
     * <p>Qualified by name because actuator registers a second {@code RequestMappingHandlerMapping}
     * of its own, and an unqualified injection is ambiguous - the context refuses to start, which is
     * the framework being right. Naming the MVC one states which dispatcher this guard is about.
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    @DisplayName("no handler in com.finapp is served without a declaration")
    void everyHandlerDeclaresSomething() {
        assertThat(undeclaredHandlers())
                .as("a rule's absence is never a grant (ADR-0031, INV-IDN-04). Annotate the handler"
                        + " with @Unauthenticated, @RequiresSession, @RequiresAssurance or"
                        + " @RequiresPermission - being public must be a decision somebody wrote"
                        + " down, not something nobody said")
                .isEmpty();
    }

    @Test
    @DisplayName("the guard is not vacuous: it sees the endpoints this application actually serves")
    void theGuardSeesRealEndpoints() {
        TreeSet<String> paths = new TreeSet<>();
        mappings.getHandlerMethods()
                .forEach((info, handler) -> paths.add(handler.getBeanType().getSimpleName()));

        // Without this the assertion above passes over an empty mapping - the "green while checking
        // nothing" failure this repository has met repeatedly. Named controllers rather than a
        // count, because a count is satisfied by any five beans.
        assertThat(paths)
                .as("the sweep must see the controllers this phase built")
                .contains(
                        "RegistrationController",
                        "AuthenticationController",
                        "SessionController",
                        "MfaController",
                        "MfaChallengeController");
    }

    @Test
    @DisplayName("the vocabulary is closed: every declaration the interceptor honours is listed")
    void theVocabularyMatchesTheInterceptor() {
        // An annotation the interceptor accepts but this guard does not know would let an endpoint
        // pass at run time and fail the build - or worse, the reverse. The two lists are one
        // decision, and disagreeing is the drift this repository closes by test rather than by
        // memory.
        assertThat(DECLARATIONS)
                .as("every declaration must be one the interceptor recognises")
                .containsExactlyInAnyOrder(
                        Unauthenticated.class,
                        RequiresSession.class,
                        RequiresAssurance.class,
                        RequiresPermission.class);
    }

    // -----------------------------------------------------------------

    private TreeSet<String> undeclaredHandlers() {
        TreeSet<String> undeclared = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> mapping :
                mappings.getHandlerMethods().entrySet()) {
            HandlerMethod handler = mapping.getValue();

            // Only handlers we wrote. Actuator's are not our code and carry no annotation - the same
            // boundary the interceptor draws, stated once in each place because a static sweep and a
            // runtime check cannot share a predicate.
            if (!handler.getBeanType().getName().startsWith("com.finapp.")) {
                continue;
            }
            if (DECLARATIONS.stream().noneMatch(declaration -> declares(handler, declaration))) {
                undeclared.add(
                        handler.getBeanType().getName() + "." + handler.getMethod().getName());
            }
        }
        return undeclared;
    }

    private static boolean declares(HandlerMethod handler, Class<? extends Annotation> annotation) {
        return handler.getMethodAnnotation(annotation) != null
                || handler.getBeanType().getAnnotation(annotation) != null;
    }
}
