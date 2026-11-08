package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic, at its boundaries (P1-TSK-004).
 *
 * <p>Hermetic and pure. The check that the <em>shipped configuration</em> actually satisfies the
 * relationship is {@link ConnectionPoolSizingIsConfiguredTest}, which reads the real
 * {@code application.yaml} — the two are different claims and one does not imply the other.
 *
 * <p>Since `P15-TSK-021` every instance holds two pools, the writer's and report generation's, and the relationship
 * counts both: {@code instances × (writer + reporting) ≤ max_connections − reserved}.
 */
@DisplayName("Connection-pool sizing (P1-TSK-004; both pools since P15-TSK-021)")
class ConnectionPoolSizingGuardTest {

    @Test
    @DisplayName("the documented default failure: 10 instances at Hikari's default pool of 10")
    void hikarisDefaultExhaustsPostgresDefault() {
        // The case the debt row and ADR-0014 both name, asserted rather than described. 10 x 10 is
        // 100 against a server that has 100 in total and has already reserved some - so the fleet
        // exhausts the database before a single connection does any work.
        ConnectionPoolSizing defaults = new ConnectionPoolSizing(10, 10, 1, 100, 12);

        assertThat(defaults.fits()).isFalse();
        assertThat(defaults.fleetDemand()).isEqualTo(110);
        assertThat(defaults.available()).isEqualTo(88);

        assertThatThrownBy(() -> ConnectionPoolSizingGuard.verify(defaults))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10 instances x (10 writer + 1 reporting) connections = 110")
                .hasMessageContaining("exceeds the 88 available")
                // The message must say what to do, not only that something is wrong: an operator
                // meeting this at 3am should not have to derive the fix.
                .hasMessageContaining("to at most 7");
    }

    @Test
    @DisplayName("the shipped relationship holds, and holds exactly at the boundary")
    void theBoundaryIsInclusive() {
        // 10 x (7 + 1) = 80 <= 88. Fits.
        assertThatCode(() -> ConnectionPoolSizingGuard.verify(new ConnectionPoolSizing(10, 7, 1, 100, 12)))
                .doesNotThrowAnyException();

        // Exactly equal must PASS. An off-by-one here would refuse a deployment that is precisely
        // sized, which is the deployment most likely to have been thought about.
        assertThatCode(() -> ConnectionPoolSizingGuard.verify(new ConnectionPoolSizing(11, 7, 1, 100, 12)))
                .doesNotThrowAnyException();
        assertThat(new ConnectionPoolSizing(11, 7, 1, 100, 12).fleetDemand())
                .isEqualTo(new ConnectionPoolSizing(11, 7, 1, 100, 12).available());

        // One more instance does not.
        assertThatThrownBy(
                        () -> ConnectionPoolSizingGuard.verify(new ConnectionPoolSizing(12, 7, 1, 100, 12)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the reporting pool is counted: a fleet whose writer pools alone fit is refused when its reporting"
            + " pools do not (P15-TSK-021)")
    void theReportingPoolIsCounted() {
        // 11 x 8 = 88 would fit on the writer alone; 11 x (8 + 1) = 99 does not. A guard that
        // forgot the second pool would start a fleet that exhausts the server under its first
        // round of reports.
        ConnectionPoolSizing both = new ConnectionPoolSizing(11, 8, 1, 100, 12);
        assertThat(both.perInstance()).isEqualTo(9);
        assertThat(both.fleetDemand()).isEqualTo(99);
        assertThatThrownBy(() -> ConnectionPoolSizingGuard.verify(both))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("(8 writer + 1 reporting)");
        assertThat(new ConnectionPoolSizing(10, 7, 2, 100, 12).fleetDemand()).isEqualTo(90);
        assertThat(new ConnectionPoolSizing(10, 7, 2, 100, 12).fits()).isFalse();
    }

    @Test
    @DisplayName("scaling out is what breaks it, which is why the instance count is configuration")
    void scalingOutIsCaught() {
        // The realistic path to this failure is not someone choosing a bad pool size. It is a
        // deployment scaling from a fleet that fits to one that does not, months later, by someone
        // changing a replica count.
        ConnectionPoolSizing fits = new ConnectionPoolSizing(10, 7, 1, 100, 12);
        assertThat(fits.fits()).isTrue();

        ConnectionPoolSizing scaledOut =
                new ConnectionPoolSizing(20, 7, 1, 100, 12);
        assertThat(scaledOut.fits()).isFalse();
        assertThat(scaledOut.largestPoolThatFits()).isEqualTo(3);
    }

    @Test
    @DisplayName("raising the server's limit is a legitimate fix, and is accepted")
    void raisingTheServerLimitIsAFix() {
        // The guard must not force the pool to be the thing that gives way. Twenty instances at
        // eight connections each is fine against a server configured for it.
        assertThatCode(() -> ConnectionPoolSizingGuard.verify(new ConnectionPoolSizing(20, 7, 1, 200, 12)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("reserved connections are subtracted, not decorative")
    void reservedConnectionsAreLoadBearing() {
        // Same fleet, same server, different reservation: the whole point of the reservation is
        // that it is unavailable, so removing it must change the answer.
        assertThat(new ConnectionPoolSizing(11, 7, 1, 100, 12).fits()).isTrue();
        assertThat(new ConnectionPoolSizing(12, 7, 1, 100, 12).fits()).isFalse();
        assertThat(new ConnectionPoolSizing(12, 7, 1, 100, 0).fits()).isTrue();
    }

    @Test
    @DisplayName("nonsense values are rejected rather than producing a nonsense answer")
    void nonsenseIsRejected() {
        // A zero or negative instance count would make largestPoolThatFits divide by zero, and a
        // zero pool would "fit" any fleet - a check that passes on absurd input is a check that
        // will one day pass on a typo.
        assertThatThrownBy(() -> new ConnectionPoolSizing(0, 7, 1, 100, 12))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionPoolSizing(10, 0, 1, 100, 12))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionPoolSizing(10, 7, 0, 100, 12))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionPoolSizing(10, 7, 1, 0, 12))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionPoolSizing(10, 7, 1, 100, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
