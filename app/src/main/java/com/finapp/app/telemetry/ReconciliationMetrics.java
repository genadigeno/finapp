package com.finapp.app.telemetry;

import com.finapp.app.reconciliation.PositionProof;
import com.finapp.ledger.AccountPurpose;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * The reconciliation verdict gauges (`P8-TSK-007`, ADR-0067 §9, `PHASE_8_PLAN.md` §15):
 * {@code finapp.reconciliation.position.proof} and {@code .line.unattributed} per
 * {@code purpose} — <strong>both must read 0, both alerted</strong> — and
 * {@code .expectation.open} per {@code source}.
 *
 * <h2>Counts, never amounts</h2>
 *
 * <p>The proof series counts <em>currencies failing the identity</em>, and the completeness
 * series counts <em>lines nobody explains</em> (ADR-0072: a difference's size is the
 * report's to show, on the record — a metric carrying it would put amounts in every
 * scrape). {@code NaN} when the sweep is unreadable, never a comforting zero
 * (`P1-TSK-029`); eager per purpose and per declared source; fleet-wide per instance over
 * the shared database behind the floor, aggregated with {@code max()}, never {@code sum()}
 * — the {@code SettlementFileMetrics} shape, verbatim, and like it this class appears in no
 * {@code DISTRIBUTED_EXECUTION.md} §3 register: it decides nothing.
 *
 * <p>The sweep runs in one {@code REPEATABLE READ} transaction, so each reading's two sides
 * are one snapshot ({@code INV-ACC-01}'s discipline beside the trial balance).
 */
@Slf4j
public final class ReconciliationMetrics {

    /** {@code finapp.reconciliation.position.proof} — currencies failing, per purpose. */
    public static final String PROOF = "finapp.reconciliation.position.proof";

    /** {@code finapp.reconciliation.line.unattributed} — unexplained lines, per purpose. */
    public static final String UNATTRIBUTED = "finapp.reconciliation.line.unattributed";

    /** {@code finapp.reconciliation.expectation.open} — open expectations, per source. */
    public static final String OPEN = "finapp.reconciliation.expectation.open";

    /** The floor: the sweep folds the open register, so it is dearer than a GROUP BY. */
    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** The {@code LedgerMetrics.Connections} seam, for the same unit-testability reason. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final PositionProof proof;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(Cached.empty());

    public ReconciliationMetrics(
            PositionProof proof,
            SettlementSources sources,
            Connections connections,
            Clock clock,
            MeterRegistry registry) {
        this.proof = proof;
        this.connections = connections;
        this.clock = clock;

        for (AccountPurpose purpose : PositionProof.PROVEN) {
            Gauge.builder(PROOF, this, self -> self.proofOf(purpose))
                    .tag("purpose", purpose.name())
                    .description(
                            "Currencies of this clearing position failing the identity"
                                    + " DR-CR = signed open expectation remainders"
                                    + " (INV-REC-06). MUST read 0 and is alerted; a count,"
                                    + " never an amount - the difference's size is the"
                                    + " positions report's, on the record. NaN when"
                                    + " unreadable, never zero. Fleet-wide from every"
                                    + " instance: aggregate with max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
        for (AccountPurpose purpose : AccountPurpose.reconciledPositions()) {
            Gauge.builder(UNATTRIBUTED, this, self -> self.unattributedOf(purpose))
                    .tag("purpose", purpose.name())
                    .description(
                            "Journal lines on this reconciled position that no expectation"
                                    + " explains (ADR-0067 section 9: every-writer"
                                    + " detection - a raw-SQL poster or a missed opener"
                                    + " lands here). MUST read 0 on the clearing purposes"
                                    + " and is alerted; SUSPENSE_UNMATCHED truthfully"
                                    + " counts Phase 7's parking lines until P8-TSK-020"
                                    + " adopts them. NaN when unreadable, never zero."
                                    + " Fleet-wide: aggregate with max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
        for (SettlementSourceDescriptor source : sources.declared()) {
            String code = source.code();
            Gauge.builder(OPEN, this, self -> self.openOf(code))
                    .tag("source", code)
                    .description(
                            "Open settlement expectations tracked against this source -"
                                    + " the population ageing (P8-TSK-013) will judge."
                                    + " A count, never an amount. NaN when unreadable."
                                    + " Fleet-wide: aggregate with max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
    }

    private double proofOf(AccountPurpose purpose) {
        return reading()
                .report()
                .map(report -> (double) report.currenciesFailing(purpose))
                .orElse(Double.NaN);
    }

    private double unattributedOf(AccountPurpose purpose) {
        return reading()
                .report()
                .map(
                        report ->
                                (double)
                                        report.unattributedByPurpose()
                                                .getOrDefault(purpose, 0L))
                .orElse(Double.NaN);
    }

    private double openOf(String sourceCode) {
        return reading()
                .report()
                .map(
                        report ->
                                (double)
                                        report.openBySourceCode()
                                                .getOrDefault(sourceCode, 0L))
                .orElse(Double.NaN);
    }

    private Cached reading() {
        Cached current = cached.get();
        if (!current.isStaleAt(clock.instant())) {
            return current;
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                fresh = new Cached(clock.instant(), Optional.of(proof.sweep(connection)));
                connection.commit();
            } catch (Exception mid) {
                connection.rollback();
                throw mid;
            }
        } catch (Exception unreadable) {
            // Never the exception's message at INFO and never an identifier (INV-AUD-02).
            log.warn(
                    "Could not sweep the position proof; the gauges report absent rather"
                            + " than zero: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }

    /** One sweep and when it was taken — per instance, deciding nothing. */
    private record Cached(Instant takenAt, Optional<PositionProof.Report> report) {

        static Cached empty() {
            return new Cached(Instant.EPOCH, Optional.empty());
        }

        boolean isStaleAt(Instant now) {
            return takenAt.plus(MIN_REFRESH).isBefore(now);
        }
    }
}
