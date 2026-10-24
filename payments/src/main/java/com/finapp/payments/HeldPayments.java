package com.finapp.payments;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * A held card payment's doors and its expiry (`P13-TSK-010`, slice 2; {@code PHASE_13_PLAN.md} §5, §7.3, §12.3, §12.8,
 * §14; ADR-0108, ADR-0112; {@code INV-RSK-02}, {@code INV-PAY-04}): the risk seam's {@code HOLD} authorised the card and
 * left a {@link CaptureHold} open; these are the only ways it ends, each <strong>exactly once</strong>.
 *
 * <ul>
 *   <li>{@link #captureHeld} - an approved override (`-012`) or a disposition's action (`-016`/`-017`) releasing the
 *       capture: in ONE transaction under the caller's key ({@link #CAPTURE_SCOPE}), the hold row {@code FOR UPDATE},
 *       the conditional {@code OPEN → CAPTURE} <strong>within {@code held_until} on the database clock</strong> (past it
 *       {@code REFUSED}, nothing written: the expiry is the policy's outcome), audited as the caller's act; then the
 *       capture itself through {@link PaymentCapture} - its own Tx1, the provider called holding no connection
 *       (ADR-0046), its own Tx2 with the posting.</li>
 *   <li>{@link #voidHeld} - the same shape: {@code OPEN → VOID} under {@link #VOID_SCOPE}, then the authorisation
 *       released through {@link PaymentVoid}.</li>
 *   <li>{@link #expireHolds} - the deadline: a leg of the existing {@code PaymentSweeperSchedule} (no new scheduler):
 *       ONE open hold past {@code held_until} on the database clock per transaction, claimed {@code FOR UPDATE SKIP
 *       LOCKED}, resolved {@code EXPIRED} as the platform; then the finisher pass voids every hold resolved
 *       {@code VOID}/{@code EXPIRED} whose attempt still stands {@code AUTHORIZED} - the expiry's own void, and the void a
 *       door's crash left undispatched.</li>
 * </ul>
 *
 * <p><strong>Exactly once, on any number of instances.</strong> The resolution is one conditional statement
 * ({@code resolution IS NULL}) under the hold row's lock, with {@code V034}'s trigger beneath it for every writer: a
 * capture racing the deadline leaves one resolution and the loser reads {@link HeldOutcome#ALREADY} (or
 * {@link HeldOutcome#REFUSED} for a capture that arrives past {@code held_until} before the leg); the money then moves
 * at most once behind the existing commands' own conditionals and keys ({@code payment-capture:<attempt>}). A crash
 * between the resolution's commit and the command is finished by the stranded-chain leg ({@code CAPTURE}) or this leg's
 * finisher ({@code VOID}/{@code EXPIRED}); a re-driven key replays its recorded answer and re-drives the converging
 * command.
 *
 * <p><strong>Who may act.</strong> The platform (risk's action machine, T-act) or an employee (the override's approval
 * door) - never a customer; the reason is a reason <em>code</em> ({@code [A-Z][A-Z0-9_]{0,63}}), never a note
 * ({@code INV-RSK-08}). An authorisation still in flight ({@code AUTH_DISPATCHED}/{@code AUTH_UNKNOWN}) is thrown - nothing
 * recorded under the key - so the caller re-drives it once the issuer has answered.
 */
@Slf4j
public final class HeldPayments {

    /** The capture door's claim scope; the caller's key completes it ({@code risk.action:<id>}). */
    public static final String CAPTURE_SCOPE = "payments.payment.capture-held";

    /** The void door's claim scope. */
    public static final String VOID_SCOPE = "payments.payment.void-held";

    /** A door's reason: a reason CODE, never a note ({@code INV-RSK-08}). */
    static final Pattern REASON_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    /** The reason the expiry's void records - the platform's own code. */
    static final String EXPIRY_REASON = "HOLD_EXPIRED";

    /** The reason the finisher's void of a door's {@code VOID} records - the platform's own code. */
    static final String VOIDED_REASON = "HELD_PAYMENT_VOIDED";

    /** What a held payment's door did - risk's {@code ActionOutcome}, in payments' words. */
    public enum HeldOutcome {
        /** This call (or this key's earlier call) resolved the hold. */
        DONE,
        /** The hold was already resolved by another hand, or the authorisation no longer stands. */
        ALREADY,
        /** No held payment answers to the intent, or - a capture - its {@code held_until} has passed. */
        REFUSED
    }

    /** A door's answer: the outcome, the hold's resolution when one stands, whether it replays the key's. */
    public record Answer(HeldOutcome outcome, Optional<CaptureHold.Resolution> resolution, boolean replayed) {
        public Answer {
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(resolution, "resolution must not be null");
            if (outcome == HeldOutcome.DONE && resolution.isEmpty()) {
                throw new IllegalArgumentException("a door that acted names the resolution it recorded");
            }
            if (outcome == HeldOutcome.REFUSED && resolution.isPresent()) {
                throw new IllegalArgumentException("a refusal resolved nothing");
            }
        }
    }

    /** One expiry tick's tally - counts only, never the count of record (the rows are). */
    public record ExpiryTally(int expired, int voided, int failed) {}

    /**
     * The existing commands a resolution hands the money to - {@link PaymentCapture} and {@link PaymentVoid}, each in
     * its own transactions with the provider called holding no connection (ADR-0046). An interface so the doors'
     * exactly-once is testable without a provider; production composes {@link #over}.
     */
    public interface Commands {

        /** The capture of a hold released for {@code CAPTURE} - converging when already dispatched. */
        void capture(PaymentAttemptId attempt);

        /**
         * The void of a hold resolved {@code VOID} or {@code EXPIRED}, recorded with {@code reason} (a code) as the
         * acting caller's; {@code true} when this call dispatched it, {@code false} when it converged.
         */
        boolean release(PaymentIntentId intent, String reason);

        /** The production commands. */
        static Commands over(PaymentCapture capture, PaymentVoid voids) {
            Objects.requireNonNull(capture, "capture must not be null");
            Objects.requireNonNull(voids, "voids must not be null");
            return new Commands() {
                @Override
                public void capture(PaymentAttemptId attempt) {
                    capture.capture(attempt);
                }

                @Override
                public boolean release(PaymentIntentId intent, String reason) {
                    return !voids.voidAuthorized(Optional.empty(), intent, Optional.of(reason)).converged();
                }
            };
        }
    }

    private final TransactionRunner transactions;
    private final CaptureHoldStore<Connection> holds;
    private final PaymentAttemptStore<Connection> attempts;
    private final IdempotentExecutor executor;
    private final AuditWriter<Connection> audit;
    private final Commands commands;
    private final IdGenerator ids;
    private final Clock clock;
    private final int batchSize;

    public HeldPayments(
            TransactionRunner transactions,
            CaptureHoldStore<Connection> holds,
            PaymentAttemptStore<Connection> attempts,
            IdempotentExecutor executor,
            AuditWriter<Connection> audit,
            Commands commands,
            IdGenerator ids,
            Clock clock,
            int batchSize) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.holds = Objects.requireNonNull(holds, "holds must not be null");
        this.attempts = Objects.requireNonNull(attempts, "attempts must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
        }
        this.batchSize = batchSize;
    }

    // ----------------------------------------------------------------- the doors

    /**
     * Releases a held card payment for capture (the class javadoc): {@code DONE} with {@code CAPTURE}, then the capture
     * dispatched; {@code ALREADY} when the hold was resolved by another hand or the authorisation no longer stands;
     * {@code REFUSED} when no held payment answers to {@code intent} or its {@code held_until} has passed.
     *
     * @throws IllegalArgumentException a malformed key or reason code
     * @throws IllegalStateException a caller that is neither the platform nor an employee
     * @throws IllegalPaymentAttemptTransitionException the authorisation is still in flight - nothing recorded
     * @throws com.finapp.platform.idempotency.IdempotencyConflictException the key reused for another payment
     */
    public Answer captureHeld(String key, PaymentIntentId intent, String reasonCode) {
        Answer answer = door(CAPTURE_SCOPE, CaptureHold.Resolution.CAPTURE, key, intent, reasonCode);
        if (answer.outcome() == HeldOutcome.DONE) {
            // The capture itself - fresh or re-driven by the key - holding no transaction of ours (ADR-0046): its Tx1's
            // conditional out of AUTHORIZED and its posting key make a second call converge.
            commands.capture(attemptOf(intent));
        }
        return answer;
    }

    /**
     * Voids a held card payment (the class javadoc): {@code DONE} with {@code VOID}, then the authorisation released;
     * {@code ALREADY}; {@code REFUSED} when no held payment answers to {@code intent}.
     *
     * @throws IllegalArgumentException a malformed key or reason code
     * @throws IllegalStateException a caller that is neither the platform nor an employee
     * @throws IllegalPaymentAttemptTransitionException the authorisation is still in flight - nothing recorded
     * @throws com.finapp.platform.idempotency.IdempotencyConflictException the key reused for another payment
     */
    public Answer voidHeld(String key, PaymentIntentId intent, String reasonCode) {
        Answer answer = door(VOID_SCOPE, CaptureHold.Resolution.VOID, key, intent, reasonCode);
        if (answer.outcome() == HeldOutcome.DONE) {
            // The release itself, through the one void command (its conditional converges a racing finisher).
            commands.release(intent, reasonCode);
        }
        return answer;
    }

    private Answer door(
            String scope,
            CaptureHold.Resolution resolution,
            String key,
            PaymentIntentId intent,
            String reasonCode) {
        Objects.requireNonNull(intent, "intent must not be null");
        requireDoor(key, reasonCode);
        Actor actor = SecurityContext.require();
        Correlation correlation = PaymentCreation.resolvedCorrelation();
        IdempotencyKey claim = new IdempotencyKey(scope, key);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256((scope + "|" + intent.value() + "|" + reasonCode)
                        .getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome executed =
                transactions.inTransaction(
                        uow ->
                                executor.execute(
                                        uow,
                                        claim,
                                        fingerprint,
                                        claimed ->
                                                CommandResult.succeeded(
                                                        stored(decide(claimed, resolution, key, intent,
                                                                reasonCode, actor, correlation)))));
        return parsed(executed.response(), executed.replayed());
    }

    /** Under the key's claim: the hold row locked, then the one conditional resolution. */
    private Answer decide(
            Connection uow,
            CaptureHold.Resolution resolution,
            String key,
            PaymentIntentId intent,
            String reasonCode,
            Actor actor,
            Correlation correlation) {
        Optional<CaptureHold> found = holds.lock(uow, intent);
        if (found.isEmpty()) {
            return new Answer(HeldOutcome.REFUSED, Optional.empty(), false);
        }
        CaptureHold hold = found.get();
        if (!hold.open()) {
            return new Answer(HeldOutcome.ALREADY, hold.resolution(), false);
        }
        PaymentAttempt attempt =
                attempts.findById(uow, hold.attemptId())
                        .orElseThrow(() -> new IllegalStateException("a capture hold's attempt has a row"));
        switch (attempt.status()) {
            case AUTHORIZED -> {
                // the decision may be taken
            }
            case AUTH_DISPATCHED, AUTH_UNKNOWN ->
                    // The issuer has not answered yet: nothing to capture or release - thrown, so the claim rolls
                    // back and the caller re-drives the same key once the authorisation stands.
                    throw new IllegalPaymentAttemptTransitionException(
                            attempt.id(),
                            attempt.status(),
                            resolution == CaptureHold.Resolution.CAPTURE
                                    ? PaymentAttemptStatus.CAPTURE_DISPATCHED
                                    : PaymentAttemptStatus.VOID_DISPATCHED);
            default -> {
                // Declined, or released by the customer's own cancellation: the authorisation no longer stands, and
                // the hold is left for the expiry leg to close with nothing to void.
                return new Answer(HeldOutcome.ALREADY, Optional.empty(), false);
            }
        }
        boolean resolved =
                resolution == CaptureHold.Resolution.CAPTURE
                        ? holds.resolveCapture(uow, intent, key)
                        : holds.resolveVoid(uow, intent, key);
        if (!resolved) {
            // Under the row's lock an open hold resolves unless the capture arrived past held_until on the database
            // clock: the expiry is the policy's outcome, never a late capture - nothing written.
            return new Answer(HeldOutcome.REFUSED, Optional.empty(), false);
        }
        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        Instant.now(clock),
                        resolution == CaptureHold.Resolution.CAPTURE
                                ? PaymentsAuditAction.HELD_PAYMENT_CAPTURED
                                : PaymentsAuditAction.HELD_PAYMENT_VOIDED,
                        PaymentCreation.TARGET_TYPE,
                        intent.value().toString(),
                        Optional.of(reasonCode),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of("intent=" + intent + ", attempt=" + hold.attemptId() + ", key=" + key)));
        return new Answer(HeldOutcome.DONE, Optional.of(resolution), false);
    }

    private PaymentAttemptId attemptOf(PaymentIntentId intent) {
        return transactions.inTransaction(uow -> holds.findForIntent(uow, intent))
                .orElseThrow(() -> new IllegalStateException("a resolved capture hold has a row"))
                .attemptId();
    }

    // ----------------------------------------------------------------- the expiry leg

    /**
     * One tick of the deadline (the class javadoc): at most {@code batchSize} expiries, one per transaction, then the
     * finisher pass over at most {@code batchSize} holds whose void is still to dispatch. The platform's act - an
     * enumerated {@code enterSystem()} site: a deadline has no person - each row under its own correlation; a failing
     * row is passed over for this tick and the queue continues (the sweeper's anti-stall posture).
     */
    @SuppressWarnings("try") // The Scopes are used for their close side effects.
    public ExpiryTally expireHolds() {
        int expired = 0;
        int voided = 0;
        int failed = 0;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            List<PaymentIntentId> passedOver = new ArrayList<>();
            for (int i = 0; i < batchSize; i++) {
                List<PaymentIntentId> claiming = new ArrayList<>(1);
                Optional<CaptureHold> claimed;
                try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(ids)))) {
                    claimed = transactions.inTransaction(uow -> expireOne(uow, passedOver, claiming));
                } catch (RuntimeException oneRowsFailure) {
                    failed++;
                    log.warn("Expiring a held payment's capture hold failed with {}; the next tick retries it",
                            oneRowsFailure.getClass().getSimpleName());
                    if (claiming.isEmpty()) {
                        break; // the claim itself failed: nothing to pass over, nothing more this tick
                    }
                    // The row rolled back with its failure: passed over for the rest of this tick.
                    passedOver.addAll(claiming);
                    continue;
                }
                if (claimed.isEmpty()) {
                    break;
                }
                expired++;
            }

            // THE FINISHER: every hold resolved VOID or EXPIRED whose authorisation still stands - this tick's
            // expiries, and a void door's crash between its two commits. The void converges if another got there.
            List<CaptureHold> toVoid = transactions.inTransaction(uow -> holds.voidsToFinish(uow, batchSize));
            for (CaptureHold hold : toVoid) {
                try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(ids)))) {
                    boolean dispatched =
                            commands.release(
                                    hold.intentId(),
                                    hold.resolution()
                                            .filter(CaptureHold.Resolution.EXPIRED::equals)
                                            .map(r -> EXPIRY_REASON)
                                            .orElse(VOIDED_REASON));
                    if (dispatched) {
                        voided++;
                    }
                } catch (RuntimeException oneRowsFailure) {
                    failed++;
                    log.warn("Voiding held payment attempt {} failed with {}; the next tick retries it",
                            hold.attemptId(), oneRowsFailure.getClass().getSimpleName());
                }
            }
        }
        return new ExpiryTally(expired, voided, failed);
    }

    /** One expiry, its own transaction: claim, resolve {@code EXPIRED}, audit as the platform. */
    private Optional<CaptureHold> expireOne(
            Connection uow, List<PaymentIntentId> passedOver, List<PaymentIntentId> claiming) {
        Optional<CaptureHold> claimed = holds.claimExpired(uow, passedOver);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        CaptureHold hold = claimed.get();
        claiming.add(hold.intentId());
        if (!holds.expire(uow, hold.intentId())) {
            throw new IllegalStateException(
                    "the claimed capture hold of " + hold.intentId() + " did not expire under its own lock");
        }
        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        PaymentsAuditAction.HELD_PAYMENT_EXPIRED,
                        PaymentCreation.TARGET_TYPE,
                        hold.intentId().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        PaymentCreation.resolvedCorrelation().correlationId(),
                        Optional.of("intent=" + hold.intentId() + ", attempt=" + hold.attemptId())));
        return claimed;
    }

    // ----------------------------------------------------------------- the rules and the record

    /** The doors' preconditions: a bounded key, a reason code, and a caller who may act on a held payment. */
    private static void requireDoor(String key, String reasonCode) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        if (key.isBlank() || key.length() > CaptureHold.MAX_RESOLUTION_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "a held payment's door key is 1-" + CaptureHold.MAX_RESOLUTION_KEY_LENGTH + " characters");
        }
        if (!REASON_CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException(
                    "a held payment's door takes a reason code, never a note (INV-RSK-08)");
        }
        ActorType acting = SecurityContext.require().type();
        if (acting != ActorType.SYSTEM && acting != ActorType.EMPLOYEE) {
            throw new IllegalStateException(
                    "a held payment is captured or voided only by the platform or an operator, through risk's"
                            + " four-eyes paths - never by " + acting);
        }
    }

    /** A door's answer, stored for the replay: {@code HELD_PAYMENT|outcome|resolution}. */
    private static StoredResponse stored(Answer answer) {
        String text = "HELD_PAYMENT|" + answer.outcome().name() + "|"
                + answer.resolution().map(Enum::name).orElse("-");
        return StoredResponse.of(text.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static Answer parsed(StoredResponse response, boolean replayed) {
        String[] parts = new String(response.body(), StandardCharsets.UTF_8).split("\\|");
        if (parts.length != 3 || !"HELD_PAYMENT".equals(parts[0])) {
            throw new IllegalStateException("a held payment's door claim held a response in no known form");
        }
        return new Answer(
                HeldOutcome.valueOf(parts[1]),
                "-".equals(parts[2]) ? Optional.empty() : Optional.of(CaptureHold.Resolution.valueOf(parts[2])),
                replayed);
    }
}
