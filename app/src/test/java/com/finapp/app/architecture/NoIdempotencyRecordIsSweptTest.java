package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * No statement in production deletes an idempotency record but the one sweep function, and that function waits for the posting
 * scope's uniqueness (`P15-TSK-029`, the ruling of `P15-TSK-002` on debt row #61; since `P16-TSK-011`, ADR-0141).
 *
 * <p>Until `P16-TSK-011` {@code journal_entry.idempotency_scope} was not {@code UNIQUE}, so the record was posting once-ness's
 * second rank and nothing could delete it. Ledger {@code V037} landed that uniqueness, and platform {@code V016} the sweep: since
 * then exactly one migration statement deletes from the table, inside {@code platform.sweep_expired_idempotency_records}, whose
 * body refuses before its delete unless the journal's posting scope is uniquely indexed; and no compiled class deletes, the
 * sweep calling that function. The class keeps its name and its members' names because the mutation register's rows cite
 * them (`MUTATION_TESTING.md` section 2); what each holds is in its display name. *(Until 2026-11-13 the class held that no
 * migration deleted at all.)*
 *
 * <p><strong>Read from what runs.</strong> The class files' string constants, not source text, so a {@code static final}
 * table name concatenated in another class arrives folded into the statement; a recipe of runtime concatenation is read as
 * well, and a class holding a mention of the table beside any delete is refused whether or not the two were ever joined. The
 * migrations are read with their comments stripped. The grant ({@code V008}) is left as it stands; this holds that nothing
 * uses it.
 */
@Tag("architecture")
@DisplayName("no production statement deletes an idempotency record but the sweep function that waits for the posting scope's uniqueness"
        + " (P15-TSK-029, debt row #61, P16-TSK-011)")
class NoIdempotencyRecordIsSweptTest {

    /** The one migration whose statement deletes an idempotency record, and the function that statement is the body of. */
    private static final String SWEEP_MIGRATION = "V016__the_idempotency_record_sweep.sql";

    private static final String SWEEP_FUNCTION = "platform.sweep_expired_idempotency_records";

    /** The ledger migration that makes the posting scope unique, which the sweep's own guard reads from the catalogue. */
    private static final String UNIQUENESS_MIGRATION = "V037__one_journal_entry_per_posting_scope.sql";

    private static final String TABLE = "idempotency_record";

    private static final Pattern DELETE_OR_TRUNCATE =
            Pattern.compile("\\b(DELETE\\s+FROM|TRUNCATE)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern DELETES_THE_TABLE = Pattern.compile(
            "\\b(?:DELETE\\s+FROM|TRUNCATE(?:\\s+TABLE)?)\\s+(?:ONLY\\s+)?(?:\"?\\w+\"?\\s*\\.\\s*)?\"?" + TABLE + "\"?",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("no production class holds a delete beside the table's name")
    void noProductionClassDeletesAnIdempotencyRecord() {
        TreeMap<String, List<String>> offenders = new TreeMap<>();
        MonitorInstructions.forEachProductionClass((module, bytes) -> {
            List<String> constants = stringConstantsOf(bytes);
            if (holdsADeleteOfTheTable(constants)) {
                offenders.put(module + ":" + new ClassReader(bytes).getClassName(), constants);
            }
        });

        assertThat(offenders.keySet())
                .as("no compiled class may delete from platform.idempotency_record: the sweep calls its function, which alone deletes")
                .isEmpty();
    }

    @Test
    @DisplayName("the class scan reads every module's production code, and it sees the table named where it really is")
    void theClassScanSeesWhatItClaims() {
        Set<String> holders = new TreeSet<>();
        MonitorInstructions.forEachProductionClass((module, bytes) -> {
            if (stringConstantsOf(bytes).stream().anyMatch(constant -> constant.toLowerCase(Locale.ROOT).contains(TABLE))) {
                holders.add(new ClassReader(bytes).getClassName());
            }
        });

        assertThat(MonitorInstructions.modulesScanned()).isEqualTo(ProductionModules.onClasspathWithProductionClasses());
        assertThat(holders)
                .as("the idempotency store names its table, or this scan is reading nothing")
                .anyMatch(name -> name.startsWith("com/finapp/platform/idempotency/"));
    }

    @Test
    @DisplayName("no migration deletes an idempotency record but platform V016's sweep function, whose body refuses first unless the"
            + " posting scope is uniquely indexed")
    void noMigrationDeletesAnIdempotencyRecord() {
        List<String> offenders = new ArrayList<>();
        List<Path> migrations = migrations();
        int guarded = 0;
        for (Path migration : migrations) {
            String sql = withoutComments(read(migration));
            Matcher delete = DELETES_THE_TABLE.matcher(sql);
            while (delete.find()) {
                if (insideTheGuardedSweep(migration, sql, delete.start())) {
                    guarded++;
                } else {
                    offenders.add(migration + " at " + delete.start());
                }
            }
        }

        assertThat(migrations)
                .as("every module's migrations are read, the retention sweeps' among them")
                .hasSizeGreaterThan(100)
                .anyMatch(path -> path.getFileName().toString().startsWith("V012__retention_sweeps"))
                .anyMatch(path -> path.getFileName().toString().equals(UNIQUENESS_MIGRATION));
        assertThat(offenders).as("only the sweep function deletes an idempotency record, after its uniqueness guard").isEmpty();
        assertThat(guarded).as("the sweep's one delete is found where it is, so this reads what it claims").isEqualTo(1);
    }

    @Test
    @DisplayName("the uniqueness the sweep waits for is the ledger's: one unique index on journal_entry's idempotency_scope, over its census")
    void theUniquenessTheSweepWaitsForIsTheLedgers() {
        Path uniqueness = migrations().stream()
                .filter(path -> path.getFileName().toString().equals(UNIQUENESS_MIGRATION))
                .filter(path -> path.toString().replace('\\', '/').contains("ledger/src/main/resources/db/migration/ledger/"))
                .findFirst().orElseThrow();
        String sql = withoutComments(read(uniqueness)).replaceAll("\\s+", " ");
        assertThat(sql)
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS journal_entry_one_per_idempotency_scope ON ledger.journal_entry"
                        + " (idempotency_scope);")
                .contains("ADD CONSTRAINT posting_scope_census_found_no_duplicate CHECK");
        assertThat(sql.indexOf("posting_scope_census_found_no_duplicate"))
                .as("the census judged before the index").isLessThan(sql.indexOf("CREATE UNIQUE INDEX"));
    }

