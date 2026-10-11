package com.finapp.app.lending;

import com.finapp.lending.LoanPayoutDestinations;
import com.finapp.paymentmethods.PaymentMethodId;
import com.finapp.paymentmethods.PaymentMethodKind;
import com.finapp.paymentmethods.PaymentMethodStatus;
import com.finapp.paymentmethods.PaymentMethodStore;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Lending's {@link LoanPayoutDestinations} over the payment-method registry (`P11-TSK-012`; ADR-0097, ADR-0062 section 2,
 * {@code INV-RAIL-03}) - the composition root's half, because lending sees no payment module (A16).
 *
 * <p>A destination is <strong>verified</strong> when it is the caller's own payment method - {@code party_id = ?} in the
 * registry's own statement (ADR-0031) - still {@code ACTIVE}, of the {@code BANK_ACCOUNT} kind: the instrument the
 * grant exchange registered with its payee check (ADR-0062 section 2), and the one payments' withdrawal pays to. The
 * answer is the payment method's identifier and nothing else: the provider's destination reference, the display suffix
 * and the payee check stay in the registry, so no account identifier enters lending. Unknown, another party's, detached
 * and card instruments fold into the one empty answer, as payments' own instrument reads do.
 */
@RequiredArgsConstructor
public final class PaymentMethodLoanPayoutDestinations implements LoanPayoutDestinations {

    @NonNull private final PaymentMethodStore<Connection> methods;

    @Override
    public Optional<UUID> verify(Connection unitOfWork, UUID party, UUID paymentMethod) {
        PaymentMethodId id;
        try {
            id = PaymentMethodId.of(paymentMethod);
        } catch (IllegalArgumentException notAPlatformIdentifier) {
            return Optional.empty();
        }
        return methods.findOwned(unitOfWork, id, party)
                .filter(method -> method.status() == PaymentMethodStatus.ACTIVE)
                .filter(method -> method.kind() == PaymentMethodKind.BANK_ACCOUNT)
                .map(method -> paymentMethod);
    }
}
