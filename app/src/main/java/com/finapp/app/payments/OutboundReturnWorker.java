package com.finapp.app.payments;

import com.finapp.payments.EndToEndReference;
import com.finapp.payments.OutboundCreditReturns;
import com.finapp.payments.OutboundCreditStore;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.RailId;
import com.finapp.payments.SchemeExecutionClaim;
import com.finapp.payments.SchemeExecutionClaimStore;
import com.finapp.payments.TransactionRunner;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.reconciliation.WaitingPayoutReturns;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * The corridor report's return channel (`P9-TSK-023`, the lifecycle document section 4; the {@code PayoutReturnSweep}
 * precedent): every instance pages the corridor sources' waiting {@code PAYOUT_RETURNED} items lock-free, then - one
 * item per transaction (T-f) - re-reads the item {@code FOR SHARE}, finds its outbound credit by our end-to-end
 * reference (or the provider's payout reference through the completion's claim), locks the credit and hands the
 * return to {@link OutboundCreditReturns}. A credit still in flight defers - nothing written, the inquiry completing it
 * applies the return - and a return that does not apply writes nothing: the grace leg parks it for a person. The
 * merchant worker never sees a corridor return: its reader is scoped to the merchant's sources.
 */
@Slf4j
public final class OutboundReturnWorker {

    /** One tick's tally - telemetry, never the count of record. */
    public record SweepResult(int candidates, int applied, int deferred, int notApplicable, int skipped, int failedRows) {}

    private final TransactionRunner transactions;
    private final WaitingPayoutReturns waiting;
    private final OutboundCreditStore credits;
    private final SchemeExecutionClaimStore<Connection> claims;
    private final List<RailId> corridorRails;
    private final OutboundCreditReturns returns;
    private final MeterRegistry meters;
    private final int batchSize;
    private final com.finapp.app.telemetry.CrossBorderMetrics crossBorderMetrics;
    private final com.finapp.crossborder.PaymentStore payments;
    private final com.finapp.platform.telemetry.Spans spans;

