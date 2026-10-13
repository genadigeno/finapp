package com.finapp.lending;

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
 */
final class CreditBalanceApplications {

    /** Rule 6's key prefix: {@code lending.credit-application:<billed item>}. */
    static final String KEY_PREFIX = "lending.credit-application:";

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
        List<JournalLine> entryLines = new ArrayList<>();
        entryLines.add(new JournalLine(accounts.creditBalance(), Direction.DEBIT, amount));
        for (Map.Entry<AllocationComponent, Money> component : perComponent.entrySet()) {
            entryLines.add(new JournalLine(accounts.due(component.getKey()), Direction.CREDIT, component.getValue()));
        }
        PostingResult result = postings.post(unitOfWork, new PostingCommand(KEY_PREFIX + item, today, today,
                applicationId.toString(), entryLines));
        if (result.replayed()) {
            throw new IllegalStateException("an application entry exists for a billed item with no application (INV-LND-04)");
        }
        CreditBalanceApplication application = new CreditBalanceApplication(applicationId, item, loan.id(), amount,
                agreement.id(), agreement.version(), agreement.allocationEngine(), lines, result.entryId());
        store.insertApplication(unitOfWork, application, loan.correlation());
        return Optional.of(application);
    }
}
