package com.finapp.app.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.finapp.app.FinappApplication;
import com.finapp.app.eventing.InboxConsumers;
import com.finapp.platform.metrics.GaugeRefresher;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.Ordered;

/**
 * The order a stopping instance lets go in, on a real context (`P15-TSK-008`, ADR-0120 decision 4): the production
 * configuration's own beans - all forty of the platform's lifecycle components running - stopped by Spring's own
 * lifecycle processor, never a list of phase numbers.
 *
 * <p><strong>How the order is observed.</strong> Each step is caught at the moment it happens, on the thread doing it: the
 * readiness event; Tomcat's own "Commencing graceful shutdown" and "Graceful shutdown complete"; each schedule's "drained"
 * line; the consumers' "stopping" and "stopped"; the gauge refresher's "stopped" (`platform`'s `GaugeRefresher`, behind every gauge since `P15-TSK-024`; the accounting gauges' own reader until then). At each, the state of everything else is
 * read there and then: readiness, how many schedules still claim, the consumers, the reader, whether the API port accepts
 * a connection, whether the management port answers, whether the pool is closed. A request accepted before the stop is held
 * across it, so the HTTP drain has something to drain.
 *
 * <p><strong>The context.</strong> {@code spring.config.location} names production's {@code application.yaml} alone - the
 * test overlay, which turns every background worker off, is not loaded - with the database and broker on closed ports, the
 * providers on the discard port so that their schedules exist, both ports free ({@code 0}), and the database's health
 * indicator off: readiness is then {@code readinessState} alone, so "refusing" is visible as a status rather than hidden
 * under a database that is down anyway.
 */
@Tag("slice")
@DisplayName("a stopping instance lets go in Spring's real order: readiness, HTTP, schedules, consumers, management, pool (P15-TSK-008)")
class ShutdownOrderTest {

    private static final String PROBE_PATH = "/__drain-probe";

    /** The platform's lifecycle components: thirty-seven schedules (`P15-TSK-022`'s window-counter sweep the latest), the inbox consumers, the gauge refresher's lifecycle (`P15-TSK-024`), the telemetry SDK's bounded flush (`P15-TSK-023`). */
    private static final int COMPONENTS = 40;

    private static final int SCHEDULES = 37;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    @DisplayName("readiness refuses first; the held request finishes while every schedule still runs and the management port"
            + " answers; every schedule drains before the consumers stop; the management port answers until they have; the"
            + " pool closes last")
    void theRealOrder() throws Exception {
        HeldRequest held = new HeldRequest();
        List<Moment> moments = Collections.synchronizedList(new ArrayList<>());
        ConfigurableApplicationContext context = start(held, moments, new HeldRequest());
        try {
            observeTheStop(context, held, moments);
        } finally {
            held.release.countDown();
            context.close();
        }
    }

