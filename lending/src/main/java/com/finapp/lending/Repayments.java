package com.finapp.lending;

import com.finapp.ledger.AvailableBalance;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.PurposeBalances;
import com.finapp.lending.LoanReceivables.Accounts;
import com.finapp.platform.idempotency.IdempotencyKey;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The customer's repayment from their wallet - T-rep (`P11-TSK-018`; {@code PHASE_11_PLAN.md} sections 7.1, 7.2, 7.4 and
 * 12.5, posting rule 5; ADR-0095 points 1-4; {@code INV-LND-04}, {@code INV-LND-08}, {@code INV-LND-01},
 * {@code INV-IDEM-01}, {@code INV-CON-01}, {@code INV-BAL-04}, {@code INV-BAL-05}, {@code INV-LIFE-04}). The borrower repays
 * any amount; it is allocated exactly by the agreement's pinned order, once, in <strong>one transaction</strong> on the
 * caller's unit of work, which the door's idempotency claim ({@code lending.repayment:CUSTOMER:<id>}) wraps: a retried key
 * replays, and every refusal is thrown before any write, so it writes nothing and rolls the claim back with it.
 *
 * <h2>The order, which is the lock order (L3, L7, L8)</h2>
 *
 * <ol>
 *   <li><strong>(L3)</strong> the loan {@code FOR UPDATE}, owner-scoped - the serialisation point of every act on the
 *       account: two repayments, a repayment and a servicing step (billing, the credit-balance application) each wait
 *       here, and each derives after the other's commit (scenario 3);
 *   <li>a repayment already born under the customer's key converges - the same amount answers it as a replay, another
 *       amount is the key reused ({@code UNIQUE (loan_id, kind, request_key)}, the arbiter behind a claim that lapsed);
 *   <li>an {@code ACTIVE} instalment loan, or a credit line {@code ACTIVE} or {@code CLOSING} (`P11-TSK-023`) - a
 *       {@code CLOSED} or {@code CANCELLED} account and one not yet disbursed are {@code LoanNotRepayable}, nothing
 *       posted;
 *   <li>the database clock, read after the lock: the value date is its business date in the pinned zone;
 *   <li><strong>the due state</strong>, derived under L3 from the billed items (billed less paid by every payer) and
 *       refused unless they explain the due accounts' ledger balances ({@link LoanReceivables}, {@code INV-LND-01}) -
 *       never the projection ({@code INV-BAL-05}); then {@link AllocationEngineV1#allocate} by the agreement's pinned
 *       rules: billed items oldest first, {@code FEES -> INTEREST -> PRINCIPAL} within each, then the remainder by the
 *       pinned treatment - an instalment loan holds it all as credit ({@code HOLD_AS_CREDIT}); a line pays down its
 *       principal not yet due and holds only what exceeds every receivable it reaches ({@code PAY_DOWN_PRINCIPAL}, A26);
 *   <li><strong>(L7)</strong> the borrower's wallet ledger row {@code FOR UPDATE}, and {@link AvailableBalance#underLock}
 *       at least the amount ({@code INV-BAL-04}) - the wallet always after the loan. A wallet that is absent or not
 *       {@code ACTIVE} cannot fund the repayment: {@code InsufficientFunds} (ADR-0095 point 4), nothing written;
 *   <li>(L8) the projection rows of every account the transaction will post to, in the ledger's one order, when a closure
 *       will follow ({@code lockBalancesInOrder} - more than one entry); then posting rule 5 under
 *       {@code lending.repayment:<repaymentId>} - {@code DR CUSTOMER_WALLET A / CR LOAN_FEES_DUE / LOAN_INTEREST_DUE /
 *       LOAN_PRINCIPAL_DUE} per allocation, aggregated per account, {@code CR LOAN_PRINCIPAL} a line's paydown (restoring
 *       its available limit at commit), {@code CR LOAN_CREDIT_BALANCE} the rest;
 *   <li>then only inserts: the {@code repayment} row naming its entry and its allocation rows (the database holds them to
 *       the amount and no billed component above what it billed, at commit), {@code lending.RepaymentReceived};
 *   <li>and the closure, when every receivable is now zero ({@link LoanClosures}): the credit balance refunded to the
 *       wallet, {@code CLOSED (REPAID)} - or a {@code CLOSING} line's {@code CLOSED (CLOSED_BY_CUSTOMER)} -
 *       {@code lending.LoanClosed}, in this same transaction. An {@code ACTIVE} line paid to zero stays open.
 * </ol>
 *
 * <p><strong>No standing check</strong>: a suspended customer may still repay. <strong>No L0</strong>: a repayment only
 * lowers outstanding exposure (the census reads it from journal lines; a deciding transaction meanwhile sees the higher,
 * safe figure); <strong>no L6</strong>: repaid principal restores capital headroom at commit. <strong>No audit
 * record</strong>: a customer's own repayment is its row and its entry.
 */
