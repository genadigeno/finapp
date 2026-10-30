package com.finapp.ledger;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The ledger's posting cutoff and its one API (`P14-TSK-004`; ADR-0114 decisions 1-2; {@code PHASE_14_PLAN.md} sections
 * 8.1-8.3; {@code INV-ACC-03}, {@code INV-ACC-08}).
 *
 * <h2>What it guards</h2>
 *
 * <p>{@code ledger.posting_cutoff.closed_through}: every journal entry dated on or before it is refused at insert by
 * ledger {@code V028}'s trigger ({@link PeriodClosedException}), whoever writes it. {@code NULL} until the first period
 * lock - so nothing changes until accounting locks a period. The period machine is accounting's
 * ({@code P14-TSK-005}), which calls this through its {@code PostingCutoff} port ({@code app}'s
 * {@code LedgerPostingCutoff}) in the lock, unlock and reopen transactions; the date is the ledger's.
 *
 * <h2>Exactly one period, forward or back</h2>
 *
 * <p>{@link #advance}: from unset to any past month end (the first lock), or from a month end to the next one, never
 * covering today. {@link #moveBack}: to the value the latest advance into the current cutoff came from - the previous
 * month end, or unset when the current one was the first lock. Each move is one conditional {@code UPDATE} (the
 * expected {@code from} is its predicate), one revision, and one {@code posting_cutoff_event} row with the actor,
 * correlation and reason - in the caller's transaction. {@code V028}'s triggers re-judge the same shape for every
 * updater, and refuse a move with no event at commit.
 *
 * <h2>Serialised with every posting (INV-ACC-08)</h2>
 *
 * <p>Each move first takes advisory namespace {@code 14} on {@code hashtext('cutoff')} <strong>exclusive</strong>
 * ({@link #LOCK_SQL}); every journal insert takes the same key <strong>shared</strong> in the trigger
 * ({@link #SHARED_LOCK}). The move therefore waits for every in-flight posting and every later posting waits for the
 * move: once it commits, no entry dated in the period commits. The lock order (the plan's section 8.2): accounting
 * period rows (the caller's) → this key → the cutoff row → the event; the move takes no money row and a posting takes
 * no accounting row, so no cycle exists.
 *
 * <h2>Bounded, and retried</h2>
 *
 * <p>While a move waits for the exclusive key, every new posting queues behind it - so the wait is bounded: each
 * attempt runs under {@code lock_timeout} {@link #LOCK_WAIT} inside a savepoint, and a refused attempt rolls back to
 * it (the caller's transaction stays usable, the queued postings pass) and tries again, {@link #LOCK_ATTEMPTS} times,
 * before {@link PostingCutoffBusyException}. The caller's own {@code lock_timeout} is restored once the key is held
 * (the {@code JdbcIdempotencyRecordStore} precedent: a timeout chosen for one wait never leaks into the caller's
 * writes).
 *
 * <h2>Idempotent</h2>
 *
 * <p>A replayed move - the cutoff already at its {@code to} - answers {@link Outcome#UNCHANGED} and writes nothing; a
 * move whose {@code from} is stale and whose {@code to} is not where the cutoff stands is refused
 * ({@link IllegalCutoffMoveException}).
 */
@RequiredArgsConstructor
public final class PostingCutoffs {

    /** Accounting's advisory namespace ({@code DISTRIBUTED_EXECUTION.md} section 3), shared with key {@code mapping}. */
    public static final int NAMESPACE = 14;

    /** This key - never {@code mapping} ({@code P14-TSK-003}'s, a different {@code hashtext}). */
    public static final String KEY = "cutoff";

    /** The move's lock: exclusive, transaction-scoped, taken first. */
    public static final String LOCK_SQL = "SELECT pg_advisory_xact_lock(" + NAMESPACE + ", hashtext('" + KEY + "'))";

    /** The posting's lock, taken by {@code V028}'s trigger on every {@code journal_entry} insert (pinned there). */
    public static final String SHARED_LOCK = "pg_advisory_xact_lock_shared(" + NAMESPACE + ", hashtext('" + KEY + "'))";

    /** The SQLSTATE of {@code V028}'s refusal, translated by {@link JdbcJournalEntryStore}. */
    public static final String PERIOD_CLOSED_SQLSTATE = "LDCUT";

    /** The marker of {@code V028}'s refusal of a move that is not one period. */
    static final String REFUSED_MOVE_MARKER = "ledger_posting_cutoff_moves_one_period";

    static final int MAX_REASON = 500;

    /** One attempt's bound on the exclusive key - the longest a move makes new postings queue behind it. */
    static final Duration LOCK_WAIT = Duration.ofSeconds(2);

    /** Attempts before the move gives up - nothing moved, the caller retries its act later. */
    static final int LOCK_ATTEMPTS = 5;

    static final String LOCK_NOT_AVAILABLE = "55P03";

    static final String READ_LOCK_TIMEOUT = "SELECT current_setting('lock_timeout')";

    static final String SET_LOCK_TIMEOUT = "SELECT set_config('lock_timeout', ?, true)";

    static final String READ = "SELECT closed_through FROM ledger.posting_cutoff WHERE id = 1";

    static final String MOVE = "UPDATE ledger.posting_cutoff SET closed_through = ?, revision = revision + 1"
            + " WHERE id = 1 AND closed_through IS NOT DISTINCT FROM ? RETURNING revision";

    static final String EVENT = "INSERT INTO ledger.posting_cutoff_event (id, revision, movement,"
            + " from_closed_through, to_closed_through, actor_id, correlation_id, causation_id, reason, occurred_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** What a move did. */
    public enum Outcome {
        /** The cutoff moved one period and the move is recorded. */
        MOVED,
        /** The cutoff already stood at the move's target - a replay; nothing written. */
        UNCHANGED
    }

    /** The event's movement - {@code posting_cutoff_event.movement}'s closed vocabulary. */
    enum Movement {
        ADVANCE,
        MOVE_BACK
    }

    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The cutoff now, on {@code unitOfWork} - empty until the first lock. Reads; takes no lock. */
    public Optional<LocalDate> closedThrough(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try {
            return read(unitOfWork);
        } catch (SQLException failure) {
            throw new LedgerStorageException(DatabaseFailure.describe("reading the posting cutoff", failure));
        }
    }

    /**
     * Advances the cutoff one period - a period's lock.
     *
     * @param from the cutoff the caller expects - empty when no period was ever locked (or the first lock was undone)
     * @param to the locked period's last day: a month end before today, and the month end after {@code from}
     * @throws IllegalCutoffMoveException not one period, over today, or the cutoff stands elsewhere
     * @throws PostingCutoffBusyException the key stayed busy through every bounded attempt; nothing moved
     */
    public Outcome advance(Connection unitOfWork, Optional<LocalDate> from, LocalDate to, String reason) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        checkAdvance(from, to, LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
        return move(unitOfWork, Movement.ADVANCE, from, Optional.of(to), reason);
    }

    /**
     * Moves the cutoff back one period - a period's unlock or reopen.
     *
     * @param from the cutoff the caller expects - the period being unlocked or reopened ends on it
     * @param to the previous month end, or empty when {@code from} was the first lock ({@code V028} re-judges which)
     * @throws IllegalCutoffMoveException not one period back, or the cutoff stands elsewhere
     * @throws PostingCutoffBusyException the key stayed busy through every bounded attempt; nothing moved
     */
    public Outcome moveBack(Connection unitOfWork, LocalDate from, Optional<LocalDate> to, String reason) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        checkMoveBack(from, to);
        return move(unitOfWork, Movement.MOVE_BACK, Optional.of(from), to, reason);
    }

    /** The advance's shape, before any statement - a malformed request never takes the lock. */
    static void checkAdvance(Optional<LocalDate> from, LocalDate to, LocalDate today) {
        requireMonthEnd(to, "to");
        if (!to.isBefore(today)) {
            throw new IllegalCutoffMoveException("the posting cutoff never covers today or the future: " + to
                    + " is not before " + today + " (INV-ACC-08)");
        }
        if (from.isPresent()) {
            requireMonthEnd(from.get(), "from");
            LocalDate next = monthEnd(from.get().plusDays(1));
            if (!to.equals(next)) {
                throw new IllegalCutoffMoveException("an advance moves the posting cutoff one period: from "
                        + from.get() + " the next is " + next + ", not " + to + " (INV-ACC-08)");
            }
        }
    }

    /** The move back's shape; which of "previous month end" and "unset" is right is {@code V028}'s to judge. */
    static void checkMoveBack(LocalDate from, Optional<LocalDate> to) {
        requireMonthEnd(from, "from");
        if (to.isPresent()) {
            LocalDate previous = from.withDayOfMonth(1).minusDays(1);
            if (!to.get().equals(previous)) {
                throw new IllegalCutoffMoveException("a move back moves the posting cutoff one period: from " + from
                        + " the previous is " + previous + " (or unset after the first lock), not " + to.get()
                        + " (INV-ACC-08)");
            }
        }
    }

    static LocalDate monthEnd(LocalDate day) {
        return day.with(TemporalAdjusters.lastDayOfMonth());
    }

    private static void requireMonthEnd(LocalDate day, String name) {
        if (!day.equals(monthEnd(day))) {
            throw new IllegalArgumentException("a posting cutoff is a calendar month's last day (C9): " + name + " "
                    + day + " is not");
        }
    }

    private Outcome move(
            Connection unitOfWork,
            Movement movement,
            Optional<LocalDate> from,
            Optional<LocalDate> to,
            String reason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        String why = reason.strip();
        if (why.isEmpty() || why.length() > MAX_REASON) {
            throw new IllegalArgumentException("a cutoff move carries its reason, 1.." + MAX_REASON + " characters");
        }
        // An unestablished actor is an error, never a default (ADR-0021): a move nobody can be asked about is what the
        // event row exists to prevent.
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        try {
            lockExclusively(unitOfWork);
            Long revision = null;
            try (PreparedStatement update = unitOfWork.prepareStatement(MOVE)) {
                bind(update, 1, to);
                bind(update, 2, from);
                try (ResultSet row = update.executeQuery()) {
                    if (row.next()) {
                        revision = row.getLong(1);
                    }
                }
            }
            if (revision == null) {
                // Under the exclusive lock the row cannot move again before this transaction ends: what it reads is
                // where the cutoff stands.
                Optional<LocalDate> standing = read(unitOfWork);
                if (standing.equals(to)) {
                    return Outcome.UNCHANGED;
                }
                throw new IllegalCutoffMoveException("the posting cutoff stands at " + text(standing) + ", not at "
                        + text(from) + " - a stale " + movement + " to " + text(to) + " (INV-ACC-08)");
            }
            try (PreparedStatement event = unitOfWork.prepareStatement(EVENT)) {
                event.setObject(1, ids.next());
                event.setLong(2, revision);
                event.setString(3, movement.name());
                bind(event, 4, from);
                bind(event, 5, to);
                event.setString(6, actor.id());
                event.setString(7, correlation.correlationId().value());
                event.setString(8, correlation.cause().orElseThrow().value());
                event.setString(9, why);
                event.setTimestamp(10, Timestamp.from(clock.instant()));
                event.executeUpdate();
            }
            return Outcome.MOVED;
        } catch (SQLException failure) {
            // V028's shape trigger, the V007 pattern: the marker is matched, never the prose, and the composed message
            // carries dates, never a row.
            if (failure.getMessage() != null && failure.getMessage().contains(REFUSED_MOVE_MARKER)) {
                throw new IllegalCutoffMoveException("the ledger refused moving the posting cutoff from " + text(from)
                        + " to " + text(to) + ": one period forward or back only (INV-ACC-08)");
            }
            throw new LedgerStorageException(DatabaseFailure.describe("moving the posting cutoff", failure));
        }
    }

    /** The exclusive key, each attempt bounded and confined to a savepoint (class comment). */
    private static void lockExclusively(Connection unitOfWork) throws SQLException {
        String callers = Objects.requireNonNullElse(scalar(unitOfWork, READ_LOCK_TIMEOUT), "0");
        for (int attempt = 1; ; attempt++) {
            Savepoint beforeLock = unitOfWork.setSavepoint();
            try {
                setLockTimeout(unitOfWork, LOCK_WAIT.toMillis() + "ms");
                try (PreparedStatement lock = unitOfWork.prepareStatement(LOCK_SQL)) {
                    lock.execute();
                }
                setLockTimeout(unitOfWork, callers);
                unitOfWork.releaseSavepoint(beforeLock);
                return;
            } catch (SQLException failure) {
                if (!LOCK_NOT_AVAILABLE.equals(failure.getSQLState())) {
                    throw failure;
                }
                // Undoes the bounded timeout too; the postings queued behind this attempt pass before the next.
                unitOfWork.rollback(beforeLock);
                if (attempt == LOCK_ATTEMPTS) {
                    throw new PostingCutoffBusyException("the posting cutoff's lock was not granted in " + LOCK_ATTEMPTS
                            + " attempts of " + LOCK_WAIT.toMillis() + " ms - a long posting transaction holds it;"
                            + " nothing moved, retry the act (INV-ACC-08)");
                }
            }
        }
    }

    private static void setLockTimeout(Connection unitOfWork, String value) throws SQLException {
        try (PreparedStatement set = unitOfWork.prepareStatement(SET_LOCK_TIMEOUT)) {
            set.setString(1, value);
            set.executeQuery().close();
        }
    }

    private static String scalar(Connection unitOfWork, String sql) throws SQLException {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql);
                ResultSet row = read.executeQuery()) {
            return row.next() ? row.getString(1) : null;
        }
    }

    private static Optional<LocalDate> read(Connection unitOfWork) throws SQLException {
        try (PreparedStatement read = unitOfWork.prepareStatement(READ);
                ResultSet row = read.executeQuery()) {
            if (!row.next()) {
                throw new LedgerStorageException("the posting cutoff row is missing (ledger V028 seeds it)");
            }
            return Optional.ofNullable(row.getObject(1, LocalDate.class));
        }
    }

    private static void bind(PreparedStatement statement, int index, Optional<LocalDate> date) throws SQLException {
        if (date.isPresent()) {
            statement.setObject(index, date.get());
        } else {
            statement.setNull(index, Types.DATE);
        }
    }

    private static String text(Optional<LocalDate> date) {
        return date.map(LocalDate::toString).orElse("unset");
    }

    /** The flow's correlation with the cause resolved - the {@link PostingService} idiom. */
    private static Correlation resolvedCorrelation() {
        Correlation current =
                CorrelationContext.current()
                        .orElseThrow(() -> new IllegalStateException(
                                "a posting cutoff move must run inside a correlation scope: its event row carries the"
                                        + " identifier"));
        return current.cause().isPresent()
                ? current
                : current.causing(CausationId.of(current.correlationId().value()));
    }
}
