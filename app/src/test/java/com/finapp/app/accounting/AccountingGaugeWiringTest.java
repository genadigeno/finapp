package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * The production wiring (`P14-TSK-020`'s independent review, I-1): <strong>every accounting read gauge of the real context reads on the refresher's thread, and a caller of any of them never touches the pool.</strong> A suite's in-line refresher hides the async path, so this boots the real beans over a data
 * source that holds the refresher's thread on a latch and records who else asked. The gauges come from the live registry, so a gauge wired to read on the caller fails here with no edit. It reads the gauges one by one and never scrapes: the other modules' classes still read on the scrape thread, which is the debt row and no part of this. The first reads, which the test contexts turn off, are turned on here: they are what holds the thread.
 */
@Tag("slice")
@SpringBootTest(properties = {"spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent", "finapp.accounting.sweeper.enabled=false", "finapp.accounting.gauges.read-at-start=true"})
@DisplayName("every accounting gauge of the real context reads on the refresher's thread (P14-TSK-020)")
class AccountingGaugeWiringTest {

    private static final HeldConnectionSource SOURCE = new HeldConnectionSource(AccountingGaugeRefresher.THREAD);

    @TestConfiguration
    static class HoldingTheRefresher {

        @Bean
        static BeanPostProcessor holdingDataSource() {
            return SOURCE.postProcessor();
        }
    }

    @Autowired private MeterRegistry registry;
    @Autowired private AccountingGaugeRefresher refresher;

    @AfterAll
    static void letItGo() {
        SOURCE.release();
    }

    @Test
    @DisplayName("the context's start asks for the first reads on the refresher's thread, which holds one connection; every read gauge answers no value to its caller at once, and the caller never asks the pool")
    void everyGaugeReadsOnTheRefresher() throws Exception {
        assertThat(refresher.isRunning()).as("started with the context, closed with it").isTrue();
        assertThat(SOURCE.awaitHeld(60)).as("the start asked for a first read, and it is on the refresher's thread").isTrue();

        List<Meter> gauges = registry.getMeters().stream().filter(Gauge.class::isInstance)
                .filter(meter -> meter.getId().getName().startsWith("finapp.accounting."))
                .filter(meter -> !meter.getId().getName().equals("finapp.accounting.sweeper.enabled")).toList();
        assertThat(gauges.stream().map(meter -> meter.getId().getName()).distinct().toList()).as("the census: nine gauges").hasSize(9);

        SOURCE.callerIs(Thread.currentThread());
        for (Meter meter : gauges) {
            assertThat(((Gauge) meter).value()).as("%s, the refresher held", meter.getId()).isNaN();
        }
        assertThat(SOURCE.askedByTheCaller()).as("a caller of an accounting gauge never touches the pool").isEmpty();
        assertThat(SOURCE.held()).as("one connection held, the other reads waiting in the one thread's queue").containsExactly(AccountingGaugeRefresher.THREAD);
    }
}
