package com.finapp.bnpl;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.creditmath.AllocationEngineV1;
import com.finapp.creditmath.AllocationLine;
import com.finapp.creditmath.AllocationRules;
import com.finapp.creditmath.DueState;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.PostingCommand;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The customer's repayment from their wallet - T-rep (`P12-TSK-018`; {@code PHASE_12_PLAN.md} sections 7.1, 7.2 and 12.11
 * F3; {@code BNPL_LIFECYCLES.md} section 4.13; owner decision B6 - the wallet is the only source; lending's
 * {@code Repayments}; {@code INV-BNPL-01}, {@code INV-LND-04} and {@code INV-LND-08} by analogy, {@code INV-IDEM-01},
 * {@code INV-CON-01}, {@code INV-BAL-04}, {@code INV-BAL-05}, {@code INV-LIFE-04}, {@code INV-BNPL-10}). The customer
 * repays any amount; it is allocated exactly by the agreement's pinned order, once, in <strong>one transaction</strong> on
 * the caller's unit of work, which the door's idempotency claim ({@code bnpl.repayment:CUSTOMER:<id>}) wraps: a retried key
 * replays, and every refusal is thrown before any write, so it writes nothing and rolls the claim back with it.
 *
 * <h2>The order, which is the Phase 12 lock order (B4, (P), L8)</h2>
 *
 * <ol>
 *   <li><strong>(B4)</strong> the obligation {@code FOR UPDATE}, owner-scoped by its agreement - the serialisation point of
 *       every act on it: two repayments, a repayment and a servicing step (billing, the credit-balance application, the
 *       collection) each wait here, and each derives after the other's commit (scenario 6);
 *   <li>a repayment already born under the customer's key converges - the same amount answers it as a replay, another
 *       amount is the key reused ({@code UNIQUE (obligation_id, kind, request_key)}, the arbiter behind a claim that
 *       lapsed);
 *   <li>an {@code ACTIVE} obligation, else {@link ObligationClosed} - nothing posted;
 *   <li>the database clock, read after the lock: today is its business date in the pinned zone;
 *   <li><strong>the due state</strong>, derived under B4 from the billed items (billed less paid by every payer) and refused
 *       unless they explain the due accounts' ledger balances ({@link BnplReceivables}) - never the projection
 *       ({@code INV-BAL-05}); then {@link AllocationEngineV1#allocate} by the pinned rules: billed items oldest first,
 *       {@code FEES -> INTEREST -> PRINCIPAL} within each, the remainder held as credit ({@code HOLD_AS_CREDIT}, both
 *       products - never principal not yet due);
 *   <li><strong>(P)</strong> the customer's <em>existing</em> {@code CUSTOMER_WALLET} row {@code FOR UPDATE} - whatever
 *       their standing, never opened - and {@code availableUnderLock} at least the amount ({@code INV-BAL-04}); the wallet
 *       alone, so no pair-order question arises (no payable is taken). Absent or not {@code ACTIVE}, or short:
 *       {@link InsufficientFunds}, nothing written;
 *   <li><strong>(L8)</strong> the projection rows of every account the transaction may post to - the wallet, the due
 *       accounts credited, the credit balance (a closure's refund is a second entry over the wallet and it) - in the
 *       ledger's one order; then F3 under {@code bnpl.repayment:<repaymentId>}: {@code DR CUSTOMER_WALLET A / CR <due
 *       accounts>} aggregated per account, {@code CR BNPL_CREDIT_BALANCE} the rest - posted on today, value-dated
 *       {@code max(today, floor)} (the Phase 11 gate's R1: never a date the record has accrued);
 *   <li>then only inserts: the repayment and its allocation rows (the database holds them to the amount and no billed
 *       component above what it billed, at commit), {@code bnpl.InstalmentPaid} for each item it settles;
 *   <li>and the closure when every receivable is now zero ({@link BnplClosures}): the credit balance refunded to the
 *       wallet, {@code CLOSED (REPAID)}, {@code bnpl.BnplAgreementClosed}, in this same transaction.
 * </ol>
 *
 * <p><strong>No standing check</strong>: a suspended customer may still repay. <strong>No L0</strong>: a repayment only
 * lowers outstanding exposure (read from journal lines at commit); <strong>no L6</strong>: repaid principal restores
 * capital headroom at commit ({@code INV-BNPL-10}). <strong>No T-acc</strong>: the value date is never before the floor,
 * so no accrued date's base moves, and a date not yet accrued later reads a base valued on or before it, which this
 * repayment is not; only the closure needs the record caught up, and it judges that itself. <strong>No audit
 * record</strong>: a customer's own repayment is its row and its entry. No JVM state.
 */
@RequiredArgsConstructor
public final class BnplRepayments {

    /** F3's key prefix (T-pur's): {@code bnpl.repayment:<repayment>}. */
    public static final String POSTING_KEY_PREFIX = BnplAcceptances.REPAYMENT_KEY_PREFIX;

    /** The longest key a requester may repay under: the door's own bound, which {@code bnpl V006} repeats. */
    public static final int MAX_REQUEST_KEY_LENGTH = com.finapp.platform.idempotency.IdempotencyKey.MAX_LENGTH;

    /** The newest repayments the history read answers. */
    public static final int HISTORY_LIMIT = 100;

    @NonNull private final BnplRepaymentStore store;
    @NonNull private final BnplServicingStore servicing;
    @NonNull private final BnplPurchaseStore purchases;
    @NonNull private final BnplTermsStore terms;
    @NonNull private final BnplWallets wallets;
    @NonNull private final BnplBook book;
    @NonNull private final BnplClosures closures;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** What the customer asks: an amount, under their own key. */
    public record Request(String requestKey, Money amount) {

        public Request {
            Objects.requireNonNull(requestKey, "requestKey must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            if (requestKey.isEmpty() || requestKey.length() > MAX_REQUEST_KEY_LENGTH) {
                throw new IllegalArgumentException("a repayment key is 1 to " + MAX_REQUEST_KEY_LENGTH + " characters");
            }
        }

        /** Never the key or the amount ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "Request[redacted]";
        }
    }

    /**
     * What the repayment committed - or, {@code replayed}, the repayment a key retried after its claim lapsed converged on
     * - and the closure it brought, if it closed the obligation.
     */
    public record Repaid(BnplPurchaseStore.Repayment repayment, Optional<BnplClosure> closure, boolean replayed) {

        public Repaid {
            Objects.requireNonNull(repayment, "repayment must not be null");
            Objects.requireNonNull(closure, "closure must not be null");
        }
    }

    // ------------------------------------------------------------------ T-rep

    /**
     * Repays {@code request.amount()} from {@code party}'s wallet onto their agreement {@code agreement}'s obligation, on the
     * caller's unit of work.
     *
     * @throws AgreementNotFound the party has no agreement with this id (another party's included)
     * @throws RepaymentKeyReused a repayment under this key exists for another amount
     * @throws ObligationClosed the obligation is {@code CLOSED}
     * @throws AmountInvalid the amount is not positive, or not in the obligation's currency and scale
     * @throws InsufficientFunds the wallet is absent, cannot be debited now, or its available balance is short
     * @throws AllocationAnomaly the obligation's rows do not explain its due accounts, or the engine broke its assertion
     */
    public Repaid repay(Connection unitOfWork, BnplAgreementId agreement, UUID party, Request request, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");

        // (B4) The obligation, owner-scoped: another party's is never locked, and is the same answer as an absent one.
        BnplServicingStore.Locked locked = store.lockOwnedBy(unitOfWork, agreement, party)
                .orElseThrow(AgreementNotFound::new);
        BnplObligation obligation = locked.obligation();
        // A repayment already born under this key: the arbiter behind a claim that has lapsed.
        Optional<BnplPurchaseStore.Repayment> born = store.findByKey(unitOfWork, obligation.id(),
                BnplPurchaseStore.Repayment.CUSTOMER, request.requestKey());
        if (born.isPresent()) {
            if (!born.get().amount().equals(request.amount())) {
                throw new RepaymentKeyReused();
            }
            return new Repaid(born.get(), store.closureOf(unitOfWork, obligation.id())
                    .filter(closure -> closure.repayment().filter(born.get().id()::equals).isPresent()), true);
        }
        if (obligation.status() != BnplObligationStatus.ACTIVE) {
            throw new ObligationClosed(obligation.product());
        }
        BnplReceivables receivables = new BnplReceivables(purchases, book);
        BnplLedgerAccounts accounts = receivables.accounts(unitOfWork, obligation.id());
        Money amount = request.amount();
        Money scaleOf = receivables.creditHeld(unitOfWork, accounts);
        if (!amount.isPositive() || !amount.currency().equals(obligation.currency())
                || amount.scale() != scaleOf.scale()) {
            throw new AmountInvalid();
        }
        // The database clock, read after the lock, in the pinned zone.
        Instant now = servicing.databaseNow(unitOfWork);
        LocalDate today = LocalDate.ofInstant(now, ZoneId.of(locked.servicingZone()));
        BnplTerms pinned = terms.version(unitOfWork, locked.termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"))
                .terms();
        if (!pinned.allocationEngine().name().equals(locked.allocationEngine())) {
            throw new IllegalStateException("the pinned terms and the agreement name one allocation engine (INV-LND-09)");
        }
        AllocationRules rules = new AllocationRules(pinned.allocationEngine(), pinned.allocationSequence(),
                pinned.allocationOrder(), pinned.overpaymentTreatment());

        // The due state under B4, explained by the ledger; the allocation by the pinned rules (INV-BNPL-01, INV-LND-04).
        BnplReceivables.Derived derived = receivables.dueState(unitOfWork, obligation.id(), accounts);
        List<AllocationLine> lines = allocate(amount, derived.dueState(), rules);

        // (P) The wallet alone - the customer's existing one, whatever their standing - then what it can spend, under it.
        LedgerAccountId wallet = wallets.existingWallet(unitOfWork, party, obligation.currency())
                .orElseThrow(() -> new InsufficientFunds(obligation.product()));
        Optional<LedgerAccountStatus> status = book.lockForUpdate(unitOfWork, wallet);
        if (status.filter(LedgerAccountStatus.ACTIVE::equals).isEmpty()
                || book.availableUnderLock(unitOfWork, wallet).compareTo(amount) < 0) {
            throw new InsufficientFunds(obligation.product());
        }

        BnplPurchaseStore.Repayment repayment = receive(unitOfWork, locked, BnplPurchaseStore.Repayment.CUSTOMER,
                request.requestKey(), amount, lines, derived.dueState(), accounts, wallet, today, correlation);

        // The closure, when this repayment brought the last receivable to zero - in this same transaction.
        Optional<BnplClosure> closure = closures.closeIfSettled(unitOfWork, new BnplClosures.Closing(locked, accounts,
                today, Optional.of(repayment.id()), Optional.of(wallet)), () -> actor, correlation);
        return new Repaid(repayment, closure, false);
    }

    /**
     * The repayment's posting half - one implementation for the customer's repayment (above) and the automatic collection
     * (`P12-TSK-019`, {@link BnplCollections}), on the caller's unit of work under its B4 and with {@code wallet} already
     * held under (P) and judged able to fund {@code amount}: <strong>(L8)</strong> the projection rows of every account the
     * entry may post to - the wallet, the due accounts credited, the credit balance - in the ledger's one order; F3 under
     * {@code bnpl.repayment:<repaymentId>} posted today and value-dated {@code max(today, floor)} (the floor read under B4,
     * the Phase 11 gate's R1); the repayment row of {@code kind} under {@code requestKey} and its allocation; and
     * {@code bnpl.InstalmentPaid} for each item of {@code before} it settles. The closure is the caller's.
     */
    BnplPurchaseStore.Repayment receive(Connection unitOfWork, BnplServicingStore.Locked locked, String kind,
            String requestKey, Money amount, List<AllocationLine> lines, DueState before, BnplLedgerAccounts accounts,
            LedgerAccountId wallet, LocalDate today, CorrelationId correlation) {
        BnplObligation obligation = locked.obligation();
        // (L8) Every projection row the transaction may post to, in the ledger's one order, before the first entry.
        List<JournalLine> entry = entryLines(amount, lines, accounts, wallet);
        Set<LedgerAccountId> touched = new LinkedHashSet<>();
        entry.forEach(line -> touched.add(line.account()));
        touched.add(accounts.creditBalance().id());
        book.lockBalancesInOrder(unitOfWork, touched);

        // F3 - posted today, value-dated never before the floor read under B4 (the Phase 11 gate's R1).
        LocalDate floor = servicing.principalValueFloor(unitOfWork, obligation.id());
        LocalDate valueDate = floor.isAfter(today) ? floor : today;
        UUID repaymentId = ids.next();
        JournalEntryId journalEntry = book.post(unitOfWork, new PostingCommand(POSTING_KEY_PREFIX + repaymentId, today,
                valueDate, obligation.id().value().toString(), entry));
        List<BnplPurchaseStore.Allocation> allocations = new ArrayList<>();
        for (AllocationLine line : lines) {
            allocations.add(new BnplPurchaseStore.Allocation(ids.next(), allocations.size() + 1, line.target().name(),
                    line.item().map(EntityId::value), line.component().map(Enum::name), line.amount()));
        }
        BnplPurchaseStore.Repayment repayment = new BnplPurchaseStore.Repayment(repaymentId, obligation.id(), kind,
                requestKey, amount, locked.agreement(), locked.agreementVersion(), locked.allocationEngine(),
                journalEntry, valueDate, allocations);
        purchases.insertRepayment(unitOfWork, repayment);
        publishPaid(unitOfWork, obligation, repayment, before, correlation);
        return repayment;
    }

    /**
     * {@code party}'s agreement {@code agreement}'s repayments, newest first - empty when the party has no such agreement
     * (another party's included), so the door answers the uniform {@code 404}.
     */
    public Optional<List<BnplPurchaseStore.Repayment>> history(Connection unitOfWork, BnplAgreementId agreement,
            UUID party) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(party, "party");
        return purchases.agreementOwnedBy(unitOfWork, agreement, party)
                .map(owned -> store.ofObligation(unitOfWork, owned.obligation().id(), HISTORY_LIMIT));
    }

    /**
     * {@link AllocationEngineV1#allocate} over a due state derived under B4 - a refusal of the system's own inputs, the
     * engine breaking its assertion, or a line paying principal not yet due (both products hold a remainder) is an
     * {@link AllocationAnomaly}, nothing written; never a customer's error.
     */
    static List<AllocationLine> allocate(Money amount, DueState dueState, AllocationRules rules) {
        List<AllocationLine> lines;
        try {
            lines = AllocationEngineV1.allocate(amount, dueState, rules);
        } catch (AllocationEngineV1.AllocationRefused refused) {
            throw new AllocationAnomaly("the allocation engine refused a repayment's own inputs (INV-LND-04)", refused);
        } catch (IllegalStateException defect) {
            throw new AllocationAnomaly("the allocation engine broke its own assertion (INV-LND-04)", defect);
        }
        Money allocated = Money.ofPersisted(0, amount.currency(), amount.scale());
        for (AllocationLine line : lines) {
            if (line.target() == AllocationLine.Target.NOT_DUE_PRINCIPAL) {
                throw new AllocationAnomaly("a BNPL repayment never pays principal not yet due - it is held"
                        + " (HOLD_AS_CREDIT, INV-LND-04)");
            }
            allocated = allocated.plus(line.amount());
        }
        if (!allocated.equals(amount)) {
            throw new AllocationAnomaly("an allocation conserves its amount exactly (INV-LND-04)");
        }
        return lines;
    }

    // ------------------------------------------------------------------ F3

    /**
     * {@code DR CUSTOMER_WALLET A}; per due account the sum of the lines paying its component; {@code CR
     * BNPL_CREDIT_BALANCE} what is held - so the entry balances by construction.
     */
    static List<JournalLine> entryLines(Money amount, List<AllocationLine> lines, BnplLedgerAccounts accounts,
            LedgerAccountId wallet) {
        Map<AllocationComponent, Money> perComponent = new EnumMap<>(AllocationComponent.class);
        Money held = Money.ofPersisted(0, amount.currency(), amount.scale());
        for (AllocationLine line : lines) {
            switch (line.target()) {
                case DUE_ITEM -> perComponent.merge(line.component().orElseThrow(), line.amount(), Money::plus);
                case CREDIT_BALANCE -> held = held.plus(line.amount());
                case NOT_DUE_PRINCIPAL -> throw new AllocationAnomaly(
                        "a BNPL repayment never pays principal not yet due (HOLD_AS_CREDIT)");
            }
        }
        List<JournalLine> entry = new ArrayList<>();
        entry.add(new JournalLine(wallet, Direction.DEBIT, amount));
        for (Map.Entry<AllocationComponent, Money> component : perComponent.entrySet()) {
            entry.add(new JournalLine(BnplReceivables.dueAccount(accounts, component.getKey()), Direction.CREDIT,
                    component.getValue()));
        }
        if (held.isPositive()) {
            entry.add(new JournalLine(accounts.creditBalance().id(), Direction.CREDIT, held));
        }
        return entry;
    }

    // ------------------------------------------------------------------ the event

    /**
     * {@code bnpl.InstalmentPaid} for each billed item this repayment settles - the item's due state before it was positive
     * and this repayment's lines pay it all - with what it paid of each component; never the wallet, the key or the party.
     */
    private void publishPaid(Connection unitOfWork, BnplObligation obligation, BnplPurchaseStore.Repayment repayment,
            DueState before, CorrelationId correlation) {
        Map<UUID, Map<AllocationComponent, Money>> paid = new LinkedHashMap<>();
        Money zero = Money.ofPersisted(0, repayment.amount().currency(), repayment.amount().scale());
        for (BnplPurchaseStore.Allocation line : repayment.allocations()) {
            if (line.instalment().isPresent()) {
                paid.computeIfAbsent(line.instalment().get(), any -> new EnumMap<>(AllocationComponent.class))
                        .merge(AllocationComponent.valueOf(line.component().orElseThrow()), line.amount(), Money::plus);
            }
        }
        if (paid.isEmpty()) {
            return;
        }
        // The sequence by due date: a refund's schedule version carries a billed instalment as a new row (`P12-TSK-021`).
        Map<LocalDate, Integer> sequenceOn = new HashMap<>();
        servicing.latestSchedule(unitOfWork, obligation.id()).ifPresent(schedule -> schedule.instalments()
                .forEach(instalment -> sequenceOn.put(instalment.dueDate(), instalment.sequence())));
        for (DueState.DueItem item : before.items()) {
            UUID instalment = item.id().value();
            Map<AllocationComponent, Money> components = paid.get(instalment);
            if (components == null) {
                continue;
            }
            Money fees = components.getOrDefault(AllocationComponent.FEES, zero);
            Money interest = components.getOrDefault(AllocationComponent.INTEREST, zero);
            Money principal = components.getOrDefault(AllocationComponent.PRINCIPAL, zero);
            if (!fees.equals(item.fees()) || !interest.equals(item.interest()) || !principal.equals(item.principal())) {
                continue; // Partly paid: still due - not settled by this repayment.
            }
            Money total = fees.plus(interest).plus(principal);
            EventPayload payload = EventPayload.of()
                    .with("obligationId", obligation.id().value().toString())
                    .with("instalmentId", instalment.toString())
                    .with("sequence", Integer.toString(sequenceOn.getOrDefault(item.dueDate(), 0)))
                    .with("repaymentId", repayment.id().toString())
                    .with("currency", total.currency().code())
                    .with("scale", Integer.toString(total.scale()))
                    .with("amountMinor", Long.toString(total.minorUnits()))
                    .with("feesMinor", Long.toString(fees.minorUnits()))
                    .with("interestMinor", Long.toString(interest.minorUnits()))
                    .with("principalMinor", Long.toString(principal.minorUnits()))
                    .with("journalEntryId", repayment.entry().value().toString());
            outbox.write(
                    unitOfWork,
                    new EventEnvelope(
                            EventId.next(ids),
                            BnplAcceptances.INSTALMENT_PAID_EVENT,
                            BnplTermsAdministration.EVENT_VERSION,
                            EventEnvelope.CURRENT_SCHEMA_VERSION,
                            obligation.id(),
                            BnplAcceptances.OBLIGATION_AGGREGATE_TYPE,
                            clock.instant(),
                            BnplTermsAdministration.PRODUCER,
                            correlation,
                            CausationId.of(correlation.value())),
                    payload.toBytes(),
                    EventPayload.MEDIA_TYPE);
        }
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** The party has no agreement with this id - another party's included. */
    public static final class AgreementNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AgreementNotFound() {
            super("no such BNPL agreement");
        }
    }

    /** A repayment was born under this key for another amount - the key reused after its claim lapsed. */
    public static final class RepaymentKeyReused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RepaymentKeyReused() {
            super("the key was already used for a different repayment");
        }
    }

    /** The amount is not positive, or not in the obligation's currency and scale. */
    public static final class AmountInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AmountInvalid() {
            super("the amount is not a positive amount in the agreement's currency");
        }
    }

    /** A refusal naming the obligation's product - for the meter; never an amount in the message. */
    public abstract static class Refused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final BnplProduct product;

        Refused(BnplProduct product, String message) {
            super(message);
            this.product = Objects.requireNonNull(product, "product");
        }

        /** The obligation's product - for the meter. */
        public BnplProduct product() {
            return product;
        }
    }

    /** The obligation is {@code CLOSED} - a repayment on it is refused, nothing posted ({@code INV-LIFE-04}). */
    public static final class ObligationClosed extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ObligationClosed(BnplProduct product) {
            super(product, "the BNPL agreement is closed; nothing was posted");
        }
    }

    /** The wallet is absent, cannot be debited now, or its available balance cannot cover the amount. */
    public static final class InsufficientFunds extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        InsufficientFunds(BnplProduct product) {
            super(product, "the wallet cannot fund the repayment now; nothing was posted");
        }
    }
}
