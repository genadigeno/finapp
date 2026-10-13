package com.finapp.lending;

import com.finapp.ledger.AvailableBalance;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PurposeBalances;
import com.finapp.lending.InstalmentBillingStore.BilledItem;
import com.finapp.lending.LoanReceivables.Accounts;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Scheduled auto-collection - the servicing step's collection step (`P11-TSK-019`; {@code PHASE_11_PLAN.md} sections 7.1
 * T-svc, 7.4 and 12.5, posting rule 5; ADR-0095 point 6, A21; {@code INV-IDEM-02}, {@code INV-LND-04}, {@code INV-BAL-04}).
 * Called by {@link LoanServicing} after the accrual has caught up and the billing (with its credit-balance application) has
 * run, under the step's loan row lock (L3), on the step's unit of work - so the attempts, any repayments, their entries and
 * events commit with the rest of the step, or none of it does. A system act with no door: the authority is the agreement's
 * pinned terms ({@code auto_collection}) and the repayment method the borrower chose (the wallet), accepted with the
 * agreement.
 *
 * <h2>The rule</h2>
 *
 * <ol>
 *   <li>Only an {@code ACTIVE} instalment loan whose pinned terms collect automatically (a line's statement minimum joins with
 *       `P11-TSK-022`).
 *   <li>The candidates: every billed item due on or before today, oldest first, not yet attempted today
 *       ({@code UNIQUE (due_item_id, attempt_date)} beneath L3) - an item on its due date always (a settled one records
 *       {@code NOTHING_DUE}), a past-due one while it still has something due (retried on each later business date).
 *   <li>Each attempt re-derives the due state under L3 ({@link LoanReceivables}, explained by the ledger) and, (L7) once a
 *       step, the borrower's existing wallet row {@code FOR UPDATE}; {@link AvailableBalance#underLock} re-read per attempt.
 *       No wallet, or not {@code ACTIVE}: {@code WALLET_NOT_POSTABLE}.
 *   <li>The amount: what the item has due - or, the pinned terms allowing partial collection, as much of it as is available;
 *       otherwise nothing ({@code INSUFFICIENT_FUNDS}). Oldest first: once an item is not collected in full, every younger
 *       item that day is {@code INSUFFICIENT_FUNDS} - a younger item's collection would be allocated to the older one.
 *   <li>Money moves only through {@link Repayments}' one posting half: kind {@code AUTO_COLLECTION}, the attempt's natural
 *       key {@code <item>:<date>} as the requester's key, allocated by {@link AllocationEngineV1} - every line on this item,
 *       never the credit balance (never more than due) - posting rule 5, {@code lending.RepaymentReceived}, and the closure
 *       when the collection settles the loan (then the step stops: a closed loan takes no attempt).
 *   <li>Every attempt is one {@code collection_attempt} row; nothing collected from an item with something due publishes
 *       {@code lending.CollectionFailed} (the item, the outcome).
 * </ol>
 */
@RequiredArgsConstructor
public final class LoanCollections {

    /** The event an attempt that collected nothing publishes. */
    public static final String FAILED_EVENT = "lending.CollectionFailed";

    @NonNull private final RepaymentStore store;
    @NonNull private final Repayments repayments;
    @NonNull private final InstalmentBillingStore billings;
    @NonNull private final LoanWallets wallets;
    @NonNull private final AvailableBalance<Connection> available;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final PurposeBalances<Connection> balances;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** What one step's collection did - its attempts counted by outcome - and the closure a collection brought, if one. */
    public record Collected(Map<CollectionOutcome, Integer> attempts, Optional<LoanClosure> closure) {

        public static final Collected NOTHING = new Collected(Map.of(), Optional.empty());

        public Collected {
            Objects.requireNonNull(closure, "closure must not be null");
            attempts = Map.copyOf(Objects.requireNonNull(attempts, "attempts must not be null"));
        }

