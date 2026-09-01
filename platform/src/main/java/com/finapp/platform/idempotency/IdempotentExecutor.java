package com.finapp.platform.idempotency;

import com.finapp.platform.correlation.Correlation;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.correlation.CorrelationId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs a command at most once per idempotency key, and replays its outcome on every retry
 * (ADR-0004, {@code INV-IDEM-01}, {@code INV-IDEM-03}).
 *
 * <h2>The sequence</h2>
 *
 * <ol>
 *   <li>Claim the key by inserting an {@code IN_PROGRESS} record.
 *   <li>If the claim succeeds, run the command, record its outcome, return it.
 *   <li>If the key is already claimed, read the existing record and decide: replay it, refuse
 *       it, or take it over.
 * </ol>
 *
 * <h2>One transaction, and why that is the whole design</h2>
 *
 * <p>The claim, the command's financial effect and the recorded outcome all happen inside the
 * caller's transaction, and commit together (ADR-0004). That is not a convenience: it removes
 * the window in which a process can crash having produced a financial effect that no
 * idempotency record describes. If the claim were committed separately, a crash between the two
 * would leave a key claimed for work that never happened, or — far worse — an effect that a
 * retry would repeat because nothing recorded it.
 *
 * <p>The cost is that a concurrent duplicate <em>blocks</em> on the unique index until the first
 * transaction ends, rather than failing at once. That is the correct trade and it is bounded:
 * see {@code lock_timeout} in the class notes of the JDBC store's tests. When the first
 * transaction commits, the second's insert fails as a unique violation and the second request
 * replays the now-committed response — the acceptance criterion's "two identical responses".
 *
 * <h2>What happens to an in-progress claim</h2>
 *
 * <p>A committed {@code IN_PROGRESS} record means a command holds the key and its outcome is
 * unknown. There are exactly three honest responses, and this class does not choose between the
 * first two:
 *
 * <ul>
 *   <li><strong>Wait</strong> — ties up a connection for as long as the other command runs. Under
 *       a retrying client that is how a hot key exhausts a pool.
 *   <li><strong>Re-execute</strong> — assumes the first attempt failed. It may have already
 *       committed, and that assumption is the second financial effect this mechanism exists to
 *       prevent ({@code INV-LIFE-03}).
 *   <li><strong>Say so</strong> — {@link IdempotencyInProgressException}. Deterministic, bounded,
 *       and true.
 * </ul>
 *
 * <p>The exception is the answer, with one qualification: a claim older than
 * {@code staleClaimAfter} is treated as abandoned by a crashed process and may be taken over.
 * Without that, a process that died mid-command would block its key until the record expired —
 * up to a day, during which a customer's payment simply cannot be retried. The takeover is
 * conditional inside the database, so two processes reclaiming at once cannot both win.
 *
 * <h2>What is not here</h2>
 *
 * <p>The HTTP {@code Idempotency-Key} header, its validation and the mapping from these
 * exceptions to status codes are P0-TSK-017. This class is the mechanism; it serves an HTTP
 * command, an event consumer and a scheduled job identically, which is exactly why ADR-0004
 * rejected putting it in a filter.
 */
public final class IdempotentExecutor {

    private final IdempotencyRecordStore<java.sql.Connection> store;
    private final Clock clock;
    private final Duration retention;
    private final Duration staleClaimAfter;

