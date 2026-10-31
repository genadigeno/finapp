package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The GL's one read-only snapshot per request (`P14-TSK-007`; {@code INV-ACC-14}, owner decision C10): repeatable read
 * and read-only set before the first statement, the settings read back and verified by that statement, the work on the
 * same connection, always rolled back and closed; a session that is not a read-only repeatable-read snapshot is refused
 * before any work. The database's own refusal of a write inside it is
 * {@code GlReadModelDatabaseTest#aGlReadCannotWrite}'s (owed, C5).
 */
@DisplayName("the GL's read-only snapshot (P14-TSK-007)")
class GlSnapshotsTest {

    @Test
    @DisplayName("REPEATABLE READ and read-only before the first statement, verified, the work on that connection, rolled"
            + " back and closed")
    void aVerifiedReadOnlySnapshot() {
        SnapshotSession session = new SnapshotSession();
        List<Connection> seen = new ArrayList<>();
        Connection connection = session.connection();
        String answer = new GlSnapshots(() -> connection).read(snapshot -> {
            seen.add(snapshot);
            session.calls.add("work");
            return "figures";
        });
        assertThat(answer).isEqualTo("figures");
        assertThat(seen).containsExactly(connection);
        assertThat(session.calls).containsExactly("autoCommit=false", "isolation=" + Connection.TRANSACTION_REPEATABLE_READ,
                "readOnly=true", "verify", "work", "rollback", "close");
    }

    @Test
    @DisplayName("a work that fails is still rolled back and closed")
    void aFailedWorkIsRolledBack() {
        SnapshotSession session = new SnapshotSession();
        Connection connection = session.connection();
        assertThatThrownBy(() -> new GlSnapshots(() -> connection).read(snapshot -> {
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
        assertThatThrownBy(() -> new GlSnapshots(() -> writable).read(snapshot -> work.add("work")))
                .isInstanceOf(GlSnapshots.SnapshotRefused.class).hasMessageContaining("read only off");
        assertThat(work).isEmpty();
        assertThat(session.calls).endsWith("verify", "rollback", "close");
    }
}
