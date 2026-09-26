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
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `INV-RAIL-01`'s static half (`P7-TSK-001`, ADR-0059 §1): <strong>no core code branches on a
 * rail's name</strong>. A rail's name is written in exactly one production place — the adapter
 * that declares it — and referenced from configuration; everywhere else the rail is data, read
 * off the attempt row or a declaration, and the decision is the declared capability's.
 *
 * <h2>What the rule scans, and what it cannot see</h2>
 *
 * <p>Two token families over every module's {@code src/main/java}, comment-stripped (a comment
 * naming a rail is prose — the `P1-TSK-021` lesson that a rule matching prose reports a control
 * it does not have):
 *
 * <ul>
 *   <li><strong>a whole string literal equal to a declared rail's name</strong> — {@code "card"}
 *       — outside the declaring adapter. This is what a planted {@code RailId.of("card")}, a
 *       {@code switch} case or an {@code equals("card")} must write;
 *   <li><strong>a reference to the declaration constant</strong> — {@code
 *       SimulatedCardPspAdapter.RAIL} — outside the composition root, which is the
 *       "configuration" the rule's own statement permits: binding the declaration to its
 *       operations and handing the directory out IS configuration's job.
 * </ul>
 *
 * <p>Stated limit: a branch built from a rail name the scanner never sees — read from
 * configuration at runtime, or assembled from fragments — is invisible to it. The design gives
 * such a branch nothing useful to compare against: the stored {@code rail} column is frozen,
 * and every capability lives on the declaration the directory serves.
 */
@DisplayName("rail vocabulary is confined (P7-TSK-001, INV-RAIL-01)")
class RailVocabularyIsConfinedTest {

    /** Every declared rail's name, pinned; the declaring file is checked to really declare it. */
    private static final Map<String, String> DECLARED_RAILS =
            Map.of("card", "SimulatedCardPspAdapter.java");

    private static final String CONSTANT_REFERENCE = "SimulatedCardPspAdapter.RAIL";

    /** The composition root may bind the declaration; nothing else may name it. */
    private static final Set<String> CONFIGURATION_FILES = Set.of("PaymentBeans.java");

    @Test
    @DisplayName("no rail-name literal exists outside its declaring adapter")
    void railNameLiteralsAreConfined() {
        List<String> outside = new ArrayList<>();
        List<String> declaring = new ArrayList<>();
        for (Path source : mainSources()) {
            String fileName = source.getFileName().toString();
            for (String literal : stringLiteralsOf(read(source)).split("\n")) {
                String declaredIn = DECLARED_RAILS.get(literal);
                if (declaredIn == null) {
                    continue;
                }
                if (declaredIn.equals(fileName)) {
                    declaring.add(literal + " in " + fileName);
                } else {
                    outside.add("\"" + literal + "\" in " + source);
                }
            }
        }
        assertThat(outside)
                .as("a rail-name literal outside its declaring adapter is a branch on a rail's"
                        + " name waiting to compile (INV-RAIL-01)")
                .isEmpty();
        assertThat(declaring)
                .as("the guard is not vacuous: each declared rail's name is found where it is"
                        + " declared")
                .hasSize(DECLARED_RAILS.size());
    }

    @Test
    @DisplayName("the declaration constant is referenced only by the adapter and configuration")
    void theDeclarationConstantIsConfined() {
        List<String> outside = new ArrayList<>();
        boolean configured = false;
        for (Path source : mainSources()) {
            String fileName = source.getFileName().toString();
            if (!codeOf(read(source)).contains(CONSTANT_REFERENCE)) {
                continue;
            }
            if (CONFIGURATION_FILES.contains(fileName)) {
                configured = true;
            } else if (!DECLARED_RAILS.containsValue(fileName)) {
                outside.add(source.toString());
            }
        }
        assertThat(outside)
                .as("%s referenced outside the adapter and the composition root is core code"
                        + " holding a rail by name (INV-RAIL-01)", CONSTANT_REFERENCE)
                .isEmpty();
        assertThat(configured)
                .as("the permit is not stale: the composition root really binds the"
                        + " declaration (the P1-TSK-015 rule - an exemption naming nothing"
                        + " silently stops applying)")
                .isTrue();
    }

    @Test
    @DisplayName("the scanners reject their violations and ignore prose")
    void scannersRejectTheirViolations() {
        // A planted branch, in each family the rule claims to see.
        assertThat(stringLiteralsOf("if (rail.value().equals(\"card\")) { pay(); }").split("\n"))
                .contains("card");
        assertThat(codeOf("Object rail = SimulatedCardPspAdapter.RAIL;"))
                .contains(CONSTANT_REFERENCE);
        // Prose is not a control: comments are stripped from both scanners, literals from the
        // code scanner, and only a WHOLE literal matches a name.
        assertThat(stringLiteralsOf("// the \"card\" rail\n/* card */ String s = \"cardigan\";"))
                .doesNotContain("\ncard\n")
                .contains("cardigan");
        assertThat(codeOf("// SimulatedCardPspAdapter.RAIL\n"
                        + "/** SimulatedCardPspAdapter.RAIL */\n"
                        + "String s = \"SimulatedCardPspAdapter.RAIL\";"))
                .doesNotContain(CONSTANT_REFERENCE);
        assertThat(stringLiteralsOf("String s = \"a card in hand\";").split("\n"))
                .doesNotContain("card");
    }

    // -----------------------------------------------------------------

    private static List<Path> mainSources() {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repositoryRoot())) {
            for (Path module : modules.toList()) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(main)) {
                    files.filter(file -> file.toString().endsWith(".java"))
                            .forEach(sources::add);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(sources).as("the scan must see the codebase").hasSizeGreaterThan(100);
        return sources;
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
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** String literals, one per line, comments stripped — the `OwnershipIsScopedTest` scanner. */
    private static String stringLiteralsOf(String source) {
        return scan(source, true);
    }

    /** The code with comments AND string/char literals stripped — what references live in. */
    private static String codeOf(String source) {
        return scan(source, false);
    }

    private static String scan(String source, boolean keepLiterals) {
        StringBuilder kept = new StringBuilder();
        int i = 0;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                int end = source.indexOf('\n', i);
                i = end < 0 ? length : end;
            } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? length : end;
                if (keepLiterals) {
                    kept.append(source, i + 3, end).append('\n');
                }
                i = Math.min(length, end + 3);
            } else if (c == '"') {
                int j = i + 1;
                while (j < length && source.charAt(j) != '"') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                if (keepLiterals) {
                    kept.append(source, i + 1, Math.min(j, length)).append('\n');
                }
                i = j + 1;
            } else if (c == '\'') {
                int j = i + 1;
                while (j < length && source.charAt(j) != '\'') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                i = j + 1;
            } else {
                if (!keepLiterals) {
                    kept.append(c);
                }
                i++;
            }
        }
        return kept.toString();
    }
}
