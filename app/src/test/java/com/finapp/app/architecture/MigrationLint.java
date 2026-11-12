package com.finapp.app.architecture;

import com.finapp.app.database.SchemaVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The expand-only lint (`P15-TSK-005`; ADR-0122 decision 5; {@code DATA_MIGRATIONS.md} section 4.1): the schema of release N
 * must serve release N − 1's code, so a migration added since the last release may not take away what N − 1 uses, and may
 * not refuse a write N − 1 still makes, unless it says so with a contract marker.
 *
 * <p>There is no release tag and CI clones shallowly, so "the last release" is a committed file, the {@link Floor}. A
 * migration above its module's floor is read; one at or below it is not.
 *
 * <h2>How a file is read</h2>
 *
 * <p>Tokenised, then taken statement by statement. Comments, string literals and dollar-quoted bodies are single tokens,
 * so a word inside them never counts, and a {@code CREATE FUNCTION}'s body, which the migration does not execute, is not
 * mistaken for DDL. <strong>The statements are an allow-list</strong>: a form this class does not know is refused as
 * {@link Refused#UNRECOGNISED}, never passed. A {@code DO} block is opaque and refused as {@link Refused#OPAQUE_BLOCK}
 * rather than half-parsed. A file the tokeniser cannot finish is {@link Refused#UNREADABLE}, which a marker does not waive.
 *
 * <h2>What it does not claim</h2>
 *
 * <p>It reads no function body. A {@code CREATE OR REPLACE} of something a release already holds can add a refusal in words
 * this class never sees, so <em>every</em> such replacement is refused as {@link Refused#REPLACED_BODY}, whatever the body
 * says, and passing needs a marker: a person's reviewed statement. The lint does not prove a replacement harmless, it makes
 * somebody say so. Nor does it know what a release's code writes: that N − 1 really does conform is what the marker
 * asserts and {@code P15-TSK-012}'s rehearsal runs.
 */
public final class MigrationLint {

    /** The floor file, relative to the repository root. */
    public static final String FLOOR_FILE = "docs/migrations/released-schema-floor.yaml";

    /** What the file's {@code release} says while no release has been cut: no release N − 1 exists to break. */
    public static final String NO_RELEASE = "none";

    /** A marker that names no release: every release's code conforms, so it excludes nothing from a rollback. */
    public static final String ANY_RELEASE = "ANY";

    /**
     * The contract marker, one {@code --} line in the file's header, before its first statement. {@code DATA_MIGRATIONS.md}
     * section 4.1 states this text and a test holds the two equal: {@code P15-TSK-017}'s promotion script reads the same
     * form. The optional {@code data-fix=V<version>} (`P15-TSK-032`) names the migration of the same release, before this one,
     * that brings the rows this file's tightening judges into line ({@link #tightenings}); every marker written without it
     * keeps its meaning.
     */
    public static final String MARKER_FORM =
            "^-- finapp-contract: first-conforming-release=(ANY|[0-9]+\\.[0-9]+\\.[0-9]+); waives=([A-Z_]+(?:,[A-Z_]+)*)"
                    + "(?:; data-fix=V([0-9]+(?:[._][0-9]+)*))?; reason=(\\S.*)$";

    private static final Pattern MARKER = Pattern.compile(MARKER_FORM);

    private static final Pattern RELEASE_NAME = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");

    private static final Pattern MARKER_LIKE = Pattern.compile("^--\\s*finapp-contract\\b.*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern MIGRATION_FILE = Pattern.compile("V([0-9]+(?:[._][0-9]+)*)__.*\\.sql");

    private static final Set<String> SERIAL_TYPES = Set.of("SMALLSERIAL", "SERIAL", "BIGSERIAL", "SERIAL2", "SERIAL4", "SERIAL8");

    private static final Set<String> ADDITIVE_STATEMENTS = Set.of("GRANT", "COMMENT", "INSERT", "UPDATE", "SELECT", "RESET", "LOCK", "ANALYZE");

    /** What can call a function: a call of one this module defined runs a body nobody here read, as a {@code DO} does. */
    private static final Set<String> CALLING_STATEMENTS = Set.of("INSERT", "UPDATE", "SELECT", "WITH");

    private static final Set<String> ADDITIVE_OBJECTS = Set.of("TYPE", "SCHEMA", "EXTENSION", "CAST", "OPERATOR", "COLLATION");

    private static final Set<String> BODIES = Set.of("FUNCTION", "PROCEDURE", "AGGREGATE");

    private static final Set<String> TABLE_MODIFIERS = Set.of("GLOBAL", "LOCAL", "TEMP", "TEMPORARY", "UNLOGGED");

    private MigrationLint() {}

    /** Why a statement or a file was refused. */
    public enum Refused {
        DROP,
        RENAME,
        TYPE_CHANGE,
        TRUNCATE,
        CHECK,
        TRIGGER,
        UNIQUE,
        FOREIGN_KEY,
        ALTERED_CONSTRAINT,
        SET_GENERATED,
        DELETE,
        SET_NOT_NULL,
        NOT_NULL_WITHOUT_DEFAULT,
        REVOKE,
        ROW_SECURITY,
        RULE,
        REPLACED_BODY,
        OPAQUE_BLOCK,
        UNRECOGNISED,
        UNREADABLE,
        NOT_A_MIGRATION,
        MALFORMED_MARKER,
        MARKER_AHEAD_OF_THE_RELEASE,
        /**
         * A tightening that judges rows an earlier release wrote, with neither {@code NOT VALID} nor a named data fix
         * ({@link #tightenings}). Not a class {@code waives=} can name: only the marker's {@code data-fix=} answers it.
         */
        TIGHTENED_OVER_OLD_ROWS;

        /** The refusals a contract marker can answer: a statement's, never the file's own form. */
        boolean waivedByAMarker() {
            return this != UNREADABLE && this != NOT_A_MIGRATION && this != MALFORMED_MARKER && this != MARKER_AHEAD_OF_THE_RELEASE
                    && this != TIGHTENED_OVER_OLD_ROWS;
        }
    }

    /** One refusal: the module, the file's name, the class, and the statement's opening words. */
    public record Refusal(String module, String file, Refused rule, String statement) {
        @Override
        public String toString() {
            return module + "/" + file + ": " + rule + " in `" + statement + "`";
        }
    }

    /** What a run read and what it refused. */
    public record Outcome(List<String> filesRead, List<Refusal> refusals) {}

    /**
     * The last release's schema, per module: the file {@value #FLOOR_FILE}.
     *
     * @param release the last release's name, or {@value #NO_RELEASE}
     * @param floors each module's highest version at that release; a module absent here is read from its first migration
     */
    public record Floor(String release, SortedMap<String, SchemaVersion> floors) {

        public SchemaVersion of(String module) {
            return floors.getOrDefault(module, SchemaVersion.ZERO);
        }

        /** The committed floor of the repository at {@code root}. */
        public static Floor committed(Path root) {
            return parse(read(root.resolve(FLOOR_FILE)));
        }

        @SuppressWarnings("unchecked")
        static Floor parse(String yaml) {
            Map<String, Object> document = (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
            Object release = document.get("release");
            Map<String, Object> declared = (Map<String, Object>) document.get("floors");
            if (!(release instanceof String) || declared == null) {
                throw new IllegalStateException("the floor file names no release or no floors");
            }
            if (!NO_RELEASE.equals(release) && !RELEASE_NAME.matcher((String) release).matches()) {
                throw new IllegalStateException("the floor file's release is neither " + NO_RELEASE + " nor MAJOR.MINOR.PATCH: " + release);
            }
            SortedMap<String, SchemaVersion> floors = new TreeMap<>();
            declared.forEach((module, version) -> floors.put(module, SchemaVersion.parse(String.valueOf(version))));
            return new Floor((String) release, floors);
        }

        /** This floor with every module moved to what is on disk now, for a reviewed copy over the committed file. */
        String rendered(Map<String, SchemaVersion> head) {
            StringBuilder text = new StringBuilder();
            text.append("# The schema of the last release, per module (ADR-0122 decision 5; DATA_MIGRATIONS.md section 4.1).\n");
            text.append("# Generated: MigrationsAreExpandOnlyTest leaves the next one under app/build/schema-floor/. Never edit by hand\n");
            text.append("# but for `release`, which the release procedure names. A migration above its module's floor is read by the\n");
            text.append("# expand-only lint; `none` says no release has been cut, so there is no release N - 1 to break yet.\n");
            text.append("release: \"").append(release).append("\"\n");
            text.append("floors:\n");
            new TreeMap<>(head).forEach((module, version) ->
                    text.append("  ").append(module).append(": \"").append(version).append("\"\n"));
            return text.toString();
        }
    }

    /** Every module that owns migrations, found by walking the repository, never listed. */
    public static List<String> modules(Path root) throws IOException {
        try (Stream<Path> children = Files.list(root)) {
            return children
                    .filter(module -> Files.isDirectory(directory(root, module.getFileName().toString())))
                    .map(module -> module.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    /** Each module's highest version on disk. */
    public static SortedMap<String, SchemaVersion> head(Path root) throws IOException {
        SortedMap<String, SchemaVersion> head = new TreeMap<>();
        for (String module : modules(root)) {
            for (Path file : files(root, module)) {
                Matcher name = MIGRATION_FILE.matcher(file.getFileName().toString());
                if (name.matches()) {
                    head.merge(module, SchemaVersion.parse(name.group(1)), (one, other) -> one.compareTo(other) >= 0 ? one : other);
                }
            }
        }
        return head;
    }

    /** Reads every migration above its module's floor, the one path a real file and a planted one both take. */
    public static Outcome check(Path root, Floor floor) throws IOException {
        List<String> read = new ArrayList<>();
        List<Refusal> refusals = new ArrayList<>();
        for (String module : modules(root)) {
            Map<SchemaVersion, Path> ordered = new TreeMap<>();
            Path directory = directory(root, module);
            for (Path file : files(root, module)) {
                Matcher name = MIGRATION_FILE.matcher(file.getFileName().toString());
                // Flyway finds a script in a subdirectory, and the migrator image stages only the top level, so one there is
                // applied by the build and never by a deployment: refused, never silently unread.
                if (name.matches() && file.getParent().equals(directory)) {
                    ordered.put(SchemaVersion.parse(name.group(1)), file);
                } else {
                    refusals.add(new Refusal(module, directory.relativize(file).toString(), Refused.NOT_A_MIGRATION, "the file's name or place"));
                }
            }
            Set<String> released = new HashSet<>();
            Set<String> earlier = new HashSet<>();
            SchemaVersion moduleFloor = floor.of(module);
            for (Map.Entry<SchemaVersion, Path> migration : ordered.entrySet()) {
                String fileName = migration.getValue().getFileName().toString();
                if (migration.getKey().compareTo(moduleFloor) <= 0) {
                    Set<String> definitions = definitionsIn(read(migration.getValue()), module, earlier);
                    released.addAll(definitions);
                    earlier.addAll(definitions);
                    continue;
                }
                read.add(module + "/" + fileName);
                refusals.addAll(lint(read(migration.getValue()), module, fileName, released, earlier, floor.release()));
            }
        }
        return new Outcome(List.copyOf(read), List.copyOf(refusals));
    }

    // -----------------------------------------------------------------
    // Tightenings over old rows (P15-TSK-032; THREAT_MODEL.md section 6 item 42 (j))
    // -----------------------------------------------------------------

    /**
     * Every tightening, since the last release, of a table or domain older than that release, that neither leaves the old rows
     * unjudged nor names the fix that makes them pass.
     *
     * <p><strong>Why.</strong> A tightening judges every row the table already holds: a {@code CHECK}, a foreign key, a unique
     * constraint or index, {@code SET NOT NULL}, a column added {@code NOT NULL} with no default or with a constraint of its own.
     * It passes in the build and in staging, on their rows, and can fail on production's alone. Then staging, which has applied
     * it, refuses any fix with a lower version ({@code outOfOrder=false}), so production has no forward fix at all
     * ({@code OPERATIONS_RUNBOOK.md} section 11.4, "In production"). So such a migration must be one of:
     * <ul>
     *   <li>{@code NOT VALID} ({@code CHECK} and foreign keys, on a table or a domain): new writes are judged at once, the old
     *       rows not yet; the constraint's {@code VALIDATE CONSTRAINT} comes only in a later release's migration, when the
     *       release between has given the rows time to be found and fixed. {@code SET NOT NULL} has no {@code NOT VALID} form, so
     *       its way is a {@code CHECK (col IS NOT NULL) NOT VALID}, validated by a later release, and then {@code SET NOT NULL},
     *       which PostgreSQL proves from the validated check without a scan: a {@code SET NOT NULL} after such a validation
     *       passes;</li>
     *   <li>or the file's contract marker names its data fix, {@code data-fix=V<version>}: a migration of the same release, of
     *       a lower version, that writes rows ({@code INSERT}, {@code UPDATE} or {@code DELETE}). The fix runs first wherever the
     *       release runs, production included.</li>
     * </ul>
     *
     * <p><strong>Old and new.</strong> "The release" is what the expand-only lint reads as one: the migrations above the
     * module's floor. A table or domain is new when a migration of this release created it (certainly: not {@code IF NOT
     * EXISTS}, not a partition, not temporary, not through a moved {@code search_path}); every other one is old, whoever made
     * it. While no release has been cut (the floor file's {@code release: none}) the floors still divide the files, and every
     * table at or below them counts as old: staging, every developer's database and every rehearsal hold rows in those tables,
     * and a tightening that waited for the first cut to be judged would be found by it.
     *
     * <p>The files at or below the floor are read only for what this needs of them: the constraints they added {@code NOT VALID}
     * and the ones they validated. It does not judge a data fix: it holds that one is named and comes first, and the marker's
     * reason is the person's statement of what it fixes.
     */
    public static Outcome tightenings(Path root, Floor floor) throws IOException {
        List<String> read = new ArrayList<>();
        List<Refusal> refusals = new ArrayList<>();
        for (String module : modules(root)) {
            Path directory = directory(root, module);
            SortedMap<SchemaVersion, Path> ordered = new TreeMap<>();
            for (Path file : files(root, module)) {
                Matcher name = MIGRATION_FILE.matcher(file.getFileName().toString());
                if (name.matches() && file.getParent().equals(directory)) {
                    ordered.put(SchemaVersion.parse(name.group(1)), file);
                }
            }
            SchemaVersion moduleFloor = floor.of(module);
            ModuleRows rows = new ModuleRows();
            for (Map.Entry<SchemaVersion, Path> migration : ordered.entrySet()) {
                String fileName = migration.getValue().getFileName().toString();
                boolean inRelease = migration.getKey().compareTo(moduleFloor) > 0;
                Tokenised text;
                try {
                    text = tokenise(read(migration.getValue()));
                } catch (UnreadableSql unreadable) {
                    continue; // the expand-only lint refuses it, and nothing here can be read from it
                }
                TighteningReading reading = new TighteningReading(module, inRelease, rows);
                statements(text.tokens()).forEach(reading::statement);
                if (!inRelease) {
                    continue;
                }
                read.add(module + "/" + fileName);
                if (!reading.unfixed.isEmpty()) {
                    String fault = dataFixFault(marker(text, module, fileName, floor.release()).dataFix(), migration.getKey(), rows.earlierInRelease);
                    if (fault != null) {
                        reading.unfixed.forEach(statement ->
                                refusals.add(new Refusal(module, fileName, Refused.TIGHTENED_OVER_OLD_ROWS, statement + "; " + fault)));
                    }
                }
                rows.earlierInRelease.put(migration.getKey(), reading.writesRows);
            }
        }
        return new Outcome(List.copyOf(read), List.copyOf(refusals));
    }

    /** Why a named data fix does not answer, or {@code null} when it does. */
    private static String dataFixFault(SchemaVersion named, SchemaVersion self, Map<SchemaVersion, Boolean> earlierInRelease) {
        if (named == null) {
            return "it is not NOT VALID, and its contract marker names no data fix (data-fix=V<version>)";
        }
        if (named.compareTo(self) >= 0) {
            return "its marker names V" + named + ", which does not come before it";
        }
        Boolean writes = earlierInRelease.get(named);
        if (writes == null) {
            return "its marker names V" + named + ", which is no migration of this release";
        }
        if (!writes) {
            return "its marker names V" + named + ", which writes no row";
        }
        return null;
    }

    /** What one module's migrations have said so far, in version order, that a later tightening is judged by. */
    private static final class ModuleRows {
        /** Tables and domains a migration of this release created. */
        private final Set<String> releaseObjects = new HashSet<>();
        /** {@code <object>:<constraint>} added {@code NOT VALID}: whether in this release, and the column of a not-null check. */
        private final Map<String, Pending> notValid = new java.util.HashMap<>();
        /** {@code <table>:<column>} whose {@code CHECK (column IS NOT NULL)} some migration has validated. */
        private final Set<String> validatedNotNull = new HashSet<>();
        private final Set<String> notNullDomains = new HashSet<>();
        private final Set<String> checkedDomains = new HashSet<>();
        /** Each migration of this release read so far, and whether it writes rows. */
        private final Map<SchemaVersion, Boolean> earlierInRelease = new java.util.HashMap<>();
    }

    private record Pending(boolean inRelease, String notNullColumn) {}

    /** One file's statements, for the tightenings of old objects and what the module must remember of them. */
    private static final class TighteningReading {
        private final String module;
        private final boolean inRelease;
        private final ModuleRows rows;
        private final Set<String> createdHere = new HashSet<>();
        private final List<String> unfixed = new ArrayList<>();
        private boolean writesRows;
        private boolean searchPathMoved;

        TighteningReading(String module, boolean inRelease, ModuleRows rows) {
            this.module = module;
            this.inRelease = inRelease;
            this.rows = rows;
        }

        void statement(List<Token> s) {
            switch (at(s, 0).word()) {
                case "CREATE" -> create(s);
                case "ALTER" -> {
                    if (at(s, 1).is("TABLE")) {
                        alterTable(s);
                    } else if (at(s, 1).is("DOMAIN")) {
                        alterDomain(s);
                    }
                }
                case "SET" -> searchPathMoved |= containsWord(s, "SEARCH_PATH");
                case "INSERT", "UPDATE", "DELETE" -> writesRows = true;
                case "WITH" -> writesRows |= containsWord(s, "INSERT") || containsWord(s, "UPDATE") || containsWord(s, "DELETE");
                default -> {}
            }
            if (CALLING_STATEMENTS.contains(at(s, 0).word())) {
                searchPathMoved |= containsWord(s, "SET_CONFIG");
            }
        }

        private boolean isNew(String key) {
            return createdHere.contains(key) || rows.releaseObjects.contains(key);
        }

        /** A tightening of {@code key}: judged only in this release, and only when the object is older than it. */
        private void tighten(String key, List<Token> s, String what) {
            if (inRelease && !isNew(key)) {
                unfixed.add(s.stream().limit(10).map(Token::text).collect(Collectors.joining(" ")) + " (" + what + ")");
            }
        }

        private void created(String key) {
            createdHere.add(key);
            if (inRelease) {
                rows.releaseObjects.add(key);
            }
        }

        private void create(List<Token> s) {
            int index = at(s, 1).is("OR") && at(s, 2).is("REPLACE") ? 3 : 1;
            boolean temporary = false;
            while (TABLE_MODIFIERS.contains(at(s, index).word())) {
                temporary |= at(s, index).is("TEMP") || at(s, index).is("TEMPORARY");
                index++;
            }
            switch (at(s, index).word()) {
                case "TABLE" -> createTable(s, index + 1, temporary);
                case "UNIQUE" -> {
                    int on = indexOf(s, "ON", index);
                    if (on >= 0) {
                        String table = nameIn(s, at(s, on + 1).is("ONLY") ? on + 2 : on + 1, module).text();
                        tighten("table:" + table, s, "a unique index judges every row");
                    }
                }
                case "DOMAIN" -> {
                    Name domain = nameIn(s, index + 1, module);
                    List<Token> body = atDepthZero(s.subList(Math.min(domain.next(), s.size()), s.size()));
                    if (containsSequence(body, "NOT", "NULL")) {
                        rows.notNullDomains.add(domain.text());
                    }
                    if (containsWord(body, "CHECK")) {
                        rows.checkedDomains.add(domain.text());
                    }
                    created("domain:" + domain.text());
                }
                default -> {}
            }
        }

        private void createTable(List<Token> s, int start, boolean temporary) {
            int index = start;
            boolean ifAbsent = at(s, index).is("IF") && at(s, index + 1).is("NOT");
            if (ifAbsent) {
                index += 3;
            }
            Name table = nameIn(s, index, module);
            boolean partition = at(s, table.next()).is("PARTITION") && at(s, table.next() + 1).is("OF");
            if (!ifAbsent && !partition && !temporary && (table.qualified() || !searchPathMoved)) {
                created("table:" + table.text());
            }
        }

        private void alterTable(List<Token> s) {
            int index = 2;
            if (at(s, index).is("IF") && at(s, index + 1).is("EXISTS")) {
                index += 2;
            }
            if (at(s, index).is("ONLY")) {
                index++;
            }
            if (at(s, index).is("ALL")) {
                return;
            }
            Name table = nameIn(s, index, module);
            index = table.next();
            if (at(s, index).isPunct("*")) {
                index++;
            }
            String key = "table:" + table.text();
            for (List<Token> action : splitOnCommas(s.subList(Math.min(index, s.size()), s.size()))) {
                switch (at(action, 0).word()) {
                    case "ADD" -> add(key, table.text(), action, s);
                    case "ALTER" -> alterColumn(key, table.text(), action, s);
                    case "VALIDATE" -> validate(key, table.text(), at(action, 2).identifier(), s);
                    default -> {}
                }
            }
        }

        private void add(String key, String table, List<Token> a, List<Token> s) {
            int index = 1;
            String constraint = null;
            if (at(a, index).is("CONSTRAINT")) {
                constraint = at(a, index + 1).identifier();
                index += 2;
            }
            switch (at(a, index).word()) {
                case "CHECK", "FOREIGN" -> {
                    if (containsSequence(atDepthZero(a), "NOT", "VALID")) {
                        if (constraint != null) {
                            rows.notValid.put(key + ":" + constraint, new Pending(inRelease && !isNew(key), notNullColumn(a, index)));
                        }
                    } else {
                        tighten(key, s, at(a, index).is("CHECK") ? "a CHECK judges every row" : "a foreign key judges every row");
                    }
                }
                case "UNIQUE", "PRIMARY", "EXCLUDE" -> tighten(key, s, "a unique constraint judges every row");
                default -> addColumn(key, a, s);
            }
        }

        /** The column of {@code CHECK (column IS NOT NULL)}, the one shape {@code SET NOT NULL} can later rest on; else null. */
        private static String notNullColumn(List<Token> a, int check) {
            boolean shaped = at(a, check).is("CHECK") && at(a, check + 1).isPunct("(") && at(a, check + 3).is("IS")
                    && at(a, check + 4).is("NOT") && at(a, check + 5).is("NULL") && at(a, check + 6).isPunct(")");
            Token column = at(a, check + 2);
            return shaped && (column.kind() == Kind.WORD || column.kind() == Kind.QUOTED) ? column.identifier() : null;
        }

        private void addColumn(String key, List<Token> a, List<Token> s) {
            int index = 1;
            if (at(a, index).is("COLUMN")) {
                index++;
            }
            if (at(a, index).is("IF") && at(a, index + 1).is("NOT")) {
                index += 3;
            }
            index++; // past the column's own name
            Name type = nameIn(a, index, module);
            List<Token> definition = a.subList(Math.min(type.next(), a.size()), a.size());
            List<Token> column = atDepthZero(definition);
            boolean notNull = containsSequence(column, "NOT", "NULL") || rows.notNullDomains.contains(type.text());
            boolean generated = SERIAL_TYPES.contains(at(a, index).word()) || containsSequence(column, "GENERATED", "ALWAYS")
                    || containsSequence(column, "GENERATED", "BY");
            if (notNull && !Reading.hasADefault(definition) && !generated) {
                tighten(key, s, "a column added NOT NULL with no default fails on any row");
            }
            if (containsWord(column, "CHECK") || rows.checkedDomains.contains(type.text()) || containsWord(column, "UNIQUE")
                    || containsWord(column, "PRIMARY") || containsWord(column, "REFERENCES")) {
                tighten(key, s, "a column added with a constraint is judged on every row");
            }
        }

        private void alterColumn(String key, String table, List<Token> a, List<Token> s) {
            int index = 1;
            if (at(a, index).is("CONSTRAINT")) {
                return;
            }
            if (at(a, index).is("COLUMN")) {
                index++;
            }
            String column = at(a, index).identifier();
            if (at(a, index + 1).is("SET") && at(a, index + 2).is("NOT") && !rows.validatedNotNull.contains(table + ":" + column)) {
                tighten(key, s, "SET NOT NULL judges every row, and no validated CHECK (" + column + " IS NOT NULL) precedes it");
            }
        }

        /** A validation: on an old object, only of a constraint an earlier release added {@code NOT VALID}. */
        private void validate(String key, String table, String constraint, List<Token> s) {
            Pending pending = rows.notValid.get(key + ":" + constraint);
            if (pending != null && pending.notNullColumn() != null) {
                rows.validatedNotNull.add(table + ":" + pending.notNullColumn());
            }
            if (pending == null) {
                tighten(key, s, "it validates a constraint no migration of this module added NOT VALID");
            } else if (pending.inRelease()) {
                tighten(key, s, "it validates in the same release the constraint was added NOT VALID");
            }
        }

        private void alterDomain(List<Token> s) {
            Name domain = nameIn(s, 2, module);
            String key = "domain:" + domain.text();
            List<Token> rest = s.subList(Math.min(domain.next(), s.size()), s.size());
            if (at(rest, 0).is("ADD")) {
                String constraint = at(rest, 1).is("CONSTRAINT") ? at(rest, 2).identifier() : null;
                if (containsSequence(atDepthZero(rest), "NOT", "VALID")) {
                    if (constraint != null) {
                        rows.notValid.put(key + ":" + constraint, new Pending(inRelease && !isNew(key), null));
                    }
                } else {
                    tighten(key, s, "a domain's CHECK judges every column of that type");
                }
            } else if (at(rest, 0).is("SET") && at(rest, 1).is("NOT")) {
                tighten(key, s, "a domain's SET NOT NULL judges every column of that type");
            } else if (at(rest, 0).is("VALIDATE")) {
                validate(key, domain.text(), at(rest, 2).identifier(), s);
            }
        }
    }

    private static Path directory(Path root, String module) {
        return root.resolve(module).resolve("src/main/resources/db/migration").resolve(module);
    }

    /** Every file under the module's directory, however deep: Flyway recurses, so a file this did not see would be unread. */
    private static List<Path> files(Path root, String module) throws IOException {
        try (Stream<Path> files = Files.walk(directory(root, module))) {
            return files.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    /** What a migration at or below the floor defined: a replacement of one of these is a change to something released. */
    private static Set<String> definitionsIn(String sql, String module, Set<String> earlier) {
        Tokenised text;
        try {
            text = tokenise(sql);
        } catch (UnreadableSql unreadable) {
            return Set.of();
        }
        Reading reading = new Reading(module, "", Set.of(), earlier);
        statements(text.tokens()).forEach(reading::statement);
        return reading.defined;
    }

    /** One file: its refusals after its marker, if it carries one. */
    private static List<Refusal> lint(String sql, String module, String file, Set<String> released, Set<String> earlier, String release) {
        Tokenised text;
        try {
            text = tokenise(sql);
        } catch (UnreadableSql unreadable) {
            return List.of(new Refusal(module, file, Refused.UNREADABLE, unreadable.getMessage()));
        }
        List<Refusal> refusals = new ArrayList<>();
        Reading reading = new Reading(module, file, released, earlier);
        statements(text.tokens()).forEach(reading::statement);
        earlier.addAll(reading.defined);

        Marker marker = marker(text, module, file, release);
        refusals.addAll(marker.refusals());
        reading.refusals.stream().filter(refusal -> !marker.waived().contains(refusal.rule())).forEach(refusals::add);
        return refusals;
    }

    // -----------------------------------------------------------------
    // The marker
    // -----------------------------------------------------------------

    /**
     * What a file's marker answers: only the classes it names, so a reason written for one cannot cover another; and the data
     * fix it names, or {@code null}.
     */
    private record Marker(Set<Refused> waived, List<Refusal> refusals, SchemaVersion dataFix) {
        Marker(Set<Refused> waived, List<Refusal> refusals) {
            this(waived, refusals, null);
        }
    }

    private static Marker marker(Tokenised text, String module, String file, String release) {
        List<Refusal> refusals = new ArrayList<>();
        List<Matcher> found = new ArrayList<>();
        for (LineComment comment : text.comments()) {
            if (!MARKER_LIKE.matcher(comment.text()).matches()) {
                continue;
            }
            if (comment.afterTheFirstStatement()) {
                refusals.add(new Refusal(module, file, Refused.MALFORMED_MARKER, "a marker after the file's first statement"));
                continue;
            }
            Matcher parsed = MARKER.matcher(comment.text());
            if (!parsed.matches()) {
                refusals.add(new Refusal(module, file, Refused.MALFORMED_MARKER, comment.text()));
                continue;
            }
            found.add(parsed);
        }
        if (found.size() > 1) {
            refusals.add(new Refusal(module, file, Refused.MALFORMED_MARKER, "more than one marker"));
        }
        if (found.size() != 1 || !refusals.isEmpty()) {
            return new Marker(Set.of(), refusals);
        }
        Set<Refused> waived = EnumSet.noneOf(Refused.class);
        for (String named : found.getFirst().group(2).split(",")) {
            Optional<Refused> rule = Arrays.stream(Refused.values()).filter(known -> known.name().equals(named)).findFirst();
            if (rule.isEmpty() || !rule.get().waivedByAMarker()) {
                refusals.add(new Refusal(module, file, Refused.MALFORMED_MARKER, "waives " + named + ", which no marker can"));
                return new Marker(Set.of(), refusals);
            }
            waived.add(rule.get());
        }
        String release1 = found.getFirst().group(1);
        // The named release must already be out: release N's schema serves release N - 1's code, so a contract whose first
        // conforming release is not yet out breaks the release still running. While nothing is cut there is no N - 1.
        if (!ANY_RELEASE.equals(release1) && !NO_RELEASE.equals(release)
                && SchemaVersion.parse(release1).compareTo(SchemaVersion.parse(release)) > 0) {
            refusals.add(new Refusal(module, file, Refused.MARKER_AHEAD_OF_THE_RELEASE,
                    "names " + release1 + ", newer than the last release " + release));
            return new Marker(Set.of(), refusals);
        }
        String dataFix = found.getFirst().group(3);
        return new Marker(waived, refusals, dataFix == null ? null : SchemaVersion.parse(dataFix));
    }

    // -----------------------------------------------------------------
    // Reading statements
    // -----------------------------------------------------------------

    /** The statements of one file, in order. */
    private static final class Reading {
        private final String module;
        private final String file;
        private final Set<String> released;
        private final Set<String> earlier;
        private final Set<String> created = new HashSet<>();
        private final Set<String> defined = new HashSet<>();
        private final List<Refusal> refusals = new ArrayList<>();

        /** After this file moves its {@code search_path}, an unqualified {@code CREATE} may have made something elsewhere. */
        private boolean searchPathMoved;

        Reading(String module, String file, Set<String> released, Set<String> earlier) {
            this.module = module;
            this.file = file;
            this.released = released;
            this.earlier = earlier;
        }

        private void refuse(Refused rule, List<Token> where) {
            String opening = where.stream().limit(10).map(Token::text).collect(Collectors.joining(" "));
            refusals.add(new Refusal(module, file, rule, opening));
        }

        private boolean known(String key) {
            return created.contains(key) || earlier.contains(key);
        }

        void statement(List<Token> s) {
            String first = at(s, 0).word();
            switch (first) {
                case "DROP" -> refuse(Refused.DROP, s);
                case "TRUNCATE" -> refuse(Refused.TRUNCATE, s);
                case "DELETE" -> refuse(Refused.DELETE, s);
                case "ALTER" -> alter(s);
                case "CREATE" -> create(s);
                case "REVOKE" -> revoke(s);
                case "DO" -> refuse(Refused.OPAQUE_BLOCK, s);
                case "SET" -> searchPathMoved |= containsWord(s, "SEARCH_PATH");
                case "WITH" -> {
                    if (containsWord(s, "DELETE")) {
                        refuse(Refused.DELETE, s);
                    }
                }
                default -> {
                    if (!ADDITIVE_STATEMENTS.contains(first)) {
                        refuse(Refused.UNRECOGNISED, s);
                    }
                }
            }
            if (CALLING_STATEMENTS.contains(first)) {
                searchPathMoved |= containsWord(s, "SET_CONFIG");
                if (callsAFunctionOfItsOwn(s)) {
                    refuse(Refused.OPAQUE_BLOCK, s);
                }
            }
        }

        /** A function this module defined, called at the top level: a body nobody here read, run now, as a {@code DO} is. */
        private boolean callsAFunctionOfItsOwn(List<Token> s) {
            for (int index = 0; index < s.size(); index++) {
                Token token = s.get(index);
                if (token.kind() != Kind.WORD && token.kind() != Kind.QUOTED) {
                    continue;
                }
                Name called = name(s, index);
                if (at(s, called.next()).isPunct("(") && known("function:" + called.text())) {
                    return true;
                }
                index = called.next() - 1;
            }
            return false;
        }

        private void alter(List<Token> s) {
            switch (at(s, 1).word()) {
                case "TABLE" -> alterTable(s);
                case "TYPE" -> alterType(s);
                case "DOMAIN" -> alterDomain(s);
                default -> {
                    if (containsWord(s, "RENAME") || containsSequence(s, "SET", "SCHEMA")) {
                        refuse(Refused.RENAME, s);
                    } else if (containsWord(s, "DROP")) {
                        refuse(Refused.DROP, s);
                    } else {
                        refuse(Refused.UNRECOGNISED, s);
                    }
                }
            }
        }

        private void alterType(List<Token> s) {
            Name name = name(s, 2);
            Token verb = at(s, name.next());
            Token object = at(s, name.next() + 1);
            if (verb.is("ADD")) {
                return;
            }
            if (verb.is("RENAME") || verb.is("SET") && object.is("SCHEMA")) {
                refuse(Refused.RENAME, s);
            } else if (verb.is("DROP")) {
                refuse(Refused.DROP, s);
            } else if (verb.is("ALTER")) {
                refuse(Refused.TYPE_CHANGE, s);
            } else {
                refuse(Refused.UNRECOGNISED, s);
            }
        }

        private void alterDomain(List<Token> s) {
            Name name = name(s, 2);
            Token verb = at(s, name.next());
            Token object = at(s, name.next() + 1);
            if (verb.is("ADD")) {
                refuse(Refused.CHECK, s);
            } else if (verb.is("SET") && object.is("NOT")) {
                refuse(Refused.SET_NOT_NULL, s);
            } else if (verb.is("DROP")) {
                refuse(Refused.DROP, s);
            } else if (verb.is("RENAME") || verb.is("SET") && object.is("SCHEMA")) {
                refuse(Refused.RENAME, s);
            } else if (!(verb.is("SET") && object.is("DEFAULT") || verb.is("VALIDATE"))) {
                refuse(Refused.UNRECOGNISED, s);
            }
        }

        private void alterTable(List<Token> s) {
            int index = 2;
            if (at(s, index).is("IF") && at(s, index + 1).is("EXISTS")) {
                index += 2;
            }
            if (at(s, index).is("ONLY")) {
                index++;
            }
            if (at(s, index).is("ALL")) {
                refuse(Refused.UNRECOGNISED, s);
                return;
            }
            Name table = name(s, index);
            index = table.next();
            if (at(s, index).isPunct("*")) {
                index++;
            }
            boolean fresh = created.contains("table:" + table.text());
            for (List<Token> action : splitOnCommas(s.subList(Math.min(index, s.size()), s.size()))) {
                alterAction(action, s, fresh);
            }
        }

        private void alterAction(List<Token> a, List<Token> s, boolean fresh) {
            switch (at(a, 0).word()) {
                case "ADD" -> add(a, s, fresh);
                case "DROP" -> refuse(Refused.DROP, s);
                case "RENAME" -> refuse(Refused.RENAME, s);
                case "ALTER" -> alterColumn(a, s, fresh);
                case "SET" -> {
                    if (at(a, 1).is("SCHEMA")) {
                        refuse(Refused.RENAME, s);
                    } else if (!at(a, 1).isPunct("(")) {
                        refuse(Refused.UNRECOGNISED, s);
                    }
                }
                case "RESET", "VALIDATE" -> {}
                case "ENABLE", "DISABLE" -> {
                    if (containsWord(a, "ROW")) {
                        tighten(Refused.ROW_SECURITY, s, fresh);
                    } else if (containsWord(a, "TRIGGER")) {
                        tighten(Refused.TRIGGER, s, fresh);
                    } else {
                        refuse(Refused.UNRECOGNISED, s);
                    }
                }
                case "FORCE" -> tighten(Refused.ROW_SECURITY, s, fresh);
                default -> refuse(Refused.UNRECOGNISED, s);
            }
        }

        private void add(List<Token> a, List<Token> s, boolean fresh) {
            int index = 1;
            if (at(a, index).is("CONSTRAINT")) {
                index += 2;
            }
            switch (at(a, index).word()) {
                case "CHECK" -> tighten(Refused.CHECK, s, fresh);
                case "UNIQUE", "PRIMARY", "EXCLUDE" -> tighten(Refused.UNIQUE, s, fresh);
                case "FOREIGN" -> tighten(Refused.FOREIGN_KEY, s, fresh);
                default -> addColumn(a, s, fresh);
            }
        }

        private void addColumn(List<Token> a, List<Token> s, boolean fresh) {
            int index = 1;
            if (at(a, index).is("COLUMN")) {
                index++;
            }
            if (at(a, index).is("IF") && at(a, index + 1).is("NOT")) {
                index += 3;
            }
            index++; // past the column's own name, which may be spelt like anything
            Name type = name(a, index);
            List<Token> definition = a.subList(Math.min(type.next(), a.size()), a.size());
            List<Token> column = atDepthZero(definition);
            boolean notNull = containsSequence(column, "NOT", "NULL") || known("domain-not-null:" + type.text());
            boolean generated = SERIAL_TYPES.contains(at(a, index).word()) || containsSequence(column, "GENERATED", "ALWAYS")
                    || containsSequence(column, "GENERATED", "BY");
            if (notNull && !hasADefault(definition) && !generated) {
                tighten(Refused.NOT_NULL_WITHOUT_DEFAULT, s, fresh);
            }
            if (containsWord(column, "CHECK") || known("domain-check:" + type.text())) {
                tighten(Refused.CHECK, s, fresh);
            }
            if (containsWord(column, "UNIQUE") || containsWord(column, "PRIMARY")) {
                tighten(Refused.UNIQUE, s, fresh);
            }
            if (containsWord(column, "REFERENCES")) {
                tighten(Refused.FOREIGN_KEY, s, fresh);
            }
        }

        /** A default that is NULL is none, however it is written: {@code NULL}, {@code (NULL)}, {@code CAST(NULL AS text)}. */
        private static boolean hasADefault(List<Token> definition) {
            int depth = 0;
            for (int index = 0; index < definition.size(); index++) {
                Token token = definition.get(index);
                depth += token.isPunct("(") ? 1 : token.isPunct(")") ? -1 : 0;
                if (depth == 0 && token.is("DEFAULT") && !isNull(definition, index + 1)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isNull(List<Token> tokens, int start) {
            int index = start;
            while (at(tokens, index).isPunct("(")) {
                index++;
            }
            if (at(tokens, index).is("CAST") && at(tokens, index + 1).isPunct("(")) {
                index += 2;
                while (at(tokens, index).isPunct("(")) {
                    index++;
                }
            }
            return at(tokens, index).is("NULL");
        }

        private void alterColumn(List<Token> a, List<Token> s, boolean fresh) {
            int index = 1;
            if (at(a, index).is("CONSTRAINT")) {
                tighten(Refused.ALTERED_CONSTRAINT, s, fresh);
                return;
            }
            if (at(a, index).is("COLUMN")) {
                index++;
            }
            index++;
            Token verb = at(a, index);
            Token object = at(a, index + 1);
            if (verb.is("TYPE") || verb.is("SET") && object.is("DATA")) {
                refuse(Refused.TYPE_CHANGE, s);
            } else if (verb.is("SET") && object.is("NOT")) {
                tighten(Refused.SET_NOT_NULL, s, fresh);
            } else if (verb.is("SET") && object.is("GENERATED")) {
                // BY DEFAULT to ALWAYS refuses an explicit value with 428C9, which release N - 1 may still write.
                tighten(Refused.SET_GENERATED, s, fresh);
            } else if (verb.is("DROP") || verb.is("SET") && object.is("DEFAULT") && isNull(a, index + 2)) {
                refuse(Refused.DROP, s); // SET DEFAULT NULL is DROP DEFAULT by another name
            } else if (!(verb.is("RESET")
                    || verb.is("SET") && (object.is("DEFAULT") || object.is("STATISTICS") || object.is("STORAGE")
                            || object.is("COMPRESSION") || object.isPunct("(")))) {
                refuse(Refused.UNRECOGNISED, s);
            }
        }

        /** A tightening refuses nothing a release makes when the table it is on was created earlier in this very file. */
        private void tighten(Refused rule, List<Token> s, boolean fresh) {
            if (!fresh) {
                refuse(rule, s);
            }
        }

        private void create(List<Token> s) {
            int index = 1;
            boolean replace = false;
            if (at(s, 1).is("OR") && at(s, 2).is("REPLACE")) {
                replace = true;
                index = 3;
            }
            boolean temporary = false;
            while (TABLE_MODIFIERS.contains(at(s, index).word())) {
                temporary |= at(s, index).is("TEMP") || at(s, index).is("TEMPORARY");
                index++;
            }
            String kind = at(s, index).word();
            switch (kind) {
                case "TABLE" -> createTable(s, index + 1, temporary);
                case "SEQUENCE" -> {
                    if (!temporary && !(at(s, index + 1).is("IF") && at(s, index + 2).is("NOT"))) {
                        created.add("sequence:" + name(s, index + 1).text());
                    }
                }
                case "DOMAIN" -> domain(s, index + 1);
                case "UNIQUE" -> uniqueIndex(s, index);
                case "INDEX" -> {}
                case "CONSTRAINT", "TRIGGER" -> trigger(s, index);
                case "POLICY" -> onTable(Refused.ROW_SECURITY, s, index);
                case "RULE" -> onTable(Refused.RULE, s, index);
                case "VIEW" -> definition(s, "view", index + 1, replace);
                case "MATERIALIZED" -> definition(s, "view", index + 2, replace);
                default -> {
                    if (BODIES.contains(kind)) {
                        definition(s, "function", index + 1, replace);
                    } else if (!ADDITIVE_OBJECTS.contains(kind)) {
                        refuse(Refused.UNRECOGNISED, s);
                    }
                }
            }
        }

        private void createTable(List<Token> s, int start, boolean temporary) {
            int index = start;
            boolean ifAbsent = false;
            if (at(s, index).is("IF") && at(s, index + 1).is("NOT")) {
                ifAbsent = true;
                index += 3;
            }
            Name table = name(s, index);
            boolean partition = at(s, table.next()).is("PARTITION") && at(s, table.next() + 1).is("OF");
            if (partition) {
                // Its own columns and constraints apply to rows a write to the released parent routes here.
                Name parent = name(s, table.next() + 2);
                if (at(s, parent.next()).isPunct("(") && !created.contains("table:" + parent.text())) {
                    refuse(Refused.CHECK, s);
                }
            }
            // A table that may already exist, a partition of one that does, a temporary table hiding a released name, or one
            // made wherever a moved search_path points is not a table nothing released can touch.
            if (!ifAbsent && !partition && !temporary && (table.qualified() || !searchPathMoved)) {
                created.add("table:" + table.text());
            }
        }

        /** A domain that refuses a null or carries a check tightens the column that later takes it as its type. */
        private void domain(List<Token> s, int start) {
            Name name = name(s, start);
            List<Token> body = atDepthZero(s.subList(Math.min(name.next(), s.size()), s.size()));
            if (containsSequence(body, "NOT", "NULL")) {
                created.add("domain-not-null:" + name.text());
                defined.add("domain-not-null:" + name.text());
            }
            if (containsWord(body, "CHECK")) {
                created.add("domain-check:" + name.text());
                defined.add("domain-check:" + name.text());
            }
        }

        private void definition(List<Token> s, String kind, int start, boolean replace) {
            Name name = name(s, start);
            String key = kind + ":" + name.text();
            // A name still carrying a schema is another module's: this module's history cannot say it is a first definition.
            if (replace && (released.contains(key) || name.text().contains("."))) {
                refuse(Refused.REPLACED_BODY, s);
            }
            created.add(key);
            defined.add(key);
        }

        private void uniqueIndex(List<Token> s, int start) {
            int on = indexOf(s, "ON", start);
            if (on < 0) {
                refuse(Refused.UNRECOGNISED, s);
                return;
            }
            int table = at(s, on + 1).is("ONLY") ? on + 2 : on + 1;
            tighten(Refused.UNIQUE, s, created.contains("table:" + name(s, table).text()));
        }

        private void onTable(Refused rule, List<Token> s, int start) {
            int on = indexOf(s, "ON", start);
            if (on < 0) {
                refuse(Refused.UNRECOGNISED, s);
                return;
            }
            tighten(rule, s, created.contains("table:" + name(s, on + 1).text()));
        }

        private void trigger(List<Token> s, int start) {
            int on = indexOf(s, "ON", start);
            if (on < 0) {
                refuse(Refused.UNRECOGNISED, s);
                return;
            }
            // A trigger that fires on TRUNCATE alone: the application holds no TRUNCATE, so it can refuse nothing a release makes.
            int timing = start;
            while (timing < on && !(at(s, timing).is("BEFORE") || at(s, timing).is("AFTER") || at(s, timing).is("OF"))) {
                timing++;
            }
            boolean truncateOnly = on - timing == 2 && at(s, timing + 1).is("TRUNCATE");
            if (!truncateOnly) {
                tighten(Refused.TRIGGER, s, created.contains("table:" + name(s, on + 1).text()));
            }
        }

        private void revoke(List<Token> s) {
            int on = indexOf(s, "ON", 0);
            if (on < 0) {
                refuse(Refused.REVOKE, s);
                return;
            }
            int from = indexOf(s, "FROM", on);
            if (from < 0) {
                refuse(Refused.REVOKE, s);
                return;
            }
            String kind;
            int start = on + 1;
            switch (at(s, start).word()) {
                case "TABLE" -> {
                    kind = "table";
                    start++;
                }
                case "FUNCTION", "PROCEDURE", "ROUTINE" -> {
                    kind = "function";
                    start++;
                }
                case "SEQUENCE" -> {
                    kind = "sequence";
                    start++;
                }
                case "ALL", "SCHEMA", "DATABASE", "TYPE", "DOMAIN", "LANGUAGE", "FOREIGN", "LARGE", "TABLESPACE", "PARAMETER" -> {
                    refuse(Refused.REVOKE, s);
                    return;
                }
                default -> kind = "table";
            }
            for (List<Token> target : splitOnCommas(s.subList(start, from))) {
                if (!created.contains(kind + ":" + name(target, 0).text())) {
                    refuse(Refused.REVOKE, s);
                    return;
                }
            }
        }

        private Name name(List<Token> s, int start) {
            return nameIn(s, start, module);
        }
    }

    private record Name(String text, int next, boolean qualified) {}

    /** A possibly qualified name as PostgreSQL resolves it: unquoted folded, and this module's schema taken as unqualified. */
    private static Name nameIn(List<Token> s, int start, String module) {
        List<String> parts = new ArrayList<>();
        int index = start;
        parts.add(at(s, index).identifier());
        index++;
        while (at(s, index).isPunct(".")) {
            parts.add(at(s, index + 1).identifier());
            index += 2;
        }
        String text = parts.size() == 2 && parts.get(0).equals(module) ? parts.get(1) : String.join(".", parts);
        return new Name(text, index, parts.size() > 1);
    }

    private static Token at(List<Token> tokens, int index) {
        return index >= 0 && index < tokens.size() ? tokens.get(index) : Token.END;
    }

    private static boolean containsWord(List<Token> tokens, String word) {
        return tokens.stream().anyMatch(token -> token.is(word));
    }

    private static boolean containsSequence(List<Token> tokens, String first, String second) {
        for (int index = 0; index + 1 < tokens.size(); index++) {
            if (tokens.get(index).is(first) && tokens.get(index + 1).is(second)) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(List<Token> tokens, String word, int from) {
        int depth = 0;
        for (int index = from; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            depth += token.isPunct("(") ? 1 : token.isPunct(")") ? -1 : 0;
            if (depth == 0 && token.is(word)) {
                return index;
            }
        }
        return -1;
    }

    private static List<Token> atDepthZero(List<Token> tokens) {
        List<Token> kept = new ArrayList<>();
        int depth = 0;
        for (Token token : tokens) {
            if (token.isPunct("(")) {
                depth++;
            } else if (token.isPunct(")")) {
                depth--;
            } else if (depth == 0) {
                kept.add(token);
            }
        }
        return kept;
    }

    private static List<List<Token>> splitOnCommas(List<Token> tokens) {
        List<List<Token>> parts = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int depth = 0;
        for (Token token : tokens) {
            depth += token.isPunct("(") ? 1 : token.isPunct(")") ? -1 : 0;
            if (depth == 0 && token.isPunct(",")) {
                parts.add(current);
                current = new ArrayList<>();
            } else {
                current.add(token);
            }
        }
        parts.add(current);
        return parts;
    }

    private static List<List<Token>> statements(List<Token> tokens) {
        List<List<Token>> statements = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        for (Token token : tokens) {
            if (token.isPunct(";")) {
                if (!current.isEmpty()) {
                    statements.add(current);
                    current = new ArrayList<>();
                }
            } else {
                current.add(token);
            }
        }
        if (!current.isEmpty()) {
            statements.add(current);
        }
        return statements;
    }

    // -----------------------------------------------------------------
    // The tokeniser
    // -----------------------------------------------------------------

    private enum Kind {
        WORD,
        QUOTED,
        LITERAL,
        BODY,
        NUMBER,
        PUNCT,
        END
    }

    private record Token(Kind kind, String text) {
        static final Token END = new Token(Kind.END, "");

        boolean is(String word) {
            return kind == Kind.WORD && text.equals(word);
        }

        boolean isPunct(String punct) {
            return kind == Kind.PUNCT && text.equals(punct);
        }

        /** A keyword's text, or nothing for anything that is not a word: a quoted name is never a keyword. */
        String word() {
            return kind == Kind.WORD ? text : "";
        }

        /** PostgreSQL folds an unquoted name to lower case, and takes a quoted one as written. */
        String identifier() {
            return switch (kind) {
                case WORD -> text.toLowerCase(Locale.ROOT);
                case QUOTED -> text;
                default -> "?";
            };
        }
    }

    private record LineComment(String text, boolean afterTheFirstStatement) {}

    private record Tokenised(List<Token> tokens, List<LineComment> comments) {}

    /** The file cannot be read to its end. */
    private static final class UnreadableSql extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnreadableSql(String message) {
            super(message);
        }
    }

    private static Tokenised tokenise(String sql) {
        List<Token> tokens = new ArrayList<>();
        List<LineComment> comments = new ArrayList<>();
        int length = sql.length();
        int i = 0;
        while (i < length) {
            char c = sql.charAt(i);
            char next = i + 1 < length ? sql.charAt(i + 1) : '\0';
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && next == '-') {
                int end = sql.indexOf('\n', i);
                end = end < 0 ? length : end;
                comments.add(new LineComment(sql.substring(sql.lastIndexOf('\n', i) + 1, end).stripTrailing(), !tokens.isEmpty()));
                i = end;
            } else if (c == '/' && next == '*') {
                i = pastBlockComment(sql, i);
            } else if (c == '\'') {
                i = pastString(sql, i, false);
                tokens.add(new Token(Kind.LITERAL, "'...'"));
            } else if (c == '"') {
                i = quotedIdentifier(sql, i, tokens);
            } else if (c == '$' && dollarTagEnd(sql, i) > 0) {
                int tagEnd = dollarTagEnd(sql, i);
                String tag = sql.substring(i, tagEnd);
                int close = sql.indexOf(tag, tagEnd);
                if (close < 0) {
                    throw new UnreadableSql("an unterminated " + tag + " body");
                }
                tokens.add(new Token(Kind.BODY, tag + "..." + tag));
                i = close + tag.length();
            } else if (Character.isLetter(c) || c == '_') {
                int end = i + 1;
                while (end < length && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_' || sql.charAt(end) == '$')) {
                    end++;
                }
                String word = sql.substring(i, end).toUpperCase(Locale.ROOT);
                char after = end < length ? sql.charAt(end) : '\0';
                if (after == '\'' && Set.of("E", "B", "X", "N").contains(word)) {
                    i = pastString(sql, end, word.equals("E"));
                    tokens.add(new Token(Kind.LITERAL, "'...'"));
                } else if (word.equals("U") && after == '&' && end + 1 < length && sql.charAt(end + 1) == '\'') {
                    i = pastString(sql, end + 1, false);
                    tokens.add(new Token(Kind.LITERAL, "'...'"));
                } else if (word.equals("U") && after == '&' && end + 1 < length && sql.charAt(end + 1) == '"') {
                    i = quotedIdentifier(sql, end + 1, tokens);
                } else {
                    tokens.add(new Token(Kind.WORD, word));
                    i = end;
                }
            } else if (Character.isDigit(c)) {
                int end = i + 1;
                while (end < length && (Character.isDigit(sql.charAt(end)) || sql.charAt(end) == '.')) {
                    end++;
                }
                tokens.add(new Token(Kind.NUMBER, sql.substring(i, end)));
                i = end;
            } else {
                tokens.add(new Token(Kind.PUNCT, String.valueOf(c)));
                i++;
            }
        }
        return new Tokenised(tokens, comments);
    }

    /** The index past the {@code $tag$} opening at {@code start}, or -1 when this {@code $} opens no body (a {@code $1}). */
    private static int dollarTagEnd(String sql, int start) {
        int index = start + 1;
        if (index < sql.length() && Character.isDigit(sql.charAt(index))) {
            return -1;
        }
        while (index < sql.length() && (Character.isLetterOrDigit(sql.charAt(index)) || sql.charAt(index) == '_')) {
            index++;
        }
        return index < sql.length() && sql.charAt(index) == '$' ? index + 1 : -1;
    }

    private static int pastBlockComment(String sql, int start) {
        int depth = 0;
        int i = start;
        while (i < sql.length()) {
            if (sql.startsWith("/*", i)) {
                depth++;
                i += 2;
            } else if (sql.startsWith("*/", i)) {
                depth--;
                i += 2;
                if (depth == 0) {
                    return i;
                }
            } else {
                i++;
            }
        }
        throw new UnreadableSql("an unterminated block comment");
    }

    /** {@code start} is the opening quote. A doubled quote is one quote; a backslash escapes only in an {@code E''} string. */
    private static int pastString(String sql, int start, boolean backslashes) {
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (backslashes && c == '\\') {
                i += 2;
            } else if (c == '\'') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i += 2;
                } else {
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        throw new UnreadableSql("an unterminated string");
    }

    private static int quotedIdentifier(String sql, int start, List<Token> tokens) {
        StringBuilder text = new StringBuilder();
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '"') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '"') {
                    text.append('"');
                    i += 2;
                } else {
                    tokens.add(new Token(Kind.QUOTED, text.toString()));
                    return i + 1;
                }
            } else {
                text.append(c);
                i++;
            }
        }
        throw new UnreadableSql("an unterminated quoted name");
    }

    /** The floors of a {@code root} of the given modules: a planted repository's. */
    static Floor floor(String release, Map<String, String> versions) {
        SortedMap<String, SchemaVersion> floors = new TreeMap<>();
        versions.forEach((module, version) -> floors.put(module, SchemaVersion.parse(version)));
        return new Floor(release, floors);
    }
}
