package com.finapp.lending;

import com.finapp.ledger.LedgerAccount;
import java.util.List;
import java.util.Objects;

/**
 * One loan's six ledger accounts, opened together in its one currency by {@link LoanAccounts}
 * (`P11-TSK-003`; ADR-0096 section 1, {@code INV-LND-01}). Named components rather than a map, so
 * each posting rule that later debits or credits a component names it at compile time - rule 1's
 * {@link #principal()}, rule 2's {@link #interestAccrued()}, rule 5's {@link #creditBalance()}.
 *
 * <p>A value of positions, never of balances: every figure is derived from journal lines through
 * the ledger, and nothing here caches one.
 */
public record LoanLedgerAccounts(
        LedgerAccount principal,
        LedgerAccount principalDue,
        LedgerAccount interestAccrued,
        LedgerAccount interestDue,
        LedgerAccount feesDue,
        LedgerAccount creditBalance) {

    public LoanLedgerAccounts {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(principalDue, "principalDue must not be null");
        Objects.requireNonNull(interestAccrued, "interestAccrued must not be null");
        Objects.requireNonNull(interestDue, "interestDue must not be null");
        Objects.requireNonNull(feesDue, "feesDue must not be null");
        Objects.requireNonNull(creditBalance, "creditBalance must not be null");
    }

    /** The six, in {@link LoanAccounts#purposes()} order - the order they are opened in. */
    public List<LedgerAccount> all() {
        return List.of(principal, principalDue, interestAccrued, interestDue, feesDue, creditBalance);
    }
}
