package com.finapp.app.database;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Whether this instance may take traffic on the schema it finds (`P15-TSK-005`; ADR-0122 decision 5): the verdict the
 * readiness contributor {@code schemaVersion} reports.
 *
 * <h2>The direction</h2>
 *
 * <p>Only a schema <em>older</em> than this build expects holds the instance back: release N on release N − 1's schema
 * would fail on the first statement naming something not yet there. A <em>newer</em> schema is normal, and must stay
 * ready: during a rollout, and for the whole of a rollback, replicas of N − 1 run on N's schema, and taking them out of
 * service would bring down the fleet the rollback is protecting.
 *
 * <h2>The probe never reads</h2>
 *
 * <p>The readiness probe runs every 10 s with a 7 s timeout ({@code deploy/base/deployment.yaml}). With the database gone
 * the {@code db} contributor already waits out the pool's 5 s {@code connection-timeout}; a second wait on the probe's
 * thread would make 10 s, the probe would time out, and the kubelet would record a timeout rather than the 503 the
 * application meant. So {@link #verdict()} answers from the last read and asks for another, which runs on a daemon thread:
 * one read at a time, one statement for every schema, at most once per {@link #STEADY} while ready and once per
 * {@link #UNSETTLED} otherwise. Ten replicas read ten statements a minute, whatever the probes do.
 *
 * <h2>What a failed read means</h2>
 *
 * <ul>
 *   <li><strong>The database did not answer</strong> (no connection, a timeout, a server going away): the verdict stands. A
 *       schema is forward-only, so it did not get older by being unreachable, and {@code db} is already reporting the
 *       database. Flipping here too would make every blip two faults. Before any read has succeeded the verdict stands too,
 *       and that verdict is {@link Verdict#UNPROVEN}, which is not ready.
 *   <li><strong>The database refused the read itself</strong> (SQLState class {@value #REFUSED_CLASS}: the function does not
 *       exist, so platform's migration has not run and the schema is older; or this role cannot execute it): nothing is
 *       proven, whatever an earlier read said. {@link Verdict#UNPROVEN}.
 *   <li><strong>A version that does not parse</strong>: {@link Verdict#UNPROVEN}. It is never guessed at.
 * </ul>
 *
 * <h2>Process-local on purpose</h2>
 *
 * <p>Readiness is one replica's opinion of itself, and every replica forms it from the one shared schema against its own
 * build. Nothing here coordinates anything or is written anywhere ({@code DISTRIBUTED_EXECUTION.md} section 3).
 */
@Slf4j
@RequiredArgsConstructor
public final class SchemaVersionGuard {

    /** How long a ready verdict stands. Only a restore to an older dump could change it. */
    static final Duration STEADY = Duration.ofSeconds(60);

    /**
     * How long any other verdict stands. Below the probe's period, so a replica that started before the migration Job
     * finished is ready at the next probe after it does.
     */
    static final Duration UNSETTLED = Duration.ofSeconds(5);

    /** SQLState class 42, "syntax error or access rule violation": a statement this database will never run as things stand. */
    static final String REFUSED_CLASS = "42";

    /** The read's thread, as a thread dump shows it. */
    static final String THREAD = "schema-version-read";

    /** A daemon thread per read: at most one exists, and nothing has to be stopped at shutdown. */
    static final Executor ON_A_DAEMON_THREAD = read -> {
        Thread thread = new Thread(read, THREAD);
        thread.setDaemon(true);
        thread.start();
    };

    /** What this instance knows about its schema. */
    public enum Verdict {
        /** Every schema is at or past what this build expects. */
        READY,
        /** At least one schema is behind what this build expects. */
        BEHIND,
        /** Nothing has established either: no read has succeeded yet, or the database refused the read. */
        UNPROVEN
    }

    private record State(Verdict verdict, Instant readAt, boolean failing) {}

    @NonNull private final ExpectedSchemaVersions expected;
    @NonNull private final AppliedSchemaVersions applied;
    @NonNull private final Clock clock;
    @NonNull private final Executor reader;
    private final AtomicReference<State> state = new AtomicReference<>(new State(Verdict.UNPROVEN, null, false));
    private final AtomicBoolean reading = new AtomicBoolean();

    /** The last verdict, at once; asks for a fresher one if this one has stood long enough. Never reads on this thread. */
    public Verdict verdict() {
        ask();
        return state.get().verdict();
    }

    /** Asks for a read, unless one is running or the last still stands. */
    public void ask() {
        State current = state.get();
        Duration floor = current.verdict() == Verdict.READY ? STEADY : UNSETTLED;
        if (current.readAt() != null && clock.instant().isBefore(current.readAt().plus(floor))) {
            return;
        }
        if (!reading.compareAndSet(false, true)) {
            return;
        }
        try {
            reader.execute(this::readAndRelease);
        } catch (RejectedExecutionException noThread) {
            reading.set(false);
        }
    }

    private void readAndRelease() {
        try {
            refreshNow();
        } finally {
            reading.set(false);
        }
    }

    /** One read on the calling thread: the first read's body, and a suite's look at what the database says now. */
    public void refreshNow() {
        State before = state.get();
        try {
            Map<String, SchemaVersion> found = applied.read();
            List<String> behind = expected.behindIn(found);
            Verdict verdict = behind.isEmpty() ? Verdict.READY : Verdict.BEHIND;
            if (verdict != before.verdict()) {
                record(verdict, behind);
            }
            settle(verdict, false);
        } catch (SQLException failure) {
            boolean refused = failure.getSQLState() != null && failure.getSQLState().startsWith(REFUSED_CLASS);
            if (!before.failing()) {
                log.warn("The schema-version read failed: {} (SQLState {}); {}", failure.getClass().getSimpleName(), failure.getSQLState(),
                        refused ? "this instance stays out of service until it can be read" : "the verdict stands");
            }
            settle(refused ? Verdict.UNPROVEN : before.verdict(), true);
        } catch (RuntimeException unreadable) {
            log.warn("The schema-version read returned something that is not a version: {}", unreadable.getClass().getSimpleName());
            settle(Verdict.UNPROVEN, true);
        }
    }

    /** Stamped when the read has ended, so a read that waited out the pool does not count its wait against its floor. */
    private void settle(Verdict verdict, boolean failing) {
        state.set(new State(verdict, clock.instant(), failing));
    }

    private static void record(Verdict verdict, List<String> behind) {
        if (verdict == Verdict.READY) {
            log.info("Every schema is at or past what this build expects");
        } else {
            log.warn("This instance is out of service until the migration has run: {}", behind);
        }
    }
}
