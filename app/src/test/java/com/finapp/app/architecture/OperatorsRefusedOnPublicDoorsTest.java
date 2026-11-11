package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.session.Unauthenticated;
import com.finapp.app.surface.OperatorsRefusedOnPublic;
import com.finapp.app.surface.RequestSurfaces;
import com.finapp.app.surface.SurfaceDoor;
import com.finapp.platform.api.ApiVersion;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every door that issues or strengthens a session, or changes what can prove an identity, refuses staff on the public surface,
 * and which handlers those are is read from the code (`P15-TSK-042`; {@code THREAT_MODEL.md} T3, T31).
 *
 * <p><strong>Enumerated from the bytecode, not from a list of routes.</strong> A handler is a door when anything it calls, however
 * deep - lambda bodies, method references and every override of a method called through its interface included - reaches one of
 * {@link #ORIGINS}: issuing a session, rotating or elevating one, beginning or confirming a factor,
 * replacing a password, starting or completing a recovery, or naming the channel recovery sends its token to. Every such handler
 * must declare {@link OperatorsRefusedOnPublic}, and every handler declaring it must reach one, so a door added later without the
 * declaration fails here, and so does a declaration left on a handler that no longer is one. The origins are a hand list, as
 * {@code MfaBypassPathsAreEnumeratedTest}'s are, and held the same way: each must exist.
 *
 * <p><strong>Not origins, with the reason.</strong> {@code ContactChannelService.verify} proves control of a mailbox for a channel
 * already named; staff can only have named one on the operator surface or before their grant, since naming one is an origin.
 * {@code IdentityRegistration.create} makes an identity that holds no role. Ending a session weakens one.
 */
@Tag("architecture")
@DisplayName("every door that issues or strengthens access refuses staff on the public surface, read from the bytecode (P15-TSK-042)")
class OperatorsRefusedOnPublicDoorsTest {

    /** What makes a handler a door, and what each one does. */
    private static final Map<String, String> ORIGINS = Map.of(
            "com.finapp.identity.SessionIssue.issue", "issues a session",
            "com.finapp.identity.SessionRotation.rotate", "rotates or elevates a session",
            "com.finapp.identity.MfaEnrolmentService.begin", "begins a second factor",
            "com.finapp.identity.MfaEnrolmentService.confirm", "confirms a second factor",
            "com.finapp.identity.CredentialChange.apply", "replaces a password",
            "com.finapp.identity.RecoveryService.initiate", "starts a recovery",
            "com.finapp.identity.RecoveryService.complete", "replaces a password through recovery",
            "com.finapp.identity.ContactChannelService.add", "names where recovery sends its token");

    private static final int DEPTH = 12;

    private static JavaClasses production;
    private static Map<String, Method> handlers;

    @BeforeAll
    static void read() throws ClassNotFoundException {
        production = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.finapp");
        handlers = new TreeMap<>();
        for (JavaClass javaClass : production) {
            if (!javaClass.isAnnotatedWith(RestController.class)) {
                continue;
            }
            Class<?> controller = Class.forName(javaClass.getName());
            for (Method method : controller.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    handlers.put(controller.getName() + "." + method.getName(), method);
                }
            }
        }
    }

    @Test
    @DisplayName("a handler reaching an origin declares the door, and a handler declaring it reaches one")
    void everyDoorDeclaresItself() {
        Set<String> reaching = new TreeSet<>();
        Set<String> declaring = new TreeSet<>();
        handlers.forEach((name, method) -> {
            if (!reached(method).isEmpty()) {
                reaching.add(name);
            }
            if (method.isAnnotationPresent(OperatorsRefusedOnPublic.class)) {
                declaring.add(name);
            }
        });
        assertThat(reaching).as("the doors, read from what each handler reaches").isNotEmpty();
        assertThat(declaring).as("a handler that issues or strengthens access must declare @OperatorsRefusedOnPublic, and only such a"
                + " handler may").isEqualTo(reaching);
    }

    @Test
    @DisplayName("each door is declared once, and each declaration names its own route as Spring maps it")
    void eachDoorNamesItsRoute() {
        Map<SurfaceDoor, String> declared = new TreeMap<>();
        handlers.values().stream().filter(method -> method.isAnnotationPresent(OperatorsRefusedOnPublic.class)).forEach(method -> {
            SurfaceDoor door = method.getAnnotation(OperatorsRefusedOnPublic.class).value();
            assertThat(declared.put(door, method.getName())).as("%s is declared twice", door).isNull();
            assertThat(door.operation()).as("%s.%s", method.getDeclaringClass().getSimpleName(), method.getName()).isEqualTo(route(method));
        });
        assertThat(declared.keySet()).as("every door the meter and the record name has its handler").containsExactlyInAnyOrder(SurfaceDoor.values());
    }

    @Test
    @DisplayName("a door that needs no session reads the surface itself and hands it on: the interceptor has nobody to judge there")
    void everyUnauthenticatedDoorReadsTheSurface() {
        Set<String> missing = new TreeSet<>();
        handlers.forEach((name, method) -> {
            boolean unauthenticated = method.isAnnotationPresent(Unauthenticated.class)
                    || method.getDeclaringClass().isAnnotationPresent(Unauthenticated.class);
            if (unauthenticated && method.isAnnotationPresent(OperatorsRefusedOnPublic.class)
                    && callsFrom(codeUnit(method)).stream().noneMatch(call -> call.equals(RequestSurfaces.class.getName() + ".of"))) {
                missing.add(name);
            }
        });
        assertThat(missing).isEmpty();
        assertThat(handlers.values().stream().filter(method -> method.isAnnotationPresent(OperatorsRefusedOnPublic.class))
                .filter(method -> method.isAnnotationPresent(Unauthenticated.class)
                        || method.getDeclaringClass().isAnnotationPresent(Unauthenticated.class)))
                .as("sign-in and the two recovery doors").hasSize(3);
    }

    @Test
    @DisplayName("every origin names a method that exists, so no door can fall out of the sweep by a rename")
    void everyOriginExists() {
        Set<String> stale = new TreeSet<>();
        for (String origin : ORIGINS.keySet()) {
            String owner = origin.substring(0, origin.lastIndexOf('.'));
            String name = origin.substring(origin.lastIndexOf('.') + 1);
            if (!production.contain(owner) || production.get(owner).getMethods().stream().noneMatch(method -> method.getName().equals(name))) {
                stale.add(origin);
            }
        }
        assertThat(stale).isEmpty();
        assertThat(handlers).as("the handler mapping was read").hasSizeGreaterThan(100);
    }

    // -----------------------------------------------------------------

    /** The origins {@code handler} reaches, following every call into production code. */
    private static Set<String> reached(Method handler) {
        Set<String> found = new TreeSet<>();
        Set<JavaCodeUnit> seen = new HashSet<>();
        Deque<Map.Entry<JavaCodeUnit, Integer>> queue = new ArrayDeque<>();
        queue.add(Map.entry(codeUnit(handler), 0));
        while (!queue.isEmpty()) {
            Map.Entry<JavaCodeUnit, Integer> next = queue.poll();
            JavaCodeUnit unit = next.getKey();
            if (!seen.add(unit) || next.getValue() > DEPTH) {
                continue;
            }
            for (JavaCodeUnit body : withItsLambdas(unit)) {
                for (JavaMethodCall call : body.getMethodCallsFromSelf()) {
                    follow(call.getTargetOwner().getName(), call.getName(), call.getTarget().resolveMember(), next.getValue() + 1,
                            found, queue);
                }
                for (JavaMethodReference reference : body.getMethodReferencesFromSelf()) {
                    follow(reference.getTargetOwner().getName(), reference.getName(), reference.getTarget().resolveMember(),
                            next.getValue() + 1, found, queue);
                }
            }
        }
        return found;
    }

    /**
     * One call or method reference: an origin is recorded; a production method is followed, and so is every override of it, since
     * a call through an interface or a superclass runs whichever implementation is wired.
     */
    private static void follow(String owner, String name, java.util.Optional<JavaMethod> resolved, int depth, Set<String> found,
            Deque<Map.Entry<JavaCodeUnit, Integer>> queue) {
        if (ORIGINS.containsKey(owner + "." + name)) {
            found.add(owner + "." + name);
            return;
        }
        if (!owner.startsWith("com.finapp.")) {
            return;
        }
        resolved.ifPresent(method -> {
            queue.add(Map.entry(method, depth));
            String[] parameters = method.getRawParameterTypes().stream().map(JavaClass::getName).toArray(String[]::new);
            for (JavaClass implementation : method.getOwner().getAllSubclasses()) {
                implementation.tryGetMethod(method.getName(), parameters).ifPresent(override -> queue.add(Map.entry(override, depth)));
            }
        });
    }

    /** A method and the lambda bodies the compiler split out of it, in case the importer has not attributed them to it. */
    private static Set<JavaCodeUnit> withItsLambdas(JavaCodeUnit unit) {
        Set<JavaCodeUnit> bodies = new HashSet<>();
        bodies.add(unit);
        String prefix = "lambda$" + unit.getName() + "$";
        for (JavaMethod method : unit.getOwner().getMethods()) {
            if (method.getName().startsWith(prefix)) {
                bodies.add(method);
            }
        }
        return bodies;
    }

    private static Set<String> callsFrom(JavaCodeUnit unit) {
        Set<String> calls = new TreeSet<>();
        for (JavaCodeUnit body : withItsLambdas(unit)) {
            body.getMethodCallsFromSelf().forEach(call -> calls.add(call.getTargetOwner().getName() + "." + call.getName()));
        }
        return calls;
    }

    private static JavaCodeUnit codeUnit(Method method) {
        return production.get(method.getDeclaringClass()).getMethod(method.getName(), method.getParameterTypes());
    }

    /** {@code <METHOD> /v1<class path><method path>}, read from the mapping annotations as Spring merges them. */
    private static String route(Method method) {
        RequestMapping onMethod = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        RequestMapping onClass = AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), RequestMapping.class);
        String classPath = onClass == null ? "" : first(onClass.path());
        return onMethod.method()[0].name() + " " + ApiVersion.CURRENT_PREFIX + classPath + first(onMethod.path());
    }

    private static String first(String[] paths) {
        return Arrays.stream(paths).findFirst().orElse("");
    }
}
