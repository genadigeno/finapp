package com.finapp.platform.idempotency;

import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
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
 * transaction ends, rather than failing at once. When that transaction commits, the second
 * insert fails as a unique violation and the second request replays the now-committed response
 * — the acceptance criterion's "two identical responses".
 *
 * <p><strong>That wait is bounded.</strong> The store sets a {@code lock_timeout} around the
 * claim, so a duplicate waits a few seconds and no longer. Without it the wait is as long as
 * the first command takes, and on a hot key with a retrying client that is how a connection
 * pool is exhausted — the very failure this class refuses to risk by waiting on an
 * {@code IN_PROGRESS} record. A duplicate that gives up is told the outcome is unknown, which
 * is true: the holder may still commit or roll back.
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
 *
 * <h2>The two-transaction command (`P5-TSK-016`)</h2>
 *
 * <p>{@link #execute} is the one-transaction model above and stays the default. A
 * dispatch-before-call command (ADR-0046) cannot use it: its judgement arrives in a
 * <em>second</em> transaction, after a provider call that must hold no connection, and a
 * response recorded at claim time would be a response recorded before the judgement exists —
 * which is how a replay comes to be rendered from a re-read, the exact defect the frozen
 * stored response exists to prevent.
 *
 * <p>{@link #begin} and {@link #complete} are that shape made honest within this model's own
 * rules. {@code begin} claims and runs the dispatch, and the claim <strong>stays
 * {@code IN_PROGRESS}</strong> — claim and dispatch effects still commit together, so the
 * one-transaction section's hazard (an effect no record describes) still cannot arise: the
 * claim row is the record, and the dispatch rows carry the key as their own column.
 * {@code complete} records the judged response in the outcome's transaction, once; the freeze
 * takes it from there. The crash between the two leaves an {@code IN_PROGRESS} claim whose
 * lease runs down exactly as designed — the retry that takes it over re-runs the dispatch,
 * which is why a {@link DispatchCommand} must converge on the committed work by its natural
 * key instead of repeating it.
 */
public final class IdempotentExecutor {

    private final IdempotencyRecordStore<java.sql.Connection> store;
    private final Clock clock;
    private final Duration retention;
    private final Duration lease;

    /**
     * @param retention how long a completed record answers retries. Must exceed every retry
     *     window a client may use: too long merely costs storage, too short costs money
     *     ({@code DATA_MIGRATIONS.md} §8)
     * @param lease how long a claim may be held before another instance may take it over.
     *     Must exceed the longest a command can legitimately take, or a slow command's key is
     *     taken while it is still running. Measured by the database's clock, so it does not
     *     have to absorb clock skew between instances — only genuine command duration
     */
    public IdempotentExecutor(
            IdempotencyRecordStore<java.sql.Connection> store,
            Clock clock,
            Duration retention,
            Duration lease) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.retention = positive(retention, "retention");
        this.lease = positive(lease, "lease");
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

        return switch (store.claim(
                unitOfWork, key, fingerprint, correlationId, now, now.plus(retention), lease)) {
            case CLAIMED -> runAndRecord(unitOfWork, key, command);
            case ALREADY_CLAIMED ->
                    resolveExistingClaim(unitOfWork, key, fingerprint, command, correlationId, now);
            // Someone holds the key in an open transaction and we declined to wait longer. There
            // is nothing committed to read, so the outcome is genuinely unknown - the same
            // situation as a live IN_PROGRESS record, and it gets the same honest answer.
            case CONTENDED -> throw new IdempotencyInProgressException(key);
        };
    }

    /**
     * Tx1 of a two-transaction command: claims the key and runs {@code dispatch} beside the
     * still-{@code IN_PROGRESS} claim, or replays the recorded outcome, or takes over an
     * expired claim and re-runs the (convergent) dispatch.
     *
     * @throws IdempotencyConflictException the key was used for a different request
     * @throws IdempotencyInProgressException another flight holds the key and its lease is
     *     still running — deterministic, bounded, and true
     */
    public BeginOutcome begin(
            java.sql.Connection unitOfWork,
            IdempotencyKey key,
            RequestFingerprint fingerprint,
            DispatchCommand dispatch) {

        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        Objects.requireNonNull(dispatch, "dispatch must not be null");

        Instant now = clock.instant();
        CorrelationId correlationId = currentCorrelationId();

        switch (store.claim(
                unitOfWork, key, fingerprint, correlationId, now, now.plus(retention), lease)) {
            case CLAIMED -> {
                return BeginOutcome.dispatched(dispatch.dispatch(unitOfWork));
            }
            case ALREADY_CLAIMED -> {
                IdempotencyRecord existing =
                        store.find(unitOfWork, key)
                                .orElseThrow(() -> new IdempotencyInProgressException(key));
                if (!fingerprint.matches(existing.fingerprint())) {
                    throw new IdempotencyConflictException(key);
                }
                if (existing.state().isTerminal()) {
                    // The point of the whole exercise: the response of record, byte for byte.
                    return BeginOutcome.replayed(existing.response());
                }
                if (store.reclaimIfLeaseExpired(
                        unitOfWork, key, correlationId, now, now.plus(retention), lease)) {
                    // The crashed flight's key, taken over: the dispatch re-runs and MUST
                    // converge on the work Tx1 committed (the DispatchCommand contract).
                    return BeginOutcome.dispatched(dispatch.dispatch(unitOfWork));
                }
                throw new IdempotencyInProgressException(key);
            }
            case CONTENDED -> throw new IdempotencyInProgressException(key);
        }
        throw new IllegalStateException("unreachable: ClaimOutcome is exhaustive");
    }

    /**
     * Tx2 of a two-transaction command: records the judged response against the
     * {@code IN_PROGRESS} claim, inside the outcome's own transaction — the outcome and the
     * response of record commit together, and the freeze (platform {@code V003}) makes the
     * response final from this commit on.
     *
     * @return {@code true} if this call recorded the outcome; {@code false} when the claim was
     *     already terminal — a takeover finished first, and the loser converges (its own
     *     conditional writes lost the same way)
     */
    public boolean complete(
            java.sql.Connection unitOfWork,
            IdempotencyKey key,
            boolean succeeded,
            StoredResponse response) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(response, "response must not be null");
        return store.complete(
                unitOfWork,
                key,
                succeeded ? IdempotencyState.COMPLETED : IdempotencyState.FAILED,
                response,
                clock.instant());
    }

    /** Tx1's answer: exactly one of the two is present. */
    public record BeginOutcome(Optional<StoredResponse> replay, Optional<byte[]> dispatched) {

        public BeginOutcome {
            Objects.requireNonNull(replay, "replay must not be null");
            Objects.requireNonNull(dispatched, "dispatched must not be null");
            if (replay.isPresent() == dispatched.isPresent()) {
                throw new IllegalArgumentException(
                        "exactly one of replay and dispatched must be present");
            }
        }

        static BeginOutcome replayed(StoredResponse response) {
            return new BeginOutcome(Optional.of(response), Optional.empty());
        }

        static BeginOutcome dispatched(byte[] working) {
            return new BeginOutcome(
                    Optional.empty(),
                    Optional.of(
                            Objects.requireNonNull(
                                    working, "a dispatch must return working state")));
        }
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

        // No client-side staleness pre-check. An earlier version asked this instance's clock
        // whether the claim looked old enough, and only then let the database decide. That
        // pre-check could not make a wrong reclaim happen - the database still had the final
        // say - but it could wrongly *prevent* a legitimate one whenever this instance's clock
        // ran behind, and it made the lease look like something a caller participates in. The
        // database owns the lease; the caller asks and is told.
        if (store.reclaimIfLeaseExpired(
                unitOfWork, key, correlationId, now, now.plus(retention), lease)) {
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
