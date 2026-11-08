package com.finapp.platform.deadletter;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the statements must say, as text (`P15-TSK-028`). The hermetic half: a statement that drifts from these fails here, in seconds.
 * Their behaviour on a real database, the row locks and the race are {@code DeadLetterDatabaseTest}'s, written and owed (C14).
 */
@DisplayName("the dead-letter statements keep their conditions (P15-TSK-028)")
class DeadLetterStatementsTest {

    @Test
    @DisplayName("each door takes its subject FOR UPDATE: the row lock is the arbiter")
    void theDoorsLock() {
        assertThat(JdbcDeadLetterStore.LOCK_EVENT_SQL).endsWith("FOR UPDATE");
        assertThat(JdbcDeadLetterStore.LOCK_PARKED_SQL).endsWith("FOR UPDATE");
        assertThat(JdbcDeadLetterStore.LOCK_PROPOSAL_SQL).endsWith("FOR UPDATE");
        assertThat(JdbcDeadLetterStore.REPLAY_SQL).endsWith("FOR UPDATE");
    }

    @Test
    @DisplayName("the window is judged on the server's clock, from a replay's order or when the event occurred, never from a publication")
    void theWindowIsTheServers() {
        // Never published_at: an attempt whose acknowledgement was lost reached the broker and left no mark, so a later success can
        // postdate a delivery by days.
        assertThat(JdbcDeadLetterStore.LOCK_EVENT_SQL).contains("COALESCE(replay_ordered_at, occurred_at) > now() - (? * INTERVAL '1 millisecond')");
        assertThat(JdbcDeadLetterStore.REPLAY_SQL).contains("COALESCE(replay_ordered_at, occurred_at) > now() - (? * INTERVAL '1 millisecond')");
        assertThat(JdbcDeadLetterStore.PENDING_REPLAYS_SQL).contains("COALESCE(replay_ordered_at, occurred_at) > now() - (? * INTERVAL '1 millisecond')");
    }

    @Test
    @DisplayName("a withdrawn replay returns to its first publication and is judged, locked, only among the aggregate's replayed rows")
    void aReplayIsWithdrawnToItsFirstPublication() {
        assertThat(JdbcDeadLetterStore.PENDING_REPLAYS_SQL)
                .contains("event_id <> ?", "published_at IS NULL", "discarded_at IS NULL", "replay_ordered_at IS NOT NULL")
                .endsWith("FOR UPDATE");
        assertThat(JdbcDeadLetterStore.WITHDRAW_REPLAYS_SQL)
                .contains("published_at = first_published_at", "GREATEST(attempts, 1)", "AND published_at IS NULL", "AND first_published_at IS NOT NULL");
    }

    @Test
    @DisplayName("a replay selects what has been published, by its first publication, so a queued row is not selected twice")
    void aReplaySelectsPublishedRows() {
        assertThat(JdbcDeadLetterStore.REPLAY_SQL).contains("published_at IS NOT NULL", "COALESCE(first_published_at, published_at) >= ?", "COALESCE(first_published_at, published_at) < ?");
        assertThat(JdbcDeadLetterStore.REQUEUE_SQL).contains("first_published_at = COALESCE(first_published_at, published_at)", "replay_ordered_at = now()", "published_at = NULL", "AND published_at IS NOT NULL");
    }

    @Test
    @DisplayName("a retry and a discard touch only a row still abandoned, and a discard keeps the mark a previous release stops at")
    void theConditionalUpdatesHold() {
        assertThat(JdbcDeadLetterStore.RETURN_TO_RELAY_SQL).contains("dead_lettered_at IS NOT NULL", "discarded_at IS NULL", "published_at IS NULL");
        assertThat(JdbcDeadLetterStore.DISCARD_EVENT_SQL).contains("dead_lettered_at IS NOT NULL", "discarded_at IS NULL").doesNotContain("dead_lettered_at = NULL");
        assertThat(JdbcDeadLetterStore.DISCARD_EVENT_SQL).doesNotContain("DELETE");
    }

    @Test
    @DisplayName("the dedupe rows are extended, for every consumer in the table, and never shortened")
    void theDedupeIsExtended() {
        assertThat(JdbcDeadLetterStore.EXTEND_DEDUPE_SQL)
                .contains("GREATEST(dedupe.expires_at, now() + (? * INTERVAL '1 millisecond'))", "WITH RECURSIVE consumers", "dedupe.dedupe_key = ANY (?)");
    }

    @Test
    @DisplayName("the failure's message never reaches an operator, only its class")
    void onlyTheFailureClassLeaves() {
        assertThat(JdbcDeadLetterStore.failureClass("org.apache.kafka.TimeoutException: broker-1.internal unreachable"))
                .isEqualTo("org.apache.kafka.TimeoutException");
        assertThat(JdbcDeadLetterStore.failureClass("java.lang.IllegalStateException")).isEqualTo("java.lang.IllegalStateException");
        assertThat(JdbcDeadLetterStore.failureClass(null)).isNull();
    }

    @Test
    @DisplayName("the relay and the backlog read no discarded row as pending, and a replay is aged from its order")
    void theRelayAndTheBacklogSkipDiscardedRows() throws IOException {
        String relay = Files.readString(RepositoryPaths.locate("platform/src/main/java/com/finapp/platform/outbox/OutboxRelay.java"), StandardCharsets.UTF_8);
        String backlog = Files.readString(RepositoryPaths.locate("platform/src/main/java/com/finapp/platform/outbox/OutboxBacklog.java"), StandardCharsets.UTF_8);

        assertThat(relay.split("discarded_at IS NULL", -1)).as("the candidate query and the per-aggregate query").hasSize(3);
        assertThat(backlog).contains("WHERE published_at IS NULL AND discarded_at IS NULL", "coalesce(replay_ordered_at, occurred_at)");
    }

    @Test
    @DisplayName("the migration grants no DELETE, and only expires_at of the inbox")
    void theGrantsAreNarrow() throws IOException {
        String migration = Files.readString(RepositoryPaths.locate("platform/src/main/resources/db/migration/platform/V011__dead_letters_and_replay.sql"), StandardCharsets.UTF_8);

        assertThat(migration).contains("GRANT SELECT, INSERT, UPDATE ON platform.inbox_parked_record TO finapp_app;")
                .contains("GRANT SELECT, INSERT, UPDATE ON platform.dead_letter_discard TO finapp_app;")
                .contains("GRANT UPDATE (expires_at) ON platform.inbox_message TO finapp_app;")
                .contains("CHECK (status <> 'APPROVED' OR decided_by <> proposed_by)")
                .doesNotContain("GRANT DELETE");
    }
}
