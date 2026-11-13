package com.finapp.platform.audit.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The JDBC half of the audit archive with no database (`P16-TSK-010`): which function each call names, what it binds, that every call
 * is a transaction of its own on a connection it returns, and that a failure carries the SQLState and nothing of the row.
 */
@DisplayName("the audit archive's JDBC calls: one transaction each, the functions named, failures by SQLState alone (P16-TSK-010)")
class JdbcAuditArchiveSegmentsTest {

    @Test
    @DisplayName("the claim binds the lease in milliseconds and the batch, and reads its one row as the claim")
    void theClaimReadsItsRow() {
        UUID holder = UUID.randomUUID();
        Script script = new Script().answering(new Object[] {7L, holder});

        Optional<AuditArchiveSegments.Claim> claim = new JdbcAuditArchiveSegments(script::open).claim(Duration.ofMinutes(2), 1000);

        assertThat(claim).contains(new AuditArchiveSegments.Claim(7, holder));
        Call call = script.calls.get(0);
        assertThat(call.sql).isEqualTo("SELECT claimed_seq, claimed_holder FROM platform.claim_audit_archive_segment(?, ?)");
        assertThat(call.bound).containsExactly(120_000L, 1000);
        assertThat(call.committed).isTrue();
        assertThat(call.closed).isTrue();
    }

    @Test
    @DisplayName("no row is no claim")
    void noRowIsNoClaim() {
        Script script = new Script().answering();
        assertThat(new JdbcAuditArchiveSegments(script::open).claim(Duration.ofMinutes(2), 10)).isEmpty();
    }

    @Test
    @DisplayName("the mark binds the segment, the holder and the two digests, and a refusal is its SQLState with no cause")
    void theMarksRefusalIsItsSqlState() {
        UUID holder = UUID.randomUUID();
        Script script = new Script().failing(AuditArchiveDatabaseException.NOT_HELD);
        JdbcAuditArchiveSegments segments = new JdbcAuditArchiveSegments(script::open);

        assertThatExceptionOfType(AuditArchiveDatabaseException.class)
                .isThrownBy(() -> segments.complete(new AuditArchiveSegments.Claim(7, holder), "c".repeat(64), "m".repeat(64)))
                .satisfies(failure -> {
                    assertThat(failure.heldElsewhere()).isTrue();
                    assertThat(failure.operation()).isEqualTo("complete");
                    assertThat(failure.getCause()).as("a driver's message can carry a refused row").isNull();
                    assertThat(failure.getMessage()).doesNotContain("Failing row");
                });
        Call call = script.calls.get(0);
        assertThat(call.sql).isEqualTo("SELECT platform.complete_audit_archive_segment(?, ?, ?, ?)");
        assertThat(call.bound).containsExactly(7L, holder, "c".repeat(64), "m".repeat(64));
        assertThat(call.rolledBack).isTrue();
        assertThat(call.committed).isFalse();
        assertThat(call.closed).isTrue();
    }

    @Test
    @DisplayName("the content and the manifest are the database's functions' text, each its own transaction")
    void theTextIsTheDatabases() {
        Script script = new Script().answering(new Object[] {"line\n"}).answering(new Object[] {"{}"});
        JdbcAuditArchiveSegments segments = new JdbcAuditArchiveSegments(script::open);

        assertThat(segments.content(3)).isEqualTo("line\n");
        assertThat(segments.manifest(3)).isEqualTo("{}");
        assertThat(script.calls).extracting(call -> call.sql)
                .containsExactly("SELECT platform.audit_archive_content(?)", "SELECT platform.audit_archive_manifest(?)");
        assertThat(script.calls).allSatisfy(call -> assertThat(call.closed).isTrue());
    }

    @Test
    @DisplayName("the backlog is the capped count and the oldest age in milliseconds on the database's clock")
    void theBacklog() {
        Script script = new Script().answering(new Object[] {12L, 61_500L});

        AuditArchiveSegments.Backlog backlog = new JdbcAuditArchiveSegments(script::open).backlog(100_001);

        assertThat(backlog).isEqualTo(new AuditArchiveSegments.Backlog(12, Duration.ofMillis(61_500)));
        assertThat(script.calls.get(0).bound).containsExactly(100_001);
        assertThat(script.calls.get(0).sql).contains("LIMIT ?").contains("now() - (SELECT min(p.enqueued_at)");
    }

    // ------------------------------------------------------------------------------------------------------------------

    private static final class Call {
        String sql;
        final List<Object> bound = new ArrayList<>();
        boolean committed;
        boolean rolledBack;
        boolean closed;
    }

    /** Each connection answers one statement: a row (or none), or a failure with a SQLState. */
    private static final class Script {

        private final List<Object> answers = new ArrayList<>();
        final List<Call> calls = new ArrayList<>();

        Script answering(Object[]... rows) {
            answers.add(rows);
            return this;
        }

        Script failing(String sqlState) {
            answers.add(sqlState);
            return this;
        }

        Connection open() {
            Object answer = answers.remove(0);
            Call call = new Call();
            calls.add(call);
            return proxy(Connection.class, (self, method, arguments) -> switch (method.getName()) {
                case "setAutoCommit" -> null;
                case "prepareStatement" -> {
                    call.sql = (String) arguments[0];
                    yield statement(call, answer);
                }
                case "commit" -> {
                    call.committed = true;
                    yield null;
                }
                case "rollback" -> {
                    call.rolledBack = true;
                    yield null;
                }
                case "close" -> {
                    call.closed = true;
                    yield null;
                }
                default -> throw new AssertionError("no call of Connection." + method.getName());
            });
        }

        private static PreparedStatement statement(Call call, Object answer) {
            return proxy(PreparedStatement.class, (self, method, arguments) -> switch (method.getName()) {
                case "setLong", "setInt", "setString", "setObject" -> {
                    call.bound.add(arguments[1]);
                    yield null;
                }
                case "executeQuery" -> {
                    if (answer instanceof String sqlState) {
                        throw new SQLException("ERROR: refused. Detail: Failing row contains (secret)", sqlState);
                    }
                    yield rows((Object[][]) answer);
                }
                case "close" -> null;
                default -> throw new AssertionError("no call of PreparedStatement." + method.getName());
            });
        }

        private static ResultSet rows(Object[][] rows) {
            int[] at = {-1};
            return proxy(ResultSet.class, (self, method, arguments) -> switch (method.getName()) {
                case "next" -> ++at[0] < rows.length;
                case "getLong" -> ((Number) rows[at[0]][(Integer) arguments[0] - 1]).longValue();
                case "getString" -> (String) rows[at[0]][(Integer) arguments[0] - 1];
                case "getObject" -> rows[at[0]][(Integer) arguments[0] - 1];
                case "close" -> null;
                default -> throw new AssertionError("no call of ResultSet." + method.getName());
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
