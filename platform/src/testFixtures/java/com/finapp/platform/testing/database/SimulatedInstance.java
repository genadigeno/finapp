package com.finapp.platform.testing.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * One simulated instance of the platform: its own connection, and its own clock.
 *
 * <h2>Why a test needs this</h2>
 *
 * <p>ADR-0014 says N is never 1, and {@code P0-TSK-016}'s lease defect passed every test because
 * every test ran in one JVM with one clock. A concurrency test that shares a connection
 * <em>serialises itself</em> — two "instances" on one connection cannot contend for a row lock,
 * because the second statement simply waits for the first on the same session. And a test that
 * shares a clock cannot see skew at all: the defect it is meant to catch is two instances
 * disagreeing about what time it is.
 *
 * <p>Both look like concurrency tests and prove far less. This makes the two things a simulated
 * instance must have its own of explicit, so a test cannot quietly omit one.
 *
 * <h2>The trap this exists to remove</h2>
 *
 * <p><strong>A skewed clock must be skewed relative to the <em>server</em>, not to a fixture
 * constant.</strong> That is not a nicety — the audit for {@code P0-TST-009} found
 * {@code clockSkewCannotStealALiveClaim} giving its "fast" instance
 * {@code Clock.fixed(FIXED.plus(1 hour))} where {@code FIXED} is a hard-coded
 * {@code 2026-09-01T12:00:00Z}. Measured against the running container, that clock was about
 * <strong>forty hours behind</strong> the server, not an hour ahead. The test passed, and it passed
 * under a deliberate reintroduction of the very defect it was written for.
 *
 * <p>Leases, retention and eligibility are all decided by the server's clock
 * ({@code DISTRIBUTED_EXECUTION.md} §3). A skew test therefore has to anchor on the server's
 * {@code now()}, which is what {@link #skewedBy} does.
 */
public final class SimulatedInstance implements AutoCloseable {

    private final Connection connection;
    private final Clock clock;

    private SimulatedInstance(Connection connection, Clock clock) {
        this.connection = connection;
        this.clock = clock;
    }

    /**
     * An instance whose clock agrees with the database's, on its own connection.
     *
     * <p>The baseline: no skew, but still a separate session, so two of these genuinely contend.
     */
    public static SimulatedInstance inAgreementWithTheServer() throws SQLException {
        return skewedBy(Duration.ZERO);
    }

    /**
     * An instance whose clock is {@code skew} away from the <strong>server's</strong> clock.
     *
     * <p>Positive skew is a fast instance — the dangerous direction for a lease, because a fast
     * instance is the one that believes its neighbour's fresh claim has already expired.
     *
     * <p>The clock is fixed rather than ticking: a test asserting a decision about time wants that
     * decision to be reproducible, and a ticking clock makes the same assertion pass or fail
     * depending on how long the test took.
     */
    public static SimulatedInstance skewedBy(Duration skew) throws SQLException {
        Connection connection = DatabaseRoles.application();
        connection.setAutoCommit(false);
        return new SimulatedInstance(connection, Clock.fixed(serverNow().plus(skew), ZoneOffset.UTC));
    }

    /**
     * The database's current time.
     *
     * <p>Read from the server on purpose. The JVM's own clock is not the reference: on this
     * project's local Docker VM the container clock drifts behind the host and is corrected
     * backwards ({@code CURRENT_STATE.md} §Local Environment Prerequisites), so a skew computed
     * from {@code Instant.now()} would be wrong by an unknown amount in an unknown direction.
     */
    public static Instant serverNow() throws SQLException {
        try (Connection reader = DatabaseRoles.application();
                Statement statement = reader.createStatement();
                ResultSet rows = statement.executeQuery("SELECT now()")) {
            rows.next();
            return rows.getTimestamp(1).toInstant();
        }
    }

    /** This instance's own session. Never shared: two instances sharing one serialise themselves. */
    public Connection connection() {
        return connection;
    }

    /** This instance's own clock, which other instances need not agree with. */
    public Clock clock() {
        return clock;
    }

    public void commit() throws SQLException {
        connection.commit();
    }

    public void rollback() throws SQLException {
        connection.rollback();
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
