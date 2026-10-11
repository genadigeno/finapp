package com.finapp.lending;

/**
 * Where the borrower would like an instalment loan's principal paid out (`P11-TSK-010`; ADR-0097, L2, A25) - a
 * PREFERENCE stated on the application. The destination itself is named and verified only at acceptance
 * (`P11-TSK-012`, {@code LoanPayoutDestinations}), as an opaque payments reference: no destination and no account
 * identifier enters lending here ({@code INV-RAIL-03}).
 *
 * <p>A credit line draws to the wallet only (A25), so its applications prefer {@link #WALLET} - refused otherwise by
 * {@link LoanApplications.Submission} and by {@code lending V004}'s CHECK.
 */
public enum PayoutPreference {

    /** Path W: the principal is credited to the borrower's wallet. */
    WALLET,

    /** Path X: the wallet, then an external payout to the borrower's own verified account (`P11-TSK-015`). */
    EXTERNAL_ACCOUNT
}
