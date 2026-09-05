package com.finapp.app.database;

/**
 * The arithmetic that decides whether a fleet of instances fits inside the database's connection
 * limit.
 *
 * <p>Pure, so the relationship can be tested at its boundaries without a Spring context or a
 * database. {@link ConnectionPoolSizingGuard} is what applies it to the real configuration.
 *
 * @param instances the maximum number of application instances that may run at once. ADR-0014:
 *     never 1
 * @param maximumPoolSize connections each instance may hold, from
 *     {@code spring.datasource.hikari.maximum-pool-size}
 * @param serverMaxConnections what the PostgreSQL server is configured with
 * @param reservedConnections connections the fleet must not consume — see
 *     {@link #headroomFor(int, int)}
 */
public record ConnectionPoolSizing(
        int instances, int maximumPoolSize, int serverMaxConnections, int reservedConnections) {

    public ConnectionPoolSizing {
        requirePositive(instances, "instances");
        requirePositive(maximumPoolSize, "maximumPoolSize");
        requirePositive(serverMaxConnections, "serverMaxConnections");
        if (reservedConnections < 0) {
            throw new IllegalArgumentException("reservedConnections must not be negative");
        }
    }

    /** What the fleet consumes in the worst case, which is the only case that matters. */
    public int fleetDemand() {
        return Math.multiplyExact(instances, maximumPoolSize);
    }

    /** What the fleet is allowed to consume. */
    public int available() {
        return serverMaxConnections - reservedConnections;
    }

    public boolean fits() {
        return fleetDemand() <= available();
    }

    /**
     * The largest pool size that would fit, for the failure message.
     *
     * <p>Reported rather than applied. A guard that silently shrank the pool to make the numbers
     * work would be changing a deployment's capacity on its own initiative, at startup, with
     * nothing saying so — and the correct response to "the fleet does not fit" is often to raise
     * {@code max_connections} or to run fewer instances, which is a decision this code has no
     * business taking.
     */
    public int largestPoolThatFits() {
        return Math.max(0, available() / instances);
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive but was " + value);
        }
    }

    /**
     * A note on the two numbers a reader will want to argue about.
     *
     * <p><strong>Why not simply divide {@code max_connections} by the instance count?</strong>
     * Because that treats the connection limit as a budget to spend, and it is a ceiling not to
     * hit. Every connection is a backend process on the server with its own memory, and PostgreSQL
     * throughput does not increase with pool size past roughly the point where the machine's cores
     * are busy — beyond it, the extra connections queue <em>inside</em> the database, where the
     * queueing is invisible to the application and shows up as latency on every query rather than
     * as a pool timeout on one.
     *
     * <p>So the pool is sized small on purpose, and this check answers a different question: given
     * a pool chosen for throughput, does the whole fleet still fit? Those are separate decisions
     * and conflating them produces a pool that is both too large and, at scale, still not enough.
     *
     * <p><strong>Why reserve anything at all?</strong> Two reasons, and the second is the one
     * people forget. PostgreSQL keeps {@code superuser_reserved_connections} (3 by default) for
     * superusers, so they were never ours to allocate. And a migration runs as
     * {@code finapp_migrator} during a deploy, while an operator diagnosing an incident connects
     * with {@code psql} — if the fleet is sized to consume every remaining connection, the one
     * thing nobody can do when the fleet is in trouble is connect to the database to find out why.
     */
    public static int headroomFor(int superuserReserved, int operationalReserved) {
        return Math.addExact(superuserReserved, operationalReserved);
    }
}
