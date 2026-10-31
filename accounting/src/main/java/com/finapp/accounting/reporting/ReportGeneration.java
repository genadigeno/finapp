package com.finapp.accounting.reporting;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.RunBasis;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * One report run's content, generated inside the caller's read-only snapshot (`P14-TSK-008`; ADR-0115 decision 2;
 * {@code PHASE_14_PLAN.md} section 8.1 T-report's generate leg; {@code INV-ACC-04}, {@code INV-ACC-14}).
 *
 * <p><strong>The lock it reads under.</strong> A FINAL run is reproducible only while its period stays locked - the lock
 * it was claimed under. So, first, the period is re-read <em>in the snapshot</em>: still not {@code OPEN}, and its
 * {@code locked_at} the run's {@link ReportInputs#periodLockedAt}. Anything else - an unlock, or an unlock and a new lock
 * - is {@link PeriodMoved}, and the run fails rather than issue a FINAL answer the books no longer give. (The completion
 * re-checks the same under the period row {@code FOR SHARE}, and {@code accounting V004} for every writer, so a move
 * between this snapshot and the commit is caught too.)
 *
 * <p><strong>The content.</strong> The definition's body inside a header naming every pinned input - the definition and
 * version, the period and its revision, the basis, the mapping version, the rate set, the parameters, and for a
 * PROVISIONAL run its cut - so the hash binds the answer to its question. Canonical ({@link CanonicalJson}), at most
 * {@link #MAX_CONTENT_BYTES}, its SHA-256 beside it.
 *
 * <p>Report code: it reads ({@code executeQuery}) and writes nothing; it reaches no store, no audit and no outbox
 * ({@code ReportingNeverWritesTest}).
 */
public final class ReportGeneration {

    /** The content's largest size in UTF-8 - {@code accounting V004}'s {@code report_run_content_bounded}. */
    public static final int MAX_CONTENT_BYTES = 8 * 1024 * 1024;

    /** The period, read in the snapshot - the lock instance's re-read. */
    static final String PERIOD_SQL = "SELECT status, locked_at FROM accounting.accounting_period WHERE id = ?";

    /** A period's state as the snapshot sees it. */
    public record PeriodState(PeriodStatus status, Optional<Instant> lockedAt) {}

    /** Reads a period's state on the snapshot. */
    @FunctionalInterface
    public interface PeriodStates {
        Optional<PeriodState> read(Connection snapshot, AccountingPeriodId id);
    }

    /** The period's state read by {@link #PERIOD_SQL} on the snapshot itself - production's reading. */
    public static final PeriodStates FROM_THE_SNAPSHOT = ReportGeneration::readPeriod;

    /** A FINAL run's period is no longer locked under the lock the run was claimed under. */
    public static final class PeriodMoved extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PeriodMoved(String detail) {
            super(detail);
        }
    }

    /** The content is not canonical or exceeds its bound. */
    public static final class ContentInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ContentInvalid(String detail) {
            super(detail);
        }
    }

    /**
     * The generation outlived its time budget - the budget spent before a statement, or a statement cancelled by the
     * snapshot's {@code statement_timeout} ({@code 57014}); the run fails {@code GENERATION_TIMED_OUT}, inside its lease.
     */
    public static final class GenerationTimedOut extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        GenerationTimedOut() {
            super("the report generation outlived its time budget (SQLSTATE 57014 or the budget spent)");
        }
    }

    /** A run's content and its SHA-256. */
    public record Generated(String content, String sha256) {

        public Generated {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    private final PeriodStates periods;
    private final Duration budget;
    private final LongSupplier ticker;

    /**
     * @param periods how the run's period is read in the snapshot
     * @param budget the whole generation's time - the configuration holds it below the generator's lease
     *     ({@code ReportRuns.Leases})
     * @param ticker a monotonic nanosecond clock - injected by the composition root, moved by a suite; never read here
     *     from the environment (`NoAmbientTimeRulesTest`: the two-argument form that defaulted it to
     *     {@code System::nanoTime} was removed at `P14-TSK-009`'s gate)
     */
    public ReportGeneration(PeriodStates periods, Duration budget, LongSupplier ticker) {
        this.periods = Objects.requireNonNull(periods, "periods");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        if (budget.toMillis() < 1) {
            throw new IllegalArgumentException("a report generation's budget is at least one millisecond");
        }
    }

    /**
     * {@code definition}'s content for {@code inputs}, read through {@code snapshot} - which neither this class nor the
     * definition touches directly: every read goes through its {@link ReadOnlySnapshot}, prepared reads only, within the
     * budget.
     *
     * @throws PeriodMoved when a FINAL run's period is not locked under the run's lock in the snapshot
     * @throws GenerationTimedOut when the budget was spent or a statement cancelled - whatever the definition did with it
     * @throws ContentInvalid when the content holds a character the form refuses, or is larger than its bound
     * @throws RuntimeException whatever the definition throws - a read it could not make, or a write refused
     */
    public Generated generate(Connection snapshot, ReportDefinition definition, ReportInputs inputs) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(inputs, "inputs");
        ReadOnlySnapshot view = new ReadOnlySnapshot(snapshot, budget, ticker);
        CanonicalJson.Obj body;
        try {
            if (inputs.basis() == RunBasis.FINAL) {
                PeriodState state = periods.read(view.connection(), inputs.periodId())
                        .orElseThrow(() -> new PeriodMoved("the run's period is not in the snapshot"));
                if (state.status() == PeriodStatus.OPEN || !state.lockedAt().equals(inputs.periodLockedAt())) {
                    throw new PeriodMoved("the run's period is no longer locked under the lock it was claimed under");
                }
            }
            body = definition.generate(view.connection(), inputs);
        } catch (RuntimeException failed) {
            if (view.timedOut()) {
                throw new GenerationTimedOut();
            }
            throw failed;
        }
        // A definition that caught the timeout and answered anyway answered from a partial read: never content.
        if (view.timedOut()) {
            throw new GenerationTimedOut();
        }
        Objects.requireNonNull(body, "a definition answers a body");
        String content;
        try {
            content = CanonicalJson.text(header(definition, inputs).put("body", body));
        } catch (IllegalArgumentException refused) {
            throw new ContentInvalid("the content is not canonical: " + refused.getMessage());
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new ContentInvalid("the content exceeds " + MAX_CONTENT_BYTES + " bytes");
        }
        return new Generated(content, CanonicalJson.sha256(content));
    }

    /** Every pinned input, by name - the run's question beside its answer. */
    static CanonicalJson.Obj header(ReportDefinition definition, ReportInputs inputs) {
        CanonicalJson.Obj header = CanonicalJson.object()
                .put("definition", definition.code())
                .put("definitionVersion", definition.version())
                .put("period", inputs.period().toString())
                .put("periodRevision", inputs.periodRevision())
                .put("basis", inputs.basis().name())
                .put("parameters", inputs.parameters().asValue());
        if (inputs.mappingVersion().isPresent()) {
            header.put("mappingVersion", inputs.mappingVersion().get());
        } else {
            header.putNull("mappingVersion");
        }
        if (inputs.parameters().valuationRateSet().isPresent()) {
            header.put("valuationRateSet", inputs.parameters().valuationRateSet().get().toString());
        } else {
            header.putNull("valuationRateSet");
        }
        // A FINAL run's range is immutable after the cutoff: its content never depends on when it was cut.
        if (inputs.basis() == RunBasis.PROVISIONAL) {
            header.put("cut", inputs.cutAt().toString());
        } else {
            header.putNull("cut");
        }
        return header;
    }

    private static Optional<PeriodState> readPeriod(Connection snapshot, AccountingPeriodId id) {
        try (PreparedStatement read = snapshot.prepareStatement(PERIOD_SQL)) {
            read.setObject(1, id.value());
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                Timestamp lockedAt = row.getTimestamp("locked_at");
                return Optional.of(new PeriodState(PeriodStatus.valueOf(row.getString("status")),
                        lockedAt == null ? Optional.empty() : Optional.of(lockedAt.toInstant())));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("the run's period could not be read in the snapshot: "
                    + failure.getSQLState());
        }
    }
}
