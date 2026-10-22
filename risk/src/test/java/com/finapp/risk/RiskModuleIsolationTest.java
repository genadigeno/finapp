package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees the {@code risk} module exists to provide (`P13-TSK-001`, ADR-0107 decision 2).
 *
 * <p>Assertions about the <strong>build</strong>, not about behaviour - the {@code PartyModuleIsolationTest} idiom, and
 * the moment they fail is the moment the damage is cheap to undo.
 *
 * <p><strong>The edges are {@code platform} and {@code sharedkernel}, and nothing else.</strong> No ledger edge: risk
 * posts nothing, holds nothing, releases nothing of its own ({@code INV-RSK-02}), and reads the journal only through
 * {@code MonitoredLedger}, a port {@code app} implements - with no edge, no code here can name a posting service. No
 * business module: the seams the consumers declare ({@code transfers}, {@code crossborder}, {@code credit},
 * {@code payments}) are implemented in {@code app} as adapters over this module's API, and every domain an action
 * reaches ({@code identity}, {@code accounts}, {@code merchant}, {@code crossborder}, {@code payments}, {@code kyc}) is
 * a port. None of these fifteen refusals has a Gradle cycle behind it - each edge would configure cleanly if added; this
 * test is the only control. Every sibling's isolation test refuses {@code risk} from its side, because nothing depends
 * on it.
 *
 * <p><strong>The matcher is probed in-suite.</strong> A forbidden-list guard over a matcher that never matches passes
 * over any classpath; {@link #theMatcherCatchesWhatItMustAndNothingElse} plants the entries a real Gradle classpath
 * carries - jars, class directories, resources - for the refusals that matter most, and the lookalikes that must NOT
 * count, so the guard is proven able to fail before it is trusted to pass.
 */
@Tag("architecture")
@DisplayName("risk module isolation (P13-TSK-001)")
class RiskModuleIsolationTest {

    /** Every business module, the ledger, lending's arithmetic and the composition root - risk may see none of them. */
    static final List<String> FORBIDDEN = List.of(
            "party",
            "identity",
            "kyc",
            "consent",
            "ledger",
            "accounts",
            "transfers",
            "payments",
            "paymentmethods",
            "checkout",
            "merchant",
            "settlement",
            "reconciliation",
            "fx",
            "crossborder",
            "credit",
            "lending",
            "creditmath",
            "bnpl",
            "app");

    @Test
    @DisplayName("risk sees no business module, not the ledger, not creditmath and not the composition root - fifteen"
            + " refusals with no cycle behind them")
    void seesNoSiblingAndNoCompositionRoot() {
        for (String forbidden : FORBIDDEN) {
            assertThat(classpathEntries())
                    .as("risk must not depend on %s", forbidden)
                    .noneMatch(entry -> isBuildOutputOf(entry, forbidden));
        }
    }

    @Test
    @DisplayName("risk does depend on platform and sharedkernel - the documented direction, and the guard's non-vacuity")
    void dependsOnPlatformAndSharedkernel() {
        // Without this the forbidden-list assertion passes over a classpath containing nothing at all.
        for (String required : List.of("platform", "sharedkernel")) {
            assertThat(classpathEntries())
                    .as("risk must depend on %s", required)
                    .anyMatch(entry -> isBuildOutputOf(entry, required));
        }
    }

    @Test
    @DisplayName("the matcher catches the ledger's and identity's jars, payments' and kyc's class directories, app's"
            + " resources, and no lookalike - the planted probes")
    void theMatcherCatchesWhatItMustAndNothingElse() {
        String root = File.separator + "repo" + File.separator;
        String sep = File.separator;
        assertThat(isBuildOutputOf(root + "ledger" + sep + "build" + sep + "libs" + sep + "ledger.jar", "ledger"))
                .as("the ledger's jar is caught").isTrue();
        assertThat(isBuildOutputOf(root + "identity" + sep + "build" + sep + "libs" + sep + "identity.jar", "identity"))
                .as("identity's jar is caught").isTrue();
        assertThat(isBuildOutputOf(
                        root + "payments" + sep + "build" + sep + "classes" + sep + "java" + sep + "main", "payments"))
                .as("payments' class directory is caught").isTrue();
        assertThat(isBuildOutputOf(root + "kyc" + sep + "build" + sep + "classes" + sep + "java" + sep + "main", "kyc"))
                .as("kyc's class directory is caught").isTrue();
        assertThat(isBuildOutputOf(root + "app" + sep + "build" + sep + "resources" + sep + "main", "app"))
                .as("app's resources directory is caught").isTrue();
        assertThat(isBuildOutputOf(root + "riskledger" + sep + "build" + sep + "libs" + sep + "x.jar", "ledger"))
                .as("a module whose name merely contains a sibling's is not it").isFalse();
        assertThat(isBuildOutputOf(root + "ledger" + sep + "src" + sep + "main", "ledger"))
                .as("a source directory is not build output").isFalse();
        // And the guard is wired to the list it claims: the refusals that matter most are forbidden, the two edges not.
        assertThat(FORBIDDEN)
                .contains("ledger", "identity", "payments", "kyc", "transfers", "crossborder", "credit", "app")
                .doesNotContain("platform", "sharedkernel");
    }

    /**
     * True when a classpath entry is build output of the named Gradle module. Matches on path <em>elements</em> rather
     * than substrings, for the reason {@code SharedKernelIsolationTest} records.
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
