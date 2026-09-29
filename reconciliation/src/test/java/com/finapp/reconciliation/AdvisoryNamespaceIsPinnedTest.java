package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reconciliation's advisory namespace is {@code 4}, registered in
 * `DISTRIBUTED_EXECUTION.md` §3 (`P8-TSK-011`; the `PaymentsMigrationTest` precedent for
 * `3`). Payments pins its namespace in migration SQL because a trigger carries it; here no
 * migration statement does — the run leg's try-lock and the park path's blocking lock are
 * Java — so the pin reads the code. A quiet change would break the per-source
 * serialisation the two paths share and could collide with a sibling module's namespace.
 */
@DisplayName("the source advisory namespace is pinned (P8-TSK-011)")
class AdvisoryNamespaceIsPinnedTest {

    @Test
    @DisplayName("the run leg's try-lock and the park path's blocking lock share"
            + " namespace 4, byte for byte")
    void namespaceFourIsPinned() throws Exception {
        assertThat(Matching.ADVISORY_NAMESPACE).isEqualTo(4);
        assertThat(Matching.CLAIM_SQL)
                .contains("pg_try_advisory_xact_lock(4, hashtext(?::text))");
        // The park path's lock is an inline statement; the pin reads the source so the
        // two paths cannot drift apart silently.
        Path suspense =
                Path.of("src", "main", "java", "com", "finapp", "reconciliation",
                        "Suspense.java");
        assertThat(Files.readString(suspense))
                .contains("pg_advisory_xact_lock(4, hashtext(?::text))");
    }
}
