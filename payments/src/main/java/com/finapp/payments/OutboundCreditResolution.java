package com.finapp.payments;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolution of cross-border outbound credits by inquiry (`P9-TSK-020`, the {@code WithdrawalResolution} shape):
 * the leaderless sweep - every instance reads the due credits without a lock, asks the corridor provider for
 * OUR reference holding no connection, then judges the answer on the locked row through
 * {@link OutboundCreditOutcomes}, the acting conditional deciding which resolver acts - and the hinted inquiry
 * a callback triggers ({@link #resolve}), the same path for one reference. The sweep also follows a completed
 * credit's delivery.
 */
@Slf4j
public final class OutboundCreditResolution {

    /**
     * The sweep's bounds.
     *
     * @param dispatchedAge how long a {@code DISPATCHED} credit's permit stands before the sweep asks - longer than
     *     a flight's own send, so a live flight is not raced
     * @param unknownAge, receivedAge how long an {@code UNKNOWN} or {@code RECEIVED} credit waits between inquiries
     * @param deliveryAge how long after its birth a completed credit's delivery is followed
     * @param margin added to the rail's declared outcome deadline before {@code UNRECOGNISED} is conclusive
     */
    public record Config(
            Duration dispatchedAge, Duration unknownAge, Duration receivedAge, Duration deliveryAge, Duration margin,
            int batchSize) {
        public Config {
            for (Duration bound : List.of(dispatchedAge, unknownAge, receivedAge, deliveryAge, margin)) {
                Objects.requireNonNull(bound, "every bound must be given");
                if (bound.isNegative() || bound.isZero()) {
                    throw new IllegalArgumentException("every sweep bound must be positive");
                }
            }
            if (batchSize < 1) {
                throw new IllegalArgumentException("batchSize must be at least 1");
            }
        }
    }

    /** One tick's tally - telemetry, never the count of record. */
    public record SweepResult(int candidates, int resolved, Map<OutboundCreditStore.Status, Integer> actingJudgements) {
        public SweepResult {
            Objects.requireNonNull(actingJudgements, "actingJudgements must not be null");
        }
    }

    private final OutboundCreditStore credits;
    private final OutboundCreditOutcomes outcomes;
    private final RailOperations operations;
    private final PaymentRails rails;
    private final ProviderEvidenceStore<java.sql.Connection> evidence;
    private final Config config;
    private final IdGenerator ids;
    private final Clock clock;
    private final TransactionRunner transactions;
    private final OutboundCreditReturnStore returnStore;

    public OutboundCreditResolution(
            OutboundCreditStore credits,
            OutboundCreditOutcomes outcomes,
            RailOperations operations,
            PaymentRails rails,
            ProviderEvidenceStore<java.sql.Connection> evidence,
            Config config,
            IdGenerator ids,
            Clock clock,
            TransactionRunner transactions,
            OutboundCreditReturnStore returnStore) {
        this.credits = Objects.requireNonNull(credits, "credits must not be null");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        this.rails = Objects.requireNonNull(rails, "rails must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.returnStore = Objects.requireNonNull(returnStore, "returnStore must not be null");
    }

    /** One tick: every due credit inquired and judged, each contained so one failure never starves the rest. */
    @SuppressWarnings("try") // The Scopes are used for their close side effects (the idiom).
    public SweepResult sweep() {
        List<OutboundCreditStore.Row> due = transactions.inTransaction(uow -> credits.findDue(uow, config.dispatchedAge(),
                config.unknownAge(), config.receivedAge(), config.deliveryAge(), config.batchSize()));
        int resolved = 0;
        Map<OutboundCreditStore.Status, Integer> judgements = new TreeMap<>();
        for (OutboundCreditStore.Row candidate : due) {
            try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                    CorrelationContext.Scope scope = CorrelationContext.enter(
                            Correlation.startingWith(CorrelationId.generate(ids)))) {
                Optional<OutboundCreditOutcomes.Applied> applied = inquireAndApply(candidate);
                if (applied.isPresent() && applied.get().acting()) {
                    resolved++;
                    judgements.merge(applied.get().status(), 1, Integer::sum);
                }
            } catch (RuntimeException oneRowFailed) {
                // Identifier and failure class only - never provider bytes (INV-AUD-02).
                log.warn("outbound credit sweep could not resolve {}: {}", candidate.id(),
                        oneRowFailed.getClass().getSimpleName());
            }
        }
        return new SweepResult(due.size(), resolved, judgements);
    }

    /**
     * The hinted inquiry: the credit OUR reference names, asked of its provider now and judged on its locked row.
     * Empty when the reference names no credit, or the credit has nothing left to learn. Runs as the caller's actor
     * and inside the caller's correlation - an enumerated platform site at every caller.
     */
    public Optional<OutboundCreditOutcomes.Applied> resolve(EndToEndReference reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        Optional<OutboundCreditStore.Row> found = transactions.inTransaction(uow -> credits.byReference(uow, reference));
        if (found.isEmpty() || !learnable(found.get()) && !returnable(found.get())) {
            return Optional.empty();
        }
        return inquireAndApply(found.get());
    }

    private Optional<OutboundCreditOutcomes.Applied> inquireAndApply(OutboundCreditStore.Row candidate) {
        Optional<CorridorRail> rail = operations.corridorRail(candidate.rail());
        if (rail.isEmpty()) {
            log.warn("outbound credit {} names rail {} but no corridor adapter operates it here", candidate.id(),
                    candidate.rail().value());
            return Optional.empty();
        }
        // The inquiry, holding no connection (ADR-0046): the provider is asked for OUR reference.
        CorridorRail.InquiryAnswer answer = rail.get().inquire(candidate.reference());
        Instant bound = neverReceivedBound(candidate.rail());
        return Optional.of(transactions.inTransaction(uow -> {
            OutboundCreditStore.Row locked = credits.lock(uow, candidate.id())
                    .orElseThrow(() -> new PaymentsStorageException("a resolving outbound credit vanished"));
            OutboundCreditOutcomes.Applied applied = outcomes.applyInquiryAnswer(uow, locked, answer, bound,
                    CorrelationContext.current().orElseThrow());
            evidenceOf(answer).ifPresent(bytes -> evidence.appendForOutboundCredit(uow, locked.id(),
                    EvidenceKind.QUERY_RESULT, bytes, Instant.now(clock)));
            return applied;
        }));
    }

    /**
     * A completed credit can still be returned (`P9-TSK-023`): a hinted inquiry asks after it until its return is
     * recorded - the sweep's report channel reads returns from the corridor's report instead.
     */
    private boolean returnable(OutboundCreditStore.Row row) {
        return row.status() == OutboundCreditStore.Status.COMPLETED
                && transactions.inTransaction(uow -> returnStore.findByCredit(uow, row.id())).isEmpty();
    }

    private static boolean learnable(OutboundCreditStore.Row row) {
        return row.status().resolvable()
                || (row.status() == OutboundCreditStore.Status.COMPLETED && row.deliveredAt().isEmpty());
    }

    private static Optional<byte[]> evidenceOf(CorridorRail.InquiryAnswer answer) {
        return switch (answer) {
            case CorridorRail.InquiryAnswer.Found found -> Optional.of(found.evidence().body());
            case CorridorRail.InquiryAnswer.Unrecognised unrecognised -> Optional.of(unrecognised.evidence().body());
            case CorridorRail.InquiryAnswer.NothingSent nothing -> Optional.empty();
            case CorridorRail.InquiryAnswer.Indeterminate unknown -> unknown.evidence().map(CorridorRail.Evidence::body);
        };
    }

    /**
     * The instant at or before which a permit makes {@code UNRECOGNISED} conclusive: now - (the rail's declared
     * outcome deadline + the configured margin). Declared data judged against the permit, never a clock alone.
     */
    private Instant neverReceivedBound(RailId rail) {
        Duration declared = rails.capabilitiesOf(rail).outcomeDeadline()
                .orElseThrow(() -> new IllegalStateException("the corridor rail declares no outcome deadline: its"
                        + " descriptor cannot bound its own ambiguity"));
        return Instant.now(clock).minus(declared.plus(config.margin()));
    }
}
