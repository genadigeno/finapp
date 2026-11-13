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
 * (the {@code FxBooksHaveOnePosterTest} scanner); planted violations prove it bites. A census reader
 * ({@link #CENSUS_READERS}, `P15-TSK-035`'s source-join read port) is permitted by name AND held to {@link CensusReaderForm}:
 * the clearing a string in a figure, no counterparty resolved, no ledger type reached.
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
                    "PostingRuleCatalogue.java",
                    "READER (P14-TSK-002; permitted when the guard was found red on master, 2026-11-04): the posting-rule register"
                            + " names each rule's accounts as data, to judge an entry already posted; it posts nothing and reaches no poster",
                    "SafeguardingPositionDefinition.java",
                    "READER (P14-TSK-019): the reference safeguarding return names each purpose's place in it, by name, over the journal's read-only reads; it posts nothing and reaches no poster",
                    "PurposeReportDefinition.java",
                    "READER (P14-TSK-017; permitted when the guard was found red on master, 2026-11-04): a report's definition names"
                            + " the purposes it sums from journal lines, on a read-only snapshot; it posts nothing and reaches no poster",
                    "FxProviderDeclaration.java",
                    "DECLARATION: an FX provider's clearingPurpose() (P9-TSK-011) - the settlement"
                            + " composition and the counterparty chart read it, never name it",
                    "SimulatedCorridorAdapter.java",
                    "DECLARATION: the corridor rail's clearingPurpose() (P9-TSK-014) - the settlement"
                            + " composition and the counterparty chart read it off the rail, never name it");

    /**
     * The census read ports permitted to name a counterparty clearing, each held to {@link CensusReaderForm} as well as named:
     * a permit by file name alone would admit a posting or a source planted beside the reader.
     */
    static final Map<String, String> CENSUS_READERS =
            Map.of(
                    "FxJournalSources.java",
                    "CENSUS READER (P15-TSK-035; permitted when the guard was found red on master, 2026-11-13): fx's read port"
                            + " for the source-join census states a cover's executed figures on the lines whose purpose is the"
                            + " string FX_PROVIDER_CLEARING, read from fx.cover_execution by SELECT; it resolves no counterparty"
                            + " and posts nothing",
                    "PaymentJournalSources.java",
                    "CENSUS READER (P15-TSK-035; permitted when the guard was found red on master, 2026-11-13): payments' read"
                            + " port for the source-join census states an outbound credit's and its return's figures on the lines"
                            + " whose purpose is the string CORRIDOR_CLEARING, read from payments.outbound_credit and"
                            + " outbound_credit_return by SELECT; it resolves no counterparty and posts nothing");

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
                .contains("FX_PROVIDER_CLEARING", "CORRIDOR_CLEARING");
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
        CENSUS_READERS.keySet().forEach(file -> seen.put(file, false));
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

    @Test
    @DisplayName("a census reader is permitted only in its read-only form: a source planted in the permitted file, a by-name"
            + " resolver, a raw ledger read of the clearing and an unpermitted port are caught")
    void aCensusReaderIsHeldToItsForm() {
        String port = "public final class PaymentJournalSources implements JournalSources {\n"
                + "private static final Set<String> CORRIDOR = Set.of(\"CORRIDOR_CLEARING\");\n";
        Map<String, String> planted = new LinkedHashMap<>();
        planted.put(
                "/reader/payments/src/main/java/com/finapp/payments/PaymentJournalSources.java",
                port + "static final String CREDIT_ROW = \"SELECT id::text AS key_text FROM payments.outbound_credit WHERE id = ANY (?)\";\n}");
        planted.put(
                "/source/payments/src/main/java/com/finapp/payments/PaymentJournalSources.java",
                port + "Optional<AccountPurpose> clearingPurpose() { return Optional.of(AccountPurpose.CORRIDOR_CLEARING); }\n}");
        planted.put(
                "/byname/fx/src/main/java/com/finapp/fx/FxJournalSources.java",
                "public final class FxJournalSources implements JournalSources {\n"
                        + "Object clearing() { return AccountPurpose.valueOf(\"FX_PROVIDER_CLEARING\"); }\n}");
        planted.put(
                "/rawsql/fx/src/main/java/com/finapp/fx/FxJournalSources.java",
                "public final class FxJournalSources implements JournalSources {\n"
                        + "static final String CLEARING = \"SELECT id FROM ledger.ledger_account WHERE purpose = 'FX_PROVIDER_CLEARING'\";\n}");
        planted.put(
                "/unpermitted/crossborder/src/main/java/com/finapp/crossborder/CrossBorderJournalSources.java",
                "public final class CrossBorderJournalSources implements JournalSources {\n"
                        + "private static final Set<String> CORRIDOR = Set.of(\"CORRIDOR_CLEARING\");\n}");

        assertThat(violations(planted, PERMITTED.keySet()))
                .as("the reader in its form passes; each other plant names a clearing outside its declarations")
                .hasSize(4)
                .noneMatch(violation -> violation.contains("/reader/"))
                .anyMatch(violation -> violation.contains("/source/"))
                .anyMatch(violation -> violation.contains("/byname/"))
                .anyMatch(violation -> violation.contains("/rawsql/"))
                .anyMatch(violation -> violation.contains("/unpermitted/"));
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
                    if (CENSUS_READERS.containsKey(fileName)) {
                        if (!CensusReaderForm.holds(text, TOKEN)) {
                            outside.add("a counterparty clearing in " + path + ": a census reader's permit, but not in a"
                                    + " census reader's read-only form (CensusReaderForm)");
                        }
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
