package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hermetic tier's JVM has the heap its workload needs (`P9-TSK-002`) - the twin of
 * {@code DatabaseTierHeapDatabaseTest}.
 *
 * <p>App's hermetic tier runs in one test JVM that caches up to 32 Spring application contexts
 * and also runs the bytecode sweeps - {@code TestTaxonomyTest}, {@code MutationDemonstrationTest}
 * - that import every module's compiled test classes, several times each. On Gradle's 512 MiB
 * test-worker default it ran a few MiB from the ceiling: when the {@code fx} module gained its
 * first production classes, and so joined those sweeps, {@code TestTaxonomyTest} met
 * {@code OutOfMemoryError} - reproducibly, and only in app's run of the whole tier. The heap is set
 * in {@code finapp.java-conventions}; this test fails if the setting is lost, rather than leaving
 * the next module's first class to find it as an unrelated suite's crash.
 */
@DisplayName("the hermetic tier's heap (P9-TSK-002)")
class HermeticTierHeapTest {

    /** Below the configured 2 GiB, above the 512 MiB default that failed, with JVM headroom. */
    private static final long REQUIRED_BYTES = 1536L * 1024 * 1024;

    @Test
    @DisplayName("the tier's JVM runs with the configured heap, never Gradle's 512 MiB default")
    void theTierRunsWithTheConfiguredHeap() {
        assertThat(Runtime.getRuntime().maxMemory())
                .as("the hermetic tier's max heap - set maxHeapSize in finapp.java-conventions;"
                        + " at 512 MiB app's run exhausted it (see the class javadoc)")
                .isGreaterThanOrEqualTo(REQUIRED_BYTES);
    }
}
