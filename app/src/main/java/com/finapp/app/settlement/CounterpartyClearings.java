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
 * ledger `V022`'s registry row and five accounts and its settlement source. Since `P9-TSK-014`:
 * every declared corridor rail's position ({@code corridor-sim-a}'s), read off its rail declaration -
 * the counterparty its {@code CorridorDeclaration} names, the clearing purpose and the currencies its
 * capabilities declare - beside ledger `V024`'s registry row and three accounts and its source.
 */
public final class CounterpartyClearings {

    private CounterpartyClearings() {}

    /** The declared counterparty positions, in code order. */
    public static List<CounterpartyClearing> declared() {
        java.util.List<CounterpartyClearing> declared = new java.util.ArrayList<>(
                com.finapp.app.fx.FxProviderBeans.DECLARED.values().stream()
                        .map(declaration -> new CounterpartyClearing(
                                declaration.code(),
                                com.finapp.ledger.CounterpartyKind.FX_PROVIDER,
                                declaration.clearingPurpose(),
                                declaration.settledCurrencies()))
                        .toList());
        declared.addAll(corridors());
        declared.sort(java.util.Comparator.comparing(CounterpartyClearing::code));
        return List.copyOf(declared);
    }

    /** Every declared corridor rail's position, read off its rail declaration (`P9-TSK-014`). */
    static List<CounterpartyClearing> corridors() {
        return com.finapp.app.payments.PaymentBeans.CORRIDOR_DECLARATIONS.stream()
                .map(declaration -> {
                    com.finapp.payments.RailCapabilities capabilities =
                            com.finapp.app.payments.PaymentBeans.DECLARED_RAILS.capabilitiesOf(declaration.rail());
                    return new CounterpartyClearing(
                            declaration.counterparty(),
                            com.finapp.ledger.CounterpartyKind.CORRIDOR_PROVIDER,
                            capabilities.clearingPurpose().orElseThrow(),
                            capabilities.currencies().orElseThrow());
                })
                .toList();
    }
}
