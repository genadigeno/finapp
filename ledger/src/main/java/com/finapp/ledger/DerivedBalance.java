package com.finapp.ledger;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;

/**
 * The settled balance of one account, derived from its posted lines (`P3-TSK-008`).
 *
 * <p><strong>Signed by the account's normal balance</strong>: positive means the account has
 * grown in its own terms, and negative is a legal state, not an error — an asset posted mostly
 * by credit reads negative, and refusing that here would be an overdraft policy wearing an
 * accounting identity's clothes (holds and available balance are {@code P3-TSK-015}'s).
 *
 * <p>The currency travels inside {@link Money}, never beside it: an account with no postings is
 * zero <em>in its own currency</em>, so even the empty answer says what kind of number it is
 * ({@code INV-MON-02}).
 *
 * <p>Deliberately only the settled number ({@code LEDGER_MODEL.md} §4's first row). Pending,
 * holds and available are distinct balances with named owners, and a field here for one of
 * them would be a column nothing populates carrying confident javadoc.
 */
public record DerivedBalance(LedgerAccountId account, Money settled) {

    public DerivedBalance {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(settled, "settled must not be null");
    }
}