    public OutboundReturnWorker(
            TransactionRunner transactions,
            WaitingPayoutReturns waiting,
            OutboundCreditStore credits,
            SchemeExecutionClaimStore<Connection> claims,
            List<RailId> corridorRails,
            OutboundCreditReturns returns,
            MeterRegistry meters,
            int batchSize,
            com.finapp.app.telemetry.CrossBorderMetrics crossBorderMetrics,
            com.finapp.crossborder.PaymentStore payments,
            com.finapp.platform.telemetry.Spans spans) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.waiting = Objects.requireNonNull(waiting, "waiting must not be null");
        this.credits = Objects.requireNonNull(credits, "credits must not be null");
        this.claims = Objects.requireNonNull(claims, "claims must not be null");
        this.corridorRails = List.copyOf(corridorRails);
        this.returns = Objects.requireNonNull(returns, "returns must not be null");
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.batchSize = batchSize;
        this.crossBorderMetrics = Objects.requireNonNull(crossBorderMetrics, "crossBorderMetrics must not be null");
        this.payments = Objects.requireNonNull(payments, "payments must not be null");
        this.spans = Objects.requireNonNull(spans, "spans must not be null");
    }

    /** One tick: every page of waiting corridor returns, keyset-walked so a deferring return never starves the rest. */
    public SweepResult sweep() {
        int candidates = 0;
        int applied = 0;
        int deferred = 0;
        int notApplicable = 0;
        int skipped = 0;
        int failed = 0;
        Optional<UUID> cursor = Optional.empty();
        while (true) {
            Optional<UUID> after = cursor;
            List<WaitingPayoutReturns.WaitingReturn> page = transactions.inTransaction(uow -> waiting.page(uow, after, batchSize));
            for (WaitingPayoutReturns.WaitingReturn item : page) {
                candidates++;
                Optional<OutboundCreditReturns.Outcome> outcome;
                try {
                    outcome = applyContained(item);
                } catch (RuntimeException oneRowFailed) {
                    // The class name only - a JDBC message can carry values. The next tick retries.
                    log.warn("Corridor return {} could not be applied: {}", item.itemId(),
                            oneRowFailed.getClass().getSimpleName());
                    failed++;
                    continue;
                }
                switch (outcome.orElse(OutboundCreditReturns.Outcome.ALREADY_RETURNED)) {
                    case APPLIED -> applied++;
                    case DEFERRED -> deferred++;
                    case NOT_APPLICABLE, CONTRADICTED -> notApplicable++;
                    case ALREADY_RETURNED -> skipped++;
                }
            }
            if (page.size() < batchSize) {
                break;
            }
            cursor = Optional.of(page.get(page.size() - 1).itemId());
        }
        return new SweepResult(candidates, applied, deferred, notApplicable, skipped, failed);
    }

    /** One item, in its own transaction, in its own flow, as the platform. Empty when it names no credit of ours. */
    @SuppressWarnings("try") // The Scopes are used for their close side effects (the idiom).
    private Optional<OutboundCreditReturns.Outcome> applyContained(WaitingPayoutReturns.WaitingReturn seen) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(seen.correlation()).causing(CausationId.of(seen.itemId().toString())));
                SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            return spans.within(com.finapp.app.telemetry.Phase9Spans.OUTBOUND_RETURN_APPLY, java.util.Map.of(),
                    () -> transactions.inTransaction(uow -> {
                Optional<WaitingPayoutReturns.WaitingReturn> item = waiting.lockWaiting(uow, seen.itemId());
                if (item.isEmpty()) {
                    return Optional.<OutboundCreditReturns.Outcome>empty();
                }
                Optional<OutboundCreditStore.Row> credit = creditOf(uow, item.get());
                if (credit.isEmpty()) {
                    return Optional.<OutboundCreditReturns.Outcome>empty();
                }
                OutboundCreditStore.Row locked = credits.lock(uow, credit.get().id())
                        .orElseThrow(() -> new IllegalStateException("a found outbound credit vanished"));
                OutboundCreditReturns.Outcome outcome = returns.apply(uow, locked, new OutboundCreditReturns.Evidence(
                                item.get().amount(), Optional.empty(),
                                item.get().settlementDate().atStartOfDay().toInstant(ZoneOffset.UTC)),
                        "report", CorrelationContext.current().orElseThrow());
                payments.findOwned(uow, locked.subject(), locked.customerParty())
                        .ifPresent(payment -> count(payment.corridor().code(), outcome));
                return Optional.of(outcome);
            }));
        }
    }

    /** The credit the line names: by our E first, then by the provider's payout reference through its claim. */
    private Optional<OutboundCreditStore.Row> creditOf(Connection uow, WaitingPayoutReturns.WaitingReturn item) {
        Optional<OutboundCreditStore.Row> byOurs = item.endToEndReference().flatMap(reference -> {
            try {
                return credits.byReference(uow, new EndToEndReference(reference));
            } catch (IllegalArgumentException notOurs) {
                return Optional.empty();
            }
        });
        if (byOurs.isPresent() || item.providerReference().isEmpty()) {
            return byOurs;
        }
        for (RailId rail : corridorRails) {
            Optional<SchemeExecutionClaim> claim = claims.findByExecution(uow, rail,
                    new ProviderReference(item.providerReference().get()));
            if (claim.isPresent() && claim.get().subject() == SchemeExecutionClaim.Subject.OUTBOUND_CREDIT) {
                return credits.lock(uow, com.finapp.payments.OutboundCreditId.of(claim.get().subjectId()));
            }
        }
        return Optional.empty();
    }

    private void count(String corridor, OutboundCreditReturns.Outcome outcome) {
        String tag = switch (outcome) {
            case APPLIED -> "applied";
            case DEFERRED -> "deferred";
            case NOT_APPLICABLE, CONTRADICTED -> "not_applicable";
            case ALREADY_RETURNED -> "already_returned";
        };
        // Counted after commit, per corridor (P9-TSK-027). An applied return is counted by the composition's own
        // returned edge, so only the worker's other judgements are counted here.
        if (outcome != OutboundCreditReturns.Outcome.APPLIED) {
            crossBorderMetrics.returned(corridor, tag);
        }
    }
}
