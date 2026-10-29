package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees the {@code lending} module exists to provide (P11-TSK-002, ADR-0090 point 5).
 *
 * <p>Assertions about the <strong>build</strong>, not about behaviour - the
 * {@code PartyModuleIsolationTest} idiom, and the moment they fail is the moment the damage is
 * cheap to undo.
 *
 * <p><strong>The one edge this module is allowed is the ledger.</strong> Every lending posting is
 * commanded through the ledger's API and every loan figure derived through it - never written
 * ({@code INV-LED-04}, {@code INV-LND-01}). This test pins the positive half (the reverse edge is
 * a Gradle cycle); {@code LedgerModuleIsolationTest} forbids the negative half from its own side.
 *
 * <p><strong>And {@code creditmath}, since `P12-TSK-002`</strong> (ADR-0101 decision 5): the schedule, accrual and
 * allocation engines lending computes with moved there unchanged, so {@code bnpl} shares the arithmetic without an edge
 * to lending. Admitted and required here - the edge is the extraction's whole point - and nothing else is admitted
 * with it: {@code bnpl}, the module that shares the engines, is refused like every other sibling, and
 * {@code CreditmathModuleIsolationTest} refuses {@code lending} from the other side (the reverse edge is a Gradle cycle
 * in any case).
 *
 * <p><strong>{@code credit} is a refusal with no cycle behind it</strong> (A16, the
 * {@code crossborder} precedent): lending consumes a credit decision, but through a port it
 * declares and {@code app} composes over credit's published ones - so the module that services
 * loans never compiles against the module that decides them, and neither can reach into the
 * other's tables. {@code accounts} (the wallet), {@code payments} (the external payout),
 * {@code party} (standing) and {@code reconciliation} (the capital contribution's recognition) are
 * refused for the same reason. Each edge would configure cleanly if added; this test is the only
 * control. Every sibling's isolation test refuses {@code lending} from its side, because nothing
 * depends on lending.
 *
 * <p><strong>The matcher is probed in-suite.</strong> A forbidden-list guard over a matcher that
 * never matches passes over any classpath; {@link #theMatcherCatchesWhatItMustAndNothingElse}
 * plants the entries a real Gradle classpath carries for the three refusals that matter most -
 * {@code credit}'s jar, {@code payments}' class directory, {@code accounts}' resources - and the
 * lookalikes that must NOT count, so the guard is proven able to fail before it is trusted to pass.
 */
@Tag("architecture")
@DisplayName("lending module isolation (P11-TSK-002)")
class LendingModuleIsolationTest {

    /** Every business module but the ledger, and the composition root - lending may see none of them. */
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
            "bnpl",
            "risk",
            "accounting",
            "app");

    @Test
    @DisplayName("lending sees no sibling business module but ledger - credit, accounts and payments named,"
            + " whose refusals have no cycle behind them - and not the composition root")
    void seesNoSiblingButLedgerAndNoCompositionRoot() {
        for (String forbidden : FORBIDDEN) {
            assertThat(classpathEntries())
                    .as("lending must not depend on %s", forbidden)
                    .noneMatch(entry -> isBuildOutputOf(entry, forbidden));
        }
    }

    @Test
    @DisplayName("lending does depend on ledger, creditmath, platform and sharedkernel - the documented direction"
            + " (creditmath since P12-TSK-002)")
    void dependsOnLedgerPlatformAndSharedkernel() {
        // The non-vacuity half of the guard above - without it, the forbidden-list assertion
        // passes over a classpath containing nothing at all - and the positive half of the
        // ledger asymmetry: lending -> ledger -> platform -> sharedkernel is pinned as the
        // documented direction.
        for (String required : List.of("ledger", "creditmath", "platform", "sharedkernel")) {
            assertThat(classpathEntries())
                    .as("lending must depend on %s", required)
                    .anyMatch(entry -> isBuildOutputOf(entry, required));
        }
    }

    @Test
    @DisplayName("the matcher catches credit's jar, payments' class directory and accounts' resources, and no"
            + " lookalike - the planted probes")
    void theMatcherCatchesWhatItMustAndNothingElse() {
        String root = File.separator + "repo" + File.separator;
        String sep = File.separator;
        assertThat(isBuildOutputOf(root + "credit" + sep + "build" + sep + "libs" + sep + "credit.jar", "credit"))
                .as("a refused sibling's jar is caught").isTrue();
        assertThat(isBuildOutputOf(
                        root + "payments" + sep + "build" + sep + "classes" + sep + "java" + sep + "main", "payments"))
                .as("a refused sibling's class directory is caught").isTrue();
        assertThat(isBuildOutputOf(
                        root + "accounts" + sep + "build" + sep + "resources" + sep + "main", "accounts"))
                .as("a refused sibling's resources directory is caught").isTrue();
        assertThat(isBuildOutputOf(root + "lendingcredit" + sep + "build" + sep + "libs" + sep + "x.jar", "credit"))
                .as("a module whose name merely contains a sibling's is not it").isFalse();
        assertThat(isBuildOutputOf(root + "credit" + sep + "src" + sep + "main", "credit"))
                .as("a source directory is not build output").isFalse();
        // And the guard is wired to the list it claims: each of the three is actually forbidden.
        assertThat(FORBIDDEN).contains("credit", "payments", "accounts", "bnpl").doesNotContain("ledger", "creditmath");
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
