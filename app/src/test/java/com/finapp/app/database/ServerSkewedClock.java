package com.finapp.app.database;

import com.finapp.platform.testing.database.SimulatedInstance;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * A TICKING instance clock {@code skew} away from the database server's (`X-TSK-013`).
 *
 * <p>{@link SimulatedInstance#skewedBy} anchors on the server for the same reason - the local Docker
 * VM's clock reads anywhere from about a second behind the host to two-thirds of a second ahead, so a
 * skew computed from the JVM would be wrong by an unknown amount in an unknown direction - but its
 * clock is fixed. A component that runs a whole flight on its clock (a takeover, a sweep) needs one
 * that ticks, so it reads its moments in order.
 */
public final class ServerSkewedClock {

    private ServerSkewedClock() {}

    public static Clock of(Duration skew) throws SQLException {
        Instant server = SimulatedInstance.serverNow();
        Clock system = Clock.systemUTC();
        return Clock.offset(system, Duration.between(system.instant(), server).plus(skew));
    }
}
