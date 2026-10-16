package com.finapp.lending;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.creditmath.AllocationEngineV1;
import com.finapp.creditmath.AllocationLine;
import com.finapp.creditmath.AllocationRules;
import com.finapp.creditmath.DueState;
import com.finapp.creditmath.OverpaymentTreatment;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.PurposeBalances;
import com.finapp.lending.LoanReceivables.Accounts;
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

/**
 * Posting rule 6 - money already held settles what became due ({@code PHASE_11_PLAN.md} section 12.8; ADR-0095 point 5;
 * {@code INV-LND-01}, {@code INV-LND-04}). Built by `P11-TSK-017` inside the billing step; lifted here by `P11-TSK-022`
 * when a line's statement became its second caller - ONE application for both billed items, never a second version.
 * Called under the account's row lock (L3), in the servicing step's transaction, right after the billed item is born.
 *
 * <p>A positive {@code LOAN_CREDIT_BALANCE} is allocated by {@link AllocationEngineV1#allocate} - the credit balance the
 * amount - over the billed items still due, oldest first, by the agreement's pinned engine, sequence and component order:
 * {@code DR LOAN_CREDIT_BALANCE / CR <due accounts>} aggregated per account, the per-item split in
 * {@code credit_balance_allocation}, under {@code lending.credit-application:<billed item>}. The due state is derived from
 * the rows under L3 and checked against the due accounts' ledger balances first ({@link LoanReceivables#dueState}).
 *
 * <p><strong>Rule 6 settles what became due, and nothing else</strong>: whatever the agreement's overpayment treatment, what
 * no billed item takes stays held. A line's {@code PAY_DOWN_PRINCIPAL} is a repayment's treatment (`P11-TSK-023`) - a credit
 * balance on a line is by construction what exceeded every receivable when it was received (A26), and rule 6's entry
 * credits due accounts only, as {@code credit_balance_allocation} records billed components only.
 *
 * <p><strong>And it settles what is ALREADY due</strong> (the Phase 11 gate's R2, {@code lending V029}): credit can arrive
 * while an item is past due - a fee refunded (rule 8a), a repayment reversed beside other credit (rule 10), a post-maturity
 * loan whose principal is paid and whose interest is due - and no billed item is born to apply it at. {@link #catchUp} is
 * the same application over the same due state, born at no item: the loan's next <em>catch-up</em> application, numbered
 * under L3 ({@code catch_up_sequence}, {@code UNIQUE (loan_id, catch_up_sequence)}) and keyed
 * {@code lending.credit-application:<loan>:catch-up:<n>}. The servicing step runs it after billing and before the
 * collection, the condition and the late fee - so no arrears the borrower's own held money covers is collected, aged or
 * charged - and the payoff before its recomputation, so the wallet is debited only what the credit does not cover.
 */
final class CreditBalanceApplications {

    /** Rule 6's key prefix: {@code lending.credit-application:<billed item>}. */
    static final String KEY_PREFIX = "lending.credit-application:";

    /** A catch-up application's key infix: {@code lending.credit-application:<loan>:catch-up:<n>}. */
    static final String CATCH_UP_INFIX = ":catch-up:";

    private final InstalmentBillingStore store;
    private final LoanReceivables receivables;
    private final PostingService postings;
    private final IdGenerator ids;