@RequiredArgsConstructor
public final class Repayments {

    /** The event a repayment publishes. */
    public static final String RECEIVED_EVENT = "lending.RepaymentReceived";

    /** Rule 5's key prefix: one entry per repayment, whoever posts it and however often. */
    public static final String POSTING_KEY_PREFIX = "lending.repayment:";

    /** The longest key a requester may repay under: the door's own bound, which {@code lending V014} repeats. */
    public static final int MAX_REQUEST_KEY_LENGTH = IdempotencyKey.MAX_LENGTH;

    /** The newest repayments the history read answers. */
    public static final int HISTORY_LIMIT = 100;

    @NonNull private final LoanStore loans;
    @NonNull private final RepaymentStore repayments;
    @NonNull private final InstalmentBillingStore billings;
    @NonNull private final LoanTermsStore terms;
    @NonNull private final LoanWallets wallets;
    @NonNull private final AvailableBalance<Connection> available;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final PurposeBalances<Connection> balances;
    @NonNull private final PostingService postings;
    @NonNull private final LoanClosures closures;
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
     * - and the closure it brought, if it closed the loan.
     */
    public record Repaid(Repayment repayment, Optional<LoanClosure> closure, boolean replayed) {

        public Repaid {
            Objects.requireNonNull(repayment, "repayment must not be null");
            Objects.requireNonNull(closure, "closure must not be null");
        }
    }

    // ------------------------------------------------------------------ T-rep

