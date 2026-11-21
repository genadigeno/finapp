package com.finapp.app.api;

import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * What the published contract says about each operation and field beyond springdoc's own reading (`P17-TSK-127`), every
 * word of it derived from a production annotation - never authored here, in the spirit of {@link OpenApiDocument}.
 *
 * <ul>
 *   <li>{@code x-permission}: the permission {@link RequiresPermission} names on the handler or its controller, as the
 *       session interceptor resolves it - the method's first, then the class's.
 *   <li>{@code x-assurance}: the session assurance {@link RequiresAssurance} demands ({@code MULTI_FACTOR}), resolved the
 *       same way; absent where a password session suffices.
 *   <li>{@code x-recorded-read}: {@code true} where {@link RecordedRead} marks the handler - the read is recorded in its own
 *       transaction before anything is served.
 *   <li>{@code x-exact-number}: on a request field bound by {@link ExactNumbers} - {@code decimal} or {@code minor-units};
 *       {@link OpenApiDocument} then publishes the field as a number <em>or</em> a patterned string.
 * </ul>
 *
 * <p>Test scope because springdoc is (ADR-0015): the running application ships no documentation library. Imported by
 * {@code OpenApiContractTest} alone, the one test that writes the contract.
 */
@TestConfiguration(proxyBeanMethods = false)
class ContractExtensions {

    static final String PERMISSION = "x-permission";
    static final String ASSURANCE = "x-assurance";
    static final String RECORDED_READ = "x-recorded-read";
    static final String EXACT_NUMBER = "x-exact-number";

    @Bean
    OperationCustomizer securityRequirements() {
        return (operation, handler) -> {
            RequiresPermission permission = declared(handler, RequiresPermission.class);
            if (permission != null) {
                operation.addExtension(PERMISSION, permission.value().name());
            }
            RequiresAssurance assurance = declared(handler, RequiresAssurance.class);
            if (assurance != null) {
                operation.addExtension(ASSURANCE, assurance.value().name());
            }
            if (handler.getMethodAnnotation(RecordedRead.class) != null) {
                operation.addExtension(RECORDED_READ, true);
            }
            return operation;
        };
    }

    @Bean
    OpenApiCustomizer exactNumbers(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping) {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            for (HandlerMethod handler : mapping.getHandlerMethods().values()) {
                for (MethodParameter parameter : handler.getMethodParameters()) {
                    Class<?> body = parameter.getParameterType();
                    if (!parameter.hasParameterAnnotation(RequestBody.class) || !body.isRecord()) {
                        continue;
                    }
                    Schema<?> schema = openApi.getComponents().getSchemas().get(body.getSimpleName());
                    if (schema == null || schema.getProperties() == null) {
                        continue;
                    }
                    for (RecordComponent component : body.getRecordComponents()) {
                        String exact = exactNumber(body, component);
                        Schema<?> property = exact == null ? null : schema.getProperties().get(component.getName());
                        if (property != null) {
                            property.addExtension(EXACT_NUMBER, exact);
                        }
                    }
                }
            }
        };
    }

    /** The method's annotation, else its controller's - the interceptor's own resolution order. */
    private static <A extends java.lang.annotation.Annotation> A declared(HandlerMethod handler, Class<A> type) {
        A onMethod = handler.getMethodAnnotation(type);
        return onMethod != null ? onMethod : handler.getBeanType().getAnnotation(type);
    }

    /** {@code decimal} or {@code minor-units} where the component is bound by {@link ExactNumbers}, else null. */
    private static String exactNumber(Class<?> record, RecordComponent component) {
        try {
            // The component's annotation lands on its field (JsonDeserialize names no RECORD_COMPONENT target).
            Field field = record.getDeclaredField(component.getName());
            JsonDeserialize binding = field.getAnnotation(JsonDeserialize.class);
            if (binding == null) {
                return null;
            }
            return Map.<Class<?>, String>of(ExactNumbers.Decimal.class, "decimal", ExactNumbers.MinorUnits.class,
                    "minor-units").get(binding.using());
        } catch (NoSuchFieldException impossible) {
            throw new IllegalStateException("a record component without its field: " + component, impossible);
        }
    }
}