    private static void observeTheStop(ConfigurableApplicationContext context, HeldRequest held, List<Moment> moments)
            throws Exception {
        Snapshotter snapshot = new Snapshotter(context);
        Recorder recorder = new Recorder(snapshot, moments);

        // The census: every one of the platform's lifecycle components exists and runs, bounded below the phase timeout.
        assertThat(snapshot.components).as("the platform's lifecycle components").hasSize(COMPONENTS);
        assertThat(snapshot.schedules).as("its schedules").hasSize(SCHEDULES);
        assertThat(snapshot.components.values()).allSatisfy(component -> assertThat(component.isRunning()).isTrue());
        assertThat(snapshot.schedules.values())
                .as("the database is absent, so the guard has proven nothing: every schedule in the real wiring is held by it (P15-TSK-005)")
                .allSatisfy(schedule -> assertThat(drainOf(schedule).held()).isTrue());
        assertThat(snapshot.consumers.held()).as("and so are the consumer loops").isTrue();
        assertThat(snapshot.timeout).as("production states the phase timeout").isEqualTo(Duration.ofSeconds(30));

        CompletableFuture<HttpResponse<String>> request = HTTP.sendAsync(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + snapshot.apiPort + PROBE_PATH)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(held.entered.await(30, TimeUnit.SECONDS)).as("a request accepted before the stop").isTrue();

        recorder.attach();
        CompletableFuture<Void> closing = CompletableFuture.runAsync(context::close);
        try {
            awaitTrue(() -> !snapshot.apiAccepts(), "the API port stops accepting");

            // Inside the HTTP drain: the request is still held.
            assertThat(request).as("the accepted request is still running").isNotDone();
            HttpResponse<String> readiness = snapshot.management("/actuator/health/readiness");
            assertThat(readiness.statusCode()).as("readiness, on the management port, refuses").isEqualTo(503);
            assertThat(readiness.body()).isEqualTo("{\"status\":\"OUT_OF_SERVICE\"}");
            assertThat(snapshot.management("/actuator/health/liveness").statusCode()).as("liveness still answers").isEqualTo(200);
            assertThat(snapshot.runningSchedules()).as("no schedule stopped before the HTTP drain ended").isEqualTo(SCHEDULES);
            assertThat(snapshot.consumers.isRunning()).isTrue();
            assertThat(snapshot.poolClosed()).isFalse();
        } finally {
            held.release.countDown();
        }
        HttpResponse<String> answered = request.get(30, TimeUnit.SECONDS);
        closing.get(90, TimeUnit.SECONDS);
        recorder.detach();

        assertThat(answered.statusCode()).as("the request accepted before the stop was answered, not cut off").isEqualTo(200);
        assertThat(answered.body()).isEqualTo("drained");

        List<Moment> order = List.copyOf(moments);
        Moment refusing = only(order, "readiness REFUSING_TRAFFIC");
        // The API server's alone: the management server closes at once (ManagementServerClose), so it logs neither.
        Moment commencing = only(order, "Commencing graceful shutdown");
        Moment complete = only(order, "Graceful shutdown complete");
        List<Moment> drained = all(order, moment -> moment.what.startsWith("Schedule ") && moment.what.endsWith(" drained"));
        Moment consumersStopping = only(order, "Inbox consumers stopping");
        Moment consumersStopped = only(order, "Inbox consumers stopped");
        Moment readerStopped = only(order, "The gauge refresher stopped");

        assertThat(order.indexOf(refusing)).as("readiness refuses before anything stops").isZero();
        assertThat(refusing.state.apiAccepts).as("readiness refused while the API port still accepted").isTrue();
        assertThat(refusing.state.schedulesRunning).isEqualTo(SCHEDULES);

        assertThat(commencing.state.refusing).as("the HTTP drain began with readiness already refusing").isTrue();
        assertThat(commencing.state.schedulesRunning).isEqualTo(SCHEDULES);

        Set<String> drainedNames = new TreeSet<>();
        drained.forEach(moment -> drainedNames.add(moment.what.substring("Schedule ".length(), moment.what.length() - " drained".length())));
        assertThat(drainedNames).as("every schedule drained, once").hasSize(SCHEDULES).isEqualTo(snapshot.scheduleNames());
        for (Moment moment : drained) {
            assertThat(order.indexOf(moment)).as("%s after the HTTP drain ended", moment.what).isGreaterThan(order.indexOf(complete));
            assertThat(order.indexOf(moment)).as("%s before the consumers stopped", moment.what)
                    .isLessThan(order.indexOf(consumersStopping));
            assertThat(moment.state.poolClosed).isFalse();
            assertThat(moment.state.managementAnswers).as("the probes answered while %s", moment.what).isTrue();
        }

        assertThat(consumersStopping.state.schedulesRunning).as("the consumers stop after every schedule").isZero();
        assertThat(consumersStopping.state.managementAnswers).isTrue();
        assertThat(consumersStopped.state.consumersRunning).isFalse();
        assertThat(consumersStopped.state.managementAnswers).as("the probes answered until the consumers had stopped").isTrue();
        assertThat(consumersStopped.state.poolClosed).isFalse();

        assertThat(order.indexOf(readerStopped)).isGreaterThan(order.indexOf(consumersStopped));
        assertThat(readerStopped.state.managementAnswers).as("the management server closed after the drains").isFalse();
        assertThat(readerStopped.state.apiAccepts).isFalse();
        assertThat(readerStopped.state.poolClosed).as("the pool is still open at the last lifecycle stop").isFalse();

        assertThat(snapshot.poolClosed()).as("the pool closed last, once every stop had returned").isTrue();
        assertThat(snapshot.components.values()).allSatisfy(component -> assertThat(component.isRunning()).isFalse());
    }

