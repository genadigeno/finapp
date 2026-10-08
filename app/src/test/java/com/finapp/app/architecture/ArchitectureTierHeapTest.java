package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The architecture tier's JVM has the heap its workload needs (`P10-TSK-021`) - the third of
 * {@code HermeticTierHeapTest} and {@code DatabaseTierHeapDatabaseTest}.
 *
 * <p>App's architecture tier runs, in one test JVM, ArchUnit's imports of the whole codebase and the
 * sweeps that read every module's compiled classes. It ran on Gradle's 512 MiB test-worker default
 * until the second bureau's classes and suites tipped it into {@code OutOfMemoryError} - in app's run
 * of the whole tier only, every suite passing alone. The heap is set for every tagged tier in
 * {@code finapp.java-conventions}; this test fails if the setting is lost.
 */
@Tag("architecture")
@DisplayName("the architecture tier's heap (P10-TSK-021)")
class ArchitectureTierHeapTest {

    /** Below the configured 2 GiB, above the 512 MiB default that failed, with JVM headroom. */
    private static final long REQUIRED_BYTES = 1536L * 1024 * 1024;

    @Test
    @DisplayName("the tier's JVM runs with the configured heap, never Gradle's 512 MiB default")
    void theTierRunsWithTheConfiguredHeap() {
        assertThat(Runtime.getRuntime().maxMemory())
                .as("the architecture tier's max heap - set maxHeapSize in finapp.java-conventions;"
                        + " at 512 MiB app's run exhausted it (see the class javadoc)")
                .isGreaterThanOrEqualTo(REQUIRED_BYTES);
    }
}
