package com.finapp.app.settlement;

import com.finapp.ledger.CounterpartyClearing;
import java.util.List;

/**
 * Every counterparty position this build declares (`P9-TSK-010`, ADR-0078 sections 4 and 6),
 * read off each counterparty's own declaration - an FX provider's, a corridor's - never
 * hand-named here: the one list the startup guard ({@code CounterpartyChartGuard}) proves the
 * chart seeds and the settlement composition proves exactly one source discharges
 * ({@code INV-SET-05} per counterparty).
 *
 * <p>Since `P9-TSK-011`: every declared FX provider's position ({@code fx-sim-a}'s), read off its
 * {@code FxProviderDeclaration} - its code, its clearing purpose and its settled currencies - beside
 * ledger `V022`'s registry row and five accounts and its settlement source. A corridor provider
 * joins with `P9-TSK-014`.
 */
public final class CounterpartyClearings {

    private CounterpartyClearings() {}

    /** The declared counterparty positions, in code order. */
    public static List<CounterpartyClearing> declared() {
        return com.finapp.app.fx.FxProviderBeans.DECLARED.values().stream()
                .sorted(java.util.Comparator.comparing(com.finapp.fx.FxProviderDeclaration::code))
                .map(declaration -> new CounterpartyClearing(
                        declaration.code(),
                        com.finapp.ledger.CounterpartyKind.FX_PROVIDER,
                        declaration.clearingPurpose(),
                        declaration.settledCurrencies()))
                .toList();
    }
}
