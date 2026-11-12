package com.finapp.migrator;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;

/**
 * The first connection a run opens, tried again while the database cannot be reached, a bounded number of times
 * ({@code P15-TSK-032}).
 *
 * <p><strong>Why.</strong> A new pod's first connections can be refused, or go unanswered, until the cluster's NetworkPolicy
 * engine has programmed its rules for that pod's address: the policy is applied to a new pod asynchronously (kube-router on the
 * kind rehearsal, and Calico and Cilium the same way). The migrator and the role bootstrap open their first connection at once,
 * and before this class each gave up on the first refusal with exit {@code 11}; the Job then started a new pod, with a new
 * address, which met the same wait: on {@code kind}, the role bootstrap failed four pods in eighty seconds that way, and only
 * an init container that waited for the port had hidden it before.
 *
 * <p><strong>What is tried again, and what is not.</strong> Only what {@link FailureClass#beforeAnythingRan} calls
 * {@link ExitCode#TRANSIENT}: a connection refused, unanswered or dropped (class {@code 08}), a server starting or stopping
 * ({@code 57P}), too many connections. Never a refused credential (class {@code 28}), a database that is not there, a TLS
 * connection that cannot be verified, or anything definite: those are the same next time, and waiting would only hide them.
 *
 * <p><strong>The bound.</strong> {@link #ATTEMPTS} attempts, {@link #PAUSE} apart. Each attempt is itself bounded by the driver's
 * connect timeout (10 s unless the URL sets one), so one pod waits at most {@code ATTEMPTS x timeout + (ATTEMPTS - 1) x PAUSE},
 * 190 s, and at least {@code (ATTEMPTS - 1) x PAUSE}, 90 s, when every attempt is refused at once. Measured on an idle {@code kind}
 * cluster under kube-router a new pod waited one to two seconds; under the full delivery run's load each of the four pods waited
 * longer than its JVM's start, so the shortest wait is set well above both. Both bounds are held by
 * {@code FirstConnectionTest} against what the Jobs allow: the migration Job's {@code activeDeadlineSeconds} across all of its
 * {@code backoffLimit + 1} pods, and the bootstrap's deadline for one. Past the bound the run exits {@code 11} as before, and the
 * Job's own retry takes over.
 */
@RequiredArgsConstructor
final class FirstConnection {

    /** Ten attempts: the first and nine more. */
    static final int ATTEMPTS = 10;

    /** Ten seconds between attempts. */
    static final Duration PAUSE = Duration.ofSeconds(10);

    private final int attempts;
    private final Duration pause;
    private final MigrationRun.Pause sleeper;
    private final PrintStream out;
    private final String name;

    /** The bound above, pausing for real: what the two entrypoints use. */
    static FirstConnection standard(PrintStream out, String name) {
        return new FirstConnection(ATTEMPTS, PAUSE, duration -> Thread.sleep(duration.toMillis()), out, name);
    }

    /**
     * A connection from the data source, or the last failure once the attempts are spent, or the first failure that is not
     * transient. Prints one line per failed attempt, its SQLSTATE and never its message: a URL's value can be in it.
     */
    Connection open(DataSource dataSource) throws SQLException {
        for (int attempt = 1; ; attempt++) {
            try {
                return dataSource.getConnection();
            } catch (SQLException failure) {
                if (attempt >= attempts || FailureClass.beforeAnythingRan(failure) != ExitCode.TRANSIENT) {
                    throw failure;
                }
                out.println(name + " connect attempt=" + attempt + " of " + attempts + " unavailable " + failure.getSQLState()
                        + ": trying again in " + pause.toSeconds() + " s");
                try {
                    sleeper.sleep(pause);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
            }
        }
    }
}
