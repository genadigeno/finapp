package com.finapp.app.risk;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Waits for the database's clock (P13-DOC-001's database pass). The monitoring intake reads a journal entry only once
 * its minting millisecond - stamped by the instance that minted its UUIDv7 - lies behind {@code clock_timestamp()} less
 * the settle horizon; a suite driving the intake with a zero horizon read nothing of what it had just posted whenever the
 * instance's clock ran ahead of the database's (this machine's Docker clock ran ~1.5 s behind the JVM). The instances'
 * skew is the system's normal condition (the plan's five seconds either way), so the suites wait for the database to reach the instant
 * rather than assume the two clocks agree.
 */
final class DatabaseClock {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    private DatabaseClock() {}

    /** Until the database's {@code clock_timestamp()} is strictly past {@code instant} (bounded by thirty seconds). */
    static void awaitPast(Instant instant) {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        try (Connection connection = DatabaseRoles.application();
                PreparedStatement select =
                        connection.prepareStatement("SELECT clock_timestamp() > CAST(? AS timestamptz)")) {
            select.setObject(1, instant.atOffset(ZoneOffset.UTC));
            while (System.nanoTime() < deadline) {
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    if (row.getBoolean(1)) {
                        return;
                    }
                }
                Thread.sleep(20);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
        throw new AssertionError("the database's clock never passed " + instant + " within " + PATIENCE);
    }

    /** Until the database's clock is past this instance's present - every entry minted so far is then settled. */
    static void awaitPastNow() {
        awaitPast(Instant.now());
    }
}
