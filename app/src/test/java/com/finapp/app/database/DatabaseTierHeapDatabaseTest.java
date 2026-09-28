package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The database tier's JVM has the heap its workload needs (the Phase 7 -> 8 transition).
 *
 * <p>The whole tier runs in one test JVM. Spring caches up to 32 application contexts, each
 * holding its connection pool, its schedulers and its Kafka producer, and Phase 7's suites added
 * contexts of their own. On Gradle's 512 MiB test-worker default the transition's fleet-wide run
 * met {@code OutOfMemoryError} late in the tier: ten concurrent registrations, each deriving an
 * Argon2id hash at 19 MiB, answered {@code 500} and failed three assertions in
 * {@code RegistrationConcurrencyDatabaseTest} that every run of the suite alone passed. No
 * targeted tier could see it, because only the whole tier accumulates the contexts.
 *
 * <p>The heap is set in {@code finapp.java-conventions} for the external-infrastructure tiers;
 * this test fails if the setting is lost, rather than leaving the next fleet-wide run to find it
 * as an unrelated suite's 500.
 */
@Tag("database")
@DisplayName("the database tier's heap (the Phase 7 -> 8 transition)")
class DatabaseTierHeapDatabaseTest {

    /** Below the configured 2 GiB, above the 512 MiB default that failed, with JVM headroom. */
    private static final long REQUIRED_BYTES = 1536L * 1024 * 1024;

    @Test
    @DisplayName("the tier's JVM runs with the configured heap, never Gradle's 512 MiB default")
    void theTierRunsWithTheConfiguredHeap() {
        assertThat(Runtime.getRuntime().maxMemory())
                .as("the database tier's max heap - set maxHeapSize in finapp.java-conventions;"
                        + " at 512 MiB the fleet-wide run exhausted it (see the class javadoc)")
                .isGreaterThanOrEqualTo(REQUIRED_BYTES);
    }
}
