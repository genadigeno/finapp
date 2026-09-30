package com.finapp.reconciliation;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Time's observers (`P8-TSK-013`): expectation AGEING — an expectation still open past
 * {@code expected_by + SETTLEMENT_DATE_DAYS} on the DATABASE clock gets its one-way
 * {@code overdue_since}, its unparked {@code MISSING_EXTERNAL} break and its event —
 * SEVERITY ESCALATION over the ageing bands, LOST-BLOCK detection (a run whose recorded
 * failures reached the bound but whose blocking transaction died), and `P8-TSK-010`'s
 * key-collision leg, scheduled at last. Leaderless: every write is a conditional the
 * losers of any race simply lose — the NULL→value {@code overdue_since}, the one-open
 * uniques, the expected-value severity step, the run's conditional edge.
 *
 * <p>Every writer that locks a committed break row holds the source's namespace-4
 * advisory first (the `P8-TSK-012` register fact), the escalation and block writers
 * included.
 */
@Slf4j
public class ReconciliationSweep {

    /** The ageing bands (days since {@code raised_at}): crossing one escalates a level. */
    static final List<Long> BAND_UPPER_BOUNDS = List.of(2L, 7L, 30L);

    public record Config(int batch, int blockAfterFailures) {

        public Config {
            if (batch <= 0) {
                throw new IllegalArgumentException("batch must be positive");
            }
            if (blockAfterFailures <= 0) {
                throw new IllegalArgumentException("blockAfterFailures must be positive");
            }
        }
    }

    public record SweepResult(int aged, int escalated, int blocked, int collisions) {}

    private final MatchingStore store;
    private final BreakRegister breaks;
    private final KeyCollisionBreaks collisions;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;
    private final Config config;
    private final TransactionRunner transactions;

    public ReconciliationSweep(
            MatchingStore store,
            BreakRegister breaks,
            KeyCollisionBreaks collisions,
            OutboxWriter<Connection> outbox,
            IdGenerator ids,
            Clock clock,
            Config config,
            TransactionRunner transactions) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.breaks = Objects.requireNonNull(breaks, "breaks must not be null");
        this.collisions = Objects.requireNonNull(collisions, "collisions must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.transactions =
                Objects.requireNonNull(transactions, "transactions must not be null");
    }

    /** How many ageing bands lie behind {@code days} — pure, the escalation's arithmetic. */
    static int bandsCrossed(long daysSinceRaised) {
        int crossed = 0;
        for (long upper : BAND_UPPER_BOUNDS) {
            if (daysSinceRaised > upper) {
                crossed++;
            }
        }
        return crossed;
    }

