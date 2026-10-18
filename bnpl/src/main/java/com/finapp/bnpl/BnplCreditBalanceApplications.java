package com.finapp.bnpl;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.creditmath.AllocationEngineV1;
import com.finapp.creditmath.AllocationLine;
import com.finapp.creditmath.AllocationRules;
import com.finapp.creditmath.OverpaymentTreatment;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.PostingCommand;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * F3a - money already held settles what is due (`P12-TSK-015`; {@code PHASE_12_PLAN.md} section 12.11 F3a; lending's
 * {@code CreditBalanceApplications} with the Phase 11 gate's R2 from the first table; {@code INV-LND-04} by analogy,
 * {@code INV-BNPL-01}). Called under the obligation's row lock (B4), in the servicing step's transaction - never on its own.
 *
 * <p>A positive {@code BNPL_CREDIT_BALANCE} is allocated by {@link AllocationEngineV1#allocate} - the credit balance the
 * amount, the pinned engine, sequence and order ({@code FEES -> INTEREST -> PRINCIPAL}) - over the billed items still due,
 * oldest first: {@code DR BNPL_CREDIT_BALANCE / CR <due accounts>} aggregated per account, the per-item split in
 * {@code bnpl_credit_balance_allocation}. What no billed item takes stays held, whatever the agreement's overpayment
 * treatment: an application settles what is due and nothing else (never principal not yet due). The due state comes from
 * {@link BnplReceivables} - rows checked against the ledger first.
 *
 * <p>Two occasions, one rule: <strong>at a billing's birth</strong> ({@link #atBilling}, key
 * {@code bnpl.credit-application:<billing>}) and, credit still held beside something already due, <strong>as a
 * catch-up</strong> ({@link #catchUp}, the obligation's next number read under B4, key
 * {@code bnpl.credit-application:<obligation>:catch-up:<n>}). Each is value-dated {@code max(today, floor)} - the floor
 * {@code bnpl.principal_value_floor} read under B4 at the application, the Phase 11 gate's R1 - posting date the step's
 * business date.
 */
@RequiredArgsConstructor
public final class BnplCreditBalanceApplications {

    /** F3a's key prefix: {@code bnpl.credit-application:<billing>}. */
    public static final String KEY_PREFIX = "bnpl.credit-application:";

    /** A catch-up's key infix: {@code bnpl.credit-application:<obligation>:catch-up:<n>}. */
    public static final String CATCH_UP_INFIX = ":catch-up:";

    @NonNull private final BnplServicingStore store;
    @NonNull private final BnplReceivables receivables;
    @NonNull private final BnplBook book;
    @NonNull private final IdGenerator ids;

    /** What the application applies over, as the servicing step holds it under B4. */
    public record Context(BnplServicingStore.Locked locked, AllocationRules rules, BnplLedgerAccounts accounts,
            LocalDate today) {

        public Context {
            Objects.requireNonNull(locked, "locked must not be null");
            Objects.requireNonNull(rules, "rules must not be null");
            Objects.requireNonNull(accounts, "accounts must not be null");
            Objects.requireNonNull(today, "today must not be null");
        }
    }

    /** At the birth of {@code billing}: the credit held over every billed item still due, if both exist. */
    public Optional<BnplCreditBalanceApplication> atBilling(Connection unitOfWork, Context context, UUID billing) {
        return apply(unitOfWork, context, Optional.of(Objects.requireNonNull(billing, "billing")));
    }

    /** At no billing's birth: the obligation's next catch-up application, if credit is held and something is due. */
    public Optional<BnplCreditBalanceApplication> catchUp(Connection unitOfWork, Context context) {
        return apply(unitOfWork, context, Optional.empty());
    }

    private Optional<BnplCreditBalanceApplication> apply(Connection unitOfWork, Context context, Optional<UUID> billing) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(context, "context");
        BnplObligationId obligation = context.locked().obligation().id();
        Money credit = receivables.creditHeld(unitOfWork, context.accounts());
        if (!credit.isPositive()) {
            return Optional.empty();
        }
        BnplReceivables.Derived derived = receivables.dueState(unitOfWork, obligation, context.accounts());
        if (!derived.dueState().anythingBilledDue()) {
            return Optional.empty();
        }
        // What is due only: the remainder held, whatever the agreement's treatment of a repayment's remainder.
        AllocationRules settling = new AllocationRules(context.rules().engine(), context.rules().sequence(),
                context.rules().order(), OverpaymentTreatment.HOLD_AS_CREDIT);
        Money zero = Money.ofPersisted(0, credit.currency(), credit.scale());
        List<BnplCreditBalanceApplication.Line> lines = new ArrayList<>();
        Map<AllocationComponent, Money> perComponent = new EnumMap<>(AllocationComponent.class);
        Money amount = zero;
        for (AllocationLine line : AllocationEngineV1.allocate(credit, derived.dueState(), settling)) {
            switch (line.target()) {
                case DUE_ITEM -> {
                    AllocationComponent component = line.component().orElseThrow();
                    lines.add(new BnplCreditBalanceApplication.Line(ids.next(),
                            derived.billingOf(line.item().orElseThrow()), component, line.amount()));
                    perComponent.merge(component, line.amount(), Money::plus);
                    amount = amount.plus(line.amount());
                }
                case CREDIT_BALANCE -> {
                    // What stays held - nothing to record.
                }
                case NOT_DUE_PRINCIPAL -> throw new IllegalStateException(
                        "a credit-balance application never pays principal not yet due (F3a)");
            }
        }
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        // The floor, read under B4 now - after any billing this step has born - and the value date never below it (R1).
        LocalDate floor = store.principalValueFloor(unitOfWork, obligation);
        LocalDate valueDate = floor.isAfter(context.today()) ? floor : context.today();
        UUID applicationId = ids.next();
        Optional<Integer> catchUp = billing.isPresent()
                ? Optional.empty()
                : Optional.of(store.latestCatchUpSequence(unitOfWork, obligation) + 1);
        String key = billing.map(at -> KEY_PREFIX + at)
                .orElseGet(() -> KEY_PREFIX + obligation.value() + CATCH_UP_INFIX + catchUp.orElseThrow());
        List<JournalLine> entryLines = new ArrayList<>();
        entryLines.add(new JournalLine(context.accounts().creditBalance().id(), Direction.DEBIT, amount));
        for (Map.Entry<AllocationComponent, Money> component : perComponent.entrySet()) {
            entryLines.add(new JournalLine(BnplReceivables.dueAccount(context.accounts(), component.getKey()),
                    Direction.CREDIT, component.getValue()));
        }
        JournalEntryId entry = book.post(unitOfWork, new PostingCommand(key, context.today(), valueDate,
                applicationId.toString(), entryLines));
        CorrelationId correlation = context.locked().obligation().correlation();
        BnplCreditBalanceApplication application = new BnplCreditBalanceApplication(applicationId, obligation, billing,
                catchUp, amount, context.locked().agreement(), context.locked().agreementVersion(),
                context.locked().allocationEngine(), valueDate, lines, entry, correlation);
        store.insertApplication(unitOfWork, application);
        return Optional.of(application);
    }
}
