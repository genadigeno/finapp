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
 *
 * <h2>Two legs added by the Phase 6 → 7 transition</h2>
 *
 * <p><strong>Stranded authorizations.</strong> Every Phase 5 and 6 payment captures what it
 * authorizes, and only the HTTP surface chained the capture — so an authorization this sweep or
 * a webhook resolved, or one whose instance crashed between its commit and the chain, rested in
 * {@code AUTHORIZED} for good unless the customer retried. {@code AUTHORIZED} past
 * {@code dispatchedAge} is chained to {@link PaymentCapture}, which converges: N sweepers and a
 * customer's retry dispatch one capture between them, behind its own conditional.
 *
 * <p><strong>Refunds.</strong> A refund's {@code UNKNOWN} was resolved by webhook alone, and a
 * refund whose instance crashed mid-dispatch by nothing unless the operator retried —
 * `PHASE_5_PLAN`'s recorded deferral, carried through Phase 6 and paid here. The refund leg
 * asks the provider about our reference and applies the answer through the one shared
 * component, like the attempt legs; and where the provider answers that it never saw the
 * reference, it <strong>re-drives the send</strong> under a freshly committed permit
 * ({@code V009}) — idempotent at the provider by our reference ({@code INV-PAY-04}) — rather
 * than concluding {@code FAILED}. A refund's failure is concluded only on the provider's own
 * {@code DECLINED}: releasing a refund's hold on anything less is the takeover hazard ADR-0057
 * named, and a refused connection on a re-drive proves nothing.
 */
@Slf4j
public final class PaymentSweeper {

    private final TransactionRunner transactions;
    private final PaymentAttemptStore<Connection> attempts;
    private final PaymentIntentStore<Connection> intents;
    private final RefundStore<Connection> refunds;
    private final ProviderEvidenceStore<Connection> evidence;
    private final PaymentProvider provider;
    private final PaymentOutcomes outcomes;
    private final PaymentCapture capture;
    private final PaymentVoid voids;
    private final IdGenerator ids;
    private final Clock clock;
    private final Duration dispatchedAge;
    private final Duration unknownAge;
    private final int batchSize;

