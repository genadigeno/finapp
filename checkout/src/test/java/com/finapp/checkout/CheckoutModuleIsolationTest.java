package com.finapp.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees the {@code checkout} module exists to provide (P6-TSK-001).
 *
 * <p>Assertions about the <strong>build</strong>, not about behaviour — the
 * {@code PartyModuleIsolationTest} idiom, and the moment they fail is the moment the damage is
 * cheap to undo.
 *
 * <p><strong>No business-sibling edge at all — and none of the refusals that matter has a cycle
 * behind it.</strong> The {@code paymentmethods} posture for a different reason: this module is
 * isolated so the purchase experience cannot grow into a god-orchestrator
 * ({@code PHASE_6_PLAN.md} §18's named risk). Three refusals are load-bearing and rest entirely
 * on this test, because adding any of the three edges would configure and compile cleanly:
 * {@code checkout -> payments} (ADR-0053: the module that owns the purchase experience must not
 * see provider machinery — payment execution resolves through a port {@code app} implements),
 * {@code checkout -> merchant} (a session references its merchant by identifier; the purchase
 * experience must not own or be shaped by the counterparty's lifecycle), and every other
 * sibling for the established reasons. The order's refund standing is derived from the
 * payment's rows at read time <em>by the composition layer</em> — this module never learns to
 * read them itself.
 */
@Tag("architecture")
@DisplayName("checkout module isolation (P6-TSK-001)")
class CheckoutModuleIsolationTest {

    @Test
    @DisplayName("checkout sees no business sibling at all — payments and merchant included, "
            + "whose refusals have no cycle behind them — and not the composition root")
    void seesNoSiblingAndNoCompositionRoot() {
        for (String forbidden :
                List.of(
                        "party",
                        "identity",
                        "kyc",
                        "consent",
                        "ledger",
                        "accounts",
                        "transfers",
                        "payments",
                        "paymentmethods",
                        "merchant",
                        "app")) {
            assertThat(classpathEntries())
                    .as("checkout must not depend on %s", forbidden)
                    .noneMatch(entry -> isBuildOutputOf(entry, forbidden));
        }
    }

    @Test
    @DisplayName("checkout does depend on platform and sharedkernel — the documented direction")
    void dependsOnPlatformAndSharedkernel() {
        // The non-vacuity half of the guard above — without it, the forbidden-list assertion
        // passes over a classpath containing nothing at all.
        for (String required : List.of("platform", "sharedkernel")) {
            assertThat(classpathEntries())
                    .as("checkout must depend on %s", required)
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
