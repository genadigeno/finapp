package com.finapp.app.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.api.ClosedBody;
import com.finapp.sharedkernel.money.ExchangeRate;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A client can never supply a rate (`P9-TSK-008`; INV-FX-02; PHASE_9_PLAN.md section 9): every
 * request body of every {@code fx} and {@code crossborder} controller - and every record nested in
 * one - is a {@link ClosedBody} (an unknown field is refused, never ignored) and has no component
 * typed as a rate or a decimal, or named like a rate or a price. Planted violations prove the rule
 * bites.
 */
@DisplayName("rates are never client-supplied (P9-TSK-008)")
class RatesAreNeverClientSuppliedTest {

    private static final List<String> PACKAGES = List.of("com.finapp.app.fx", "com.finapp.app.crossborder");
    private static final Set<Class<?>> DECIMAL_TYPES =
            Set.of(BigDecimal.class, double.class, Double.class, float.class, Float.class, ExchangeRate.class);

    @Test
    @DisplayName("every fx and crossborder request body is closed and carries no rate")
    void everyRequestBodyIsClosedAndRateFree() throws ClassNotFoundException {
        Set<Class<?>> bodies = requestBodies();
        assertThat(bodies)
                .as("not vacuous: the customer's quote and conversion and the operator's policy are scanned")
                .contains(FxQuoteController.QuoteRequestBody.class, FxQuoteController.ConversionRequestBody.class,
                        FxAdministrationController.PricingPolicyRequest.class);
        List<String> violations = new ArrayList<>();
        bodies.forEach(body -> violations.addAll(violations(body)));
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("the rule bites: a rate field, an open body, a decimal amount and a rate nested in a list"
            + " are each refused")
    void thePlantedViolationsAreRefused() {
        assertThat(violations(PlantedRate.class)).singleElement().asString().contains("rate-named");
        assertThat(violations(PlantedOpen.class)).singleElement().asString().contains("not a @ClosedBody");
        assertThat(violations(PlantedDecimal.class)).singleElement().asString().contains("decimal-typed");
        assertThat(violations(PlantedNested.class)).singleElement().asString().contains("PlantedRate.customerRate");
        assertThat(violations(PlantedPrice.class)).singleElement().asString().contains("rate-named");
    }

    // -----------------------------------------------------------------

    @ClosedBody
    record PlantedRate(String amount, String customerRate) {}

    record PlantedOpen(String amount) {}

    @ClosedBody
    record PlantedDecimal(BigDecimal amount) {}

    @ClosedBody
    record PlantedNested(List<PlantedRate> legs) {}

    @ClosedBody
    record PlantedPrice(String unitPrice) {}

    /** Every {@code @RequestBody} type of the scanned packages' controllers. */
    private static Set<Class<?>> requestBodies() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<Class<?>> bodies = new LinkedHashSet<>();
        for (String scanned : PACKAGES) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(scanned)) {
                for (Method method : Class.forName(candidate.getBeanClassName()).getDeclaredMethods()) {
                    for (java.lang.reflect.Parameter parameter : method.getParameters()) {
                        if (parameter.isAnnotationPresent(RequestBody.class)) {
                            bodies.add(parameter.getType());
                        }
                    }
                }
            }
        }
        return bodies;
    }

    /** The rule's verdicts on one body and every record it nests. */
    static List<String> violations(Class<?> body) {
        List<String> found = new ArrayList<>();
        walk(body, new LinkedHashSet<>(), found);
        return found;
    }

    private static void walk(Class<?> type, Set<Class<?>> seen, List<String> found) {
        if (!seen.add(type)) {
            return;
        }
        if (!type.isRecord()) {
            found.add(type.getSimpleName() + " is not a record, so not a @ClosedBody");
            return;
        }
        if (!type.isAnnotationPresent(ClosedBody.class)) {
            found.add(type.getSimpleName() + " is not a @ClosedBody");
        }
        for (RecordComponent component : type.getRecordComponents()) {
            String where = type.getSimpleName() + "." + component.getName();
            String name = component.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith("rate") || name.contains("price")) {
                found.add(where + " is rate-named");
            }
            if (DECIMAL_TYPES.contains(component.getType())) {
                found.add(where + " is decimal-typed");
            }
            for (Class<?> nested : nestedRecords(component.getGenericType())) {
                walk(nested, seen, found);
            }
        }
    }

    private static List<Class<?>> nestedRecords(Type type) {
        List<Class<?>> nested = new ArrayList<>();
        if (type instanceof Class<?> klass && klass.isRecord()) {
            nested.add(klass);
        } else if (type instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                nested.addAll(nestedRecords(argument));
            }
        }
        return nested;
    }
}
