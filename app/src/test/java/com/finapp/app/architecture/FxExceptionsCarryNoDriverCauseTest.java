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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * No fx exception carries the driver's exception (the Phase 9 to 10 transition; {@code DatabaseFailure}'s rule,
 * {@code INV-AUD-02}).
 *
 * <h2>Why</h2>
 *
 * <p>A {@code SQLException} refused by a {@code CHECK} carries PostgreSQL's {@code DETAIL} line listing every column
 * of the refused row, and every fx table holds a column classified above {@code INTERNAL} (amounts, rates, provider
 * references, operator reasons - DATA_CLASSIFICATION.md). The fx stores attached the driver's exception as their
 * failure's cause, so a logged storage failure logged the row. The module's one rule now: an fx exception built from
 * a {@code DatabaseFailure} description takes that description ALONE - {@code FxStorageException} has no constructor
 * taking a cause - and no fx exception is given one later ({@code initCause}).
 *
 * <p>Read from fx's production code with comments and string literals stripped (the
 * {@code FxBooksHaveOnePosterTest} scanner): every {@code new X(...)} whose arguments hold a
 * {@code DatabaseFailure.describe(...)}, and every {@code new FxStorageException(...)}, must have exactly one
 * top-level argument. Planted violations prove the rule bites, in each spelling it claims to see.
 */
@Tag("architecture")
@DisplayName("no fx exception carries the driver's exception (the Phase 9 to 10 transition, INV-AUD-02)")
class FxExceptionsCarryNoDriverCauseTest {

    private static final String FX_MAIN = "/fx/src/main/java/";

    private static final Pattern CONSTRUCTION = Pattern.compile("\\bnew\\s+([A-Z][A-Za-z0-9_.]*)\\s*\\(");

    private static final Pattern INIT_CAUSE = Pattern.compile("\\.initCause\\s*\\(");

    @Test
    @DisplayName("every fx exception built from a DatabaseFailure description takes that description alone")
    void fxExceptionsTakeTheDescriptionAlone() {
        Map<String, String> sources = new LinkedHashMap<>();
        for (Path source : fxMainSources()) {
            sources.put(source.toString().replace('\\', '/'), read(source));
        }
        assertThat(violations(sources))
                .as("an fx exception carrying a SQLException carries PostgreSQL's DETAIL - the refused row, whose"
                        + " columns are classified above INTERNAL; describe the failure, never attach it")
                .isEmpty();
        long described = sources.values().stream()
                .mapToLong(text -> countOf(code(text), "DatabaseFailure.describe("))
                .sum();
        assertThat(described)
                .as("the rule read real failure sites - every fx store describes its failures")
                .isGreaterThanOrEqualTo(30);
    }

    @Test
    @DisplayName("a planted cause is caught in each spelling - a store's FxStorageException, another exception over a"
            + " description, a multi-line construction, a late initCause - and prose and other modules are ignored")
    void aPlantedCauseIsCaught() {
        Map<String, String> planted = new LinkedHashMap<>();
        planted.put("/x/fx/src/main/java/com/finapp/fx/JdbcRogueStore.java",
                "} catch (SQLException failure) {\n"
                        + "    throw new FxStorageException(DatabaseFailure.describe(\"reading a row\", failure), failure);\n}");
        planted.put("/x/fx/src/main/java/com/finapp/fx/JdbcOtherStore.java",
                "throw new IllegalStateException(DatabaseFailure.describe(\"reading a cover by its reference\", failure),"
                        + " failure);");
        planted.put("/x/fx/src/main/java/com/finapp/fx/JdbcMultiLineStore.java",
                "throw new FxStorageException(\n        DatabaseFailure.describe(\"x\", failure),\n        failure);");
        planted.put("/x/fx/src/main/java/com/finapp/fx/JdbcLateStore.java",
                "FxStorageException loud = new FxStorageException(DatabaseFailure.describe(\"x\", failure));\n"
                        + "loud.initCause(failure);\nthrow loud;");
        planted.put("/x/fx/src/main/java/com/finapp/fx/JdbcProseStore.java",
                "// never: new FxStorageException(DatabaseFailure.describe(\"x\", failure), failure)\n"
                        + "throw new FxStorageException(DatabaseFailure.describe(\"x, with a comma\", failure));\n"
                        + "String s = \"new FxStorageException(a, b)\";");
        planted.put("/x/payments/src/main/java/com/finapp/payments/JdbcElsewhere.java",
                "throw new IllegalStateException(DatabaseFailure.describe(\"x\", failure), failure);");
        assertThat(violations(planted))
                .hasSize(4)
                .anyMatch(violation -> violation.contains("JdbcRogueStore.java"))
                .anyMatch(violation -> violation.contains("JdbcOtherStore.java"))
                .anyMatch(violation -> violation.contains("JdbcMultiLineStore.java"))
                .anyMatch(violation -> violation.contains("JdbcLateStore.java"))
                .noneMatch(violation -> violation.contains("JdbcProseStore.java"))
                .noneMatch(violation -> violation.contains("JdbcElsewhere.java"));
    }

    // -----------------------------------------------------------------

    static List<String> violations(Map<String, String> sources) {
        List<String> found = new ArrayList<>();
        sources.forEach((path, text) -> {
            if (!path.replace('\\', '/').contains(FX_MAIN)) {
                return;
            }
            String code = code(text);
            Matcher construction = CONSTRUCTION.matcher(code);
            while (construction.find()) {
                String type = construction.group(1);
                String arguments = argumentsFrom(code, construction.end());
                boolean describes = arguments.contains("DatabaseFailure.describe(");
                boolean storage = type.endsWith("FxStorageException");
                if ((describes || storage) && topLevelArguments(arguments) > 1) {
                    found.add("new " + type + "(...) with a cause in " + path);
                }
            }
            if (INIT_CAUSE.matcher(code).find()) {
                found.add("initCause in " + path);
            }
        });
        return found;
    }

    /** The text between the construction's opening parenthesis and its matching close. */
    private static String argumentsFrom(String code, int afterOpen) {
        int depth = 1;
        int i = afterOpen;
        while (i < code.length() && depth > 0) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            i++;
        }
        return code.substring(afterOpen, Math.max(afterOpen, i - 1));
    }

    private static int topLevelArguments(String arguments) {
        if (arguments.isBlank()) {
            return 0;
        }
        int depth = 0;
        int count = 1;
        for (char c : arguments.toCharArray()) {
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                count++;
            }
        }
        return count;
    }

    private static long countOf(String text, String needle) {
        long count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    private static List<Path> fxMainSources() {
        Path main = repositoryRoot().resolve("fx/src/main/java");
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            files.filter(file -> file.toString().endsWith(".java")).forEach(sources::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(sources).as("the scan must see the fx module").hasSizeGreaterThan(50);
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

    /** The code with comments stripped and every string or character literal emptied - its commas never counted. */
    private static String code(String source) {
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
                kept.append("\"\"");
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < length && source.charAt(j) != c) {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                kept.append(c).append(c);
                i = j + 1;
            } else {
                kept.append(c);
                i++;
            }
        }
        return kept.toString();
    }
}
