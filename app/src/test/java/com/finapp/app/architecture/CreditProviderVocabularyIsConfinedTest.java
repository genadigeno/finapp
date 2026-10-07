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
 * {@code INV-PAY-03}'s rule at the credit bureau boundary (`P10-TSK-005`, ADR-0085 section 2): the
 * {@code bureau-sim-a} wire's vocabulary - its path, its status words and its report's field names -
 * exists in exactly one production file, {@code SimulatedBureauAdapter}. Everything else speaks the
 * port's {@code CreditDataAnswer} and {@code CreditAttributeCode}. A second file naming
 * {@code "report_partial"} or {@code "totalBalance"} is a second place a bureau's language could leak
 * into the decision, and this rule refuses it - the {@code FxProviderVocabularyIsConfinedTest} shape.
 *
 * <p>Scans every module's {@code src/main/java} for string LITERALS carrying a vocabulary token
 * (comments are ignored - prose may name the wire); the adapter is the one permitted file.
 */
@DisplayName("the credit bureau's wire vocabulary is confined to its adapter (P10-TSK-005)")
class CreditProviderVocabularyIsConfinedTest {

    private static final String ADAPTER = "SimulatedBureauAdapter.java";

    /** The financial-data provider's adapter (`P10-TSK-007`) - its words confined to it, as the bureau's to its own. */
    private static final String FINDATA_ADAPTER = "SimulatedFinancialDataAdapter.java";

    /** Each adapter's wire words - path, statuses and field names no port speaks - and the one file allowed them. */
    private static final Map<String, List<String>> VOCABULARIES =
            Map.of(
                    ADAPTER,
                    List.of(
                            "/bureau/reports", "report_complete", "report_partial", "externalScore",
                            "activeAccounts", "delinquencies24m", "defaults72m", "insolvencyFlag",
                            "monthlyObligations", "totalBalance"),
                    FINDATA_ADAPTER,
                    List.of(
                            "/findata/summaries", "summary_complete", "summary_partial", "verifiedMonthlyIncome",
                            "committedMonthlyExpenditure"));

    @Test
    @DisplayName("no production file but the adapter carries the wire's vocabulary - and the adapter really does")
    void theVocabularyLivesInTheAdapterAlone() {
        Map<String, String> sources = new LinkedHashMap<>();
        for (Path source : mainSources()) {
            sources.put(source.toString(), read(source));
        }
        assertThat(violations(sources))
                .as("the credit bureau's wire words outside its adapter")
                .isEmpty();
        assertThat(sources.entrySet())
                .as("not vacuous: the adapter carries the vocabulary")
                .anySatisfy(
                        entry -> {
                            assertThat(entry.getKey()).endsWith(ADAPTER);
                            assertThat(literalsOf(entry.getValue())).contains("report_partial", "totalBalance");
                        });
        assertThat(sources.entrySet())
                .as("not vacuous: the financial-data adapter carries its vocabulary")
                .anySatisfy(
                        entry -> {
                            assertThat(entry.getKey()).endsWith(FINDATA_ADAPTER);
                            assertThat(literalsOf(entry.getValue())).contains("summary_partial", "verifiedMonthlyIncome");
                        });
    }

    @Test
    @DisplayName("a planted leak is caught; prose and the adapter are not")
    void aPlantedLeakIsCaught() {
        Map<String, String> planted = new LinkedHashMap<>();
        planted.put("/x/credit/src/main/java/com/finapp/credit/RogueEvaluator.java",
                "if (report.contains(\"report_partial\")) { refer(); }");
        planted.put("/x/app/src/main/java/com/finapp/app/credit/RogueReader.java",
                "Matcher m = Pattern.compile(\"\\\"totalBalance\\\"\").matcher(body);");
        planted.put("/x/app/src/main/java/com/finapp/app/credit/Prose.java",
                "// the bureau answers report_partial; we map it at the adapter\nString s = \"x\";");
        planted.put("/x/app/src/main/java/com/finapp/app/credit/" + ADAPTER,
                "case \"report_partial\" -> x;");
        // One adapter's words in the OTHER adapter are a leak too - each wire is its own.
        planted.put("/x/app/src/main/java/com/finapp/app/credit/" + FINDATA_ADAPTER,
                "case \"summary_partial\" -> x; String leak = \"totalBalance\";");
        assertThat(violations(planted))
                .hasSize(3)
                .anyMatch(v -> v.contains("totalBalance") && v.contains(FINDATA_ADAPTER))
                .anyMatch(v -> v.contains("RogueEvaluator.java"))
                .anyMatch(v -> v.contains("RogueReader.java"));
    }

    // -----------------------------------------------------------------

    private static List<String> violations(Map<String, String> sources) {
        List<String> found = new ArrayList<>();
        sources.forEach(
                (path, text) -> {
                    String file = Paths.get(path).getFileName().toString();
                    for (String literal : literalsOf(text)) {
                        VOCABULARIES.forEach((adapter, words) -> {
                            if (file.equals(adapter)) {
                                return;
                            }
                            for (String word : words) {
                                if (literal.contains(word)) {
                                    found.add(word + " in " + path);
                                }
                            }
                        });
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
