package com.finapp.app.api;

import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.module.SimpleModule;

/**
 * Refuses an unknown field on a {@link ClosedBody} type (`P9-TSK-008`). The platform's mapper skips
 * unknown properties; Jackson consults its problem handlers BEFORE that skip, so this handler throws
 * for a closed type and declines for every other - no existing door changes behaviour.
 * {@code ApiErrorHandler} answers the failure {@code 422 api.ValidationFailed}.
 */
@Configuration
class ClosedBodies {

    @Bean
    JacksonModule closedBodiesRefuseUnknownFields() {
        return new SimpleModule("finapp-closed-bodies") {
            @java.io.Serial private static final long serialVersionUID = 1L;

            @Override
            public void setupModule(SetupContext context) {
                super.setupModule(context);
                context.addHandler(new RefuseUnknownOnClosedBodies());
            }
        };
    }

    /** Throws for a closed body's unknown property; declines (returns false) for every other type. */
    static final class RefuseUnknownOnClosedBodies extends DeserializationProblemHandler {

        @Override
        public boolean handleUnknownProperty(
                DeserializationContext context,
                JsonParser parser,
                ValueDeserializer<?> deserializer,
                Object beanOrClass,
                String propertyName) {
            Class<?> type = beanOrClass instanceof Class<?> klass ? klass : beanOrClass.getClass();
            if (type.isAnnotationPresent(ClosedBody.class)) {
                Collection<Object> known = deserializer == null ? List.of() : deserializer.getKnownPropertyNames();
                throw UnrecognizedPropertyException.from(parser, beanOrClass, propertyName, known);
            }
            return false;
        }
    }
}
