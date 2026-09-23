package com.finapp.paymentmethods;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees the {@code paymentmethods} module exists to provide (P5-TSK-001).
 *
 * <p>Assertions about the <strong>build</strong>, not about behaviour — the
 * {@code PartyModuleIsolationTest} idiom.
 *
 * <p><strong>This is the most isolated business module on the platform, deliberately.</strong>
 * It is the PCI boundary ({@code INV-PAY-02}, {@code MODULE_ARCHITECTURE.md} M7): the one place
 * a tokenised instrument reference lives, and the one surface a reviewer must read to know that
 * no raw card data crosses the line. Every business sibling is forbidden in <em>both</em>
 * directions — this test holds this side; every sibling's isolation test (and
 * {@code PaymentsModuleIsolationTest} most importantly, since the {@code payments} refusal has
 * no Gradle cycle behind it) holds the other. A PCI boundary that depended on business siblings
 * would have the whole dependency ball as its review surface; one that business siblings could
 * import would have its instrument types compiled into the modules that talk to providers.
 */
@Tag("architecture")
@DisplayName("paymentmethods module isolation (P5-TSK-001)")
class PaymentmethodsModuleIsolationTest {

    @Test
    @DisplayName("paymentmethods sees no business sibling at all, and not the composition root")
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
                        "checkout",
                        "merchant",
                        "app")) {
            assertThat(classpathEntries())
                    .as("paymentmethods must not depend on %s", forbidden)
                    .noneMatch(entry -> isBuildOutputOf(entry, forbidden));
        }
    }

    @Test
    @DisplayName("paymentmethods does depend on platform and sharedkernel — the documented direction")
    void dependsOnPlatformAndSharedkernel() {
        // The non-vacuity half — without it, the forbidden-list assertion passes over a
        // classpath containing nothing at all.
        for (String required : List.of("platform", "sharedkernel")) {
            assertThat(classpathEntries())
                    .as("paymentmethods must depend on %s", required)
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
