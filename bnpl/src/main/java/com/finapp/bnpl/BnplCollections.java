package com.finapp.bnpl;

import com.finapp.creditmath.AllocationLine;
import com.finapp.creditmath.AllocationRules;
import com.finapp.creditmath.DueState;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Auto-collection from the wallet - the servicing step's collection slot (`P12-TSK-019`; {@code PHASE_12_PLAN.md}
 * sections 7.1 T-svc, 7.2 and 12.11 F3; A7, owner decision B6 - the wallet only, no card charge; lending's
 * {@code LoanCollections}; {@code INV-IDEM-02}, {@code INV-LND-04} by analogy, {@code INV-BAL-04}, {@code INV-BNPL-01}).
 * Called by {@link BnplServicing} after the billing and the catch-up credit-balance application and before the closure,
 * under the step's obligation row lock (B4) and on the step's unit of work - so the attempts, any repayments, their
 * entries and events commit with the rest of the step, or none of it does. A system act with no door: the authority is the
 * agreement's pinned terms ({@code auto_collection}), accepted with the agreement; no card instrument is reachable from
 * bnpl ({@code BnplModuleIsolationTest}).
 *
 * <h2>The rule</h2>
 *
 * <ol>
 *   <li>Only an {@code ACTIVE} obligation whose agreement version in force pins terms that collect automatically.
 *   <li>The candidates: every billed instalment due by today, oldest first, never attempted on or after today - on its due
 *       date always (one settled earlier records {@code NOTHING_DUE}), afterwards while it still has something due on each
 *       business date the pinned retry cadence ({@code collection_retry_days}, A7: daily) reaches from its latest attempt.
 *       A missed date is never back-filled: the attempt is dated today ({@code UNIQUE (instalment_id, attempt_date)}
 *       beneath B4).
 *   <li>Each attempt re-derives the due state under B4 ({@link BnplReceivables}, explained by the ledger) and, once a step,
 *       <strong>(P)</strong> the customer's existing wallet row {@code FOR UPDATE} - the wallet alone, whatever their
 *       standing, never opened; no wallet, or not {@code ACTIVE}: {@code WALLET_NOT_POSTABLE}. What it can spend is
 *       re-read under that lock for every attempt ({@code INV-BAL-04}).
 *   <li>The amount: what the instalment has due - or, the pinned terms allowing partial collection, as much of it as is
 *       available; otherwise nothing ({@code INSUFFICIENT_FUNDS}). Oldest first: once an instalment is not collected in
 *       full - or an older one is still due and not attempted now - nothing younger is collected that day
 *       ({@code INSUFFICIENT_FUNDS}): the pinned allocation would pay the older one first.
 *   <li>Money moves only through {@link BnplRepayments}' one posting half: kind {@code AUTO_COLLECTION}, the attempt's
 *       natural key {@code <instalment>:<date>}, {@code ALLOCATION_ENGINE_V1} - every line on the attempted instalment,
 *       never the credit balance (never more than due) - F3 {@code DR CUSTOMER_WALLET / CR <due accounts>} under
 *       {@code bnpl.repayment:<repayment>}, value-dated {@code max(today, floor)}, and {@code bnpl.InstalmentPaid}.
 *   <li>Every attempt is one {@code bnpl_collection_attempt} row; nothing collected from an instalment with something due
 *       publishes {@code bnpl.BnplCollectionFailed} (the instalment, the outcome). The closure is the step's next slot -
 *       with the wallet this step holds, so it is never locked twice.
 * </ol>
 */
@RequiredArgsConstructor
public final class BnplCollections {

    /** The event an attempt that collected nothing publishes. */
    public static final String FAILED_EVENT = "bnpl.BnplCollectionFailed";

    @NonNull private final BnplCollectionStore store;
    @NonNull private final BnplRepayments repayments;
    @NonNull private final BnplPurchaseStore purchases;
    @NonNull private final BnplWallets wallets;
    @NonNull private final BnplBook book;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * What one step's collection did - its attempts counted by outcome - the wallet it holds under (P), if it took it, and
     * the last repayment it made, if any (the one a closure in this step would name).
     */
    public record Collected(Map<CollectionOutcome, Integer> attempts, Optional<LedgerAccountId> walletHeld,
            Optional<UUID> lastRepayment) {

        public static final Collected NOTHING = new Collected(Map.of(), Optional.empty(), Optional.empty());

        public Collected {
            Objects.requireNonNull(walletHeld, "walletHeld must not be null");
            Objects.requireNonNull(lastRepayment, "lastRepayment must not be null");
            attempts = Map.copyOf(Objects.requireNonNull(attempts, "attempts must not be null"));
        }

        /** The attempts of {@code outcome}. */
        public int count(CollectionOutcome outcome) {
            return attempts.getOrDefault(outcome, 0);
        }
    }

