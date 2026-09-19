package com.finapp.transfers;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees the {@code transfers} module exists to provide (P4-TSK-001).
 *
 * <p>Assertions about the <strong>build</strong>, not about behaviour — the
 * {@code PartyModuleIsolationTest} idiom, and the moment they fail is the moment the damage is
 * cheap to undo.
 *
 * <p><strong>The one edge this module is defined by is the one it is allowed.</strong> ADR-0043:
 * a transfer's money movement IS a ledger posting, commanded through {@code PostingService} and
 * never written here ({@code INV-LED-04}) — so {@code transfers} depends on {@code ledger}, and
 * {@code ledger} never depends on {@code transfers}: the accounting must not be shaped by the
 * movement built over it. This test pins the <em>positive</em> half (the edge exists, so the
 * asymmetry is a fact about the build graph and the reverse edge is a Gradle cycle);
 * {@code LedgerModuleIsolationTest} forbids the negative half from its own side.
 *
 * <p><strong>{@code accounts} is the refusal that matters most.</strong> The transfer resolves the
 * caller's products through a port {@code app} implements (the {@code AccountHolderVerification}
 * shape, {@code P4-TSK-005}), never by importing the product module: the module that owns the
 * product and the module that moves the money must not become one dependency ball. Every other
 * business sibling stays forbidden for the established reasons — verification is a projection this
 * module may only be handed ({@code INV-KYC-05}), and beneficiaries reference a Party by value,
 * never by import.
 */
@Tag("architecture")
@DisplayName("transfers module isolation (P4-TSK-001)")
class TransfersModuleIsolationTest {

    @Test
    @DisplayName("transfers sees no sibling business module but ledger, and not the composition root")
    void seesNoSiblingButLedgerAndNoCompositionRoot() {
        for (String forbidden : List.of("party", "identity", "kyc", "consent", "accounts", "payments", "paymentmethods", "app")) {
            assertThat(classpathEntries())
                    .as("transfers must not depend on %s", forbidden)
                    .noneMatch(entry -> isBuildOutputOf(entry, forbidden));
        }
    }

    @Test
    @DisplayName("transfers does depend on ledger, platform and sharedkernel — the documented direction")
    void dependsOnLedgerPlatformAndSharedkernel() {
        // The non-vacuity half of the guard above — without it, the forbidden-list assertion
        // passes over a classpath containing nothing at all — and the positive half of
        // ADR-0043's asymmetry: transfers -> ledger -> platform -> sharedkernel is pinned as the
        // documented direction, so the ledger edge quietly disappearing (which would make the
        // asymmetry an accident rather than a structure) is a failure here.
        for (String required : List.of("ledger", "platform", "sharedkernel")) {
            assertThat(classpathEntries())
                    .as("transfers must depend on %s", required)
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
