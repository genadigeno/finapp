package com.finapp.migrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

/**
 * The first connection is tried again while the database cannot be reached, within a bound the Jobs allow, and never for a
 * failure that would be the same next time ({@code P15-TSK-032}: on {@code kind} under kube-router, the role bootstrap failed
 * four pods in eighty seconds on a new pod's first connections, refused until the network policy had reached its address).
 */
@DisplayName("the first connection is tried again while the database cannot be reached, within the Jobs' bounds (P15-TSK-032)")
class FirstConnectionTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);
    private final List<Duration> pauses = new ArrayList<>();
    private final FirstConnection recording =
            new FirstConnection(FirstConnection.ATTEMPTS, FirstConnection.PAUSE, pauses::add, out, "finapp-migrator");

    @AfterEach
    void clearTheInterruptFlag() {
        Thread.interrupted();
    }

    private static SQLException refused() {
        return new PSQLException("Connection to the database refused.", PSQLState.CONNECTION_UNABLE_TO_CONNECT, new ConnectException("Connection refused"));
    }

    /** A data source that fails with the given failures in turn, then hands out a connection; counts the calls. */
    private static DataSource failingThen(int[] calls, List<SQLException> failures) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
            if (!method.getName().equals("getConnection")) {
                throw new UnsupportedOperationException(method.getName());
            }
            int call = calls[0]++;
            if (call < failures.size()) {
                throw failures.get(call);
            }
            return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (c, m, a) -> null);
        });
    }

    private static List<SQLException> times(int count, SQLException failure) {
        return Stream.generate(() -> failure).limit(count).toList();
    }

    @Test
    @DisplayName("a connection refused is tried again, a pause apart, and the first that succeeds is the run's")
    void aRefusedConnectionIsTriedAgain() throws SQLException {
        int[] calls = {0};
        Connection connection = recording.open(failingThen(calls, times(4, refused())));
        assertThat(connection).isNotNull();
        assertThat(calls[0]).isEqualTo(5);
        assertThat(pauses).containsExactly(FirstConnection.PAUSE, FirstConnection.PAUSE, FirstConnection.PAUSE, FirstConnection.PAUSE);
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("finapp-migrator connect attempt=4 of " + FirstConnection.ATTEMPTS + " unavailable 08001");
    }

    @Test
    @DisplayName("past the bound the last failure is thrown, and the run exits transient as before, for the Job to try again")
    void theBoundIsKept() {
        int[] calls = {0};
        assertThatThrownBy(() -> recording.open(failingThen(calls, times(100, refused())))).isInstanceOf(SQLException.class);
        assertThat(calls[0]).isEqualTo(FirstConnection.ATTEMPTS);
        assertThat(pauses).hasSize(FirstConnection.ATTEMPTS - 1).containsOnly(FirstConnection.PAUSE);
        assertThat(FailureClass.beforeAnythingRan(refused())).isEqualTo(ExitCode.TRANSIENT);
    }

    @ParameterizedTest
    @MethodSource("theSameNextTime")
    @DisplayName("a refused credential, a database not there, a TLS connection that cannot be verified, or a definite failure is not tried again")
    void whatWouldFailTheSameWayIsNotTriedAgain(SQLException failure) {
        int[] calls = {0};
        assertThatThrownBy(() -> recording.open(failingThen(calls, times(100, failure)))).isSameAs(failure);
        assertThat(calls[0]).isEqualTo(1);
        assertThat(pauses).isEmpty();
    }

    private static Stream<SQLException> theSameNextTime() {
        return Stream.of(
                new PSQLException("password authentication failed", PSQLState.INVALID_PASSWORD),
                new SQLException("database does not exist", "3D000"),
                new PSQLException("Could not open SSL root certificate file /etc/ca.pem.", PSQLState.CONNECTION_FAILURE, new FileNotFoundException("/etc/ca.pem")),
                new SQLException("permission denied", "42501"));
    }

    @Test
    @DisplayName("an interrupted pause stops the attempts, keeps the interrupt and throws the failure")
    void anInterruptedPauseStops() {
        FirstConnection interrupted = new FirstConnection(FirstConnection.ATTEMPTS, FirstConnection.PAUSE, duration -> {
            throw new InterruptedException();
        }, out, "finapp-bootstrap");
        int[] calls = {0};
        assertThatThrownBy(() -> interrupted.open(failingThen(calls, times(100, refused())))).isInstanceOf(SQLException.class);
        assertThat(calls[0]).isEqualTo(1);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    @DisplayName("the bound fits the Jobs: every migration pod's worst wait inside the Job's deadline, the bootstrap's inside its own, and the shortest wait longer than the policy's lag seen on kind")
    void theBoundFitsTheJobs() {
        long timeout = new PGSimpleDataSource().getConnectTimeout();
        assertThat(timeout).as("the driver's connect timeout, read from the driver: each attempt's own bound").isPositive();
        long pause = FirstConnection.PAUSE.toSeconds();
        long worst = FirstConnection.ATTEMPTS * timeout + (FirstConnection.ATTEMPTS - 1) * pause;
        long shortest = (FirstConnection.ATTEMPTS - 1) * pause;

        String migrator = RepositoryFiles.read("deploy/base/migrator-job.yaml");
        long migratorDeadline = number(migrator, "activeDeadlineSeconds");
        long migratorPods = number(migrator, "backoffLimit") + 1;
        assertThat(migratorPods * worst).as("every pod the migration Job may start waits its worst and the deadline still holds").isLessThan(migratorDeadline);

        String bootstrap = RepositoryFiles.read("infra/scripts/kind-rehearsal/bootstrap-job.yaml");
        assertThat(worst).as("one bootstrap pod's worst wait inside the bootstrap Job's deadline").isLessThan(number(bootstrap, "activeDeadlineSeconds"));

        assertThat(shortest).as("refused at once each time, a pod still waits well past the policy's lag seen on kind (FirstConnection's comment)")
                .isGreaterThanOrEqualTo(60);
    }

    private static long number(String manifest, String key) {
        Matcher found = Pattern.compile("(?m)^  " + key + ": (\\d+)$").matcher(manifest);
        assertThat(found.find()).as("%s is stated", key).isTrue();
        return Long.parseLong(found.group(1));
    }

    @Test
    @DisplayName("both entrypoints open their first connection through it: the migrator's and the role bootstrap's")
    void bothEntrypointsUseIt() throws Exception {
        int[] calls = {0};
        assertThatThrownBy(() -> RoleBootstrapMain.applyTwice(failingThen(calls, times(100, refused())), Map.of(), out, recording))
                .isInstanceOf(SQLException.class);
        assertThat(calls[0]).as("the bootstrap tried the bound").isEqualTo(FirstConnection.ATTEMPTS);
        String migratorMain = RepositoryFiles.read("migrator/src/main/java/com/finapp/migrator/MigratorMain.java");
        assertThat(migratorMain).contains("try (Connection connection = firstConnection.open(dataSource)) {")
                .contains("FirstConnection.standard(out, \"finapp-migrator\")").doesNotContain("dataSource.getConnection()");
        String bootstrapMain = RepositoryFiles.read("migrator/src/main/java/com/finapp/migrator/RoleBootstrapMain.java");
        assertThat(bootstrapMain).contains("try (Connection connection = firstConnection.open(dataSource)) {")
                .contains("FirstConnection.standard(out, \"finapp-bootstrap\")").doesNotContain("dataSource.getConnection()");
    }
}
