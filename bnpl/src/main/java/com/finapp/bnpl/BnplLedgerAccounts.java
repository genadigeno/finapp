package com.finapp.bnpl;

import com.finapp.ledger.LedgerAccount;
import java.util.List;
import java.util.Objects;

/**
 * One customer repayment obligation's six ledger accounts, opened together in its one currency by
 * {@link BnplAccounts} (`P12-TSK-003`; ADR-0103 decision 1, {@code INV-BNPL-01}). Named components rather than
 * a map, so each posting rule that later debits or credits a component names it at compile time - F1's
 * {@link #principal()}, F2's {@link #principalDue()}, F2a's {@link #interestAccrued()}, F4's
 * {@link #feesDue()}, F3's {@link #creditBalance()}.
 *
 * <p>A value of positions, never of balances: every figure is derived from journal lines through the ledger,
 * and nothing here caches one.
 */
public record BnplLedgerAccounts(
        LedgerAccount principal,
        LedgerAccount principalDue,
        LedgerAccount interestAccrued,
        LedgerAccount interestDue,
        LedgerAccount feesDue,
        LedgerAccount creditBalance) {

    public BnplLedgerAccounts {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(principalDue, "principalDue must not be null");
        Objects.requireNonNull(interestAccrued, "interestAccrued must not be null");
        Objects.requireNonNull(interestDue, "interestDue must not be null");
        Objects.requireNonNull(feesDue, "feesDue must not be null");
        Objects.requireNonNull(creditBalance, "creditBalance must not be null");
        // Each component is the purpose it is named for, so a rule naming principal() posts to BNPL_PRINCIPAL.
        List<LedgerAccount> given = List.of(principal, principalDue, interestAccrued, interestDue, feesDue, creditBalance);
        for (int i = 0; i < given.size(); i++) {
            if (given.get(i).purpose() != BnplAccounts.purposes().get(i)) {
                throw new IllegalArgumentException("component " + i + " must be the "
                        + BnplAccounts.purposes().get(i) + " account, not " + given.get(i).purpose());
            }
        }
    }

    /** The six, in {@link BnplAccounts#purposes()} order - the order they are opened in. */
    public List<LedgerAccount> all() {
        return List.of(principal, principalDue, interestAccrued, interestDue, feesDue, creditBalance);
    }
}
