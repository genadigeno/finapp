package com.finapp.app.fx;

import com.finapp.app.lifecycle.ScheduleDrain;
import com.finapp.app.lifecycle.ShutdownOrder;
import com.finapp.fx.QuoteLifecycle;
import com.finapp.fx.QuoteStore;
import com.finapp.fx.TransactionRunner;
import com.finapp.platform.security.SecurityContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Quote expiry as an event (`P9-TSK-008`; ADR-0075 section 5). Every instance runs it with no
 * lease: each page is one transaction whose conditional {@code UPDATE} - judged on
 * {@code statement_timestamp()}, rows another sweeper holds skipped - moves each lapsed quote once
 * and writes its one {@code fx.FxQuoteExpired}, so ten sweepers write ten pages of distinct rows.
 * The instance's own clock decides nothing about expiry. Off in test contexts
 * ({@code finapp.fx.quote.expiry.sweeper.enabled}); the suites drive {@link #sweepOnce()}.
 */
@Slf4j
public final class FxQuoteExpirySchedule implements SmartLifecycle {

    /** The pages one tick may take before yielding to the next tick. */
    static final int MAX_PAGES = 20;

    private final QuoteLifecycle lifecycle;
    private final TransactionRunner transactions;
    private final FxQuoteMetrics metrics;
    private final Clock clock;
    private final Duration pollInterval;
    private final int pageSize;
    private final ScheduleDrain drain = new ScheduleDrain("fx-quote-expiry");

    public FxQuoteExpirySchedule(
            QuoteLifecycle lifecycle,
            TransactionRunner transactions,
            FxQuoteMetrics metrics,
            Clock clock,
            Duration pollInterval,
            int pageSize) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive: " + pollInterval);
        }
        if (pageSize < 1 || pageSize > 1000) {
            throw new IllegalArgumentException("pageSize must be 1..1000: " + pageSize);
        }
        this.pageSize = pageSize;
    }

    /**
     * Expires every lapsed quote this tick can reach, page by page, each page its own transaction.
     *
     * @return how many quotes this call expired
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public int sweepOnce() {
        int total = 0;
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            for (int page = 0; page < MAX_PAGES && !drain.stopping(); page++) {
                Instant now = Instant.now(clock);
                List<QuoteStore.ExpiredRow> expired =
                        transactions.inTransaction(unitOfWork -> lifecycle.expirePage(unitOfWork, pageSize, now, SecurityContext.require()));
                Map<String, Integer> perPair = new HashMap<>();
                for (QuoteStore.ExpiredRow row : expired) {
                    perPair.merge(row.source().code() + "-" + row.destination().code(), 1, Integer::sum);
                }
                perPair.forEach((pair, count) -> metrics.closed(pair, "expired", count));
                total += expired.size();
                if (expired.size() < pageSize) {
                    break;
                }
            }
        }
        return total;
    }

    @Override
    public void start() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, drain.name());
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::sweepQuietly, pollInterval.toMillis(), pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        drain.started(executor);
    }

    private void sweepQuietly() {
        try {
            sweepOnce();
        } catch (RuntimeException failure) {
            // The next tick retries; the class name only - a JDBC message can carry values.
            log.warn("FX quote expiry sweep failed: {}", failure.getClass().getSimpleName());
        }
    }

    /** This schedule's drain: its name is what a stopping instance's log calls it. */
    public ScheduleDrain drain() {
        return drain;
    }

    /** No new tick starts, and the tick in flight finishes within its bound ({@link ScheduleDrain}). */
    @Override
    public void stop() {
        drain.stop();
    }

    /** Spring's form: no new tick starts, and it calls back once the tick in flight has drained, so a phase drains in parallel. */
    @Override
    public void stop(Runnable callback) {
        drain.stop(callback);
    }

    @Override
    public boolean isRunning() {
        return drain.isRunning();
    }

    /** After the HTTP drain, before the consumers ({@link ShutdownOrder}). */
    @Override
    public int getPhase() {
        return ShutdownOrder.SCHEDULES;
    }

    /** The longest {@link #stop()} takes, below {@code spring.lifecycle.timeout-per-shutdown-phase} ({@code ShutdownOrderTest}). */
    public Duration stopBound() {
        return drain.stopBound();
    }
}
