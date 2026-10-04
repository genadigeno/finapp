package com.finapp.ledger;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.util.Objects;
import java.util.Set;

/**
 * One declared counterparty position: which counterparty settles on which counterparty-owned
 * purpose, in which currencies (`P9-TSK-010`, ADR-0078 sections 4 and 6). Read off the
 * counterparty's own declaration - an FX provider's, a corridor's - never hand-named; what the
 * startup guard ({@link CounterpartyChart#verify}) proves the chart seeds, and what the settlement
 * composition proves exactly one source discharges ({@code INV-SET-05}).
 *
 * @param code the counterparty's code
 * @param kind what the counterparty is - its registry row must agree
 * @param purpose a counterparty-owned purpose ({@link OwnerKind#COUNTERPARTY})
 * @param currencies the currencies it settles in; one seeded account each
 */
public record CounterpartyClearing(
        String code, CounterpartyKind kind, AccountPurpose purpose, Set<CurrencyCode> currencies) {

    public CounterpartyClearing {
        Counterparty.requireCode(code);
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(currencies, "currencies must not be null");
        if (purpose.ownerKind() != OwnerKind.COUNTERPARTY) {
            throw new IllegalArgumentException(
                    purpose + " is " + purpose.ownerKind() + "-owned: a counterparty settles only on a"
                            + " counterparty-owned purpose, never on a shared one (INV-RAIL-04)");
        }
        if (currencies.isEmpty()) {
            throw new IllegalArgumentException(
                    "counterparty '" + code + "' declares no settled currency on " + purpose);
        }
        currencies = Set.copyOf(currencies);
    }
}
