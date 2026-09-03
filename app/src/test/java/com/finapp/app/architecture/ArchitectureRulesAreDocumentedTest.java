package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Holds {@code docs/architecture/MODULE_ARCHITECTURE.md} §6 and the enforced rule set to each
 * other (P0-DOC-002).
 *
 * <p><strong>Why this is a test and not a review item.</strong> The acceptance criterion for
 * `P0-DOC-002` is that the document "matches the enforced ArchUnit rules exactly". That is an
 * equivalence between prose and code — the single most perishable kind of claim in a
 * repository, because nothing about editing code forces anyone to open a document. The
 * evidence is not theoretical: `P0-TSK-007` found §2 and §8 asserting that rules were "not yet
 * in place" when they were, and the `P0-DOC-002` audit found six further drifts, including a
 * rule that was enforced on every build but listed nowhere, and two sections still describing
 * {@code INV-MON-01} as unenforced two tasks after it was enforced.
 *
 * <p>Fixing prose and declaring it accurate would restore the same condition that produced
 * those six. So the equivalence is asserted instead, in both directions:
 *
 * <ul>
 *   <li>a rule added to the code but not documented fails the build;
 *   <li>a rule named in the document but deleted from the code fails the build;
 *   <li>a claim marked {@code *(ArchUnit)*} that names no rule at all fails the build, because
 *       an unattributed claim of mechanical enforcement is exactly what cannot be checked.
 * </ul>
 *
 * <p><strong>The convention this depends on.</strong> A documented rule is written
 * {@code *(ArchUnit: `ruleName`)*}, listing one or more rule identifiers in backticks.
 * Everything inside such a marker must be a rule identifier; supporting prose belongs in the
 * sentence, not the marker.
 *
 * <p>Rule suites are discovered rather than listed: any test class annotated
 * {@link AnalyzeClasses} is a suite, wherever it sits, so neither adding a third one nor moving
 * one to another package silently escapes the check.
 */
@Tag("architecture")
class ArchitectureRulesAreDocumentedTest {

    private static final String DOCUMENT = "docs/architecture/MODULE_ARCHITECTURE.md";

    /** An {@code *(ArchUnit ...)*} marker. Spans lines, so the pattern is DOTALL. */
    private static final Pattern ARCHUNIT_MARKER =
            Pattern.compile("\\*\\(([^)]*ArchUnit[^)]*)\\)\\*", Pattern.DOTALL);

    private static final Pattern BACKTICKED = Pattern.compile("`([A-Za-z][A-Za-z0-9_]*)`");

    @Test
    @DisplayName("every enforced rule is documented, and every documented rule is enforced")
    void documentAndRulesAgreeExactly() {
        Set<String> documented = documentedRuleNames();
        Set<String> enforced = enforcedRuleNames();

        // Stated as one equality rather than two containment checks so a failure shows both
        // directions at once: what the document promises and what the build actually does.
        assertThat(documented)
                .as(
                        "%s §6 must name exactly the rules that run on every build. "
                                + "Undocumented rules are unattributable enforcement; documented "
                                + "rules that no longer exist are a promise the build stopped keeping.",
                        DOCUMENT)
                .isEqualTo(enforced);
    }

    @Test
    @DisplayName("no claim of mechanical enforcement is left unattributed")
    void everyArchUnitClaimNamesItsRule() {
        String text = readDocument();
        Matcher marker = ARCHUNIT_MARKER.matcher(text);

        int markers = 0;
        while (marker.find()) {
            markers++;
            assertThat(namesIn(marker.group(1)))
                    .as("the marker *(%s)* claims ArchUnit enforcement but names no rule", marker.group(1))
                    .isNotEmpty();
        }

        // A document that stopped using the convention would otherwise pass both tests by
        // having nothing to check — the vacuity failure mode the rule suites guard against too.
        assertThat(markers).as("%s must still use the *(ArchUnit: `rule`)* convention", DOCUMENT).isPositive();
    }

    // -----------------------------------------------------------------

    private static Set<String> documentedRuleNames() {
        Set<String> names = new TreeSet<>();
        Matcher marker = ARCHUNIT_MARKER.matcher(readDocument());
        while (marker.find()) {
            names.addAll(namesIn(marker.group(1)));
        }
        return names;
    }

    private static Set<String> namesIn(String markerBody) {
        Set<String> names = new TreeSet<>();
        Matcher backticked = BACKTICKED.matcher(markerBody);
        while (backticked.find()) {
            names.add(backticked.group(1));
        }
        return names;
    }

    /**
     * Every {@code @ArchTest} rule across every suite in this package.
     *
     * <p>Both a static {@link ArchRule} field and a static method taking the imported classes
     * are rules ArchUnit runs, and the coverage guards are the second kind. Counting only
     * fields would leave the guards undocumented and undocumentable.
     */
    private static Set<String> enforcedRuleNames() {
        Set<String> names = new TreeSet<>();
        for (Class<?> suite : ruleSuites()) {
            for (Field field : suite.getDeclaredFields()) {
                if (field.isAnnotationPresent(ArchTest.class)
                        && ArchRule.class.isAssignableFrom(field.getType())) {
                    names.add(field.getName());
                }
            }
            for (Method method : suite.getDeclaredMethods()) {
                if (method.isAnnotationPresent(ArchTest.class)) {
                    names.add(method.getName());
                }
            }
        }
        assertThat(names).as("no ArchUnit rules were discovered at all").isNotEmpty();
        return names;
    }

    /**
     * Every test class annotated {@link AnalyzeClasses}, found by walking the whole test-classes
     * tree rather than one directory.
     *
     * <p>The first version listed one directory. That was demonstrated insufficient during this
     * task's own review: a suite placed in a subpackage ran its rules on every build and escaped
     * this check entirely, so its rules were enforced but undocumentable. Scanning everything
     * means placement cannot exempt a suite.
     *
     * <p>Classes are loaded without initialisation - only annotations and member names are read,
     * and running a stranger's static initialiser to decide whether it is a rule suite would be
     * a side effect this check has no business causing.
     */
    private static List<Class<?>> ruleSuites() {
        Path root = testClassesDirectory();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(Path::toString)
                    .filter(name -> name.endsWith(".class") && !name.contains("$"))
                    .map(ArchitectureRulesAreDocumentedTest::toClassName)
                    .map(ArchitectureRulesAreDocumentedTest::load)
                    .filter(type -> type.isAnnotationPresent(AnalyzeClasses.class))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + root, e);
        }
    }

    private static String toClassName(String relativePath) {
        return relativePath
                .substring(0, relativePath.length() - ".class".length())
                .replace(java.io.File.separatorChar, '.')
                .replace('/', '.');
    }

    private static Class<?> load(String qualified) {
        try {
            // initialize = false: reading annotations and member names must not run static code.
            return Class.forName(
                    qualified, false, ArchitectureRulesAreDocumentedTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Could not load " + qualified, e);
        }
    }

    private static Path testClassesDirectory() {
        try {
            return Path.of(
                    ArchitectureRulesAreDocumentedTest.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Could not locate the test classes directory", e);
        }
    }

    /**
     * Finds the document by walking up from the working directory.
     *
     * <p>Failing loudly matters here: a test that cannot find the document and quietly passes
     * would report that prose and code agree without having compared them.
     */
    private static String readDocument() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(DOCUMENT);
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException("Could not read " + candidate, e);
                }
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "Could not find " + DOCUMENT + " above " + Path.of("").toAbsolutePath());
    }
}
