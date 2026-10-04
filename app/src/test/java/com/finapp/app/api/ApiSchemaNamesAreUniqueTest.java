package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every type the published contract names has a simple name of its own (`P9-TSK-008`). The
 * OpenAPI generator keys schemas by SIMPLE class name, so two records called {@code PairView} in
 * two controllers become ONE schema - and one door silently publishes the other's shape. The
 * contract diff cannot see it when the survivor is the existing schema; this guard can.
 * {@code P9-TSK-007} lost KYC's {@code DecisionRequest} this way and {@code P9-TSK-008} nearly
 * published the operator's {@code PairView} for the customer's pairs. A planted pair proves it bites.
 */
@DisplayName("every contract type's simple name is unique (P9-TSK-008)")
class ApiSchemaNamesAreUniqueTest {

    /**
     * The collisions that predate this guard, each a recorded debt (CURRENT_STATE.md section Known
     * Architectural Debt): the operator review door's {@code OwnerView} is published as the KYB
     * door's shape. The map may only shrink - a new collision fails the build.
     */
    private static final Map<String, Set<String>> KNOWN = Map.of(
            "OwnerView", new TreeSet<>(Set.of(
                    "com.finapp.app.kyc.KybController$OwnerView", "com.finapp.app.kyc.ReviewController$OwnerView")));

    @Test
    @DisplayName("no two request or response records reachable from a controller share a simple name")
    void schemaNamesAreUnique() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<Class<?>> roots = new LinkedHashSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.finapp.app")) {
            for (Method method : Class.forName(candidate.getBeanClassName()).getDeclaredMethods()) {
                roots.addAll(records(method.getGenericReturnType()));
                for (java.lang.reflect.Parameter parameter : method.getParameters()) {
                    if (parameter.isAnnotationPresent(RequestBody.class)) {
                        roots.addAll(records(parameter.getParameterizedType()));
                    }
                }
            }
        }
        assertThat(roots).as("not vacuous").hasSizeGreaterThan(50);
        assertThat(collisions(roots)).isEqualTo(KNOWN);
    }

    @Test
    @DisplayName("the guard bites: two records of one simple name are a collision")
    void thePlantedPairCollides() {
        assertThat(collisions(Set.of(First.Shared.class, Second.Shared.class))).containsKey("Shared");
    }

    static final class First {
        record Shared(String a) {}
    }

    static final class Second {
        record Shared(String b) {}
    }

    /** Simple names claimed by more than one reachable record, with the claimants. */
    static Map<String, Set<String>> collisions(Set<Class<?>> roots) {
        Map<String, Set<String>> byName = new TreeMap<>();
        Set<Class<?>> seen = new LinkedHashSet<>();
        List<Class<?>> pending = new ArrayList<>(roots);
        while (!pending.isEmpty()) {
            Class<?> type = pending.remove(pending.size() - 1);
            if (!seen.add(type)) {
                continue;
            }
            byName.computeIfAbsent(type.getSimpleName(), name -> new TreeSet<>()).add(type.getName());
            for (RecordComponent component : type.getRecordComponents()) {
                pending.addAll(records(component.getGenericType()));
            }
        }
        byName.values().removeIf(claimants -> claimants.size() < 2);
        return byName;
    }

    private static List<Class<?>> records(Type type) {
        List<Class<?>> found = new ArrayList<>();
        if (type instanceof Class<?> klass && klass.isRecord() && klass.getName().startsWith("com.finapp")) {
            found.add(klass);
        } else if (type instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                found.addAll(records(argument));
            }
        }
        return found;
    }
}
