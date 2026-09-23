package com.finapp.merchant;

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
 * Resolves payouts the synchronous answer left open (`P6-TSK-012`, ADR-0046 §4, ADR-0057 §10):
 * a {@code DISPATCHED} payout stranded by a crash, and an {@code UNKNOWN} one whose send timed
 * out — each queried by our reference and resolved through the same
 * {@link MerchantPayoutOutcomes} the synchronous path uses. The refund's recorded sweep
 * deferral finds its sibling here; the refund's own sweep stays deferred.
 *
 * <h2>Every instance sweeps; nothing is leased</h2>
 *
 * <p>The query is read-only and idempotent at the provider, and every write is a conditional
 * transition on the row locked in its own transaction, whose losers converge — so N sweepers,
 * the synchronous answer and a takeover's re-send are one harmless race. No lease, no leader
 * (the {@code PaymentSweeper} justification, restated). Registered in
 * {@code DISTRIBUTED_EXECUTION.md} §3.
 *
 * <h2>The bounds are the safety</h2>
 *
 * <ul>
 *   <li>{@code dispatchedAge}: a {@code DISPATCHED} payout is swept only once its latest send
 *       permit is this old, and an unrecognised reference is concluded {@code NEVER_RECEIVED}
 *       only past the same bound — judged again on the locked row, because a takeover may have
 *       renewed the permit since the candidate was read (ADR-0057 §4). The bound must exceed
 *       any plausible gap between a committed permit and its request reaching the provider.
 *   <li>{@code unknownAge}: an {@code UNKNOWN} payout is queried once it has been unknown this
 *       long, measured from the move that made it so.
 * </ul>
 *
 * <h2>One transaction per row, under its own correlation, as the platform</h2>
 *
 * <p>A poisoned row must not stall other merchants' payouts, and a scheduled resolution has no
 * person at all — each row runs through {@link SecurityContext#enterSystem()}, an enumerated
 * site.
 */
@Slf4j
public final class MerchantPayoutResolution {

    private final MerchantTransactionRunner transactions;
    private final MerchantPayoutStore<Connection> payouts;
    private final PayoutProvider provider;
    private final MerchantPayoutOutcomes outcomes;
    private final PayoutEvidenceStore<Connection> evidence;
    private final IdGenerator ids;
    private final Clock clock;
    private final Duration dispatchedAge;
    private final Duration unknownAge;
    private final int batchSize;

    public MerchantPayoutResolution(
            MerchantTransactionRunner transactions,
            MerchantPayoutStore<Connection> payouts,
            PayoutProvider provider,
            MerchantPayoutOutcomes outcomes,
            PayoutEvidenceStore<Connection> evidence,
            IdGenerator ids,
            Clock clock,
            Duration dispatchedAge,
            Duration unknownAge,
            int batchSize) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.payouts = Objects.requireNonNull(payouts, "payouts must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.dispatchedAge = notNegative(dispatchedAge, "dispatchedAge");
        this.unknownAge = notNegative(unknownAge, "unknownAge");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /**
     * One tick's tally — telemetry, never the count of record. {@code resolved} counts this
     * tick's own acting transitions; a candidate a sibling resolved first is {@code skipped}.
     *
     * @param actingJudgements the status each of this tick's OWN acting transitions committed,
     *     in candidate order — what the payout meter counts (`P6-TSK-013`, the
     *     {@code PaymentSweeper} shape). A sibling's resolution, a young permit and an answer
     *     that moved nothing are absent, so ten sweeps racing one payout count one judgement
     */
    public record SweepResult(
            int candidates,
            int resolved,
            int skipped,
            int failedRows,
            List<MerchantPayoutStatus> actingJudgements) {

        public SweepResult {
            actingJudgements = List.copyOf(actingJudgements);
            if (actingJudgements.size() != resolved) {
                throw new IllegalArgumentException(
                        "every acting transition is one judgement: " + resolved + " resolved, "
                                + actingJudgements.size() + " judged");
            }
        }
    }

    /** One sweep: bounded candidates, one transaction per row. */
    @SuppressWarnings("try") // The Scopes are used for their close side effects (the idiom).
    public SweepResult sweep() {
        Instant now = Instant.now(clock);
        List<MerchantPayout> candidates =
                transactions.inTransaction(
                        uow ->
                                payouts.findSweepable(
                                        uow,
                                        now.minus(dispatchedAge),
                                        now.minus(unknownAge),
                                        batchSize));
        List<MerchantPayoutStatus> judged = new ArrayList<>();
        int skipped = 0;
        int failedRows = 0;
        for (MerchantPayout candidate : candidates) {
            try (CorrelationContext.Scope flow =
                            CorrelationContext.enter(
                                    Correlation.startingWith(CorrelationId.generate(ids)));
                    SecurityContext.Scope actor = SecurityContext.enterSystem()) {
                Optional<MerchantPayoutStatus> judgement = resolve(candidate);
                if (judgement.isPresent()) {
                    judged.add(judgement.get());
                } else {
                    skipped++;
                }
            } catch (RuntimeException oneRowsFailure) {
                // The anti-stall posture: the rows behind this one are other merchants' money.
                // Identifier and failure class only - never provider bytes (INV-AUD-02).
                failedRows++;
                log.warn(
                        "Resolving payout {} failed with {}; the sweep continues and the next"
                                + " tick will retry this row",
                        candidate.id(),
                        oneRowsFailure.getClass().getSimpleName());
            }
        }
        return new SweepResult(candidates.size(), judged.size(), skipped, failedRows, judged);
    }

    /** The status THIS call's own transition committed, or empty when it moved nothing. */
    private Optional<MerchantPayoutStatus> resolve(MerchantPayout candidate) {
        // The query, holding no connection (ADR-0046 §1).
        PayoutQueryAnswer answer = provider.query(candidate.reference());
        Correlation correlation =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a payout resolution must run inside a"
                                                        + " correlation scope"));
        return transactions.inTransaction(
                uow -> {
                    // LOCK, THEN JUDGE: re-read under the row lock, never trusted from the list -
                    // resolved by a sibling, the synchronous answer or a takeover since.
                    Optional<MerchantPayout> found =
                            payouts.findForUpdate(uow, candidate.merchantId(), candidate.id());
                    if (found.isEmpty()) {
                        return Optional.<MerchantPayoutStatus>empty();
                    }
                    Instant now = Instant.now(clock);
                    MerchantPayoutOutcomes.Applied applied =
                            outcomes.applyQueryAnswer(
                                    uow, found.get(), answer, now.minus(dispatchedAge), correlation);
                    // Retained whatever it moved: a late or contradictory answer beside a
                    // resolved payout is evidence, not a move (INV-HIST-02).
                    answer.evidence()
                            .ifPresent(
                                    bytes ->
                                            evidence.append(
                                                    uow,
                                                    candidate.id(),
                                                    PayoutEvidenceKind.QUERY_RESULT,
                                                    bytes,
                                                    now));
                    return applied.acting()
                            ? Optional.of(applied.status())
                            : Optional.<MerchantPayoutStatus>empty();
                });
    }

    private static Duration notNegative(Duration value, String what) {
        Objects.requireNonNull(value, what + " must not be null");
        if (value.isNegative()) {
            throw new IllegalArgumentException(what + " must not be negative: " + value);
        }
        return value;
    }
}
