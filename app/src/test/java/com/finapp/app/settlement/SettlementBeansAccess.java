package com.finapp.app.settlement;

import com.finapp.payments.PaymentRails;
import com.finapp.settlement.SettlementSources;

/** The composed register, for tests outside this package (`P9-TSK-026`). */
public final class SettlementBeansAccess {

    private SettlementBeansAccess() {}

    public static SettlementSources composed(PaymentRails rails) {
        return SettlementBeans.composedSettlementSources(rails);
    }
}
