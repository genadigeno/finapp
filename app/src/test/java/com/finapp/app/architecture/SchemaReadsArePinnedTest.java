package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-0006's data boundary as the code keeps it (`P15-REM-001`): each module's SQL names its own schema only, and {@code app} -
 * the composition root, which owns no schema - reads module schemas directly only where {@code MODULE_ARCHITECTURE.md} §6
 * "Data boundary" pins the adapter and the schemas it reads, {@code SELECT} only.
 *
 * <p><strong>Why the exception exists.</strong> {@code app}'s cross-context reports and readers (the regulatory and phase reports,
 * accounting's journal reads, credit's exposure census, risk's monitored ledger) were each declared where they were built
 * (ADR-0107, ADR-0113, the task each class's header names): a read across contexts that belongs to no one context, answered on
 * one snapshot, which no module could host without depending on its siblings. The Phase 15 exit review found thirteen schemas
 * read so, {@code SELECT} only - and nothing pinning which adapter reads which schema, nor stopping a fourteenth or a write.
 *
 * <p><strong>What is read.</strong> SQL here is Java string literals, so the reading is of literals: comments are skipped, a run
 * of literals joined by {@code +} is one text (SQL is built that way), and a run is SQL when it carries an upper-case SQL word
 * ({@code SELECT}, {@code FROM}, {@code JOIN}, {@code INSERT} ...) or opens with one in any case. In a SQL run every {@code schema.name} of a schema that has
 * migrations counts. That keeps an audit action ({@code "ledger.account.open"}) or a span name out, and finds a schema however
 * the statement was split across constants.
 *
 * <p><strong>{@code SELECT} only</strong> is three holds: no write ({@code INSERT INTO}, {@code UPDATE}, {@code DELETE FROM},
 * {@code MERGE}, {@code TRUNCATE}, {@code COPY}, {@code LOCK}, DDL, a sequence advanced) names a module schema; no row lock
 * ({@code FOR UPDATE}/{@code SHARE}) is taken in a statement naming one; and every function {@code app} calls in a module
 * schema is declared {@code STABLE} or {@code IMMUTABLE} in every migration that defines it, which PostgreSQL makes a function
 * that cannot modify the database. The database's own half is {@code finapp_app}'s grants; this is the code's.
 *
 * <p>Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("app's direct schema reads are pinned per adapter and schema, SELECT only; no module's SQL names another's schema (P15-REM-001)")
class SchemaReadsArePinnedTest {

    private static final String REGISTER = "docs/architecture/MODULE_ARCHITECTURE.md";
    private static final String APP_SOURCES = "app/src/main/java/com/finapp/app/";

    /** An upper-case SQL word: a literal run carrying one is a statement or a fragment of one. */
    private static final Pattern SQL_WORD =
            Pattern.compile("\\b(SELECT|INSERT|UPDATE|DELETE|MERGE|TRUNCATE|COPY|CALL|LOCK|WITH|FROM|JOIN)\\b");

    /**
     * An SQL word in any case opening the run: a statement or fragment written in lower case. The gate's finding - the upper-case
     * reading alone let {@code "select id from ledger.hold"} pass unread. Opening only, because a lower-case "from" or "with"
     * inside prose (a span name, a message) is not SQL, and reading it as SQL would pin reads that do not exist.
     */
    private static final Pattern OPENS_WITH_SQL =
            Pattern.compile("(?i)\\s*(SELECT|INSERT|UPDATE|DELETE|MERGE|TRUNCATE|COPY|CALL|LOCK|WITH|FROM|JOIN)\\b");

    private static final Pattern LOCKING = Pattern.compile("(?i)\\bFOR\\s+(NO\\s+KEY\\s+)?(UPDATE|SHARE|KEY\\s+SHARE)\\b");

    private static final Pattern REGISTER_ROW = Pattern.compile("^\\| `([a-z]+/[A-Za-z0-9]+\\.java)` \\| ([^|]+) \\|.*\\|$");

    private static Path root;
    private static List<String> schemas;
    private static Map<String, Reading> app;

    @BeforeAll
    static void readTheTree() throws IOException {
        root = EvidenceCitations.repositoryRoot();
        schemas = MigrationLint.modules(root);
        app = readings(root.resolve(APP_SOURCES));
    }

    // -----------------------------------------------------------------
    // The real tree
    // -----------------------------------------------------------------

    @Test
    @DisplayName("every module schema app's SQL reads is a row of the register, adapter by adapter, and every row is a read the adapter makes")
    void everyAppReadIsPinned() throws IOException {
        SortedMap<String, Set<String>> reads = new TreeMap<>();
        app.forEach((file, reading) -> {
            if (!reading.schemas().isEmpty()) {
                reads.put(file, reading.schemas());
            }
        });
        SortedMap<String, Set<String>> register = register(EvidenceCitations.read(root.resolve(REGISTER)));

        assertThat(differences(reads, register))
                .as("a new direct read is declared in MODULE_ARCHITECTURE.md section 6 \"Data boundary\" with its reason; a read removed"
                        + " takes its row with it")
                .isEmpty();
        // The reading must have seen what the review counted, or an empty difference proves nothing.
        assertThat(reads.values().stream().flatMap(Set::stream).collect(Collectors.toSet()))
                .as("the schemas app reads directly").contains("ledger", "lending", "bnpl", "accounting", "platform");
        assertThat(reads.values().stream().mapToInt(Set::size).sum()).as("adapter-schema pairs").isGreaterThanOrEqualTo(40);
    }

    @Test
    @DisplayName("app's SQL writes no module schema, locks no row of one, and calls only functions that cannot write")
    void appReadsOnly() throws IOException {
        List<String> faults = new ArrayList<>();
        for (Map.Entry<String, Reading> entry : app.entrySet()) {
            entry.getValue().writes().forEach(write -> faults.add(entry.getKey() + " writes: " + write));
            entry.getValue().locks().forEach(lock -> faults.add(entry.getKey() + " locks rows: " + lock));
            for (String function : entry.getValue().functions()) {
                String[] name = function.split("\\.");
                List<String> declared = MigrationLint.volatilities(root, name[0], name[1]);
                if (declared.isEmpty() || !declared.stream().allMatch(v -> v.equals("STABLE") || v.equals("IMMUTABLE"))) {
                    faults.add(entry.getKey() + " calls " + function + ", declared " + (declared.isEmpty() ? "nowhere" : declared)
                            + ": only a STABLE or IMMUTABLE function cannot write");
                }
            }
        }
        assertThat(faults).as("the composition root reads module schemas; it never writes one (ADR-0006, P15-REM-001)").isEmpty();
        assertThat(app.values().stream().flatMap(reading -> reading.functions().stream()).collect(Collectors.toSet()))
                .as("the functions read today, so the volatility check is known to run")
                .contains("platform.applied_migration_versions", "lending.business_date", "bnpl.business_date");
    }

    @Test
    @DisplayName("every module's SQL names its own schema only - a module with none names none")
    void everyModuleNamesItsOwnSchemaOnly() throws IOException {
        List<String> faults = new ArrayList<>();
        int modulesWithSql = 0;
        try (Stream<Path> children = Files.list(root)) {
            for (Path module : children.filter(child -> Files.isDirectory(child.resolve("src/main/java"))).sorted().toList()) {
                String name = module.getFileName().toString();
                if (name.equals("app")) {
                    continue;
                }
                Map<String, Reading> readings = readings(module.resolve("src/main/java"));
                if (readings.values().stream().anyMatch(reading -> reading.schemas().contains(name))) {
                    modulesWithSql++;
                }
                readings.forEach((file, reading) -> reading.schemas().stream().filter(schema -> !schema.equals(name))
                        .forEach(schema -> faults.add(name + "/" + file + " names " + schema + "'s schema")));
            }
        }
        assertThat(faults).as("cross-module data is read through the owning module's API, never by querying its tables (ADR-0006)").isEmpty();
        assertThat(modulesWithSql).as("modules whose own SQL the reading found - every module with a schema has some").isGreaterThanOrEqualTo(19);
    }

    // -----------------------------------------------------------------
    // Teeth: planted sources, through the same reading
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a read is found however the statement is built: one literal, a text block, a run joined by +, any case of schema")
    void aReadIsFoundHoweverItIsBuilt() {
        assertThat(reading("String q = \"SELECT id FROM ledger.journal_entry WHERE id = ?\";").schemas()).containsExactlyInAnyOrder("ledger");
        assertThat(reading("String q = \"\"\"\n    SELECT 1\n      FROM bnpl.purchase p\n    \"\"\";").schemas()).containsExactlyInAnyOrder("bnpl");
        assertThat(reading("String q = \"SELECT x FROM accounting.gl_account a, \" // the second table\n + \"lending.loan l\";").schemas())
                .as("a schema in a literal with no SQL word, joined by + to one that has").containsExactlyInAnyOrder("accounting", "lending");
        assertThat(reading("String q = \" FROM Ledger.journal_line\";").schemas()).containsExactlyInAnyOrder("ledger");
        assertThat(reading("String q = \"select id from ledger.hold where id = ?\";").schemas())
                .as("a statement in lower case (the gate's finding)").containsExactlyInAnyOrder("ledger");
        assertThat(reading("String q = \"\\n  from lending.loan l\";").schemas()).as("a lower-case fragment").containsExactlyInAnyOrder("lending");
        assertThat(reading("String w = \"update ledger.hold set status = 'RELEASED'\";").writes()).as("and a lower-case write").hasSize(1);
        assertThat(reading("String q = \"SELECT '\\\"' FROM ledger.hold\"; char c = '\"'; String r = \"SELECT 1 FROM fx.trade\";").schemas())
                .as("an escaped quote and a quote character do not end or begin a literal").containsExactlyInAnyOrder("ledger", "fx");
    }

    @Test
    @DisplayName("what is not SQL is not read: a comment, a Javadoc, an audit action, a span name, a lone message")
    void whatIsNotSqlIsNotRead() {
        assertThat(reading(String.join("\n",
                "/** Reads {@code SELECT * FROM ledger.journal_line}. */",
                "// SELECT * FROM ledger.journal_line",
                "/* FROM ledger.hold */",
                "String action = \"ledger.account.open\";",
                "String span = \"bnpl.purchase.accept\";",
                "String message = \"the balance of ledger.account_balance (INV-BAL-02)\";",
                "String prose = \"withdrawn from ledger.hold by the sweep\";")).schemas()).isEmpty();
    }

    @Test
    @DisplayName("a write, a lock and a volatile function are each caught; a STABLE or IMMUTABLE one is not")
    void aWriteALockAndAVolatileFunctionAreCaught() {
        assertThat(reading("String w = \"INSERT INTO ledger.journal_line (entry_id) VALUES (?)\";").writes()).hasSize(1);
        assertThat(reading("String w = \"UPDATE ledger.hold SET status = 'RELEASED' WHERE id = ?\";").writes()).hasSize(1);
        assertThat(reading("String w = \"DELETE FROM ledger.hold WHERE id = ?\";").writes()).hasSize(1);
        assertThat(reading("String w = \"SELECT nextval('ledger.posting_seq')\";").writes()).as("a sequence advanced").hasSize(1);
        assertThat(reading("String w = \"TRUNCATE TABLE ledger.hold\";").writes()).hasSize(1);
        assertThat(reading("String r = \"SELECT id FROM ledger.hold WHERE id = ? FOR UPDATE\";").locks()).hasSize(1);
        assertThat(reading("String r = \"SELECT id FROM ledger.hold WHERE id = ? FOR NO KEY UPDATE\";").locks()).hasSize(1);
        assertThat(reading("String r = \"SELECT id FROM ledger.hold WHERE id = ?\";").writes()).isEmpty();

        assertThat(reading("String f = \"SELECT ledger.post_entry(?, ?)\";").functions()).containsExactly("ledger.post_entry");
        assertThat(MigrationLint.volatilities("CREATE FUNCTION ledger.post_entry(a uuid, b uuid) RETURNS void LANGUAGE sql"
                + " AS $$ INSERT INTO ledger.x VALUES (a, b) $$;", "ledger", "post_entry")).containsExactly("VOLATILE");
        assertThat(MigrationLint.volatilities(String.join("\n",
                "CREATE OR REPLACE FUNCTION ledger.day(t timestamptz) RETURNS date LANGUAGE sql STABLE AS $$ SELECT t::date $$;",
                "CREATE OR REPLACE FUNCTION ledger.day(t timestamptz) RETURNS date AS $$ SELECT t::date $$ LANGUAGE sql IMMUTABLE;",
                "CREATE OR REPLACE FUNCTION ledger.day(t timestamptz) RETURNS date AS $$ SELECT t::date $$ LANGUAGE sql;",
                "CREATE FUNCTION ledger.other() RETURNS int LANGUAGE sql STABLE AS $$ SELECT 1 $$;"), "ledger", "day"))
                .as("every definition, in order, a replacement that drops the marker included").containsExactly("STABLE", "IMMUTABLE", "VOLATILE");
    }

    @Test
    @DisplayName("the register's comparison names an unpinned read and a stale row")
    void theRegisterNamesAnUnpinnedReadAndAStaleRow() {
        SortedMap<String, Set<String>> register = register(String.join("\n",
                "| `accounting/A.java` | `ledger` | P14-TSK-001 |",
                "| `risk/B.java` | `ledger`, `payments` | P13-TSK-001 |"));
        assertThat(register).containsEntry("risk/B.java", Set.of("ledger", "payments"));

        SortedMap<String, Set<String>> reads = new TreeMap<>(Map.of(
                "accounting/A.java", Set.of("ledger", "lending"),
                "risk/B.java", Set.of("ledger"),
                "fx/C.java", Set.of("fx")));
        assertThat(differences(reads, register)).containsExactlyInAnyOrder(
                "accounting/A.java reads lending's schema and its row does not name it",
                "risk/B.java's row names payments, which it does not read",
                "fx/C.java reads fx's schema and has no row");
    }

    // -----------------------------------------------------------------
    // The reading
    // -----------------------------------------------------------------

    /** What one source file's SQL names: the module schemas, the module functions it calls, its writes and its row locks. */
    record Reading(Set<String> schemas, Set<String> functions, List<String> writes, List<String> locks) {}

    private static Reading reading(String source) {
        return read(source, schemas == null ? List.of("accounting", "bnpl", "fx", "ledger", "lending") : schemas);
    }

    private static Map<String, Reading> readings(Path sources) throws IOException {
        SortedMap<String, Reading> readings = new TreeMap<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                readings.put(sources.relativize(file).toString().replace('\\', '/'), read(EvidenceCitations.read(file), schemas));
            }
        }
        return readings;
    }

    static Reading read(String source, List<String> schemas) {
        String names = String.join("|", schemas);
        Pattern name = Pattern.compile("(?i)(?<![\\w.$])(" + names + ")\\.([a-z_][a-z0-9_]*)(\\s*\\()?");
        Pattern write = Pattern.compile("(?i)\\b(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|MERGE\\s+INTO|TRUNCATE(\\s+TABLE)?|COPY|LOCK(\\s+TABLE)?"
                + "|(ALTER|DROP|CREATE)\\s+(TABLE|INDEX|VIEW|SEQUENCE|FUNCTION)(\\s+IF\\s+(NOT\\s+)?EXISTS)?)\\s+(ONLY\\s+)?(" + names + ")\\."
                + "|\\b(nextval|setval)\\s*\\(\\s*'(" + names + ")\\.");
        Set<String> named = new TreeSet<>();
        Set<String> functions = new TreeSet<>();
        List<String> writes = new ArrayList<>();
        List<String> locks = new ArrayList<>();
        for (String run : literalRuns(source)) {
            if (!SQL_WORD.matcher(run).find() && !OPENS_WITH_SQL.matcher(run).lookingAt()) {
                continue;
            }
            Matcher found = name.matcher(run);
            boolean any = false;
            while (found.find()) {
                any = true;
                String schema = found.group(1).toLowerCase(Locale.ROOT);
                named.add(schema);
                boolean afterInto = run.substring(0, found.start()).stripTrailing().toUpperCase(Locale.ROOT).endsWith("INTO");
                if (found.group(3) != null && !afterInto) {
                    functions.add(schema + "." + found.group(2).toLowerCase(Locale.ROOT));
                }
            }
            Matcher written = write.matcher(run);
            while (written.find()) {
                writes.add(written.group().strip());
            }
            if (any && LOCKING.matcher(run).find()) {
                locks.add(run.strip().replaceAll("\\s+", " "));
            }
        }
        return new Reading(Set.copyOf(named), Set.copyOf(functions), List.copyOf(writes), List.copyOf(locks));
    }

    /** Every string literal and text block of a Java source, comments skipped; literals joined only by {@code +} are one run. */
    static List<String> literalRuns(String source) {
        List<String> runs = new ArrayList<>();
        StringBuilder run = null;
        int lastEnd = -1;
        int i = 0;
        int length = source.length();
        while (i < length) {
            if (source.startsWith("//", i) || source.startsWith("/*", i)) {
                i = pastComment(source, i);
            } else if (source.charAt(i) == '"') {
                boolean block = source.startsWith("\"\"\"", i);
                int end = block ? pastTextBlock(source, i) : pastString(source, i);
                String text = unescape(block ? source.substring(i + 3, end - 3) : source.substring(i + 1, end - 1));
                if (run != null && joinedByPlus(source, lastEnd, i)) {
                    run.append(text);
                } else {
                    if (run != null) {
                        runs.add(run.toString());
                    }
                    run = new StringBuilder(text);
                }
                lastEnd = end;
                i = end;
            } else if (source.charAt(i) == '\'') {
                int j = i + 1;
                while (j < length && source.charAt(j) != '\'') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                i = j + 1;
            } else {
                i++;
            }
        }
        if (run != null) {
            runs.add(run.toString());
        }
        return runs;
    }

    /** A literal's text as the program sees it, near enough: a whitespace escape is a space, any other escaped character itself. */
    private static String unescape(String raw) {
        StringBuilder text = new StringBuilder(raw.length());
        for (int k = 0; k < raw.length(); k++) {
            char c = raw.charAt(k);
            if (c == '\\' && k + 1 < raw.length()) {
                char escaped = raw.charAt(++k);
                text.append("ntrsfb\n".indexOf(escaped) >= 0 ? ' ' : escaped);
            } else {
                text.append(c);
            }
        }
        return text.toString();
    }

    private static boolean joinedByPlus(String source, int from, int to) {
        int k = pastSpace(source, from);
        return k < to && source.charAt(k) == '+' && pastSpace(source, k + 1) == to;
    }

    private static int pastSpace(String source, int from) {
        int k = from;
        while (k < source.length()) {
            if (Character.isWhitespace(source.charAt(k))) {
                k++;
            } else if (source.startsWith("//", k) || source.startsWith("/*", k)) {
                k = pastComment(source, k);
            } else {
                break;
            }
        }
        return k;
    }

    private static int pastComment(String source, int start) {
        if (source.startsWith("//", start)) {
            int end = source.indexOf('\n', start);
            return end < 0 ? source.length() : end;
        }
        int end = source.indexOf("*/", start + 2);
        return end < 0 ? source.length() : end + 2;
    }

    private static int pastString(String source, int start) {
        int j = start + 1;
        while (j < source.length() && source.charAt(j) != '"') {
            j += source.charAt(j) == '\\' ? 2 : 1;
        }
        return j + 1;
    }

    private static int pastTextBlock(String source, int start) {
        int j = start + 3;
        while (j < source.length()) {
            if (source.charAt(j) == '\\') {
                j += 2;
            } else if (source.startsWith("\"\"\"", j)) {
                return j + 3;
            } else {
                j++;
            }
        }
        return source.length();
    }

    // -----------------------------------------------------------------
    // The register
    // -----------------------------------------------------------------

    /** The register's rows: {@code | `area/Class.java` | `schema`, `schema` | declared by |}, in §6 "Data boundary". */
    static SortedMap<String, Set<String>> register(String document) {
        SortedMap<String, Set<String>> rows = new TreeMap<>();
        for (String line : document.replace("\r\n", "\n").split("\n")) {
            Matcher row = REGISTER_ROW.matcher(line);
            if (row.matches()) {
                Set<String> read = new TreeSet<>();
                Matcher schema = Pattern.compile("`([a-z]+)`").matcher(row.group(2));
                while (schema.find()) {
                    read.add(schema.group(1));
                }
                assertThat(rows.put(row.group(1), Set.copyOf(read))).as("one row per adapter: %s", row.group(1)).isNull();
            }
        }
        return rows;
    }

    static List<String> differences(SortedMap<String, Set<String>> reads, SortedMap<String, Set<String>> register) {
        List<String> differences = new ArrayList<>();
        reads.forEach((file, read) -> {
            Set<String> pinned = register.get(file);
            if (pinned == null) {
                read.forEach(schema -> differences.add(file + " reads " + schema + "'s schema and has no row"));
                return;
            }
            read.stream().filter(schema -> !pinned.contains(schema))
                    .forEach(schema -> differences.add(file + " reads " + schema + "'s schema and its row does not name it"));
        });
        register.forEach((file, pinned) -> pinned.stream().filter(schema -> !reads.getOrDefault(file, Set.of()).contains(schema))
                .forEach(schema -> differences.add(file + "'s row names " + schema + ", which it does not read")));
        return differences;
    }
}