    /**
     * @param retention how long a completed record answers retries. Must exceed every retry
     *     window a client may use: too long merely costs storage, too short costs money
     *     ({@code DATA_MIGRATIONS.md} §8)
     * @param staleClaimAfter how long an {@code IN_PROGRESS} claim may stand before it is
     *     treated as abandoned. Must exceed the longest a command can legitimately take, or a
     *     slow command's key gets stolen while it is still running
     */
    public IdempotentExecutor(
            IdempotencyRecordStore<java.sql.Connection> store,
            Clock clock,
            Duration retention,
            Duration staleClaimAfter) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.retention = positive(retention, "retention");
        this.staleClaimAfter = positive(staleClaimAfter, "staleClaimAfter");
    }

    /**
     * Executes {@code command} at most once for {@code key}, or replays its recorded outcome.
     *
     * @param unitOfWork the caller's open transaction; the claim, the command and the recorded
     *     outcome all commit with it
     * @throws IdempotencyConflictException the key was used for a different request
     *     ({@code INV-IDEM-03})
     * @throws IdempotencyInProgressException the command is running and its outcome is unknown
     */
    public ExecutionOutcome execute(
            java.sql.Connection unitOfWork,
            IdempotencyKey key,
            RequestFingerprint fingerprint,
            IdempotentCommand command) {

        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        Objects.requireNonNull(command, "command must not be null");

        Instant now = clock.instant();
        CorrelationId correlationId = currentCorrelationId();

        if (store.claim(unitOfWork, key, fingerprint, correlationId, now, now.plus(retention))) {
            return runAndRecord(unitOfWork, key, command);
        }
        return resolveExistingClaim(unitOfWork, key, fingerprint, command, correlationId, now);
    }

    // -----------------------------------------------------------------

    private ExecutionOutcome runAndRecord(
            java.sql.Connection unitOfWork, IdempotencyKey key, IdempotentCommand command) {

        CommandResult result = command.run(unitOfWork);
        Objects.requireNonNull(result, "a command must return an outcome, not null");

        IdempotencyState terminal =
                result.succeeded() ? IdempotencyState.COMPLETED : IdempotencyState.FAILED;

        // Recorded inside the caller's transaction, so a rolled-back effect takes its claim with
        // it. A record that outlived a rolled-back command would block the key for work that
        // never happened.
        if (!store.complete(unitOfWork, key, terminal, result.response(), clock.instant())) {
            throw new IdempotencyStorageException(
                    "The claim for " + key + " was no longer in progress when its outcome was "
                            + "recorded; something else modified it inside this transaction",
                    null);
        }
        return new ExecutionOutcome(terminal, result.response(), false);
    }

    private ExecutionOutcome resolveExistingClaim(
            java.sql.Connection unitOfWork,
            IdempotencyKey key,
            RequestFingerprint fingerprint,
            IdempotentCommand command,
            CorrelationId correlationId,
            Instant now) {

        IdempotencyRecord existing =
                store.find(unitOfWork, key)
                        .orElseThrow(
                                () ->
                                        // The claim was refused, so a row existed; if it is gone the
                                        // retention sweep removed it mid-flight. Retrying is safe and
                                        // is what the caller should do, so this is reported as such
                                        // rather than as a lost command.
                                        new IdempotencyInProgressException(key));

        // The fingerprint decides first, and deliberately so. A different request must be
        // refused whatever state the existing claim is in - replaying a stored response to it
        // would be answering a question nobody asked, and reclaiming its key would let a
        // different command inherit it.
        if (!fingerprint.matches(existing.fingerprint())) {
            throw new IdempotencyConflictException(key);
        }

        if (existing.state().isTerminal()) {
            // The point of the whole exercise: the original outcome, byte for byte.
            return new ExecutionOutcome(existing.state(), existing.response(), true);
        }

        if (existing.isStaleAt(now, staleClaimAfter)
                && store.reclaimIfStale(
                        unitOfWork,
                        key,
                        fingerprint,
                        correlationId,
                        now.minus(staleClaimAfter),
                        now,
                        now.plus(retention))) {
            return runAndRecord(unitOfWork, key, command);
        }

        throw new IdempotencyInProgressException(key);
    }

    /**
     * The flow that made this claim.
     *
     * <p>Absent only outside any correlation scope, which no request path should be. A generated
     * placeholder would be worse than nothing: it would satisfy the {@code NOT NULL} while
     * pointing at no flow at all, so the duplicate submission it records could never be traced
     * back. Failing here surfaces the wiring defect instead.
     */
    private static CorrelationId currentCorrelationId() {
        return CorrelationContext.current()
                .map(Correlation::correlationId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "A money-moving command must run inside a correlation scope, so "
                                                + "that a duplicate submission can be traced to the request "
                                                + "that made it (P0-TSK-014)"));
    }

    private static Duration positive(Duration value, String what) {
        Objects.requireNonNull(value, what + " must not be null");
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(what + " must be positive but was " + value);
        }
        return value;
    }

    /** What happened, and whether it happened now or was replayed. */
    public record ExecutionOutcome(
            IdempotencyState state, StoredResponse response, boolean replayed) {

        public ExecutionOutcome {
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(response, "response must not be null");
        }

        /** True when the command ran, false when a stored outcome was returned instead. */
        public boolean executed() {
            return !replayed;
        }

        public Optional<byte[]> body() {
            return response.bodyBytes();
        }
    }
}
