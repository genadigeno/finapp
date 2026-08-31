package com.finapp.app.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * What the architecture rules are supposed to be looking at, derived from the classpath rather
 * than from a list somebody maintains.
 *
 * <p><strong>Why this exists as one shared thing.</strong> Every ArchUnit rule is vacuously
 * satisfied over classes that were never imported, so each rule suite needs a guard proving its
 * analysis actually reached the code it claims to protect. A guard is only as good as the
 * expectation it compares against, and an expectation written by hand drifts: the
 * {@code P0-TSK-007} review found a guard asserting merely that <em>some</em> module was seen,
 * and the {@code P0-TSK-008} review found a second one asserting merely that {@code Money} was
 * seen — which stayed green while a whole module, and a {@code double} planted inside it, fell
 * out of the sweep.
 *
 * <p>Deriving the expectation from the classpath is what makes the guard self-maintaining: a
 * module added to the build is protected without anyone remembering to add it here, and a
 * module that silently stops being analysed fails the build.
 */
final class ProductionModules {

    private static final String ROOT = "com.finapp.";

    private ProductionModules() {
        // Static helper for the architecture tests; not instantiable.
    }

    /**
     * The module a class belongs to — the segment after {@code com.finapp} — or {@code null} if
     * it is not one of ours.
     */
    static String of(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        if (!packageName.startsWith(ROOT)) {
            return null;
        }
        String remainder = packageName.substring(ROOT.length());
        int dot = remainder.indexOf('.');
        return dot < 0 ? remainder : remainder.substring(0, dot);
    }

    /**
     * Modules whose build output is on the classpath and contains at least one class other than
     * {@code package-info}. A module holding only {@code package-info} contributes nothing for
     * ArchUnit to import, which is why it is excluded rather than treated as a failure.
     */
    static Set<String> onClasspathWithProductionClasses() {
        Set<String> modules = new TreeSet<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path path = Path.of(entry);
            String module = moduleOwning(path);
            if (module == null || !isMainOutput(path)) {
                continue;
            }
            if (containsProductionClasses(path)) {
                modules.add(module);
            }
        }
        return modules;
    }

    /** The Gradle module a classpath entry belongs to: the element before {@code build}. */
    private static String moduleOwning(Path path) {
        for (int i = 0; i < path.getNameCount() - 1; i++) {
            if (path.getName(i + 1).toString().equals("build")) {
                return path.getName(i).toString();
            }
        }
        return null;
    }

    /** Main source output only — test output must not count towards analysed coverage. */
    private static boolean isMainOutput(Path path) {
        String normalised = path.toString().replace(File.separatorChar, '/');
        return normalised.contains("/build/classes/java/main") || normalised.contains("/build/libs/");
    }

    private static boolean containsProductionClasses(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    return files.anyMatch(f -> isProductionClassFile(f.getFileName().toString()));
                }
            }
            if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
                try (JarFile jar = new JarFile(path.toFile())) {
                    return jar.stream()
                            .map(JarEntry::getName)
                            .anyMatch(
                                    name ->
                                            isProductionClassFile(
                                                    name.substring(name.lastIndexOf('/') + 1)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not inspect classpath entry " + path, e);
        }
        return false;
    }

    private static boolean isProductionClassFile(String fileName) {
        return fileName.endsWith(".class") && !fileName.equals("package-info.class");
    }
}
