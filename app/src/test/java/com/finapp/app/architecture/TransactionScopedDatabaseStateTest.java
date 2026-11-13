package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every advisory lock and every setting the platform's own statements take is transaction-scoped ({@code P16-TSK-009}, ADR-0134).
 *
 * <p>A pooled connection outlives the transaction that borrowed it. A lock or a setting scoped to the <em>session</em> therefore
 * outlives it too: a session advisory lock not released is held by whoever borrows the connection next, and one the next borrower
 * believes it took is not its own; a session {@code SET} decides what the next borrower's statements do. The arbiters of this platform
 * rely on the opposite, a lock released by the commit or rollback that ends their transaction ({@code pg_advisory_xact_lock} and its
 * forms) and a setting gone with it ({@code set_config(..., true)}, {@code SET LOCAL}, {@code SET TRANSACTION}). Hikari reuses the
 * connections of each instance's pool, and a transaction-mode pooler (evaluated and not adopted, ADR-0134) would hand one server
 * connection to many clients in turn: both are correct only while that holds. {@code PHASE_15_PLAN.md} §12 stated it as read;
 * this holds it.
 *
 * <p>Read from the text of every module's {@code src/main/java} and {@code src/main/resources} {@code .sql} (migrations included:
 * a trigger's or a function's body runs inside the application's transactions), comments included, so a form that is named at all is
 * named on purpose. It does not see what the JDBC driver sends of its own: {@code Connection.setTransactionIsolation} is a session
 * characteristic in PgJDBC, which is why ADR-0134 refuses a transaction-mode pooler while the code sets isolation that way.
 *
 * <p>Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("every advisory lock and setting the platform's statements take is transaction-scoped (P16-TSK-009)")
class TransactionScopedDatabaseStateTest {

    /** A session-scoped form, each with why it is refused. Every one is case-insensitive unless its name says otherwise. */
    static final Map<String, Pattern> SESSION_SCOPED = new LinkedHashMap<>();

    static {
        // pg_advisory_lock, pg_try_advisory_lock and their _shared forms, and every unlock: a session's, released by nothing a
        // transaction does.
        SESSION_SCOPED.put("a session advisory lock", Pattern.compile("(?i)\\bpg_(?:try_)?advisory_lock(?:_shared)?\\s*\\("));
        SESSION_SCOPED.put("an advisory unlock, which only a session lock needs", Pattern.compile("(?i)\\bpg_advisory_unlock"));
        // SET SESSION ... and SET SESSION CHARACTERISTICS: the session's own.
        SESSION_SCOPED.put("a SET SESSION", Pattern.compile("(?i)\\bSET\\s+SESSION\\b"));
        // A statement that is a SET, at the start of a string literal: a session setting unless it is LOCAL, TRANSACTION or
        // CONSTRAINTS. An UPDATE's SET clause follows its table, never opens the literal.
        SESSION_SCOPED.put("a session SET", Pattern.compile("(?i)\"SET\\s+(?!LOCAL\\b|TRANSACTION\\b|CONSTRAINTS\\b)[a-z_]"));
        // RESET as a statement, at the start of a literal: a comment may say a write would "RESET" a column.
        SESSION_SCOPED.put("RESET, which undoes a session's setting", Pattern.compile("(?i)\"RESET\\s+[a-z_]"));
        // LISTEN and UNLISTEN: a session's subscription (upper case, as SQL is written here; prose says "listen").
        SESSION_SCOPED.put("LISTEN or UNLISTEN", Pattern.compile("\\b(?:UN)?LISTEN\\s+[a-z_*\"]"));
        // A temporary table lives as long as its session unless it is dropped on commit.
        SESSION_SCOPED.put("a temporary table not dropped on commit",
                Pattern.compile("(?i)\\bCREATE\\s+(?:LOCAL\\s+|GLOBAL\\s+)?TEMP(?:ORARY)?\\s+TABLE\\b(?![^;\"]*ON\\s+COMMIT\\s+DROP)"));
        SESSION_SCOPED.put("a cursor WITH HOLD", Pattern.compile("(?i)\\bWITH\\s+HOLD\\b"));
        // SQL-level PREPARE and DEALLOCATE (upper case): a pooler forwards them to whichever server connection it chose.
        SESSION_SCOPED.put("an SQL PREPARE or DEALLOCATE", Pattern.compile("\\b(?:PREPARE\\s+[a-z_]+\\s*(?:AS\\b|\\()|DEALLOCATE\\b)"));
        SESSION_SCOPED.put("a DISCARD", Pattern.compile("\\bDISCARD\\s+(?:ALL|PLANS|SEQUENCES|TEMP|TEMPORARY)\\b"));
    }

