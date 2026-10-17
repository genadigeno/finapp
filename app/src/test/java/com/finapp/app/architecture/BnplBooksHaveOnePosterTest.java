package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
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
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The BNPL book has one poster (`P12-TSK-003`; ADR-0103 decision 2, PHASE_12_PLAN.md section 12.11;
 * {@code INV-BNPL-01}, {@code INV-LED-04}) - the mirror of {@code LendingBooksHaveOnePosterTest}, with its
 * scanner: every {@code BNPL_*} purpose - the six per-obligation accounts and {@code BNPL_MERCHANT_FEE_INCOME},
 * {@code BNPL_INTEREST_INCOME}, {@code BNPL_FEE_INCOME}, {@code BNPL_WRITE_OFF_EXPENSE} - is named in production
 * code only by its declaration and by the {@code bnpl} module, whose operations are the only ones that will post
 * there (through {@code PostingService} / {@code ReversalService}; the write-off expense by nothing in Phase 12).
 * Live from this task: bnpl's {@code BnplAccounts} names the six; every posting rule arrives with its own task
 * (ADR-0103 Follow-up) inside bnpl.
 *
 * <p>{@link #READERS} is the permit list for a file outside bnpl that must NAME a BNPL balance to read it - lending's
 * capital headroom (`P12-TSK-005`, the BNPL principal purposes by ledger purpose) its first entry, the merchant
 * payable's drill-down (`P12-TSK-016`) the next expected. A reader is held never to reach a
 * poster - no {@code PostingService}, {@code ReversalService} or {@code JournalLine} - so the permit cannot become
 * a second way value moves.
 *
 * <p>{@code MERCHANT_PAYABLE} gains bnpl as a poster (F1, F5 - the financing and a refund), beside merchant
 * settlement's capture and refunds and merchant's payouts; no single-poster rule binds that purpose
 * ({@code LEDGER_MODEL.md} section 11), so nothing here names it.
 *
 * <p>Read from the code with comments stripped and from the string literals; planted posters prove the rule
 * bites. The every-writer half - a raw SQL poster no Java scan can see - is bnpl's subledger and three-way
 * proofs' (`P12-TSK-025`).
 */
@DisplayName("the BNPL book has one poster (P12-TSK-003, INV-BNPL-01)")
class BnplBooksHaveOnePosterTest {

    /** The declaration - its own members name themselves. */
    private static final String DECLARING_FILE = "AccountPurpose.java";

    /** The one module whose operations post to the BNPL book. */
    private static final String BNPL_MAIN = "/bnpl/src/main/java/";

    /**
     * Every file outside {@code bnpl} permitted to name the BNPL book, READ-only, with its role. *(Empty until
     * `P12-TSK-005`.)* Lending's capital headroom is the first (ADR-0104 decision 2): it names the two BNPL principal
     * purposes to sum them by ledger purpose, and is held below never to reach a poster.
     */
    static final Map<String, String> READERS =
            Map.of(
                    "LendingCapitalHeadroom.java",
                    "READER (P12-TSK-005): sums BNPL_PRINCIPAL + BNPL_PRINCIPAL_DUE by ledger purpose beside the loan"
                            + " principal, in one statement under the capital lock (L6) - one pool, one proof; it posts"
                            + " nothing and learns no bnpl type");

    /** Every BNPL_* purpose, derived from the declaration. */
    static final List<String> BNPL_BOOK =
            Arrays.stream(AccountPurpose.values())
                    .map(AccountPurpose::name)
                    .filter(name -> name.startsWith("BNPL_"))
                    .toList();

    private static final Pattern BNPL_TOKEN = Pattern.compile("\\b(" + String.join("|", BNPL_BOOK) + ")\\b");

    @Test
    @DisplayName("the BNPL book is named only by its declaration and bnpl")
    void theBnplBookIsNamedOnlyByItsPoster() {
        assertThat(BNPL_BOOK)
                .as("not vacuous: the six per-obligation purposes and the four BNPL P&L purposes")
                .hasSize(10)
                .contains("BNPL_PRINCIPAL", "BNPL_CREDIT_BALANCE", "BNPL_MERCHANT_FEE_INCOME", "BNPL_WRITE_OFF_EXPENSE");
        Map<String, String> sources = new LinkedHashMap<>();
        for (Path source : mainSources()) {
            sources.put(normalised(source), read(source));
        }
        assertThat(violations(sources))
                .as("a second place naming a BNPL account is a second way value could move on it: only bnpl's"
                        + " operations post to the BNPL book")
                .isEmpty();

        assertThat(sources.entrySet().stream()
                        .filter(source -> fileName(source.getKey()).equals(DECLARING_FILE))
                        .anyMatch(source -> mentionsBnplBook(source.getValue())))
                .as("not vacuous: the declaration names the book")
                .isTrue();
        assertThat(sources.entrySet().stream()
                        .filter(source -> source.getKey().contains(BNPL_MAIN))
                        .anyMatch(source -> mentionsBnplBook(source.getValue())))
                .as("not vacuous: bnpl's BnplAccounts names the book")
                .isTrue();
        READERS.keySet().forEach(permitted -> assertThat(sources.entrySet().stream()
                        .filter(source -> fileName(source.getKey()).equals(permitted))
                        .anyMatch(source -> mentionsBnplBook(source.getValue())))
                .as("no reader permit is stale: %s really names the BNPL book", permitted)
                .isTrue());
        // Lending's headroom reads principal only - never the obligation's interest, fees or credit balance, nor any BNPL
        // income: income is not capital (lending A29, ADR-0104 decision 6).
        sources.forEach((path, text) -> {
            if (fileName(path).equals("LendingCapitalHeadroom.java")) {
                java.util.Set<String> named = new java.util.TreeSet<>();
                java.util.regex.Matcher token = BNPL_TOKEN.matcher(scan(text, false));
                while (token.find()) {
                    named.add(token.group(1));
                }
                assertThat(named)
                        .as("lending's headroom names exactly the two BNPL principal purposes")
                        .containsExactly("BNPL_PRINCIPAL", "BNPL_PRINCIPAL_DUE");
            }
        });
        sources.forEach((path, text) -> {
            if (READERS.containsKey(fileName(path))) {
                assertThat(scan(text, false))
                        .as("%s is a READER permit and must never reach a poster", path)
                        .doesNotContain("PostingService").doesNotContain("ReversalService").doesNotContain("JournalLine");
            }
        });
    }

    @Test
    @DisplayName("a planted poster is caught, in each spelling the rule claims to see, and prose is ignored")
    void aPlantedPosterIsCaught() {
        Map<String, String> planted = new LinkedHashMap<>();
        planted.put(
                "/x/merchant/src/main/java/com/finapp/merchant/RogueFinancing.java",
                "store.findOwned(uow, obligation, AccountPurpose.BNPL_PRINCIPAL, eur);");
        planted.put(
                "/x/app/src/main/java/com/finapp/app/StaticImportPoster.java",
                "import static com.finapp.ledger.AccountPurpose.BNPL_FEES_DUE;\nstore.findOwned(uow, o, BNPL_FEES_DUE, eur);");
        planted.put(
                "/x/lending/src/main/java/com/finapp/lending/LendingPostsBnpl.java",
                "chart.resolve(uow, AccountPurpose.valueOf(\"BNPL_INTEREST_INCOME\"), eur);");
        planted.put(
                "/x/app/src/main/java/com/finapp/app/Prose.java",
                "// posts to AccountPurpose.BNPL_PRINCIPAL only via bnpl\n/** BNPL_MERCHANT_FEE_INCOME */ int i = 0;");
        planted.put(
                "/x/bnpl/src/main/java/com/finapp/bnpl/PurchaseFinancing.java",
                "chart.resolve(uow, AccountPurpose.BNPL_MERCHANT_FEE_INCOME, eur); accounts.principal();");
        planted.put(
                "/x/app/src/main/java/com/finapp/app/NotTheBook.java",
                "AccountPurpose purpose = AccountPurpose.LOAN_PRINCIPAL; String s = \"BNPL_PRINCIPAL_X\";");
        assertThat(violations(planted))
                .hasSize(3)
                .anyMatch(violation -> violation.contains("RogueFinancing.java"))
                .anyMatch(violation -> violation.contains("StaticImportPoster.java"))
                .anyMatch(violation -> violation.contains("LendingPostsBnpl.java"))
                .noneMatch(violation -> violation.contains("Prose.java"))
                .noneMatch(violation -> violation.contains("PurchaseFinancing.java"))
                .noneMatch(violation -> violation.contains("NotTheBook.java"));
    }

    // -----------------------------------------------------------------

    private static List<String> violations(Map<String, String> sources) {
        List<String> outside = new ArrayList<>();
        sources.forEach(
                (path, text) -> {
                    String fileName = fileName(path);
                    if (DECLARING_FILE.equals(fileName)) {
                        return;
                    }
                    if (!path.contains(BNPL_MAIN) && !READERS.containsKey(fileName) && mentionsBnplBook(text)) {
                        outside.add("the BNPL book in " + path);
                    }
                });
        return outside;
    }

    private static boolean mentionsBnplBook(String source) {
        return BNPL_TOKEN.matcher(scan(source, false)).find() || BNPL_TOKEN.matcher(scan(source, true)).find();
    }

    private static String fileName(String path) {
        return Paths.get(path).getFileName().toString();
    }

    /** Forward slashes on every platform, so the module prefix reads the same on Windows. */
    private static String normalised(Path source) {
        return source.toString().replace('\\', '/');
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

    /**
     * {@code keepLiterals}: the string literals, one per line; otherwise the code with comments AND literals
     * stripped - the {@code FxBooksHaveOnePosterTest} / {@code LendingBooksHaveOnePosterTest} scanner, verbatim.
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
