package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.OwnerKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A counterparty clearing is named by its declarations, never hand-named (`P9-TSK-010`, ADR-0078
 * section 6; {@code INV-RAIL-04}, {@code INV-SET-05}): every counterparty-owned purpose - derived
 * from {@link AccountPurpose}, so a new one is covered the day it is admitted - is named in
 * production code only by its declaration ({@code AccountPurpose} itself), the counterparties'
 * own declarations and the settlement composition that reads them. A posting or a source that
 * named {@code FX_PROVIDER_CLEARING} itself would stop following the counterparty whose position
 * it is - the {@code CLEARING_POSITIONS} lesson of {@code RailVocabularyIsConfinedTest}, at the
 * counterparty boundary. Read from the code with comments stripped and from the string literals
 * (the {@code FxBooksHaveOnePosterTest} scanner); planted violations prove it bites.
 */
@DisplayName("a counterparty clearing is named only by its declarations (P9-TSK-010, INV-RAIL-04)")
class CounterpartyClearingIsNamedByDeclarationsTest {

    /** The declaration - its own members name themselves. */
    private static final String DECLARING_FILE = "AccountPurpose.java";

    /**
     * The counterparties' declarations and the composition reading them, each with its role.
     * {@code FxProviderDeclaration.java} since `P9-TSK-011`; the settlement composition reads the
     * purpose off the declaration and names none, so it holds no permit - a permit naming nothing
     * would silently stop applying (the `P1-TSK-015` rule).
     */
    static final Map<String, String> PERMITTED =
            Map.of(
                    "FxProviderDeclaration.java",
                    "DECLARATION: an FX provider's clearingPurpose() (P9-TSK-011) - the settlement"
                            + " composition and the counterparty chart read it, never name it");

    /** Every counterparty-owned purpose, derived. */
    static final List<String> COUNTERPARTY_PURPOSES =
            Arrays.stream(AccountPurpose.values())
                    .filter(purpose -> purpose.ownerKind() == OwnerKind.COUNTERPARTY)
                    .map(AccountPurpose::name)
                    .toList();

    private static final Pattern TOKEN =
            Pattern.compile("\\b(" + String.join("|", COUNTERPARTY_PURPOSES) + ")\\b");

    @Test
    @DisplayName("every counterparty-owned purpose is named only by its declaration and its counterparties' own")
    void counterpartyClearingsAreNamedOnlyByDeclarations() {
        assertThat(COUNTERPARTY_PURPOSES).as("not vacuous: a counterparty-owned purpose exists")
                .contains("FX_PROVIDER_CLEARING");
        Map<String, String> sources = new LinkedHashMap<>();
        for (Path source : mainSources()) {
            sources.put(source.toString(), read(source));
        }
        assertThat(violations(sources, PERMITTED.keySet()))
                .as("a counterparty clearing named outside its declarations is a posting or a source"
                        + " that stops following the counterparty whose position it is (INV-RAIL-04)")
                .isEmpty();
        Map<String, Boolean> seen = new java.util.TreeMap<>();
        PERMITTED.keySet().forEach(file -> seen.put(file, false));
        seen.put(DECLARING_FILE, false);
        sources.forEach((path, text) -> {
            String fileName = Paths.get(path).getFileName().toString();
            if (seen.containsKey(fileName) && mentions(text)) {
                seen.put(fileName, true);
            }
        });
        assertThat(seen)
                .as("no permit is stale and the guard is not vacuous: each permitted file names one")
                .allSatisfy((file, found) -> assertThat(found).as(file).isTrue());
    }

    @Test
    @DisplayName("a planted naming is caught in each spelling the rule claims to see; prose and a permit are not")
    void aPlantedNamingIsCaught() {
        Map<String, String> planted = new LinkedHashMap<>();
        planted.put("/x/fx/src/main/java/com/finapp/fx/RogueCover.java",
                "chart.resolve(uow, AccountPurpose.FX_PROVIDER_CLEARING, \"fx-sim-a\", eur);");
        planted.put("/x/settlement/src/main/java/com/finapp/settlement/StaticImport.java",
                "import static com.finapp.ledger.AccountPurpose.FX_PROVIDER_CLEARING;\nOptional.of(FX_PROVIDER_CLEARING);");
        planted.put("/x/app/src/main/java/com/finapp/app/ByName.java",
                "AccountPurpose.valueOf(\"FX_PROVIDER_CLEARING\");");
        planted.put("/x/app/src/main/java/com/finapp/app/Prose.java",
                "// settles on AccountPurpose.FX_PROVIDER_CLEARING\n/** FX_PROVIDER_CLEARING */ int i = 0;");
        planted.put("/x/fx/src/main/java/com/finapp/fx/FxProviderDeclaration.java",
                "Optional.of(AccountPurpose.FX_PROVIDER_CLEARING)");
        assertThat(violations(planted, Set.of("FxProviderDeclaration.java")))
                .hasSize(3)
                .anyMatch(violation -> violation.contains("RogueCover.java"))
                .anyMatch(violation -> violation.contains("StaticImport.java"))
                .anyMatch(violation -> violation.contains("ByName.java"))
                .noneMatch(violation -> violation.contains("Prose.java"))
                .noneMatch(violation -> violation.contains("FxProviderDeclaration.java"));
    }

    // -----------------------------------------------------------------

    private static List<String> violations(Map<String, String> sources, Set<String> permitted) {
        List<String> outside = new ArrayList<>();
        sources.forEach(
                (path, text) -> {
                    String fileName = Paths.get(path).getFileName().toString();
                    if (DECLARING_FILE.equals(fileName) || permitted.contains(fileName)) {
                        return;
                    }
                    if (mentions(text)) {
                        outside.add("a counterparty clearing in " + path);
                    }
                });
        return outside;
    }

    private static boolean mentions(String source) {
        String literals = scan(source, true);
        return TOKEN.matcher(scan(source, false)).find()
                || COUNTERPARTY_PURPOSES.stream().anyMatch(literals::contains);
    }

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

    /**
     * {@code keepLiterals}: the string literals, one per line; otherwise the code with comments
     * AND literals stripped — the `RailVocabularyIsConfinedTest` scanner.
     */
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
