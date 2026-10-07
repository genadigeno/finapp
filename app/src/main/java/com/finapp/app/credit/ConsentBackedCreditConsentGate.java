package com.finapp.app.credit;

import com.finapp.consent.ConsentGate;
import com.finapp.consent.ConsentPurpose;
import com.finapp.credit.CreditConsentGate;
import com.finapp.credit.CreditSourceKind;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * {@code credit}'s {@link CreditConsentGate}, answered by {@code consent}'s gate
 * ({@code P10-TSK-002}; ADR-0084 section 3, ADR-0085 section 4, {@code INV-CRD-03}).
 *
 * <p><strong>The mapping lives here, and only here.</strong> Credit asks by source kind and
 * consent answers by purpose; neither module knows the other, so the composition root owns the
 * translation: one purpose per kind, and a bureau basis never admits a financial-data pull - the
 * reason there is no combined "credit" purpose. The switch is exhaustive with no default, so a
 * third source kind does not compile until it is given its own purpose
 * ({@code CreditConsentPurposeMappingTest}).
 *
 * <p><strong>No state.</strong> The one field is the consent gate, which itself holds none:
 * every answer is {@code ConsentGate#permits} on the caller's connection, now
 * ({@code INV-CNS-03}; {@code NoProcessLocalConsentStateTest} holds this class to that).
 *
 * <p>No bean yet: wiring nothing consumes is wiring nobody can review (the P1-TSK-007 licence,
 * {@code ConsentBeans}). Its first consumer, bureau collection ({@code P10-TSK-006}), wires it.
 */
@RequiredArgsConstructor
public final class ConsentBackedCreditConsentGate implements CreditConsentGate<Connection> {

    @NonNull private final ConsentGate<Connection> consents;

    @Override
    public boolean permits(Connection unitOfWork, UUID partyId, CreditSourceKind kind) {
        return consents.permits(unitOfWork, partyId, purposeOf(kind));
    }

    /** The consent purpose that is the lawful basis for collecting this kind's data. */
    static ConsentPurpose purposeOf(CreditSourceKind kind) {
        Objects.requireNonNull(kind, "kind must not be null");
        return switch (kind) {
            case BUREAU -> ConsentPurpose.CREDIT_BUREAU_ACCESS;
            case FINANCIAL_DATA -> ConsentPurpose.FINANCIAL_DATA_ACCESS;
        };
    }
}
