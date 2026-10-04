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
 * <p><strong>Empty until the first counterparty is admitted</strong>: `P9-TSK-011` adds
 * {@code fx-sim-a}'s, from {@code FxProviderDeclaration}, beside ledger `V022`'s registry row
 * and five accounts and its settlement source. The mechanism is proven now against planted
 * declarations.
 */
public final class CounterpartyClearings {

    private CounterpartyClearings() {}

    /** The declared counterparty positions, in declaration order. */
    public static List<CounterpartyClearing> declared() {
        return List.of();
    }
}
