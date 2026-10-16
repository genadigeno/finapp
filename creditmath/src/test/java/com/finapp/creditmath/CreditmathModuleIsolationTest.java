package com.finapp.creditmath;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees the {@code creditmath} module exists to provide (`P12-TSK-002`; ADR-0101 decision 5,
 * {@code MODULE_ARCHITECTURE.md} M12).
 *
 * <p>Assertions about the <strong>build</strong>, not about behaviour - the {@code PartyModuleIsolationTest} idiom, and
 * the moment they fail is the moment the damage is cheap to undo.
 *
 * <p><strong>{@code sharedkernel} and nothing else.</strong> The engines are pure functions whose every figure is
 * replayed years later by the version its agreement pins ({@code INV-LND-05}): an edge to {@code platform} would bring a
 * clock, Spring and a database within an import's reach, and an edge to any business module would make the arithmetic
 * two contexts share depend on one of them - and {@code lending -> creditmath} or {@code bnpl -> creditmath} the
 * reverse of an edge already there, a cycle. So every module of the build but {@code sharedkernel} is refused, the
 * composition root among them. Its two dependants are pinned from their own sides ({@code LendingModuleIsolationTest},
 * {@code BnplModuleIsolationTest}); every other module's isolation test refuses {@code creditmath}.
 *
 * <p><strong>The matcher is probed in-suite.</strong> A forbidden-list guard over a matcher that never matches passes
 * over any classpath; {@link #theMatcherCatchesWhatItMustAndNothingElse} plants the entries a real Gradle classpath
 * carries for the refusals that matter most - {@code lending}'s jar, {@code bnpl}'s class directory, {@code platform}'s
 * resources - and the lookalikes that must NOT count, so the guard is proven able to fail before it is trusted to pass.
 */
@Tag("architecture")
@DisplayName("creditmath module isolation (P12-TSK-002)")
class CreditmathModuleIsolationTest {

    /** Every module of the build but sharedkernel - the arithmetic may see none of them. */
    static final List<String> FORBIDDEN = List.of(
            "platform",
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
            "bnpl",
            "app");

    @Test
    @DisplayName("creditmath sees no module but sharedkernel - not platform, not lending or bnpl, its two dependants,"
            + " and not the composition root")
    void seesNoModuleButSharedkernel() {
        for (String forbidden : FORBIDDEN) {
            assertThat(classpathEntries())
                    .as("creditmath must not depend on %s", forbidden)
                    .noneMatch(entry -> isBuildOutputOf(entry, forbidden));
        }
    }

    @Test
    @DisplayName("creditmath does depend on sharedkernel - the documented direction")
    void dependsOnSharedkernel() {
        // The non-vacuity half of the guard above - without it, the forbidden-list assertion passes over a classpath
        // containing nothing at all.
        assertThat(classpathEntries())
                .as("creditmath must depend on sharedkernel")
                .anyMatch(entry -> isBuildOutputOf(entry, "sharedkernel"));
    }

    @Test
    @DisplayName("the matcher catches lending's jar, bnpl's class directory and platform's resources, and no lookalike -"
            + " the planted probes")
    void theMatcherCatchesWhatItMustAndNothingElse() {
        String root = File.separator + "repo" + File.separator;
        String sep = File.separator;
        assertThat(isBuildOutputOf(root + "lending" + sep + "build" + sep + "libs" + sep + "lending.jar", "lending"))
                .as("a refused module's jar is caught").isTrue();
        assertThat(isBuildOutputOf(
                        root + "bnpl" + sep + "build" + sep + "classes" + sep + "java" + sep + "main", "bnpl"))
                .as("a refused module's class directory is caught").isTrue();
        assertThat(isBuildOutputOf(
                        root + "platform" + sep + "build" + sep + "resources" + sep + "main", "platform"))
                .as("a refused module's resources directory is caught").isTrue();
        assertThat(isBuildOutputOf(root + "creditmathlending" + sep + "build" + sep + "libs" + sep + "x.jar", "lending"))
                .as("a module whose name merely contains another's is not it").isFalse();
        assertThat(isBuildOutputOf(root + "lending" + sep + "src" + sep + "main", "lending"))
                .as("a source directory is not build output").isFalse();
        // And the guard is wired to the list it claims: each of the three is actually forbidden.
        assertThat(FORBIDDEN).contains("lending", "bnpl", "platform").doesNotContain("sharedkernel", "creditmath");
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
