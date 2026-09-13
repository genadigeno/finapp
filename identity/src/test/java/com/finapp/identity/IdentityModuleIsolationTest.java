package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees the {@code identity} module exists to provide (P1-TSK-003).
 *
 * <p>These are assertions about the <strong>build</strong>, not about behaviour. They fail when
 * somebody adds a dependency to {@code identity/build.gradle.kts} — which is the moment the
 * damage is cheap to undo, rather than after aggregates on both sides of the boundary reference
 * each other. Same idiom, and same reasoning, as {@code SharedKernelIsolationTest}.
 *
 * <p><strong>Why {@code party} and {@code identity} must not see each other.</strong> ADR-0029
 * makes Party, Customer and Identity three aggregates in two modules, and {@code identity} holds
 * a {@code PartyId} <em>by value</em>. A compile-time dependency between them is the first step
 * toward the shared {@code users} table the ADR exists to prevent, and toward a cross-module
 * foreign key that would turn ADR-0001's stated escape — extracting a module — into a data
 * migration. ArchUnit's {@code entitiesAreNotReferencedAcrossModules} catches the reference;
 * this catches the dependency that would make one possible.
 */
@Tag("architecture")
@DisplayName("identity module isolation (P1-TSK-003)")
class IdentityModuleIsolationTest {

    @Test
    @DisplayName("identity sees neither its sibling business module nor the composition root")
    void seesNoSiblingAndNoCompositionRoot() {
        // `app` is the composition root and depends on this module. Seeing it here would mean the
        // dependency direction had been inverted. `consent` matters especially: consent is not
        // authentication and not authorization (INV-IDN-04), and an edge from here onto it is
        // the first step toward a session standing in for a lawful basis.
        for (String forbidden : List.of("party", "kyc", "consent", "ledger", "app")) {
            assertThat(classpathEntries())
                    .as("identity must not depend on %s", forbidden)
                    .noneMatch(entry -> isBuildOutputOf(entry, forbidden));
        }
    }

    @Test
    @DisplayName("identity does depend on platform, so the guard above is not vacuous")
    void dependsOnPlatform() {
        // Without this, the assertion above passes over a classpath that contains nothing at all -
        // which is the vacuity failure this repository has met five times. It also pins the
        // documented direction: identity -> platform -> sharedkernel.
        for (String required : List.of("platform", "sharedkernel")) {
            assertThat(classpathEntries())
                    .as("identity must depend on %s", required)
                    .anyMatch(entry -> isBuildOutputOf(entry, required));
        }
    }

    /**
     * True when a classpath entry is build output of the named Gradle module.
     *
     * <p>Matches on path <em>elements</em> rather than substrings, for the reason
     * {@code SharedKernelIsolationTest} records: a plain {@code contains} would also match an
     * unrelated directory such as {@code party-backup}.
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