    @SuppressWarnings("try") // Scopes are used for their close side effect.
    public SweepResult sweep() {
        int aged = 0;
        int escalated = 0;
        int blocked = 0;
        int raisedCollisions = 0;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            aged = contained("ageing", this::age);
            escalated = contained("escalation", this::escalate);
            blocked = contained("run-block detection", this::detectLostBlocks);
            raisedCollisions = contained("key collisions", this::raiseCollisions);
        }
        return new SweepResult(aged, escalated, blocked, raisedCollisions);
    }

    private int contained(String leg, java.util.function.IntSupplier work) {
        try {
            return work.getAsInt();
        } catch (RuntimeException failure) {
            // The class only: a JDBC message can name hosts and identifiers.
            log.warn("The {} leg failed: {}", leg, failure.getClass().getSimpleName());
            return 0;
        }
    }

    // ----------------------------------------------------------------- ageing

    @SuppressWarnings("try")
    private int age() {
        List<MatchingStore.OverdueCandidate> candidates =
                transactions.inTransaction(
                        unitOfWork -> store.overdueCandidates(unitOfWork, config.batch()));
        int aged = 0;
        for (MatchingStore.OverdueCandidate candidate : candidates) {
            CorrelationId correlation = CorrelationId.of(candidate.correlationId());
            try (CorrelationContext.Scope scope =
                    CorrelationContext.enter(Correlation.startingWith(correlation))) {
                boolean marked =
                        transactions.inTransaction(
                                unitOfWork -> ageOne(unitOfWork, candidate, correlation));
                if (marked) {
                    aged++;
                }
            } catch (RuntimeException failure) {
                log.warn(
                        "An ageing row failed and rolled back: {}",
                        failure.getClass().getSimpleName());
            }
        }
        return aged;
    }

    /** One row, one transaction: the one-way fact, its break and its event, whole. */
    private boolean ageOne(
            Connection unitOfWork,
            MatchingStore.OverdueCandidate candidate,
            CorrelationId correlation) {
        Instant now = Instant.now(clock);
        // The conditional NULL -> value is the ten-sweeper arbiter: a loser writes
        // nothing at all, and money arriving meanwhile empties the predicate.
        if (!store.lockAndMarkOverdue(unitOfWork, candidate.expectationId(), now)) {
            return false;
        }
        BreakRegister.Raised raised =
                breaks.raise(
                        unitOfWork,
                        new BreakRegister.NewBreak(
                                ids.next(),
                                BreakType.MISSING_EXTERNAL,
                                BreakCause.EXPECTATION_OVERDUE,
                                BreakRegister.Subject.expectation(candidate.expectationId()),
                                candidate.sourceId(),
                                candidate.ruleSetId(),
                                candidate.remainder(),
                                Optional.of(candidate.direction()),
                                Optional.of(candidate.kind()),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                SecurityContext.require(),
                                now,
                                correlation));
        ReconciliationEvents.expectationOverdue(
                outbox,
                unitOfWork,
                ids,
                candidate.expectationId(),
                candidate.kind(),
                candidate.operationRef(),
                candidate.expectedBy(),
                raised.breakId(),
                now,
                correlation);
        return true;
    }

    // ----------------------------------------------------------------- escalation

    @SuppressWarnings("try")
    private int escalate() {
        List<MatchingStore.EscalationRow> rows =
                transactions.inTransaction(
                        unitOfWork -> store.unresolvedBreaks(unitOfWork, config.batch()));
        int escalated = 0;
        for (MatchingStore.EscalationRow row : rows) {
            long due = bandsCrossed(row.daysSinceRaised()) - row.escalations();
            if (due <= 0) {
                continue;
            }
            try {
                escalated +=
                        transactions.inTransaction(
                                unitOfWork -> escalateOne(unitOfWork, row, due));
            } catch (RuntimeException failure) {
                log.warn(
                        "An escalation row failed and rolled back: {}",
                        failure.getClass().getSimpleName());
            }
        }
        return escalated;
    }

    @SuppressWarnings("try")
    private int escalateOne(
            Connection unitOfWork, MatchingStore.EscalationRow row, long due) {
        // Every writer locking a committed break row holds its source's advisory first.
        advisorySourceLock(unitOfWork, row.sourceId());
        Instant now = Instant.now(clock);
        int stepped = 0;
        Severity severity = row.severity();
        CorrelationId correlation = CorrelationId.generate(ids);
        try (CorrelationContext.Scope scope =
                CorrelationContext.enter(Correlation.startingWith(correlation))) {
            for (long step = 0; step < due; step++) {
                Severity[] grades = Severity.values();
                if (severity.ordinal() + 1 >= grades.length) {
                    break; // CRITICAL has no level above.
                }
                Severity next = grades[severity.ordinal() + 1];
                if (!store.escalate(
                        unitOfWork, row.breakId(), severity, next,
                        SecurityContext.require(), now, correlation)) {
                    break; // Another sweeper stepped it, or it resolved: converge.
                }
                severity = next;
                stepped++;
            }
        }
        return stepped;
    }

    // ----------------------------------------------------------------- lost blocks

    private int detectLostBlocks() {
        List<MatchingStore.RunRow> runs =
                transactions.inTransaction(
                        unitOfWork ->
                                store.runsAtFailureBound(
                                        unitOfWork, config.blockAfterFailures()));
        int blocked = 0;
        for (MatchingStore.RunRow run : runs) {
            try {
                if (transactions.inTransaction(unitOfWork -> blockOne(unitOfWork, run))) {
                    blocked++;
                }
            } catch (RuntimeException failure) {
                log.warn(
                        "A run-block row failed and rolled back: {}",
                        failure.getClass().getSimpleName());
            }
        }
        return blocked;
    }

    @SuppressWarnings("try")
    private boolean blockOne(Connection unitOfWork, MatchingStore.RunRow run) {
        advisorySourceLock(unitOfWork, run.sourceId());
        Instant now = Instant.now(clock);
        CorrelationId correlation = CorrelationId.of(run.correlationId());
        try (CorrelationContext.Scope scope =
                CorrelationContext.enter(Correlation.startingWith(correlation))) {
            if (!store.blockRun(unitOfWork, run.id(), SecurityContext.require(), now)) {
                return false; // Blocked, completed or taken meanwhile: converge.
            }
            breaks.raise(
                    unitOfWork,
                    new BreakRegister.NewBreak(
                            ids.next(),
                            BreakType.PROCESSING_ERROR,
                            BreakCause.RUN_BLOCKED,
                            BreakRegister.Subject.run(run.id()),
                            run.sourceId(),
                            run.ruleSetId(),
                            // A blocked run holds no money of its own (the P8-TSK-011
                            // stance): the zero rides the presentation currency.
                            com.finapp.sharedkernel.money.Money.ofPersisted(
                                    0, com.finapp.sharedkernel.money.CurrencyCode.of("EUR"),
                                    2),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            SecurityContext.require(),
                            now,
                            correlation));
            return true;
        }
    }

    // ----------------------------------------------------------------- collisions

    @SuppressWarnings("try")
    private int raiseCollisions() {
        CorrelationId correlation = CorrelationId.generate(ids);
        try (CorrelationContext.Scope scope =
                CorrelationContext.enter(Correlation.startingWith(correlation))) {
            return transactions.inTransaction(
                    unitOfWork ->
                            collisions.raiseFromRecordedCollisions(
                                    unitOfWork,
                                    config.batch(),
                                    SecurityContext.require(),
                                    Instant.now(clock),
                                    correlation));
        }
    }

    // ----------------------------------------------------------------- plumbing

    private void advisorySourceLock(Connection unitOfWork, UUID sourceId) {
        try (PreparedStatement lock =
                unitOfWork.prepareStatement(
                        "SELECT pg_advisory_xact_lock(4, hashtext(?::text))")) {
            lock.setObject(1, sourceId);
            lock.execute();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not take the source's advisory lock", failure);
        }
    }
}