    /**
     * Repays {@code request.amount()} from {@code party}'s wallet onto their loan {@code id}, on the caller's unit of work,
     * inside the caller's correlation and actor scopes (the postings carry both).
     *
     * @throws LoanNotFound the party has no loan with this id (another party's included)
     * @throws RepaymentKeyReused a repayment under this key exists for another amount
     * @throws LoanNotRepayable the account is neither an {@code ACTIVE} instalment loan nor a credit line {@code ACTIVE}
     *     or {@code CLOSING}
     * @throws AmountInvalid the amount is not positive, or not in the loan's currency and scale
     * @throws InsufficientFunds the wallet's available balance cannot cover the amount, or the wallet cannot be debited
     * @throws AllocationAnomaly the loan's rows do not explain its due accounts, or the engine broke its assertion
     */
    public Repaid repay(Connection unitOfWork, LoanId id, UUID party, Request request, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");

        // (L3) The loan, owner-scoped: another party's account is never locked, and is the same answer as an absent one.
        LoanStore.Owned locked = loans.lockOwnedBy(unitOfWork, id, party).orElseThrow(LoanNotFound::new);
        Loan loan = locked.loan();
        LoanAgreement agreement = locked.agreement();
        // A repayment already born under this key: the arbiter behind a claim that has lapsed.
        Optional<Repayment> born = repayments.findByKey(unitOfWork, id, RepaymentKind.CUSTOMER, request.requestKey());
        if (born.isPresent()) {
            if (!born.get().amount().equals(request.amount())) {
                throw new RepaymentKeyReused();
            }
            return new Repaid(born.get(), repayments.closedBy(unitOfWork, born.get().id()), true);
        }
        if (!repayable(loan)) {
            throw new LoanNotRepayable(loan.product());
        }
        Money amount = request.amount();
        Money principal = agreement.amount();
        if (!amount.isPositive() || !amount.currency().equals(principal.currency())
                || amount.scale() != principal.scale()) {
            throw new AmountInvalid();
        }
        // The database clock, read after the lock, in the agreement's pinned zone.
        Instant now = repayments.databaseNow(unitOfWork);
        LoanTerms pinned = terms.version(unitOfWork, agreement.termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"))
                .terms();
        ZoneId zone = ZoneId.of(pinned.servicingZone());
        LocalDate today = LocalDate.ofInstant(now, zone);

        // The due state under L3, explained by the ledger; the allocation by the pinned rules (INV-LND-01, INV-LND-04).
        Money zero = Money.ofPersisted(0, loan.currency(), principal.scale());
        LoanReceivables receivables = new LoanReceivables(billings, ledgerAccounts, balances);
        Accounts accounts = receivables.accounts(unitOfWork, id, loan.currency());
        DueState dueState = receivables.dueState(unitOfWork, id, accounts, zero);
        AllocationRules rules = AllocationRules.of(pinned);
        if (rules.engine() != agreement.allocationEngine()) {
            throw new IllegalStateException("the pinned terms and the agreement name one allocation engine (INV-LND-09)");
        }
        List<AllocationLine> lines = allocate(amount, dueState, rules);

        // (L7) The wallet's ledger row, then what it can spend, judged under it (INV-BAL-04).
        LedgerAccountId wallet = wallets.existingWallet(unitOfWork, party, loan.currency())
                .orElseThrow(InsufficientFunds::new);
        LedgerAccount walletRow = ledgerAccounts.lockForUpdate(unitOfWork, wallet)
                .orElseThrow(() -> new IllegalStateException("a resolved wallet has its ledger account row"));
        if (walletRow.status() != LedgerAccountStatus.ACTIVE
                || available.underLock(unitOfWork, wallet).compareTo(amount) < 0) {
            throw new InsufficientFunds();
        }

        return receive(unitOfWork, loan, agreement, RepaymentKind.CUSTOMER, request.requestKey(), amount, lines, accounts,
                wallet, zone, today, now, () -> actor, correlation);
    }

    /**
     * Whether the account takes a repayment: an {@code ACTIVE} instalment loan, or a credit line {@code ACTIVE} or
     * {@code CLOSING} (`P11-TSK-023` - a closing line is repaid until it closes).
     */
    static boolean repayable(Loan loan) {
        return loan.kind() == LoanKind.INSTALMENT
                ? loan.status() == LoanStatus.ACTIVE
                : loan.status() == LoanStatus.ACTIVE || loan.status() == LoanStatus.CLOSING;
    }

    /**
     * The repayment's posting half - one implementation for every kind (`P11-TSK-018`'s T-rep and `P11-TSK-019`'s
     * auto-collection step): on the caller's unit of work, under the caller's L3 on {@code loan} and L7 on {@code wallet},
     * with {@code amount} judged against the wallet's available balance under L7 and {@code lines} allocated by
     * {@link AllocationEngineV1} over the due state the caller derived under L3. (L8) the projection rows of every account
     * the transaction may post to; posting rule 5 under {@code lending.repayment:<repaymentId>}; then only inserts - the
     * repayment and its allocation rows, {@code lending.RepaymentReceived} - and the closure when every receivable is now
     * zero ({@link LoanClosures}), naming this repayment.
     *
     * @param requestKey the requester's key - the customer's idempotency key, or a collection attempt's natural key
     * @param actor the closing act's actor, asked for only when the repayment closes the loan
     */
    Repaid receive(Connection unitOfWork, Loan loan, LoanAgreement agreement, RepaymentKind kind, String requestKey,
            Money amount, List<AllocationLine> lines, Accounts accounts, LedgerAccountId wallet, ZoneId zone,
            LocalDate today, Instant now, Supplier<Actor> actor, CorrelationId correlation) {
        return receive(unitOfWork, loan, agreement, kind, requestKey, amount, lines, accounts, wallet, today, now,
                correlation, repaymentId -> closures.closeIfSettled(unitOfWork, loan, agreement, zone, today,
                        Optional.of(repaymentId), actor, correlation));
    }

    /**
     * The posting half with the closure the caller's transaction brings: {@code closer}, given this repayment's id, closes
     * the loan when it is settled - the customer's repayment and an automatic collection close it {@code REPAID} (or a
     * closing line {@code CLOSED_BY_CUSTOMER}) by {@link LoanClosures#closeIfSettled}; a payoff's one repayment
     * (`P11-TSK-026`) closes it by its payoff, always.
     */
    Repaid receive(Connection unitOfWork, Loan loan, LoanAgreement agreement, RepaymentKind kind, String requestKey,
            Money amount, List<AllocationLine> lines, Accounts accounts, LedgerAccountId wallet, LocalDate today,
            Instant now, CorrelationId correlation, Function<UUID, Optional<LoanClosure>> closer) {
        LoanId id = loan.id();
        // The value date: today, or - on a clock stepped back behind the record - the first date it leaves open (the
        // Phase 11 gate's R1): never a date already accrued, whose base read the principal without this repayment.
        LocalDate valueDate = loans.principalValueDate(unitOfWork, id, today);
        UUID repaymentId = ids.next();
        List<RepaymentAllocation> allocations = new ArrayList<>();
        for (AllocationLine line : lines) {
            allocations.add(new RepaymentAllocation(ids.next(), allocations.size() + 1, line));
        }
        List<JournalLine> entry = entryLines(amount, lines, accounts, wallet);
        // (L8) Every projection row the transaction may post to, in the ledger's one order, before the first entry - a
        // closure's refund is a second entry over the wallet and the credit balance.
        Set<LedgerAccountId> touched = new LinkedHashSet<>();
        entry.forEach(line -> touched.add(line.account()));
        touched.add(accounts.creditBalance());
        postings.lockBalancesInOrder(unitOfWork, touched);
        JournalEntryId journalEntry = post(unitOfWork, repaymentId, loan, entry, valueDate);
        Repayment unstamped = new Repayment(repaymentId, id, kind, requestKey, amount,
                agreement.id(), agreement.version(), agreement.allocationEngine(), allocations, journalEntry, valueDate,
                now);
        Instant receivedAt = repayments.insert(unitOfWork, unstamped, correlation);
        Repayment repayment = new Repayment(repaymentId, id, kind, requestKey, amount,
                agreement.id(), agreement.version(), agreement.allocationEngine(), allocations, journalEntry, valueDate,
                receivedAt);
        Optional<LoanClosure> closure = closer.apply(repaymentId);
        publish(unitOfWork, loan, repayment, closure.isPresent(), correlation);
        return new Repaid(repayment, closure, false);
    }

    /**
     * {@link AllocationEngineV1#allocate} over a due state the caller derived under L3 - a refusal of the system's own
     * inputs, or the engine breaking its assertion, is an {@link AllocationAnomaly} (nothing written), never a customer's
     * error. The amount's currency and scale are the caller's to have judged.
     */
    static List<AllocationLine> allocate(Money amount, DueState dueState, AllocationRules rules) {
        try {
            return AllocationEngineV1.allocate(amount, dueState, rules);
        } catch (AllocationEngineV1.AllocationRefused refused) {
            throw new AllocationAnomaly("the allocation engine refused a repayment's own inputs (INV-LND-04)", refused);
        } catch (IllegalStateException defect) {
            throw new AllocationAnomaly("the allocation engine broke its own assertion (INV-LND-04)", defect);
        }
    }

    /**
     * {@code party}'s loan {@code id}'s repayments, newest first - empty when the party has no such loan (another party's
     * included), so the door answers the uniform {@code 404}.
     */
    public Optional<List<Repayment>> history(Connection unitOfWork, LoanId id, UUID party) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        if (loans.ownedBy(unitOfWork, id, party).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(repayments.ownedBy(unitOfWork, id, party, HISTORY_LIMIT));
    }

    // ------------------------------------------------------------------ rule 5

    /**
     * {@code DR CUSTOMER_WALLET A}; per due account the sum of the lines paying its component; {@code CR LOAN_PRINCIPAL}
     * a line's paydown of principal not yet due (`P11-TSK-023`, A26 - an instalment loan's treatment never produces one,
     * and {@code lending V014}'s trigger refuses its row); {@code CR LOAN_CREDIT_BALANCE} what is held.
     */
    private static List<JournalLine> entryLines(Money amount, List<AllocationLine> lines, Accounts accounts,
            LedgerAccountId wallet) {
        Map<AllocationComponent, Money> perComponent = new EnumMap<>(AllocationComponent.class);
        Money held = Money.ofPersisted(0, amount.currency(), amount.scale());
        Money paidDown = held;
        for (AllocationLine line : lines) {
            switch (line.target()) {
                case DUE_ITEM -> perComponent.merge(line.component().orElseThrow(), line.amount(), Money::plus);
                case CREDIT_BALANCE -> held = held.plus(line.amount());
                case NOT_DUE_PRINCIPAL -> paidDown = paidDown.plus(line.amount());
            }
        }
        List<JournalLine> entry = new ArrayList<>();
        entry.add(new JournalLine(wallet, Direction.DEBIT, amount));
        for (Map.Entry<AllocationComponent, Money> component : perComponent.entrySet()) {
            entry.add(new JournalLine(accounts.due(component.getKey()), Direction.CREDIT, component.getValue()));
        }
        if (paidDown.isPositive()) {
            entry.add(new JournalLine(accounts.principal(), Direction.CREDIT, paidDown));
        }
        if (held.isPositive()) {
            entry.add(new JournalLine(accounts.creditBalance(), Direction.CREDIT, held));
        }
        return entry;
    }

    /**
     * Posting rule 5, dated the repayment's value date, its reference the loan - a fresh repayment id per attempt, so a replay under its key is
     * a defect, never a second posting.
     */
    private JournalEntryId post(Connection unitOfWork, UUID repaymentId, Loan loan, List<JournalLine> lines,
            LocalDate valueDate) {
        PostingResult result = postings.post(unitOfWork, new PostingCommand(POSTING_KEY_PREFIX + repaymentId, valueDate,
                valueDate, loan.id().value().toString(), lines));
        if (result.replayed()) {
            throw new IllegalStateException("a repayment's entry exists for a repayment id minted in this transaction"
                    + " (INV-IDEM-01)");
        }
        return result.entryId();
    }

    // ------------------------------------------------------------------ the event

    /**
     * {@code lending.RepaymentReceived} - the repayment, its kind, entry, amount and value date, the allocation summarised
     * per component (fees, interest and principal paid, principal paid down, held as credit) and whether it closed the
     * loan; never the wallet or the key.
     */
    private void publish(Connection unitOfWork, Loan loan, Repayment repayment, boolean closed,
            CorrelationId correlation) {
        Money amount = repayment.amount();
        EventPayload payload = EventPayload.of()
                .with("loanId", loan.id().value().toString())
                .with("repaymentId", repayment.id().toString())
                .with("kind", repayment.kind().name())
                .with("journalEntryId", repayment.journalEntry().value().toString())
                .with("valueDate", repayment.valueDate().toString())
                .with("amountMinor", Long.toString(amount.minorUnits()))
                .with("feesPaidMinor", Long.toString(repayment.paid(AllocationComponent.FEES).minorUnits()))
                .with("interestPaidMinor", Long.toString(repayment.paid(AllocationComponent.INTEREST).minorUnits()))
                .with("principalPaidMinor", Long.toString(repayment.paid(AllocationComponent.PRINCIPAL).minorUnits()))
                .with("principalPaidDownMinor",
                        Long.toString(repayment.to(AllocationLine.Target.NOT_DUE_PRINCIPAL).minorUnits()))
                .with("creditHeldMinor", Long.toString(repayment.to(AllocationLine.Target.CREDIT_BALANCE).minorUnits()))
                .with("scale", Integer.toString(amount.scale()))
                .with("currency", amount.currency().code())
                .with("agreementVersion", Integer.toString(repayment.agreementVersion()))
                .with("closed", Boolean.toString(closed));
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        RECEIVED_EVENT,
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

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** The party has no loan with this id - another party's included. */
    public static final class LoanNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        LoanNotFound() {
            super("no such loan account");
        }
    }

    /** A repayment was born under this key for another amount - the key reused after its claim lapsed. */
    public static final class RepaymentKeyReused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RepaymentKeyReused() {
            super("the key was already used for a different repayment");
        }
    }

    /** The account takes no repayment: closed, cancelled or not yet disbursed. */
    public static final class LoanNotRepayable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final LoanProduct product;

        LoanNotRepayable(LoanProduct product) {
            super("the account cannot be repaid");
            this.product = product;
        }

        /** The account's product - for the meter. */
        public LoanProduct product() {
            return product;
        }
    }

    /** The amount is not positive, or not in the loan's currency and scale. */
    public static final class AmountInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AmountInvalid() {
            super("the amount is not a positive amount in the loan's currency");
        }
    }

    /** The wallet's available balance cannot cover the amount, or the wallet cannot be debited now. */
    public static final class InsufficientFunds extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        InsufficientFunds() {
            super("the wallet cannot fund the repayment");
        }
    }
}
