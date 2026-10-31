package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingStorageException;
import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The GL's one read-only snapshot per request (`P14-TSK-007`; {@code INV-ACC-14}, owner decision C10): repeatable read
 * and read-only set before the first statement, the settings read back and verified by that statement, the statement
 * timeout armed by the second (the review's I-2), the work on the same connection, always rolled back and closed; a
 * session that is not a read-only repeatable-read snapshot is refused before any work; a statement cancelled by the
 * timeout ends the read as {@link GlSnapshots.ReadTimedOut}. The database's own refusal of a write and cancellation of a
 * slow statement are {@code GlReadModelDatabaseTest#aGlReadCannotWrite} and {@code #aStatementPastTheBoundIsCancelled}
 * (owed, C5).
 */
@DisplayName("the GL's read-only snapshot (P14-TSK-007)")
class GlSnapshotsTest {

    private static final Duration BOUND = Duration.ofMillis(2500);

    @Test
    @DisplayName("REPEATABLE READ and read-only before the first statement, verified, the statement timeout armed, the"
            + " work on that connection, rolled back and closed")
    void aVerifiedReadOnlySnapshot() {
        SnapshotSession session = new SnapshotSession();
        List<Connection> seen = new ArrayList<>();
        Connection connection = session.connection();
        String answer = new GlSnapshots(() -> connection, BOUND).read(snapshot -> {
            seen.add(snapshot);
            session.calls.add("work");
            return "figures";
        });
        assertThat(answer).isEqualTo("figures");
        assertThat(seen).containsExactly(connection);
        assertThat(session.calls).containsExactly("autoCommit=false", "isolation=" + Connection.TRANSACTION_REPEATABLE_READ,
                "readOnly=true", "verify", "timeout=2500ms", "work", "rollback", "close");
    }

    @Test
    @DisplayName("a work that fails is still rolled back and closed")
    void aFailedWorkIsRolledBack() {
        SnapshotSession session = new SnapshotSession();
        Connection connection = session.connection();
        assertThatThrownBy(() -> new GlSnapshots(() -> connection, BOUND).read(snapshot -> {
            throw new IllegalStateException("the read failed");
        })).hasMessage("the read failed");
        assertThat(session.calls).endsWith("rollback", "close");
    }

    @Test
    @DisplayName("a session the settings did not reach - read committed, or writable - is refused before any work")
    void aSessionThatIsNotTheSnapshotIsRefused() {
        SnapshotSession session = new SnapshotSession();
        Connection real = session.connection();
        // A connection that swallows setReadOnly - a pool or driver that dropped the setting.
        Connection writable = (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setReadOnly")) {
                        return null;
                    }
                    return method.invoke(real, args);
                });
        List<String> work = new ArrayList<>();
        assertThatThrownBy(() -> new GlSnapshots(() -> writable, BOUND).read(snapshot -> work.add("work")))
                .isInstanceOf(GlSnapshots.SnapshotRefused.class).hasMessageContaining("read only off");
        assertThat(work).isEmpty();
        assertThat(session.calls).endsWith("verify", "rollback", "close");
    }

    @Test
    @DisplayName("a statement the timeout cancelled - a store's failure ending (SQLState 57014) - ends the read as"
            + " ReadTimedOut, rolled back; any other store failure passes through as itself")
    void aCancelledStatementIsReadTimedOut() {
        SnapshotSession session = new SnapshotSession();
        Connection connection = session.connection();
        String cancelled = DatabaseFailure.describe("reading the journal's aggregates for a posting-date range",
                new SQLException("canceling statement due to statement timeout", "57014"));
        assertThatThrownBy(() -> new GlSnapshots(() -> connection, BOUND).read(snapshot -> {
            throw new AccountingStorageException(cancelled);
        })).isInstanceOf(GlSnapshots.ReadTimedOut.class).hasMessageContaining("57014");
        assertThat(session.calls).endsWith("rollback", "close");
        String deadlock = DatabaseFailure.describe("reading", new SQLException("deadlock", "40P01"));
        assertThatThrownBy(() -> new GlSnapshots(() -> new SnapshotSession().connection(), BOUND).read(snapshot -> {
            throw new AccountingStorageException(deadlock);
        })).isInstanceOf(AccountingStorageException.class).hasMessageEndingWith("(SQLState 40P01)");
    }

    @Test
    @DisplayName("a bound under one millisecond is refused - PostgreSQL reads zero as no timeout at all")
    void aZeroBoundIsRefused() {
        assertThatThrownBy(() -> new GlSnapshots(() -> new SnapshotSession().connection(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(GlSnapshots.STATEMENT_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
    }
}
