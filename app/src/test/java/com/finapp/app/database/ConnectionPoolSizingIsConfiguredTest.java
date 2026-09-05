package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * The relationship holds for the configuration this application actually ships (P1-TSK-004).
 *
 * <h2>Why this is separate from the arithmetic tests</h2>
 *
 * <p>{@link ConnectionPoolSizingGuardTest} proves the rule is right. This is about the shipped
 * numbers, which is the claim the acceptance criterion makes: *the relationship between instances,
 * pool size and max_connections is written down and checked rather than assumed*.
 *
 * <p><strong>How the build actually fails, stated accurately.</strong> The guard is a bean, so it
 * runs when this test's context starts — which means a violating configuration fails the context
 * before any assertion here is reached, and {@link #theShippedConfigurationFits()} can only ever be
 * evaluated in the case where the guard already passed. Its assertion is therefore not what catches
 * a violation; the guard is, and this test is what makes the guard run in the build rather than
 * only on a deployment's next restart. That distinction is worth writing down because the obvious
 * reading — "this test checks the arithmetic" — is wrong, and a later reader who deleted the guard
 * believing this test covered it would remove the only thing that does.
 *
 * <p>What this test uniquely contributes is the other two assertions: that the pool is fixed-size,
 * without which the arithmetic is a statement about the average rather than the worst case; and
 * that the guard is wired at all, without which every number could be correct while nothing
 * checked them at run time.
 *
 * <p>Values are read from the {@code Environment} rather than restated, so this cannot pass while
 * describing numbers the application does not use — the failure mode of every test that keeps its
 * own copy of the thing it checks.
 *
 * <h2>Hermetic</h2>
 *
 * <p>The datasource points at a closed port. The guard reads configuration and touches no database,
 * which is itself a property worth having: a sizing check that needed a database could not run in
 * the build that changes the sizing.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@Tag("slice")
@DisplayName("The shipped pool sizing fits the declared fleet (P1-TSK-004)")
class ConnectionPoolSizingIsConfiguredTest {

    @Autowired private Environment environment;

    @Test
    @DisplayName("every number in the relationship is declared, and together they fit")
    void theShippedConfigurationFits() {
        ConnectionPoolSizing shipped =
                new ConnectionPoolSizing(
                        setting(ConnectionPoolSizingGuard.INSTANCES),
                        setting(ConnectionPoolSizingGuard.POOL_SIZE),
                        setting(ConnectionPoolSizingGuard.SERVER_MAX),
                        setting(ConnectionPoolSizingGuard.RESERVED));

        assertThat(shipped.fits())
                .as(
                        "%d instances x %d connections = %d, against %d available (%d server max less "
                                + "%d reserved). Lower the pool to at most %d, run fewer instances, or "
                                + "raise the server's max_connections and %s with it.",
                        shipped.instances(),
                        shipped.maximumPoolSize(),
                        shipped.fleetDemand(),
                        shipped.available(),
                        shipped.serverMaxConnections(),
                        shipped.reservedConnections(),
                        shipped.largestPoolThatFits(),
                        ConnectionPoolSizingGuard.SERVER_MAX)
                .isTrue();
    }

    @Test
    @DisplayName("the pool is fixed-size, which is what makes the arithmetic mean anything")
    void thePoolIsFixedSize() {
        // The check assumes each instance holds its full pool. That is only true while minimum-idle
        // equals maximum-pool-size; if it did not, the arithmetic would be a statement about the
        // average, and a server is exhausted by the worst case rather than the average.
        //
        // Hikari's own default for minimum-idle is "same as maximum-pool-size", so this could be
        // left unset and still be true today. It is set explicitly and asserted here because the
        // arithmetic DEPENDS on it: a future change setting minimum-idle lower would silently turn
        // a proven property into an assumption.
        assertThat(setting("spring.datasource.hikari.minimum-idle"))
                .as("minimum-idle must equal maximum-pool-size, or the fleet demand is a guess")
                .isEqualTo(setting(ConnectionPoolSizingGuard.POOL_SIZE));
    }

    @Test
    @DisplayName("the guard is wired, so a violation stops an instance rather than being found later")
    void theGuardIsWired() {
        // Without this, every assertion above could hold while nothing checked the relationship at
        // run time - the build would be green and a scaled-out deployment would still exhaust the
        // database. The bean existing is what makes the check happen on every start.
        assertThat(environment).isNotNull();
        assertThat(guardIsPresent())
                .as("ConnectionPoolSizingGuard must be a bean, or nothing checks this at startup")
                .isTrue();
    }

    @Autowired(required = false)
    private ConnectionPoolSizingGuard guard;

    private boolean guardIsPresent() {
        return guard != null;
    }

    private int setting(String key) {
        Integer value = environment.getProperty(key, Integer.class);
        assertThat(value).as("%s must be declared in application.yaml", key).isNotNull();
        return value;
    }
}
