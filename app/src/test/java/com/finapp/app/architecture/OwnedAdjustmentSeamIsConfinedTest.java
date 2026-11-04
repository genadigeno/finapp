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
 * The reconciliation-owned adjustment seam is reachable only from
 * {@code com.finapp.reconciliation} (`P8-TSK-006`, ADR-0071 §6): {@code proposeOwned},
 * {@code approveOwned} and {@code rejectOwned} are the break resolution's ledger half —
 * origin {@code RECONCILIATION}, decided by the flow that also moves the break — and a
 * caller anywhere else would be a door around the origin refusal the routes enforce.
 *
 * <p><strong>The accounting-owned seam</strong> (`P14-TSK-013`, ADR-0113 decision 4): {@code proposePriorPeriod},
 * {@code approvePriorPeriod} and {@code rejectPriorPeriod} are a prior-period adjustment's ledger half, and exactly one class
 * may name them, {@code app}'s {@code LedgerPriorPeriodPostings} - a file, not a package. <strong>Each owner is held to its own
 * door</strong>: reconciliation naming the accounting tokens is refused, and so is that class naming reconciliation's.
 *
 * <p>The {@code RailVocabularyIsConfinedTest} shape: a token scan over every module's
 * {@code src/main/java}, comments and string literals stripped, so prose never reads as a
 * control. The declaring file ({@code AdjustmentService.java}) and reconciliation's own
 * sources are the only legal homes. <strong>Zero callers today is legal</strong> — the
 * caller arrives with `P8-TSK-015` — which is why the declaring file is asserted as the
 * non-vacuity control instead of a caller count.
 *
 * <p>Stated limit: a reflective or string-assembled call is invisible to the scan; the
 * origin refusal in the service ({@code AdjustmentOriginMismatchException}, tested at both
 * doors) is the runtime layer beneath.
 */
@DisplayName("the owned adjustment seam is confined to reconciliation (P8-TSK-006)")
class OwnedAdjustmentSeamIsConfinedTest {

    private static final Set<String> OWNED_TOKENS =
            Set.of("proposeOwned(", "approveOwned(", "rejectOwned(");

    /** The accounting-owned door's own names (`P14-TSK-013`). */
    private static final Set<String> PRIOR_PERIOD_TOKENS =
            Set.of("proposePriorPeriod(", "approvePriorPeriod(", "rejectPriorPeriod(");

    /** The one class that may name them: a path, not a package. */
    private static final String PRIOR_PERIOD_HOME =
            "/app/src/main/java/com/finapp/app/accounting/LedgerPriorPeriodPostings.java";

    /**
     * The declaring homes: the service seam itself, and the aggregate factory it calls
     * ({@code AdjustmentProposal.proposeOwned} shares the name by design — one vocabulary
     * for one act). Both live in the ledger; nothing else outside reconciliation may name
     * a token.
     */
    private static final Set<String> DECLARING_FILES =
            Set.of("AdjustmentService.java", "AdjustmentProposal.java");

    @Test
    @DisplayName("the owned methods are named only by their declaration and their one owner: reconciliation's by reconciliation, accounting's by one class of app")
    void theOwnedMethodsAreConfined() {
        List<String> outside = new ArrayList<>();
        List<String> named = new ArrayList<>();
        for (Path source : mainSources()) {
            String path = source.toString().replace('\\', '/');
            String code = codeOf(read(source));
            outside.addAll(violations(path, code));
            for (String token : OWNED_TOKENS) {
                if (code.contains(token) && isDeclaration(path)) {
                    named.add("declared " + token);
                }
            }
            for (String token : PRIOR_PERIOD_TOKENS) {
                if (code.contains(token) && isDeclaration(path)) {
                    named.add("declared " + token);
                }
                if (code.contains(token) && path.endsWith(PRIOR_PERIOD_HOME)) {
                    named.add("called " + token);
                }
            }
        }
        assertThat(outside)
                .as("a caller of an owned adjustment seam outside its one owner is a door around the origin refusal"
                        + " (ADR-0071 section 6)")
                .isEmpty();
        assertThat(named)
                .as("the guard is not vacuous: each declaration was seen, and the one class calling each of its door's methods")
                .contains("declared proposeOwned(", "declared approveOwned(", "declared rejectOwned(",
                        "declared proposePriorPeriod(", "declared approvePriorPeriod(", "declared rejectPriorPeriod(",
                        "called proposePriorPeriod(", "called approvePriorPeriod(", "called rejectPriorPeriod(");
    }

