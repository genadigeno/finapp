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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `INV-PAY-03` at the settlement door (`P8-TSK-008`, ADR-0066 §8): <strong>a counterparty's
 * vocabulary never leaves its format adapter</strong>. The simulated PSP says {@code SALE};
 * the platform says {@code CAPTURE} — and the mapping lives in exactly one package,
 * {@code com.finapp.settlement.format.<format>}, so core settlement and reconciliation code
 * can never branch on a provider's word, and `-016`…`-018`'s formats arrive additively (the
 * simulated bank's record tags, `P8-TSK-016`, are the second adapter's words, the simulated
 * instant scheme's entry codes, `P8-TSK-017`, the third's, and the simulated payout provider's
 * record codes, `P8-TSK-018`, the fourth's — each adapter held to declaring its own).
 *
 * <p>The {@code RailVocabularyIsConfinedTest} mechanism: comment-stripped whole string
 * literals for the words, comment-and-literal-stripped code for the adapter type, each with
 * its planted-violation control. Scope: the settlement module OUTSIDE the format packages,
 * and reconciliation — the modules that consume canonical lines. The canonical vocabulary
 * ({@code SettlementLineType}) rides enums there, never provider literals.
 */
@DisplayName("settlement provider vocabulary is confined (P8-TSK-008, INV-PAY-03)")
class SettlementVocabularyIsConfinedTest {

    /**
     * Each adapter's own words, by adapter type: SIM_PSP_CSV v1's provider types,
     * SIM_STATEMENT_TAGGED v1's record tags (`P8-TSK-016`), SIM_SCHEME_JSON v1's entry codes
     * (`P8-TSK-017`), and SIM_PAYOUT_CSV v1's record codes (`P8-TSK-018`). Per adapter, so the
     * non-vacuity guard holds for each one — a second adapter's words can never stand in for a
     * first's.
     */
    private static final Map<String, Set<String>> PROVIDER_WORDS_BY_ADAPTER =
            Map.of(
                    "SimPspCsvFormat",
                    Set.of("SALE", "REFUND", "CHARGEBACK", "CB_REVERSAL", "DISPUTE_FEE",
                            "ADJUSTMENT"),
                    "SimStatementTaggedFormat",
                    Set.of(":20:", ":25:", ":28C:", ":60F:", ":61:", ":86:", ":62F:"),
                    "SimSchemeJsonFormat",
                    Set.of("CT", "RT"),
                    "SimPayoutCsvFormat",
                    Set.of("SETTLED", "RETURNED"));

    /** Every adapter's words together — none may appear as a literal outside the adapters. */
    private static final Set<String> PROVIDER_WORDS =
            PROVIDER_WORDS_BY_ADAPTER.values().stream()
                    .flatMap(Set::stream)
                    .collect(Collectors.toUnmodifiableSet());

    /** Each adapter type, confined to its own package and the composition root. */
    private static final Set<String> ADAPTER_TYPES =
            Set.of("SimPspCsvFormat", "SimStatementTaggedFormat", "SimSchemeJsonFormat",
                    "SimPayoutCsvFormat");

    /** The composition root may bind an adapter; nothing else outside its package may. */
    private static final Set<String> CONFIGURATION_FILES = Set.of("SettlementBeans.java");

    @Test
    @DisplayName("no provider word exists as a literal in settlement core or reconciliation")
    void providerWordsAreConfinedToTheFormatPackages() {
        List<String> outside = new ArrayList<>();
        Map<String, Set<String>> declared = new TreeMap<>();
        ADAPTER_TYPES.forEach(type -> declared.put(type, new TreeSet<>()));
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
            // The adapter whose package this file sits in, if any: its words count for it alone.
            String owningAdapter =
                    formatAdapter
                            ? ADAPTER_TYPES.stream()
                                    .filter(type -> Files.exists(
                                            source.resolveSibling(type + ".java")))
                                    .findFirst()
                                    .orElse(null)
                            : null;
            for (String literal : stringLiteralsOf(read(source)).split("\n")) {
                if (!PROVIDER_WORDS.contains(literal)) {
                    continue;
                }
                if (formatAdapter) {
                    if (owningAdapter != null
                            && PROVIDER_WORDS_BY_ADAPTER.get(owningAdapter).contains(literal)) {
                        declared.get(owningAdapter).add(literal);
                    }
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
        assertThat(PROVIDER_WORDS_BY_ADAPTER.keySet())
                .as("every adapter names its own words, and every word list has its adapter")
                .containsExactlyInAnyOrderElementsOf(ADAPTER_TYPES);
        PROVIDER_WORDS_BY_ADAPTER.forEach(
                (adapter, words) ->
                        assertThat(declared.get(adapter))
                                .as("the guard is not vacuous for %s: that adapter's own package"
                                        + " really declares each of its words as a whole"
                                        + " literal", adapter)
                                .containsExactlyInAnyOrderElementsOf(words));
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
        // The planted statement-tag leak (P8-TSK-016): core code reading the bank's own
        // record tag instead of the canonical line type - a word the scan knows.
        String[] plantedTag =
                stringLiteralsOf("if (record.startsWith(\":61:\")) { bankLine(); }").split("\n");
        assertThat(plantedTag).contains(":61:");
        assertThat(PROVIDER_WORDS).contains(":61:");
        // The planted scheme-code leak (P8-TSK-017): core code branching on the scheme's credit
        // transfer code instead of the canonical line type - a word the scan knows.
        String[] plantedCode =
                stringLiteralsOf("if (entry.code().equals(\"CT\")) { creditIn(); }").split("\n");
        assertThat(plantedCode).contains("CT");
        assertThat(PROVIDER_WORDS).contains("CT", "RT");
        // The planted payout-code leak (P8-TSK-018): core code branching on the payout
        // provider's return code instead of the canonical line type - a word the scan knows.
        String[] plantedPayoutCode =
                stringLiteralsOf("if (record.code().equals(\"RETURNED\")) { payoutReturned(); }")
                        .split("\n");
        assertThat(plantedPayoutCode).contains("RETURNED");
        assertThat(PROVIDER_WORDS).contains("SETTLED", "RETURNED");
        // The planted adapter types outside com.finapp.settlement.format.<format>.
        assertThat(codeOf("Object format = SimPspCsvFormat.INSTANCE;"))
                .contains("SimPspCsvFormat");
        assertThat(codeOf("SettlementFormat format = new SimStatementTaggedFormat(accounts);"))
                .contains("SimStatementTaggedFormat");
        assertThat(codeOf("SettlementFormat format = SimSchemeJsonFormat.INSTANCE;"))
                .contains("SimSchemeJsonFormat");
        assertThat(codeOf("SettlementFormat format = SimPayoutCsvFormat.INSTANCE;"))
                .contains("SimPayoutCsvFormat");
        // Prose is not a control, and only a WHOLE literal matches a word.
        assertThat(codeOf("// SimPspCsvFormat maps SALE\n/** SimPspCsvFormat */ int x;"))
                .doesNotContain("SimPspCsvFormat");
        assertThat(stringLiteralsOf("String s = \"WHOLESALE\"; // SALE in prose").split("\n"))
                .doesNotContain("SALE");
        assertThat(stringLiteralsOf("String s = \":61:2026-09-25,C,1.00\"; // :61: in prose")
                        .split("\n"))
                .doesNotContain(":61:");
        assertThat(stringLiteralsOf("String s = \"CTRL\"; // CT in prose").split("\n"))
                .doesNotContain("CT");
        assertThat(stringLiteralsOf("String s = \"UNSETTLED\"; // SETTLED in prose").split("\n"))
                .doesNotContain("SETTLED");
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
