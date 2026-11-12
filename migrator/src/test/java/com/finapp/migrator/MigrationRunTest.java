package com.finapp.migrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.internal.exception.FlywayMigrateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Bounded retries and exit codes ({@code P15-TSK-004}). The classification is by SQLSTATE, read through the exceptions
 * Flyway really wraps a failure in; that the real thing times out and exits 11 is shown by {@code migrator-verify.sh}.
 */
@DisplayName("the migrator's retries and exit codes (P15-TSK-004)")
class MigrationRunTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final List<Duration> pauses = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();

    private ExitCode run(Function<String, Integer> migration, String... schemas) {
        return new MigrationRun(
                        schema -> {
                            calls.add(schema);
                            return migration.apply(schema);
                        },
                        pauses::add,
                        5,
                        Duration.ofSeconds(5),
                        new PrintStream(captured, true, StandardCharsets.UTF_8))
                .apply(List.of(schemas));
    }

    private static FlywayException sqlFailure(String sqlState) {
        return new FlywayException("failed", new SQLException("from the server", sqlState));
    }

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("every schema in order, once, and the result line sums what was applied")
    void appliesEverySchemaInOrder() {
        ExitCode exit = run(Map.of("accounting", 2, "ledger", 3)::get, "accounting", "ledger");
        assertThat(exit).isEqualTo(ExitCode.APPLIED);
        assertThat(exit.getCode()).isZero();
        assertThat(calls).containsExactly("accounting", "ledger");
        assertThat(output()).contains("schema=accounting applied=2 attempts=1").contains("result=APPLIED schemas=2 applied=5");
    }

    @Test
    @DisplayName("a lock not granted is retried, apart, and the run goes on when it is granted")
    void aLockTimeoutIsRetriedThenSucceeds() {
        int[] tries = {0};
        ExitCode exit = run(schema -> {
            if (++tries[0] < 3) {
                throw sqlFailure("55P03");
            }
            return 1;
        }, "ledger");
        assertThat(exit).isEqualTo(ExitCode.APPLIED);
        assertThat(tries[0]).isEqualTo(3);
        assertThat(pauses).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("the retries are bounded: five attempts, four pauses, exit 11, and no later schema is touched")
    void aLockTimeoutGivesUp() {
        ExitCode exit = run(schema -> {
            throw sqlFailure("55P03");
        }, "ledger", "lending");
        assertThat(exit).isEqualTo(ExitCode.TRANSIENT);
        assertThat(exit.getCode()).isEqualTo(11);
        assertThat(calls).containsExactly("ledger", "ledger", "ledger", "ledger", "ledger");
        assertThat(pauses).hasSize(4);
        assertThat(output()).contains("schema=ledger attempts=5 failure=LOCK_CONTENTION").contains("result=TRANSIENT schema=ledger");
    }

    @Test
    @DisplayName("a failure a retry cannot help is not retried: exit 10, once, and no later schema is touched")
    void aDefiniteFailureStopsAtOnce() {
        ExitCode exit = run(schema -> {
            throw sqlFailure("22012");
        }, "ledger", "lending");
        assertThat(exit).isEqualTo(ExitCode.FAILED);
        assertThat(exit.getCode()).isEqualTo(10);
        assertThat(calls).containsExactly("ledger");
        assertThat(pauses).isEmpty();
    }

    @Test
    @DisplayName("the database going away is left to the Job's backoff, not retried here")
    void anUnavailableDatabaseIsTheJobs() {
        ExitCode exit = run(schema -> {
            throw sqlFailure("08006");
        }, "ledger", "lending");
        assertThat(exit).isEqualTo(ExitCode.TRANSIENT);
        assertThat(calls).containsExactly("ledger");
        assertThat(pauses).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "55P03, LOCK_CONTENTION", // lock_not_available: lock_timeout expired
        "40P01, LOCK_CONTENTION", // deadlock_detected
        "08006, UNAVAILABLE",
        "08001, UNAVAILABLE",
        "57P01, UNAVAILABLE", // admin_shutdown
        "57P03, UNAVAILABLE", // cannot_connect_now
        "53300, UNAVAILABLE", // too_many_connections
        "57014, DEFINITE", // query_canceled: not class 57P, and nothing here sets a statement timeout
        "40001, DEFINITE", // serialization_failure: no migration runs at SERIALIZABLE
        "42P07, DEFINITE", // duplicate_table
        "42501, DEFINITE", // insufficient_privilege
        "28P01, DEFINITE", // invalid_password
        "23514, DEFINITE" // check_violation: a backfill finding data it did not expect
    })
    @DisplayName("each SQLSTATE lands in its class: the narrow reading, so a defect is not retried into hiding")
    void sqlStatesAreClassified(String sqlState, FailureClass expected) {
        assertThat(FailureClass.of(sqlFailure(sqlState))).isEqualTo(expected);
    }

    @Test
    @DisplayName("the words of a message are not a SQLSTATE: a failure merely mentioning 55P03 is a failure")
    void aMessageIsNotAState() {
        assertThat(FailureClass.of(new FlywayException("the check rejected code 55P03 in row 7"))).isEqualTo(FailureClass.DEFINITE);
    }

    @Test
    @DisplayName("another run holding Flyway's lock past its bound is transient: Flyway raises it with no SQLSTATE")
    void anotherRunHoldingTheHistoryLock() {
        assertThat(FailureClass.of(new FlywayException(
                        "Number of retries exceeded while attempting to acquire PostgreSQL advisory lock. Configure ...")))
                .isEqualTo(FailureClass.UNAVAILABLE);
    }

    @Test
    @DisplayName("a lock timeout inside a non-transactional migration is NOT retried: what it did stays, and a retry trips over it")
    void aNonTransactionalMigrationIsNotRetried() {
        assertThat(FailureClass.of(failedMigration(false, "55P03"))).isEqualTo(FailureClass.DEFINITE);
        assertThat(FailureClass.of(failedMigration(true, "55P03"))).isEqualTo(FailureClass.LOCK_CONTENTION);
    }

    @Test
    @DisplayName("the exit codes are none the JVM or a killed container uses for something else")
    void theCodesMeanOneThing() {
        assertThat(List.of(ExitCode.values()).stream().map(ExitCode::getCode).toList())
                .containsExactly(0, 10, 11, 12)
                .doesNotContain(1, 3, 137, 143);
    }

    private static FlywayMigrateException failedMigration(boolean transactional, String sqlState) {
        MigrationInfo info = (MigrationInfo) Proxy.newProxyInstance(
                MigrationInfo.class.getClassLoader(), new Class<?>[] {MigrationInfo.class}, (proxy, method, args) -> null);
        return new FlywayMigrateException(info, false, new SQLException("from the server", sqlState), transactional, new MigrateResult());
    }
}
