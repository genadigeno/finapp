package com.finapp.lending;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PurposeBalances;
import com.finapp.lending.InstalmentBillingStore.BilledItem;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What a loan owes, read under its row lock (L3) from its six ledger accounts and its billed items - the one derivation
 * every payer allocates against (`P11-TSK-017` built it inside the billing step; `P11-TSK-018` lifted it here when the
 * repayment became its second caller, and the closure its third). {@code INV-LND-01}, {@code INV-BAL-05}.
 *
 * <ul>
 *   <li>every balance is DR - CR over the account's journal lines, folded through {@link Money} - never the projection;
 *       read under L3, and every writer of a loan's accounts holds L3, so the reads of one transaction see one history;
 *   <li>the allocation engine's {@link DueState} is each billed item's billed less paid (every payer's rows, through
 *       {@code lending.billing_component_paid}) and the principal not yet due from {@code LOAN_PRINCIPAL} - and is refused
 *       ({@link AllocationAnomaly}) unless those rows explain the due accounts' ledger balances exactly: a difference is
 *       an alert, never allocated over.
 * </ul>
 */
final class LoanReceivables {

    private final InstalmentBillingStore billings;
    private final LedgerAccountStore<Connection> ledgerAccounts;
    private final PurposeBalances<Connection> balances;

    LoanReceivables(InstalmentBillingStore billings, LedgerAccountStore<Connection> ledgerAccounts,
            PurposeBalances<Connection> balances) {
        this.billings = Objects.requireNonNull(billings, "billings");
        this.ledgerAccounts = Objects.requireNonNull(ledgerAccounts, "ledgerAccounts");
        this.balances = Objects.requireNonNull(balances, "balances");
    }

    /** The loan's six accounts, resolved once a transaction. */
    record Accounts(
            LedgerAccountId principal,
            LedgerAccountId principalDue,
            LedgerAccountId interestAccrued,
            LedgerAccountId interestDue,
            LedgerAccountId feesDue,
            LedgerAccountId creditBalance) {

        /** The due account a billed component's payment credits. */
        LedgerAccountId due(AllocationComponent component) {
            return switch (component) {
                case FEES -> feesDue;
                case INTEREST -> interestDue;
                case PRINCIPAL -> principalDue;
            };
        }

        /** The five receivables - every asset account of the loan. */
        List<LedgerAccountId> receivables() {
            return List.of(principal, principalDue, interestAccrued, interestDue, feesDue);
        }
    }

    Accounts accounts(Connection unitOfWork, LoanId loan, CurrencyCode currency) {
        return new Accounts(
                owned(unitOfWork, loan, AccountPurpose.LOAN_PRINCIPAL, currency),
                owned(unitOfWork, loan, AccountPurpose.LOAN_PRINCIPAL_DUE, currency),
                owned(unitOfWork, loan, AccountPurpose.LOAN_INTEREST_ACCRUED, currency),
                owned(unitOfWork, loan, AccountPurpose.LOAN_INTEREST_DUE, currency),
                owned(unitOfWork, loan, AccountPurpose.LOAN_FEES_DUE, currency),
                owned(unitOfWork, loan, AccountPurpose.LOAN_CREDIT_BALANCE, currency));
    }

    /** DR - CR over one of the loan's own accounts, folded through {@link Money} from its journal lines. */
    Money netDebit(Connection unitOfWork, LedgerAccountId account, Money zero) {
        Money net = zero;
        for (PurposeBalances.EntryLine line : balances.linesOn(unitOfWork, account)) {
            net = line.direction() == Direction.DEBIT ? net.plus(line.amount()) : net.minus(line.amount());
        }
        return net;
    }

    /** What the loan holds for the borrower - the credit balance, a liability: CR - DR, zero or positive. */
    Money creditHeld(Connection unitOfWork, Accounts accounts, Money zero) {
        return netDebit(unitOfWork, accounts.creditBalance(), zero).negated();
    }

    /** Whether every receivable of the loan - its five asset accounts - is zero, from the ledger. */
    boolean everyReceivableZero(Connection unitOfWork, Accounts accounts, Money zero) {
        for (LedgerAccountId receivable : accounts.receivables()) {
            if (!netDebit(unitOfWork, receivable, zero).isZero()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The allocation engine's due state, derived under L3 from the rows - each billed item's billed less paid, the
     * principal not yet due from {@code LOAN_PRINCIPAL} - and refused unless the rows explain the due accounts' ledger
     * balances exactly ({@code INV-LND-01}: a difference is an alert, never allocated over).
     *
     * @throws AllocationAnomaly the rows do not explain the ledger
     */
    DueState dueState(Connection unitOfWork, LoanId loan, Accounts accounts, Money zero) {
        List<DueState.DueItem> items = new ArrayList<>();
        Money fees = zero;
        Money interest = zero;
        Money principal = zero;
        for (BilledItem item : billings.billedItems(unitOfWork, loan)) {
            DueState.DueItem due = item.dueItem();
            fees = fees.plus(due.fees());
            interest = interest.plus(due.interest());
            principal = principal.plus(due.principal());
            if (!due.total().isZero()) {
                items.add(due);
            }
        }
        if (!fees.equals(netDebit(unitOfWork, accounts.feesDue(), zero))
                || !interest.equals(netDebit(unitOfWork, accounts.interestDue(), zero))
                || !principal.equals(netDebit(unitOfWork, accounts.principalDue(), zero))) {
            throw new AllocationAnomaly(
                    "the billed items do not explain the loan's due accounts - nothing is allocated (INV-LND-01)");
        }
        return new DueState(items, netDebit(unitOfWork, accounts.principal(), zero));
    }

    private LedgerAccountId owned(Connection unitOfWork, LoanId loan, AccountPurpose purpose, CurrencyCode currency) {
        return ledgerAccounts.findOwned(unitOfWork, loan.value(), purpose, currency)
                .orElseThrow(() -> new IllegalStateException("the acceptance opened the loan's six accounts"))
                .id();
    }
}