        /** The attempts of {@code outcome}. */
        public int count(CollectionOutcome outcome) {
            return attempts.getOrDefault(outcome, 0);
        }
    }

    /**
     * The collection step for {@code loan}, under the caller's L3 and on its unit of work.
     *
     * @param pinned the governing agreement's pinned terms
     * @param today the business date of the database clock in the pinned zone, read after L3
     * @param now the database clock, read after L3
     * @param actor the closing act's actor - asked for only when a collection closes the loan
     */
    public Collected collect(Connection unitOfWork, Loan loan, LoanAgreement agreement, LoanTerms pinned, LocalDate today,
            Instant now, Supplier<Actor> actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        if (!pinned.autoCollection() || loan.kind() != LoanKind.INSTALMENT || loan.status() != LoanStatus.ACTIVE) {
            return Collected.NOTHING;
        }
        Set<DueItemId> attempted = store.attemptedOn(unitOfWork, loan.id(), today);
        List<BilledItem> due = billings.billedItems(unitOfWork, loan.id()).stream()
                .filter(item -> !item.billing().dueDate().isAfter(today))
                .toList();
        if (due.stream().noneMatch(item -> candidate(item, attempted, today))) {
            return Collected.NOTHING;
        }
        AllocationRules rules = AllocationRules.of(pinned);
        if (rules.engine() != agreement.allocationEngine()) {
            throw new IllegalStateException("the pinned terms and the agreement name one allocation engine (INV-LND-09)");
        }
        Money zero = Money.ofPersisted(0, loan.currency(), agreement.amount().scale());
        LoanReceivables receivables = new LoanReceivables(billings, ledgerAccounts, balances);
        Accounts accounts = receivables.accounts(unitOfWork, loan.id(), loan.currency());

        Map<CollectionOutcome, Integer> counts = new EnumMap<>(CollectionOutcome.class);
        Optional<Optional<LedgerAccountId>> wallet = Optional.empty();
        boolean blocked = false;
        for (BilledItem item : due) {
            DueItemId id = DueItemId.of(item.billing().id());
            if (!candidate(item, attempted, today)) {
                // An older item attempted earlier today and still due keeps its priority: nothing younger is collected.
                blocked |= item.outstanding().isPositive();
                continue;
            }
            // The due state re-derived under L3 for every attempt, explained by the ledger (INV-LND-01, INV-BAL-05).
            DueState state = receivables.dueState(unitOfWork, loan.id(), accounts, zero);
            Money owed = state.items().stream().filter(dueItem -> dueItem.id().equals(id)).findFirst()
                    .map(DueState.DueItem::total).orElse(zero);
            if (owed.isZero()) {
                record(unitOfWork, loan, id, today, CollectionOutcome.NOTHING_DUE, owed, zero, Optional.empty(),
                        correlation, counts);
                continue;
            }
            if (wallet.isEmpty()) {
                wallet = Optional.of(lockedWallet(unitOfWork, loan));
            }
            if (wallet.get().isEmpty()) {
                record(unitOfWork, loan, id, today, CollectionOutcome.WALLET_NOT_POSTABLE, owed, zero, Optional.empty(),
                        correlation, counts);
                continue;
            }
            // (L7 held) What the wallet can spend, judged under its row lock and re-read after every collection (INV-BAL-04).
            Money spendable = blocked ? zero : available.underLock(unitOfWork, wallet.get().get());
            Money amount = owed.compareTo(spendable) <= 0 ? owed
                    : pinned.partialCollection() && spendable.isPositive() ? spendable : zero;
            if (!amount.isPositive()) {
                blocked = true;
                record(unitOfWork, loan, id, today, CollectionOutcome.INSUFFICIENT_FUNDS, owed, zero, Optional.empty(),
                        correlation, counts);
                continue;
            }
            List<AllocationLine> lines = Repayments.allocate(amount, state, rules);
            for (AllocationLine line : lines) {
                if (line.target() != AllocationLine.Target.DUE_ITEM || !line.item().orElseThrow().equals(id)) {
                    throw new AllocationAnomaly("an automatic collection pays only the item it was attempted on, never"
                            + " more than it has due (INV-LND-04)");
                }
            }
            Repayments.Repaid repaid = repayments.receive(unitOfWork, loan, agreement, RepaymentKind.AUTO_COLLECTION,
                    CollectionAttempt.requestKey(id, today), amount, lines, accounts, wallet.get().get(), today, now, actor,
                    correlation);
            CollectionOutcome outcome = amount.equals(owed) ? CollectionOutcome.COLLECTED : CollectionOutcome.PARTIAL;
            blocked |= outcome == CollectionOutcome.PARTIAL;
            record(unitOfWork, loan, id, today, outcome, owed, amount, Optional.of(repaid.repayment().id()), correlation,
                    counts);
            if (repaid.closure().isPresent()) {
                // The collection settled the loan and closed it (its own attempt, just recorded, is the one lending V015
                // admits on the loan it closed): a closed loan takes no further attempt.
                return new Collected(counts, repaid.closure());
            }
        }
        return new Collected(counts, Optional.empty());
    }

