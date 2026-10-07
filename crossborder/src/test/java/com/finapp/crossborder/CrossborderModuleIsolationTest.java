package com.finapp.crossborder;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees the {@code crossborder} module exists to provide (P9-TSK-001).
 *
 * <p>Assertions about the <strong>build</strong>, not about behaviour - the
 * {@code PartyModuleIsolationTest} idiom, and the moment they fail is the moment the damage is
 * cheap to undo.
 *
 * <p><strong>The one edge this module is allowed is the ledger.</strong> The hold a cross-border
 * authorization places and the corridor accounts its postings name are reached through the
 * ledger's API - commanded and read, never written ({@code INV-LED-04}). This test pins the
 * positive half (the reverse edge is a Gradle cycle); {@code LedgerModuleIsolationTest}
 * forbids the negative half from its own side.
 *
 * <p><strong>{@code fx} is a refusal with no cycle behind it.</strong> The module that decides
 * who is paid must not compile against the module that prices and books (ADR-0079): the edge
 * would configure cleanly if added, and this test and its twin are the only controls.
 * {@code payments}, {@code kyc} and {@code accounts} are refused for the same reason:
 * execution, screening and the customer's wallet are reached through
 * {@code CrossBorderExecution}, {@code CounterpartyScreening} and
 * {@code CrossBorderParticipants}, ports {@code app} implements.
 *
 * <p><strong>The matcher is probed in-suite.</strong> A forbidden-list guard over a matcher that
 * never matches passes over any classpath; {@link #theMatcherCatchesWhatItMustAndNothingElse}
 * plants entries a real Gradle classpath carries - a sibling's jar, its class directory - and the
 * lookalikes that must NOT count (a module whose name merely contains another's), so the guard
 * is proven able to fail before it is trusted to pass.
 */
@Tag("architecture")
@DisplayName("crossborder module isolation (P9-TSK-001)")
class CrossborderModuleIsolationTest {

    @Test
    @DisplayName("crossborder sees no sibling business module but ledger - fx included, whose refusal"
            + " has no cycle behind it - and not the composition root")
    void seesNoSiblingButLedgerAndNoCompositionRoot() {
        for (String forbidden :
                List.of(
                        "party",
                        "identity",
                        "kyc",
                        "consent",
                        "accounts",
                        "transfers",
                        "payments",
                        "paymentmethods",
                        "checkout",
                        "merchant",
                        "settlement",
                        "reconciliation",
                        "fx",
                        "credit",
                        "app")) {
            assertThat(classpathEntries())
                    .as("crossborder must not depend on %s", forbidden)
                    .noneMatch(entry -> isBuildOutputOf(entry, forbidden));
        }
    }

    @Test
    @DisplayName("crossborder does depend on ledger, platform and sharedkernel - the documented direction")
    void dependsOnLedgerPlatformAndSharedkernel() {
        // The non-vacuity half of the guard above - without it, the forbidden-list assertion
        // passes over a classpath containing nothing at all - and the positive half of the
        // ledger asymmetry: crossborder -> ledger -> platform -> sharedkernel is pinned as the documented
        // direction.
        for (String required : List.of("ledger", "platform", "sharedkernel")) {
            assertThat(classpathEntries())
                    .as("crossborder must depend on %s", required)
                    .anyMatch(entry -> isBuildOutputOf(entry, required));
        }
    }

    @Test
    @DisplayName("the matcher catches a sibling's jar and class directory, and no lookalike -"
            + " the planted probes")
    void theMatcherCatchesWhatItMustAndNothingElse() {
        String root = File.separator + "repo" + File.separator;
        String sep = File.separator;
        assertThat(isBuildOutputOf(
                        root + "fx" + sep + "build" + sep + "libs" + sep + "fx.jar", "fx"))
                .as("a refused sibling's jar is caught").isTrue();
        assertThat(isBuildOutputOf(
                        root + "payments" + sep + "build" + sep + "classes" + sep + "java"
                                + sep + "main", "payments"))
                .as("a refused sibling's class directory is caught").isTrue();
        assertThat(isBuildOutputOf(
                        root + "crossborderpayments" + sep + "build" + sep + "libs" + sep + "x.jar",
                        "payments"))
                .as("a module whose name merely contains a sibling's is not it").isFalse();
        assertThat(isBuildOutputOf(
                        root + "payments" + sep + "src" + sep + "main", "payments"))
                .as("a source directory is not build output").isFalse();
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
