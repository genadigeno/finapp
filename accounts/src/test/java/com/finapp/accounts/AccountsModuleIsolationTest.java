package com.finapp.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees the {@code accounts} module exists to provide (P3-TSK-011).
 *
 * <p>Assertions about the <strong>build</strong>, not about behaviour — the
 * {@code PartyModuleIsolationTest} idiom, and the moment they fail is the moment the damage is
 * cheap to undo.
 *
 * <p><strong>The one edge this module is defined by is the one it is allowed.</strong> ADR-0042:
 * {@code accounts} depends on {@code ledger} for balance queries and posting requests, and
 * {@code ledger} never depends on {@code accounts} — the accounting must not be shaped by the
 * product built over it. This test pins the <em>positive</em> half (the edge exists, so the
 * asymmetry is a fact about the build graph and the reverse edge is a Gradle cycle);
 * {@code LedgerModuleIsolationTest} forbids the negative half from its own side.
 *
 * <p><strong>Every other business sibling stays forbidden</strong>: the product consumes Phase 2's
 * customer-status projection as a <em>value</em> through orchestration in {@code app}
 * ({@code INV-KYC-05} — the gate is {@code P3-TSK-012}'s, wired the {@code CaseKindResolver} way),
 * never by importing {@code party} or {@code kyc}. A compile-time edge toward either would be the
 * first step to the product recomputing a verification decision it may only read.
 */
@Tag("architecture")
@DisplayName("accounts module isolation (P3-TSK-011)")
class AccountsModuleIsolationTest {

    @Test
    @DisplayName("accounts sees no sibling business module but ledger, and not the composition root")
    void seesNoSiblingButLedgerAndNoCompositionRoot() {
        for (String forbidden : List.of("party", "identity", "kyc", "consent", "app")) {
            assertThat(classpathEntries())
                    .as("accounts must not depend on %s", forbidden)
                    .noneMatch(entry -> isBuildOutputOf(entry, forbidden));
        }
    }

    @Test
    @DisplayName("accounts does depend on ledger, platform and sharedkernel — the documented direction")
    void dependsOnLedgerPlatformAndSharedkernel() {
        // The non-vacuity half of the guard above — without it, the forbidden-list assertion
        // passes over a classpath containing nothing at all — and the positive half of
        // ADR-0042's asymmetry: accounts -> ledger -> platform -> sharedkernel is pinned as the
        // documented direction, so the ledger edge quietly disappearing (which would make the
        // asymmetry an accident rather than a structure) is a failure here.
        for (String required : List.of("ledger", "platform", "sharedkernel")) {
            assertThat(classpathEntries())
                    .as("accounts must depend on %s", required)
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