    CreditBalanceApplications(InstalmentBillingStore store, LedgerAccountStore<Connection> ledgerAccounts,
            PurposeBalances<Connection> balances, PostingService postings, IdGenerator ids) {
        this.store = Objects.requireNonNull(store, "store");
        this.receivables = new LoanReceivables(store, ledgerAccounts, balances);
        this.postings = Objects.requireNonNull(postings, "postings");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    /**
     * Applies {@code loan}'s credit balance over its billed items at the birth of {@code item} (a billing or a statement),
     * if both a credit balance and something billed exist - on the caller's unit of work, under its L3.
     *
     * @param rules the agreement's pinned allocation rules - their engine, sequence and order; the remainder stays held
     */
    Optional<CreditBalanceApplication> apply(Connection unitOfWork, Loan loan, LoanAgreement agreement,
            AllocationRules rules, UUID item, LocalDate today, Accounts accounts, Money zero) {
        return apply(unitOfWork, loan, agreement, rules, Optional.of(item), today, accounts, zero);
    }

    /**
     * Applies {@code loan}'s credit balance over what its billed items already have due, at no item's birth - the loan's
     * next catch-up application - if both a credit balance and something due exist; on the caller's unit of work, under its
     * L3 (the servicing step after billing, before the collection; the payoff before its recomputation).
     */
    Optional<CreditBalanceApplication> catchUp(Connection unitOfWork, Loan loan, LoanAgreement agreement,
            AllocationRules rules, LocalDate today, Accounts accounts, Money zero) {
        return apply(unitOfWork, loan, agreement, rules, Optional.empty(), today, accounts, zero);
    }

    private Optional<CreditBalanceApplication> apply(Connection unitOfWork, Loan loan, LoanAgreement agreement,
            AllocationRules rules, Optional<UUID> item, LocalDate today, Accounts accounts, Money zero) {
        Money credit = receivables.creditHeld(unitOfWork, accounts, zero);
        if (!credit.isPositive()) {
            return Optional.empty();
        }
        DueState dueState = receivables.dueState(unitOfWork, loan.id(), accounts, zero);
        if (!dueState.anythingBilledDue()) {
            return Optional.empty();
        }
        // What becomes due only: the remainder held, whatever the agreement's treatment of a repayment's remainder.
        AllocationRules settling = new AllocationRules(rules.engine(), rules.sequence(), rules.order(),
                OverpaymentTreatment.HOLD_AS_CREDIT);
        List<CreditBalanceApplication.Line> lines = new ArrayList<>();
        Map<AllocationComponent, Money> perComponent = new EnumMap<>(AllocationComponent.class);
        Money amount = zero;
        for (AllocationLine line : AllocationEngineV1.allocate(credit, dueState, settling)) {
            switch (line.target()) {
                case DUE_ITEM -> {
                    lines.add(new CreditBalanceApplication.Line(ids.next(), line));
                    perComponent.merge(line.component().orElseThrow(), line.amount(), Money::plus);
                    amount = amount.plus(line.amount());
                }
                case CREDIT_BALANCE -> {
                    // What stays held - nothing to record.
                }
                case NOT_DUE_PRINCIPAL -> throw new IllegalStateException(
                        "a credit-balance application never pays principal not yet due (rule 6, ADR-0095 point 5)");
            }
        }
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        UUID applicationId = ids.next();
        // At an item, keyed by it; a catch-up, by its number - the loan's latest read under L3, plus one.
        Optional<Integer> catchUp = item.isPresent()
                ? Optional.empty()
                : Optional.of(store.latestCatchUpSequence(unitOfWork, loan.id()) + 1);
        String key = item.map(billed -> KEY_PREFIX + billed)
                .orElseGet(() -> KEY_PREFIX + loan.id().value() + CATCH_UP_INFIX + catchUp.orElseThrow());
        List<JournalLine> entryLines = new ArrayList<>();
        entryLines.add(new JournalLine(accounts.creditBalance(), Direction.DEBIT, amount));
        for (Map.Entry<AllocationComponent, Money> component : perComponent.entrySet()) {
            entryLines.add(new JournalLine(accounts.due(component.getKey()), Direction.CREDIT, component.getValue()));
        }
        PostingResult result = postings.post(unitOfWork, new PostingCommand(key, today, today,
                applicationId.toString(), entryLines));
        if (result.replayed()) {
            throw new IllegalStateException("an application entry exists for a billed item or a catch-up number with no"
                    + " application (INV-LND-04)");
        }
        CreditBalanceApplication application = new CreditBalanceApplication(applicationId, item, catchUp, loan.id(), amount,
                agreement.id(), agreement.version(), agreement.allocationEngine(), lines, result.entryId());
        store.insertApplication(unitOfWork, application, loan.correlation());
        return Optional.of(application);
    }
}
