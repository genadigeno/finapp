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
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;

/**
 * The withdrawal inquiry sweep (`P7-TSK-008`, ADR-0062 §3) — any instance resolves a
 * {@code DISPATCHED} withdrawal stranded by a crash and an {@code UNKNOWN} one whose answer
 * was lost, by asking the scheme for OUR reference and applying the word through the same
 * {@link WithdrawalOutcomes} every resolver uses. Leaderless: bounded candidates, one
 * inquiry and one outcome transaction per row, each candidate re-judged under its own lock
 * (the {@code MerchantPayoutResolution} justification, restated). Registered in
 * {@code DISTRIBUTED_EXECUTION.md} §3.
 *
 * <h2>The bounds, and whose clock decides ({@code INV-LIFE-03})</h2>
 *
 * <ul>
 *   <li>{@code dispatchedAge}: a {@code DISPATCHED} withdrawal is swept only once its
 *       latest send permit is this old — the candidacy filter, generous against skew.
 *   <li>{@code unknownAge}: an {@code UNKNOWN} withdrawal is asked about once it has been
 *       unknown this long.
 *   <li>{@code margin}: the scheme's explicit {@code UNRECOGNISED} concludes
 *       {@code NEVER_RECEIVED} only when the latest permit is older than <strong>the
 *       rail's DECLARED outcome deadline plus this margin</strong> — the declaration is
 *       the datum, the margin absorbs clock skew and delivery lag, and a zero margin is
 *       refused at construction (ADR-0057 §4's rule: it would let the sweep hear
 *       "unrecognised" for a request still in flight).
 * </ul>
 */
@Slf4j
public final class WithdrawalResolution {

    /** The sweep's bounds and batch, refused mis-configured at construction. */
    public record Config(
            Duration dispatchedAge, Duration unknownAge, Duration margin, int batchSize) {

        public Config {
            requirePositive(dispatchedAge, "dispatchedAge");
            requirePositive(unknownAge, "unknownAge");
            requirePositive(margin, "margin");
            if (batchSize < 1) {
                throw new IllegalArgumentException("batchSize must be at least 1");
            }
        }

        private static void requirePositive(Duration bound, String what) {
            Objects.requireNonNull(bound, what + " must not be null");
            if (bound.isZero() || bound.isNegative()) {
                throw new IllegalArgumentException(
                        what + " must be positive: a zero bound would conclude against a"
                                + " request still in flight (ADR-0057 §4)");
            }
        }
    }

    private final WithdrawalStore<Connection> withdrawals;
    private final WithdrawalOutcomes outcomes;
    private final PushRail rail;
    private final PaymentRails rails;
    private final RailId railId;
    private final ProviderEvidenceStore<Connection> evidence;
    private final Config config;
    private final IdGenerator ids;
    private final Clock clock;
    private final TransactionRunner transactions;

    public WithdrawalResolution(
            WithdrawalStore<Connection> withdrawals,
            WithdrawalOutcomes outcomes,
            PushRail rail,
            PaymentRails rails,
            RailId railId,
            ProviderEvidenceStore<Connection> evidence,
            Config config,
            IdGenerator ids,
            Clock clock,
            TransactionRunner transactions) {
        this.withdrawals = Objects.requireNonNull(withdrawals, "withdrawals must not be null");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
        this.rail = Objects.requireNonNull(rail, "rail must not be null");
        this.rails = Objects.requireNonNull(rails, "rails must not be null");
        this.railId = Objects.requireNonNull(railId, "railId must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    }

    /**
     * One tick's tally — telemetry, never the count of record: {@code resolved} counts this
     * tick's own acting transitions; a candidate a sibling resolved first is skipped
     * quietly (the {@code PaymentSweeper} shape).
     */
    public record SweepResult(
            int candidates, int resolved, Map<WithdrawalStatus, Integer> actingJudgements) {

        public SweepResult {
            Objects.requireNonNull(actingJudgements, "actingJudgements must not be null");
        }
    }

    @SuppressWarnings("try") // The Scopes are used for their close side effects (the idiom).
    public SweepResult sweep() {
        Instant now = Instant.now(clock);
        List<Withdrawal> candidates =
                transactions.inTransaction(
                        uow ->
                                withdrawals.findSweepable(
                                        uow,
                                        now.minus(config.dispatchedAge()),
                                        now.minus(config.unknownAge()),
                                        config.batchSize()));
        int resolved = 0;
        Map<WithdrawalStatus, Integer> judgements = new TreeMap<>();
        for (Withdrawal candidate : new ArrayList<>(candidates)) {
            // Per-row containment: one candidate's failure never starves the rest.
            try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                    CorrelationContext.Scope scope =
                            CorrelationContext.enter(
                                    Correlation.startingWith(
                                            CorrelationId.generate(ids)))) {
                // The inquiry, holding no connection (ADR-0046): the scheme is asked for
                // OUR reference, and its word lands on the locked row.
                PushInquiryAnswer answer = rail.inquire(candidate.reference());
                WithdrawalOutcomes.Applied applied =
                        transactions.inTransaction(
                                uow -> {
                                    Withdrawal locked =
                                            withdrawals
                                                    .findForUpdate(uow, candidate.id())
                                                    .orElseThrow(
                                                            () ->
                                                                    new PaymentsStorageException(
                                                                            "a swept withdrawal"
                                                                                + " vanished"));
                                    WithdrawalOutcomes.Applied inner =
                                            outcomes.applyInquiryAnswer(
                                                    uow,
                                                    locked,
                                                    answer,
                                                    neverReceivedBound(),
                                                    CorrelationContext.current().orElseThrow());
                                    answer.evidence()
                                            .ifPresent(
                                                    bytes ->
                                                            evidence.appendForWithdrawal(
                                                                    uow,
                                                                    locked.id(),
                                                                    EvidenceKind.QUERY_RESULT,
                                                                    bytes,
                                                                    Instant.now(clock)));
                                    return inner;
                                });
                if (applied.acting()) {
                    resolved++;
                    judgements.merge(applied.status(), 1, Integer::sum);
                }
            } catch (RuntimeException oneRowFailed) {
                // Identifier and failure class only - never scheme bytes (INV-AUD-02).
                log.warn(
                        "withdrawal sweep could not resolve {}: {}",
                        candidate.id(),
                        oneRowFailed.getClass().getSimpleName());
            }
        }
        return new SweepResult(candidates.size(), resolved, judgements);
    }

    /**
     * The instant at or before which a permit makes {@code UNRECOGNISED} conclusive:
     * now − (the rail's declared outcome deadline + the configured margin). Declared data
     * judged against the permit, never a clock alone (ADR-0062 §3).
     */
    private Instant neverReceivedBound() {
        Duration declared =
                rails.capabilitiesOf(railId)
                        .outcomeDeadline()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "the withdrawal rail declares no outcome"
                                                        + " deadline: its descriptor cannot"
                                                        + " bound its own ambiguity"));
        return Instant.now(clock).minus(declared.plus(config.margin()));
    }
}
