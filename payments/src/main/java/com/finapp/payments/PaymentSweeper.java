package com.finapp.payments;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Reconciliation by query (`P5-TSK-014`, ADR-0046 §4): every instance polls for
 * {@code *_DISPATCHED} rows past their bound and {@code *_UNKNOWN} rows past their patience,
 * asks the provider about <strong>our</strong> reference — the one stored before anything was
 * sent ({@code INV-PAY-04}), which is exactly what makes the question askable — and applies
 * the answer through the standard outcome transactions ({@link PaymentOutcomes}, the one code
 * path every resolver shares).
 *
 * <h2>No lease, no leader, by design</h2>
 *
 * <p>ADR-0024's bar for a bare schedule is <em>idempotent per period or an explicit lease</em>;
 * the relay took the lease half, this class is the other half made real: the query is
 * read-only and idempotent at the provider, and every write is a conditional transition whose
 * losers converge — N sweepers racing each other, the webhook and a client's retry are the
 * same harmless race `P5-TSK-013` counted. The register row is
 * {@code DISTRIBUTED_EXECUTION.md} §3, beside the relay's, each naming its own justification.
 *
 * <h2>The bounds are the safety margin, not the correctness</h2>
 *
 * <p>A dispatch younger than {@code dispatchedAge} is probably mid-call and is left alone —
 * querying it early would be harmless (the conditional arbitrates) but would turn ordinary
 * latency into {@code *_UNKNOWN} churn. Bounds are judged by the injected server clock (the
 * ADR-0014 discipline; minutes-scale bounds make NTP skew noise — the {@code WebhookSignature}
 * reasoning).
 *
 * <h2>What each verdict may do</h2>
 *
 * <p>{@code APPROVED}/{@code DECLINED} apply the stage's outcome (an approved capture posts,
 * once — the {@code payment-capture:} claim behind the conditional). {@code UNRECOGNISED} —
 * the provider answering <em>in so many words</em> that it never saw the reference — is the
 * licence to resolve to {@code FAILED(NEVER_RECEIVED)}; a 404 never earns it
 * ({@link QueryAnswer}'s fold). {@code INDETERMINATE} moves a {@code *_DISPATCHED} into its
 * honest {@code *_UNKNOWN} and changes nothing else: the next tick asks again, and ambiguity
 * never becomes a failure ({@code INV-LIFE-03}).
 *
 * <h2>One bad row must not stall the queue</h2>
 *
 * <p>Each candidate resolves in its own transaction; a failing resolution is logged
 * (identifiers and failure class only, {@code INV-AUD-02}) and the sweep continues — the
 * anti-stall posture, because the rows behind a poisoned one are other customers' money.
 */
@Slf4j
public final class PaymentSweeper {

    private final TransactionRunner transactions;
    private final PaymentAttemptStore<Connection> attempts;
    private final PaymentIntentStore<Connection> intents;
    private final ProviderEvidenceStore<Connection> evidence;
    private final PaymentProvider provider;
    private final PaymentOutcomes outcomes;
    private final IdGenerator ids;
    private final Clock clock;
    private final Duration dispatchedAge;
    private final Duration unknownAge;
    private final int batchSize;

    public PaymentSweeper(
            TransactionRunner transactions,
            PaymentAttemptStore<Connection> attempts,
            PaymentIntentStore<Connection> intents,
            ProviderEvidenceStore<Connection> evidence,
            PaymentProvider provider,
            PaymentOutcomes outcomes,
            IdGenerator ids,
            Clock clock,
            Duration dispatchedAge,
            Duration unknownAge,
            int batchSize) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.attempts = Objects.requireNonNull(attempts, "attempts must not be null");
        this.intents = Objects.requireNonNull(intents, "intents must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.dispatchedAge = requirePositive(dispatchedAge, "dispatchedAge");
        this.unknownAge = requirePositive(unknownAge, "unknownAge");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
        }
        this.batchSize = batchSize;
    }

    private static Duration requirePositive(Duration bound, String name) {
        Objects.requireNonNull(bound, name + " must not be null");
        if (bound.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative: " + bound);
        }
        return bound;
    }

    /**
     * One tick's tally — what a log line or a future meter reads, <strong>never the count of
     * record</strong>: {@code applied} means this tick submitted an outcome application, whose
     * conditional may still have converged on another resolver's win inside the component —
     * the singular effect is counted in the tables (the journal entry, the transition row),
     * which is where the accept counts it. {@code skipped} is a candidate whose row had moved
     * (or was already at its honest {@code *_UNKNOWN}) before this tick could ask.
     *
     * <p>{@code actingJudgements} is the exception to the sentence above and the reason it
     * can be: each entry is a state some row's <strong>own conditional transition</strong>
     * committed on this tick ({@code P5-TSK-017}), so a converged loser contributes nothing
     * — which is what lets the schedule count throughput at the door without counting one
     * judgement once per resolver that raced for it. Statuses only; no identifier leaves.
     */
    public record SweepResult(
            int candidates,
            int applied,
            int skipped,
            int failedRows,
            List<PaymentAttemptStatus> actingJudgements) {

        public SweepResult {
            actingJudgements = List.copyOf(actingJudgements);
        }
    }

    /**
     * One sweep: bounded candidates, one provider query and one outcome transaction per row.
     *
     * <p>The whole tick runs as the platform — the module's fourth enumerated
     * {@code enterSystem()} site: a scheduled resolution has no person at all, the cleanest
     * case of the `P5-TSK-009` attribution reasoning — and each row under its own fresh
     * correlation (scheduled work carries no request scope).
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect.
    public SweepResult sweep() {
        Instant now = Instant.now(clock);
        List<PaymentAttempt> candidates =
                transactions.inTransaction(
                        uow ->
                                attempts.findSweepable(
                                        uow,
                                        now.minus(dispatchedAge),
                                        now.minus(unknownAge),
                                        batchSize));
        int applied = 0;
        int skipped = 0;
        int failedRows = 0;
        List<PaymentAttemptStatus> acting = new ArrayList<>();
        for (PaymentAttempt candidate : candidates) {
            try (CorrelationContext.Scope flow =
                            CorrelationContext.enter(
                                    Correlation.startingWith(CorrelationId.generate(ids)));
                    SecurityContext.Scope actor = SecurityContext.enterSystem()) {
                Resolution resolution = resolve(candidate);
                if (resolution.submitted()) {
                    applied++;
                } else {
                    skipped++;
                }
                resolution.acting().ifPresent(acting::add);
            } catch (RuntimeException oneRowsFailure) {
                // The anti-stall posture: the rows behind this one are other customers'
                // money. Identifiers and class only - never provider bytes (INV-AUD-02).
                failedRows++;
                log.warn(
                        "Sweeping attempt {} failed with {}; the sweep continues and the next"
                                + " tick will retry this row",
                        candidate.id(),
                        oneRowsFailure.getClass().getSimpleName());
            }
        }
        return new SweepResult(candidates.size(), applied, skipped, failedRows, acting);
    }

    /**
     * What one row's sweep did: whether an application was submitted, and — when this call's
     * own conditional fired — the state it committed (`P5-TSK-017`'s counting seam).
     */
    private record Resolution(boolean submitted, Optional<PaymentAttemptStatus> acting) {

        static Resolution skipped() {
            return new Resolution(false, Optional.empty());
        }
    }

    /** Submitted or skipped, and the judgement this call itself committed, if any. */
    private Resolution resolve(PaymentAttempt candidate) {
        // Which operation the state is stranded in decides which reference we ask about.
        boolean authStage =
                candidate.status() == PaymentAttemptStatus.AUTH_DISPATCHED
                        || candidate.status() == PaymentAttemptStatus.AUTH_UNKNOWN;
        ProviderIdempotencyReference reference =
                authStage ? candidate.authorizationReference() : candidate.captureReference();

        // The provider query - HOLDING NO DATABASE CONNECTION (the P1-TSK-026 discipline,
        // the dispatch-before-call shape inverted into read-before-ask).
        QueryAnswer answer = provider.query(reference);

        Correlation correlation = PaymentCreation.resolvedCorrelation();
        return transactions.inTransaction(
                uow -> {
                    // Re-read: another resolver may have won since the candidate list. The
                    // conditional transitions arbitrate anyway; this read keeps the FROM
                    // state honest and skips the settled cheaply.
                    PaymentAttempt current =
                            attempts.findById(uow, candidate.id()).orElse(null);
                    if (current == null || current.status() != candidate.status()) {
                        return Resolution.skipped();
                    }
                    PaymentIntent intent =
                            intents.findById(uow, current.intentId())
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "an attempt row's intent exists:"
                                                                    + " V003's foreign key"
                                                                    + " holds it"));
                    Resolution changed =
                            switch (answer.verdict()) {
                                case APPROVED, DECLINED ->
                                        applyStage(
                                                uow, authStage, current, intent,
                                                answer, correlation);
                                case UNRECOGNISED -> {
                                    PaymentOutcomes.Applied applied =
                                            outcomes.applyUnrecognised(
                                                    uow,
                                                    intent.id(),
                                                    current.id(),
                                                    current.status(),
                                                    correlation);
                                    yield submitted(applied);
                                }
                                case INDETERMINATE -> {
                                    // Ambiguity is never a failure: a DISPATCHED moves into
                                    // its honest *_UNKNOWN; an *_UNKNOWN stays and the next
                                    // tick asks again (INV-LIFE-03).
                                    yield markUnknown(uow, authStage, current, intent,
                                            correlation);
                                }
                            };
                    // Whatever the mapping said, what arrived is retained (INV-HIST-02) -
                    // AFTER the outcome's row lock (the P5-TSK-013 lock-order rule).
                    answer.evidence()
                            .ifPresent(
                                    bytes ->
                                            evidence.append(
                                                    uow,
                                                    Optional.of(current.id()),
                                                    Optional.empty(),
                                                    EvidenceKind.QUERY_RESULT,
                                                    bytes,
                                                    Instant.now(clock)));
                    return changed;
                });
    }

    /** The acting judgement, when this call's own conditional made it (`P5-TSK-017`). */
    private static Resolution submitted(PaymentOutcomes.Applied applied) {
        return new Resolution(
                true, applied.acting() ? Optional.of(applied.attempt()) : Optional.empty());
    }

    private Resolution applyStage(
            Connection uow,
            boolean authStage,
            PaymentAttempt current,
            PaymentIntent intent,
            QueryAnswer answer,
            Correlation correlation) {
        ProviderAnswer.Verdict verdict =
                answer.verdict() == QueryAnswer.Verdict.APPROVED
                        ? ProviderAnswer.Verdict.APPROVED
                        : ProviderAnswer.Verdict.DECLINED;
        if (authStage) {
            return submitted(
                    outcomes.applyAuthorization(
                            uow,
                            intent.id(),
                            current.id(),
                            current.status(),
                            verdict,
                            answer.providerReference(),
                            // The issuer approved the dispatched ask: the intent's amount,
                            // the same promise every other resolver carries.
                            intent.amount(),
                            correlation));
        }
        return submitted(
                outcomes.applyCapture(
                        uow,
                        intent.id(),
                        current.id(),
                        current.status(),
                        verdict,
                        answer.providerReference(),
                        intent.walletAccount(),
                        // The capture is the authorized promise, in full (one attempt, no
                        // partial capture until its producer exists - ADR-0045 §4).
                        current.authorizedAmount(),
                        correlation));
    }

    private Resolution markUnknown(
            Connection uow,
            boolean authStage,
            PaymentAttempt current,
            PaymentIntent intent,
            Correlation correlation) {
        boolean dispatched =
                current.status() == PaymentAttemptStatus.AUTH_DISPATCHED
                        || current.status() == PaymentAttemptStatus.CAPTURE_DISPATCHED;
        if (!dispatched) {
            return Resolution.skipped();
        }
        if (authStage) {
            return submitted(
                    outcomes.applyAuthorization(
                            uow,
                            intent.id(),
                            current.id(),
                            current.status(),
                            ProviderAnswer.Verdict.INDETERMINATE,
                            Optional.empty(),
                            intent.amount(),
                            correlation));
        }
        return submitted(
                outcomes.applyCapture(
                        uow,
                        intent.id(),
                        current.id(),
                        current.status(),
                        ProviderAnswer.Verdict.INDETERMINATE,
                        Optional.empty(),
                        intent.walletAccount(),
                        current.authorizedAmount(),
                        correlation));
    }
}
