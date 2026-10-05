package com.finapp.app.fx;

import com.finapp.fx.FxBooksProof;
import com.finapp.fx.FxPlanVerification;
import com.finapp.ledger.AccountPurpose;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * The FX proofs' verdicts (`P9-TSK-013`; PHASE_9_PLAN.md section 12.9.4, the
 * {@code ReconciliationMetrics} shape): {@code finapp.fx.proof{purpose}} - the number of currencies
 * failing each FX book's identity, which must be 0 - and {@code finapp.fx.plan.verdict} - 1 when
 * every booked trade replays exactly, 0 when one does not (its CRITICAL log line names the trade).
 * Both read from ONE {@code REPEATABLE READ} read-only snapshot behind a refresh floor; NaN when
 * unreadable. Verdicts only - never an amount.
 */
@Slf4j
public final class FxProofMetrics {

    public static final String PROOF = "finapp.fx.proof";
    public static final String PLAN_VERDICT = "finapp.fx.plan.verdict";
    static final Duration MIN_REFRESH = Duration.ofSeconds(60);

    /** Opens a connection for one reading. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final FxBooksProof books;
    private final FxPlanVerification plans;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Reading> cached = new AtomicReference<>(new Reading(Instant.EPOCH, Optional.empty()));

    public FxProofMetrics(
            FxBooksProof books, FxPlanVerification plans, Connections connections, Clock clock, MeterRegistry registry) {
        this.books = Objects.requireNonNull(books, "books must not be null");
        this.plans = Objects.requireNonNull(plans, "plans must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(registry, "registry must not be null");
        for (AccountPurpose book : FxBooksProof.BOOKS) {
            Gauge.builder(PROOF, this, self -> self.reading().result()
                            .map(result -> (double) result.books().failing(book)).orElse(Double.NaN))
                    .tag("purpose", book.name())
                    .description("Currencies failing this FX book's identity against fx's own rows (INV-FX-06):"
                            + " must be 0. NaN when unreadable. Fleet-wide: max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
        Gauge.builder(PLAN_VERDICT, this, self -> self.reading().result()
                        .map(result -> result.plans().clean() ? 1d : 0d).orElse(Double.NaN))
                .description("1 when every booked FX trade replays exactly from its stored inputs (INV-FX-05),"
                        + " 0 when one does not - alerting. NaN when unreadable")
                .strongReference(true)
                .register(registry);
    }

    /** One reading now, bypassing the floor - the suites' and an operator's view. */
    public Result readNow() {
        Reading fresh = read();
        cached.set(fresh);
        return fresh.result().orElseThrow(() -> new IllegalStateException("the FX proofs could not be read"));
    }

    private Reading reading() {
        Reading current = cached.get();
        if (!current.takenAt().plus(MIN_REFRESH).isBefore(clock.instant())) {
            return current;
        }
        Reading fresh = read();
        cached.set(fresh);
        return fresh;
    }

    private Reading read() {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            try {
                Result result = new Result(books.prove(connection), plans.verify(connection));
                if (!result.books().clean()) {
                    log.error("CRITICAL: the FX books proof fails (INV-FX-06): {}", result.books().lines().stream()
                            .filter(line -> !line.holds())
                            .map(line -> line.book() + "/" + line.currency().code())
                            .toList());
                }
                return new Reading(clock.instant(), Optional.of(result));
            } finally {
                connection.rollback();
            }
        } catch (Exception unreadable) {
            log.warn("Could not read the FX proofs; their gauges report absent rather than clean: {}",
                    unreadable.getClass().getSimpleName());
            return new Reading(clock.instant(), Optional.empty());
        }
    }

    /** One snapshot's two verdicts. */
    public record Result(FxBooksProof.Report books, FxPlanVerification.Report plans) {}

    private record Reading(Instant takenAt, Optional<Result> result) {}
}
