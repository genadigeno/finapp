package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees the {@code kyc} module exists to provide (P2-TSK-003).
 *
 * <p>Assertions about the <strong>build</strong>, not about behaviour — the
 * {@code PartyModuleIsolationTest} idiom, and the moment they fail is the moment the damage is
 * cheap to undo.
 *
 * <p><strong>Why {@code kyc} must not see its business siblings.</strong> {@code INV-KYC-05}
 * makes the verification decision this module's alone, with {@code party}'s customer status a
 * projection updated <em>in reaction</em> to it — through events and the composition root, never
 * through a compile-time edge. A dependency on {@code party} here is the first step toward the
 * decision being computed against (or worse, written into) the projection; one on
 * {@code identity} is the first step toward collapsing KYC into login
 * ({@code CLAUDE.md} §Domain Distinctions); and one on {@code consent} would entangle the
 * decision with its gate — the gate consults consent, cases do not.
 */
@Tag("architecture")
@DisplayName("kyc module isolation (P2-TSK-003)")
class KycModuleIsolationTest {

    @Test
    @DisplayName("kyc sees no sibling business module and not the composition root")
    void seesNoSiblingAndNoCompositionRoot() {
        for (String forbidden : List.of("party", "identity", "consent", "ledger", "accounts", "transfers", "payments", "paymentmethods", "app")) {
            assertThat(classpathEntries())
                    .as("kyc must not depend on %s", forbidden)
                    .noneMatch(entry -> isBuildOutputOf(entry, forbidden));
        }
    }

    @Test
    @DisplayName("kyc does depend on platform, so the guard above is not vacuous")
    void dependsOnPlatform() {
        // Without this, the assertion above passes over a classpath that contains nothing at
        // all. It also pins the documented direction: kyc -> platform -> sharedkernel.
        for (String required : List.of("platform", "sharedkernel")) {
            assertThat(classpathEntries())
                    .as("kyc must depend on %s", required)
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
