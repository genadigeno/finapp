package com.finapp.app.credit;

import com.finapp.credit.CreditDataAnswer;
import com.finapp.credit.CreditDataPull;
import com.finapp.credit.FinancialDataProvider;
import java.util.Objects;
import java.util.Optional;

/**
 * The financial-data provider when none is configured (`P10-TSK-007`): every pull is
 * {@code Unavailable(PROVIDER_ERROR)} - fail safe, {@link UnconfiguredBureau}'s reasoning: nothing is ever data, so a
 * missing provider can approve nothing ({@code INV-CRD-10}).
 */
public final class UnconfiguredFinancialData implements FinancialDataProvider {

    /** The code its data requests and meters carry. */
    public static final String CODE = "findata-none";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public CreditDataAnswer pull(CreditDataPull request) {
        Objects.requireNonNull(request, "request");
        return new CreditDataAnswer.Unavailable(CreditDataAnswer.UnavailableCause.PROVIDER_ERROR, Optional.empty());
    }

    @Override
    public String toString() {
        return "UnconfiguredFinancialData[" + CODE + "]";
    }
}
