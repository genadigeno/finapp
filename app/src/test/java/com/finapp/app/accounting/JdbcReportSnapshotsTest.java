package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The report snapshot's shape (`P14-TSK-008`; {@code INV-ACC-14}, owner decision C10): every read on <strong>its own
 * connection</strong>, the transaction's <strong>first</strong> statement making it {@code REPEATABLE READ, READ ONLY},
 * the database's own answer proven before any report code runs - a snapshot that is not read-only is refused, fail
 * closed, the work never run - then the snapshot's time bound armed ({@code statement_timeout}, local to the
 * transaction - `P14-TSK-008`'s review, I-1), and the transaction rolled back however the work ends. The hermetic half
 * of "a write inside generation is refused"; the database's refusal ({@code 25006}) is {@code
 * ReportRunDatabaseTest#aWriteInsideGenerationIsRefused} (owed, C5).
 */
@DisplayName("the report snapshot: a separate, read-only, repeatable-read connection (P14-TSK-008)")
class JdbcReportSnapshotsTest {

    private final List<String> calls = new ArrayList<>();
    private final AtomicInteger opened = new AtomicInteger();
    private String readOnly = "on";
    private String isolation = "repeatable read";

    @Test
    @DisplayName("the statement is pinned: REPEATABLE READ and READ ONLY, the transaction's own, never the session's")
    void theStatementIsPinned() {
        assertThat(JdbcReportSnapshots.BEGIN_SQL).isEqualTo("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
        assertThat(JdbcReportSnapshots.PROOF_SQL).isEqualTo(
                "SELECT current_setting('transaction_read_only'), current_setting('transaction_isolation')");
        assertThat(JdbcReportSnapshots.BOUND).isEqualTo("SELECT set_config('statement_timeout', ?, true)");
    }

    @Test
    @DisplayName("the bound: armed after the proof at the configured milliseconds; a bound under a millisecond - PostgreSQL's"
            + " no bound - refused")
    void theBoundIsArmed() {
        new JdbcReportSnapshots(this::open, Duration.ofMillis(1500)).read(snapshot -> null);
        assertThat(calls).containsSubsequence("executeQuery(" + JdbcReportSnapshots.PROOF_SQL + ")",
                "prepareStatement(" + JdbcReportSnapshots.BOUND + ")", "setString(1, 1500ms)", "executeQuery()",
                "rollback");
        assertThatThrownBy(() -> new JdbcReportSnapshots(this::open, Duration.ofNanos(999_999)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("in order: autocommit off, read-only, the SET TRANSACTION first, the proof, the work, the rollback, the"
            + " close - on a connection of its own for every read")
    void theOrder() {
        JdbcReportSnapshots snapshots = new JdbcReportSnapshots(this::open, Duration.ofSeconds(240));
        Integer answered = snapshots.read(snapshot -> {
            calls.add("work");
            return 42;
        });
        assertThat(answered).isEqualTo(42);
        assertThat(calls).containsExactly("setAutoCommit(false)", "setReadOnly(true)",
                "execute(" + JdbcReportSnapshots.BEGIN_SQL + ")", "executeQuery(" + JdbcReportSnapshots.PROOF_SQL + ")",
                "prepareStatement(" + JdbcReportSnapshots.BOUND + ")", "setString(1, 240000ms)", "executeQuery()",
                "work", "rollback", "close");
        snapshots.read(snapshot -> null);
        assertThat(opened).as("a separate connection per read - never a caller's").hasValue(2);
    }

    @Test
    @DisplayName("fail closed: a transaction the database says is not read-only, or not repeatable read, runs no report"
            + " code - and is rolled back")
    void notReadOnlyIsRefused() {
        JdbcReportSnapshots snapshots = new JdbcReportSnapshots(this::open, Duration.ofSeconds(240));
        readOnly = "off";
        assertThatThrownBy(() -> snapshots.read(snapshot -> calls.add("work")))
                .isInstanceOf(JdbcReportSnapshots.NotReadOnly.class);
        assertThat(calls).doesNotContain("work").contains("rollback", "close");
        calls.clear();
        readOnly = "on";
        isolation = "read committed";
        assertThatThrownBy(() -> snapshots.read(snapshot -> calls.add("work")))
                .isInstanceOf(JdbcReportSnapshots.NotReadOnly.class);
        assertThat(calls).doesNotContain("work").contains("rollback");
    }

    @Test
    @DisplayName("the work's failure still rolls back and closes, and propagates as itself")
    void aFailureRollsBack() {
        JdbcReportSnapshots snapshots = new JdbcReportSnapshots(this::open, Duration.ofSeconds(240));
        assertThatThrownBy(() -> snapshots.read(snapshot -> {
            throw new IllegalStateException("the definition failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("the definition failed");
        assertThat(calls).endsWith("rollback", "close");
    }

    // -----------------------------------------------------------------

    private Connection open() {
        opened.incrementAndGet();
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit", "setReadOnly" -> {
                        calls.add(method.getName() + "(" + args[0] + ")");
                        yield null;
                    }
                    case "createStatement" -> statement();
                    case "prepareStatement" -> {
                        calls.add("prepareStatement(" + args[0] + ")");
                        yield prepared();
                    }
                    case "rollback", "close" -> {
                        calls.add(method.getName());
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Statement statement() {
        return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> {
                        calls.add("execute(" + args[0] + ")");
                        yield false;
                    }
                    case "executeQuery" -> {
                        calls.add("executeQuery(" + args[0] + ")");
                        yield proof();
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private PreparedStatement prepared() {
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setString" -> {
                        calls.add("setString(" + args[0] + ", " + args[1] + ")");
                        yield null;
                    }
                    case "executeQuery" -> {
                        calls.add("executeQuery()");
                        yield proof();
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private ResultSet proof() {
        boolean[] read = {false};
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        boolean first = !read[0];
                        read[0] = true;
                        yield first;
                    }
                    case "getString" -> ((Integer) args[0]) == 1 ? readOnly : isolation;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
