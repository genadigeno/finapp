package com.finapp.app.telemetry;

import com.finapp.platform.metrics.GaugeRefresher;
import com.finapp.platform.metrics.MetricNames;
import com.finapp.platform.metrics.RefreshedReading;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.tracer.common.SpanContext;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

/**
 * The application's Prometheus registry, with <strong>every {@code finapp.} function-backed meter read off the scrape
 * thread</strong> (`P15-TSK-024`; ADR-0124 decision 7). Micrometer calls a gauge's function while it renders a scrape, so a
 * gauge that reads the database made the scrape wait on the database: with the pool exhausted or the database gone one
 * scrape took 96.7 s against a 5 s timeout, stored nothing, and fired nothing. Here the function of every meter whose
 * name carries {@link MetricNames#PREFIX} - a gauge (and so a time gauge), a function counter, a custom meter - is
 * handed to the {@link GaugeRefresher} as a {@link RefreshedReading}; the scrape renders the reading's last value and
 * only asks for another.
 *
 * <ul>
 *   <li><strong>At the registry, not per class</strong>: a gauge added tomorrow is off the scrape with no edit, and the
 *       meter's name, tags, base unit and help are untouched, since only its function is wrapped. {@code
 *       NoGaugeReadsOnTheScrapeTest} holds it over every meter of the real context.
 *   <li><strong>The library meters stay on the scrape</strong> (JVM, process, pool, HTTP): they read memory, which that
 *       test proves for each of them - a library binder that read the database there would turn it red.
 *   <li><strong>A {@code finapp.} function timer is refused at registration</strong>: its count is a {@code long}, which
 *       cannot be NaN, so before its first read it would publish a zero nobody measured. None exists.
 *   <li>A weakly referenced gauge stays weak: the reading holds its object through a {@link WeakReference}, as Micrometer's
 *       gauge does, and reads NaN once it is collected.
 * </ul>
 */
public final class OffScrapeMeterRegistry extends PrometheusMeterRegistry {

    private final GaugeRefresher refresher;
    private final Map<Meter.Id, List<RefreshedReading>> served = new ConcurrentHashMap<>();

    public OffScrapeMeterRegistry(PrometheusConfig config, PrometheusRegistry registry, Clock clock, SpanContext spanContext, GaugeRefresher refresher) {
        super(config, registry, clock, spanContext);
        this.refresher = Objects.requireNonNull(refresher, "refresher");
        config().onMeterRemoved(this::untrack);
    }

    /** Whether a meter of this name is served off the scrape: every one the platform names. */
    static boolean servedOffTheScrape(Meter.Id id) {
        return id.getName().startsWith(MetricNames.PREFIX);
    }

    @Override
    protected <T> Gauge newGauge(Meter.Id id, T obj, ToDoubleFunction<T> valueFunction) {
        if (!servedOffTheScrape(id)) {
            return super.newGauge(id, obj, valueFunction);
        }
        RefreshedReading reading = track(id, weakly(obj, valueFunction));
        return super.newGauge(id, obj, ignored -> reading.current());
    }

    @Override
    protected <T> FunctionCounter newFunctionCounter(Meter.Id id, T obj, ToDoubleFunction<T> countFunction) {
        if (!servedOffTheScrape(id)) {
            return super.newFunctionCounter(id, obj, countFunction);
        }
        RefreshedReading reading = track(id, weakly(obj, countFunction));
        return super.newFunctionCounter(id, obj, ignored -> reading.current());
    }

    @Override
    protected <T> FunctionTimer newFunctionTimer(Meter.Id id, T obj, ToLongFunction<T> countFunction, ToDoubleFunction<T> totalTimeFunction,
            TimeUnit totalTimeFunctionUnit) {
        if (servedOffTheScrape(id)) {
            throw new IllegalArgumentException(id.getName() + ": a finapp function timer cannot be read off the scrape - its count cannot be"
                    + " NaN before its first read; register a gauge, or a timer the code records into");
        }
        return super.newFunctionTimer(id, obj, countFunction, totalTimeFunction, totalTimeFunctionUnit);
    }

    @Override
    protected Meter newMeter(Meter.Id id, Meter.Type type, Iterable<Measurement> measurements) {
        if (!servedOffTheScrape(id)) {
            return super.newMeter(id, type, measurements);
        }
        List<Measurement> offTheScrape = new ArrayList<>();
        for (Measurement measurement : measurements) {
            RefreshedReading reading = track(id, measurement::getValue);
            offTheScrape.add(new Measurement(reading::current, measurement.getStatistic()));
        }
        return super.newMeter(id, type, offTheScrape);
    }

    /** The ids whose functions this registry serves off the scrape: {@code NoGaugeReadsOnTheScrapeTest}'s census. */
    public Set<Meter.Id> servedIds() {
        return Set.copyOf(served.keySet());
    }

    /**
     * Every reading read now on the refresher's thread, and waited for: the suites' and an operator's look at what the
     * database says now ({@link GaugeRefresher#refreshAll}). Never a scrape's.
     */
    public boolean refreshAll(Duration timeout) throws InterruptedException {
        return refresher.refreshAll(timeout);
    }

    /** {@link #refreshAll}, for the meters whose name starts with {@code prefix}: one module's, say. */
    public boolean refresh(String prefix, Duration timeout) throws InterruptedException {
        return refresher.refresh(what -> what.startsWith(prefix), timeout);
    }

    private RefreshedReading track(Meter.Id id, DoubleSupplier read) {
        RefreshedReading reading = refresher.track(id.getName(), read);
        served.computeIfAbsent(id, ignored -> new CopyOnWriteArrayList<>()).add(reading);
        return reading;
    }

    private void untrack(Meter meter) {
        List<RefreshedReading> readings = served.remove(meter.getId());
        if (readings != null) {
            readings.forEach(refresher::untrack);
        }
    }

    private static <T> DoubleSupplier weakly(T obj, ToDoubleFunction<T> function) {
        WeakReference<T> reference = new WeakReference<>(obj);
        return () -> {
            T held = reference.get();
            return held == null ? Double.NaN : function.applyAsDouble(held);
        };
    }
}
