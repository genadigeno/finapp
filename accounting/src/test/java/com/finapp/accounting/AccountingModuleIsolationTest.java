package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees the {@code accounting} module exists to provide (`P14-TSK-001`, ADR-0113 decision 1;
 * {@code INV-ACC-06}'s and {@code INV-ACC-14}'s static halves).
 *
 * <p>Assertions about the <strong>build</strong>, not about behaviour - the {@code RiskModuleIsolationTest} idiom, and
 * the moment they fail is the moment the damage is cheap to undo.
 *
 * <p><strong>The edges are {@code platform} and {@code sharedkernel}, and nothing else.</strong> No ledger edge: the GL
 * is a function of the journal, not a store, and accounting reads the journal through {@code JournalReads}, commands the
 * cutoff through {@code PostingCutoff} and posts a prior-period adjustment through {@code PriorPeriodPostings} - ports
 * {@code app} implements - so with no edge no code here can name a posting service or a ledger table's writer. No
 * business module and not {@code risk}: the product proofs, the accruals, reconciliation's disclosure and fx's reference
 * rates are ports too. None of these refusals has a Gradle cycle behind it - each edge would configure cleanly if added;
 * this test is the only control. Every sibling's isolation test refuses {@code accounting} from its side, because
 * nothing but {@code app} depends on it.
 *
 * <p><strong>The matcher is probed in-suite.</strong> A forbidden-list guard over a matcher that never matches passes
 * over any classpath; {@link #theMatcherCatchesWhatItMustAndNothingElse} plants the entries a real Gradle classpath
 * carries - jars, class directories, resources - for the refusals that matter most, and the lookalikes that must NOT
 * count, so the guard is proven able to fail before it is trusted to pass.
 */
@Tag("architecture")
@DisplayName("accounting module isolation (P14-TSK-001)")
class AccountingModuleIsolationTest {

    /** Every business module, the ledger, lending's arithmetic, risk and the composition root - accounting sees none. */
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
            "risk",
            "app");

    @Test
    @DisplayName("accounting sees no business module, not the ledger, not risk and not the composition root - twenty-one"
            + " refusals with no cycle behind them")
    void seesNoSiblingAndNoCompositionRoot() {
        for (String forbidden : FORBIDDEN) {
            assertThat(classpathEntries())
                    .as("accounting must not depend on %s", forbidden)
                    .noneMatch(entry -> isBuildOutputOf(entry, forbidden));
        }
    }

    @Test
    @DisplayName("accounting does depend on platform and sharedkernel - the documented direction, and the guard's"
            + " non-vacuity")
    void dependsOnPlatformAndSharedkernel() {
        // Without this the forbidden-list assertion passes over a classpath containing nothing at all.
        for (String required : List.of("platform", "sharedkernel")) {
            assertThat(classpathEntries())
                    .as("accounting must depend on %s", required)
                    .anyMatch(entry -> isBuildOutputOf(entry, required));
        }
    }

    @Test
    @DisplayName("the matcher catches the ledger's and risk's jars, reconciliation's and fx's class directories, app's"
            + " resources, and no lookalike - the planted probes")
    void theMatcherCatchesWhatItMustAndNothingElse() {
        String root = File.separator + "repo" + File.separator;
        String sep = File.separator;
        assertThat(isBuildOutputOf(root + "ledger" + sep + "build" + sep + "libs" + sep + "ledger.jar", "ledger"))
                .as("the ledger's jar is caught").isTrue();
        assertThat(isBuildOutputOf(root + "risk" + sep + "build" + sep + "libs" + sep + "risk.jar", "risk"))
                .as("risk's jar is caught").isTrue();
        assertThat(isBuildOutputOf(root + "reconciliation" + sep + "build" + sep + "classes" + sep + "java" + sep
                        + "main", "reconciliation"))
                .as("reconciliation's class directory is caught").isTrue();
        assertThat(isBuildOutputOf(root + "fx" + sep + "build" + sep + "classes" + sep + "java" + sep + "main", "fx"))
                .as("fx's class directory is caught").isTrue();
        assertThat(isBuildOutputOf(root + "app" + sep + "build" + sep + "resources" + sep + "main", "app"))
                .as("app's resources directory is caught").isTrue();
        assertThat(isBuildOutputOf(root + "accountingledger" + sep + "build" + sep + "libs" + sep + "x.jar", "ledger"))
                .as("a module whose name merely contains a sibling's is not it").isFalse();
        assertThat(isBuildOutputOf(root + "ledger" + sep + "src" + sep + "main", "ledger"))
                .as("a source directory is not build output").isFalse();
        // And the guard is wired to the list it claims: the refusals that matter most are forbidden, the two edges not.
        assertThat(FORBIDDEN)
                .contains("ledger", "reconciliation", "fx", "lending", "bnpl", "risk", "identity", "app")
                .doesNotContain("platform", "sharedkernel", "accounting");
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
