package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees the {@code ledger} module exists to provide (P3-TSK-001).
 *
 * <p>Assertions about the <strong>build</strong>, not about behaviour — the
 * {@code PartyModuleIsolationTest} idiom, and the moment they fail is the moment the damage is
 * cheap to undo.
 *
 * <p><strong>Why {@code ledger} must not see any business sibling.</strong> The ledger is
 * commanded and does not react ({@code MODULE_ARCHITECTURE.md} §4): transfers, payments and every
 * later money-moving module request postings through its command API, and the ledger decides
 * whether and how they are written ({@code INV-LED-04}). A compile-time edge from the ledger toward
 * any of them is the first step toward accounting rules being computed against another module's
 * model - a second authority over what a posting means. The siblings forbid {@code ledger} in
 * turn, so the isolation holds in both directions rather than only this one (the
 * {@code P2-TSK-003} finding).
 *
 * <p><strong>{@code accounts} is the sharpest entry in the list</strong> (P3-TSK-011, ADR-0042):
 * it is the one sibling that legitimately depends on {@code ledger}, so this direction is the
 * only one a test still guards - the reverse edge is already a Gradle dependency cycle. The
 * accounting must never be shaped by the product built over it: the ledger knows
 * {@code owner_kind} and an opaque {@code owner_ref}, not what a Customer Account is.
 */
@Tag("architecture")
@DisplayName("ledger module isolation (P3-TSK-001)")
class LedgerModuleIsolationTest {

    @Test
    @DisplayName("ledger sees no sibling business module and not the composition root")
    void seesNoSiblingAndNoCompositionRoot() {
        for (String forbidden : List.of("party", "identity", "kyc", "consent", "accounts", "transfers", "app")) {
            assertThat(classpathEntries())
                    .as("ledger must not depend on %s", forbidden)
                    .noneMatch(entry -> isBuildOutputOf(entry, forbidden));
        }
    }

    @Test
    @DisplayName("ledger does depend on platform, so the guard above is not vacuous")
    void dependsOnPlatform() {
        // Without this, the assertion above passes over a classpath that contains nothing at all.
        // It also pins the documented direction: ledger -> platform -> sharedkernel.
        for (String required : List.of("platform", "sharedkernel")) {
            assertThat(classpathEntries())
                    .as("ledger must depend on %s", required)
                    .anyMatch(entry -> isBuildOutputOf(entry, required));
        }
    }

    /**
     * True when a classpath entry is build output of the named Gradle module. Matches on path
     * <em>elements</em> rather than substrings, for the reason {@code SharedKernelIsolationTest}
     * records.
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
