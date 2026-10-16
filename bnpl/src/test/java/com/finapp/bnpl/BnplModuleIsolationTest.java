package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees the {@code bnpl} module exists to provide (`P12-TSK-002`, ADR-0101 decision 6).
 *
 * <p>Assertions about the <strong>build</strong>, not about behaviour - the {@code PartyModuleIsolationTest} idiom, and
 * the moment they fail is the moment the damage is cheap to undo.
 *
 * <p><strong>The edges are the ledger and {@code creditmath}.</strong> Every bnpl posting is commanded through the
 * ledger's API and every obligation figure derived through it - never written ({@code INV-LED-04},
 * {@code INV-BNPL-01}); the reverse edge is a Gradle cycle and {@code LedgerModuleIsolationTest} refuses it from its own
 * side. {@code creditmath} brings lending's pure engines without lending ({@code CreditmathModuleIsolationTest} refuses
 * {@code bnpl} from the other side).
 *
 * <p><strong>Six refusals with no cycle behind them, named.</strong> {@code lending} - bnpl reuses lending's arithmetic
 * and patterns but never its {@code loan} and never a lending store (ADR-0101 decision 4; lending capital is reached
 * through {@code app}'s {@code BnplCapital}); {@code credit} - the decision, its consumption and the exposure lock are a
 * port {@code app} composes over credit's published ones; {@code merchant} - its standing, payable, fee schedule and
 * payouts (bnpl never pays a merchant out); {@code checkout} - the session and the order; {@code payments} - bnpl is not
 * a rail and creates no payment; {@code accounts} - the wallet. Each edge would configure cleanly if added; this test is
 * the only control. Every sibling's isolation test refuses {@code bnpl} from its side, because nothing depends on it.
 *
 * <p><strong>The matcher is probed in-suite.</strong> A forbidden-list guard over a matcher that never matches passes
 * over any classpath; {@link #theMatcherCatchesWhatItMustAndNothingElse} plants the entries a real Gradle classpath
 * carries for each of the six named refusals - jars, class directories, resources - and the lookalikes that must NOT
 * count, so the guard is proven able to fail before it is trusted to pass.
 */
@Tag("architecture")
@DisplayName("bnpl module isolation (P12-TSK-002)")
class BnplModuleIsolationTest {

    /** Every business module but the ledger, and the composition root - bnpl may see none of them. */
    static final List<String> FORBIDDEN = List.of(
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
            "crossborder",
            "credit",
            "lending",
            "app");

    @Test
    @DisplayName("bnpl sees no sibling business module but ledger - lending, credit, merchant, checkout, payments and"
            + " accounts named, whose refusals have no cycle behind them - and not the composition root")
    void seesNoSiblingButLedgerAndNoCompositionRoot() {
        for (String forbidden : FORBIDDEN) {
            assertThat(classpathEntries())
                    .as("bnpl must not depend on %s", forbidden)
                    .noneMatch(entry -> isBuildOutputOf(entry, forbidden));
        }
    }

    @Test
    @DisplayName("bnpl does depend on creditmath, ledger, platform and sharedkernel - the documented direction")
    void dependsOnCreditmathLedgerPlatformAndSharedkernel() {
        // The non-vacuity half of the guard above - without it, the forbidden-list assertion passes over a classpath
        // containing nothing at all - and the positive half of the ledger asymmetry.
        for (String required : List.of("creditmath", "ledger", "platform", "sharedkernel")) {
            assertThat(classpathEntries())
                    .as("bnpl must depend on %s", required)
                    .anyMatch(entry -> isBuildOutputOf(entry, required));
        }
    }

    @Test
    @DisplayName("the matcher catches lending's and credit's jars, merchant's and payments' class directories, checkout's"
            + " and accounts' resources, and no lookalike - the planted probes")
    void theMatcherCatchesWhatItMustAndNothingElse() {
        String root = File.separator + "repo" + File.separator;
        String sep = File.separator;
        assertThat(isBuildOutputOf(root + "lending" + sep + "build" + sep + "libs" + sep + "lending.jar", "lending"))
                .as("lending's jar is caught").isTrue();
        assertThat(isBuildOutputOf(root + "credit" + sep + "build" + sep + "libs" + sep + "credit.jar", "credit"))
                .as("credit's jar is caught").isTrue();
        assertThat(isBuildOutputOf(
                        root + "merchant" + sep + "build" + sep + "classes" + sep + "java" + sep + "main", "merchant"))
                .as("merchant's class directory is caught").isTrue();
        assertThat(isBuildOutputOf(
                        root + "payments" + sep + "build" + sep + "classes" + sep + "java" + sep + "main", "payments"))
                .as("payments' class directory is caught").isTrue();
        assertThat(isBuildOutputOf(root + "checkout" + sep + "build" + sep + "resources" + sep + "main", "checkout"))
                .as("checkout's resources directory is caught").isTrue();
        assertThat(isBuildOutputOf(root + "accounts" + sep + "build" + sep + "resources" + sep + "main", "accounts"))
                .as("accounts' resources directory is caught").isTrue();
        assertThat(isBuildOutputOf(root + "bnpllending" + sep + "build" + sep + "libs" + sep + "x.jar", "lending"))
                .as("a module whose name merely contains a sibling's is not it").isFalse();
        assertThat(isBuildOutputOf(root + "lending" + sep + "src" + sep + "main", "lending"))
                .as("a source directory is not build output").isFalse();
        // And the guard is wired to the list it claims: each of the six is actually forbidden, the two edges are not.
        assertThat(FORBIDDEN).contains("lending", "credit", "merchant", "checkout", "payments", "accounts")
                .doesNotContain("ledger", "creditmath");
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
