package com.finapp.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Arrays;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies platform's position in the dependency graph: it sees sharedkernel below it, and
 * nothing above it (P0-TSK-002 acceptance criteria).
 *
 * <p>The upward assertion is the one that matters. A module reaching upward is how a
 * modular monolith quietly becomes a monolith, and it is far cheaper to fail here than to
 * discover it when the ledger can no longer be extracted or reasoned about in isolation.
 */
class PlatformModuleBoundaryTest {

    @Test
    @DisplayName("platform sees sharedkernel below it")
    void seesSharedKernel() {
        assertThat(classpathEntries())
                .as("sharedkernel output must be on platform's classpath")
                .anyMatch(entry -> isBuildOutputOf(entry, "sharedkernel"));
    }

    @Test
    @DisplayName("platform depends on no module above it")
    void dependsOnNoModuleAboveIt() {
        // platform sits below every business module and below app. Nothing above it may
        // appear on its classpath.
        assertThat(classpathEntries())
                .as("classpath must not contain output of modules above platform")
                .noneMatch(entry -> isBuildOutputOf(entry, "app"));
    }

    /**
     * True when a classpath entry is build output of the named Gradle module.
     *
     * <p>Matches on path <em>elements</em> rather than substrings. Substring matching is
     * unreliable here: {@code java.class.path} arrives with doubled separators on this
     * platform, and a plain {@code contains} would also match an unrelated directory such
     * as {@code sharedkernel-backup}. {@link Path} normalises separators and lets us assert
     * on the real structure {@code .../<module>/build/...}.
     */
    private static boolean isBuildOutputOf(String classpathEntry, String module) {
        Path path = Path.of(classpathEntry);
        for (int i = 0; i < path.getNameCount() - 1; i++) {
            if (path.getName(i).toString().equals(module)
                    && path.getName(i + 1).toString().equals("build")) {
                return true;
            }
        }
        return false;
    }

    /** Entries of {@code java.class.path}. */
    private static List<String> classpathEntries() {
        String classpath = System.getProperty("java.class.path");
        assertThat(classpath).as("java.class.path").isNotBlank();
        return Arrays.stream(classpath.split(File.pathSeparator)).toList();
    }
}
