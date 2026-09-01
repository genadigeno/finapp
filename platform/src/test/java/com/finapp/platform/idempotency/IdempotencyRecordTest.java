package com.finapp.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.correlation.CorrelationId;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Staleness decides whether a claim may be taken over from a process presumed crashed.
 *
 * <p>Tested directly because a mutation sweep showed it was not: making
 * {@link IdempotencyRecord#isStaleAt} return true for every in-progress claim survived the whole
 * database suite, since the reclaim statement re-checks the condition in its {@code WHERE}
 * clause and refused. That is defence in depth working, and it is exactly why the Java side must
 * be tested on its own — the day someone relies on this predicate without the database behind
 * it, a fresh claim would be reclaimed and its command run a second time while the first was
 * still running.
 */
class IdempotencyRecordTest {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final Duration STALE_AFTER = Duration.ofMinutes(5);

    @Test
    @DisplayName("a claim younger than the threshold is not stale")
    void freshClaimIsNotStale() {
        assertThat(claim(IdempotencyState.IN_PROGRESS, NOW).isStaleAt(NOW, STALE_AFTER)).isFalse();
        assertThat(claim(IdempotencyState.IN_PROGRESS, NOW.minus(Duration.ofMinutes(4)))
                        .isStaleAt(NOW, STALE_AFTER))
                .isFalse();
    }

    @Test
    @DisplayName("the threshold is exclusive: a claim exactly at it is not yet stale")
    void claimExactlyAtTheThresholdIsNotStale() {
        // The boundary matters. Reclaiming at the instant the threshold is reached would take
        // over a command that may be about to finish.
        assertThat(claim(IdempotencyState.IN_PROGRESS, NOW.minus(STALE_AFTER))
                        .isStaleAt(NOW, STALE_AFTER))
                .isFalse();
    }

    @Test
    @DisplayName("a claim older than the threshold is stale")
    void oldClaimIsStale() {
        assertThat(claim(IdempotencyState.IN_PROGRESS, NOW.minus(STALE_AFTER).minusMillis(1))
                        .isStaleAt(NOW, STALE_AFTER))
                .isTrue();
    }

    @Test
    @DisplayName("a terminal claim is never stale, however old")
    void terminalClaimIsNeverStale() {
        // Staleness only ever licenses taking a claim over. A completed one has an outcome to
        // replay; treating it as abandoned would re-run a command that already succeeded.
        Instant longAgo = NOW.minus(Duration.ofDays(365));

        assertThat(claim(IdempotencyState.COMPLETED, longAgo).isStaleAt(NOW, STALE_AFTER)).isFalse();
        assertThat(claim(IdempotencyState.FAILED, longAgo).isStaleAt(NOW, STALE_AFTER)).isFalse();
    }

    private static IdempotencyRecord claim(IdempotencyState state, Instant createdAt) {
        return new IdempotencyRecord(
                new IdempotencyKey("scope", "key"),
                RequestFingerprint.sha256("request".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                state,
                StoredResponse.empty(),
                CorrelationId.of("flow-1"),
                createdAt,
                createdAt.plus(Duration.ofHours(24)));
    }
}
