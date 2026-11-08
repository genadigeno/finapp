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
 * No statement in production deletes an idempotency record (`P15-TSK-029`, the ruling of `P15-TSK-002` on debt row #61).
 *
 * <p>{@code journal_entry.idempotency_scope} is not {@code UNIQUE}, so that record is posting once-ness's second rank: sweeping
 * it would open a window for a second posting. Any later sweep lands that uniqueness first.
 *
 * <p><strong>Read from what runs.</strong> The class files' string constants, not source text, so a {@code static final}
 * table name concatenated in another class arrives folded into the statement; a recipe of runtime concatenation is read as
 * well, and a class holding a mention of the table beside any delete is refused whether or not the two were ever joined. The
 * migrations are read with their comments stripped. The grant ({@code V008}) is left as it stands; this holds that nothing
 * uses it.
 */
@Tag("architecture")
@DisplayName("no production statement deletes an idempotency record (P15-TSK-029, debt row #61)")
class NoIdempotencyRecordIsSweptTest {

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
                .as("debt row #61 is not settled: no statement may delete from platform.idempotency_record")
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
    @DisplayName("no migration deletes an idempotency record")
    void noMigrationDeletesAnIdempotencyRecord() {
        List<String> offenders = new ArrayList<>();
        List<Path> migrations = migrations();
        for (Path migration : migrations) {
            if (deletesTheTable(withoutComments(read(migration)))) {
                offenders.add(migration.toString());
            }
        }

        assertThat(migrations)
                .as("every module's migrations are read, the retention sweeps' among them")
                .hasSizeGreaterThan(100)
                .anyMatch(path -> path.getFileName().toString().startsWith("V012__retention_sweeps"));
        assertThat(offenders).as("debt row #61 is not settled").isEmpty();
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