    public PaymentSweeper(
            TransactionRunner transactions,
            PaymentAttemptStore<Connection> attempts,
            PaymentIntentStore<Connection> intents,
            RefundStore<Connection> refunds,
            ProviderEvidenceStore<Connection> evidence,
            PaymentProvider provider,
            PaymentOutcomes outcomes,
            PaymentCapture capture,
            PaymentVoid voids,
            IdGenerator ids,
            Clock clock,
            Duration dispatchedAge,
            Duration unknownAge,
            int batchSize) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.attempts = Objects.requireNonNull(attempts, "attempts must not be null");
        this.intents = Objects.requireNonNull(intents, "intents must not be null");
        this.refunds = Objects.requireNonNull(refunds, "refunds must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
        this.capture = Objects.requireNonNull(capture, "capture must not be null");
        this.voids = Objects.requireNonNull(voids, "voids must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.dispatchedAge = requirePositive(dispatchedAge, "dispatchedAge");
        this.unknownAge = requirePositive(unknownAge, "unknownAge");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /**
     * Refuses a zero bound as well as a negative one — the name's own promise, kept since the
     * Phase 6 → 7 transition, which found this guard accepting {@code PT0S} a day after the Phase
     * 6 review had closed the same defect in the payout's sweep (`P6-DOC-001`,
     * {@code MerchantPayoutResolution#positive}).
     *
     * <p>Both bounds license {@code FAILED(NEVER_RECEIVED)}: an {@code UNRECOGNISED} answer
     * about a {@code *_DISPATCHED} row past {@code dispatchedAge}, or about a {@code *_UNKNOWN}
     * row past {@code unknownAge}. At zero, the sweep can ask about a request still on its way —
     * a dispatch mid-call, or one our client abandoned at its timeout but the network had not —
     * hear "never saw it", fail the attempt, and then watch the provider perform it: an
     * authorization held against the customer for nothing, or a capture that took the
     * customer's money while the books say {@code FAILED} and nothing is credited. A positive
     * bound is necessary, not sufficient: it must still exceed the client timeout and the
     * clock skew, which is the configuration's to get right.
     */
    private static Duration requirePositive(Duration bound, String name) {
        Objects.requireNonNull(bound, name + " must not be null");
        if (bound.isNegative() || bound.isZero()) {
            throw new IllegalArgumentException(name + " must be positive: " + bound);
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
     * {@code refundJudgements} is the same for the refund leg, in the refund machine's own
     * vocabulary (the Phase 6 → 7 transition).
     */
    public record SweepResult(
            int candidates,
            int applied,
            int skipped,
            int failedRows,
            List<PaymentAttemptStatus> actingJudgements,
            List<RefundStatus> refundJudgements) {

        public SweepResult {
            actingJudgements = List.copyOf(actingJudgements);
            refundJudgements = List.copyOf(refundJudgements);
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
                if (resolution.voidToFinish().isPresent()) {
                    // The declined-capture redirect this resolution committed: send the
                    // void now, holding no candidate-list state - its own Tx1-call-Tx2.
                    PaymentVoid.VoidResult sent =
                            voids.completeDispatched(resolution.voidToFinish().get());
                    if (sent.acting()) {
                        acting.add(sent.attempt());
                    }
                }
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

        // THE STRANDED CHAIN: an authorization nothing captured. The capture command runs its
        // own Tx1 / provider call / Tx2 and converges, so this holds no connection across it.
        List<PaymentAttempt> stranded =
                transactions.inTransaction(
                        uow ->
                                attempts.findStrandedAuthorizations(
                                        uow, now.minus(dispatchedAge), batchSize));
        for (PaymentAttempt authorized : stranded) {
            try (CorrelationContext.Scope flow =
                            CorrelationContext.enter(
                                    Correlation.startingWith(CorrelationId.generate(ids)));
                    SecurityContext.Scope actor = SecurityContext.enterSystem()) {
                PaymentCapture.CaptureResult captured = capture.capture(authorized.id());
                if (captured.converged()) {
                    skipped++;
                } else {
                    applied++;
                }
                if (captured.acting()) {
                    acting.add(captured.attempt());
                }
            } catch (RuntimeException oneRowsFailure) {
                failedRows++;
                log.warn(
                        "Chaining the capture of stranded attempt {} failed with {}; the next"
                                + " tick will retry this row",
                        authorized.id(),
                        oneRowsFailure.getClass().getSimpleName());
            }
        }

        // THE REFUND LEG.
        List<Refund> refundCandidates =
                transactions.inTransaction(
                        uow ->
                                refunds.findSweepable(
                                        uow,
                                        now.minus(dispatchedAge),
                                        now.minus(unknownAge),
                                        batchSize));
        List<RefundStatus> refundActing = new ArrayList<>();
        for (Refund candidate : refundCandidates) {
            try (CorrelationContext.Scope flow =
                            CorrelationContext.enter(
                                    Correlation.startingWith(CorrelationId.generate(ids)));
                    SecurityContext.Scope actor = SecurityContext.enterSystem()) {
                RefundResolution resolution = resolveRefund(candidate);
                if (resolution.submitted()) {
                    applied++;
                } else {
                    skipped++;
                }
                resolution.acting().ifPresent(refundActing::add);
            } catch (RuntimeException oneRowsFailure) {
                failedRows++;
                log.warn(
                        "Sweeping refund {} failed with {}; the sweep continues and the next"
                                + " tick will retry this row",
                        candidate.id(),
                        oneRowsFailure.getClass().getSimpleName());
            }
        }
        return new SweepResult(
                candidates.size() + stranded.size() + refundCandidates.size(),
                applied,
                skipped,
                failedRows,
                acting,
                refundActing);
    }

    /** The refund leg's per-row answer, the attempt leg's {@link Resolution} in refund words. */
    private record RefundResolution(boolean submitted, Optional<RefundStatus> acting) {

        static RefundResolution skipped() {
            return new RefundResolution(false, Optional.empty());
        }
    }

    /**
     * One refund: ask about our reference, re-drive it under a new permit if the provider never
     * saw it, and apply what is known from the LOCKED row — the one shared component
     * ({@link PaymentOutcomes#applyRefund}), so completion releases-and-posts once, whoever wins.
     */
    private RefundResolution resolveRefund(Refund candidate) {
        // The query - HOLDING NO DATABASE CONNECTION (the P1-TSK-026 discipline).
        QueryAnswer answer = provider.query(candidate.providerIdempotencyReference());

        Optional<ProviderAnswer> resent = Optional.empty();
        if (answer.verdict() == QueryAnswer.Verdict.UNRECOGNISED) {
            // The provider says it never saw our reference. Concluding FAILED would release a
            // hold a racing re-send could still spend (ADR-0057's hazard); re-driving the send
            // under a committed permit is the answer that cannot give money away - the provider
            // performs our reference once, however many resolvers send it (INV-PAY-04).
            Optional<ProviderReference> capturedAs =
                    transactions.inTransaction(
                            uow ->
                                    refunds.renewSendPermit(
                                                    uow, candidate.id(), Instant.now(clock))
                                            .map(
                                                    permit ->
                                                            attempts.findById(
                                                                            uow,
                                                                            candidate.attemptId())
                                                                    .orElseThrow()
                                                                    .captureProviderReference()));
            if (capturedAs.isEmpty()) {
                // Resolved since the candidate list: nothing may be sent.
                return RefundResolution.skipped();
            }
            resent =
                    Optional.of(
                            provider.refund(
                                    new PaymentProvider.RefundRequest(
                                            candidate.providerIdempotencyReference(),
                                            capturedAs.get(),
                                            candidate.amount())));
        }

        Correlation correlation = PaymentCreation.resolvedCorrelation();
        Optional<ProviderAnswer> sent = resent;
        return transactions.inTransaction(
                uow -> {
                    RefundStore.LockedRefund locked =
                            refunds.lockForOutcome(uow, candidate.id()).orElse(null);
                    if (locked == null
                            || (locked.refund().status() != RefundStatus.DISPATCHED
                                    && locked.refund().status() != RefundStatus.UNKNOWN)) {
                        return RefundResolution.skipped();
                    }
                    Refund current = locked.refund();
                    ProviderAnswer.Verdict verdict;
                    Optional<ProviderReference> reference;
                    if (sent.isPresent()) {
                        // A re-drive is never the first send: its refused connection proves
                        // nothing about the sends before it (ADR-0057 section 3).
                        verdict =
                                sent.get().verdict() == ProviderAnswer.Verdict.NOTHING_SENT
                                        ? ProviderAnswer.Verdict.INDETERMINATE
                                        : sent.get().verdict();
                        reference = sent.get().providerReference();
                    } else {
                        verdict =
                                switch (answer.verdict()) {
                                    case APPROVED -> ProviderAnswer.Verdict.APPROVED;
                                    case DECLINED -> ProviderAnswer.Verdict.DECLINED;
                                    default -> ProviderAnswer.Verdict.INDETERMINATE;
                                };
                        reference = answer.providerReference();
                    }
                    RefundResolution resolution;
                    if (verdict == ProviderAnswer.Verdict.INDETERMINATE
                            && current.status() == RefundStatus.UNKNOWN) {
                        // Ambiguity is never a failure, and an UNKNOWN stays UNKNOWN: nothing to
                        // apply, and no outcome record per tick for an answer that changed
                        // nothing (the attempt leg's markUnknown stance).
                        resolution = RefundResolution.skipped();
                    } else {
                        PaymentAttempt attempt =
                                attempts.findById(uow, current.attemptId()).orElseThrow();
                        PaymentIntent intent =
                                intents.findById(uow, attempt.intentId())
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "an attempt row's intent exists:"
                                                                        + " V003's foreign key"
                                                                        + " holds it"));
                        PaymentOutcomes.RefundApplied applied =
                                outcomes.applyRefund(
                                        uow,
                                        intent.id(),
                                        current,
                                        current.status(),
                                        verdict,
                                        reference,
                                        intent.creditAccount(),
                                        correlation);
                        resolution =
                                new RefundResolution(
                                        true,
                                        applied.acting()
                                                ? Optional.of(applied.status())
                                                : Optional.empty());
                    }
                    // Whatever the mapping said, what arrived is retained (INV-HIST-02) -
                    // AFTER the outcome's row lock (the P5-TSK-013 lock-order rule).
                    Instant at = Instant.now(clock);
                    answer.evidence()
                            .ifPresent(
                                    bytes ->
                                            evidence.append(
                                                    uow,
                                                    Optional.empty(),
                                                    Optional.of(current.id()),
                                                    EvidenceKind.QUERY_RESULT,
                                                    bytes,
                                                    at));
                    sent.flatMap(ProviderAnswer::evidence)
                            .ifPresent(
                                    bytes ->
                                            evidence.append(
                                                    uow,
                                                    Optional.empty(),
                                                    Optional.of(current.id()),
                                                    EvidenceKind.RESPONSE,
                                                    bytes,
                                                    at));
                    return resolution;
                });
    }

    /**
     * What one row's sweep did: whether an application was submitted, and — when this call's
     * own conditional fired — the state it committed (`P5-TSK-017`'s counting seam).
     */
    private record Resolution(
            boolean submitted,
            Optional<PaymentAttemptStatus> acting,
            Optional<PaymentAttemptId> voidToFinish) {

        Resolution(boolean submitted, Optional<PaymentAttemptStatus> acting) {
            this(submitted, acting, Optional.empty());
        }

        static Resolution skipped() {
            return new Resolution(false, Optional.empty());
        }
    }

    /** Submitted or skipped, and the judgement this call itself committed, if any. */
    private Resolution resolve(PaymentAttempt candidate) {
        // THE VOID'S SEND LEG (P7-TSK-004): a VOID_DISPATCHED past the bound is RE-SENT,
        // not queried - idempotent at the provider by the stored reference, so any
        // instance may finish it. This is what guarantees a redirect committed by a
        // port-less resolver (the webhook door) actually releases the authorization, and
        // it is deliberately permit-free: releasing a released promise converges, the
        // recorded asymmetry with the refund's V009.
        if (candidate.status() == PaymentAttemptStatus.VOID_DISPATCHED) {
            PaymentVoid.VoidResult sent = voids.completeDispatched(candidate.id());
            return new Resolution(
                    !sent.converged(),
                    sent.acting() ? Optional.of(sent.attempt()) : Optional.empty());
        }

        // Which operation the state is stranded in decides which reference we ask about.
        boolean authStage =
                candidate.status() == PaymentAttemptStatus.AUTH_DISPATCHED
                        || candidate.status() == PaymentAttemptStatus.AUTH_UNKNOWN;
        boolean voidStage = candidate.status() == PaymentAttemptStatus.VOID_UNKNOWN;
        ProviderIdempotencyReference reference =
                authStage
                        ? candidate.authorizationReference()
                        : voidStage
                                ? candidate.voidReference()
                                : candidate.captureReference();

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

    /** The redirect's follow-up rides out of the transaction with the resolution. */
    private static Resolution submitted(
            PaymentOutcomes.Applied applied, PaymentAttemptId attemptId) {
        return new Resolution(
                true,
                applied.acting() ? Optional.of(applied.attempt()) : Optional.empty(),
                applied.voidPending() ? Optional.of(attemptId) : Optional.empty());
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
        if (current.status() == PaymentAttemptStatus.VOID_UNKNOWN) {
            return submitted(
                    outcomes.applyVoid(
                            uow,
                            intent.id(),
                            current.id(),
                            current.status(),
                            verdict,
                            answer.providerReference(),
                            correlation));
        }
        // A capture stage's DECLINED may redirect into the void (P7-TSK-004): the follow-up
        // send rides out with the resolution, performed after this transaction commits.
        return submitted(
                outcomes.applyCapture(
                        uow,
                        intent.id(),
                        current.id(),
                        current.status(),
                        verdict,
                        answer.providerReference(),
                        intent.creditAccount(),
                        // The capture is the authorized promise, in full (one attempt, no
                        // partial capture until its producer exists - ADR-0045 §4).
                        current.authorizedAmount(),
                        correlation),
                current.id());
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
                        intent.creditAccount(),
                        current.authorizedAmount(),
                        correlation));
    }
}
