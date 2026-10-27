package com.finapp.app.risk;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An instance clock exactly {@code skew} from the database server's, tracked (`P13-TST-001`): the storm's instances run
 * the plan's "clocks +/-5 s" against the server's own time - the reference every risk tolerance is written against
 * ({@code VelocityProof#SKEW}, the windows' cut).
 *
 * <p><strong>Why not {@code ServerSkewedClock}</strong> (anchored once): the Docker VM's clock this tier runs on is
 * measured running about 8% fast and stepping back about 2.6 s every 22 s against the host's - so a clock anchored to the
 * server once and then ticking with the host's drifts up to 7.6 s from it, past the plan's bound, and the velocity proof's
 * journal link (instance-stamped postings judged against the server's cuts within 5 s) misjudged the storm's first
 * transfers on its first run. This clock re-reads the server's time every 20 ms ({@code clock_timestamp()}, the round
 * trip halved) and answers the host's instant moved by the measured offset and the skew - never backwards (a JVM's clock
 * is monotonic in practice): after the server steps back it holds until the server catches up, so a clock behind the
 * server stays within the bound and one ahead of it is, for those seconds, further ahead.
 */
final class RiskStormClock extends Clock {

    private static final Duration PERIOD = Duration.ofMillis(20);
    private static volatile Duration offset = Duration.ZERO;
    private static volatile Thread tracker;
    /** The latency phases' offset, frozen when they begin - null while the clocks track the server. */
    private static volatile Duration frozen;

    private final Duration skew;
    private final AtomicReference<Instant> last = new AtomicReference<>(Instant.EPOCH);

    private RiskStormClock(Duration skew) {
        this.skew = skew;
    }

    /** A clock {@code skew} from the server's, the tracker started on first use. */
    static synchronized RiskStormClock of(Duration skew) throws Exception {
        if (tracker == null) {
            offset = measure();
            Thread thread = new Thread(RiskStormClock::track, "risk-storm-clock-tracker");
            thread.setDaemon(true);
            thread.start();
            tracker = thread;
        }
        return new RiskStormClock(skew);
    }

    private static void track() {
        while (true) {
            try (Connection server = DatabaseRoles.bootstrap();
                    PreparedStatement read = server.prepareStatement("SELECT clock_timestamp()")) {
                while (true) {
                    offset = measure(read);
                    Thread.sleep(PERIOD.toMillis());
                }
            } catch (InterruptedException interrupted) {
                return;
            } catch (Exception lost) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    private static Duration measure() throws Exception {
        try (Connection server = DatabaseRoles.bootstrap();
                PreparedStatement read = server.prepareStatement("SELECT clock_timestamp()")) {
            return measure(read);
        }
    }

    private static Duration measure(PreparedStatement read) throws Exception {
        Instant before = Instant.now();
        Instant server;
        try (ResultSet row = read.executeQuery()) {
            row.next();
            server = row.getTimestamp(1).toInstant();
        }
        Instant after = Instant.now();
        Instant middle = before.plus(Duration.between(before, after).dividedBy(2));
        return Duration.between(middle, server);
    }

    /**
     * The latency phases' clocks (`RiskStormLatencyReport`): every instance at the host's speed, unskewed, off the
     * server's offset frozen now. A clock that tracks the server holds still for the ~2.6 s of each of its steps back and
     * runs ~8% fast between them - so T-eval's {@code latency_micros}, measured on it, read zero for a sample in a step
     * and ~8% long otherwise; a measurement needs the host's own seconds. Unskewed, the instances stay within the
     * server's step (~2.6 s) of its clock - inside every tolerance written against it ({@code VelocityProof#SKEW}). Called
     * at rest: the instance ahead holds still for its skew's 5 s, the one behind steps forward.
     */
    static void atHostSpeed() {
        frozen = offset;
    }

    @Override
    public Instant instant() {
        Duration atSpeed = frozen;
        Instant candidate = atSpeed != null ? Instant.now().plus(atSpeed) : Instant.now().plus(offset).plus(skew);
        return last.accumulateAndGet(candidate, (previous, next) -> next.isAfter(previous) ? next : previous);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        if (ZoneOffset.UTC.equals(zone)) {
            return this;
        }
        RiskStormClock tracked = this;
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId other) {
                return tracked.withZone(other);
            }

            @Override
            public Instant instant() {
                return tracked.instant();
            }
        };
    }
}
