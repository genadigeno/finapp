package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The FX books have one poster (`P9-TSK-009`; PHASE_9_PLAN.md section 12.6): {@code FX_POSITION},
 * {@code FX_SPREAD_REVENUE}, {@code ROUNDING_RESIDUAL}, {@code FX_REALISED_GAINS} and
 * {@code FX_REALISED_LOSSES} are named in production code only by their declaration and {@code fx}'s
 * two line composers - {@code ConversionLines} and {@code CoverLines} (`P9-TSK-012`) - a second place
 * naming one is a second way value could move on it. Read from the
 * code with comments stripped and from the string literals, the {@code CashAtBankHasOnePosterTest}
 * scanner; planted posters prove the rule bites.
 */
@DisplayName("the FX books have one poster (P9-TSK-009, INV-FX-01, INV-FX-03, INV-FX-07)")
class FxBooksHaveOnePosterTest {

    /** The declaration - its own members name themselves. */
    private static final String DECLARING_FILE = "AccountPurpose.java";

    /** Every permitted file and its one role. */
    private static final Map<String, String> PERMITTED =
            Map.of(
                    "ConversionLines.java",
                    "POSTER: a wallet conversion's lines - wallet against FX_POSITION per currency,"
                            + " the margin to FX_SPREAD_REVENUE and the residual to ROUNDING_RESIDUAL"
                            + " in the computed leg's currency",
                    "CoverLines.java",
                    "POSTER: a cover's lines (P9-TSK-012) - FX_POSITION's plan legs closed onto the"
                            + " provider's own clearing, the difference to FX_REALISED_GAINS or"
                            + " FX_REALISED_LOSSES in that leg's currency");

    /** The FX books: the position, the spread revenue, the rounding residual and the realised results. */
    private static final List<String> BOOKS =
            List.of("FX_POSITION", "FX_SPREAD_REVENUE", "ROUNDING_RESIDUAL", "FX_REALISED_GAINS", "FX_REALISED_LOSSES");

    private static final Pattern TOKEN = Pattern.compile(
            "\\b(FX_POSITION|FX_SPREAD_REVENUE|ROUNDING_RESIDUAL|FX_REALISED_GAINS|FX_REALISED_LOSSES)\\b");

    @Test
    @DisplayName("the FX books are named only by their declaration and the conversion's line composer")
    void theFxBooksAreNamedOnlyByTheirPoster() {
        Map<String, String> sources = new LinkedHashMap<>();
        for (Path source : mainSources()) {
            sources.put(source.toString(), read(source));
        }
        assertThat(violations(sources))
                .as("a second place naming an FX book is a second way value could move on it:"
                        + " only a conversion's lines (and, from P9-TSK-012, a cover's) post there")
                .isEmpty();

        Map<String, Boolean> seen = new TreeMap<>();
        PERMITTED.keySet().forEach(file -> seen.put(file, false));
        seen.put(DECLARING_FILE, false);
        sources.forEach(
                (path, text) -> {
                    String fileName = Paths.get(path).getFileName().toString();
                    if (seen.containsKey(fileName) && mentions(text)) {
                        seen.put(fileName, true);
                    }
                });
        assertThat(seen)
                .as("no permit is stale and the guard is not vacuous: the declaration and the poster"
                        + " each really name the books")
                .allSatisfy((file, found) -> assertThat(found).as(file).isTrue());
    }

    @Test
    @DisplayName("a planted poster is caught, in each spelling the rule claims to see, and prose is ignored")
    void aPlantedPosterIsCaught() {
        Map<String, String> planted = new LinkedHashMap<>();
        planted.put(
                "/x/fx/src/main/java/com/finapp/fx/RogueConversion.java",
                "chart.resolve(uow, AccountPurpose.FX_SPREAD_REVENUE, usd);");
        planted.put(
                "/x/app/src/main/java/com/finapp/app/StaticImportPoster.java",
                "import static com.finapp.ledger.AccountPurpose.FX_POSITION;\nchart.resolve(uow, FX_POSITION, eur);");
        planted.put(
                "/x/app/src/main/java/com/finapp/app/ByName.java",
                "AccountPurpose purpose = AccountPurpose.valueOf(\"ROUNDING_RESIDUAL\");");
        planted.put(
                "/x/app/src/main/java/com/finapp/app/Prose.java",
                "// posts to AccountPurpose.FX_POSITION only via ConversionLines\n/** FX_SPREAD_REVENUE */ int i = 0;");
        planted.put(
                "/x/fx/src/main/java/com/finapp/fx/ConversionLines.java",
                "chart.resolve(uow, AccountPurpose.FX_POSITION, eur);");
        assertThat(violations(planted))
                .hasSize(3)
                .anyMatch(violation -> violation.contains("RogueConversion.java"))
                .anyMatch(violation -> violation.contains("StaticImportPoster.java"))
                .anyMatch(violation -> violation.contains("ByName.java"))
                .noneMatch(violation -> violation.contains("Prose.java"))
                .noneMatch(violation -> violation.contains("ConversionLines.java"));
    }

    // -----------------------------------------------------------------

    private static List<String> violations(Map<String, String> sources) {
        List<String> outside = new ArrayList<>();
        sources.forEach(
                (path, text) -> {
                    String fileName = Paths.get(path).getFileName().toString();
                    if (DECLARING_FILE.equals(fileName) || PERMITTED.containsKey(fileName)) {
                        return;
                    }
                    if (mentions(text)) {
                        outside.add("an FX book in " + path);
                    }
                });
        return outside;
    }

    private static boolean mentions(String source) {
        String literals = scan(source, true);
        return TOKEN.matcher(scan(source, false)).find() || BOOKS.stream().anyMatch(literals::contains);
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
