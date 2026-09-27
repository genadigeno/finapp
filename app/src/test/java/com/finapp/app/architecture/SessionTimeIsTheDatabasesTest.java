package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.SessionStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * No session decision is handed an instance's clock (`X-TSK-007`, ADR-0014).
 *
 * <h2>Why a build rule, and not only the skew tests</h2>
 *
 * <p>{@code SessionClockSkewDatabaseTest} and {@code SessionClockSkewEndpointDatabaseTest} prove
 * the current code judges and stamps on the database's clock. They cannot stop the next change
 * from adding an {@code Instant at} back to {@code findLive} "for testability" - the parameter the
 * store used to have, and the whole defect: every caller filled it from its own clock. {@code V004}
 * removed {@code IdempotencyRecord.isStaleAt} for the same reason. A predicate on a caller's clock
 * invites the assumption straight back, so the shape itself is refused.
 *
 * <h2>Two rules</h2>
 *
 * <ul>
 *   <li><strong>The port.</strong> Every method is classified. Those that decide or stamp time -
 *       insert, the lookups, the count, the touch - take no temporal parameter at all. The
 *       revocations take exactly one {@code Instant}, which is business time, recorded as
 *       {@code revoked_at} beside the audit record carrying the same reading. A new method fails
 *       until somebody classifies it, which is the point: the question has to be asked.
 *   <li><strong>The statements.</strong> In {@code JdbcSessionStore}'s SQL, no bound is compared
 *       with a bind parameter, and the one predicate every liveness statement uses reads the
 *       database's {@code now()}. Read from the string literals only - the {@code
 *       OwnershipIsScopedTest} lesson, where a {@code contains} over source text matched prose.
 * </ul>
 */
@Tag("architecture")
@DisplayName("session time is the database's (X-TSK-007)")
class SessionTimeIsTheDatabasesTest {

    /** Decide or stamp a session's time, so no caller's clock may reach them. */
    private static final Set<String> DATABASE_TIMED =
            Set.of("insert", "findLive", "findLiveFor", "countLive", "touch");

    /** Take one business-time instant, recorded as {@code revoked_at} and compared with nothing. */
    private static final Set<String> BUSINESS_TIMED =
            Set.of("revoke", "revokeOwned", "revokeAllFor", "revokeAllForExcept");

    private static final List<Class<?>> TEMPORAL =
            List.of(
                    Instant.class,
                    Clock.class,
                    InstantSource.class,
                    OffsetDateTime.class,
                    ZonedDateTime.class,
                    LocalDateTime.class,
                    java.util.Date.class,
                    java.sql.Timestamp.class);

    private static final String STORE = "com.finapp.identity.JdbcSessionStore";

    /** Liveness judged per statement, the methods that must use the one shared predicate. */
    private static final Set<String> JUDGES_LIVENESS =
            Set.of("findLive", "findLiveFor", "countLive", "touch", "revoke");

    @Test
    @DisplayName("every SessionStore method is classified, and only revocations take an instant")
    void thePortHandsNoDecisionACallersClock() {
        Map<String, List<Method>> methods =
                Arrays.stream(SessionStore.class.getDeclaredMethods())
                        .filter(method -> !method.isSynthetic())
                        .collect(Collectors.groupingBy(Method::getName));

        Set<String> classified = new TreeSet<>(DATABASE_TIMED);
        classified.addAll(BUSINESS_TIMED);
        assertThat(new TreeSet<>(methods.keySet()))
                .as("a SessionStore method nobody has classified: decide whether it decides time"
                        + " (then it takes no instant) or records business time, and say so here")
                .isEqualTo(classified);

        for (String name : DATABASE_TIMED) {
            for (Method method : methods.get(name)) {
                assertThat(temporalParametersOf(method))
                        .as(name + " decides or stamps a session's time, so the database's clock"
                                + " does it - a temporal parameter would be a caller's clock")
                        .isEmpty();
            }
        }
        for (String name : BUSINESS_TIMED) {
            for (Method method : methods.get(name)) {
                assertThat(temporalParametersOf(method))
                        .as(name + " records revoked_at, business time, and nothing else")
                        .containsExactly(Instant.class);
            }
        }
    }

