package com.finapp.merchant;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Produces {@code EFFECTIVE} (`P6-TSK-011`, ADR-0056 §3): the platform makes an approved payout
 * destination effective once its cooling-off has elapsed, and supersedes the merchant's previous
 * one in the same transaction.
 *
 * <h2>A state earned by a producer, never a query filter</h2>
 *
 * <p>"Effective" could have been computed — the latest approval whose deadline has passed. It is
 * a state instead (ADR-0044; the Phase 6 plan's own risk, "expiry treated as a query filter"):
 * one {@code EFFECTIVE} row per merchant, guarded by `V006`'s partial index, is something a
 * payout dispatch can read in its own transaction and a reviewer can see in the history table.
 *
 * <h2>Every instance sweeps; nothing is leased</h2>
 *
 * <p>The {@code CheckoutExpirySweeper} justification, restated: every write is a conditional
 * transition on a locked row whose losers converge ({@code INV-IDEM-02}), so N sweepers racing
 * each other and an operator's withdrawal are one counted race. Registered in
 * {@code DISTRIBUTED_EXECUTION.md} §3.
 *
 * <h2>One transaction per row, under its own correlation, as the platform</h2>
 *
 * <p>A poisoned row must not roll back other merchants' changes, and a scheduled effect has no
 * person at all — the tick runs through {@link SecurityContext#enterSystem()}, an enumerated
 * site.
 */
@Slf4j
public final class PayoutDestinationEffectuation {

    private final MerchantTransactionRunner transactions;
    private final PayoutDestinationStore<Connection> destinations;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;
    private final int batchSize;

    public PayoutDestinationEffectuation(
            MerchantTransactionRunner transactions,
            PayoutDestinationStore<Connection> destinations,
            AuditWriter<Connection> audit,
            IdGenerator ids,
            Clock clock,
            int batchSize) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.destinations = Objects.requireNonNull(destinations, "destinations must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /**
     * One tick's tally — telemetry, never the count of record. {@code effected} counts this
     * tick's own conditional transitions; a candidate a sibling effected or an operator withdrew
     * since the list is {@code skipped}.
     */
    public record SweepResult(int candidates, int effected, int skipped, int failedRows) {}

    /** One sweep: bounded candidates, one transaction per row. */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public SweepResult sweep() {
        Instant now = Instant.now(clock);
        List<PayoutDestination> candidates =
                transactions.inTransaction(uow -> destinations.findDue(uow, now, batchSize));

        int effected = 0;
        int skipped = 0;
        int failedRows = 0;
        for (PayoutDestination candidate : candidates) {
            try (CorrelationContext.Scope flow =
                            CorrelationContext.enter(
                                    Correlation.startingWith(CorrelationId.generate(ids)));
                    SecurityContext.Scope actor = SecurityContext.enterSystem()) {
                if (effectOne(candidate)) {
                    effected++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException oneRowsFailure) {
                // The anti-stall posture: the rows behind this one are other merchants' changes.
                // Identifier and failure class only - a JDBC message can name hosts and values.
                failedRows++;
                log.warn(
                        "Effecting payout destination {} failed with {}; the sweep continues and"
                                + " the next tick will retry this row",
                        candidate.id(),
                        oneRowsFailure.getClass().getSimpleName());
            }
        }
        return new SweepResult(candidates.size(), effected, skipped, failedRows);
    }

    /** {@code true} when <strong>this call's own</strong> conditional transitions fired. */
    private boolean effectOne(PayoutDestination candidate) {
        Correlation correlation =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "an effectuation must run inside a correlation"
                                                        + " scope"));
        return transactions.inTransaction(
                uow -> {
                    // LOCK, THEN JUDGE, THEN WRITE CONDITIONALLY. The approved row first, then
                    // the effective one: every effectuation of a merchant takes them in that
                    // order, and only an effectuation ever locks an EFFECTIVE row, so there is
                    // no second order to deadlock against.
                    Optional<PayoutDestination> found =
                            destinations.findForUpdate(uow, candidate.merchantId(), candidate.id());
                    if (found.isEmpty()) {
                        return false;
                    }
                    PayoutDestination approved = found.get();
                    // RE-JUDGED AGAINST THE CLOCK on the locked row, never trusted from the
                    // list: withdrawn during the cooling-off, or effected by a sibling, since.
                    if (!approved.isDue(Instant.now(clock))) {
                        return false;
                    }

                    // SUPERSEDE FIRST: the one-effective index is checked per statement, so the
                    // previous destination leaves EFFECTIVE before the new one arrives - and the
                    // two commit together, so no reader ever sees neither (MVCC).
                    Optional<PayoutDestination> previous =
                            destinations.findEffectiveForUpdate(uow, approved.merchantId());
                    if (previous.isPresent()) {
                        PayoutDestination replaced = previous.get().supersede(clock);
                        requireLanded(uow, previous.get(), replaced);
                    }
                    PayoutDestination effective = approved.effect(clock);
                    // A throw, never a false: returning here would commit the supersession
                    // without its replacement and leave the merchant with no destination.
                    requireLanded(uow, approved, effective);

                    Actor platform = SecurityContext.require();
                    audit.append(
                            uow,
                            new AuditRecord(
                                    AuditId.next(ids),
                                    platform,
                                    Instant.now(clock),
                                    MerchantAuditAction.PAYOUT_DESTINATION_EFFECTIVE,
                                    PayoutDestinations.TARGET_TYPE,
                                    effective.id().value().toString(),
                                    Optional.empty(),
                                    AuditOutcome.SUCCEEDED,
                                    correlation.correlationId(),
                                    // Identifiers only - never the reference or its suffix
                                    // (INV-AUD-02).
                                    Optional.of(
                                            "destination=" + effective.id()
                                                    + ", merchant=" + effective.merchantId()
                                                    + ", superseded="
                                                    + previous.map(p -> p.id().toString())
                                                            .orElse("none"))));
                    return true;
                });
    }

    private void requireLanded(
            Connection uow, PayoutDestination before, PayoutDestination after) {
        if (!destinations.transition(uow, before, after)) {
            // The locks make a lost count unreachable in this flow; refusing loudly beats
            // guessing if an unknown writer proves otherwise (INV-CON-01).
            throw new MerchantStorageException(
                    "a locked payout destination's conditional move found another writer's"
                            + " state");
        }
    }
}
