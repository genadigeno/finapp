package com.finapp.accounts;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.io.Serial;

/**
 * The account still holds value, so the agreement cannot end (`P3-TSK-014`).
 *
 * <p>The zero-balance precondition is what makes "closed" a fact about the agreement and never
 * about money: value stranded behind a closed product would be a balance nobody can reach, which
 * is a liability the platform still owes with no product to owe it through. The message names
 * the account and the currency whose balance refused — <strong>never the amount</strong>
 * ({@code INV-AUD-02}: an exception's text reaches logs, and balances are
 * {@code RESTRICTED-FINANCIAL}).
 */
public final class AccountNotEmptyException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public AccountNotEmptyException(CustomerAccountId account, CurrencyCode currency) {
        super(
                "account " + account + " still holds a non-zero " + currency.code()
                        + " balance and cannot close");
    }
}
