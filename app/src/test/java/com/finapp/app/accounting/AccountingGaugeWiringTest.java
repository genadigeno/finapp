package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.metrics.GaugeRefresher;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The production wiring (`P14-TSK-020`'s independent review, I-1): <strong>every accounting read gauge of the real context reads on the refresher's thread, and a caller of any of them never touches the pool.</strong> A suite's in-line refresher hides the async path, so this boots the real beans over a data
 * source that holds the refresher's thread on a latch and records who else asked. The gauges come from the live registry, so a gauge wired to read on the caller fails here with no edit. It reads accounting's gauges one by one; every gauge of every module, rendered by a scrape, is {@code NoGaugeReadsOnTheScrapeTest}'s.
 * <em>(`P15-TSK-024`, 2026-11-08: the refresher is the platform's {@link GaugeRefresher}, behind every {@code finapp.} gauge, where it was accounting's own; read "the other modules' classes still read on the scrape thread, which is the debt row" here until then.)</em>
 *
 * <p><strong>On production's own configuration</strong>: {@code spring.config.location} names the main {@code application.yaml} alone, so the test resources' overlay, which turns the first reads off as it does every background worker, is not loaded and nothing here turns them back on. What holds the thread is what the code's default and production's file make of {@code finapp.gauges.read-at-start} (named {@code finapp.accounting.gauges.read-at-start} until `P15-TSK-024`): a default flipped, or the file setting it, fails here. So the workers that overlay holds off do run, against the closed port; the context is closed with the class, so they stop with it.
 */
@Tag("slice")
@SpringBootTest(properties = {"spring.config.location=classpath:/application.yaml", "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent", "finapp.accounting.sweeper.enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("every accounting gauge of the real context reads on the refresher's thread (P14-TSK-020)")
class AccountingGaugeWiringTest {

    private static final HeldConnectionSource SOURCE = new HeldConnectionSource(GaugeRefresher.THREAD);

    @TestConfiguration
    static class HoldingTheRefresher {

        @Bean
        static BeanPostProcessor holdingDataSource() {
            return SOURCE.postProcessor();
        }
    }

    @Autowired private MeterRegistry registry;
    @Autowired private GaugeRefresher refresher;
    @Autowired private Environment environment;

    @AfterAll
    static void letItGo() {
        SOURCE.release();
    }

    @Test
    @DisplayName("on production's configuration, the context's start asks for the first reads on the refresher's thread, which holds one connection; every read gauge answers no value to its caller at once, and the caller never asks the pool")
    void everyGaugeReadsOnTheRefresher() throws Exception {
        assertThat(environment.getProperty("finapp.outbox.relay.enabled")).as("the census: the test resources' overlay is not loaded").isNotEqualTo("false");
        assertThat(refresher.isRunning()).as("started with the context, closed with it").isTrue();
        assertThat(SOURCE.awaitHeld(60)).as("the start asked for a first read, and it is on the refresher's thread").isTrue();

        List<Meter> gauges = registry.getMeters().stream().filter(Gauge.class::isInstance)
                .filter(meter -> meter.getId().getName().startsWith("finapp.accounting."))
                .filter(meter -> !meter.getId().getName().equals("finapp.accounting.sweeper.enabled")).toList();
        assertThat(gauges.stream().map(meter -> meter.getId().getName()).distinct().toList()).as("the census: eleven gauges (P15-TSK-035's two census gauges with them)").hasSize(11);

        SOURCE.callerIs(Thread.currentThread());
        for (Meter meter : gauges) {
            assertThat(((Gauge) meter).value()).as("%s, the refresher held", meter.getId()).isNaN();
        }
        assertThat(SOURCE.askedByTheCaller()).as("a caller of an accounting gauge never touches the pool").isEmpty();
        assertThat(SOURCE.held()).as("one connection held, the other reads waiting in the one thread's queue").containsExactly(GaugeRefresher.THREAD);
    }
}
