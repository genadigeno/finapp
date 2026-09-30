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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `INV-PAY-03` at the settlement door (`P8-TSK-008`, ADR-0066 §8): <strong>a counterparty's
 * vocabulary never leaves its format adapter</strong>. The simulated PSP says {@code SALE};
 * the platform says {@code CAPTURE} — and the mapping lives in exactly one package,
 * {@code com.finapp.settlement.format.<format>}, so core settlement and reconciliation code
 * can never branch on a provider's word, and `-016`…`-018`'s formats arrive additively.
 *
 * <p>The {@code RailVocabularyIsConfinedTest} mechanism: comment-stripped whole string
 * literals for the words, comment-and-literal-stripped code for the adapter type, each with
 * its planted-violation control. Scope: the settlement module OUTSIDE the format packages,
 * and reconciliation — the modules that consume canonical lines. The canonical vocabulary
 * ({@code SettlementLineType}) rides enums there, never provider literals.
 */
@DisplayName("settlement provider vocabulary is confined (P8-TSK-008, INV-PAY-03)")
class SettlementVocabularyIsConfinedTest {

    /** The simulated PSP's own words — SIM_PSP_CSV v1's whole provider vocabulary. */
    private static final Set<String> PROVIDER_WORDS =
            Set.of("SALE", "REFUND", "CHARGEBACK", "CB_REVERSAL", "DISPUTE_FEE", "ADJUSTMENT");

    /** Each adapter type, confined to its own package and the composition root. */
    private static final Set<String> ADAPTER_TYPES = Set.of("SimPspCsvFormat");

    /** The composition root may bind an adapter; nothing else outside its package may. */
    private static final Set<String> CONFIGURATION_FILES = Set.of("SettlementBeans.java");

    @Test
    @DisplayName("no provider word exists as a literal in settlement core or reconciliation")
    void providerWordsAreConfinedToTheFormatPackages() {
        List<String> outside = new ArrayList<>();
        int adapterDeclarations = 0;
        for (Path source : mainSources()) {
            String path = source.toString().replace('\\', '/');
            boolean settlementCore =
                    path.contains("/settlement/src/main/java/")
                            && !path.contains("/settlement/format/");
            boolean reconciliation = path.contains("/reconciliation/src/main/java/");
            boolean formatAdapter = path.contains("/settlement/format/");
            if (!settlementCore && !reconciliation && !formatAdapter) {
                continue;
            }
            for (String literal : stringLiteralsOf(read(source)).split("\n")) {
                if (!PROVIDER_WORDS.contains(literal)) {
                    continue;
                }
                if (formatAdapter) {
                    adapterDeclarations++;
                } else {
                    outside.add("\"" + literal + "\" in " + source);
                }
            }
        }
        assertThat(outside)
                .as("a provider word outside the format packages is core code holding a"
                        + " counterparty's vocabulary (INV-PAY-03): the canonical line type"
                        + " is the platform's word, and the mapping lives in the adapter")
                .isEmpty();
        assertThat(adapterDeclarations)
                .as("the guard is not vacuous: the adapter really declares its words")
                .isGreaterThanOrEqualTo(PROVIDER_WORDS.size());
    }

    @Test
    @DisplayName("each format adapter type is referenced only inside its own package and the"
            + " composition root")
    void adapterTypesAreConfined() {
        List<String> outside = new ArrayList<>();
        java.util.Map<String, Boolean> configured = new java.util.TreeMap<>();
        ADAPTER_TYPES.forEach(type -> configured.put(type, false));
        for (Path source : mainSources()) {
            String path = source.toString().replace('\\', '/');
            String fileName = source.getFileName().toString();
            String code = codeOf(read(source));
            for (String type : ADAPTER_TYPES) {
                if (!code.contains(type)) {
                    continue;
                }
                if (path.contains("/settlement/format/")) {
                    continue; // Its own package's business, tests' included.
                }
                if (CONFIGURATION_FILES.contains(fileName)) {
                    configured.put(type, true);
                } else {
                    outside.add(type + " in " + source);
                }
            }
        }
        assertThat(outside)
                .as("a format adapter referenced outside its package and the composition root"
                        + " is provider knowledge leaking into the core (INV-PAY-03)")
                .isEmpty();
        assertThat(configured)
                .as("no permit is stale: the composition root really binds every adapter")
                .allSatisfy((type, bound) -> assertThat(bound).as(type).isTrue());
    }

    @Test
    @DisplayName("the scanners reject their violations and ignore prose")
    void scannersRejectTheirViolations() {
        // The planted provider code the backlog names: a switch on "SALE" in core code.
        assertThat(stringLiteralsOf("if (type.equals(\"SALE\")) { capture(); }").split("\n"))
                .contains("SALE");
        // The planted adapter type outside com.finapp.settlement.format.<format>.
        assertThat(codeOf("Object format = SimPspCsvFormat.INSTANCE;"))
                .contains("SimPspCsvFormat");
        // Prose is not a control, and only a WHOLE literal matches a word.
        assertThat(codeOf("// SimPspCsvFormat maps SALE\n/** SimPspCsvFormat */ int x;"))
                .doesNotContain("SimPspCsvFormat");
        assertThat(stringLiteralsOf("String s = \"WHOLESALE\"; // SALE in prose").split("\n"))
                .doesNotContain("SALE");
    }

    // ----------------------------------------------------------------- the shared scanners

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

    /** String literals, one per line, comments stripped — the shared scanner. */
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