    /** An item on its due date always; a past-due one while it has something due - each once a business date. */
    private static boolean candidate(BilledItem item, Set<DueItemId> attempted, LocalDate today) {
        return !attempted.contains(DueItemId.of(item.billing().id()))
                && (item.billing().dueDate().equals(today) || item.outstanding().isPositive());
    }

    /**
     * (L7) The borrower's existing wallet ledger row {@code FOR UPDATE} - whatever their standing, never opened
     * ({@link LoanWallets#existingWallet}) - when it can be debited now; empty when there is none or it is not
     * {@code ACTIVE}.
     */
    private Optional<LedgerAccountId> lockedWallet(Connection unitOfWork, Loan loan) {
        Optional<LedgerAccountId> wallet = wallets.existingWallet(unitOfWork, loan.party(), loan.currency());
        if (wallet.isEmpty()) {
            return Optional.empty();
        }
        Optional<LedgerAccount> row = ledgerAccounts.lockForUpdate(unitOfWork, wallet.get());
        return row.isPresent() && row.get().status() == LedgerAccountStatus.ACTIVE ? wallet : Optional.empty();
    }

    private void record(Connection unitOfWork, Loan loan, DueItemId item, LocalDate today, CollectionOutcome outcome,
            Money due, Money collected, Optional<UUID> repayment, CorrelationId correlation,
            Map<CollectionOutcome, Integer> counts) {
        CollectionAttempt attempt = new CollectionAttempt(ids.next(), loan.id(), item, today, outcome, due, collected,
                repayment);
        store.insertAttempt(unitOfWork, attempt, correlation);
        counts.merge(outcome, 1, Integer::sum);
        if (outcome.failed()) {
            publishFailure(unitOfWork, loan, attempt, correlation);
        }
    }

    /** {@code lending.CollectionFailed} - the attempt, its billed item, date, outcome and what was due; never the wallet. */
    private void publishFailure(Connection unitOfWork, Loan loan, CollectionAttempt attempt, CorrelationId correlation) {
        EventPayload payload = EventPayload.of()
                .with("loanId", loan.id().value().toString())
                .with("attemptId", attempt.id().toString())
                .with("dueItemId", attempt.dueItem().value().toString())
                .with("attemptDate", attempt.attemptDate().toString())
                .with("outcome", attempt.outcome().name())
                .with("dueMinor", Long.toString(attempt.due().minorUnits()))
                .with("scale", Integer.toString(attempt.due().scale()))
                .with("currency", attempt.due().currency().code());
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        FAILED_EVENT,
                        LoanTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        loan.id(),
                        LoanAcceptances.LOAN_AGGREGATE_TYPE,
                        clock.instant(),
                        LoanTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
