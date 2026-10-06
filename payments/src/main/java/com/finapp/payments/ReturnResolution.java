package com.finapp.payments;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.persistence.DatabaseTime;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * The return-payment resolution sweep (`P7-TSK-010`, ADR-0059 §3) — the card sweeper's refund
 * leg, asked of the push rail. The partition is the point: {@code findSweepable} feeds the
 * card leg and now excludes push-model refunds, because a resolver asking the CARD provider
 * about a return's reference would hear {@code UNRECOGNISED} — truthfully, from the wrong
 * counterparty — and re-drive a card refund against capture facts the row does not carry.
 * Each rail's refunds are resolved against that rail's own wire, and nothing else changed:
 * the judgement still lands through the one shared {@link PaymentOutcomes#applyRefund}, on
 * the LOCKED row, so completion releases-and-posts once, whoever wins.
 *
 * <p>Per row: inquire by OUR reference (an idempotent read, permit-free — the card leg's
 * recorded stance). {@code UNRECOGNISED} means the scheme never saw the return: concluding
 * {@code FAILED} would release a hold a racing re-send could still spend (ADR-0057's hazard),
 * so the row's send permit is stamped forward CONDITIONALLY and the return re-driven with the
 * SAME reference — the scheme deduplicates on it (`INV-PAY-04`), so however many resolvers
 * send it, one return executes. A re-drive's refused connection proves nothing about the
 * sends before it and stays {@code INDETERMINATE} (ADR-0057 §3). Registered leaderless in
 * {@code DISTRIBUTED_EXECUTION.md} §3.
 */
@Slf4j
public final class ReturnResolution {

    /** The sweep's pacing and batch, refused mis-configured at construction. */
    public record Config(Duration dispatchedAge, Duration unknownAge, int batchSize) {

        public Config {
            Objects.requireNonNull(dispatchedAge, "dispatchedAge must not be null");
            Objects.requireNonNull(unknownAge, "unknownAge must not be null");
            if (dispatchedAge.isZero() || dispatchedAge.isNegative()) {
                throw new IllegalArgumentException(
                        "dispatchedAge must be positive: a zero bound would re-contact the"
                                + " scheme about a send still in flight");
            }
            if (unknownAge.isZero() || unknownAge.isNegative()) {
                throw new IllegalArgumentException("unknownAge must be positive");
            }
            if (batchSize < 1) {
                throw new IllegalArgumentException("batchSize must be at least 1");
            }
        }
    }

    private final RefundStore<Connection> refunds;
    private final PaymentAttemptStore<Connection> attempts;
    private final PaymentIntentStore<Connection> intents;
    private final PaymentOutcomes outcomes;
    private final PushRail rail;
    private final ProviderEvidenceStore<Connection> evidence;
    private final Config config;
    private final IdGenerator ids;
    private final Clock clock;
    private final TransactionRunner transactions;

    public ReturnResolution(
            RefundStore<Connection> refunds,
            PaymentAttemptStore<Connection> attempts,
            PaymentIntentStore<Connection> intents,
            PaymentOutcomes outcomes,
            PushRail rail,
            ProviderEvidenceStore<Connection> evidence,
            Config config,
            IdGenerator ids,
            Clock clock,
            TransactionRunner transactions) {
        this.refunds = Objects.requireNonNull(refunds, "refunds must not be null");
        this.attempts = Objects.requireNonNull(attempts, "attempts must not be null");
        this.intents = Objects.requireNonNull(intents, "intents must not be null");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
        this.rail = Objects.requireNonNull(rail, "rail must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    }

    /** One tick's tally — telemetry, never the count of record ({@code PaymentSweeper}'s rule). */
    public record SweepResult(int candidates, int applied, int skipped) {}

    @SuppressWarnings("try") // The Scopes are used for their close side effects (the idiom).
    public SweepResult sweep() {
        Instant now = Instant.now(clock);
        List<Refund> candidates =
                transactions.inTransaction(
                        uow ->
                                refunds.findSweepableReturns(
                                        uow,
                                        // The permit's own clock, the database's (X-TSK-013).
                                        DatabaseTime.now(uow).minus(config.dispatchedAge()),
                                        now.minus(config.unknownAge()),
                                        config.batchSize()));
        int applied = 0;
        int skipped = 0;
        for (Refund candidate : candidates) {
            // Per-row containment: one candidate's failure never starves the rest.
            try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                    CorrelationContext.Scope scope =
                            CorrelationContext.enter(
                                    Correlation.startingWith(CorrelationId.generate(ids)))) {
                if (resolveOne(candidate)) {
                    applied++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException oneRowFailed) {
                // Identifier and failure class only - never scheme bytes (INV-AUD-02).
                log.warn(
                        "return sweep could not resolve refund {}: {}",
                        candidate.id(),
                        oneRowFailed.getClass().getSimpleName());
            }
        }
        return new SweepResult(candidates.size(), applied, skipped);
    }

    /** One row's wire call(s) and outcome transaction; true when its conditional acted. */
    private boolean resolveOne(Refund candidate) {
        EndToEndReference ourReference =
                new EndToEndReference(candidate.providerIdempotencyReference().value());

        // The inquiry - HOLDING NO DATABASE CONNECTION (the P1-TSK-026 discipline).
        PushInquiryAnswer answer = rail.inquireReturn(ourReference);

        Optional<PushAnswer> resent = Optional.empty();
        if (answer.verdict() == PushInquiryAnswer.Verdict.UNRECOGNISED) {
            // The scheme says it never saw our reference. Concluding FAILED would release a
            // hold a racing re-send could still spend (ADR-0057's hazard); re-driving under a
            // committed permit is the answer that cannot give money away - the scheme
            // performs our reference once, however many resolvers send it (INV-PAY-04).
            Optional<ProviderReference> original =
                    transactions.inTransaction(
                            uow ->
                                    refunds.renewSendPermit(uow, candidate.id())
                                            .map(
                                                    permit ->
                                                            attempts.findById(
                                                                            uow,
                                                                            candidate.attemptId())
                                                                    .orElseThrow()
                                                                    .schemeReference()
                                                                    .orElseThrow(
                                                                            () ->
                                                                                    new IllegalStateException(
                                                                                            "a return's attempt is"
                                                                                                + " EXECUTED and V017"
                                                                                                + " pairs the scheme"
                                                                                                + " reference with"
                                                                                                + " it"))));
            if (original.isEmpty()) {
                // Resolved since the candidate list: nothing may be sent.
                return false;
            }
            resent =
                    Optional.of(
                            rail.sendReturn(
                                    new PushRail.ReturnPayment(
                                            ourReference, original.get(), candidate.amount())));
        }

        Correlation correlation = PaymentCreation.resolvedCorrelation();
        Optional<PushAnswer> sent = resent;
        return transactions.inTransaction(
                uow -> {
                    RefundStore.LockedRefund locked =
                            refunds.lockForOutcome(uow, candidate.id()).orElse(null);
                    if (locked == null
                            || (locked.refund().status() != RefundStatus.DISPATCHED
                                    && locked.refund().status() != RefundStatus.UNKNOWN)) {
                        return false;
                    }
                    Refund current = locked.refund();
                    ProviderAnswer.Verdict verdict;
                    Optional<ProviderReference> reference;
                    if (sent.isPresent()) {
                        // A re-drive is never the first send: its refused connection proves
                        // nothing about the sends before it (ADR-0057 section 3).
                        verdict =
                                switch (sent.get().verdict()) {
                                    case ACCEPTED -> ProviderAnswer.Verdict.APPROVED;
                                    case REJECTED -> ProviderAnswer.Verdict.DECLINED;
                                    case NOTHING_SENT, INDETERMINATE ->
                                            ProviderAnswer.Verdict.INDETERMINATE;
                                };
                        reference = sent.get().schemeReference();
                    } else {
                        verdict =
                                switch (answer.verdict()) {
                                    case ACCEPTED -> ProviderAnswer.Verdict.APPROVED;
                                    case REJECTED -> ProviderAnswer.Verdict.DECLINED;
                                    default -> ProviderAnswer.Verdict.INDETERMINATE;
                                };
                        reference = answer.schemeReference();
                    }
                    boolean acted;
                    if (verdict == ProviderAnswer.Verdict.INDETERMINATE
                            && current.status() == RefundStatus.UNKNOWN) {
                        // Ambiguity is never a failure, and an UNKNOWN stays UNKNOWN: nothing
                        // to apply (the card leg's stance, INV-LIFE-03).
                        acted = false;
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
                        PaymentOutcomes.RefundApplied result =
                                outcomes.applyRefund(
                                        uow,
                                        intent.id(),
                                        current,
                                        current.status(),
                                        verdict,
                                        reference,
                                        intent.creditAccount(),
                                        correlation);
                        acted = result.acting();
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
                    sent.flatMap(PushAnswer::evidence)
                            .ifPresent(
                                    bytes ->
                                            evidence.append(
                                                    uow,
                                                    Optional.empty(),
                                                    Optional.of(current.id()),
                                                    EvidenceKind.RESPONSE,
                                                    bytes,
                                                    at));
                    return acted;
                });
    }
}
