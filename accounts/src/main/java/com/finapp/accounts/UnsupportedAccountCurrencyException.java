package com.finapp.accounts;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.io.Serial;

/**
 * The requested account currency is not one the platform operates in (`P3-TSK-012`).
 *
 * <p>"Supported" means <strong>postable</strong> ({@code SupportedCurrencies}, `P3-TSK-003`): a
 * currency in the set has a rounding-residual account before any allocation posts
 * ({@code INV-BAL-03}) and a suspense account before Phase 8 needs one. An account opened
 * outside it would be a product the chart cannot serve — a ledger account no posting could ever
 * balance against the platform's own side. Refused before anything is written; unlike the
 * holder refusal this one names its subject, because the currency is a value the caller chose
 * and must be able to correct (the {@code P1-TSK-026} short-password reasoning), and an ISO
 * code discloses nothing about anybody.
 */
public final class UnsupportedAccountCurrencyException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public UnsupportedAccountCurrencyException(CurrencyCode currency) {
        super("the platform does not operate accounts in " + currency.code());
    }
}
