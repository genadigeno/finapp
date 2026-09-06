package com.finapp.app.api;

import com.finapp.sharedkernel.security.Sensitive;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * A {@link Sensitive} value renders as its mask in any JSON this application writes.
 *
 * <h2>Why this is not redundant</h2>
 *
 * <p>Without it, Jackson already fails to reveal the value — it finds no properties on the wrapper
 * and emits {@code {}}. That is safety by <strong>accident</strong>, and this codebase has already
 * been bitten by exactly that shape: {@code ProblemDetail} serialised directly produced
 * {@code "correlationId":{}}, an empty object where a value was expected, with nothing failing.
 *
 * <p>Accidental safety is not default-deny. It holds only while the wrapper happens to have no
 * accessible property, and it ends silently the day somebody adds a getter for convenience, or
 * configures Jackson to use fields, or swaps the serialiser. {@code INV-AUD-02} covers API
 * responses as firmly as logs, so the property has to be stated rather than inherited.
 *
 * <h2>Why in {@code app} and not in the wrapper</h2>
 *
 * <p>{@code Sensitive} lives in {@code sharedkernel}, which is framework-free by construction —
 * annotating it with {@code @JsonValue} would put a serialisation library into the one module whose
 * whole point is having none. Rendering is {@code app}'s responsibility
 * ({@code MODULE_ARCHITECTURE.md} §M10), which is the same reason {@code ProblemDetailBody} lives
 * here rather than beside the contract it renders.
 */
@Configuration
class SensitiveSerialization {

    @Bean
    JacksonModule sensitiveValuesRenderAsMask() {
        SimpleModule module = new SimpleModule("finapp-sensitive");
        // The raw type is deliberate: the serialiser must apply to every Sensitive<?>, and a
        // wildcard-parameterised Class literal does not exist in Java.
        @SuppressWarnings({"rawtypes", "unchecked"})
        ValueSerializer<Sensitive> masking = (ValueSerializer) new MaskingSerializer();
        module.addSerializer(Sensitive.class, masking);

        // The symmetric half, added by `P1-TSK-010`: a request body may now CARRY a secret, and
        // without this Jackson cannot construct the wrapper at all. Registering it here rather
        // than in a second module keeps both directions of one decision in one place.
        @SuppressWarnings({"rawtypes", "unchecked"})
        ValueDeserializer<Sensitive> wrapping = (ValueDeserializer) new WrappingDeserializer();
        module.addDeserializer(Sensitive.class, wrapping);
        return module;
    }

    /**
     * Writes {@link Sensitive#MASK}, never the value.
     *
     * <p>A string rather than {@code null} or an omission: a reader must be able to tell a value
     * that was <em>withheld</em> from one that was absent. The two mean different things when
     * somebody is working out why a request failed.
     */
    private static final class MaskingSerializer extends ValueSerializer<Sensitive<?>> {

        @Override
        public void serialize(Sensitive<?> value, JsonGenerator generator, SerializationContext context) {
            generator.writeString(Sensitive.MASK);
        }
    }

    /**
     * Reads a JSON string into a {@link Sensitive} wrapper.
     *
     * <p>So that a password can arrive in a request body <strong>already wrapped</strong>, rather
     * than existing as a bare {@code String} on a record whose generated {@code toString} prints
     * every component. That is the accident {@code Sensitive} exists for, and the request DTO is
     * where a plaintext password enters the platform - the first place it could be logged.
     *
     * <p><strong>Only {@code Sensitive<String>} is producible</strong>, and that is a limit rather
     * than an oversight: the wrapper's type parameter is erased at run time, so this cannot know
     * what to build for any other. A request body carrying a wrapped non-string secret would need
     * this to become type-aware, and there is no such body.
     */
    private static final class WrappingDeserializer extends ValueDeserializer<Sensitive<String>> {

        @Override
        public Sensitive<String> deserialize(JsonParser parser, DeserializationContext context) {
            // A scalar is coerced rather than type-checked: a caller sending a number where a
            // secret was expected has made a mistake about the shape of the request, not about the
            // secret, and refusing it here would add a response shape to a path where INV-IDN-07
            // wants exactly two. It fails authentication like any other wrong value.
            //
            // Anything with no string form - an object, an array - never reaches this method:
            // Jackson raises MismatchedInputException while resolving the token, and the error
            // contract already renders that as `api.MalformedRequest` (400). Measured, not assumed
            // - the completion gate's first "fix" here was a null-check for that case, which was
            // unreachable code with a comment claiming it handled something it never saw.
            return Sensitive.of(parser.getValueAsString());
        }
    }
}
