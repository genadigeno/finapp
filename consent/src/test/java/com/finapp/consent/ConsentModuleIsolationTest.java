package com.finapp.consent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees the {@code consent} module exists to provide (P2-TSK-003).
 *
 * <p>Assertions about the <strong>build</strong>, not about behaviour — the
 * {@code PartyModuleIsolationTest} idiom.
 *
 * <p><strong>Why {@code consent} must not see its business siblings.</strong> Consent is not
 * authentication and is not authorization ({@code CLAUDE.md} §Domain Distinctions,
 * {@code INV-IDN-04}), and a compile-time edge onto {@code identity} is the first step toward a
 * session or a permission quietly standing in for a lawful basis. The gated capabilities live in
 * <em>other</em> modules and consult consent through the composition root — dependency toward
 * this module's consumers here would invert that.
 */
@Tag("architecture")
@DisplayName("consent module isolation (P2-TSK-003)")
class ConsentModuleIsolationTest {

    @Test
    @DisplayName("consent sees no sibling business module and not the composition root")
    void seesNoSiblingAndNoCompositionRoot() {
        for (String forbidden : List.of("party", "identity", "kyc", "ledger", "app")) {
            assertThat(classpathEntries())
                    .as("consent must not depend on %s", forbidden)
                    .noneMatch(entry -> isBuildOutputOf(entry, forbidden));
        }
    }

    @Test
    @DisplayName("consent does depend on platform, so the guard above is not vacuous")
    void dependsOnPlatform() {
        // Without this, the assertion above passes over a classpath that contains nothing at
        // all. It also pins the documented direction: consent -> platform -> sharedkernel.
        for (String required : List.of("platform", "sharedkernel")) {
            assertThat(classpathEntries())
                    .as("consent must depend on %s", required)
                    .anyMatch(entry -> isBuildOutputOf(entry, required));
        }
    }

    /**
     * True when a classpath entry is build output of the named Gradle module. Matches on path
     * <em>elements</em> rather than substrings, for the reason
     * {@code SharedKernelIsolationTest} records.
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
