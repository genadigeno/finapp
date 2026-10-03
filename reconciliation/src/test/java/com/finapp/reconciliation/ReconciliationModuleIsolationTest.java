package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees the {@code reconciliation} module exists to provide (P8-TSK-001).
 *
 * <p>Assertions about the <strong>build</strong>, not about behaviour — the
 * {@code PartyModuleIsolationTest} idiom, and the moment they fail is the moment the damage is
 * cheap to undo.
 *
 * <p><strong>The one edge this module is defined by is the one it is allowed.</strong> An
 * approved resolution's compensating entry goes through the ledger's adjustment machinery, and
 * the position proof reads balances through the ledger's API — commanded and read, never
 * written ({@code INV-LED-04}, ADR-0071). So {@code reconciliation} depends on {@code ledger},
 * and {@code ledger} never depends on {@code reconciliation}: the accounting must not be shaped
 * by the comparison built over it. This test pins the <em>positive</em> half (the edge exists,
 * so the asymmetry is a fact about the build graph and the reverse edge is a Gradle cycle);
 * {@code LedgerModuleIsolationTest} forbids the negative half from its own side.
 *
 * <p><strong>{@code settlement} is a refusal with no cycle behind it — and it is the refusal
 * this phase is built on.</strong> Reconciliation owns what the platform EXPECTED; settlement
 * owns what the counterparties SAY happened (ADR-0064). If the matcher could compile against
 * evidence rows, or a resolution reach into a batch, the two authorities would share a writer —
 * exactly the shared-mutable-ownership CLAUDE.md forbids. The edge would configure cleanly if
 * added; this test and its twin are the only controls, and every hand-off — the intake's copy,
 * the openers' calls — goes through a port {@code app} composes. {@code payments} and
 * {@code merchant} are refused for the same shape of reason: an expectation names the
 * completion that opened it by identifier and posting key, never by type.
 */
@Tag("architecture")
@DisplayName("reconciliation module isolation (P8-TSK-001)")
class ReconciliationModuleIsolationTest {

    @Test
    @DisplayName("reconciliation sees no sibling business module but ledger — settlement included, "
            + "whose refusal has no cycle behind it — and not the composition root")
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
                        "fx",
                        "crossborder",
                        "app")) {
            assertThat(classpathEntries())
                    .as("reconciliation must not depend on %s", forbidden)
                    .noneMatch(entry -> isBuildOutputOf(entry, forbidden));
        }
    }

    @Test
    @DisplayName("reconciliation does depend on ledger, platform and sharedkernel — the documented "
            + "direction")
    void dependsOnLedgerPlatformAndSharedkernel() {
        // The non-vacuity half of the guard above — without it, the forbidden-list assertion
        // passes over a classpath containing nothing at all — and the positive half of
        // ADR-0071's asymmetry: reconciliation -> ledger -> platform -> sharedkernel is pinned
        // as the documented direction, so the ledger edge quietly disappearing (which would make
        // the asymmetry an accident rather than a structure) is a failure here.
        for (String required : List.of("ledger", "platform", "sharedkernel")) {
            assertThat(classpathEntries())
                    .as("reconciliation must depend on %s", required)
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