    /**
     * The collection slot for the obligation {@code locked} holds, under the caller's B4 and on its unit of work.
     *
     * @param pinned the agreement version in force's pinned terms
     * @param rules the pinned allocation rules
     * @param today the business date of the database clock in the pinned zone, read after B4
     */
    public Collected collect(Connection unitOfWork, BnplServicingStore.Locked locked, BnplTerms pinned,
            AllocationRules rules, BnplLedgerAccounts accounts, LocalDate today, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(locked, "locked");
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(accounts, "accounts");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(correlation, "correlation");
        BnplObligation obligation = locked.obligation();
        if (!pinned.autoCollection() || obligation.status() != BnplObligationStatus.ACTIVE) {
            return Collected.NOTHING;
        }
        Map<UUID, LocalDate> latest = store.latestAttempts(unitOfWork, obligation.id());
        // Every billed instalment due by today, oldest first (the billings' value-date order) - but those the dispute hold
        // covers today (`P12-TSK-023`, ADR-0106 decision 3): a held instalment is never collected automatically, and,
        // being among the latest (the hold walks latest first), it blocks nothing younger.
        BnplDisputeHold hold = purchases.disputeHold(unitOfWork, obligation.id());
        List<BnplPurchaseStore.BilledItem> billed = purchases.billedItems(unitOfWork, obligation.id()).stream()
                .filter(item -> !item.dueDate().isAfter(today))
                .filter(item -> !hold.heldOn(item.dueDate(), today))
                .sorted(java.util.Comparator.comparing(BnplPurchaseStore.BilledItem::dueDate))
                .toList();
        if (billed.stream().noneMatch(item -> candidate(item, latest, today, pinned.collectionRetryDays()))) {
            return Collected.NOTHING;
        }
        BnplReceivables receivables = new BnplReceivables(purchases, book);
        Map<CollectionOutcome, Integer> counts = new EnumMap<>(CollectionOutcome.class);
        Optional<Optional<LedgerAccountId>> wallet = Optional.empty();
        Optional<UUID> lastRepayment = Optional.empty();
        boolean blocked = false;
        for (BnplPurchaseStore.BilledItem item : billed) {
            UUID instalment = item.instalment();
            if (!candidate(item, latest, today, pinned.collectionRetryDays())) {
                // An older instalment still due and not attempted now keeps its priority: nothing younger is collected.
                blocked |= item.dueTotal().isPositive();
                continue;
            }
            // The due state re-derived under B4 for every attempt, explained by the ledger (INV-BNPL-01, INV-BAL-05).
            BnplReceivables.Derived derived = receivables.dueState(unitOfWork, obligation.id(), accounts);
            Money zero = Money.ofPersisted(0, item.billedPrincipal().currency(), item.billedPrincipal().scale());
            Money owed = derived.dueState().items().stream().filter(due -> due.id().value().equals(instalment))
                    .findFirst().map(DueState.DueItem::total).orElse(zero);
            if (owed.isZero()) {
                record(unitOfWork, locked, instalment, today, CollectionOutcome.NOTHING_DUE, owed, zero,
                        Optional.empty(), correlation, counts);
                continue;
            }
            if (wallet.isEmpty()) {
                wallet = Optional.of(lockedWallet(unitOfWork, obligation));
            }
            if (wallet.get().isEmpty()) {
                record(unitOfWork, locked, instalment, today, CollectionOutcome.WALLET_NOT_POSTABLE, owed, zero,
                        Optional.empty(), correlation, counts);
                continue;
            }
            // (P held) What the wallet can spend, judged under its row lock and re-read after every collection (INV-BAL-04).
            Money spendable = blocked ? zero : book.availableUnderLock(unitOfWork, wallet.get().get());
            Money amount = owed.compareTo(spendable) <= 0 ? owed
                    : pinned.partialCollection() && spendable.isPositive() ? spendable : zero;
            if (!amount.isPositive()) {
                blocked = true;
                record(unitOfWork, locked, instalment, today, CollectionOutcome.INSUFFICIENT_FUNDS, owed, zero,
                        Optional.empty(), correlation, counts);
                continue;
            }
            List<AllocationLine> lines = BnplRepayments.allocate(amount, derived.dueState(), rules);
            for (AllocationLine line : lines) {
                if (line.target() != AllocationLine.Target.DUE_ITEM
                        || !line.item().orElseThrow().value().equals(instalment)) {
                    throw new AllocationAnomaly("an automatic collection pays only the instalment it was attempted on,"
                            + " never more than it has due (INV-LND-04)");
                }
            }
            BnplPurchaseStore.Repayment repayment = repayments.receive(unitOfWork, locked,
                    BnplPurchaseStore.Repayment.AUTO_COLLECTION, BnplCollectionAttempt.requestKey(instalment, today),
                    amount, lines, derived.dueState(), accounts, wallet.get().get(), today, correlation);
            CollectionOutcome outcome = amount.equals(owed) ? CollectionOutcome.COLLECTED : CollectionOutcome.PARTIAL;
            blocked |= outcome == CollectionOutcome.PARTIAL;
            record(unitOfWork, locked, instalment, today, outcome, owed, amount, Optional.of(repayment.id()), correlation,
                    counts);
            lastRepayment = Optional.of(repayment.id());
        }
        return new Collected(counts, wallet.flatMap(held -> held), lastRepayment);
    }

