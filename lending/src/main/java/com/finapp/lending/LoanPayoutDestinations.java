package com.finapp.lending;

import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

/**
 * Where an instalment loan's principal is paid out on path X (`P11-TSK-012`; {@code PHASE_11_PLAN.md} section 3.2,
 * ADR-0097, A25) - a port {@code app} implements over the payment-method registry, because lending sees neither
 * {@code paymentmethods} nor {@code payments} (A16).
 *
 * <p>Verified at the acceptance, on the caller's unit of work, never cached: the borrower's OWN external bank account,
 * live, of the bank-account kind - the instrument payments' withdrawal pays to. The answer is an <strong>opaque
 * reference</strong> - the payment method's identifier, which payments resolves to its provider's destination at dispatch
 * (`P11-TSK-015`). No account number, no provider destination reference and no display metadata ever enters lending
 * ({@code INV-RAIL-03}).
 */
public interface LoanPayoutDestinations {

    /**
     * {@code paymentMethod} as {@code party}'s verified payout destination - its opaque reference - or empty when it is
     * not the party's, not live, or not a bank account; another party's is the same empty answer as an unknown one.
     */
    Optional<UUID> verify(Connection unitOfWork, UUID party, UUID paymentMethod);
}