    @Test
    @DisplayName("the guard has teeth: a delete outside the function, or in it before its guard, or in another file, is refused")
    void theGuardHasTeeth() {
        Path sweep = Path.of("platform", "src", "main", "resources", "db", "migration", "platform", SWEEP_MIGRATION);
        String guard = "IF NOT EXISTS ( SELECT 1 FROM pg_catalog.pg_index i WHERE i.indisunique AND a.attname = 'idempotency_scope'"
                + " AND i.indrelid = pg_catalog.to_regclass('ledger.journal_entry') ) THEN RAISE EXCEPTION 'x'; END IF;";
        String guarded = "CREATE FUNCTION " + SWEEP_FUNCTION + "(g bigint, b integer) RETURNS integer AS $$ BEGIN " + guard
                + " DELETE FROM platform.idempotency_record d USING doomed; END; $$;";
        assertThat(insideTheGuardedSweep(sweep, guarded, guarded.indexOf("DELETE FROM"))).isTrue();

        String unguarded = guarded.replace(guard, "");
        assertThat(insideTheGuardedSweep(sweep, unguarded, unguarded.indexOf("DELETE FROM"))).as("no guard").isFalse();
        String late = "CREATE FUNCTION " + SWEEP_FUNCTION + "(g bigint, b integer) RETURNS integer AS $$ BEGIN"
                + " DELETE FROM platform.idempotency_record d USING doomed; " + guard + " END; $$;";
        assertThat(insideTheGuardedSweep(sweep, late, late.indexOf("DELETE FROM"))).as("the guard after the delete").isFalse();
        String outside = guarded + " DELETE FROM platform.idempotency_record;";
        assertThat(insideTheGuardedSweep(sweep, outside, outside.lastIndexOf("DELETE FROM"))).as("a top-level delete").isFalse();
        Path elsewhere = Path.of("platform", "src", "main", "resources", "db", "migration", "platform", "V012__retention_sweeps.sql");
        assertThat(insideTheGuardedSweep(elsewhere, guarded, guarded.indexOf("DELETE FROM"))).as("another file").isFalse();
    }