    /** {@code set_config(name, value, is_local)}: allowed only with its third argument {@code true}. */
    private static final Pattern SET_CONFIG = Pattern.compile("(?i)\\bset_config\\s*\\(");

    /** The transaction-scoped forms that do exist, counted so that the scan is known to have read them. */
    private static final Pattern TRANSACTION_LOCK = Pattern.compile("(?i)\\bpg_(?:try_)?advisory_xact_lock(?:_shared)?\\s*\\(");
    private static final Pattern TRANSACTION_SET = Pattern.compile("(?i)\"SET\\s+TRANSACTION\\b");

    private static final Pattern MODULE = Pattern.compile("(?m)^include\\(\"([a-z]+)\"\\)");

    private static Path root;
    private static Map<Path, String> sources;

    @BeforeAll
    static void readTheSources() {
        root = EvidenceCitations.repositoryRoot();
        sources = new LinkedHashMap<>();
        Matcher module = MODULE.matcher(EvidenceCitations.read(root.resolve("settings.gradle.kts")));
        while (module.find()) {
            Path main = root.resolve(module.group(1)).resolve("src/main");
            if (!Files.isDirectory(main)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(main)) {
                files.filter(Files::isRegularFile)
                        .filter(file -> {
                            String name = file.getFileName().toString();
                            return (name.endsWith(".java") && file.startsWith(main.resolve("java"))) || name.endsWith(".sql");
                        })
                        .sorted()
                        .forEach(file -> sources.put(root.relativize(file), EvidenceCitations.read(file).replace("\r\n", "\n")));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Test
    @DisplayName("no production statement takes a session-scoped lock or setting, a listener, a held cursor or a temporary table")
    void noSessionScopedStateInProductionSql() {
        List<String> found = new ArrayList<>();
        sources.forEach((file, text) -> found.addAll(findings(file.toString().replace('\\', '/'), text)));
        assertThat(found).as("session-scoped forms in production sources: each outlives its transaction on a pooled connection").isEmpty();
    }

    @Test
    @DisplayName("the scan read the tree: every module's sources, and the transaction-scoped forms the arbiters use, above floors")
    void theScanReadTheForms() {
        long java = sources.keySet().stream().filter(file -> file.toString().endsWith(".java")).count();
        long sql = sources.keySet().stream().filter(file -> file.toString().endsWith(".sql")).count();
        assertThat(java).as("production Java sources read").isGreaterThan(2000);
        assertThat(sql).as("production SQL files read (the migrations)").isGreaterThan(250);
        assertThat(count(TRANSACTION_LOCK)).as("transaction-scoped advisory locks read").isGreaterThanOrEqualTo(40);
        assertThat(count(SET_CONFIG)).as("set_config calls read, each one held to is_local true").isGreaterThanOrEqualTo(5);
        assertThat(count(TRANSACTION_SET)).as("SET TRANSACTION statements read").isGreaterThanOrEqualTo(5);
        assertThat(sources.keySet()).as("the outbox relay, whose per-aggregate lock is the arbiters' model, is among them")
                .contains(Path.of("platform/src/main/java/com/finapp/platform/outbox/OutboxRelay.java"));
    }

    @Test
    @DisplayName("each refusal refuses its plant, and the transaction-scoped forms beside it pass: the rules are proven on text, not assumed")
    void eachRuleRefusesItsPlant() {
        Map<String, String> plants = new LinkedHashMap<>();
        plants.put("SELECT pg_try_advisory_lock(?)", "a session advisory lock");
        plants.put("SELECT pg_advisory_lock_shared(4, ?)", "a session advisory lock");
        plants.put("SELECT pg_advisory_unlock_all()", "an advisory unlock, which only a session lock needs");
        plants.put("execute(\"SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL SERIALIZABLE\")", "a SET SESSION");
        plants.put("execute(\"SET search_path TO ledger\")", "a session SET");
        plants.put("execute(\"set statement_timeout = 5000\")", "a session SET");
        plants.put("execute(\"RESET ALL\")", "RESET, which undoes a session's setting");
        plants.put("execute(\"LISTEN finapp_events\")", "LISTEN or UNLISTEN");
        plants.put("CREATE TEMP TABLE scratch (id uuid);", "a temporary table not dropped on commit");
        plants.put("DECLARE c CURSOR WITH HOLD FOR SELECT 1", "a cursor WITH HOLD");
        plants.put("PREPARE census AS SELECT 1", "an SQL PREPARE or DEALLOCATE");
        plants.put("DISCARD ALL", "a DISCARD");
        plants.put("SELECT set_config('statement_timeout', ?, false)", "a set_config not local to its transaction");
        plants.forEach((text, rule) -> assertThat(findings("plant.java", text)).as("the plant %s", text).anySatisfy(found -> assertThat(found).contains(rule)));

        for (String allowed : List.of(
                "SELECT pg_try_advisory_xact_lock(?)", "SELECT pg_advisory_xact_lock_shared(4, ?)",
                "SELECT set_config('statement_timeout', ?, true)", "SELECT set_config('lock_timeout', ?, TRUE)",
                "execute(\"SET TRANSACTION ISOLATION LEVEL REPEATABLE READ\")", "execute(\"SET LOCAL lock_timeout = '1s'\")",
                "execute(\"SET CONSTRAINTS ALL DEFERRED\")", "\"UPDATE \" + TABLE + \" SET status = ?\"", "+ \" SET reclaims = 1\"",
                "CREATE TEMP TABLE scratch (id uuid) ON COMMIT DROP;", "SELECT pg_notify('finapp', 'x')",
                "a discard is four-eyes; the census listens", "pg_advisory* and set_config are refused there")) {
            assertThat(findings("allowed.java", allowed)).as("the allowed form %s", allowed).isEmpty();
        }
    }

    /** Every session-scoped form in one file's text, as {@code file:line rule: text}. */
    static List<String> findings(String file, String text) {
        List<String> found = new ArrayList<>();
        SESSION_SCOPED.forEach((rule, pattern) -> {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                found.add(where(file, text, matcher.start()) + " " + rule + ": " + excerpt(text, matcher.start()));
            }
        });
        Matcher call = SET_CONFIG.matcher(text);
        while (call.find()) {
            String arguments = arguments(text, call.end());
            int last = arguments.lastIndexOf(',');
            String isLocal = last < 0 ? "" : arguments.substring(last + 1).trim();
            if (!isLocal.equalsIgnoreCase("true")) {
                found.add(where(file, text, call.start()) + " a set_config not local to its transaction: " + excerpt(text, call.start()));
            }
        }
        return found;
    }

    /** The text between an opening parenthesis (just before {@code from}) and its match, or to the end of the line if none. */
    private static String arguments(String text, int from) {
        int depth = 1;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return text.substring(from, i);
            }
        }
        int end = text.indexOf('\n', from);
        return text.substring(from, end < 0 ? text.length() : end);
    }

    private static String where(String file, String text, int at) {
        return file + ":" + (text.substring(0, at).chars().filter(c -> c == '\n').count() + 1);
    }

    private static String excerpt(String text, int at) {
        int end = text.indexOf('\n', at);
        return text.substring(at, Math.min(end < 0 ? text.length() : end, at + 100)).trim();
    }

    private static long count(Pattern pattern) {
        return sources.values().stream().mapToLong(text -> pattern.matcher(text).results().count()).sum();
    }
}
