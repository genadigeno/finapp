package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees the {@code credit} module exists to provide (P10-TSK-001, ADR-0084).
 *
 * <p>Assertions about the <strong>build</strong>, not about behaviour - the
 * {@code PartyModuleIsolationTest} idiom, and the moment they fail is the moment the damage is
 * cheap to undo.
 *
 * <p><strong>No business sibling at all - the ledger included.</strong> Credit decides whether
 * credit may be offered and moves no money, so it has nothing to command through the ledger: it
 * is the first business module since Phase 1 with no ledger edge, and the absence is the point.
 * {@code consent}, {@code kyc}, {@code party} and {@code identity} are refused for the reason
 * ADR-0084 gives: the lawful basis for bureau access and the party's standing are read through
 * ports this module declares and {@code app} composes, inside credit's own transaction. None of
 * these refusals has a Gradle cycle behind it - an edge would configure cleanly if added, and this
 * test is the only control. Every sibling's isolation test refuses {@code credit} from its side,
 * because nothing depends on credit in Phase 10.
 *
 * <p><strong>The matcher is probed in-suite.</strong> A forbidden-list guard over a matcher that
 * never matches passes over any classpath; {@link #theMatcherCatchesWhatItMustAndNothingElse}
 * plants entries a real Gradle classpath carries - a sibling's jar, its class directory - and the
 * lookalikes that must NOT count, so the guard is proven able to fail before it is trusted to pass.
 */
@Tag("architecture")
@DisplayName("credit module isolation (P10-TSK-001)")
class CreditModuleIsolationTest {

    /** Every business module and the composition root - credit may see none of them. */
    static final List<String> FORBIDDEN = List.of(
            "ledger",
            "consent",
            "kyc",
            "party",
            "identity",
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
            "app");

    @Test
    @DisplayName("credit sees no business module - ledger, consent, kyc, party and identity named - and not the"
            + " composition root")
    void seesNoBusinessModuleAndNoCompositionRoot() {
        for (String forbidden : FORBIDDEN) {
            assertThat(classpathEntries())
                    .as("credit must not depend on %s", forbidden)
                    .noneMatch(entry -> isBuildOutputOf(entry, forbidden));
        }
    }

    @Test
    @DisplayName("credit does depend on platform and sharedkernel - exactly the documented direction")
    void dependsOnPlatformAndSharedkernel() {
        // The non-vacuity half of the guard above - without it, the forbidden-list assertion
        // passes over a classpath containing nothing at all.
        for (String required : List.of("platform", "sharedkernel")) {
            assertThat(classpathEntries())
                    .as("credit must depend on %s", required)
                    .anyMatch(entry -> isBuildOutputOf(entry, required));
        }
    }

    @Test
    @DisplayName("the matcher catches a sibling's jar and class directory, and no lookalike - the planted probes")
    void theMatcherCatchesWhatItMustAndNothingElse() {
        String root = File.separator + "repo" + File.separator;
        String sep = File.separator;
        assertThat(isBuildOutputOf(root + "ledger" + sep + "build" + sep + "libs" + sep + "ledger.jar", "ledger"))
                .as("a refused sibling's jar is caught").isTrue();
        assertThat(isBuildOutputOf(
                        root + "consent" + sep + "build" + sep + "classes" + sep + "java" + sep + "main", "consent"))
                .as("a refused sibling's class directory is caught").isTrue();
        assertThat(isBuildOutputOf(root + "creditledger" + sep + "build" + sep + "libs" + sep + "x.jar", "ledger"))
                .as("a module whose name merely contains a sibling's is not it").isFalse();
        assertThat(isBuildOutputOf(root + "ledger" + sep + "src" + sep + "main", "ledger"))
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
