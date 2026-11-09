package com.finapp.app.architecture;

import com.finapp.app.database.SchemaVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
     * form.
     */
    public static final String MARKER_FORM =
            "^-- finapp-contract: first-conforming-release=(ANY|[0-9]+\\.[0-9]+\\.[0-9]+); reason=(\\S.*)$";

    private static final Pattern MARKER = Pattern.compile(MARKER_FORM);

    private static final Pattern RELEASE_NAME = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");

    private static final Pattern MARKER_LIKE = Pattern.compile("^--\\s*finapp-contract\\b.*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern MIGRATION_FILE = Pattern.compile("V([0-9]+(?:[._][0-9]+)*)__.*\\.sql");

    private static final Set<String> SERIAL_TYPES = Set.of("SMALLSERIAL", "SERIAL", "BIGSERIAL", "SERIAL2", "SERIAL4", "SERIAL8");

    private static final Set<String> ADDITIVE_STATEMENTS =
            Set.of("GRANT", "COMMENT", "INSERT", "UPDATE", "DELETE", "SELECT", "WITH", "SET", "RESET", "LOCK", "ANALYZE");

    private static final Set<String> ADDITIVE_OBJECTS =
            Set.of("TYPE", "DOMAIN", "SCHEMA", "EXTENSION", "CAST", "OPERATOR", "COLLATION");

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
        MARKER_AHEAD_OF_THE_RELEASE;

        /** The refusals a contract marker can answer: a statement's, never the file's own form. */
        boolean waivedByAMarker() {
            return this != UNREADABLE && this != NOT_A_MIGRATION && this != MALFORMED_MARKER && this != MARKER_AHEAD_OF_THE_RELEASE;
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
            for (Path file : files(root, module)) {
                Matcher name = MIGRATION_FILE.matcher(file.getFileName().toString());
                if (name.matches()) {
                    ordered.put(SchemaVersion.parse(name.group(1)), file);
                } else {
                    refusals.add(new Refusal(module, file.getFileName().toString(), Refused.NOT_A_MIGRATION, "the file's name"));
                }
            }
            Set<String> released = new HashSet<>();
            SchemaVersion moduleFloor = floor.of(module);
            for (Map.Entry<SchemaVersion, Path> migration : ordered.entrySet()) {
                String fileName = migration.getValue().getFileName().toString();
                if (migration.getKey().compareTo(moduleFloor) <= 0) {
                    released.addAll(definitionsIn(read(migration.getValue()), module));
                    continue;
                }
                read.add(module + "/" + fileName);
                refusals.addAll(lint(read(migration.getValue()), module, fileName, released, floor.release()));
            }
        }
        return new Outcome(List.copyOf(read), List.copyOf(refusals));
    }

    private static Path directory(Path root, String module) {
        return root.resolve(module).resolve("src/main/resources/db/migration").resolve(module);
    }

    private static List<Path> files(Path root, String module) throws IOException {
        try (Stream<Path> files = Files.list(directory(root, module))) {
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
    private static Set<String> definitionsIn(String sql, String module) {
        Tokenised text;
        try {
            text = tokenise(sql);
        } catch (UnreadableSql unreadable) {
            return Set.of();
        }
        Reading reading = new Reading(module, "", Set.of());
        statements(text.tokens()).forEach(reading::statement);
        return reading.defined;
    }

    /** One file: its refusals after its marker, if it carries one. */
    private static List<Refusal> lint(String sql, String module, String file, Set<String> released, String release) {
        Tokenised text;
        try {
            text = tokenise(sql);
        } catch (UnreadableSql unreadable) {
            return List.of(new Refusal(module, file, Refused.UNREADABLE, unreadable.getMessage()));
        }
        List<Refusal> refusals = new ArrayList<>();
        Reading reading = new Reading(module, file, released);
        statements(text.tokens()).forEach(reading::statement);

        Marker marker = marker(text, module, file, release);
        refusals.addAll(marker.refusals());
        if (marker.valid()) {
            reading.refusals.stream().filter(refusal -> !refusal.rule().waivedByAMarker()).forEach(refusals::add);
        } else {
            refusals.addAll(reading.refusals);
        }
        return refusals;
    }

    // -----------------------------------------------------------------
    // The marker
    // -----------------------------------------------------------------

    private record Marker(boolean valid, List<Refusal> refusals) {}

    private static Marker marker(Tokenised text, String module, String file, String release) {
        List<Refusal> refusals = new ArrayList<>();
        List<String> found = new ArrayList<>();
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
            found.add(parsed.group(1));
        }
        if (found.size() > 1) {
            refusals.add(new Refusal(module, file, Refused.MALFORMED_MARKER, "more than one marker"));
        }
        if (found.size() != 1 || !refusals.isEmpty()) {
            return new Marker(false, refusals);
        }
        String named = found.getFirst();
        // The named release must already be out: release N's schema serves release N - 1's code, so a contract whose first
        // conforming release is not yet out breaks the release still running. While nothing is cut there is no N - 1.
        if (!ANY_RELEASE.equals(named) && !NO_RELEASE.equals(release)
                && SchemaVersion.parse(named).compareTo(SchemaVersion.parse(release)) > 0) {
            refusals.add(new Refusal(module, file, Refused.MARKER_AHEAD_OF_THE_RELEASE,
                    "names " + named + ", newer than the last release " + release));
            return new Marker(false, refusals);
        }
        return new Marker(true, refusals);
    }

    // -----------------------------------------------------------------
    // Reading statements
    // -----------------------------------------------------------------

    /** The statements of one file, in order. */
    private static final class Reading {
        private final String module;
        private final String file;
        private final Set<String> released;
        private final Set<String> created = new HashSet<>();
        private final Set<String> defined = new HashSet<>();
        private final List<Refusal> refusals = new ArrayList<>();

        Reading(String module, String file, Set<String> released) {
            this.module = module;
            this.file = file;
            this.released = released;
        }

        private void refuse(Refused rule, List<Token> where) {
            String opening = where.stream().limit(10).map(Token::text).collect(Collectors.joining(" "));
            refusals.add(new Refusal(module, file, rule, opening));
        }

        void statement(List<Token> s) {
            switch (at(s, 0).word()) {
                case "DROP" -> refuse(Refused.DROP, s);
                case "TRUNCATE" -> refuse(Refused.TRUNCATE, s);
                case "ALTER" -> alter(s);
                case "CREATE" -> create(s);
                case "REVOKE" -> revoke(s);
                case "DO" -> refuse(Refused.OPAQUE_BLOCK, s);
                default -> {
                    if (!ADDITIVE_STATEMENTS.contains(at(s, 0).word())) {
                        refuse(Refused.UNRECOGNISED, s);
                    }
                }
            }
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
            List<Token> column = atDepthZero(a);
            boolean notNull = containsSequence(column, "NOT", "NULL");
            boolean defaulted = false;
            for (int index = 0; index < column.size(); index++) {
                if (column.get(index).is("DEFAULT") && !at(column, index + 1).is("NULL")) {
                    defaulted = true;
                }
            }
            boolean generated = containsWord(column, "GENERATED") || column.stream().anyMatch(token -> SERIAL_TYPES.contains(token.word()));
            if (notNull && !defaulted && !generated) {
                tighten(Refused.NOT_NULL_WITHOUT_DEFAULT, s, fresh);
            }
            if (containsWord(column, "CHECK")) {
                tighten(Refused.CHECK, s, fresh);
            }
            if (containsWord(column, "UNIQUE") || containsWord(column, "PRIMARY")) {
                tighten(Refused.UNIQUE, s, fresh);
            }
            if (containsWord(column, "REFERENCES")) {
                tighten(Refused.FOREIGN_KEY, s, fresh);
            }
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
            } else if (verb.is("DROP")) {
                refuse(Refused.DROP, s);
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
            while (TABLE_MODIFIERS.contains(at(s, index).word())) {
                index++;
            }
            String kind = at(s, index).word();
            switch (kind) {
                case "TABLE" -> createTable(s, index + 1);
                case "SEQUENCE" -> {
                    if (!(at(s, index + 1).is("IF") && at(s, index + 2).is("NOT"))) {
                        created.add("sequence:" + name(s, index + 1).text());
                    }
                }
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

        private void createTable(List<Token> s, int start) {
            int index = start;
            boolean ifAbsent = false;
            if (at(s, index).is("IF") && at(s, index + 1).is("NOT")) {
                ifAbsent = true;
                index += 3;
            }
            Name table = name(s, index);
            boolean partition = at(s, table.next()).is("PARTITION") && at(s, table.next() + 1).is("OF");
            // A table that may already exist, or a partition of one that does, is not one nothing released can touch.
            if (!ifAbsent && !partition) {
                created.add("table:" + table.text());
            }
        }

        private void definition(List<Token> s, String kind, int start, boolean replace) {
            Name name = name(s, start);
            String key = kind + ":" + name.text();
            if (replace && released.contains(key)) {
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
            List<String> parts = new ArrayList<>();
            int index = start;
            parts.add(at(s, index).identifier());
            index++;
            while (at(s, index).isPunct(".")) {
                parts.add(at(s, index + 1).identifier());
                index += 2;
            }
            String text = parts.size() == 2 && parts.get(0).equals(module) ? parts.get(1) : String.join(".", parts);
            return new Name(text, index);
        }
    }

    private record Name(String text, int next) {}

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
