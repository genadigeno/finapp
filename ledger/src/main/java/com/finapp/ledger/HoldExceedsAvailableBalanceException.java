package com.finapp.ledger;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.io.Serial;
import java.util.Objects;

/**
 * The {@code INV-BAL-04} refusal: placing this hold would make available balance negative.
 *
 * <p><strong>Names the account and the currency, never an amount</strong> — not the hold's, not
 * the settled balance, not the shortfall ({@code INV-AUD-02}: an exception message reaches
 * logs, and balances are {@code RESTRICTED-FINANCIAL}). The {@code AccountNotEmptyException}
 * discipline, applied to the refusal that will be this platform's most frequent.
 *
 * <p>No account may permit an overdrawing hold: {@code INV-BAL-04}'s "unless the account
 * explicitly permits it" has no subject — no such permission exists on any account — so the
 * refusal is unconditional, and an overdraft product is a later phase's recorded decision
 * rather than a flag smuggled in here.
 */
public final class HoldExceedsAvailableBalanceException extends RuntimeException {

    // No EntityId field: an exception is serializable and EntityId deliberately is not
    // (the P1-TSK-005 consequence, met here as by AccountNotEmptyException) - the message
    // carries the identifiers.
    @Serial private static final long serialVersionUID = 1L;

    public HoldExceedsAvailableBalanceException(LedgerAccountId account, CurrencyCode currency) {
        super(
                "a hold on account "
                        + Objects.requireNonNull(account, "account must not be null")
                        + " would make its available balance in "
                        + Objects.requireNonNull(currency, "currency must not be null").code()
                        + " negative (INV-BAL-04)");
    }
}
