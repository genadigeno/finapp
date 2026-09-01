package com.finapp.sharedkernel.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** {@link EntityId}: identity that includes the type, and a type the compiler enforces. */
class EntityIdTest {

    private static final Instant FIXED = Instant.parse("2026-09-01T10:15:30.500Z");

    /**
     * One shared generator, deliberately.
     *
     * <p>A fresh generator per call returns the <em>same</em> value every time here, because
     * the clock is fixed and the seed is fixed — which is the determinism the design is for,
     * and was briefly mistaken for a defect while writing this test. Distinct identifiers come
     * from one generator advancing its counter, not from constructing more of them.
     */
    private static final IdGenerator IDS =
            new IdGenerator(Clock.fixed(FIXED, ZoneOffset.UTC), new Random(1L));

    private static UUID anId() {
        return IDS.next();
    }

    /** Stand-ins for identifiers that will belong to `party` and `accounts` in later phases. */
    static final class ProbeAccountId extends EntityId {
        ProbeAccountId(UUID value) {
            super(value);
        }
    }

    static final class ProbeCustomerId extends EntityId {
        ProbeCustomerId(UUID value) {
            super(value);
        }
    }

    // -----------------------------------------------------------------
    // Identity
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the same value in two different identifier types is two different identifiers")
    void identityIncludesTheConcreteType() {
        UUID value = anId();

        ProbeAccountId account = new ProbeAccountId(value);
        ProbeCustomerId customer = new ProbeCustomerId(value);

        // Without this, a Set<EntityId> or a map keyed by identifier would silently treat an
        // account and a customer as the same entity whenever their values collided.
        assertThat(account).isNotEqualTo(customer);
        assertThat(customer).isNotEqualTo(account);
        assertThat(java.util.Set.of(account, customer)).hasSize(2);
    }

    @Test
    @DisplayName("identifiers of the same type and value are equal, with equal hash codes")
    void valueEqualityWithinAType() {
        UUID value = anId();

        assertThat(new ProbeAccountId(value)).isEqualTo(new ProbeAccountId(value));
        assertThat(new ProbeAccountId(value).hashCode()).isEqualTo(new ProbeAccountId(value).hashCode());
        assertThat(new ProbeAccountId(anId())).isNotEqualTo(new ProbeAccountId(anId()));
    }

    @Test
    @DisplayName("reads back the instant it was minted")
    void exposesItsCreationInstant() {
        assertThat(new ProbeAccountId(anId()).createdAt()).isEqualTo(FIXED);
    }

    @Test
    @DisplayName("names its type when printed, because the type is part of what it is")
    void printsItsType() {
        UUID value = anId();

        assertThat(new ProbeAccountId(value).toString())
                .startsWith("ProbeAccountId(")
                .contains(value.toString());
    }

    @Test
    @DisplayName("refuses a UUID that is not version 7, rather than lying about createdAt")
    void refusesNonTimeOrderedValues() {
        // UUID.randomUUID is v4: its high bits are randomness, so createdAt() would return a
        // fabricated instant derived from noise, and the value would scatter across the index.
        assertThatThrownBy(() -> new ProbeAccountId(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUIDv7");

        assertThatThrownBy(() -> new ProbeAccountId(new UUID(0L, 0L)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ProbeAccountId(null)).isInstanceOf(NullPointerException.class);
    }

    // -----------------------------------------------------------------
    // The acceptance criterion: substitution must not compile
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("type substitution is rejected by the compiler")
    class CompileTimeSafety {

        /**
         * Two identifier types and a method that takes one of them. The last line of each
         * variant below is the whole experiment.
         */
        private static final String PREAMBLE =
                """
                package probe;

                import com.finapp.sharedkernel.id.EntityId;
                import java.util.UUID;

                final class ProbeAccountId extends EntityId {
                    ProbeAccountId(UUID value) { super(value); }
                }

                final class ProbeCustomerId extends EntityId {
                    ProbeCustomerId(UUID value) { super(value); }
                }

                final class Ledger {
                    static void debit(ProbeAccountId account) { }
                """;

        @Test
        @DisplayName("passing a customer identifier where an account identifier is required fails")
        void substitutingOneIdentifierForAnotherDoesNotCompile() {
            List<Diagnostic<? extends JavaFileObject>> errors =
                    compile(PREAMBLE + "    static void call(ProbeCustomerId c) { debit(c); }\n}\n");

            assertThat(errors).as("the compiler must reject the substitution").isNotEmpty();
            assertThat(errors.getFirst().getMessage(Locale.ENGLISH))
                    .as("and reject it as a type error, not for some unrelated reason")
                    .contains("ProbeCustomerId")
                    .contains("ProbeAccountId");
        }

        @Test
        @DisplayName("passing the right identifier compiles, so the check is not always-failing")
        void theCorrectTypeStillCompiles() {
            List<Diagnostic<? extends JavaFileObject>> errors =
                    compile(PREAMBLE + "    static void call(ProbeAccountId a) { debit(a); }\n}\n");

            assertThat(errors).as("the correct call must compile").isEmpty();
        }

        @Test
        @DisplayName("a bare UUID is not accepted either, which is the point of typing at all")
        void rawUuidDoesNotCompile() {
            // The failure mode this whole design exists to prevent is an untyped identifier
            // flowing into a method that meant something else.
            List<Diagnostic<? extends JavaFileObject>> errors =
                    compile(PREAMBLE + "    static void call(UUID raw) { debit(raw); }\n}\n");

            assertThat(errors).isNotEmpty();
        }
    }

    /**
     * Compiles one source unit against this test's own classpath and returns its errors.
     *
     * <p>A compile error cannot be asserted at run time, so the compiler is invoked as the
     * thing under test. This is the only way the acceptance criterion — "a {@code CustomerId}
     * cannot be passed where an {@code AccountId} is required (compile error)" — can be
     * verified on every build rather than demonstrated once by hand.
     */
    private static List<Diagnostic<? extends JavaFileObject>> compile(String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        // Fail loudly rather than skip: a test that quietly does nothing on a JRE would report
        // that the type safety holds without having checked it.
        assertThat(compiler)
                .as("a JDK compiler must be available; the build runs on a JDK toolchain")
                .isNotNull();

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            Path output = Files.createTempDirectory("finapp-idtype-probe");
            output.toFile().deleteOnExit();

            List<String> options =
                    List.of(
                            "-classpath", System.getProperty("java.class.path"),
                            "-d", output.toString());

            compiler.getTask(null, files, diagnostics, options, null, List.of(new InMemorySource(source)))
                    .call();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not run the compiler probe", e);
        }

        return diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .toList();
    }

    /** Source held in memory; nothing about this probe belongs on disk. */
    private static final class InMemorySource extends SimpleJavaFileObject {

        private final String code;

        private InMemorySource(String code) {
            super(URI.create("string:///probe/Ledger.java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
