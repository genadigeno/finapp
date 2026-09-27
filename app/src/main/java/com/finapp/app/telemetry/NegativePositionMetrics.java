package com.finapp.app.telemetry;

import com.finapp.ledger.AccountPurpose;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code finapp.ledger.negative.positions} (`P7-TSK-013`, ADR-0061 §5, `PHASE_7_PLAN.md` §15):
 * how many counterparties stand below zero — merchant payables (merchant debt: a chargeback
 * after a payout, or a retained refund fee) and customer wallets (a receivable from a customer
 * who spent a top-up and charged it back). Both are legal and neither is ever absorbed; this is
 * the series that keeps them visible until they are recovered.
 *
 * <p>The {@code PaymentMetrics} stance verbatim: eager (every series from the first scrape),
 * NaN when unreadable and never a false zero, a refresh floor as the rate limit, a count and
 * never an amount ({@code INV-AUD-02}), and fleet-wide — every instance reads the same database
 * and publishes the same answer, so aggregate with {@code max()}, never {@code sum()}. Per
 * instance and non-authoritative: it decides nothing, so it needs no
 * {@code DISTRIBUTED_EXECUTION.md} §3 row.
 */
@Slf4j
final class NegativePositionMetrics {

    /** {@code finapp.ledger.negative.positions} — counterparties below zero, by purpose. */
    static final String NEGATIVE_POSITIONS = "finapp.ledger.negative.positions";

    /** The counterparty purposes a chargeback can drive below zero — the bounded tag set. */
    static final List<AccountPurpose> COUNTERPARTIES =
            List.of(AccountPurpose.MERCHANT_PAYABLE, AccountPurpose.CUSTOMER_WALLET);

    static final Duration MIN_REFRESH = Duration.ofSeconds(5);

    /** The {@code PaymentMetrics.Connections} seam, for the same unit-testability reason. */
    @FunctionalInterface
    interface Connections {
        Connection open() throws SQLException;
    }

    /** The ledger read seam ({@code NegativePositions.countBelowZero}). */
    @FunctionalInterface
    interface Counts {
        Map<AccountPurpose, Long> read(Connection connection, Set<AccountPurpose> purposes);
    }

    private final Counts counts;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(Cached.empty());

    NegativePositionMetrics(
            Counts counts, Connections connections, Clock clock, MeterRegistry registry) {
        this.counts = counts;
        this.connections = connections;
        this.clock = clock;
        for (AccountPurpose purpose : COUNTERPARTIES) {
            Gauge.builder(NEGATIVE_POSITIONS, this, self -> self.reading().valueOrNaN(purpose))
                    .tag("purpose", purpose.name())
                    .description(
                            "Counterparty accounts below zero (ADR-0061 section 5): merchant"
                                    + " debt on MERCHANT_PAYABLE, a receivable from the customer"
                                    + " on CUSTOMER_WALLET - legal, never absorbed, visible"
                                    + " until recovered. A count, never an amount. NaN when"
                                    + " unreadable, never zero. Fleet-wide: aggregate with"
                                    + " max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
    }

    private Cached reading() {
        Cached current = cached.get();
        if (!current.isStaleAt(clock.instant())) {
            return current;
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            Map<AccountPurpose, Long> read =
                    counts.read(connection, Set.copyOf(COUNTERPARTIES));
            fresh = new Cached(clock.instant(), new EnumMap<>(read), false);
        } catch (SQLException | RuntimeException unreadable) {
            log.warn(
                    "Could not count the counterparties below zero; the gauge reports absent"
                            + " rather than a false zero: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Map.of(), true);
        }
        cached.set(fresh);
        return fresh;
    }

    /** One reading and when it was taken — per instance, non-authoritative. */
    private record Cached(Instant takenAt, Map<AccountPurpose, Long> counts, boolean unknown) {

        static Cached empty() {
            return new Cached(Instant.MIN, Map.of(), true);
        }

        boolean isStaleAt(Instant now) {
            return takenAt.isBefore(now.minus(MIN_REFRESH));
        }

        double valueOrNaN(AccountPurpose purpose) {
            if (unknown || !counts.containsKey(purpose)) {
                return Double.NaN;
            }
            return (double) counts.get(purpose);
        }
    }
}
