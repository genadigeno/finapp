package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees the {@code settlement} module exists to provide (P8-TSK-001).
 *
 * <p>Assertions about the <strong>build</strong>, not about behaviour — the
 * {@code PartyModuleIsolationTest} idiom, and the moment they fail is the moment the damage is
 * cheap to undo.
 *
 * <p><strong>The one edge this module is defined by is the one it is allowed.</strong>
 * Recognition — the counterparty's fees on acceptance, cash on the bank's own statement — IS a
 * ledger posting, commanded through {@code PostingService} and never written here
 * ({@code INV-LED-04}, ADR-0065). So {@code settlement} depends on {@code ledger}, and
 * {@code ledger} never depends on {@code settlement}: the accounting must not be shaped by the
 * evidence traffic built over it. This test pins the <em>positive</em> half (the edge exists,
 * so the asymmetry is a fact about the build graph and the reverse edge is a Gradle cycle);
 * {@code LedgerModuleIsolationTest} forbids the negative half from its own side.
 *
 * <p><strong>{@code reconciliation} is a refusal with no cycle behind it — and it is the
 * refusal this phase is built on.</strong> Settlement owns what the counterparties SAY
 * happened; reconciliation owns what the platform EXPECTED (ADR-0064). If acceptance could
 * compile against dispositions, or hand evidence rows to the matcher directly, the two
 * authorities would share a writer — exactly the shared-mutable-ownership CLAUDE.md forbids.
 * The edge would configure cleanly if added; this test and its twin are the only controls, and
 * every hand-off goes through a port {@code app} composes. {@code payments} and
 * {@code merchant} are refused for the same shape of reason: a settlement line names the
 * operations it settles by identifier, never by type.
 */
@Tag("architecture")
@DisplayName("settlement module isolation (P8-TSK-001)")
class SettlementModuleIsolationTest {

    @Test
    @DisplayName("settlement sees no sibling business module but ledger — reconciliation included, "
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
                        "reconciliation",
                        "app")) {
            assertThat(classpathEntries())
                    .as("settlement must not depend on %s", forbidden)
                    .noneMatch(entry -> isBuildOutputOf(entry, forbidden));
        }
    }

    @Test
    @DisplayName("settlement does depend on ledger, platform and sharedkernel — the documented direction")
    void dependsOnLedgerPlatformAndSharedkernel() {
        // The non-vacuity half of the guard above — without it, the forbidden-list assertion
        // passes over a classpath containing nothing at all — and the positive half of
        // ADR-0065's asymmetry: settlement -> ledger -> platform -> sharedkernel is pinned as
        // the documented direction, so the ledger edge quietly disappearing (which would make
        // the asymmetry an accident rather than a structure) is a failure here.
        for (String required : List.of("ledger", "platform", "sharedkernel")) {
            assertThat(classpathEntries())
                    .as("settlement must depend on %s", required)
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