    @Test
    @DisplayName("no bound in JdbcSessionStore's SQL is compared with a bind parameter")
    void noBoundIsComparedWithACallersInstant() {
        String literals = literalsIn(readSourceOf(STORE));
        assertThat(literals)
                .as("the rule read the real statements, not an empty file")
                .contains("identity.session")
                .contains("idle_expires_at");

        Pattern boundAgainstParameter =
                Pattern.compile(
                        "(idle_expires_at|absolute_expires_at|live_from)\\s*(<=|>=|<|>|=)\\s*\\?"
                                + "|\\?\\s*(<=|>=|<|>|=)\\s*(idle_expires_at|absolute_expires_at|live_from)");
        assertThat(boundAgainstParameter.matcher(literals).find())
                .as("a session bound compared with a bind parameter is a caller's clock deciding"
                        + " liveness - the defect X-TSK-007 removed")
                .isFalse();

        Pattern stampFromParameter =
                Pattern.compile("SET\\s+(idle_expires_at|absolute_expires_at)\\s*=\\s*(LEAST\\()?\\s*\\?");
        assertThat(stampFromParameter.matcher(literals).find())
                .as("a bound stamped from a bind parameter is a caller's clock deciding how long a"
                        + " session lives - the touch did this until X-TSK-007")
                .isFalse();
    }

    @Test
    @DisplayName("every liveness statement uses the one predicate, and it reads now()")
    void livenessIsOnePredicateOnTheDatabasesClock() {
        String source = readSourceOf(STORE);
        String live = constantOf(source, "LIVE");
        assertThat(live)
                .as("both bounds judged against the database's now()")
                .contains("idle_expires_at > now()")
                .contains("absolute_expires_at > now()")
                .contains("status = 'ACTIVE'");

        for (String method : JUDGES_LIVENESS) {
            assertThat(namesIdentifier(methodSource(source, method), "LIVE"))
                    .as(method + " must judge liveness with the shared predicate, so the lookup,"
                            + " the listing, the gauge, the touch and rotation cannot disagree")
                    .isTrue();
        }
    }

    // -----------------------------------------------------------------

    private static List<Class<?>> temporalParametersOf(Method method) {
        return Arrays.stream(method.getParameterTypes())
                .filter(type -> TEMPORAL.stream().anyMatch(temporal -> temporal.isAssignableFrom(type)))
                .collect(Collectors.toList());
    }

    /** The value of one {@code static final String} constant made of literals. */
    private static String constantOf(String source, String name) {
        Matcher declared =
                Pattern.compile(
                                "static final String\\s+" + Pattern.quote(name) + "\\s*=\\s*"
                                        + "((?:\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"\\s*\\+?\\s*)+);")
                        .matcher(source);
        assertThat(declared.find()).as("the constant " + name + " is declared").isTrue();
        return literalsIn(declared.group(1));
    }

    private static String literalsIn(String source) {
        StringBuilder literals = new StringBuilder();
        Matcher quoted = Pattern.compile("\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"").matcher(source);
        while (quoted.find()) {
            literals.append(quoted.group());
        }
        return literals.toString();
    }

    /** Word-boundary match, so {@code LIVE} is not found inside another identifier. */
    private static boolean namesIdentifier(String body, String name) {
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(body).find();
    }

    /** The body of the method DECLARED with this name - never a call site of it. */
    private static String methodSource(String source, String method) {
        int from = 0;
        int signature = -1;
        while (signature < 0) {
            int hit = source.indexOf(method + "(", from);
            if (hit < 0) {
                throw new IllegalStateException("No declaration of " + method);
            }
            int lineStart = source.lastIndexOf(10, hit) + 1;
            String line = source.substring(lineStart, hit).trim();
            if (line.startsWith("public ") || line.startsWith("private ")) {
                signature = hit;
            }
            from = hit + 1;
        }
        int open = source.indexOf('{', signature);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char character = source.charAt(i);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        throw new IllegalStateException("Unbalanced braces reading " + method);
    }

    private static String readSourceOf(String type) {
        String module = type.substring("com.finapp.".length());
        module = module.substring(0, module.indexOf('.'));
        Path path =
                repositoryRoot()
                        .resolve(module)
                        .resolve("src/main/java")
                        .resolve(type.replace('.', '/') + ".java");
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the source of " + type, e);
        }
    }

    /** The {@code DomainGlossaryTest} idiom - the working directory differs between IDE and Gradle. */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("No settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
}
