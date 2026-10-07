package com.finapp.app.credit;

import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditBureau;
import com.finapp.credit.CreditDataSource;

/**
 * The credit bureau contract (`P10-TSK-005`): {@link CreditDataSourceContract} for a {@link CreditBureau}, whose
 * foreign-currency figure is its total balance. Every bureau adapter's battery extends this.
 */
abstract class CreditBureauContract extends CreditDataSourceContract {

    /** The bureau adapter under test. */
    protected abstract CreditBureau bureau();

    @Override
    protected final CreditDataSource source() {
        return bureau();
    }

    @Override
    protected final CreditAttributeCode foreignCurrencyAttribute() {
        return CreditAttributeCode.BUREAU_TOTAL_BALANCE;
    }
}
