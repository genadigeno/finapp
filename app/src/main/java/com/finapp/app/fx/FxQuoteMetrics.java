package com.finapp.app.fx;

import com.finapp.fx.QuoteStore;
import com.finapp.fx.ReferencePair;
import com.finapp.fx.ReferenceSourceDeclaration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * The quote's meters (`P9-TSK-008`, PHASE_9_PLAN.md section 15): {@code finapp.fx.quote} - issued,
 * or refused for rate trouble or the cap - and {@code finapp.fx.quote.closed} by outcome, per pair;
 * {@code finapp.fx.quote.open}, live quotes per pair read from the database on its own clock behind
 * a floor (the {@code FxRateMetrics} shape). Counts only - never an amount, a rate or an owner.
 * Every meter is registered eagerly for the twenty directional pairs.
 */
@Slf4j
public final class FxQuoteMetrics {

    public static final String QUOTE = "finapp.fx.quote";
    public static final String CLOSED = "finapp.fx.quote.closed";
    public static final String OPEN = "finapp.fx.quote.open";
    public static final String TRADE = "finapp.fx.trade";
    public static final String RESIDUAL = "finapp.fx.residual";
    static final List<String> RESIDUAL_DIRECTIONS = List.of("positive", "negative", "zero");

    static final List<String> QUOTE_OUTCOMES = List.of(
            "issued", "refused_rate_unavailable", "refused_reference_stale", "refused_implausible",
            "refused_incoherent", "refused_cap");
    static final List<String> CLOSED_OUTCOMES = List.of("accepted", "expired", "cancelled", "abandoned");
    static final Duration MIN_REFRESH = Duration.ofSeconds(5);

    /** Opens a connection for one gauge reading. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final QuoteStore quotes;
    private final Connections connections;
    private final Clock clock;
    private final MeterRegistry registry;
    private final AtomicReference<Reading> cached = new AtomicReference<>(new Reading(Instant.EPOCH, Optional.empty()));

    public FxQuoteMetrics(QuoteStore quotes, Connections connections, Clock clock, MeterRegistry registry) {
        this.quotes = quotes;
        this.connections = connections;
        this.clock = clock;
        this.registry = registry;
        for (String pair : pairs()) {
            QUOTE_OUTCOMES.forEach(outcome -> quote(pair, outcome));
            CLOSED_OUTCOMES.forEach(outcome -> closed(pair, outcome));
            trade(pair);
            RESIDUAL_DIRECTIONS.forEach(direction -> residual(pair, direction));
            Gauge.builder(OPEN, this, self -> self.liveOf(pair).doubleValue())
                    .tag("pair", pair)
                    .description("Live FX quotes for this pair: ISSUED and not yet past expiry on the"
                            + " database clock. NaN when unreadable. Fleet-wide: max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
    }

    /** The twenty directional pairs of the reference's ten canonical ones, as {@code "EUR-USD"}. */
    static List<String> pairs() {
        List<String> pairs = new ArrayList<>();
        for (ReferencePair pair : ReferenceSourceDeclaration.PAIRS) {
            pairs.add(pair.base().code() + "-" + pair.quote().code());
            pairs.add(pair.quote().code() + "-" + pair.base().code());
        }
        return List.copyOf(pairs);
    }

    /** A quote outcome, after its transaction committed. */
    public void quoted(String pair, String outcome) {
        quote(pair, outcome).increment();
    }

    /**
     * A booked conversion, after its transaction committed (`P9-TSK-009`): one trade, and its
     * residual's DIRECTION - a frequency, never the amount (ADR-0072); under half roundings the
     * residual random-walks around zero, so a drift of one direction is a defect signal.
     */
    public void traded(String pair, long residualMinor) {
        trade(pair).increment();
        residual(pair, residualMinor > 0 ? "positive" : residualMinor < 0 ? "negative" : "zero").increment();
    }

    private Counter trade(String pair) {
        return Counter.builder(TRADE)
                .tag("pair", pair)
                .description("Booked FX conversions per pair. A count, never an amount")
                .register(registry);
    }

    private Counter residual(String pair, String direction) {
        return Counter.builder(RESIDUAL)
                .tag("pair", pair)
                .tag("direction", direction)
                .description("Booked conversions by their rounding residual's direction - the platform kept"
                        + " the fraction (positive), bore it (negative), or none. A frequency, never an amount")
                .register(registry);
    }

    /** A quote left ISSUED, after its transaction committed. */
    public void closed(String pair, String outcome, int count) {
        closed(pair, outcome).increment(count);
    }

    private Counter quote(String pair, String outcome) {
        return Counter.builder(QUOTE)
                .tag("pair", pair)
                .tag("outcome", outcome)
                .description("FX quote requests by outcome: issued, or refused for rate trouble (no"
                        + " provider answered usably, a stale reference, an implausible or"
                        + " incoherent provider rate) or the open-quote cap. A count, never a rate")
                .register(registry);
    }

    private Counter closed(String pair, String outcome) {
        return Counter.builder(CLOSED)
                .tag("pair", pair)
                .tag("outcome", outcome)
                .description("FX quotes leaving ISSUED by outcome - accepted, expired, cancelled,"
                        + " abandoned. Quote-to-trade and expiry rates by ratio")
                .register(registry);
    }

    private Number liveOf(String pair) {
        Reading reading = reading();
        return reading.live().<Number>map(live -> live.getOrDefault(pair, 0)).orElse(Double.NaN);
    }

    private Reading reading() {
        Reading current = cached.get();
        if (!current.takenAt().plus(MIN_REFRESH).isBefore(clock.instant())) {
            return current;
        }
        Reading fresh;
        try (Connection connection = connections.open()) {
            fresh = new Reading(clock.instant(), Optional.of(quotes.liveByPair(connection)));
        } catch (Exception unreadable) {
            log.warn("Could not count live FX quotes; the open gauges report absent rather than zero: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new Reading(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }

    private record Reading(Instant takenAt, Optional<Map<String, Integer>> live) {}
}
