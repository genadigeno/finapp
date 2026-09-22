package com.finapp.merchant;

import com.finapp.sharedkernel.money.CurrencyCode;

/**
 * A fee schedule was asked to price, or to be assigned to, something in a currency it does not
 * hold.
 *
 * <p>Refused <strong>by name, before</strong> {@link com.finapp.sharedkernel.money.Money}
 * refuses it by type. {@code INV-MON-04} would catch it either way, but a
 * {@code CurrencyMismatchException} leaking out of an arithmetic helper tells a caller that
 * something deep went wrong; this says which two currencies disagreed and at which boundary,
 * which is what an operator assigning a GBP schedule to a EUR merchant needs to read.
 *
 * <p>Multi-currency settlement is Phase 9's (`PHASE_6_PLAN.md` §17). Until then a merchant
 * settles in one currency and is priced by a schedule that holds it.
 */
public class FeeCurrencyMismatchException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public FeeCurrencyMismatchException(CurrencyCode expected, CurrencyCode actual) {
        super(
                "this fee schedule prices in "
                        + expected
                        + " and cannot be used for "
                        + actual
                        + " (INV-MON-04; cross-currency fees are Phase 9's)");
    }
}
