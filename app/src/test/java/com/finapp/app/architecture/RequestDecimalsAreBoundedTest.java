package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * No decimal text from a request is parsed unbounded (the Phase 9 to 10 transition).
 *
 * <h2>Why</h2>
 *
 * <p>{@code new BigDecimal("1E+500000000")} is twelve characters, and the {@code setScale} every money conversion then
 * performs computes ten to the five-hundred-millionth power - minutes of CPU and hundreds of megabytes, and an
 * {@code OutOfMemoryError} when the overflow's message prints the number. The transition found it at the FX and
 * cross-border quote doors and, by the same pattern, in the payment, withdrawal, transfer, payout, ledger adjustment
 * and rule-set doors. Every such parse now goes through {@code DecimalText}, which judges the text's shape first.
 *
 * <p>Read from {@code app}'s production code with comments and string literals stripped: a {@code new BigDecimal(}
 * appears only in {@code DecimalText} itself and in the named adapters that parse a provider's own answers, already
 * shape-checked (the reference rate source's pattern, the FX adapter's {@code requireDecimal}, the signed instant
 * callback's amount) and the chargeback report's own computed ratio. A planted violation proves the rule bites.
 */
@Tag("architecture")
@DisplayName("no request decimal is parsed unbounded (the Phase 9 to 10 transition)")
class RequestDecimalsAreBoundedTest {

    private static final Pattern UNBOUNDED = Pattern.compile("\\bnew\\s+(?:java\\.math\\.)?BigDecimal\\s*\\(\\s*[^\")0-9]");

    /** Files allowed a dynamic parse: the bounded parser and the provider adapters that shape-check first. */
    private static final Set<String> ALLOWED = Set.of(
            "com/finapp/app/api/DecimalText.java",
            "com/finapp/app/fx/HttpReferenceRateSource.java",
            "com/finapp/app/fx/SimulatedFxProviderAdapter.java",
            "com/finapp/app/payments/InstantCallbackService.java",
            // The chargeback report's ratio: a decimal string the report computed itself, never request text.
            "com/finapp/app/payments/DisputeOperations.java");

    @Test
    @DisplayName("a request's decimal text is parsed only through DecimalText")
    void requestDecimalsGoThroughTheBoundedParser() {
        Path main = repositoryRoot().resolve("app/src/main/java");
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                scanned++;
                String relative = main.relativize(file).toString().replace('\\', '/');
                if (!ALLOWED.contains(relative) && unbounded(read(file))) {
                    violations.add(relative);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(scanned).as("the scan must see app's production code").isGreaterThan(200);
        assertThat(violations)
                .as("parse request decimals with DecimalText.parse - an exponent like 1E+500000000 makes setScale"
                        + " compute an astronomically large power")
                .isEmpty();
    }

    @Test
    @DisplayName("the rule bites: a planted unbounded parse is seen, a literal and prose are not")
    void aPlantedParseIsSeen() {
        assertThat(unbounded("class A { Money m(String raw) { return of(new BigDecimal(raw)); } }")).isTrue();
        assertThat(unbounded("class A { Money m(Body b) { return of(new java.math.BigDecimal(b.amount())); } }"))
                .isTrue();
        assertThat(unbounded("class A { static final BigDecimal ONE = new BigDecimal(\"1.00\"); }")).isFalse();
        assertThat(unbounded("class A { /* new BigDecimal(raw) is refused */ }")).isFalse();
        assertThat(unbounded("class A { String s = \"new BigDecimal(raw)\"; }")).isFalse();
    }

    private static boolean unbounded(String source) {
        Matcher matcher = UNBOUNDED.matcher(stripped(source));
        return matcher.find();
    }

    /** The source with comments and string literals blanked, so prose and literals never match. */
    private static String stripped(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("//[^\\n]*", " ")
                .replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        assertThat(current).as("the repository root must be findable").isNotNull();
        return current;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
