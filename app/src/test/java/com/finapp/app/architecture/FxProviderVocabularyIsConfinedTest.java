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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code INV-PAY-03} at the FX boundary (`P9-TSK-006`): the {@code fx-sim-a} wire's vocabulary -
 * its paths, its status and reason words, its field names - exists in exactly one production file,
 * {@code SimulatedFxProviderAdapter}. Everything else speaks the port's verdicts. A second file
 * naming {@code "quote_expired"} or {@code "/fx/executions"} is a second place the provider's
 * language could leak into the core, and this rule refuses it before it compiles into a decision.
 *
 * <p>Scans every module's {@code src/main/java} for string LITERALS carrying a vocabulary token
 * (comments are ignored - prose may name the wire); the adapter is the one permitted file.
 */
@DisplayName("the FX provider's wire vocabulary is confined to its adapter (P9-TSK-006, INV-PAY-03)")
class FxProviderVocabularyIsConfinedTest {

    private static final String ADAPTER = "SimulatedFxProviderAdapter.java";

    /** The wire's own words - paths, statuses, reasons and field names no port speaks. */
    private static final List<String> VOCABULARY =
            List.of(
                    "/fx/quotes", "/fx/executions", "quote_expired", "price_changed",
                    "pair_not_quoted", "amount_out_of_range", "market_closed", "quoteRef",
                    "tradeRef", "validForSeconds", "counterCurrency", "soldCurrency",
                    "boughtCurrency");


    @Test
    @DisplayName("no production file but the adapter carries the wire's vocabulary - and the adapter"
            + " really does")
    void theVocabularyLivesInTheAdapterAlone() {
        Map<String, String> sources = new LinkedHashMap<>();
        for (Path source : mainSources()) {
            sources.put(source.toString(), read(source));
        }
        assertThat(violations(sources))
                .as("the FX provider's wire words outside its adapter (INV-PAY-03)")
                .isEmpty();
        assertThat(sources.entrySet())
                .as("not vacuous: the adapter carries the vocabulary")
                .anySatisfy(
                        entry -> {
                            assertThat(entry.getKey()).endsWith(ADAPTER);
                            assertThat(literalsOf(entry.getValue())).contains("quote_expired");
                        });
    }

    @Test
    @DisplayName("a planted leak is caught; prose and the adapter are not")
    void aPlantedLeakIsCaught() {
        Map<String, String> planted = new LinkedHashMap<>();
        planted.put("/x/fx/src/main/java/com/finapp/fx/RogueCover.java",
                "if (body.contains(\"quote_expired\")) { requote(); }");
        planted.put("/x/app/src/main/java/com/finapp/app/fx/RoguePath.java",
                "URI uri = base.resolve(\"/fx/executions/\" + t);");
        planted.put("/x/app/src/main/java/com/finapp/app/fx/Prose.java",
                "// the provider answers quote_expired; we map it at the adapter\nString s = \"x\";");
        planted.put("/x/app/src/main/java/com/finapp/app/fx/" + ADAPTER,
                "case \"quote_expired\" -> x;");
        assertThat(violations(planted))
                .hasSize(2)
                .anyMatch(v -> v.contains("RogueCover.java"))
                .anyMatch(v -> v.contains("RoguePath.java"));
    }

    // -----------------------------------------------------------------

    private static List<String> violations(Map<String, String> sources) {
        List<String> found = new ArrayList<>();
        sources.forEach(
                (path, text) -> {
                    if (Paths.get(path).getFileName().toString().equals(ADAPTER)) {
                        return;
                    }
                    for (String literal : literalsOf(text)) {
                        for (String word : VOCABULARY) {
                            if (literal.contains(word)) {
                                found.add(word + " in " + path);
                            }
                        }
                    }
                });
        return found;
    }

    /**
     * The string literals of {@code source}, comments skipped - a character scanner, because a
     * literal-matching regex recurses per character and overflows the stack on large files.
     */
    private static List<String> literalsOf(String source) {
        List<String> literals = new ArrayList<>();
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
            } else if (c == '\'') {
                int end = i + 1;
                while (end < length && source.charAt(end) != '\'') {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                i = end + 1;
            } else if (c == '"') {
                StringBuilder literal = new StringBuilder();
                int end = i + 1;
                while (end < length && source.charAt(end) != '"') {
                    if (source.charAt(end) == '\\' && end + 1 < length) {
                        literal.append(source.charAt(end + 1));
                        end += 2;
                    } else {
                        literal.append(source.charAt(end));
                        end++;
                    }
                }
                literals.add(literal.toString());
                i = end + 1;
            } else {
                i++;
            }
        }
        return literals;
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
                    files.filter(file -> file.toString().endsWith(".java")).forEach(sources::add);
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
}