    @Test
    @DisplayName("the statement scan catches a delete however it is written, and passes one that is not of this table")
    void theStatementScanHasTeeth() {
        assertThat(deletesTheTable("DELETE FROM platform.idempotency_record WHERE expires_at < now()")).isTrue();
        assertThat(deletesTheTable("delete  from\n \"platform\" . \"idempotency_record\" i USING x")).isTrue();
        assertThat(deletesTheTable("DELETE FROM ONLY idempotency_record")).isTrue();
        assertThat(deletesTheTable("TRUNCATE TABLE platform.idempotency_record")).isTrue();
        assertThat(deletesTheTable("TRUNCATE platform.idempotency_record")).isTrue();

        assertThat(deletesTheTable("DELETE FROM platform.outbox_event e USING doomed")).isFalse();
        assertThat(deletesTheTable("SELECT state FROM platform.idempotency_record")).isFalse();
    }

    @Test
    @DisplayName("the class test catches a delete built from a constant, and passes the table named with no delete beside it")
    void theClassTestHasTeeth() {
        assertThat(holdsADeleteOfTheTable(List.of("DELETE FROM platform.idempotency_record WHERE state = ?"))).isTrue();
        assertThat(holdsADeleteOfTheTable(List.of("platform.idempotency_record", "DELETE FROM \u0001 WHERE expires_at < now()"))).isTrue();

        assertThat(holdsADeleteOfTheTable(List.of("SELECT * FROM platform.idempotency_record"))).isFalse();
        assertThat(holdsADeleteOfTheTable(List.of("DELETE FROM platform.outbox_event"))).isFalse();
    }

    // ------------------------------------------------------------------------------------------------------------------

    private static boolean holdsADeleteOfTheTable(List<String> constants) {
        boolean namesTheTable = constants.stream().anyMatch(constant -> constant.toLowerCase(Locale.ROOT).contains(TABLE));
        boolean deletes = constants.stream().anyMatch(constant -> DELETE_OR_TRUNCATE.matcher(constant).find());
        return namesTheTable && deletes;
    }

    /**
     * Whether the delete at {@code at} is the guarded sweep's: in platform's {@code V016}, inside the body of
     * {@code platform.sweep_expired_idempotency_records}, after that body's guard, which reads the catalogue for a unique index on
     * {@code ledger.journal_entry}'s {@code idempotency_scope} and raises when there is none.
     */
    private static boolean insideTheGuardedSweep(Path migration, String sql, int at) {
        String path = migration.toString().replace('\\', '/');
        if (!migration.getFileName().toString().equals(SWEEP_MIGRATION) || !path.contains("platform/src/main/resources/db/migration/platform")) {
            return false;
        }
        int function = sql.indexOf("CREATE FUNCTION " + SWEEP_FUNCTION);
        int start = function < 0 ? -1 : sql.indexOf("$$", function);
        int end = start < 0 ? -1 : sql.indexOf("$$", start + 2);
        if (start < 0 || end < 0 || at < start || at > end) {
            return false;
        }
        String body = sql.substring(start, at);
        int guard = body.indexOf("IF NOT EXISTS");
        return guard >= 0
                && body.indexOf("pg_catalog.pg_index", guard) >= 0
                && body.indexOf("indisunique", guard) >= 0
                && body.indexOf("'idempotency_scope'", guard) >= 0
                && body.indexOf("to_regclass('ledger.journal_entry')", guard) >= 0
                && body.indexOf("RAISE EXCEPTION", guard) >= 0;
    }

    private static boolean deletesTheTable(String sql) {
        return DELETES_THE_TABLE.matcher(sql).find();
    }

    /** Every {@code String} a class's code can load, with invokedynamic's concatenation recipes among them. */
    private static List<String> stringConstantsOf(byte[] bytes) {
        List<String> constants = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if (value instanceof String text) {
                    constants.add(text);
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String text) {
                            constants.add(text);
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String indyName, String indyDescriptor, Handle bootstrap, Object... arguments) {
                        for (Object argument : arguments) {
                            if (argument instanceof String text) {
                                constants.add(text);
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return constants;
    }

    /** Every module's migrations, found from the root: a new module's are read without anyone adding it here. */
    private static List<Path> migrations() {
        Path root = RepositoryPaths.locate("settings.gradle.kts").getParent();
        try (Stream<Path> modules = Files.list(root)) {
            List<Path> found = new ArrayList<>();
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path directory = module.resolve("src/main/resources/db/migration");
                if (Files.isDirectory(directory)) {
                    try (Stream<Path> files = Files.walk(directory)) {
                        files.filter(file -> file.toString().endsWith(".sql")).forEach(found::add);
                    }
                }
            }
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String withoutComments(String sql) {
        Matcher line = Pattern.compile("--[^\\n]*").matcher(sql);
        return line.replaceAll("");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