    /**
     * An instalment never attempted on or after today, and - on its due date always; afterwards while it has something due
     * - once its latest attempt is at least the pinned retry cadence ago (or it has none: a due date the sweep missed).
     */
    static boolean candidate(BnplPurchaseStore.BilledItem item, Map<UUID, LocalDate> latest, LocalDate today,
            int retryDays) {
        LocalDate last = latest.get(item.instalment());
        if (last != null && !last.isBefore(today)) {
            return false;
        }
        if (item.dueDate().equals(today)) {
            return true;
        }
        return item.dueTotal().isPositive() && (last == null || !last.plusDays(retryDays).isAfter(today));
    }

    /**
     * (P) The customer's existing wallet ledger row {@code FOR UPDATE} - whatever their standing, never opened
     * ({@link BnplWallets#existingWallet}) - when it can be debited now; empty when there is none or it is not
     * {@code ACTIVE}.
     */
    private Optional<LedgerAccountId> lockedWallet(Connection unitOfWork, BnplObligation obligation) {
        Optional<LedgerAccountId> wallet = wallets.existingWallet(unitOfWork, obligation.party(), obligation.currency());
        if (wallet.isEmpty()) {
            return Optional.empty();
        }
        Optional<LedgerAccountStatus> status = book.lockForUpdate(unitOfWork, wallet.get());
        return status.filter(LedgerAccountStatus.ACTIVE::equals).isPresent() ? wallet : Optional.empty();
    }

    private void record(Connection unitOfWork, BnplServicingStore.Locked locked, UUID instalment, LocalDate today,
            CollectionOutcome outcome, Money due, Money collected, Optional<UUID> repayment, CorrelationId correlation,
            Map<CollectionOutcome, Integer> counts) {
        BnplCollectionAttempt attempt = new BnplCollectionAttempt(ids.next(), locked.obligation().id(), instalment, today,
                outcome, due, collected, locked.agreementVersion(), repayment, correlation);
        store.insertAttempt(unitOfWork, attempt);
        counts.merge(outcome, 1, Integer::sum);
        if (outcome.failed()) {
            publishFailure(unitOfWork, attempt);
        }
    }

    /**
     * {@code bnpl.BnplCollectionFailed} - the attempt, its instalment, date, outcome and what was due; never the wallet or
     * the party.
     */
    private void publishFailure(Connection unitOfWork, BnplCollectionAttempt attempt) {
        EventPayload payload = EventPayload.of()
                .with("obligationId", attempt.obligation().value().toString())
                .with("attemptId", attempt.id().toString())
                .with("instalmentId", attempt.instalment().toString())
                .with("attemptDate", attempt.attemptDate().toString())
                .with("outcome", attempt.outcome().name())
                .with("currency", attempt.due().currency().code())
                .with("scale", Integer.toString(attempt.due().scale()))
                .with("dueMinor", Long.toString(attempt.due().minorUnits()));
        CorrelationId correlation = attempt.correlation();
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        FAILED_EVENT,
                        BnplTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        attempt.obligation(),
                        BnplAcceptances.OBLIGATION_AGGREGATE_TYPE,
                        clock.instant(),
                        BnplTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
