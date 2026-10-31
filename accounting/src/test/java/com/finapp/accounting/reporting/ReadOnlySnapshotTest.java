package com.finapp.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.testing.TestReportDefinition;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The view a definition reads through (`P14-TSK-008`'s review, I-1 and I-2; {@code INV-ACC-14}): it prepares single reading
 * statements and nothing else - no second statement that could end the read-only transaction, no mutator, no plain or
 * callable statement, no write a statement or a result set offers, no way back to the raw objects - and it spends one time
 * budget, re-arming {@code statement_timeout} to what is left before every statement, refusing the next statement once it
 * is spent and turning a cancelled statement ({@code 57014}) into {@link ReportGeneration.GenerationTimedOut} - which a
 * definition cannot catch its way out of. Hermetic: the raw connection is a recording stand-in; the database's own
 * cancellation and refusal are {@code ReportRunDatabaseTest}'s (owed, C5).
 */
@DisplayName("the read-only snapshot view: prepared reads only, one time budget (P14-TSK-008)")
class ReadOnlySnapshotTest {

    private final List<String> calls = new ArrayList<>();
    private final AtomicLong ticker = new AtomicLong();
    private String cancelling;

    @Test
    @DisplayName("the screen: one SELECT or WITH statement, never two, naming no function that acts outside a read")
    void theScreen() {
        assertThat(ReadOnlySnapshot.screened("SELECT 1")).isEqualTo("SELECT 1");
        assertThat(ReadOnlySnapshot.screened("  with x as (select 1) select * from x")).startsWith("  with");
        for (String refused : List.of("SELECT 1; COMMIT", "SELECT 1;", "COMMIT", "BEGIN", "UPDATE t SET a = 1",
                "/* SELECT */ DELETE FROM t", "SET statement_timeout = 0", "SELECT set_config('statement_timeout', '0', true)",
                "SELECT pg_advisory_xact_lock(14, hashtext('cutoff'))", "SELECT pg_notify('c', 'x')", "SELECT pg_sleep(60)",
                "SELECT lo_unlink(1)", "SELECTION")) {
            assertThatThrownBy(() -> ReadOnlySnapshot.screened(refused)).as(refused)
                    .isInstanceOf(ReadOnlySnapshot.Refused.class);
        }
        assertThatThrownBy(() -> ReadOnlySnapshot.screened(null)).isInstanceOf(ReadOnlySnapshot.Refused.class);
    }

    @Test
    @DisplayName("every connection mutator, every plain or callable statement and every way back to the raw object is"
            + " refused - nothing reaches the database")
    void theConnectionsMutatorsAreRefused() throws Exception {
        Connection view = view(Duration.ofSeconds(10));
        List<ThrowingCall> refused = List.of(
                () -> view.setAutoCommit(true), () -> view.setReadOnly(false), view::commit, view::rollback,
                () -> view.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED), view::setSavepoint,
                view::close, view::createStatement, () -> view.prepareCall("CALL p()"),
                () -> view.unwrap(Connection.class), () -> view.setSchema("ledger"),
                () -> view.prepareStatement("SELECT 1", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE));
        for (ThrowingCall call : refused) {
            assertThatThrownBy(call::run).isInstanceOf(ReadOnlySnapshot.Refused.class);
        }
        assertThat(view.isWrapperFor(Connection.class)).isFalse();
        assertThat(calls).as("nothing reached the raw connection").isEmpty();
        assertThat(view.isReadOnly()).as("a read is forwarded").isTrue();
    }

    @Test
    @DisplayName("a prepared statement only reads: executeUpdate, execute, batches, a query string and its own timeout"
            + " refused; its connection is the view; a result refuses its writes and answers the view's statement")
    void aStatementAndAResultOnlyRead() throws Exception {
        Connection view = view(Duration.ofSeconds(10));
        PreparedStatement statement = view.prepareStatement("SELECT 7");
        for (ThrowingCall call : List.<ThrowingCall>of(statement::executeUpdate, statement::execute, statement::addBatch,
                statement::executeBatch, () -> statement.executeQuery("DELETE FROM t"), () -> statement.setQueryTimeout(0),
                () -> statement.unwrap(PreparedStatement.class))) {
            assertThatThrownBy(call::run).isInstanceOf(ReadOnlySnapshot.Refused.class);
        }
        assertThat(statement.getConnection()).isSameAs(view);
        statement.setLong(1, 3L);
        ResultSet row = statement.executeQuery();
        assertThat(row.getStatement()).isSameAs(statement);
        assertThat(row.next()).isTrue();
        assertThat(row.getLong(1)).isEqualTo(7L);
        for (ThrowingCall call : List.<ThrowingCall>of(() -> row.updateLong(1, 8L), row::insertRow, row::deleteRow,
                row::updateRow, () -> row.unwrap(ResultSet.class))) {
            assertThatThrownBy(call::run).isInstanceOf(ReadOnlySnapshot.Refused.class);
        }
    }

    @Test
    @DisplayName("the budget is spent across statements: before each, statement_timeout re-armed to what is left; once spent"
            + " the next statement is refused before it runs - GenerationTimedOut, remembered")
    void theBudgetIsSpentAcrossStatements() throws Exception {
        ReadOnlySnapshot snapshot = new ReadOnlySnapshot(raw(), Duration.ofSeconds(10), ticker::get);
        snapshot.connection().prepareStatement("SELECT 7").executeQuery();
        ticker.addAndGet(Duration.ofSeconds(4).toNanos());
        snapshot.connection().prepareStatement("SELECT 8").executeQuery();
        assertThat(calls).containsExactly("prepare SELECT 7", "prepare " + ReadOnlySnapshot.BOUND, "bound 10000ms",
                "execute SELECT 7", "prepare SELECT 8", "prepare " + ReadOnlySnapshot.BOUND, "bound 6000ms",
                "execute SELECT 8");
        ticker.addAndGet(Duration.ofSeconds(6).toNanos());
        PreparedStatement late = snapshot.connection().prepareStatement("SELECT 9");
        assertThatThrownBy(late::executeQuery).isInstanceOf(ReportGeneration.GenerationTimedOut.class);
        assertThat(calls).doesNotContain("execute SELECT 9");
        assertThat(snapshot.timedOut()).isTrue();
    }

    @Test
    @DisplayName("a statement PostgreSQL cancels (57014) is the timeout; any other failure is itself; a budget under a"
            + " millisecond is refused")
    void aCancelledStatementIsTheTimeout() throws Exception {
        ReadOnlySnapshot snapshot = new ReadOnlySnapshot(raw(), Duration.ofSeconds(10), ticker::get);
        cancelling = "57014";
        assertThatThrownBy(() -> snapshot.connection().prepareStatement("SELECT 7").executeQuery())
                .isInstanceOf(ReportGeneration.GenerationTimedOut.class);
        assertThat(snapshot.timedOut()).isTrue();
        ReadOnlySnapshot other = new ReadOnlySnapshot(raw(), Duration.ofSeconds(10), ticker::get);
        cancelling = "42P01";
        assertThatThrownBy(() -> other.connection().prepareStatement("SELECT 7").executeQuery())
                .isInstanceOf(SQLException.class).hasMessageContaining("42P01");
        assertThat(other.timedOut()).isFalse();
        assertThatThrownBy(() -> new ReadOnlySnapshot(raw(), Duration.ofNanos(999_999), ticker::get))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a definition cannot catch its way out: a timeout it swallowed still fails the generation, and a"
            + " definition writing through its view is refused before the database sees it")
    void aDefinitionCannotEscapeTheView() {
        ReportGeneration generation = new ReportGeneration((snapshot, id) -> Optional.of(
                new ReportGeneration.PeriodState(PeriodStatus.LOCKED, Optional.of(LOCKED_AT))), Duration.ofSeconds(10),
                ticker::get);
        TestReportDefinition swallowing = TestReportDefinition.of(1, (snapshot, inputs) -> {
            ticker.addAndGet(Duration.ofSeconds(11).toNanos());
            try {
                snapshot.prepareStatement("SELECT 7").executeQuery();
            } catch (RuntimeException | SQLException swallowed) {
                // A definition that hides the timeout and answers anyway answers from a partial read.
            }
            return CanonicalJson.object().put("partial", true);
        });
        assertThatThrownBy(() -> generation.generate(raw(), swallowing, inputs()))
                .isInstanceOf(ReportGeneration.GenerationTimedOut.class);
        TestReportDefinition writing = TestReportDefinition.of(1, (snapshot, inputs) -> {
            try {
                snapshot.setReadOnly(false);
                return CanonicalJson.object();
            } catch (SQLException impossible) {
                throw new IllegalStateException(impossible);
            }
        });
        assertThatThrownBy(() -> new ReportGeneration((snapshot, id) -> Optional.of(new ReportGeneration.PeriodState(
                PeriodStatus.LOCKED, Optional.of(LOCKED_AT))), Duration.ofSeconds(10), ticker::get)
                .generate(raw(), writing, inputs())).isInstanceOf(ReadOnlySnapshot.Refused.class);
    }


    @Test
    @DisplayName("the production generation spends its budget on the injected clock - moved past the budget, the next"
            + " statement is refused and the generation timed out")
    void theBudgetIsSpentOnTheInjectedClock() {
        Clock moving = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(TimeUnit.NANOSECONDS.toMillis(ticker.get()));
            }
        };
        ReportGeneration generation = new ReportGeneration((snapshot, id) -> Optional.of(
                new ReportGeneration.PeriodState(PeriodStatus.LOCKED, Optional.of(LOCKED_AT))), Duration.ofSeconds(10),
                moving);
        TestReportDefinition late = TestReportDefinition.of(1, (snapshot, inputs) -> {
            ticker.addAndGet(Duration.ofSeconds(11).toNanos());
            try {
                snapshot.prepareStatement("SELECT 7").executeQuery();
            } catch (SQLException unexpected) {
                throw new IllegalStateException(unexpected);
            }
            return CanonicalJson.object();
        });
        assertThatThrownBy(() -> generation.generate(raw(), late, inputs()))
                .isInstanceOf(ReportGeneration.GenerationTimedOut.class);
    }
    // -----------------------------------------------------------------

    private static final Instant LOCKED_AT = Instant.parse("2026-10-03T09:00:00.123456Z");

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static ReportInputs inputs() {
        return new ReportInputs(AccountingPeriodId.of(UUID.fromString("0190f0c4-1d2e-7a00-8000-0000000000aa")),
                YearMonth.of(2026, 9), 1, RunBasis.FINAL, Optional.of(LOCKED_AT), Optional.empty(), Optional.empty(),
                Instant.parse("2026-10-05T00:00:00Z"), RunParameters.of(Map.of(), Optional.empty()));
    }

    private Connection view(Duration budget) {
        return new ReadOnlySnapshot(raw(), budget, ticker::get).connection();
    }

    /** A raw connection recording what reaches it: prepares, the bound's setting and every statement's execution. */
    private Connection raw() {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        calls.add("prepare " + args[0]);
                        yield statement((String) args[0]);
                    }
                    case "isReadOnly" -> true;
                    default -> {
                        calls.add(method.getName());
                        throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private PreparedStatement statement(String sql) {
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setString" -> {
                        calls.add("bound " + args[1]);
                        yield null;
                    }
                    case "setLong", "close" -> null;
                    case "executeQuery" -> {
                        if (!sql.equals(ReadOnlySnapshot.BOUND)) {
                            if (cancelling != null) {
                                throw new SQLException("cancelled " + cancelling, cancelling);
                            }
                            calls.add("execute " + sql);
                        }
                        yield result(sql);
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private ResultSet result(String sql) {
        boolean[] read = {false};
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        boolean first = !read[0];
                        read[0] = true;
                        yield first;
                    }
                    case "getLong" -> Long.parseLong(sql.substring("SELECT ".length()));
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
