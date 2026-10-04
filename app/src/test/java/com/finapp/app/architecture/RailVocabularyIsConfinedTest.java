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
            Map.of(
                    "card", "SimulatedCardPspAdapter.java",
                    "instant", "SimulatedInstantSchemeAdapter.java",
                    // The book rail (P7-TSK-011): no adapter exists because no wire
                    // does, so the declaration class IS the declaring file.
                    "book", "BookRail.java");

    /** Each declaration constant, confined to its adapter and the composition root. */
    private static final Map<String, String> DECLARATION_CONSTANTS =
            Map.of(
                    "SimulatedCardPspAdapter.RAIL", "SimulatedCardPspAdapter.java",
                    "SimulatedInstantSchemeAdapter.RAIL", "SimulatedInstantSchemeAdapter.java",
                    "BookRail.RAIL", "BookRail.java");

    /**
     * The composition root may bind the declaration; nothing else may name it.
     * {@code SettlementBeans} joined with `P8-TSK-002`: the source register reads each rail's
     * clearing position off its declaration, which is exactly a composition-root binding.
     */
    private static final Set<String> CONFIGURATION_FILES =
            Set.of("PaymentBeans.java", "SettlementBeans.java");

    /**
     * Each external rail's clearing position, named only by the declaration that owns it
     * (`P7-DOC-001`, {@code INV-RAIL-04}): every posting in {@code payments} reads its clearing
     * off the stored rail's declared {@code clearingPurpose}. The Phase 7 review found the
     * withdrawal's completion naming {@code INSTANT_CLEARING} itself - right only while one push
     * rail exists, and invisible to the rail-name rule above because a purpose is not a name.
     * Scoped to {@code payments}, the module that ACTS on rails: the merchant drill-down's
     * read-side labelling of entry shapes decides nothing a rail does.
     *
     * <p><em>Amended `P9-TSK-010` (ADR-0078; ADR-0080's "several declaring adapters, each with
     * its own counterparty code"): each position maps to the SET of files that may declare it -
     * a second adapter of one position joins its set, and each set must be found at least once.
     * A counterparty-owned position is not here at all: it is never a rail's, and
     * {@code CounterpartyClearingIsNamedByDeclarationsTest} confines it to its counterparties'
     * declarations.</em>
     */
    private static final Map<String, Set<String>> CLEARING_POSITIONS =
            Map.of(
                    "AccountPurpose.SETTLEMENT_CLEARING", Set.of("SimulatedCardPspAdapter.java"),
                    "AccountPurpose.INSTANT_CLEARING", Set.of("SimulatedInstantSchemeAdapter.java"));

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
    @DisplayName("every declaration constant is referenced only by its adapter and"
            + " configuration - and each is really bound by the composition root")
    void theDeclarationConstantsAreConfined() {
        List<String> outside = new ArrayList<>();
        Map<String, Boolean> configured = new java.util.TreeMap<>();
        DECLARATION_CONSTANTS.keySet().forEach(constant -> configured.put(constant, false));
        for (Path source : mainSources()) {
            String fileName = source.getFileName().toString();
            String code = codeOf(read(source));
            for (Map.Entry<String, String> constant : DECLARATION_CONSTANTS.entrySet()) {
                if (!code.contains(constant.getKey())) {
                    continue;
                }
                if (CONFIGURATION_FILES.contains(fileName)) {
                    configured.put(constant.getKey(), true);
                } else if (!constant.getValue().equals(fileName)) {
                    outside.add(constant.getKey() + " in " + source);
                }
            }
        }
        assertThat(outside)
                .as("a declaration constant referenced outside its adapter and the"
                        + " composition root is core code holding a rail by name"
                        + " (INV-RAIL-01)")
                .isEmpty();
        assertThat(configured)
                .as("no permit is stale: the composition root really binds EVERY declaration"
                        + " (the P1-TSK-015 rule - an exemption naming nothing silently"
                        + " stops applying)")
                .allSatisfy((constant, bound) -> assertThat(bound).as(constant).isTrue());
    }

    @Test
    @DisplayName("a rail's clearing position is named in payments only by its declaring adapter"
            + " - every posting reads it off the stored rail (P7-DOC-001, INV-RAIL-04)")
    void clearingPositionsAreNamedOnlyByTheirDeclarations() {
        List<String> outside = new ArrayList<>();
        Map<String, Boolean> declared = new java.util.TreeMap<>();
        CLEARING_POSITIONS.keySet().forEach(purpose -> declared.put(purpose, false));
        for (Path source : mainSources()) {
            if (!source.toString().replace('\\', '/').contains("/payments/src/main/java/")) {
                continue;
            }
            String fileName = source.getFileName().toString();
            String code = codeOf(read(source));
            for (Map.Entry<String, Set<String>> purpose : CLEARING_POSITIONS.entrySet()) {
                if (!code.contains(purpose.getKey())) {
                    continue;
                }
                if (purpose.getValue().contains(fileName)) {
                    declared.put(purpose.getKey(), true);
                } else {
                    outside.add(purpose.getKey() + " in " + source);
                }
            }
        }
        assertThat(outside)
                .as("a clearing position named outside its rail's declaration is a posting"
                        + " that stops following the rail it was made on (INV-RAIL-01, -04)")
                .isEmpty();
        assertThat(declared)
                .as("the guard is not vacuous: each clearing position is found where its rail"
                        + " declares it")
                .allSatisfy((purpose, found) -> assertThat(found).as(purpose).isTrue());
    }

    @Test
    @DisplayName("settlement and reconciliation name no clearing position at all - positions"
            + " come from declarations, composed in app (P8-TSK-002, INV-SET-05, INV-RAIL-04)")
    void theSettlementModulesNameNoClearingPosition() {
        // Wider than CLEARING_POSITIONS on purpose: PAYOUT_CLEARING and every later rail's
        // position are equally out of reach - the modules that reconcile positions must read
        // them from the register app composes, or two authorities drift.
        java.util.regex.Pattern anyClearing =
                java.util.regex.Pattern.compile("AccountPurpose\\.[A-Z_]*_CLEARING");
        List<String> outside = new ArrayList<>();
        int seen = 0;
        for (Path source : mainSources()) {
            String path = source.toString().replace('\\', '/');
            if (!path.contains("/settlement/src/main/java/")
                    && !path.contains("/reconciliation/src/main/java/")) {
                continue;
            }
            seen++;
            java.util.regex.Matcher named = anyClearing.matcher(codeOf(read(source)));
            if (named.find()) {
                outside.add(named.group() + " in " + source);
            }
        }
        assertThat(seen)
                .as("the sweep saw the settlement modules' sources - vacuity is not a pass")
                .isPositive();
        assertThat(outside).isEmpty();
    }

    @Test
    @DisplayName("the scanners reject their violations and ignore prose")
    void scannersRejectTheirViolations() {
        // A planted branch, in each family the rule claims to see - both rails.
        assertThat(stringLiteralsOf("if (rail.value().equals(\"card\")) { pay(); }").split("\n"))
                .contains("card");
        assertThat(stringLiteralsOf("if (rail.value().equals(\"instant\")) { push(); }")
                        .split("\n"))
                .contains("instant");
        assertThat(codeOf("Object rail = SimulatedCardPspAdapter.RAIL;"))
                .contains("SimulatedCardPspAdapter.RAIL");
        assertThat(codeOf("Object rail = SimulatedInstantSchemeAdapter.RAIL;"))
                .contains("SimulatedInstantSchemeAdapter.RAIL");
        assertThat(stringLiteralsOf("if (rail.value().equals(\"book\")) { post(); }")
                        .split("\n"))
                .contains("book");
        assertThat(codeOf("Object rail = BookRail.RAIL;")).contains("BookRail.RAIL");
        assertThat(codeOf("chart.resolve(uow, AccountPurpose.INSTANT_CLEARING, eur);"))
                .contains("AccountPurpose.INSTANT_CLEARING");
        assertThat(codeOf("// posts to AccountPurpose.INSTANT_CLEARING"))
                .doesNotContain("AccountPurpose.INSTANT_CLEARING");
        // Prose is not a control: comments are stripped from both scanners, literals from the
        // code scanner, and only a WHOLE literal matches a name.
        assertThat(stringLiteralsOf("// the \"card\" rail\n/* card */ String s = \"cardigan\";"))
                .doesNotContain("\ncard\n")
                .contains("cardigan");
        assertThat(codeOf("// SimulatedCardPspAdapter.RAIL\n"
                        + "/** SimulatedCardPspAdapter.RAIL */\n"
                        + "String s = \"SimulatedCardPspAdapter.RAIL\";"))
                .doesNotContain("SimulatedCardPspAdapter.RAIL");
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
