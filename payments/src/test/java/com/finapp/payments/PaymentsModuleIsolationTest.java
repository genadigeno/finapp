package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees the {@code payments} module exists to provide (P5-TSK-001).
 *
 * <p>Assertions about the <strong>build</strong>, not about behaviour — the
 * {@code PartyModuleIsolationTest} idiom, and the moment they fail is the moment the damage is
 * cheap to undo.
 *
 * <p><strong>The one edge this module is defined by is the one it is allowed.</strong> ADR-0048:
 * a capture's money movement IS a ledger posting — debit PSP clearing, credit the customer
 * wallet — commanded through {@code PostingService} and never written here ({@code INV-LED-04}).
 * So {@code payments} depends on {@code ledger}, and {@code ledger} never depends on
 * {@code payments}: the accounting must not be shaped by the payment traffic built over it. This
 * test pins the <em>positive</em> half (the edge exists, so the asymmetry is a fact about the
 * build graph and the reverse edge is a Gradle cycle); {@code LedgerModuleIsolationTest} forbids
 * the negative half from its own side.
 *
 * <p><strong>{@code paymentmethods} is the refusal that matters most — and the only control is
 * this test.</strong> Unlike every earlier sibling refusal, no Gradle cycle backs it: adding
 * {@code implementation(project(":paymentmethods"))} to {@code payments} would configure and
 * compile cleanly, which is exactly why this entry is load-bearing rather than decorative. The
 * module that talks to providers must not see the module that holds the PCI boundary
 * ({@code INV-PAY-02}, {@code MODULE_ARCHITECTURE.md} M7): the instrument resolves through a
 * port {@code app} implements ({@code P5-TSK-009}), so "no raw card data crosses this line"
 * stays a review of one module's surface. Every other business sibling stays forbidden for the
 * established reasons — the wallet resolves through the ledger and ports, never through
 * {@code accounts}; verification is a projection this module may only be handed.
 */
@Tag("architecture")
@DisplayName("payments module isolation (P5-TSK-001)")
class PaymentsModuleIsolationTest {

    @Test
    @DisplayName("payments sees no sibling business module but ledger — paymentmethods included, "
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
                        "paymentmethods",
                        "app")) {
            assertThat(classpathEntries())
                    .as("payments must not depend on %s", forbidden)
                    .noneMatch(entry -> isBuildOutputOf(entry, forbidden));
        }
    }

    @Test
    @DisplayName("payments does depend on ledger, platform and sharedkernel — the documented direction")
    void dependsOnLedgerPlatformAndSharedkernel() {
        // The non-vacuity half of the guard above — without it, the forbidden-list assertion
        // passes over a classpath containing nothing at all — and the positive half of
        // ADR-0048's asymmetry: payments -> ledger -> platform -> sharedkernel is pinned as the
        // documented direction, so the ledger edge quietly disappearing (which would make the
        // asymmetry an accident rather than a structure) is a failure here.
        for (String required : List.of("ledger", "platform", "sharedkernel")) {
            assertThat(classpathEntries())
                    .as("payments must depend on %s", required)
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
