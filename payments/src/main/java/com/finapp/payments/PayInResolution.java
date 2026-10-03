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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * The pay-in resolution sweep (`P7-TSK-009`, ADR-0062 §5) — the platform's clock deciding
 * only <strong>when to ask</strong>, never an outcome: leaderless, bounded, one wire call
 * and one outcome transaction per row, each candidate re-judged on the current row (the
 * {@code WithdrawalResolution} shape). Registered in {@code DISTRIBUTED_EXECUTION.md} §3.
 *
 * <h2>Two legs, split by the handle</h2>
 *
 * <ul>
 *   <li><strong>Handle-less</strong> ({@code AWAITING_PAYER} with no stored handle — a
 *       crash mid-{@code initiate}, a lost answer, a refused connection whose conclusion a
 *       racing writer beat): <strong>re-initiate</strong> with the SAME reference. The
 *       scheme deduplicates on it, so an opened initiation answers its existing handle and
 *       an unopened one opens late — convergent in both worlds, which is why no
 *       never-opened conclusion exists here at all ({@code INV-LIFE-03}: the operator's
 *       standing alert is the age gauge, not a clock's guess).
 *   <li><strong>Handled</strong>: <strong>inquire</strong> by our reference and apply the
 *       payer PSP's word — executed, rejected or expired — through the same
 *       {@link PaymentOutcomes} every resolver uses.
 * </ul>
 *
 * <p>Both legs stamp the initiation permit forward first, conditionally: the winner makes
 * this tick's one wire call for the row, the losers skip — a wire-noise arbiter among
 * instances, never a money guard (the aggregate door says why).
 */
@Slf4j
public final class PayInResolution {

    /** The sweep's pacing and batch, refused mis-configured at construction. */
    public record Config(Duration initiationAge, int batchSize) {

        public Config {
            Objects.requireNonNull(initiationAge, "initiationAge must not be null");
            if (initiationAge.isZero() || initiationAge.isNegative()) {
                throw new IllegalArgumentException(
                        "initiationAge must be positive: a zero bound would re-contact the"
                                + " scheme about a request still in flight");
            }
            if (batchSize < 1) {
                throw new IllegalArgumentException("batchSize must be at least 1");
            }
        }
    }

    private final PaymentAttemptStore<Connection> attempts;
    private final PaymentIntentStore<Connection> intents;
    private final PaymentOutcomes outcomes;
    private final PushRail rail;
    private final ProviderEvidenceStore<Connection> evidence;
    private final Config config;
    private final IdGenerator ids;
    private final Clock clock;
    private final TransactionRunner transactions;

    public PayInResolution(
            PaymentAttemptStore<Connection> attempts,
            PaymentIntentStore<Connection> intents,
            PaymentOutcomes outcomes,
            PushRail rail,
            ProviderEvidenceStore<Connection> evidence,
            Config config,
            IdGenerator ids,
            Clock clock,
            TransactionRunner transactions) {
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
    public record SweepResult(int candidates, int contacted, int resolved) {}

    @SuppressWarnings("try") // The Scopes are used for their close side effects (the idiom).
    public SweepResult sweep() {
        Instant now = Instant.now(clock);
        List<PaymentAttempt> candidates =
                transactions.inTransaction(
                        uow ->
                                attempts.findResolvableInitiations(
                                        uow,
                                        now.minus(config.initiationAge()),
                                        config.batchSize()));
        int contacted = 0;
        int resolved = 0;
        for (PaymentAttempt candidate : new ArrayList<>(candidates)) {
            // Per-row containment: one candidate's failure never starves the rest.
            try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                    CorrelationContext.Scope scope =
                            CorrelationContext.enter(
                                    Correlation.startingWith(
                                            CorrelationId.generate(ids)))) {
                // The permit, stamped forward CONDITIONALLY and first: the winner makes
                // this tick's one wire call for the row, a losing sibling skips - and a
                // row a resolver concluded meanwhile renews nothing and is skipped too.
                Instant renewed = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
                boolean permitted =
                        transactions.inTransaction(
                                uow ->
                                        attempts.renewInitiationPermit(
                                                uow,
                                                candidate.id(),
                                                candidate.lastDispatchedAt(),
                                                renewed));
                if (!permitted) {
                    continue;
                }
                contacted++;
                if (resolveOne(candidate)) {
                    resolved++;
                }
            } catch (RuntimeException oneRowFailed) {
                // Identifier and failure class only - never scheme bytes (INV-AUD-02).
                log.warn(
                        "pay-in sweep could not resolve attempt {}: {}",
                        candidate.id(),
                        oneRowFailed.getClass().getSimpleName());
            }
        }
        return new SweepResult(candidates.size(), contacted, resolved);
    }

    /** One row's wire call and outcome transaction; true when this tick's own conditional acted. */
    private boolean resolveOne(PaymentAttempt candidate) {
        // The cause resolved the door's way (PaymentCreation.resolvedCorrelation): a
        // sweep tick is a flow root, and the executed fact's event must still carry a
        // causation id - the raw root correlation has none, and an announce would refuse.
        Correlation correlation = PaymentCreation.resolvedCorrelation();
        if (candidate.authorizationHandle().isEmpty()) {
            // The re-initiate leg - the wire call holding no connection (ADR-0046).
            InitiationAnswer answer =
                    rail.initiate(
                            new PushRail.PayInInitiation(
                                    candidate.endToEndReference(),
                                    amountOf(candidate)));
            PaymentOutcomes.Applied applied =
                    transactions.inTransaction(
                            uow -> {
                                PaymentOutcomes.Applied inner =
                                        outcomes.applyInitiation(
                                                uow,
                                                candidate.intentId(),
                                                candidate.id(),
                                                answer.outcome(),
                                                answer.authorizationHandle(),
                                                correlation);
                                answer.evidence()
                                        .ifPresent(
                                                bytes ->
                                                        evidence.append(
                                                                uow,
                                                                Optional.of(candidate.id()),
                                                                Optional.empty(),
                                                                EvidenceKind.RESPONSE,
                                                                bytes,
                                                                Instant.now(clock)));
                                return inner;
                            });
            return applied.acting();
        }
        // The inquiry leg: the payer PSP's word by OUR reference, applied on the current row.
        PushInquiryAnswer answer = rail.inquireInitiation(candidate.endToEndReference());
        PaymentOutcomes.Applied applied =
                transactions.inTransaction(
                        uow -> {
                            PaymentAttempt current =
                                    attempts.findById(uow, candidate.id())
                                            .orElseThrow(
                                                    () ->
                                                            new PaymentsStorageException(
                                                                    "a swept attempt"
                                                                            + " vanished"));
                            PaymentOutcomes.Applied inner;
                            if (current.status() != PaymentAttemptStatus.AWAITING_PAYER) {
                                // A sibling resolver concluded between the read and this
                                // transaction: converge quietly, the row is the truth.
                                inner =
                                        new PaymentOutcomes.Applied(
                                                intentOf(uow, current).status(),
                                                current.status(),
                                                false,
                                                false);
                            } else {
                                PaymentIntent intent = intentOf(uow, current);
                                inner =
                                        outcomes.applyExecution(
                                                uow,
                                                intent.id(),
                                                current.id(),
                                                current.status(),
                                                answer.verdict(),
                                                answer.schemeReference(),
                                                answer.settlementCycle(),
                                                intent.creditAccount(),
                                                intent.amount(),
                                                // The amount the payer PSP says it executed:
                                                // the applier judges it for this producer too
                                                // (the Phase 7 -> 8 transition).
                                                answer.executed(),
                                                correlation);
                            }
                            // An answer whose value parked rests addressed to its parking
                            // (V023's fifth subject, P8-TSK-020), else to the attempt.
                            Optional<java.util.UUID> parkedIn = inner.parking();
                            answer.evidence()
                                    .ifPresent(
                                            bytes -> {
                                                if (parkedIn.isPresent()) {
                                                    evidence.appendForUnmatched(
                                                            uow,
                                                            parkedIn.get(),
                                                            EvidenceKind.QUERY_RESULT,
                                                            bytes,
                                                            Instant.now(clock));
                                                } else {
                                                    evidence.append(
                                                            uow,
                                                            Optional.of(candidate.id()),
                                                            Optional.empty(),
                                                            EvidenceKind.QUERY_RESULT,
                                                            bytes,
                                                            Instant.now(clock));
                                                }
                                            });
                            return inner;
                        });
        return applied.acting();
    }

    /** The initiation's ask is the intent's amount — the one fact, read where it lives. */
    private com.finapp.sharedkernel.money.Money amountOf(PaymentAttempt candidate) {
        return transactions.inTransaction(
                uow -> intentOf(uow, candidate).amount());
    }

    private PaymentIntent intentOf(Connection uow, PaymentAttempt attempt) {
        return intents.findById(uow, attempt.intentId())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "an attempt row's intent exists: V003's foreign key"
                                                + " holds it"));
    }
}
