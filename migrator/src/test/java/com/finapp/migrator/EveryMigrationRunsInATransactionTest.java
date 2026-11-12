package com.finapp.migrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.migration.JavaMigration;
import org.flywaydb.core.api.resource.LoadableResource;
import org.flywaydb.core.extensibility.Plugin;
import org.flywaydb.core.internal.database.DatabaseType;
import org.flywaydb.core.internal.database.DatabaseTypeRegister;
import org.flywaydb.core.internal.parser.ParsingContext;
import org.flywaydb.core.internal.scanner.Scanner;
import org.flywaydb.core.internal.sqlscript.SqlScript;
import org.flywaydb.core.internal.sqlscript.SqlScriptFactory;
import org.flywaydb.core.internal.sqlscript.SqlScriptMetadata;
import org.flywaydb.core.internal.sqlscript.SqlStatement;
import org.flywaydb.core.internal.sqlscript.SqlStatementIterator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Every migration the build or the image would apply runs inside a transaction ({@code P15-TSK-032}; {@code OPERATIONS_RUNBOOK.md}
 * section 11.4; {@code DATA_MIGRATIONS.md} section 4.4).
 *
 * <h2>Why</h2>
 *
 * <p>A migration Flyway runs outside a transaction is one a deployed environment can neither fail cleanly nor complete, on the
 * pinned Flyway with PostgreSQL's default transactional lock, which neither the build nor the image changes (the Gradle plugin
 * cannot set it without a JSON library on the build's classpath). Flyway writes a failed row inside the transaction that holds
 * that lock, and the transaction rolls back, so a failure leaves <em>no</em> row while its effect stays: {@code CREATE INDEX
 * CONCURRENTLY} leaves an {@code INVALID} index, and every later run is refused by it ("already exists") with nothing in the
 * history to say why. On rows that allow it, the same statement waits for every open transaction, Flyway's own lock-holding one
 * among them: the image's {@code lock_timeout} ends it after three seconds, {@code INVALID} again, and the developer's Gradle task
 * waits with no bound. Only a person's {@code DROP INDEX} clears the leftover, and no person reaches a terminal in a deployed
 * environment. {@code infra/scripts/migration-failure-rehearsal.sh} shows each of these on the image and on Gradle. (With the lock
 * taken per session instead, as Flyway advises for such migrations, a failure would leave a {@code success = false} row that only
 * {@code flywayRepair} removes, and the image has no repair.) So until the migrator gains a reviewed mode for these, the build
 * refuses such a migration.
 *
 * <h2>How: Flyway decides, not this class</h2>
 *
 * <p>Each module's directory is scanned by the pinned Flyway's own scanner, configured as the runner configures it
 * ({@link MigrationPlan#configurationFor}), and every SQL file it finds is read by Flyway's own PostgreSQL parser into Flyway's own
 * script, whose {@link SqlScript#executeInTransaction()} is the answer the migrate command acts on. So each form the pinned
 * version (13.7.0, read in its sources) accepts is covered by the same call:
 *
 * <ul>
 *   <li>a script configuration file beside the script, {@code V1__x.sql.conf} holding {@code executeInTransaction=false}, in any
 *       case, placeholders expanded ({@code SqlScriptMetadata});
 *   <li>a statement the PostgreSQL parser runs outside a transaction: {@code CREATE} or {@code DROP [UNIQUE] INDEX CONCURRENTLY},
 *       {@code CREATE} or {@code DROP} {@code DATABASE}, {@code TABLESPACE} or {@code SUBSCRIPTION}, {@code ALTER SYSTEM},
 *       {@code REINDEX SCHEMA}, {@code DATABASE} or {@code SYSTEM}, {@code VACUUM}, {@code DISCARD ALL}
 *       ({@code PostgreSQLParser.detectCanExecuteInTransaction}). {@code ALTER TYPE ... ADD VALUE} is read as the parser reads it
 *       when it cannot ask the server's version, outside a transaction: on PostgreSQL 12 and later Flyway would run it inside one,
 *       so here it is refused one form more than it must be, and the first migration that needs an enum value meets this sentence;
 *   <li>versioned, repeatable and callback scripts alike: every {@code .sql} the scanner finds, whatever its prefix.
 * </ul>
 *
 * <p>And the global setting, which reaches every script at once: the runner's own configuration; a migrating module's
 * {@code flyway} block naming a transaction setting, a way to load one, or code of its own that runs SQL
 * ({@code executeInTransaction}, {@code mixed}, {@code pluginConfiguration}, {@code configFiles}, {@code resolvers},
 * {@code callbacks}); the extension configured anywhere else (a second {@code flyway} block, a block in the root script, a
 * convention plugin or another module, {@code FlywayExtension} or {@code extensions} by name); a Flyway task configured or
 * registered (its type, {@code tasks.flywayMigrate}, {@code named("flyway...")}); a committed {@code gradle.properties} holding
 * {@code flyway.} or {@code systemProp.flyway.} or a {@code -Dflyway.} JVM argument (the plugin reads every project property
 * and every system property with that prefix); and a committed Flyway configuration file ({@code flyway.conf}, which the
 * plugin loads from the user's home and from its working directory, or {@code flyway.toml}). A developer's own shell, home
 * directory and command line are not the repository's, and are not read here; nor is {@code .claude/}, which holds other
 * checkouts of this repository.
 *
 * <p>Separately, {@link #theRunnerKeepsFlywaysTransactionalLock} holds the one Flyway setting the procedure's description of a
 * failure rests on: the image keeps Flyway's transactional PostgreSQL lock, under which a failed migration writes no row.
 *
 * <p>The expand-only lint ({@code MigrationLint}) already refuses any file in a migration directory that is not a versioned
 * script, so a {@code .conf} is refused twice; this rule is the one that says why, and that reads a statement as Flyway does.
 */
@DisplayName("every migration runs inside a transaction, until the migrator has a reviewed mode for one that cannot (P15-TSK-032)")
class EveryMigrationRunsInATransactionTest {

    /** Where a module's scripts live, relative to the module: {@code src/main/resources/db/migration/<module>}. */
    private static final String MIGRATIONS = "src/main/resources/db/migration";

    /**
     * The settings a flyway block could change the transaction mode with, directly, by loading a file or a map that does, or by
     * running code of its own that executes SQL.
     */
    private static final List<String> TRANSACTION_SETTINGS =
            List.of("executeInTransaction", "mixed", "pluginConfiguration", "configFiles", "resolvers", "callbacks");

    private static final Pattern FLYWAY_BLOCK = Pattern.compile("(?ms)^flyway \\{\\R(.*?)^}\\s*$");

    /** Any block configuring the extension, wherever it opens on a line. */
    private static final Pattern FLYWAY_BLOCK_OPENING = Pattern.compile("(?m)^\\s*flyway\\s*\\{");

    /** The extension configured other than by a module's one block. */
    private static final List<Pattern> EXTENSION_BY_NAME = List.of(
            Pattern.compile("\\bFlywayExtension\\b"),
            Pattern.compile("\\bextensions\\s*\\.\\s*\\w+\\s*(<[^>]*>)?\\s*\\(\\s*\"flyway\""));

    /** A Flyway task configured or registered: by its type, its accessor, or its name. */
    private static final List<Pattern> FLYWAY_TASK = List.of(
            Pattern.compile("\\bFlyway\\w*Task\\b"),
            Pattern.compile("\\btasks\\s*\\.\\s*flyway\\w*"),
            Pattern.compile("\\b(named|getByName|findByName|getting|existing|register|configure)\\s*(<[^>]*>)?\\s*\\(\\s*\"flyway\\w*\""),
            Pattern.compile("\\bval\\s+flyway\\w*\\s+by\\s+tasks\\b"),
            Pattern.compile("\\btasks\\s*\\[\\s*\"flyway"));

    /** A whole-line comment, or a block comment opening a line: never inside a string, which a build script's paths are full of. */
    private static final Pattern SCRIPT_COMMENT = Pattern.compile("(?ms)^[ \\t]*/\\*.*?\\*/|^[ \\t]*//[^\\n]*");

    /** {@code .claude} holds other checkouts of this repository (worktrees): their files are not this tree's. */
    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of(".git", ".gradle", ".idea", ".claude", "build", "out", "node_modules");

    /** Why a script or a setting was refused. */
    enum Refused {
        /** A statement Flyway's PostgreSQL parser runs outside a transaction. */
        STATEMENT,
        /** The script's configuration file sets {@code executeInTransaction=false}. */
        SCRIPT_CONFIGURATION,
        /** Flyway itself refuses the script, as it does one mixing both kinds of statement. */
        FLYWAY_REFUSES,
        /** A migrating module's flyway block names a transaction setting, a way to load one, or code that runs SQL. */
        BUILD_SETTING,
        /** The extension configured other than by a migrating module's one block. */
        EXTENSION_ELSEWHERE,
        /** A Flyway task configured or registered in a build script. */
        TASK_CONFIGURATION,
        /** A committed {@code gradle.properties} holds a {@code flyway.} key, a {@code systemProp.flyway.} key or a {@code -Dflyway.}. */
        GRADLE_PROPERTY,
        /** A committed Flyway configuration file. */
        CONFIGURATION_FILE,
        /** The migrator's own configuration runs outside a transaction, or mixes. */
        RUNNER
    }

    /** One refusal: the file, relative to the root, the reason, and what was read. */
    record Refusal(String file, Refused rule, String detail) {
        @Override
        public String toString() {
            return file + ": " + rule + " (" + detail + ")";
        }
    }

    /** What a check read, and what it refused. */
    record Outcome(List<String> scriptsRead, List<Refusal> refusals) {}

    @Test
    @DisplayName("every script of every module, read by the pinned Flyway as the runner configures it, runs inside a transaction")
    void everyRealMigrationRunsInATransaction() throws IOException {
        Path root = RepositoryFiles.root();
        Outcome outcome = check(root);

        long onDisk = 0;
        List<String> modules = modules(root);
        for (String module : modules) {
            try (Stream<Path> files = Files.walk(root.resolve(module).resolve(MIGRATIONS).resolve(module))) {
                onDisk += files.filter(file -> file.getFileName().toString().endsWith(".sql")).count();
            }
        }
        assertThat(modules).as("the walk found the migrating modules").contains("ledger", "platform").hasSizeGreaterThanOrEqualTo(21);
        assertThat(onDisk).as("the migrations on disk").isGreaterThan(300);
        assertThat(outcome.scriptsRead()).as("Flyway's scanner found every script on disk, so nothing passed unread").hasSize((int) onDisk);
        assertThat(outcome.refusals())
                .as("a migration outside a transaction can neither fail cleanly nor finish on the deployed runner, and what it leaves"
                        + " only a person can drop: build the index in the migration's transaction, as ledger V029 does"
                        + " (DATA_MIGRATIONS.md section 4.4)")
                .isEmpty();
    }

    @Test
    @DisplayName("each form the pinned Flyway accepts for a script outside a transaction is refused, through the path a real script takes,"
            + " and a script that only mentions one is not")
    void eachFormOfAScriptOutsideATransactionIsRefused(@TempDir Path root) throws IOException {
        Path planted = module(root, "plantedledger", "");
        write(planted, "V1__a_table.sql", "CREATE TABLE plantedledger.note (id integer PRIMARY KEY, reference text NOT NULL);\n");
        write(planted, "V2__an_index_built_concurrently.sql", "CREATE INDEX CONCURRENTLY note_reference ON plantedledger.note (reference);\n");
        write(planted, "V3__lower_case_and_a_comment.sql",
                "-- a header\ncreate /* between */ unique index concurrently if not exists note_unique on plantedledger.note (reference);\n");
        write(planted, "V4__configured_out.sql", "CREATE INDEX note_plain ON plantedledger.note (id);\n");
        write(planted, "V4__configured_out.sql.conf", "executeInTransaction=FALSE\n");
        write(planted, "V5__a_body_that_mentions_one.sql", """
                CREATE FUNCTION plantedledger.advice() RETURNS text LANGUAGE sql AS $$
                    SELECT 'CREATE INDEX CONCURRENTLY is refused here'
                $$;
                -- VACUUM, in a comment, runs nothing
                """);
        write(planted, "V6__vacuum.sql", "VACUUM ANALYZE plantedledger.note;\n");
        write(planted, "V7__both_kinds.sql",
                "ALTER TABLE plantedledger.note ADD COLUMN memo text;\nDROP INDEX CONCURRENTLY plantedledger.note_plain;\n");
        write(planted, "V8__an_enum_value.sql", "ALTER TYPE plantedledger.state ADD VALUE 'PARKED';\n");
        write(planted, "R__a_repeatable.sql", "ALTER SYSTEM SET work_mem = '64MB';\n");
        write(planted, "afterMigrate.sql", "DISCARD ALL;\n");
        write(planted.resolve("deeper"), "V9__in_a_subdirectory.sql", "REINDEX SCHEMA plantedledger;\n");

        Outcome outcome = check(root);

        assertThat(outcome.scriptsRead()).as("every planted script was read, the subdirectory's too, and no .conf as a script").hasSize(11);
        Map<String, Refused> refused = new TreeMap<>();
        outcome.refusals().forEach(refusal -> refused.put(refusal.file(), refusal.rule()));
        String at = "plantedledger/" + MIGRATIONS + "/plantedledger/";
        assertThat(refused).as("each form refused for its own reason, and nothing else refused").containsExactlyInAnyOrderEntriesOf(Map.of(
                at + "V2__an_index_built_concurrently.sql", Refused.STATEMENT,
                at + "V3__lower_case_and_a_comment.sql", Refused.STATEMENT,
                at + "V4__configured_out.sql", Refused.SCRIPT_CONFIGURATION,
                at + "V6__vacuum.sql", Refused.STATEMENT,
                at + "V7__both_kinds.sql", Refused.FLYWAY_REFUSES,
                at + "V8__an_enum_value.sql", Refused.STATEMENT,
                at + "R__a_repeatable.sql", Refused.STATEMENT,
                at + "afterMigrate.sql", Refused.STATEMENT,
                at + "deeper/V9__in_a_subdirectory.sql", Refused.STATEMENT));
        assertThat(outcome.refusals()).filteredOn(refusal -> refusal.file().endsWith("V2__an_index_built_concurrently.sql"))
                .extracting(Refusal::detail)
                .as("the refusal names the statement and its line")
                .containsExactly("line 1: CREATE INDEX CONCURRENTLY note_reference ON plantedledger.note (reference)");
    }

    @Test
    @DisplayName("a setting that would take every script out of a transaction is refused wherever the build or the runner would read it")
    void aGlobalSettingIsRefused(@TempDir Path root) throws IOException {
        module(root, "plantedpayments", "    mixed = true\n");
        write(module(root, "plantedfx", "    pluginConfiguration = mapOf(\"executeInTransaction\" to \"false\")\n"),
                "V1__a_table.sql", "CREATE TABLE plantedfx.rate (id integer PRIMARY KEY);\n");
        module(root, "plantedclean", "    // executeInTransaction = false, in a comment, is read as nothing\n");
        write(root, "gradle.properties", "org.gradle.jvmargs=-Xmx2g\nflyway.executeInTransaction=false\n");
        write(root.resolve("tools"), "gradle.properties", "# flyway.mixed=true, in a comment\norg.gradle.caching=true\n");
        write(root, "flyway.conf", "flyway.executeInTransaction=false\n");
        write(root.resolve("conf"), "flyway.toml", "[flyway]\nexecuteInTransaction = false\n");
        write(root.resolve("build"), "flyway.conf", "a build directory's own output is not the repository's\n");
        write(root.resolve(".claude/worktrees/another"), "flyway.conf", "another checkout's file is not this tree's\n");
        // The setting reached as a system property of the build's JVM, which the plugin reads with every other system property.
        write(root.resolve("plantedsys"), "gradle.properties", "systemProp.flyway.mixed=true\n");
        write(root.resolve("plantedjvm"), "gradle.properties", "org.gradle.jvmargs=-Xmx2g -Dflyway.executeInTransaction=false\n");
        // Code that runs SQL of its own, or loads it.
        module(root, "plantedresolver", "    resolvers = arrayOf(\"com.example.Resolver\")\n");
        module(root, "plantedcallback", "    callbacks = arrayOf(\"com.example.Callback\")\n");
        // The extension configured outside a module's one block.
        module(root, "plantedtwo", "");
        Files.writeString(root.resolve("plantedtwo/build.gradle.kts"), "\nflyway {\n    mixed = true\n}\n", StandardOpenOption.APPEND);
        write(root, "build.gradle.kts", "subprojects {\n    flyway {\n        mixed = true\n    }\n}\n");
        write(root.resolve("build-logic/src/main/kotlin"), "finapp.flyway-conventions.gradle.kts",
                "configure<org.flywaydb.gradle.FlywayExtension> {\n    mixed = true\n}\n");
        // A Flyway task configured: by its accessor, and by its type and name.
        module(root, "plantedtask", "");
        Files.writeString(root.resolve("plantedtask/build.gradle.kts"), "\ntasks.flywayMigrate {\n    mixed = true\n}\n",
                StandardOpenOption.APPEND);
        module(root, "plantedtyped", "");
        Files.writeString(root.resolve("plantedtyped/build.gradle.kts"),
                "\ntasks.named<org.flywaydb.gradle.task.FlywayMigrateTask>(\"flywayMigrate\") {\n    mixed = true\n}\n",
                StandardOpenOption.APPEND);
        // Each of those, in comments only: read as nothing.
        write(root.resolve("tools"), "build.gradle.kts",
                "// tasks.flywayMigrate { mixed = true } and FlywayExtension, in a comment\n/* flyway {\n    mixed = true\n} */\n"
                        + "val where = \"**/*.gradle.kts\"\n");

        Map<String, Refused> refused = new TreeMap<>();
        check(root).refusals().forEach(refusal -> refused.put(refusal.file(), refusal.rule()));

        assertThat(refused).as("each place refused for its own reason, and nothing else refused").containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("plantedpayments/build.gradle.kts", Refused.BUILD_SETTING),
                Map.entry("plantedfx/build.gradle.kts", Refused.BUILD_SETTING),
                Map.entry("plantedresolver/build.gradle.kts", Refused.BUILD_SETTING),
                Map.entry("plantedcallback/build.gradle.kts", Refused.BUILD_SETTING),
                Map.entry("plantedtwo/build.gradle.kts", Refused.EXTENSION_ELSEWHERE),
                Map.entry("build.gradle.kts", Refused.EXTENSION_ELSEWHERE),
                Map.entry("build-logic/src/main/kotlin/finapp.flyway-conventions.gradle.kts", Refused.EXTENSION_ELSEWHERE),
                Map.entry("plantedtask/build.gradle.kts", Refused.TASK_CONFIGURATION),
                Map.entry("plantedtyped/build.gradle.kts", Refused.TASK_CONFIGURATION),
                Map.entry("gradle.properties", Refused.GRADLE_PROPERTY),
                Map.entry("plantedsys/gradle.properties", Refused.GRADLE_PROPERTY),
                Map.entry("plantedjvm/gradle.properties", Refused.GRADLE_PROPERTY),
                Map.entry("flyway.conf", Refused.CONFIGURATION_FILE),
                Map.entry("conf/flyway.toml", Refused.CONFIGURATION_FILE)));
    }

    @Test
    @DisplayName("the runner runs each migration in a transaction of its own and refuses to mix")
    void theRunnerUsesTransactions() {
        FluentConfiguration runner = MigrationPlan.configurationFor("ledger", new PGSimpleDataSource(), Path.of("/migrator/sql"));
        assertThat(runner.isExecuteInTransaction()).as("Flyway's default, which nothing in the runner may change").isTrue();
        assertThat(runner.isMixed()).as("a script mixing both kinds is refused rather than run outside a transaction").isFalse();
        assertThat(runnerRefusals(runner)).isEmpty();
    }

    @Test
    @DisplayName("the runner keeps Flyway's transactional PostgreSQL lock, under which a failed migration writes no history row:"
            + " what OPERATIONS_RUNBOOK.md section 11.4 describes and the rehearsal shows")
    void theRunnerKeepsFlywaysTransactionalLock() throws ReflectiveOperationException {
        FluentConfiguration runner = MigrationPlan.configurationFor("ledger", new PGSimpleDataSource(), Path.of("/migrator/sql"));
        Plugin postgres = runner.getPluginRegister().getExact("PostgreSQLConfigurationExtension");
        assertThat(postgres).as("flyway-database-postgresql's configuration, as the image runs it").isNotNull();
        assertThat(postgres.getClass().getName()).isEqualTo("org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension");
        assertThat((boolean) postgres.getClass().getMethod("isTransactionalLock").invoke(postgres))
                .as("a per-session lock would change what a failure leaves (a success = false row) and what section 11.4 says to do")
                .isTrue();
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The rule: one path for the real tree and every plant.
    // ------------------------------------------------------------------------------------------------------------------

    /** Every refusal under {@code root}: the scripts of every migrating module, their build blocks, and the committed settings. */
    static Outcome check(Path root) throws IOException {
        List<String> read = new ArrayList<>();
        List<Refusal> refusals = new ArrayList<>();
        DatabaseType postgres = postgres();
        for (String module : modules(root)) {
            Path moduleRoot = root.resolve(module);
            FluentConfiguration configuration =
                    MigrationPlan.configurationFor(module, new PGSimpleDataSource(), moduleRoot.resolve(MIGRATIONS));
            runnerRefusals(configuration).forEach(detail -> refusals.add(new Refusal(module, Refused.RUNNER, detail)));
            Scanner<JavaMigration> scanner = new Scanner<>(JavaMigration.class, configuration, configuration.getLocations());
            SqlScriptFactory scripts = postgres.createSqlScriptFactory(configuration, new ParsingContext());
            for (LoadableResource resource : scanner.getResources("", configuration.getSqlMigrationSuffixes())) {
                String file = relative(root, Path.of(resource.getAbsolutePathOnDisk()));
                read.add(file);
                SqlScript script = scripts.createSqlScript(resource, configuration.isMixed(), scanner);
                boolean inATransaction;
                try {
                    inATransaction = script.executeInTransaction();
                } catch (FlywayException refusedByFlyway) {
                    refusals.add(new Refusal(file, Refused.FLYWAY_REFUSES, firstLine(refusedByFlyway.getMessage())));
                    continue;
                }
                if (!inATransaction) {
                    refusals.add(SqlScriptMetadata.getMetadataResource(scanner, resource) != null
                            ? new Refusal(file, Refused.SCRIPT_CONFIGURATION, "its script configuration sets executeInTransaction=false")
                            : new Refusal(file, Refused.STATEMENT, firstOutsideATransaction(script)));
                }
            }
            String build = Files.isRegularFile(moduleRoot.resolve("build.gradle.kts"))
                    ? Files.readString(moduleRoot.resolve("build.gradle.kts"), StandardCharsets.UTF_8)
                    : "";
            Matcher block = FLYWAY_BLOCK.matcher(build);
            if (block.find()) {
                String settings = block.group(1).replaceAll("(?m)//.*$", "");
                for (String setting : TRANSACTION_SETTINGS) {
                    if (Pattern.compile("\\b" + setting + "\\b").matcher(settings).find()) {
                        refusals.add(new Refusal(module + "/build.gradle.kts", Refused.BUILD_SETTING, setting));
                    }
                }
            }
        }
        List<String> migrating = modules(root);
        for (Path file : committedFiles(root)) {
            String name = file.getFileName().toString();
            String at = relative(root, file);
            if (name.equals("flyway.conf") || name.equals("flyway.toml")) {
                refusals.add(new Refusal(at, Refused.CONFIGURATION_FILE, "a Flyway configuration file"));
            } else if (name.equals("gradle.properties")) {
                Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                        .map(String::strip)
                        .filter(line -> !line.startsWith("#") && !line.startsWith("!"))
                        .filter(line -> line.startsWith("flyway.") || line.startsWith("systemProp.flyway.") || line.contains("-Dflyway."))
                        .forEach(line -> refusals.add(new Refusal(at, Refused.GRADLE_PROPERTY, line)));
            } else if (name.endsWith(".gradle.kts")) {
                refusals.addAll(scriptRefusals(at, Files.readString(file, StandardCharsets.UTF_8), migrating));
            }
        }
        return new Outcome(List.copyOf(read), List.copyOf(refusals));
    }

    /**
     * What a build script does to Flyway beyond a migrating module's one block: a second block, a block anywhere else, the
     * extension or a Flyway task configured by name. Whole-line and line-opening block comments are not read.
     */
    private static List<Refusal> scriptRefusals(String at, String script, List<String> migrating) {
        String text = SCRIPT_COMMENT.matcher(script).replaceAll("");
        List<Refusal> found = new ArrayList<>();
        boolean ownScript = at.endsWith("/build.gradle.kts") && at.indexOf('/') == at.lastIndexOf('/')
                && migrating.contains(at.substring(0, at.indexOf('/')));
        long blocks = FLYWAY_BLOCK_OPENING.matcher(text).results().count();
        if (ownScript && blocks > 1) {
            found.add(new Refusal(at, Refused.EXTENSION_ELSEWHERE, blocks + " flyway blocks, where the first is the one read"));
        } else if (!ownScript && blocks > 0) {
            found.add(new Refusal(at, Refused.EXTENSION_ELSEWHERE, "a flyway block outside a migrating module's own script"));
        }
        EXTENSION_BY_NAME.forEach(pattern -> pattern.matcher(text).results()
                .forEach(match -> found.add(new Refusal(at, Refused.EXTENSION_ELSEWHERE, match.group()))));
        FLYWAY_TASK.forEach(pattern -> pattern.matcher(text).results()
                .forEach(match -> found.add(new Refusal(at, Refused.TASK_CONFIGURATION, match.group()))));
        return found;
    }

    private static List<String> runnerRefusals(FluentConfiguration configuration) {
        List<String> found = new ArrayList<>();
        if (!configuration.isExecuteInTransaction()) {
            found.add("executeInTransaction=false");
        }
        if (configuration.isMixed()) {
            found.add("mixed=true");
        }
        return found;
    }

    /** The PostgreSQL support the build's URL is served by, from Flyway's own register, as a migration run picks it. */
    private static DatabaseType postgres() {
        return DatabaseTypeRegister.getDatabaseTypes().stream()
                .filter(DatabaseType.class::isInstance)
                .map(DatabaseType.class::cast)
                .filter(type -> type.getName().equals("PostgreSQL"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("flyway-database-postgresql is not on the test's runtime classpath"));
    }

    private static String firstOutsideATransaction(SqlScript script) {
        try (SqlStatementIterator statements = script.getSqlStatements()) {
            while (statements.hasNext()) {
                SqlStatement statement = statements.next();
                if (statement != null && !statement.canExecuteInTransaction()) {
                    return "line " + statement.getLineNumber() + ": " + statement.getSql().strip();
                }
            }
        }
        return "no single statement: Flyway's script as a whole";
    }

    /** Every module with a {@code src/main/resources/db/migration/<module>} directory, found by walking, never listed. */
    private static List<String> modules(Path root) throws IOException {
        try (Stream<Path> children = Files.list(root)) {
            return children
                    .filter(module -> Files.isDirectory(module.resolve(MIGRATIONS).resolve(module.getFileName().toString())))
                    .map(module -> module.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    private static List<Path> committedFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path directory, java.nio.file.attribute.BasicFileAttributes attributes) {
                return !directory.equals(root) && SKIPPED_DIRECTORIES.contains(directory.getFileName().toString())
                        ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                        : java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes) {
                files.add(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static String relative(Path root, Path file) {
        return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String firstLine(String message) {
        return message == null ? "" : message.lines().findFirst().orElse("");
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Plants.
    // ------------------------------------------------------------------------------------------------------------------

    /** A migrating module under {@code root} with a build script whose flyway block holds the module's settings and {@code extra}. */
    private static Path module(Path root, String name, String extra) throws IOException {
        Path directory = root.resolve(name).resolve(MIGRATIONS).resolve(name);
        Files.createDirectories(directory);
        write(root.resolve(name), "build.gradle.kts", "flyway {\n    schemas = arrayOf(\"" + name + "\")\n    cleanDisabled = true\n" + extra + "}\n");
        return directory;
    }

    private static Path write(Path directory, String name, String text) {
        try {
            Files.createDirectories(directory);
            return Files.writeString(directory.resolve(name), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
