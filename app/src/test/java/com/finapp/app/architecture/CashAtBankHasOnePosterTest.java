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
 * `INV-SET-06`'s static half (`P8-TSK-016`, ADR-0065 §3): <strong>cash moves on the books only on
 * the bank's own statement</strong>. {@code CASH_AT_BANK} has exactly ONE poster — the statement's
 * recognition in the accept leg ({@code BatchAcceptance} resolving the account for
 * {@code BankRecognition}) — and one READER, the cash proof. Nothing else in production code may
 * name the purpose at all: a second resolver of the account is a second way cash could move, and
 * this rule refuses it before it compiles into a posting.
 *
 * <p>The database's rank sits beside it: {@code CASH_AT_BANK} is a reconciled position, so a
 * {@code MANUAL} adjustment line on it is refused by the domain binding and by ledger `V018`'s
 * trigger for every writer. What neither rank can see — raw SQL naming the account's id — the
 * cash proof detects: the balance stops being the chain's closing.
 *
 * <h2>What the rule scans</h2>
 *
 * <p>Every module's {@code src/main/java}, comment-stripped: the bare token {@code CASH_AT_BANK}
 * in code (qualified or statically imported — both spell it whole) and any string literal
 * carrying it (a {@code valueOf("CASH_AT_BANK")} or a SQL text). The declaring enum is exempt;
 * every other occurrence must be in a permitted file with its stated role.
 */
@DisplayName("CASH_AT_BANK has one poster (P8-TSK-016, INV-SET-06)")
class CashAtBankHasOnePosterTest {

    /** The declaration — its own members name themselves. */
    private static final String DECLARING_FILE = "AccountPurpose.java";

    /** Every permitted file and its one role — a poster, or a reader that posts nothing. */
    private static final Map<String, String> PERMITTED =
            Map.of(
                    "BatchAcceptance.java",
                    "POSTER: the bank statement's recognition resolves the account for"
                            + " BankRecognition's entry - the one way cash moves",
                    "PositionProof.java",
                    "READER: the cash proof derives the account's balance and compares it with"
                            + " the statement chain's closing; it posts nothing");

    private static final Pattern TOKEN = Pattern.compile("\\bCASH_AT_BANK\\b");

    @Test
    @DisplayName("CASH_AT_BANK is named only by its declaration, its one poster and its reader")
    void cashAtBankIsNamedOnlyByItsPosterAndItsReader() {
        Map<String, String> sources = new LinkedHashMap<>();
        for (Path source : mainSources()) {
            sources.put(source.toString(), read(source));
        }
        List<String> outside = violations(sources);
        assertThat(outside)
                .as("a second place naming CASH_AT_BANK is a second way cash could move on the"
                        + " books (INV-SET-06): only the statement's recognition posts it")
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
                .as("no permit is stale and the guard is not vacuous: the declaration, the"
                        + " poster and the reader each really name the purpose (the P1-TSK-015"
                        + " rule - an exemption naming nothing silently stops applying)")
                .allSatisfy((file, found) -> assertThat(found).as(file).isTrue());
    }

    @Test
    @DisplayName("a planted poster is caught, in each spelling the rule claims to see, and prose"
            + " is ignored")
    void aPlantedPosterIsCaught() {
        Map<String, String> planted = new LinkedHashMap<>();
        planted.put(
                "/x/merchant/src/main/java/com/finapp/merchant/RogueTopUp.java",
                "var cash = accounts.findOperational(uow, AccountPurpose.CASH_AT_BANK, eur);\n"
                        + "lines.add(new JournalLine(cash.orElseThrow().id(), Direction.DEBIT,"
                        + " amount));");
        planted.put(
                "/x/payments/src/main/java/com/finapp/payments/StaticImportPoster.java",
                "import static com.finapp.ledger.AccountPurpose.CASH_AT_BANK;\n"
                        + "chart.resolve(uow, CASH_AT_BANK, eur);");
        planted.put(
                "/x/app/src/main/java/com/finapp/app/ByName.java",
                "AccountPurpose purpose = AccountPurpose.valueOf(\"CASH_AT_BANK\");");
        planted.put(
                "/x/app/src/main/java/com/finapp/app/Prose.java",
                "// posts to AccountPurpose.CASH_AT_BANK only via the statement\n"
                        + "/** CASH_AT_BANK is hop 2's */ String s = \"cash at bank\";");
        planted.put(
                "/x/settlement/src/main/java/com/finapp/settlement/BatchAcceptance.java",
                "operational(uow, AccountPurpose.CASH_AT_BANK, batch);");

        assertThat(violations(planted))
                .as("the qualified poster, the statically imported one and the by-name one are"
                        + " all refused; prose and the permitted poster are not")
                .hasSize(3)
                .anyMatch(violation -> violation.contains("RogueTopUp.java"))
                .anyMatch(violation -> violation.contains("StaticImportPoster.java"))
                .anyMatch(violation -> violation.contains("ByName.java"))
                .noneMatch(violation -> violation.contains("Prose.java"))
                .noneMatch(violation -> violation.contains("BatchAcceptance.java"));
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
                        outside.add("CASH_AT_BANK in " + path);
                    }
                });
        return outside;
    }

    private static boolean mentions(String source) {
        return TOKEN.matcher(scan(source, false)).find()
                || scan(source, true).contains("CASH_AT_BANK");
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
