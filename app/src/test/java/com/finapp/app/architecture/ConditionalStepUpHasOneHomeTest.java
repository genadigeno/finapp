package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.ConditionalStepUp;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityId;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.Session;
import com.finapp.identity.SessionId;
import com.finapp.identity.SessionStatus;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.testing.RepositoryPaths;
import com.finapp.sharedkernel.security.Sensitive;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.objenesis.SpringObjenesis;

/**
 * The conditional step-up has one body and every caller answers its truth table (`P15-TSK-039`).
 *
 * <p>The callers are found in the bytecode, never listed: every production class that calls
 * {@link ConditionalStepUp#require}. Each is driven through its own private {@code requireConditionalAssurance}, so what
 * is proven is the caller's behaviour, the one each surface's step-up test pins on PostgreSQL (those, each a database
 * case, are owed to the exit pass).
 */
@Tag("architecture")
@DisplayName("the conditional step-up has one body, and every caller answers its truth table (P15-TSK-039)")
class ConditionalStepUpHasOneHomeTest {

    /** Sixteen after the payout destination's two acts became unconditional; a broken import cannot pass as none. */
    private static final int FEWEST_CALLERS = 16;

    /** The shape of a copy: the census's own expression. */
    private static final Pattern A_COPY = Pattern.compile("hasFactor\\s*&&\\s*!\\s*\\S*assurance\\(\\)");

    private static final List<Class<?>> CALLERS = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.finapp.app")
            .stream()
            .filter(javaClass -> javaClass.getMethodCallsFromSelf().stream().anyMatch(ConditionalStepUpHasOneHomeTest::callsTheRule))
            .map(JavaClass::reflect)
            .toList();

    private static boolean callsTheRule(JavaMethodCall call) {
        return call.getTargetOwner().isEquivalentTo(ConditionalStepUp.class) && call.getName().equals("require");
    }

    @Test
    @DisplayName("no production source restates the rule: only the component, and CredentialChange which audits and returns")
    void noCopyIsLeft() throws IOException {
        TreeSet<String> copies = new TreeSet<>();
        Path root = RepositoryPaths.locate("settings.gradle.kts").getParent();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> root.relativize(file).toString().replace('\\', '/').matches("[^/]+/src/main/java/.*"))
                    .filter(ConditionalStepUpHasOneHomeTest::restatesTheRule)
                    .map(file -> file.getFileName().toString())
                    .forEach(copies::add);
        }

        assertThat(copies).containsExactly("ConditionalStepUp.java", "CredentialChange.java");
    }

    @Test
    @DisplayName("every caller, found in the bytecode, is refused and admitted exactly as the rule says")
    void everyCallerAnswersTheTruthTable() throws Exception {
        assertThat(CALLERS).as("%s", CALLERS).hasSizeGreaterThanOrEqualTo(FEWEST_CALLERS);
        for (Class<?> caller : CALLERS) {
            assertThatRefused(caller, true, AssuranceLevel.PASSWORD);
            admitted(caller, true, AssuranceLevel.MULTI_FACTOR);
            admitted(caller, true, AssuranceLevel.STRONG);
            admitted(caller, false, AssuranceLevel.PASSWORD);
        }
    }

    private static void assertThatRefused(Class<?> caller, boolean enrolled, AssuranceLevel level) throws Exception {
        assertThatThrownBy(() -> call(caller, enrolled, level)).as(caller.getSimpleName())
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED);
                    assertThat(refused.getMessage()).contains("requires a MULTI_FACTOR session");
                });
    }

    private static void admitted(Class<?> caller, boolean enrolled, AssuranceLevel level) {
        assertThatCode(() -> call(caller, enrolled, level)).as(caller.getSimpleName() + " " + enrolled + " " + level)
                .doesNotThrowAnyException();
    }

    /** The caller's own method on an instance holding nothing but the store the rule reads. */
    private static void call(Class<?> caller, boolean enrolled, AssuranceLevel level) throws Throwable {
        Method check = Stream.of(caller.getDeclaredMethods())
                .filter(method -> method.getName().equals("requireConditionalAssurance"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(caller + " calls the rule outside requireConditionalAssurance"));
        check.setAccessible(true);
        Object instance = new SpringObjenesis().newInstance(caller);
        for (Field field : caller.getDeclaredFields()) {
            if (MfaEnrolmentStore.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                field.set(instance, store(enrolled));
            }
        }
        Object[] arguments = check.getParameterCount() == 3
                ? new Object[] {connection(), session(level), "act"}
                : new Object[] {connection(), session(level)};
        try {
            check.invoke(instance, arguments);
        } catch (InvocationTargetException thrown) {
            throw thrown.getCause();
        }
    }

    private static boolean restatesTheRule(Path file) {
        try {
            return A_COPY.matcher(Files.readString(file, StandardCharsets.UTF_8)).find();
        } catch (IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> null);
    }

    private static Object store(boolean enrolled) {
        return Proxy.newProxyInstance(MfaEnrolmentStore.class.getClassLoader(), new Class<?>[] {MfaEnrolmentStore.class},
                (proxy, method, args) -> enrolled ? Optional.of("an active factor") : Optional.empty());
    }

    private static Session session(AssuranceLevel level) {
        Instant at = Instant.parse("2026-11-09T10:00:00Z");
        return Session.rehydrate(SessionId.of(UUID.fromString("00000000-0000-7000-8000-000000000002")),
                IdentityId.of(UUID.fromString("00000000-0000-7000-8000-000000000001")), Sensitive.of("hash"), level,
                SessionStatus.ACTIVE, at, at.plusSeconds(3600), at.plusSeconds(86400), null, null);
    }
}
