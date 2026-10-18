package com.finapp.bnpl;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.creditmath.DueItemId;
import com.finapp.creditmath.DueState;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * What an obligation owes, read under its row lock (B4) from its six ledger accounts and its billed items - the ONE
 * derivation every payer allocates against (`P12-TSK-015`; lending's {@code LoanReceivables}; {@code INV-BNPL-01},
 * {@code INV-BAL-05}): the credit-balance application here, the repayment (`-018`), auto-collection (`-019`), the refund's
 * split (`-021`) and the payoff (`-022`) after it.
 *
 * <ul>
 *   <li>every balance is a ledger balance derived from journal lines ({@link BnplBook#balanceOf}) - never the projection;
 *       read under B4, and every writer of an obligation's accounts holds B4, so one transaction's reads see one history;
 *   <li>the allocation engine's {@link DueState} is each billed item's billed less paid (every payer's rows, through
 *       {@code bnpl.billing_component_paid}) and the principal not yet due from {@code BNPL_PRINCIPAL} - refused
 *       ({@link Anomaly}) unless those rows explain the due accounts' ledger balances exactly: a difference is an alert,
 *       never allocated over.
 * </ul>
 *
 * <p>An item's identity in the due state is its <strong>instalment</strong> - the repayment's allocation rows name the
 * instalment (`P12-TSK-013`), one billing per instalment ({@code UNIQUE (instalment_id)}) - and {@link Derived#billingOf}
 * maps it back to the billing an application's rows name.
 */
@RequiredArgsConstructor
public final class BnplReceivables {

    @NonNull private final BnplPurchaseStore store;
    @NonNull private final BnplBook book;

    /** The due state and the billing each of its items is. */
    public record Derived(DueState dueState, Map<UUID, UUID> billingOfInstalment) {

        public Derived {
            Objects.requireNonNull(dueState, "dueState must not be null");
            billingOfInstalment = Map.copyOf(Objects.requireNonNull(billingOfInstalment, "billingOfInstalment"));
        }

        /** The billing a due item - an instalment - is. */
        public UUID billingOf(DueItemId item) {
            UUID billing = billingOfInstalment.get(item.value());
            if (billing == null) {
                throw new IllegalStateException("every due item is a billed instalment");
            }
            return billing;
        }
    }

    /** The obligation's six accounts, by purpose. */
    public BnplLedgerAccounts accounts(Connection unitOfWork, BnplObligationId obligation) {
        Map<AccountPurpose, LedgerAccount> byPurpose = new EnumMap<>(AccountPurpose.class);
        for (LedgerAccount account : book.accountsOf(unitOfWork, obligation)) {
            if (!BnplAccounts.purposes().contains(account.purpose()) || byPurpose.put(account.purpose(), account) != null) {
                throw new IllegalStateException("an obligation's accounts are its six declared purposes, each once");
            }
        }
        if (byPurpose.size() != BnplAccounts.purposes().size()) {
            throw new IllegalStateException("the acceptance opened the obligation's six accounts");
        }
        return new BnplLedgerAccounts(byPurpose.get(AccountPurpose.BNPL_PRINCIPAL),
                byPurpose.get(AccountPurpose.BNPL_PRINCIPAL_DUE), byPurpose.get(AccountPurpose.BNPL_INTEREST_ACCRUED),
                byPurpose.get(AccountPurpose.BNPL_INTEREST_DUE), byPurpose.get(AccountPurpose.BNPL_FEES_DUE),
                byPurpose.get(AccountPurpose.BNPL_CREDIT_BALANCE));
    }

    /** What the obligation holds for the customer - its credit balance, a liability: zero or positive. */
    public Money creditHeld(Connection unitOfWork, BnplLedgerAccounts accounts) {
        return book.balanceOf(unitOfWork, accounts.creditBalance());
    }

    /** The principal not yet due - {@code BNPL_PRINCIPAL}. */
    public Money principalNotDue(Connection unitOfWork, BnplLedgerAccounts accounts) {
        return book.balanceOf(unitOfWork, accounts.principal());
    }

    /** The due account a billed component's payment credits. */
    public static LedgerAccountId dueAccount(BnplLedgerAccounts accounts, AllocationComponent component) {
        return switch (component) {
            case FEES -> accounts.feesDue().id();
            case INTEREST -> accounts.interestDue().id();
            case PRINCIPAL -> accounts.principalDue().id();
        };
    }

    /**
     * The allocation engine's due state, derived under B4 from the rows and refused unless they explain the due accounts'
     * ledger balances exactly.
     *
     * @throws Anomaly the rows do not explain the ledger - nothing is allocated
     */
    public Derived dueState(Connection unitOfWork, BnplObligationId obligation, BnplLedgerAccounts accounts) {
        Money principalNotDue = principalNotDue(unitOfWork, accounts);
        Money zero = Money.ofPersisted(0, principalNotDue.currency(), principalNotDue.scale());
        Map<AllocationComponent, Money> due = new EnumMap<>(AllocationComponent.class);
        for (AllocationComponent component : AllocationComponent.values()) {
            due.put(component, zero);
        }
        List<DueState.DueItem> items = new ArrayList<>();
        Map<UUID, UUID> billingOf = new LinkedHashMap<>();
        for (BnplPurchaseStore.BilledItem item : store.billedItems(unitOfWork, obligation)) {
            for (AllocationComponent component : AllocationComponent.values()) {
                Money owed = item.due(component);
                if (owed.isNegative()) {
                    throw new Anomaly("a billed component is paid beyond what it billed - nothing is allocated"
                            + " (INV-LND-04)");
                }
                due.merge(component, owed, Money::plus);
            }
            billingOf.put(item.instalment(), item.billing());
            if (!item.dueTotal().isZero()) {
                items.add(new DueState.DueItem(DueItemId.of(item.instalment()), item.dueDate(),
                        item.due(AllocationComponent.FEES), item.due(AllocationComponent.INTEREST),
                        item.due(AllocationComponent.PRINCIPAL)));
            }
        }
        if (!due.get(AllocationComponent.FEES).equals(book.balanceOf(unitOfWork, accounts.feesDue()))
                || !due.get(AllocationComponent.INTEREST).equals(book.balanceOf(unitOfWork, accounts.interestDue()))
                || !due.get(AllocationComponent.PRINCIPAL).equals(book.balanceOf(unitOfWork, accounts.principalDue()))) {
            throw new Anomaly("the billed items do not explain the obligation's due accounts - nothing is allocated"
                    + " (INV-BNPL-01)");
        }
        if (principalNotDue.isNegative()) {
            throw new Anomaly("the principal not yet due is negative - nothing is allocated (INV-BNPL-01)");
        }
        return new Derived(new DueState(items, principalNotDue), billingOf);
    }

    /** The rows do not explain the ledger - an alert, never allocated over (`INV-BNPL-01`). */
    public static final class Anomaly extends IllegalStateException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        Anomaly(String message) {
            super(message);
        }
    }
}