    /** Each token this source names that its path may not. */
    private static List<String> violations(String path, String code) {
        List<String> found = new ArrayList<>();
        if (isDeclaration(path)) {
            return found;
        }
        for (String token : OWNED_TOKENS) {
            if (code.contains(token) && !path.contains("/reconciliation/src/main/java/")) {
                found.add(token + " in " + path);
            }
        }
        for (String token : PRIOR_PERIOD_TOKENS) {
            if (code.contains(token) && !path.endsWith(PRIOR_PERIOD_HOME)) {
                found.add(token + " in " + path);
            }
        }
        return found;
    }

    private static boolean isDeclaration(String path) {
        return path.contains("/ledger/src/main/java/") && DECLARING_FILES.contains(path.substring(path.lastIndexOf('/') + 1));
    }

    @Test
    @DisplayName("each owner is held to its own door, and the home is one class, not a package: reconciliation naming accounting's, the home naming reconciliation's, its neighbour naming its own - each refused")
    void eachOwnerIsHeldToItsOwnDoor() {
        String reconciliation = "/repo/reconciliation/src/main/java/com/finapp/reconciliation/ResolutionMachine.java";
        String neighbour = "/repo/app/src/main/java/com/finapp/app/accounting/PriorPeriodAdjustmentDesk.java";
        String home = "/repo" + PRIOR_PERIOD_HOME;

        assertThat(violations(reconciliation, "adjustments.approvePriorPeriod(uow, id);")).singleElement().asString().contains("approvePriorPeriod(");
        assertThat(violations(reconciliation, "adjustments.proposePriorPeriod(uow, command);")).hasSize(1);
        assertThat(violations(home, "adjustments.approveOwned(uow, id);")).singleElement().asString().contains("approveOwned(");
        assertThat(violations(neighbour, "adjustments.approvePriorPeriod(uow, id);")).as("a package is no home").hasSize(1);
        assertThat(violations(neighbour, "adjustments.rejectOwned(uow, id);")).hasSize(1);

        assertThat(violations(reconciliation, "adjustments.approveOwned(uow, id);")).isEmpty();
        assertThat(violations(home, "adjustments.approvePriorPeriod(uow, id);")).isEmpty();
        assertThat(violations("/repo/ledger/src/main/java/com/finapp/ledger/AdjustmentService.java", "public PostingResult approvePriorPeriod(Connection uow, Id id) {}")).isEmpty();
    }

    @Test
    @DisplayName("the scanner rejects a planted caller and ignores prose")
    void theScannerRejectsItsViolation() {
        // A planted caller, in each token's shape - what a bypass must write.
        assertThat(codeOf("service.approveOwned(uow, id);")).contains("approveOwned(");
        assertThat(codeOf("var id = adjustments.proposeOwned(uow, command);"))
                .contains("proposeOwned(");
        assertThat(codeOf("adjustments.rejectOwned(uow, id);")).contains("rejectOwned(");
        // Prose is not a control: comments and string literals are stripped.
        assertThat(codeOf("// call approveOwned( here\n/* proposeOwned( */"
                        + " String s = \"rejectOwned(\";"))
                .doesNotContain("approveOwned(")
                .doesNotContain("proposeOwned(")
                .doesNotContain("rejectOwned(");
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

    /** Comments, string and char literals stripped — the `RailVocabularyIsConfinedTest` scanner. */
    private static String codeOf(String source) {
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
                i = end < 0 ? length : end + 3;
            } else if (c == '"') {
                int j = i + 1;
                while (j < length && source.charAt(j) != '"') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                i = j + 1;
            } else if (c == '\'') {
                int j = i + 1;
                while (j < length && source.charAt(j) != '\'') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                i = j + 1;
            } else {
                kept.append(c);
                i++;
            }
        }
        return kept.toString();
    }
}