    @Test
    @DisplayName("a scrape still running on the management port does not hold the close: the management server closes at once")
    void aScrapeInFlightDoesNotHoldTheClose() throws Exception {
        HeldRequest scrape = new HeldRequest();
        List<Moment> moments = Collections.synchronizedList(new ArrayList<>());
        ConfigurableApplicationContext context = start(new HeldRequest(), moments, scrape);
        try {
            Snapshotter snapshot = new Snapshotter(context);
            Recorder recorder = new Recorder(snapshot, moments);
            HTTP.sendAsync(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + snapshot.managementPort + "/actuator/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(scrape.entered.await(30, TimeUnit.SECONDS)).as("a scrape running inside the management server").isTrue();

            recorder.attach();
            context.close();
            recorder.detach();

            // The management server closes between these two. Its child context's lifecycle processor is not the parent's:
            // spring.lifecycle.timeout-per-shutdown-phase does not reach it, so a graceful server would wait out Spring's own
            // default of ten seconds on the held scrape. Closing at once costs Tomcat's two-second unload.
            List<Moment> order = List.copyOf(moments);
            Duration managementClose = Duration.ofNanos(
                    only(order, "The gauge refresher stopped").nanos - only(order, "Inbox consumers stopped").nanos);
            assertThat(managementClose).as("the scrape, held for a minute, cost none of the child context's ten seconds")
                    .isLessThan(Duration.ofSeconds(8));
        } finally {
            scrape.release.countDown();
            context.close();
        }
    }

    @Test
    @DisplayName("every lifecycle component's stop returns within a bound shorter than production's phase timeout")
    void everyStopIsBoundedBelowThePhaseTimeout() throws Exception {
        ConfigurableApplicationContext context = start(new HeldRequest(), Collections.synchronizedList(new ArrayList<>()), new HeldRequest());
        try {
            Snapshotter snapshot = new Snapshotter(context);
            assertThat(snapshot.components).hasSize(COMPONENTS);
            Map<String, Duration> tooLong = new TreeMap<>();
            snapshot.components.forEach((name, component) -> {
                Duration bound = stopBound(name, component);
                if (bound.compareTo(snapshot.timeout) >= 0) {
                    tooLong.put(name, bound);
                }
            });
            assertThat(tooLong).as("a stop past spring.lifecycle.timeout-per-shutdown-phase is cut off, not finished").isEmpty();
            // The telemetry SDK's bounded last export (P15-TSK-023): the last of the platform's stops, before the beans - the
            // producer and the pools - are destroyed, so a collector that never answers cannot spend their grace.
            SmartLifecycle flush = snapshot.components.get("telemetryFlush");
            assertThat(flush).as("the telemetry flush is a lifecycle component of the production context").isNotNull();
            assertThat(stopBound("telemetryFlush", flush)).isEqualTo(com.finapp.app.telemetry.TelemetryFlush.BOUND);
            snapshot.components.forEach((name, component) -> {
                if (component != flush) {
                    assertThat(component.getPhase()).as("%s stops before the telemetry flush", name).isGreaterThan(flush.getPhase());
                }
            });
        } finally {
            context.close();
        }
    }

    // ------------------------------------------------------------------------------------------------------------------

    private static ConfigurableApplicationContext start(HeldRequest held, List<Moment> moments, HeldRequest scrape) {
        // Arguments, never builder "properties": those are DEFAULT properties, below production's file, which would then
        // bind its fixed 8081 and dial the database production's file names (both found so, in this class's first run).
        String[] arguments = {
            "--spring.config.location=classpath:/application.yaml",
            "--server.port=0",
            "--management.server.port=0",
            "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent",
            // The pool starts without its database and a borrower gives up in a quarter second, so a tick fails fast
            // instead of queueing on the pool's first start behind every other worker.
            "--spring.datasource.hikari.initialization-fail-timeout=-1",
            "--spring.datasource.hikari.connection-timeout=250",
            "--finapp.kafka.bootstrap-servers=127.0.0.1:1",
            // The providers on the discard port, so that every provider's schedule exists.
            "--finapp.kyc.provider.url=http://127.0.0.1:9",
            "--finapp.payments.provider.url=http://127.0.0.1:9",
            "--finapp.payments.instant.url=http://127.0.0.1:9",
            "--finapp.merchant.payout.provider.url=http://127.0.0.1:9",
            "--finapp.fx.reference.url=http://127.0.0.1:9",
            "--management.health.db.enabled=false",
            // The schema-version guard (P15-TSK-005) would answer OUT_OF_SERVICE here with no database, before the stop as well
            // as during it, and "readiness refuses" would then prove nothing about the stop: readiness stays readinessState alone.
            "--management.endpoint.health.group.readiness.include=readinessState",
            "--management.endpoint.health.validate-group-membership=false",
            "--logging.level.com.finapp.app.lifecycle=DEBUG",
            "--logging.level.com.finapp.platform.metrics.GaugeRefresher=DEBUG"
        };
        return new SpringApplicationBuilder(FinappApplication.class)
                .initializers(initializer -> {
                    GenericApplicationContext generic = (GenericApplicationContext) initializer;
                    generic.registerBean("drainProbe", DrainProbe.class, () -> new DrainProbe(held));
                    generic.registerBean("heldScrape", HealthIndicator.class, () -> () -> {
                        scrape.entered.countDown();
                        try {
                            if (!scrape.release.await(60, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("never released");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return Health.up().build();
                    });
                    generic.addApplicationListener(event -> {
                        // This context's own: the management server's child context publishes its own when it closes,
                        // and a child's events reach the parent's listeners.
                        if (event instanceof AvailabilityChangeEvent<?> change && change.getSource() == generic
                                && change.getState() == ReadinessState.REFUSING_TRAFFIC) {
                            moments.add(new Moment("readiness REFUSING_TRAFFIC", Snapshotter.current().state(), System.nanoTime()));
                        }
                    });
                })
                .run(arguments);
    }

    /**
     * A component's stop bound, read from its public {@code stopBound()}. By reflection, not through an interface: a
     * {@code com.finapp} supertype shared by every schedule would put accounting's and risk's schedules on a customer
     * surface's reach wherever one schedule is on it (`NoCustomerSurfaceReadsAccountingTest` follows an interface to every
     * implementation), so each component states its bound itself and this test holds that every one does.
     */
    private static Duration stopBound(String name, SmartLifecycle component) {
        try {
            return (Duration) component.getClass().getMethod("stopBound").invoke(component);
        } catch (ReflectiveOperationException absent) {
            throw new AssertionError(name + " states no stop bound: a public stopBound() is every component's", absent);
        }
    }

    /** A schedule's drain, through its public {@code drain()}; {@code null} for a component that is not a schedule. */
    private static ScheduleDrain drainOf(Object bean) {
        try {
            return (ScheduleDrain) bean.getClass().getMethod("drain").invoke(bean);
        } catch (NoSuchMethodException notASchedule) {
            return null;
        } catch (ReflectiveOperationException unreadable) {
            throw new AssertionError(bean.getClass().getName() + "'s drain could not be read", unreadable);
        }
    }

    private static Moment only(List<Moment> order, String what) {
        List<Moment> found = all(order, moment -> moment.what.startsWith(what));
        assertThat(found).as("exactly one '%s' among %s", what, order.stream().map(moment -> moment.what).toList()).hasSize(1);
        return found.get(0);
    }

    private static Moment first(List<Moment> order, String what) {
        List<Moment> found = all(order, moment -> moment.what.startsWith(what));
        assertThat(found).as("a '%s' among %s", what, order.stream().map(moment -> moment.what).toList()).isNotEmpty();
        return found.get(0);
    }

    private static List<Moment> all(List<Moment> order, Predicate<Moment> which) {
        return order.stream().filter(which).toList();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        for (int poll = 0; poll < 300 && !condition.getAsBoolean(); poll++) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    /** One step, and the state of everything else at the moment it happened. */
    private record Moment(String what, State state, long nanos) {}

    private record State(boolean refusing, int schedulesRunning, boolean consumersRunning, boolean readerRunning,
            boolean apiAccepts, boolean managementAnswers, boolean poolClosed) {}

    /** Reads the state of the context under test, from any thread. */
    private static final class Snapshotter {
        private static volatile Snapshotter current;

        private final ApplicationAvailability availability;
        private final Map<String, SmartLifecycle> components = new TreeMap<>();
        private final Map<String, SmartLifecycle> schedules = new TreeMap<>();
        private final InboxConsumers consumers;
        private final GaugeRefresher reader;
        private final HikariDataSource pool;
        private final int apiPort;
        private final int managementPort;
        private final Duration timeout;

        private Snapshotter(ConfigurableApplicationContext context) throws Exception {
            this.availability = context.getBean(ApplicationAvailability.class);
            context.getBeansOfType(SmartLifecycle.class).forEach((name, bean) -> {
                if (bean.getClass().getName().startsWith("com.finapp.")) {
                    components.put(name, bean);
                    if (drainOf(bean) != null) {
                        schedules.put(name, bean);
                    }
                }
            });
            this.consumers = context.getBean(InboxConsumers.class);
            this.reader = context.getBean(GaugeRefresher.class);
            // By name and through java.sql.Wrapper: a test naming javax.sql.DataSource is read as needing PostgreSQL
            // (TestTier), and this one needs none.
            this.pool = ((java.sql.Wrapper) context.getBean("dataSource")).unwrap(HikariDataSource.class);
            this.apiPort = Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
            this.managementPort = Integer.parseInt(context.getEnvironment().getRequiredProperty("local.management.port"));
            this.timeout = context.getEnvironment().getRequiredProperty("spring.lifecycle.timeout-per-shutdown-phase", Duration.class);
            current = this;
        }

        private static Snapshotter current() {
            return current;
        }

        private Set<String> scheduleNames() {
            Set<String> names = new TreeSet<>();
            schedules.values().forEach(schedule -> names.add(drainOf(schedule).name()));
            return names;
        }

        private int runningSchedules() {
            return (int) schedules.values().stream().filter(SmartLifecycle::isRunning).count();
        }

        private boolean poolClosed() {
            return pool.isClosed();
        }

        private boolean apiAccepts() {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", apiPort), 500);
                return true;
            } catch (ConnectException refused) {
                return false;
            } catch (IOException other) {
                return false;
            }
        }

        private HttpResponse<String> management(String path) throws IOException, InterruptedException {
            return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + path))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        private boolean managementAnswers() {
            try {
                return management("/actuator/health/liveness").statusCode() == 200;
            } catch (IOException | InterruptedException unreachable) {
                if (unreachable instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
        }

        private State state() {
            return new State(availability.getReadinessState() == ReadinessState.REFUSING_TRAFFIC, runningSchedules(),
                    consumers.isRunning(), reader.isRunning(), apiAccepts(), managementAnswers(), poolClosed());
        }
    }

    /** Catches the steps' own log lines, and reads the state on the thread that logs each. */
    private static final class Recorder extends AppenderBase<ILoggingEvent> {
        private static final Set<String> LOGGERS = Set.of(
                "org.springframework.boot.tomcat.GracefulShutdown",
                ScheduleDrain.class.getName(),
                InboxConsumers.class.getName(),
                GaugeRefresher.class.getName());

        private final Snapshotter snapshot;
        private final List<Moment> moments;
        private Logger root;

        private Recorder(Snapshotter snapshot, List<Moment> moments) {
            this.snapshot = snapshot;
            this.moments = moments;
            setName("shutdown-order-recorder");
        }

        private void attach() {
            root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            setContext(root.getLoggerContext());
            start();
            root.addAppender(this);
        }

        private void detach() {
            root.detachAppender(this);
            stop();
        }

        @Override
        protected void append(ILoggingEvent event) {
            if (LOGGERS.contains(event.getLoggerName())) {
                moments.add(new Moment(event.getFormattedMessage(), snapshot.state(), System.nanoTime()));
            }
        }
    }

    /** A request that holds until released: the HTTP drain's subject. Answers before any of the application's filters. */
    static final class DrainProbe extends FilterRegistrationBean<Filter> {
        DrainProbe(HeldRequest held) {
            super((request, response, chain) -> {
                held.entered.countDown();
                try {
                    if (!held.release.await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("never released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                HttpServletResponse http = (HttpServletResponse) response;
                http.setStatus(200);
                http.getOutputStream().write("drained".getBytes(StandardCharsets.UTF_8));
            });
            addUrlPatterns(PROBE_PATH);
            setOrder(Ordered.HIGHEST_PRECEDENCE);
        }
    }

    static final class HeldRequest {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
    }
}
