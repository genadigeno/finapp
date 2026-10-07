package com.finapp.app.credit;

import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditDataSource;
import com.finapp.credit.FinancialDataProvider;

/**
 * The financial-data provider contract (`P10-TSK-007`): {@link CreditDataSourceContract} for a
 * {@link FinancialDataProvider}, whose foreign-currency figure is its committed expenditure. Every financial-data
 * adapter's battery extends this.
 */
abstract class FinancialDataProviderContract extends CreditDataSourceContract {

    /** The financial-data adapter under test. */
    protected abstract FinancialDataProvider provider();

    @Override
    protected final CreditDataSource source() {
        return provider();
    }

    @Override
    protected final CreditAttributeCode foreignCurrencyAttribute() {
        return CreditAttributeCode.FINDATA_MONTHLY_COMMITTED_EXPENDITURE;
    }
}
