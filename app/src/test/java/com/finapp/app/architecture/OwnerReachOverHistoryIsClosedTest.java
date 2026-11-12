package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Two reaches the owner of a schema, or a role with a path of its own, must not have over financial history
 * ({@code P15-TSK-004}, the two C15 debt rows; {@code THREAT_MODEL.md} T13, T33), held over <em>every</em> module's migrations,
 * read in chain order, by what each statement says rather than by a list of files.
 *
 * <ul>
 *   <li><strong>A table that refuses {@code DELETE} by trigger also refuses {@code TRUNCATE}</strong>: a row trigger does not
 *       fire on {@code TRUNCATE}, so without a statement-level trigger of its own such a table can be emptied by its owner
 *       in one statement.
 *   <li><strong>No function reads a clock function unqualified with a mutable {@code search_path}</strong>: PostgreSQL searches
 *       {@code pg_catalog} first only when the path does not name it, so a session whose path puts another schema first
 *       resolves {@code statement_timestamp()} to that schema's. Every function counts, not only trigger functions: a trigger
 *       that calls a helper reading the clock is reading the clock. {@code CURRENT_TIMESTAMP} and its like are grammar and are
 *       never resolved through a path.
 * </ul>
 *
 * <p>What it finds is <strong>fixed here or named with an owner</strong>, and the lists below are equalities, not
 * allowances: a table fixed later must leave its list, or this goes red. Since {@code P15-TSK-040} (one forward migration per
 * schema, the journal first) both lists are empty: no table and no function is left, and {@link #everyReplacedBodyDiffersFromItsLatestPredecessorOnlyInItsClockCalls}
 * holds what those migrations did. Text, not behaviour: that the triggers fire and that the clock is read is
 * {@code infra/scripts/migrator-verify.sh} on a real database, for the schemas it walks, and the database cases owed to {@code P15-TST-002}.
 */
@Tag("architecture")
@DisplayName("the migrations close the owner's reach over financial history, or name who will (P15-TSK-004, P15-TSK-040)")
class OwnerReachOverHistoryIsClosedTest {

    /** Every table that refuses DELETE by trigger and not TRUNCATE, by schema: empty since {@code P15-TSK-040}. A new one needs a refusal, or an owner here. */
    private static final Map<String, String> TRUNCATE_NOT_YET_REFUSED = Map.of();

    /** Every function that reads a clock through the path, by schema: empty since {@code P15-TSK-040}. A new one needs {@code pg_catalog.}, or an owner here. */
    private static final Map<String, String> CLOCK_NOT_YET_QUALIFIED = Map.of();

    /** The forward migrations that closed both lists ({@code P15-TSK-040}): one per schema, the version each took. */
    private static final Map<String, Integer> CLOSING_MIGRATIONS = Map.ofEntries(
            Map.entry("accounting", 16), Map.entry("bnpl", 22), Map.entry("checkout", 5), Map.entry("credit", 24),
            Map.entry("crossborder", 7), Map.entry("fx", 12), Map.entry("kyc", 13), Map.entry("ledger", 35),
            Map.entry("merchant", 15), Map.entry("payments", 39), Map.entry("risk", 21), Map.entry("settlement", 20));

    /** How many functions each closing migration qualified: the guard's list, by schema, when it was emptied. */
    private static final Map<String, Integer> QUALIFIED_BY_THE_CLOSING_MIGRATIONS = Map.ofEntries(
            Map.entry("accounting", 18), Map.entry("bnpl", 33), Map.entry("checkout", 1), Map.entry("credit", 16),
            Map.entry("crossborder", 1), Map.entry("fx", 8), Map.entry("ledger", 1), Map.entry("merchant", 3),
            Map.entry("payments", 7), Map.entry("risk", 19), Map.entry("settlement", 1));

    /** How many tables each closing migration closed: the guard's list, by schema, when it was emptied. */
    private static final Map<String, Integer> TRUNCATE_CLOSED_BY_THE_CLOSING_MIGRATIONS = Map.ofEntries(
            Map.entry("checkout", 1), Map.entry("crossborder", 8), Map.entry("fx", 19), Map.entry("kyc", 1),
            Map.entry("ledger", 4), Map.entry("merchant", 6), Map.entry("payments", 11), Map.entry("risk", 1));

    /** The clock functions resolved through a path. {@code CURRENT_TIMESTAMP} and its like are keywords, so are absent by design. */
    private static final Set<String> CLOCK_FUNCTIONS =
            Set.of("now", "statement_timestamp", "clock_timestamp", "transaction_timestamp", "timeofday");

    /** The qualifier a closing migration writes, exactly, before a clock call. */
    private static final String QUALIFIER = "pg_catalog.";

    private static final Pattern VERSIONED = Pattern.compile("V(\\d+)__.*\\.sql");
    private static final Pattern CREATE_TRIGGER = Pattern.compile("(?is)^CREATE\\s+(?:CONSTRAINT\\s+)?TRIGGER\\s+(\\w+)\\s+"
            + "(BEFORE|AFTER|INSTEAD\\s+OF)\\s+(.*?)\\s+ON\\s+([a-z_]+\\.\\w+)\\b(.*)$");
    private static final Pattern EXECUTES = Pattern.compile("(?is)EXECUTE\\s+(?:FUNCTION|PROCEDURE)\\s+([a-z_]+\\.\\w+)");
    private static final Pattern DROP_TRIGGER =
            Pattern.compile("(?is)^DROP\\s+TRIGGER\\s+(?:IF\\s+EXISTS\\s+)?(\\w+)\\s+ON\\s+([a-z_]+\\.\\w+)");
    private static final Pattern CREATE_TABLE = Pattern.compile("(?is)^CREATE\\s+(?:UNLOGGED\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z_]+\\.\\w+)");
    private static final Pattern DROP_TABLE = Pattern.compile("(?is)^DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?([a-z_]+\\.\\w+)");
    private static final Pattern CREATE_FUNCTION =
            Pattern.compile("(?is)^CREATE\\s+(?:OR\\s+REPLACE\\s+)?FUNCTION\\s+([a-z_]+\\.\\w+)\\s*\\(");
    private static final Pattern DROP_FUNCTION = Pattern.compile("(?is)^DROP\\s+FUNCTION\\s+(?:IF\\s+EXISTS\\s+)?([a-z_]+\\.\\w+)");
    private static final Pattern SET_SEARCH_PATH =
            Pattern.compile("(?is)\\bSET\\s+search_path\\s*(?:=|TO)\\s*([^;]*?)(?=\\s+(?:AS|LANGUAGE|SECURITY|STABLE|VOLATILE|IMMUTABLE)\\b|\\s*$)");

    /** The chain, as it stands after its last migration: every trigger on every table, and the latest of every function. */
    private static final Map<String, Trigger> TRIGGERS = new TreeMap<>();

    private static final Map<String, String> FUNCTIONS = new TreeMap<>();
    private static final Set<String> TABLES = new TreeSet<>();
    /** Every definition of every function, in chain order: a replaced body is compared with the one before it. */
    private static final Map<String, List<Definition>> DEFINITIONS = new TreeMap<>();
    /** Each migration's statements as the walk read them, by {@code module/Vn}. */
    private static final Map<String, List<String>> STATEMENTS_BY_MIGRATION = new TreeMap<>();
    private static int modulesRead;

    record Trigger(String table, String timing, String events, String function) {}

    record Definition(String migration, String statement) {}

    @BeforeAll
    static void readEveryMigrationInChainOrder() throws IOException {
        Path root = EvidenceCitations.repositoryRoot();
        List<Path> modules;
        try (Stream<Path> children = Files.list(root)) {
            modules = children.filter(module -> Files.isDirectory(migrations(module))).sorted().toList();
        }
        modulesRead = modules.size();
        for (Path module : modules) {
            List<Path> scripts;
            try (Stream<Path> files = Files.list(migrations(module))) {
                scripts = files.filter(file -> VERSIONED.matcher(file.getFileName().toString()).matches())
                        .sorted(Comparator.comparingInt(OwnerReachOverHistoryIsClosedTest::version))
                        .toList();
            }
            for (Path script : scripts) {
                readStatements(module.getFileName() + "/V" + version(script), EvidenceCitations.read(script));
            }
        }
    }

    private static Path migrations(Path module) {
        return module.resolve("src/main/resources/db/migration").resolve(module.getFileName());
    }

    private static int version(Path script) {
        Matcher matcher = VERSIONED.matcher(script.getFileName().toString());
        if (!matcher.matches()) {
            throw new UncheckedIOException(new IOException("Not a versioned migration: " + script));
        }
        return Integer.parseInt(matcher.group(1));
    }

    @Test
    @DisplayName("the walk read the whole chain: every module with migrations, the tables and triggers the recount found")
    void theGuardReadTheChain() {
        assertThat(modulesRead).as("the modules with migrations").isGreaterThanOrEqualTo(21);
        assertThat(TABLES).as("reconciliation's 34 tables and settlement's 13 at the least").hasSizeGreaterThanOrEqualTo(331);
        // 34 since reconciliation V025 (P16-TSK-017): rematch_fault, an operational record updated in place, refusing no DELETE by
        // trigger and granting the application none, so it adds no table to the TRUNCATE rule below. The recount was 33 before.
        assertThat(TABLES.stream().filter(table -> table.startsWith("reconciliation."))).hasSize(34);
        assertThat(TABLES.stream().filter(table -> table.startsWith("settlement."))).hasSize(13);
        assertThat(refusing("DELETE")).as("the row triggers refusing DELETE").hasSizeGreaterThanOrEqualTo(259);
        assertThat(FUNCTIONS).as("every function's latest definition").hasSizeGreaterThanOrEqualTo(400);
        assertThat(FUNCTIONS.keySet().stream().filter(function -> function.startsWith("lending.") && reachesTheClock(function)))
                .as("lending's functions that read a clock were found, so a green rule below is not a parse that saw none")
                .hasSizeGreaterThanOrEqualTo(39);
        QUALIFIED_BY_THE_CLOSING_MIGRATIONS.forEach((schema, count) -> assertThat(FUNCTIONS.keySet().stream()
                        .filter(function -> function.startsWith(schema + ".") && reachesTheClock(function)))
                .as("%s's functions that read a clock were found, so a green rule below is not a parse that saw none", schema)
                .hasSizeGreaterThanOrEqualTo(count));
        assertThat(refusing("TRUNCATE")).as("the 212 that already refused, and the 51 closed").hasSizeGreaterThanOrEqualTo(263);
    }

    @Test
    @DisplayName("every table that refuses DELETE by trigger refuses TRUNCATE too, or is named with its owner")
    void everyTableThatRefusesDeleteRefusesTruncate() {
        Set<String> refusesTruncate = refusing("TRUNCATE");
        Map<String, Set<String>> found = new TreeMap<>();
        for (String table : refusing("DELETE")) {
            if (!refusesTruncate.contains(table)) {
                found.computeIfAbsent(schemaOf(table), schema -> new TreeSet<>()).add(nameOf(table));
            }
        }
        assertThat(found)
                .as("a statement-level BEFORE TRUNCATE trigger in a forward migration, or a name in this list with an owner; a"
                        + " table already fixed must leave the list")
                .isEqualTo(expected(TRUNCATE_NOT_YET_REFUSED));
    }

    @Test
    @DisplayName("no function reads a clock function unqualified with a mutable search_path, or it is named with its owner")
    void noFunctionReadsTheClockThroughTheSearchPath() {
        Map<String, Set<String>> found = new TreeMap<>();
        FUNCTIONS.forEach((function, statement) -> {
            if (!hasAPinnedPath(statement) && !clockCalls(statement).stream().allMatch(call -> "pg_catalog".equals(call))) {
                found.computeIfAbsent(schemaOf(function), schema -> new TreeSet<>()).add(nameOf(function));
            }
        });
        assertThat(found)
                .as("every clock call pg_catalog-qualified or the function's search_path fixed, or a name in this list with"
                        + " an owner; a function already fixed must leave the list")
                .isEqualTo(expected(CLOCK_NOT_YET_QUALIFIED));
    }

    @Test
    @DisplayName("every function the closing migrations replace differs from its latest predecessor only in its clock calls, and they say nothing else")
    void everyReplacedBodyDiffersFromItsLatestPredecessorOnlyInItsClockCalls() {
        Map<String, Integer> qualified = new TreeMap<>();
        Map<String, Integer> closed = new TreeMap<>();
        CLOSING_MIGRATIONS.forEach((module, version) -> {
            String migration = module + "/V" + version;
            assertThat(STATEMENTS_BY_MIGRATION).as("the closing migration %s is in the chain", migration).containsKey(migration);
            for (String statement : STATEMENTS_BY_MIGRATION.get(migration)) {
                Matcher function = CREATE_FUNCTION.matcher(statement);
                Matcher trigger = CREATE_TRIGGER.matcher(statement);
                if (function.find()) {
                    if (replaced(function.group(1).toLowerCase(Locale.ROOT), migration, statement)) {
                        qualified.merge(module, 1, Integer::sum);
                    }
                } else if (trigger.find()) {
                    assertThat(trigger.group(2).toUpperCase(Locale.ROOT) + " " + trigger.group(3).trim().toUpperCase(Locale.ROOT))
                            .as("%s: a trigger here refuses TRUNCATE and nothing else: %s", migration, statement)
                            .isEqualTo("BEFORE TRUNCATE");
                    assertThat(trigger.group(4).toLowerCase(Locale.ROOT)).startsWith(module + ".");
                    assertThat(trigger.group(5).toUpperCase(Locale.ROOT)).contains("FOR EACH STATEMENT");
                    closed.merge(module, 1, Integer::sum);
                } else {
                    assertThat(statement.toUpperCase(Locale.ROOT))
                            .as("%s says nothing but its functions, its triggers and their comments: %s", migration, statement)
                            .startsWith("COMMENT ON FUNCTION");
                }
            }
        });
        assertThat(qualified).as("the functions each closing migration qualified: 108, as the guard named them").isEqualTo(QUALIFIED_BY_THE_CLOSING_MIGRATIONS);
        assertThat(closed).as("the tables each closing migration closed: 51, as the guard named them").isEqualTo(TRUNCATE_CLOSED_BY_THE_CLOSING_MIGRATIONS);
    }

    /** True when the statement replaces an earlier definition; false for a new function, which may only be the refusal. */
    private static boolean replaced(String function, String migration, String statement) {
        List<Definition> definitions = DEFINITIONS.get(function);
        int position = -1;
        for (int index = 0; index < definitions.size(); index++) {
            if (definitions.get(index).migration().equals(migration)) {
                position = index;
                break;
            }
        }
        assertThat(position).as("%s defined in %s", function, migration).isGreaterThanOrEqualTo(0);
        if (position == 0) {
            assertThat(function).as("a function new in %s can only be the TRUNCATE refusal", migration).endsWith(".history_is_never_truncated");
            assertThat(clockCalls(statement)).as(function).isEmpty();
            return false;
        }
        assertThat(clockCalls(statement)).as("%s: at least one clock call, and each one qualified", function)
                .isNotEmpty().containsOnly("pg_catalog");
        assertThat(withoutClockQualifiers(statement))
                .as("%s in %s is its latest predecessor byte for byte, but for pg_catalog. before each clock call", function, migration)
                .isEqualTo(asReplacement(definitions.get(position - 1).statement()));
        return true;
    }

    /** A first definition was {@code CREATE FUNCTION}; the replacement that keeps it must say {@code OR REPLACE}, and that is the only other difference. */
    private static String asReplacement(String statement) {
        return statement.replaceFirst("(?i)^CREATE\\s+FUNCTION\\b", "CREATE OR REPLACE FUNCTION");
    }

    /** The statement with {@code pg_catalog.} taken from before each clock call in the body's code, and only there. */
    private static String withoutClockQualifiers(String statement) {
        int[] body = bodyOf(statement);
        assertThat(body).as("a function with a body").isNotNull();
        String code = statement.substring(body[0], body[1]);
        StringBuilder result = new StringBuilder(statement.substring(0, body[0]));
        int copied = 0;
        int at = 0;
        while (at < code.length()) {
            char character = code.charAt(at);
            if (code.startsWith("--", at)) {
                int end = code.indexOf('\n', at);
                at = end < 0 ? code.length() : end;
            } else if (code.startsWith("/*", at)) {
                at = code.indexOf("*/", at) + 2;
            } else if (character == '\'') {
                at = afterString(code, at);
            } else if (character == '$' && dollarQuote(code, at) != null) {
                at = dollarQuote(code, at)[1];
            } else if (Character.isLetter(character) || character == '_') {
                int end = at;
                while (end < code.length() && (Character.isLetterOrDigit(code.charAt(end)) || code.charAt(end) == '_')) {
                    end++;
                }
                if (CLOCK_FUNCTIONS.contains(code.substring(at, end).toLowerCase(Locale.ROOT)) && nextIsParenthesis(code, end)
                        && at >= QUALIFIER.length() && code.startsWith(QUALIFIER, at - QUALIFIER.length())) {
                    result.append(code, copied, at - QUALIFIER.length());
                    copied = at;
                }
                at = end;
            } else {
                at++;
            }
        }
        return result.append(code, copied, code.length()).append(statement, body[1], statement.length()).toString();
    }

    @Test
    @DisplayName("the rule reads code: a clock in a comment or a string is not a call, and a pinned path or another schema is told apart")
    void theRuleReadsCodeNotText() {
        assertThat(clockCalls(function("$$ BEGIN -- statement_timestamp()\n /* now() */ RETURN NEW; END; $$"))).isEmpty();
        assertThat(clockCalls(function("$$ BEGIN RAISE EXCEPTION 'read clock_timestamp() late'; RETURN NEW; END; $$"))).isEmpty();
        assertThat(clockCalls(function("$$ BEGIN NEW.at := statement_timestamp(); RETURN NEW; END; $$"))).containsExactly("");
        assertThat(clockCalls(function("$$ BEGIN NEW.at := NOW ( ); RETURN NEW; END; $$"))).containsExactly("");
        assertThat(clockCalls(function("$$ BEGIN NEW.at := pg_catalog.now(); RETURN NEW; END; $$"))).containsExactly("pg_catalog");
        assertThat(clockCalls(function("$$ BEGIN NEW.at := evil.now(); RETURN NEW; END; $$"))).containsExactly("evil");
        assertThat(clockCalls(function("$fn$ SELECT LEAST(now(), pg_catalog.now()) $fn$"))).containsExactly("", "pg_catalog");
        assertThat(clockCalls(function("$$ BEGIN NEW.nowhere := now_or_never(); RETURN NEW; END; $$"))).isEmpty();
        assertThat(hasAPinnedPath("CREATE FUNCTION a.b() RETURNS int LANGUAGE sql SET search_path = pg_catalog, pg_temp AS $$ SELECT 1 $$"))
                .isTrue();
        assertThat(hasAPinnedPath("CREATE FUNCTION a.b() RETURNS int SET search_path = a AS $$ SELECT 1 $$"))
                .as("pg_catalog not named is searched first")
                .isTrue();
        assertThat(hasAPinnedPath("CREATE FUNCTION a.b() RETURNS int SET search_path = evil, pg_catalog AS $$ SELECT 1 $$"))
                .as("a schema named before pg_catalog is searched before it")
                .isFalse();
        assertThat(hasAPinnedPath("CREATE FUNCTION a.b() RETURNS int AS $$ SET search_path = pg_catalog; SELECT 1 $$"))
                .as("inside the body it is no pin")
                .isFalse();
    }

    private static String function(String body) {
        return "CREATE FUNCTION a.b() RETURNS trigger LANGUAGE plpgsql AS " + body;
    }

    private static boolean reachesTheClock(String function) {
        return !clockCalls(FUNCTIONS.get(function)).isEmpty();
    }

    /** A trigger counts as a refusal when it runs BEFORE the operation and its function can raise: naming one proves nothing. */
    private static Set<String> refusing(String operation) {
        Set<String> tables = new TreeSet<>();
        for (Trigger trigger : TRIGGERS.values()) {
            if (trigger.timing().equals("BEFORE")
                    && List.of(trigger.events().split("\\s+OR\\s+")).contains(operation)
                    && FUNCTIONS.getOrDefault(trigger.function(), "").toUpperCase(Locale.ROOT).contains("RAISE EXCEPTION")) {
                tables.add(trigger.table());
            }
        }
        return tables;
    }

    private static Map<String, Set<String>> expected(Map<String, String> named) {
        Map<String, Set<String>> expected = new TreeMap<>();
        named.forEach((schema, names) -> expected.put(schema, new TreeSet<>(List.of(names.split("\\s+")))));
        return expected;
    }

    private static String schemaOf(String qualified) {
        return qualified.substring(0, qualified.indexOf('.'));
    }

    private static String nameOf(String qualified) {
        return qualified.substring(qualified.indexOf('.') + 1);
    }

    private static void readStatements(String migration, String sql) {
        List<String> statements = statementsOf(sql);
        STATEMENTS_BY_MIGRATION.put(migration, statements);
        for (String statement : statements) {
            Matcher matcher = DROP_TRIGGER.matcher(statement);
            if (matcher.find()) {
                TRIGGERS.remove(matcher.group(2).toLowerCase(Locale.ROOT) + "#" + matcher.group(1).toLowerCase(Locale.ROOT));
                continue;
            }
            matcher = CREATE_TRIGGER.matcher(statement);
            if (matcher.find()) {
                String table = matcher.group(4).toLowerCase(Locale.ROOT);
                // Words only, UPDATE's column list dropped, so "UPDATE OF a, b OR DELETE" reads as its two events.
                String events = matcher.group(3).toUpperCase(Locale.ROOT).replaceAll("UPDATE\\s+OF\\s+[^\\s,]+(\\s*,\\s*[^\\s,]+)*", "UPDATE");
                Matcher executes = EXECUTES.matcher(matcher.group(5));
                if (!executes.find()) {
                    throw new IllegalStateException("A trigger whose function this could not read: " + statement);
                }
                TRIGGERS.put(table + "#" + matcher.group(1).toLowerCase(Locale.ROOT),
                        new Trigger(table, matcher.group(2).toUpperCase(Locale.ROOT), events.trim(), executes.group(1).toLowerCase(Locale.ROOT)));
                continue;
            }
            matcher = DROP_TABLE.matcher(statement);
            if (matcher.find()) {
                TABLES.remove(matcher.group(1).toLowerCase(Locale.ROOT));
                continue;
            }
            matcher = CREATE_TABLE.matcher(statement);
            if (matcher.find()) {
                TABLES.add(matcher.group(1).toLowerCase(Locale.ROOT));
                continue;
            }
            matcher = DROP_FUNCTION.matcher(statement);
            if (matcher.find()) {
                FUNCTIONS.remove(matcher.group(1).toLowerCase(Locale.ROOT));
                DEFINITIONS.remove(matcher.group(1).toLowerCase(Locale.ROOT));
                continue;
            }
            matcher = CREATE_FUNCTION.matcher(statement);
            if (matcher.find()) {
                FUNCTIONS.put(matcher.group(1).toLowerCase(Locale.ROOT), statement);
                DEFINITIONS.computeIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT), function -> new ArrayList<>())
                        .add(new Definition(migration, statement));
            }
        }
    }

    /** {@code [start, end)} of a dollar-quoted string beginning at {@code from}, or null when none begins there. */
    private static int[] dollarQuote(String text, int from) {
        int tagEnd = from + 1;
        while (tagEnd < text.length() && (Character.isLetterOrDigit(text.charAt(tagEnd)) || text.charAt(tagEnd) == '_')) {
            tagEnd++;
        }
        if (tagEnd >= text.length() || text.charAt(tagEnd) != '$' || Character.isDigit(text.charAt(from + 1))) {
            return null;
        }
        String delimiter = text.substring(from, tagEnd + 1);
        int close = text.indexOf(delimiter, tagEnd + 1);
        if (close < 0) {
            throw new IllegalStateException("Unterminated dollar quote " + delimiter);
        }
        return new int[] {from, close + delimiter.length(), tagEnd + 1, close};
    }

    private static int afterString(String text, int from) {
        int at = from + 1;
        while (at < text.length()) {
            if (text.charAt(at) == '\'') {
                if (at + 1 < text.length() && text.charAt(at + 1) == '\'') {
                    at += 2;
                    continue;
                }
                return at + 1;
            }
            at++;
        }
        throw new IllegalStateException("Unterminated string");
    }

    /** The script's statements with comments removed and strings and dollar quotes kept whole. */
    private static List<String> statementsOf(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int at = 0;
        while (at < sql.length()) {
            char character = sql.charAt(at);
            if (sql.startsWith("--", at)) {
                int end = sql.indexOf('\n', at);
                at = end < 0 ? sql.length() : end;
            } else if (sql.startsWith("/*", at)) {
                at = sql.indexOf("*/", at) + 2;
            } else if (character == '\'') {
                int end = afterString(sql, at);
                current.append(sql, at, end);
                at = end;
            } else if (character == '$' && dollarQuote(sql, at) != null) {
                int end = dollarQuote(sql, at)[1];
                current.append(sql, at, end);
                at = end;
            } else if (character == ';') {
                statements.add(current.toString().strip());
                current.setLength(0);
                at++;
            } else {
                current.append(character);
                at++;
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString().strip());
        }
        return statements;
    }

    /** One entry per clock call in the body's code: its qualifier, or {@code ""} when it has none. */
    private static List<String> clockCalls(String statement) {
        int[] body = bodyOf(statement);
        List<String> calls = new ArrayList<>();
        if (body == null) {
            return calls;
        }
        String code = statement.substring(body[0], body[1]);
        int at = 0;
        while (at < code.length()) {
            char character = code.charAt(at);
            if (code.startsWith("--", at)) {
                int end = code.indexOf('\n', at);
                at = end < 0 ? code.length() : end;
            } else if (code.startsWith("/*", at)) {
                at = code.indexOf("*/", at) + 2;
            } else if (character == '\'') {
                at = afterString(code, at);
            } else if (character == '$' && dollarQuote(code, at) != null) {
                at = dollarQuote(code, at)[1];
            } else if (Character.isLetter(character) || character == '_') {
                int end = at;
                while (end < code.length() && (Character.isLetterOrDigit(code.charAt(end)) || code.charAt(end) == '_')) {
                    end++;
                }
                if (CLOCK_FUNCTIONS.contains(code.substring(at, end).toLowerCase(Locale.ROOT)) && nextIsParenthesis(code, end)) {
                    calls.add(qualifierBefore(code, at));
                }
                at = end;
            } else {
                at++;
            }
        }
        return calls;
    }

    private static boolean nextIsParenthesis(String code, int from) {
        int at = from;
        while (at < code.length() && Character.isWhitespace(code.charAt(at))) {
            at++;
        }
        return at < code.length() && code.charAt(at) == '(';
    }

    private static String qualifierBefore(String code, int at) {
        int before = at - 1;
        while (before >= 0 && Character.isWhitespace(code.charAt(before))) {
            before--;
        }
        if (before < 0 || code.charAt(before) != '.') {
            return "";
        }
        int end = before;
        before--;
        while (before >= 0 && Character.isWhitespace(code.charAt(before))) {
            before--;
        }
        int last = before + 1;
        while (before >= 0 && (Character.isLetterOrDigit(code.charAt(before)) || code.charAt(before) == '_')) {
            before--;
        }
        return end == 0 ? "" : code.substring(before + 1, last).toLowerCase(Locale.ROOT);
    }

    /** {@code [start, end)} of the function body's text, or null when the statement holds no dollar quote. */
    private static int[] bodyOf(String statement) {
        int at = 0;
        while (at < statement.length()) {
            char character = statement.charAt(at);
            if (character == '\'') {
                at = afterString(statement, at);
            } else if (character == '$' && dollarQuote(statement, at) != null) {
                int[] quote = dollarQuote(statement, at);
                return new int[] {quote[2], quote[3]};
            } else {
                at++;
            }
        }
        return null;
    }

    /** A {@code SET search_path} on the function itself, outside its body, under which {@code pg_catalog} is searched first. */
    private static boolean hasAPinnedPath(String statement) {
        int[] body = bodyOf(statement);
        String outside = body == null ? statement : statement.substring(0, body[0]) + " " + statement.substring(body[1]);
        Matcher set = SET_SEARCH_PATH.matcher(outside.replace("$$", " "));
        if (!set.find()) {
            return false;
        }
        List<String> path = List.of(set.group(1).trim().toLowerCase(Locale.ROOT).split("\\s*,\\s*"));
        return !path.contains("pg_catalog") || path.get(0).equals("pg_catalog");
    }
}
