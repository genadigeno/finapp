package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees the {@code merchant} module exists to provide (P6-TSK-001).
 *
 * <p>Assertions about the <strong>build</strong>, not about behaviour — the
 * {@code PartyModuleIsolationTest} idiom, and the moment they fail is the moment the damage is
 * cheap to undo.
 *
 * <p><strong>The one edge this module is defined by is the one it is allowed.</strong>
 * {@code INV-MER-02}: the merchant payable is a ledger <em>position</em> — captured minus fees
 * minus refunds minus payouts — read and posted to through the ledger's APIs (ADR-0050's
 * one-entry capture, ADR-0051's hold-then-dispatch payout), commanded and never written
 * ({@code INV-LED-04}), and stored nowhere as a column. So {@code merchant} depends on
 * {@code ledger}, and {@code ledger} never depends on {@code merchant}: the accounting must not
 * be shaped by the commercial traffic built over it. This test pins the <em>positive</em> half
 * (the edge exists, so the asymmetry is a fact about the build graph and the reverse edge is a
 * Gradle cycle); {@code LedgerModuleIsolationTest} forbids the negative half from its own side.
 *
 * <p><strong>{@code checkout} and {@code payments} are refusals with no cycle behind them.</strong>
 * A session references its merchant by identifier through a port {@code app} implements — the
 * counterparty's lifecycle must not be shaped by the purchase experience. And the fee
 * assessment rides the capture through the ADR-0050 §6 composition seam in {@code app}, so the
 * module that prices the platform's service never compiles against provider machinery. Both
 * would configure cleanly if added; this test is the only control.
 */
@Tag("architecture")
@DisplayName("merchant module isolation (P6-TSK-001)")
class MerchantModuleIsolationTest {

    @Test
    @DisplayName("merchant sees no sibling business module but ledger — checkout and payments "
            + "included, whose refusals have no cycle behind them — and not the composition root")
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
                        "app")) {
            assertThat(classpathEntries())
                    .as("merchant must not depend on %s", forbidden)
                    .noneMatch(entry -> isBuildOutputOf(entry, forbidden));
        }
    }

    @Test
    @DisplayName("merchant does depend on ledger, platform and sharedkernel — the documented direction")
    void dependsOnLedgerPlatformAndSharedkernel() {
        // The non-vacuity half of the guard above — without it, the forbidden-list assertion
        // passes over a classpath containing nothing at all — and the positive half of
        // INV-MER-02's asymmetry: merchant -> ledger -> platform -> sharedkernel is pinned as
        // the documented direction, so the ledger edge quietly disappearing (which would make
        // the asymmetry an accident rather than a structure) is a failure here.
        for (String required : List.of("ledger", "platform", "sharedkernel")) {
            assertThat(classpathEntries())
                    .as("merchant must depend on %s", required)
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
