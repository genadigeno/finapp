package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Statement continuity (`P8-TSK-016`, {@code INV-SET-06}, ADR-0069 §2's statement causes): the
 * chain of a bank account's statements is the truth about its cash, so every accepted statement
 * is judged against its neighbours — in the acceptance transaction, under the settlement source
 * row lock that serialises every acceptance of the account.
 *
 * <h2>Its own seam</h2>
 *
 * <ul>
 *   <li>The FIRST statement (sequence 1) opens at zero — the platform's account opened empty; a
 *       non-zero first opening is {@code OPENING_BALANCE}: value nobody booked, never posted to
 *       make the chain fit (an equity account for a genuine opening is Phase 14's).
 *   <li>A later statement stitches to its ACCEPTED predecessor: absent, or present with a
 *       closing that is not this opening, it is {@code STATEMENT_GAP} — the chain has a hole the
 *       platform cannot see across (the lifecycle's taxonomy: a sequence gap, or an opening
 *       unequal to the previous closing).
 * </ul>
 *
 * <p>Both are {@code SETTLEMENT_MISMATCH} on the statement's RUN (the run names its batch), CRITICAL
 * by {@link BreakSeverity}, never parked, and resolvable only by evidence
 * ({@code ResolutionTemplates} admits no person's kind for them).
 *
 * <h2>Its successor's seam</h2>
 *
 * <p>A statement that FILLS a gap — its successor already accepted and standing on an open
 * {@code STATEMENT_GAP} — closes that break {@code EVIDENCED}, naming itself as the evidence,
 * exactly when the successor's opening is this closing. A fill that does not stitch leaves the
 * gap OPEN: the chain is still not the truth, and the cash proof keeps failing loudly. A gap
 * raised against a PRESENT predecessor (a mis-stitched opening) has nothing left to fill it: it
 * stays open until the repudiation of the wrong statement and its correction (`P8-TSK-023`).
 *
 * <p>The closure locks a COMMITTED break row, so it first takes the break's source's namespace-4
 * advisory — the blocking form of the key every committed-break writer holds
 * (`DISTRIBUTED_EXECUTION.md` §3). No cycle: the matcher that holds the same advisory never waits
 * on a settlement row, and this transaction's other reconciliation rows are its own.
 */
@RequiredArgsConstructor
public final class StatementChain {

    @NonNull private final BreakRegister register;
    @NonNull private final Resolutions resolutions;
    @NonNull private final IdGenerator ids;

    /** An accepted neighbouring statement: its batch, sequence and signed balances. */
    public record Link(UUID batchId, long sequence, Money opening, Money closing) {

        public Link {
            Objects.requireNonNull(batchId, "batchId must not be null");
            Objects.requireNonNull(opening, "opening must not be null");
            Objects.requireNonNull(closing, "closing must not be null");
        }
    }

    /** The statement being accepted, with the neighbours read under the source row lock. */
    public record Statement(
            UUID batchId,
            UUID runId,
            UUID sourceId,
            UUID ruleSetId,
            long sequence,
            Money opening,
            Money closing,
            Optional<Link> predecessor,
            Optional<Link> successor,
            Actor actor,
            Instant at,
            CorrelationId correlation) {

        public Statement {
            Objects.requireNonNull(batchId, "batchId must not be null");
            Objects.requireNonNull(runId, "runId must not be null");
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
            Objects.requireNonNull(opening, "opening must not be null");
            Objects.requireNonNull(closing, "closing must not be null");
            Objects.requireNonNull(predecessor, "predecessor must not be null");
            Objects.requireNonNull(successor, "successor must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(at, "at must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            if (sequence < 1) {
                throw new IllegalArgumentException("a statement sequence starts at 1");
            }
        }
    }

    /** A broken seam: which cause, and the value the chain cannot explain. */
    public record Seam(BreakCause cause, Money valueAtIssue) {}

    /** What the judgement wrote: breaks raised on this run, and whether a gap was filled. */
    public record Outcome(int raised, boolean gapFilled) {}

    /**
     * This statement's own seam, pure: {@code OPENING_BALANCE} for a non-zero FIRST opening;
     * {@code STATEMENT_GAP} for a missing predecessor (value the opening) or a predecessor whose
     * closing is not this opening (value the difference); empty when the chain holds.
     */
    public static Optional<Seam> seamOf(long sequence, Money opening, Optional<Link> predecessor) {
        Objects.requireNonNull(opening, "opening must not be null");
        Objects.requireNonNull(predecessor, "predecessor must not be null");
        if (sequence == 1) {
            return opening.minorUnits() == 0
                    ? Optional.empty()
                    : Optional.of(new Seam(BreakCause.OPENING_BALANCE, absolute(opening)));
        }
        if (predecessor.isEmpty()) {
            return Optional.of(new Seam(BreakCause.STATEMENT_GAP, absolute(opening)));
        }
        if (predecessor.get().sequence() != sequence - 1) {
            throw new IllegalArgumentException("a predecessor is the statement just before");
        }
        Money difference = opening.minus(predecessor.get().closing());
        return difference.minorUnits() == 0
                ? Optional.empty()
                : Optional.of(new Seam(BreakCause.STATEMENT_GAP, absolute(difference)));
    }

    /** Whether this closing stitches the successor's opening — the gap-fill's one condition. */
    public static boolean stitches(Money closing, Link successor) {
        return successor.opening().equals(closing);
    }

    /** Judges one accepted statement: its own seam, then the successor's gap it may fill. */
    public Outcome judge(Connection unitOfWork, Statement statement) {
        int raised = 0;
        Optional<Seam> seam =
                seamOf(statement.sequence(), statement.opening(), statement.predecessor());
        if (seam.isPresent()) {
            BreakRegister.Raised outcome =
                    register.raise(
                            unitOfWork,
                            new BreakRegister.NewBreak(
                                    ids.next(),
                                    BreakType.SETTLEMENT_MISMATCH,
                                    seam.get().cause(),
                                    BreakRegister.Subject.run(statement.runId()),
                                    statement.sourceId(),
                                    statement.ruleSetId(),
                                    seam.get().valueAtIssue(),
                                    Optional.empty(),
                                    Optional.empty(),
                                    Optional.empty(),
                                    Optional.empty(),
                                    Optional.empty(),
                                    Optional.empty(),
                                    statement.actor(),
                                    statement.at(),
                                    statement.correlation()));
            if (outcome.created()) {
                raised++;
            }
        }
        boolean filled = false;
        if (statement.successor().isPresent()
                && stitches(statement.closing(), statement.successor().get())) {
            filled = fillGap(unitOfWork, statement, statement.successor().get());
        }
        return new Outcome(raised, filled);
    }

    private boolean fillGap(Connection unitOfWork, Statement statement, Link successor) {
        advisorySourceLock(unitOfWork, statement.sourceId());
        Optional<GapBreak> gap = lockOpenGap(unitOfWork, successor.batchId());
        if (gap.isEmpty()) {
            return false; // The successor stitched from birth, or a racer closed it: converge.
        }
        return resolutions.evidence(
                unitOfWork,
                new Resolutions.Evidence(
                        ids.next(),
                        gap.get().breakId(),
                        gap.get().value(),
                        gap.get().residualVersion(),
                        Optional.empty(),
                        Optional.of(statement.batchId()),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        gap.get().ruleSetId(),
                        statement.actor(),
                        statement.at(),
                        statement.correlation()));
    }

    private record GapBreak(UUID breakId, Money value, long residualVersion, UUID ruleSetId) {}

    private static void advisorySourceLock(Connection unitOfWork, UUID sourceId) {
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

    /** The successor run's open STATEMENT_GAP, locked — the seat is one open per (type, run). */
    private static Optional<GapBreak> lockOpenGap(Connection unitOfWork, UUID successorBatchId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT b.id, b.value_at_issue_minor, b.currency, b.scale,"
                                + " b.residual_version, b.rule_set_id"
                                + " FROM reconciliation.break b"
                                + " JOIN reconciliation.reconciliation_batch r ON r.id = b.run_id"
                                + " WHERE r.batch_id = ? AND b.type = 'SETTLEMENT_MISMATCH'"
                                + " AND b.cause = 'STATEMENT_GAP' AND b.status <> 'RESOLVED'"
                                + " FOR UPDATE OF b")) {
            read.setObject(1, successorBatchId);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new GapBreak(
                                row.getObject("id", UUID.class),
                                Money.ofPersisted(
                                        row.getLong("value_at_issue_minor"),
                                        CurrencyCode.of(row.getString("currency").trim()),
                                        row.getInt("scale")),
                                row.getLong("residual_version"),
                                row.getObject("rule_set_id", UUID.class)));
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not lock the successor's gap break", failure);
        }
    }

    private static Money absolute(Money amount) {
        return amount.minorUnits() < 0 ? amount.negated() : amount;
    }
}
