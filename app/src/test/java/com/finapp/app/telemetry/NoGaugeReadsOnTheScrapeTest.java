package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.metrics.GaugeRefresher;
import com.finapp.platform.metrics.RefreshedReading;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;

/**
 * <strong>No gauge reads the database on the scrape thread</strong> (`P15-TSK-024`; ADR-0124 decision 7;
 * {@code THREAT_MODEL.md} T14), proven over every meter of the real context, never a list of them by name.
 *
 * <p>The real context's beans, its real {@link OffScrapeMeterRegistry}, over a data source that records every connection
 * asked for on the thread that is scraping ({@link ScrapeThreadSource}). One scrape renders every meter, and every meter's
 * measurements are read once more on the same thread, so a gauge added tomorrow - by any module, or by a library binder -
 * is rendered here with no edit: if it reads the database on the scrape, this is red. Two planted gauges show the guard
 * is live: one outside the platform's names, reading the database on the scrape, is caught; one inside them is moved off.
 *
 * <p>The test resources' overlay is loaded, deliberately: it configures every conditional surface (the provider
 * endpoints), so the most meters exist, and nothing in it touches the mechanism - the registry has no switch. The first
 * reads' default is {@code AccountingGaugeWiringTest}'s, on production's own file.
 *
 * <p><strong>The main case runs first, on caches nothing has filled</strong>: the overlay turns the first reads off, so
 * no reading of this context has been read, and every gauge class's own floor is cold, so a read on the scrape thread
 * would reach the database rather than a class's cache. It asserts that precondition. Its probe found the need: a look
 * made to read in line passed here when a planted case's scrape, run first, had filled every cache within its floor.
 */
@Tag("slice")
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("no gauge reads the database on the scrape thread: every meter of the real context, rendered (P15-TSK-024)")
class NoGaugeReadsOnTheScrapeTest {

    private static final ScrapeThreadSource SOURCE = new ScrapeThreadSource();
    private static final Duration WAIT = Duration.ofMinutes(2);

    @TestConfiguration
    static class WatchingTheScrape {

        @Bean
        static BeanPostProcessor watchingDataSource() {
            return SOURCE.postProcessor();
        }
    }

    @Autowired private MeterRegistry registry;
    @Autowired private GaugeRefresher refresher;
    @Autowired private ConfigurableApplicationContext context;

    @AfterAll
    static void forget() {
        SOURCE.scrapingOn(null);
    }

