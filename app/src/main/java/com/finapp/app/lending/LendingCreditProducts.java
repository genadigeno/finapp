package com.finapp.app.lending;

import com.finapp.credit.CreditProduct;
import com.finapp.lending.LoanProduct;
import java.util.Objects;

/**
 * Lending's one mapping onto credit's products (`P11-TSK-010`, `-006`; `P12-TSK-001`): by name, and only ever onto a
 * product of credit's {@link CreditProduct.Family#LENDING lending family}. {@code LendingVocabularyTest} holds
 * {@code LoanProduct} to exactly credit's lending products; this refuses the impossible case at the seam as well, so a
 * BNPL product - which {@code bnpl}, not lending, prices and services - can never reach a lending request, its bounds or
 * its terms.
 */
final class LendingCreditProducts {

    private LendingCreditProducts() {}

    /** The credit product {@code product} is decided as. */
    static CreditProduct of(LoanProduct product) {
        CreditProduct credit = CreditProduct.valueOf(Objects.requireNonNull(product, "product").name());
        if (credit.family() != CreditProduct.Family.LENDING) {
            throw new IllegalStateException(product + " maps onto a " + credit.family() + " product - lending decides"
                    + " only its own family's");
        }
        return credit;
    }
}
