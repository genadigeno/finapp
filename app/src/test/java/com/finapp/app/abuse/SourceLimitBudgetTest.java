package com.finapp.app.abuse;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.abuse.SourceBucket;
import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * The counter's budget, read from the file production ships (`P15-TSK-022`): none of these numbers is copied here.
 *
 * <p>Two questions the design answered with arithmetic, held so a changed number has to answer them again: can the sweep keep up with
 * the worst the doors can create, and how much of the pool can counting take.
 */
@DisplayName("the counter's budget: the sweep outruns the doors, and counting leaves the pool to the work (P15-TSK-022)")
class SourceLimitBudgetTest {

    private static final StandardEnvironment SHIPPED = shipped();

    private static StandardEnvironment shipped() {
        try {
            StandardEnvironment environment = new StandardEnvironment();
            for (PropertySource<?> source : new YamlPropertySourceLoader()
                    .load("shipped", new FileSystemResource(RepositoryPaths.locate("app/src/main/resources/application.yaml")))) {
                environment.getPropertySources().addLast(source);
            }
            return environment;
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static int number(String key) {
        return Integer.parseInt(SHIPPED.getRequiredProperty(key));
    }

    private static Duration duration(String key) {
        return Duration.parse(SHIPPED.getRequiredProperty(key));
    }

    @Test
    @DisplayName("one instance's sweep deletes faster than every door together can create rows, at the worst the key space allows")
    void oneInstancesSweepOutrunsEveryDoor() {
        double worstRowsPerSecond = 0;
        for (String door : new String[] {"registration", "authentication", "recovery", "recovery-completion", "channel-verification"}) {
            worstRowsPerSecond += (double) (SourceBucket.BUCKETS + 1) / duration("finapp.abuse." + door + ".window").toSeconds();
        }
        double sweptPerSecond = (double) (number("finapp.abuse.sweep.batch-size") * com.finapp.app.eventing.RetentionSweepSchedule.MAX_BATCHES)
                / duration("finapp.abuse.sweep.interval").toSeconds();

        assertThat(sweptPerSecond)
                .as("%.0f rows a second could be created at worst; one instance's sweep deletes %.0f", worstRowsPerSecond, sweptPerSecond)
                .isGreaterThan(worstRowsPerSecond);
    }

    @Test
    @DisplayName("counting can hold at most half the pool, so a flood of counts cannot starve the requests they would admit")
    void countingLeavesMostOfThePool() {
        int pool = number("spring.datasource.hikari.maximum-pool-size");
        int counting = number("finapp.abuse.max-concurrent-counts");

        assertThat(counting * 2).as("%d counts in flight of a pool of %d", counting, pool).isLessThanOrEqualTo(pool);
        assertThat(counting).isPositive();
    }

    @Test
    @DisplayName("no shipped window is below the floor the budget is sized on")
    void noWindowIsBelowTheFloor() {
        for (String door : new String[] {"registration", "authentication", "recovery", "recovery-completion", "channel-verification"}) {
            assertThat(duration("finapp.abuse." + door + ".window")).as(door).isGreaterThanOrEqualTo(SourceLimits.MINIMUM_WINDOW);
        }
    }
}
