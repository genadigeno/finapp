package com.finapp.app.database;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the fleet's connection pools could not all fit inside the database's
 * connection limit.
 *
 * <h2>The failure this exists to stop</h2>
 *
 * <p>Hikari's default pool is 10 connections and PostgreSQL's default {@code max_connections} is
 * 100, so <strong>ten instances exhaust the server before a single connection does any work</strong>
 * — and ADR-0014 says N is never 1. Nothing in the defaults notices: each instance starts happily,
 * fills its pool, and the instances that lose the race fail readiness with
 * {@code connection is not available}. That reads as the pool being too small, or the database
 * being slow, and it is neither. It is arithmetic that nobody did.
 *
 * <p>It is the worst shape of operational failure: it appears only under the conditions that make
 * it hardest to diagnose — a full deploy, a scale-out, or a restart storm — and the symptom points
 * away from the cause.
 *
 * <h2>Why a startup guard rather than a runbook</h2>
 *
 * <p>The relationship holds between four numbers that live in three different places: the pool size
 * is application configuration, the instance count is a deployment's replica count, and
 * {@code max_connections} is a database setting. Nothing brings them together, so nothing notices
 * when one moves — and every one of them is changed by someone who has no reason to be thinking
 * about the other two. Scaling from eight instances to twelve is an ordinary operational act.
 *
 * <p>So the numbers are declared as configuration and the relationship is checked here, where a
 * violation stops the instance rather than being discovered by the instance after it.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It does not shrink the pool to make the numbers work. That would change a deployment's
 * capacity on its own initiative, silently, and the right answer is often to raise
 * {@code max_connections} or run fewer instances — decisions this code has no business taking.
 *
 * <p>It also cannot verify {@code max_connections} against the live server, and does not pretend
 * to: the guard runs before the pool is used, the application role may not be able to read the
 * setting, and a guard that queried the database would fail for a database that is merely down.
 * {@link #SERVER_MAX} is therefore a <em>declaration</em> by the deployment, and a wrong
 * declaration is a wrong answer — which is why the value is configuration a deployment must state
 * rather than a default that would quietly be 100 everywhere.
 *
 * <h2>Fixed-size pools</h2>
 *
 * <p>The arithmetic assumes each instance holds its full pool, which is true here because
 * {@code minimum-idle} equals {@code maximum-pool-size}. That is deliberate on both counts: a
 * fixed-size pool is what HikariCP recommends, and a pool that only sometimes reaches its maximum
 * would make this check meaningless — the worst case is what exhausts a server, not the average.
 */
@Component
public class ConnectionPoolSizingGuard {

    /** The maximum number of instances that may run concurrently. A deployment states it. */
    static final String INSTANCES = "finapp.database.instances";

    /** What the PostgreSQL server is configured with. A deployment states it. */
    static final String SERVER_MAX = "finapp.database.server-max-connections";

    /** Connections the fleet must not consume. See {@link ConnectionPoolSizing#headroomFor}. */
    static final String RESERVED = "finapp.database.reserved-connections";

    /** Hikari's own setting, read rather than restated so the two cannot drift. */
    static final String POOL_SIZE = "spring.datasource.hikari.maximum-pool-size";

    ConnectionPoolSizingGuard(Environment environment) {
        verify(
                new ConnectionPoolSizing(
                        required(environment, INSTANCES),
                        required(environment, POOL_SIZE),
                        required(environment, SERVER_MAX),
                        required(environment, RESERVED)));
    }

    /**
     * @throws IllegalStateException if the fleet could exhaust the server's connection limit
     */
    public static void verify(ConnectionPoolSizing sizing) {
        if (sizing.fits()) {
            return;
        }
        throw new IllegalStateException(
                "Refusing to start: "
                        + sizing.instances()
                        + " instances x "
                        + sizing.maximumPoolSize()
                        + " connections = "
                        + sizing.fleetDemand()
                        + ", which exceeds the "
                        + sizing.available()
                        + " available ("
                        + sizing.serverMaxConnections()
                        + " server max_connections less "
                        + sizing.reservedConnections()
                        + " reserved). Lower "
                        + POOL_SIZE
                        + " to at most "
                        + sizing.largestPoolThatFits()
                        + ", run fewer instances, or raise the server's max_connections and "
                        + SERVER_MAX
                        + " with it. See docs/architecture/DISTRIBUTED_EXECUTION.md.");
    }

    /**
     * Reads a required integer setting.
     *
     * <p>Absent is a failure rather than a default. A default here would be a number nobody chose
     * standing in for a deployment fact, which is exactly the situation this guard exists to end —
     * and it would make the check pass on an assumption while reporting that it had verified
     * something.
     */
    private static int required(Environment environment, String key) {
        Integer value = environment.getProperty(key, Integer.class);
        if (value == null) {
            throw new IllegalStateException(
                    "Refusing to start: " + key + " is not set, so the fleet's connection demand "
                            + "cannot be checked against the database's limit. Every value in that "
                            + "relationship is a deployment fact and none of them has a safe default.");
        }
        return value;
    }
}
