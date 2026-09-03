package com.finapp.app.architecture;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Finds {@code synchronized} blocks in compiled production code.
 *
 * <h2>Why this is not an ArchUnit rule</h2>
 *
 * <p>ArchUnit models field and method <em>accesses</em>. A {@code synchronized}
 * <strong>method</strong> is an access flag and it sees that; a {@code synchronized}
 * <strong>block</strong> is a {@code MONITORENTER} instruction and it is blind to it. Verified by
 * probe rather than assumed: a class with both was imported, and the block method reported
 * {@code modifiers=[]} — no signal at all, and no call or field access distinguishing it from any
 * other read.
 *
 * <p>{@code P0-TSK-041}'s acceptance criterion is explicit that the build must fail on a planted
 * {@code synchronized} block, so the check needs instruction-level access. That is what ASM is for.
 *
 * <h2>Why a block matters as much as a method</h2>
 *
 * <p>Both take a lock that exists in <em>one JVM</em>. With N instances — and ADR-0014 says N is
 * never 1 — a lock held in one process says nothing to the other nine, so the invariant it appears
 * to protect is protected in none of them. That is worse than no lock at all, because the code
 * reads as though the race was handled.
 *
 * <h2>What it scans, and the defect that taught it</h2>
 *
 * <p>Every main-output classpath entry, <strong>jars included</strong>. The first version walked
 * only {@code build/classes/java/main} directories — but a consumed module reaches a dependent on
 * the runtime classpath as a jar, so {@code platform} and {@code sharedkernel} were never scanned.
 * A {@code synchronized} block planted in {@code OutboxRelay} was not caught, and the vacuity guard
 * did not notice because it counted methods and {@code app}'s own classes were plenty.
 *
 * <p>The entries now come from {@link ProductionModules#mainOutputEntries()}, so the sweep and the
 * ArchUnit coverage guard cannot disagree about what production code is, and coverage is asserted
 * per <em>module</em> rather than by a count.
 */
final class MonitorInstructions {

    private MonitorInstructions() {}

    /** Every {@code owner#method} in production output that enters a monitor. */
    static Set<String> inProductionCode() {
        Set<String> found = new TreeSet<>();
        forEachProductionClass((module, bytes) -> scan(bytes, found));
        return found;
    }

    /**
     * The modules the sweep actually read a class from.
     *
     * <p>Compared against {@link ProductionModules#onClasspathWithProductionClasses()}, so a module
     * silently dropping out of the sweep fails the build — the failure a method count cannot see.
     */
    static Set<String> modulesScanned() {
        Set<String> modules = new LinkedHashSet<>();
        forEachProductionClass((module, bytes) -> modules.add(module));
        return new TreeSet<>(modules);
    }

    @FunctionalInterface
    private interface ClassSink {
        void accept(String module, byte[] bytes);
    }

    private static void forEachProductionClass(ClassSink sink) {
        for (Map.Entry<Path, String> entry : ProductionModules.mainOutputEntries().entrySet()) {
            Path path = entry.getKey();
            String module = entry.getValue();
            try {
                if (Files.isDirectory(path)) {
                    try (Stream<Path> files = Files.walk(path)) {
                        files.filter(f -> f.toString().endsWith(".class"))
                                .forEach(f -> sink.accept(module, read(f)));
                    }
                } else if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
                    try (JarFile jar = new JarFile(path.toFile())) {
                        for (JarEntry jarEntry : (Iterable<JarEntry>) jar.stream()::iterator) {
                            if (jarEntry.getName().endsWith(".class")) {
                                try (InputStream in = jar.getInputStream(jarEntry)) {
                                    sink.accept(module, in.readAllBytes());
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not scan " + path, e);
            }
        }
    }

    private static void scan(byte[] bytes, Set<String> found) {
        ClassVisitor visitor =
                new ClassVisitor(Opcodes.ASM9) {
                    private String owner = "";

                    @Override
                    public void visit(
                            int version,
                            int access,
                            String name,
                            String signature,
                            String superName,
                            String[] interfaces) {
                        owner = name.replace('/', '.');
                    }

                    @Override
                    public MethodVisitor visitMethod(
                            int access,
                            String name,
                            String descriptor,
                            String signature,
                            String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode == Opcodes.MONITORENTER) {
                                    found.add(owner + "#" + name);
                                }
                            }
                        };
                    }
                };
        // SKIP_FRAMES and SKIP_DEBUG: neither affects instructions, and skipping them makes the
        // sweep cheap enough to run on every build.
        new ClassReader(bytes).accept(visitor, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }
}