    @Test
    @Order(1)
    @DisplayName("every finapp function-backed meter of the context is served off the scrape, and one scrape of every meter, with every meter's measurements read again on the same thread, asks the database for nothing; the refresher reads instead")
    void noGaugeReadsTheDatabaseOnTheScrapeThread() throws Exception {
        assertThat(refresher.tracked()).as("the first look at a context nothing has read: every gauge class's cache is cold, so a read on"
                + " the scrape thread would reach the database").isNotEmpty().noneMatch(RefreshedReading::hasBeenRead);
        OffScrapeMeterRegistry offScrape = RefreshedGauges.offScrape(registry);
        Set<Meter.Id> functionBacked = registry.getMeters().stream()
                .filter(meter -> meter instanceof Gauge || meter instanceof FunctionCounter)
                .map(Meter::getId).filter(id -> id.getName().startsWith("finapp.")).collect(Collectors.toSet());
        assertThat(offScrape.servedIds()).as("every finapp gauge and function counter of the context is served off the scrape").containsAll(functionBacked);
        Set<String> modules = functionBacked.stream().map(id -> id.getName().split("\\.")[1]).collect(Collectors.toCollection(TreeSet::new));
        assertThat(modules).as("the census reaches every module that reads a gauge, so a context that lost its beans cannot pass")
                .contains("accounting", "bnpl", "credit", "crossborder", "fx", "identity", "kyc", "ledger", "lending", "merchant", "outbox",
                        "payments", "reconciliation", "risk", "settlement", "gauges");

        assertThat(violationsOfAScrape()).as("connections asked for on the scrape thread, every meter rendered").isEmpty();

        assertThat(offScrape.refreshAll(WAIT)).as("the refresher read every reading").isTrue();
        assertThat(SOURCE.askedOnTheRefresher().size()).as("the reads moved to the refresher's thread, not away: each of the sixty"
                + " database-reading classes asks at least once for its first read").isGreaterThanOrEqualTo(60);
        assertThat(registry.get(GaugeRefresherBeans.REFRESHED).gauge().value()).as("the canary, read on the refresher").isEqualTo(1.0);
        assertThat(violationsOfAScrape()).as("and again once every reading has been read").isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("the guard is live: a planted gauge outside the platform's names, reading the database on the scrape, is caught; one inside them is moved off the scrape")
    void aPlantedReadOnTheScrapeIsCaught() throws Exception {
        Gauge outside = Gauge.builder("planted.scrape.read", () -> read()).strongReference(true).register(registry);
        try {
            assertThat(violationsOfAScrape()).as("a library-style gauge reading the database on the scrape").isNotEmpty()
                    .anySatisfy(violation -> assertThat(violation).contains(NoGaugeReadsOnTheScrapeTest.class.getName()));
        } finally {
            registry.remove(outside);
        }
        assertThat(violationsOfAScrape()).as("removed, the scrape is clean again").isEmpty();

        int before = SOURCE.askedOnTheRefresher().size();
        Gauge inside = Gauge.builder("finapp.planted.read", () -> read()).strongReference(true).register(registry);
        try {
            assertThat(violationsOfAScrape()).as("a finapp gauge added tomorrow reads off the scrape with no edit").isEmpty();
            assertThat(RefreshedGauges.offScrape(registry).refresh("finapp.planted.", WAIT)).isTrue();
            assertThat(SOURCE.askedOnTheRefresher().size()).as("it read, on the refresher").isGreaterThan(before);
            assertThat(inside.value()).as("an absent database: no value, never a zero").isNaN();
        } finally {
            registry.remove(inside);
        }
    }

    @Test
    @Order(3)
    @DisplayName("the context stopped and started (PI-014 item 2): the refresher stops with it, starts again, and every reading it holds answers - the canary and every configuration gauge a number")
    void theRefresherRestartsWithTheContext() throws Exception {
        assertThat(refresher.isRunning()).isTrue();
        context.stop();
        assertThat(refresher.isRunning()).as("stopped with the context").isFalse();
        context.start();
        assertThat(refresher.isRunning()).as("started again with it").isTrue();
        assertThat(RefreshedGauges.offScrape(registry).refreshAll(WAIT)).as("the restarted refresher takes every read").isTrue();
        assertThat(registry.get(GaugeRefresherBeans.REFRESHED).gauge().value()).isEqualTo(1.0);
        List<Gauge> flags = registry.getMeters().stream().filter(Gauge.class::isInstance).map(Gauge.class::cast)
                .filter(gauge -> gauge.getId().getName().endsWith(".enabled")).toList();
        assertThat(flags).as("the census: the schedules' flags").hasSizeGreaterThanOrEqualTo(20);
        assertThat(flags).allSatisfy(flag -> assertThat(flag.value()).as(flag.getId().toString()).isNotNaN());
    }

    /** One scrape of every meter, and every meter's measurements read again, on this thread: the connections asked for on it. */
    private List<String> violationsOfAScrape() {
        SOURCE.clear();
        SOURCE.scrapingOn(Thread.currentThread());
        try {
            String exposition = RefreshedGauges.offScrape(registry).scrape();
            assertThat(exposition).as("the scrape renders").contains("finapp_outbox_pending");
            for (Meter meter : registry.getMeters()) {
                StreamSupport.stream(meter.measure().spliterator(), false).forEach(Measurement::getValue);
            }
        } finally {
            SOURCE.scrapingOn(null);
        }
        return List.copyOf(SOURCE.askedOnTheScrape());
    }

    /** A gauge that reads the database: through the context's own (wrapped) data source. */
    private static double read() {
        return SOURCE.plantedRead();
    }
}
