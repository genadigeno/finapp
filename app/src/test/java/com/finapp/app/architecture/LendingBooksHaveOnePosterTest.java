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
 * The lending books have one poster each (`P11-TSK-003`; ADR-0096 sections 1 and 3, PHASE_11_PLAN.md
 * section 12.8; {@code INV-LND-01}, {@code INV-LND-13}, {@code INV-LED-04}) - the
 * {@code FxBooksHaveOnePosterTest} scanner over two rules:
 *
 * <ul>
 *   <li><strong>The loan book</strong> - every {@code LOAN_*} purpose: the six per-loan accounts and
 *       {@code LOAN_INTEREST_INCOME}, {@code LOAN_FEE_INCOME}, {@code LOAN_WRITE_OFF_EXPENSE} - is
 *       named in production code only by its declaration and by the {@code lending} module, whose
 *       operations are the only ones that post there (through {@code PostingService} /
 *       {@code ReversalService}). Live from this task: lending's {@code LoanAccounts} names the six.
 *   <li><strong>The lending capital</strong> - {@code LENDING_CAPITAL} - is named only by its
 *       declaration and by {@link #CAPITAL_PERMITTED}, each file with its one role. Live since
 *       `P11-TSK-004`: capital recognition ({@code BatchAcceptance}, rule 0 - bank recognition of a
 *       contribution's statement line) is its one POSTER and lending's headroom its one READER, each
 *       permit asserted to really name it; the capital proof and the expectation opener reach the
 *       account through the headroom. *(Vacuous, with an empty permit list, from `P11-TSK-003` until
 *       then.)* Lending itself holds no module-wide permit here: capital is recognised from bank
 *       evidence, never posted by lending.
 * </ul>
 *
 * <p>Read from the code with comments stripped and from the string literals; planted posters prove
 * both rules bite. The every-writer half - a raw SQL poster - is completeness's: {@code LENDING_CAPITAL}
 * is a reconciled position, and the loan book is proven by the subledger proof (`P11-TSK-029`).
 */
@DisplayName("the lending books have one poster each (P11-TSK-003, INV-LND-01, INV-LND-13)")
class LendingBooksHaveOnePosterTest {

    /** The declaration - its own members name themselves. */
    private static final String DECLARING_FILE = "AccountPurpose.java";

    /** The one module whose operations post to the loan book. */
    private static final String LENDING_MAIN = "/lending/src/main/java/";

    /**
     * Every file permitted to name {@code LENDING_CAPITAL}, with its one role (`P11-TSK-004`): bank recognition, the
     * one POSTER - the statement's acceptance resolving the account for {@code BankRecognition}'s rule 0 line - and
     * the headroom, the one READER that resolves and locks it (L6); the capital proof and the expectation opener reach
     * the account through the headroom and never name the purpose. *(Empty until `P11-TSK-004`.)*
     */
    static final Map<String, String> CAPITAL_PERMITTED =
            Map.of(
                    "BatchAcceptance.java",
                    "POSTER: the bank statement's acceptance resolves the capital account for BankRecognition's rule 0"
                            + " line - a statement credit matched to an approved contribution, the one way capital arises",
                    "LendingCapitalHeadroom.java",
                    "READER: resolves the currency's capital account, locks it FOR UPDATE (L6) and derives the headroom"
                            + " from journal lines; it posts nothing");

    /** Every LOAN_* purpose, derived from the declaration. */
    static final List<String> LOAN_BOOK =
            Arrays.stream(AccountPurpose.values())
                    .map(AccountPurpose::name)
                    .filter(name -> name.startsWith("LOAN_"))
                    .toList();

    private static final String CAPITAL = AccountPurpose.LENDING_CAPITAL.name();

    private static final Pattern LOAN_TOKEN = Pattern.compile("\\b(" + String.join("|", LOAN_BOOK) + ")\\b");

    private static final Pattern CAPITAL_TOKEN = Pattern.compile("\\b" + CAPITAL + "\\b");

    @Test
    @DisplayName("the loan book is named only by its declaration and lending; the capital only by its declaration, its"
            + " poster and its reader")
    void theLendingBooksAreNamedOnlyByTheirPosters() {
        assertThat(LOAN_BOOK)
                .as("not vacuous: the six per-loan purposes and the three loan P&L purposes")
                .hasSize(9)
                .contains("LOAN_PRINCIPAL", "LOAN_CREDIT_BALANCE", "LOAN_INTEREST_INCOME", "LOAN_WRITE_OFF_EXPENSE");
        Map<String, String> sources = new LinkedHashMap<>();
        for (Path source : mainSources()) {
            sources.put(normalised(source), read(source));
        }
        assertThat(violations(sources))
                .as("a second place naming a loan or capital account is a second way value could move on it:"
                        + " only lending's operations post to the loan book, only capital recognition to the capital")
                .isEmpty();

        assertThat(sources.entrySet().stream()
                        .filter(source -> fileName(source.getKey()).equals(DECLARING_FILE))
                        .anyMatch(source -> mentionsLoanBook(source.getValue()) && mentionsCapital(source.getValue())))
                .as("not vacuous: the declaration names both books")
                .isTrue();
        assertThat(sources.entrySet().stream()
                        .filter(source -> source.getKey().contains(LENDING_MAIN))
                        .anyMatch(source -> mentionsLoanBook(source.getValue())))
                .as("not vacuous: lending's LoanAccounts names the loan book")
                .isTrue();
        CAPITAL_PERMITTED.keySet().forEach(permitted -> assertThat(sources.entrySet().stream()
                        .filter(source -> fileName(source.getKey()).equals(permitted))
                        .anyMatch(source -> mentionsCapital(source.getValue())))
                .as("no capital permit is stale: %s really names LENDING_CAPITAL", permitted)
                .isTrue());
    }

    /**
     * `P11-TSK-029`: the rule is no longer vacuous on either half. Statically, every one of the six per-loan purposes is
     * named by a lending operation that posts to it - not only by its opener ({@code LoanAccounts}) - so the "only lending
     * names it" rule above guards live posters. And the every-writer half - a raw SQL poster no Java scan can see - is the
     * subledger proof's: it proves exactly the six per-loan purposes, each against the rows of its one poster.
     */
    @Test
    @DisplayName("not vacuous (P11-TSK-029): each per-loan purpose has a live lending poster, and the subledger proof proves"
            + " exactly the six")
    void theLoanBookHasLivePostersAndItsProof() {
        List<String> perLoan = com.finapp.lending.LoanAccounts.purposes().stream().map(AccountPurpose::name).toList();
        assertThat(perLoan).hasSize(6).allMatch(LOAN_BOOK::contains);
        assertThat(com.finapp.lending.LoanSubledgerProof.PURPOSES.stream().map(AccountPurpose::name).toList())
                .as("the subledger proof proves exactly the six per-loan accounts, the every-writer half of this rule")
                .containsExactlyElementsOf(perLoan);
        Map<String, String> lending = new LinkedHashMap<>();
        for (Path source : mainSources()) {
            String path = normalised(source);
            String name = fileName(path);
            if (path.contains(LENDING_MAIN) && !name.equals("LoanAccounts.java") && !name.equals("LoanSubledgerProof.java")) {
                lending.put(path, scan(read(source), false));
            }
        }
        perLoan.forEach(purpose -> assertThat(lending.values().stream()
                        .anyMatch(code -> Pattern.compile("\\b" + purpose + "\\b").matcher(code).find()))
                .as("%s is posted by a lending operation, not only opened", purpose)
                .isTrue());
    }

    @Test
    @DisplayName("a planted poster is caught, in each spelling the rule claims to see, and prose is ignored")
    void aPlantedPosterIsCaught() {
        Map<String, String> planted = new LinkedHashMap<>();
        planted.put(
                "/x/payments/src/main/java/com/finapp/payments/RogueLoanFee.java",
                "chart.resolve(uow, AccountPurpose.LOAN_FEE_INCOME, eur);");
        planted.put(
                "/x/app/src/main/java/com/finapp/app/StaticImportPoster.java",
                "import static com.finapp.ledger.AccountPurpose.LOAN_PRINCIPAL;\nstore.findOwned(uow, loan, LOAN_PRINCIPAL, eur);");
        planted.put(
                "/x/app/src/main/java/com/finapp/app/CapitalByName.java",
                "AccountPurpose purpose = AccountPurpose.valueOf(\"LENDING_CAPITAL\");");
        planted.put(
                "/x/lending/src/main/java/com/finapp/lending/LendingPostsCapital.java",
                "chart.resolve(uow, AccountPurpose.LENDING_CAPITAL, eur);");
        planted.put(
                "/x/app/src/main/java/com/finapp/app/Prose.java",
                "// posts to AccountPurpose.LOAN_PRINCIPAL only via lending\n/** LENDING_CAPITAL */ int i = 0;");
        planted.put(
                "/x/lending/src/main/java/com/finapp/lending/Disbursement.java",
                "chart.resolve(uow, AccountPurpose.LOAN_FEE_INCOME, eur); accounts.principal();");
        assertThat(violations(planted))
                .hasSize(4)
                .anyMatch(violation -> violation.contains("RogueLoanFee.java") && violation.contains("loan book"))
                .anyMatch(violation -> violation.contains("StaticImportPoster.java") && violation.contains("loan book"))
                .anyMatch(violation -> violation.contains("CapitalByName.java") && violation.contains("capital"))
                .anyMatch(violation -> violation.contains("LendingPostsCapital.java") && violation.contains("capital"))
                .noneMatch(violation -> violation.contains("Prose.java"))
                .noneMatch(violation -> violation.contains("Disbursement.java"));
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
                    if (!path.contains(LENDING_MAIN) && mentionsLoanBook(text)) {
                        outside.add("the loan book in " + path);
                    }
                    if (!CAPITAL_PERMITTED.containsKey(fileName) && mentionsCapital(text)) {
                        outside.add("the lending capital in " + path);
                    }
                });
        return outside;
    }

    private static boolean mentionsLoanBook(String source) {
        String literals = scan(source, true);
        return LOAN_TOKEN.matcher(scan(source, false)).find() || LOAN_TOKEN.matcher(literals).find();
    }

    private static boolean mentionsCapital(String source) {
        String literals = scan(source, true);
        return CAPITAL_TOKEN.matcher(scan(source, false)).find() || CAPITAL_TOKEN.matcher(literals).find();
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
     * {@code keepLiterals}: the string literals, one per line; otherwise the code with comments
     * AND literals stripped - the {@code FxBooksHaveOnePosterTest} scanner.
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
